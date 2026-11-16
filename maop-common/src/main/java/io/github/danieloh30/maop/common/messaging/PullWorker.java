package io.github.danieloh30.maop.common.messaging;

import io.github.danieloh30.maop.common.events.CloudEvent;
import io.github.danieloh30.maop.common.events.CloudEventCodec;
import io.github.danieloh30.maop.common.tracing.Tracing;
import io.nats.client.JetStreamSubscription;
import io.nats.client.Message;
import io.nats.client.PullSubscribeOptions;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.jboss.logging.Logger;

/**
 * Pull-consumer loop on virtual threads with bounded concurrency, in-progress acknowledgements while a handler
 * runs, bounded redelivery (nak with 2 s then 5 s delay), final-delivery dead-lettering, and graceful drain.
 */
public final class PullWorker implements AutoCloseable {

    private static final Logger LOG = Logger.getLogger(PullWorker.class);

    private final JetStreamClient client;
    private final ConsumerSpec spec;
    private final int concurrency;
    private final MessageHandler handler;
    private final Semaphore permits;
    private final AtomicBoolean running = new AtomicBoolean();
    private final Set<Message> inFlight = ConcurrentHashMap.newKeySet();
    private final ScheduledExecutorService heartbeats = Executors.newSingleThreadScheduledExecutor(
            Thread.ofPlatform().daemon().name("maop-inprogress").factory());
    private Thread loop;

    public PullWorker(JetStreamClient client, ConsumerSpec spec, int concurrency, MessageHandler handler) {
        this.client = client;
        this.spec = spec;
        this.concurrency = concurrency;
        this.handler = handler;
        this.permits = new Semaphore(concurrency);
    }

    public synchronized void start() {
        if (running.getAndSet(true)) {
            return;
        }
        loop = Thread.ofVirtual().name("maop-pull-" + spec.durable()).start(this::run);
    }

    public int activeTasks() {
        return inFlight.size();
    }

    private void run() {
        JetStreamSubscription sub = null;
        while (running.get()) {
            try {
                if (sub == null) {
                    sub = client.jetStream().subscribe(null, PullSubscribeOptions.bind(spec.stream(), spec.durable()));
                }
                permits.acquire();
                int extra = permits.drainPermits();
                int batch = 1 + extra;
                List<Message> msgs = sub.fetch(batch, Duration.ofSeconds(1));
                int unused = batch - msgs.size();
                if (unused > 0) {
                    permits.release(unused);
                }
                for (Message m : msgs) {
                    inFlight.add(m);
                    Thread.ofVirtual().name("maop-handle-" + spec.durable()).start(() -> process(m));
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception e) {
                if (running.get()) {
                    LOG.warnf("Pull loop for %s failed, retrying: %s", spec.durable(), e.getMessage());
                    sub = null;
                    sleepQuietly(1000);
                }
            }
        }
    }

    private void process(Message m) {
        long delivery = m.metaData().deliveredCount();
        ScheduledFuture<?> hb = null;
        CloudEvent event = null;
        Span span = null;
        try {
            event = CloudEventCodec.fromBinary(m.getHeaders(), m.getData());
            Context parent = Tracing.extract(event);
            span = Tracing.tracer().spanBuilder(m.getSubject() + " process").setSpanKind(SpanKind.CONSUMER)
                    .setParent(parent)
                    .setAttribute("messaging.system", "nats")
                    .setAttribute("messaging.operation.type", "process")
                    .setAttribute("messaging.destination.name", m.getSubject())
                    .setAttribute("messaging.consumer.group.name", spec.durable())
                    .setAttribute("messaging.message.id", event.id())
                    .setAttribute("cloudevents.event_type", event.type())
                    .setAttribute("maop.delivery.attempt", delivery)
                    .startSpan();
            if (event.runId() != null) {
                span.setAttribute("maop.run.id", event.runId().toString());
            }
            if (event.taskId() != null) {
                span.setAttribute("maop.task.id", event.taskId().toString());
            }
            long periodMs = Math.max(1000, spec.ackWait().toMillis() / 3);
            hb = heartbeats.scheduleAtFixedRate(m::inProgress, periodMs, periodMs, TimeUnit.MILLISECONDS);
            InboundMessage in = new InboundMessage(m, event, delivery, spec.maxDeliver(), spec.durable());
            Disposition d;
            try (Scope ignored = span.makeCurrent()) {
                try {
                    d = handler.handle(in);
                } catch (Exception e) {
                    span.recordException(e);
                    if (in.finalDelivery()) {
                        LOG.warnf("Final delivery %d of %s failed; dead-lettering: %s", delivery, event.id(), e.toString());
                        handler.onFinalFailure(in, e);
                        d = Disposition.ACK;
                        span.setAttribute("maop.dead_lettered", true);
                    } else {
                        Duration delay = delivery <= 1 ? Duration.ofSeconds(2) : Duration.ofSeconds(5);
                        LOG.infof("Delivery %d of %s failed, retrying in %s: %s", delivery, event.id(), delay, e.toString());
                        d = Disposition.nak(delay, e.getClass().getSimpleName());
                    }
                }
            }
            apply(m, d, span);
        } catch (Exception e) {
            // Undecodable message or failure in onFinalFailure: never loop forever on poison input.
            LOG.errorf(e, "Unprocessable message on %s (delivery %d)", m.getSubject(), delivery);
            if (span != null) {
                span.recordException(e);
                span.setStatus(StatusCode.ERROR);
            }
            if (event == null || delivery >= spec.maxDeliver()) {
                m.term();
            } else {
                m.nakWithDelay(Duration.ofSeconds(5));
            }
        } finally {
            if (hb != null) {
                hb.cancel(false);
            }
            if (span != null) {
                span.end();
            }
            inFlight.remove(m);
            permits.release();
        }
    }

    private void apply(Message m, Disposition d, Span span) {
        switch (d) {
            case Disposition.Ack a -> m.ack();
            case Disposition.Nak n -> {
                span.setAttribute("maop.nak.reason", n.reason());
                m.nakWithDelay(n.delay());
            }
            case Disposition.Abandon ab -> span.setAttribute("maop.abandoned", ab.reason());
        }
        span.setAttribute("maop.disposition", d.getClass().getSimpleName());
    }

    /** Stop pulling, wait up to {@code grace} for in-flight handlers, then nak the rest for prompt redelivery. */
    public void stop(Duration grace) {
        if (!running.getAndSet(false)) {
            return;
        }
        if (loop != null) {
            loop.interrupt();
        }
        Instant until = Instant.now().plus(grace);
        while (!inFlight.isEmpty() && Instant.now().isBefore(until)) {
            sleepQuietly(100);
        }
        for (Message m : inFlight) {
            LOG.infof("Shutdown: returning unfinished message %s for redelivery", m.getSubject());
            m.nak();
        }
        heartbeats.shutdownNow();
    }

    @Override
    public void close() {
        stop(Duration.ofSeconds(5));
    }

    public int concurrency() {
        return concurrency;
    }

    private static void sleepQuietly(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
