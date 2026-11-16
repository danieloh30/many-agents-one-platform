package io.github.danieloh30.maop.common.messaging;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.danieloh30.maop.common.Ids;
import io.github.danieloh30.maop.common.events.CloudEvent;
import io.github.danieloh30.maop.common.events.EventTypes;
import io.github.danieloh30.maop.common.events.Subjects;
import io.github.danieloh30.maop.common.tracing.Tracing;
import io.github.danieloh30.maop.testing.NatsTestResource;
import io.nats.client.api.PublishAck;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.Scope;
import io.opentelemetry.context.propagation.ContextPropagators;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Real NATS JetStream: redelivery, dead-lettering, graceful drain, de-duplication, trace continuity. */
class JetStreamIntegrationTest {

    static JetStreamClient js;
    static final InMemorySpanExporter SPANS = InMemorySpanExporter.create();

    @BeforeAll
    static void connect() throws Exception {
        SdkTracerProvider tp = SdkTracerProvider.builder().addSpanProcessor(SimpleSpanProcessor.create(SPANS)).build();
        Tracing.use(OpenTelemetrySdk.builder().setTracerProvider(tp)
                .setPropagators(ContextPropagators.create(W3CTraceContextPropagator.getInstance())).build());
        js = new JetStreamClient();
        js.url = NatsTestResource.url();
        js.user = java.util.Optional.empty();
        js.password = java.util.Optional.empty();
        js.connectTimeout = Duration.ofSeconds(30);
        js.evidenceAckWait = Duration.ofSeconds(3);
        js.provision = true;
        js.serviceName = "unit";
        js.connect();
    }

    @AfterAll
    static void close() throws Exception {
        js.connection().close();
        Tracing.use(null);
    }

    @BeforeEach
    void purge() throws Exception {
        js.purgeStream(Subjects.WORK_STREAM);
        js.purgeStream(Subjects.DLQ_STREAM);
        SPANS.reset();
    }

    private static CloudEvent command(UUID run, UUID task) {
        return CloudEvent.builder("unit", EventTypes.EVIDENCE_REQUESTED).task(run, task).data(Map.of("n", 1)).build();
    }

    @Test
    void duplicatePublishWithSameIdIsDeduplicatedByTheBroker() throws Exception {
        CloudEvent e = command(Ids.v7(), Ids.v7());
        PublishAck first = js.publish(Subjects.WORK_EVIDENCE, e);
        PublishAck second = js.publish(Subjects.WORK_EVIDENCE, e);
        assertTrue(!first.isDuplicate());
        assertTrue(second.isDuplicate());
        assertEquals(1, js.consumerInfo(Subjects.WORK_STREAM, Subjects.CONSUMER_EVIDENCE).getNumPending());
    }

    @Test
    void failedDeliveryIsRedeliveredAndFinalFailureIsDeadLetteredOnce() throws Exception {
        AtomicInteger attempts = new AtomicInteger();
        List<Long> finals = new CopyOnWriteArrayList<>();
        PullWorker w = new PullWorker(js, js.consumerSpec(Subjects.CONSUMER_EVIDENCE), 2, new MessageHandler() {
            @Override
            public Disposition handle(InboundMessage m) {
                attempts.incrementAndGet();
                throw new IllegalStateException("boom");
            }

            @Override
            public void onFinalFailure(InboundMessage m, Exception failure) throws Exception {
                finals.add(m.delivery());
                DeadLetters.publish(js, m.event(), failure.getMessage(), m.delivery());
            }
        });
        w.start();
        try {
            js.publish(Subjects.WORK_EVIDENCE, command(Ids.v7(), Ids.v7()));
            Awaitility.await().atMost(Duration.ofSeconds(30)).until(() -> finals.size() == 1);
            assertEquals(List.of(3L), finals, "dead-lettered on the third (final) delivery");
            assertEquals(3, attempts.get(), "bounded by MaxDeliver");
            Awaitility.await().atMost(Duration.ofSeconds(5)).until(() ->
                    js.consumerInfo(Subjects.WORK_STREAM, Subjects.CONSUMER_EVIDENCE).getNumAckPending() == 0);
            assertEquals(0, js.management().getStreamInfo(Subjects.WORK_STREAM).getStreamState().getMsgCount(),
                    "acknowledged after dead-lettering, so the work queue no longer holds it");
            assertEquals(1, js.management().getStreamInfo(Subjects.DLQ_STREAM).getStreamState().getMsgCount());
        } finally {
            w.stop(Duration.ofSeconds(2));
        }
    }

    @Test
    void abandonedDeliveryComesBackAfterAckWait() throws Exception {
        Map<Long, Boolean> deliveries = new ConcurrentHashMap<>();
        PullWorker w = new PullWorker(js, js.consumerSpec(Subjects.CONSUMER_EVIDENCE), 1, m -> {
            deliveries.put(m.delivery(), true);
            return m.delivery() == 1 ? new Disposition.Abandon("test") : Disposition.ACK;
        });
        w.start();
        try {
            js.publish(Subjects.WORK_EVIDENCE, command(Ids.v7(), Ids.v7()));
            Awaitility.await().atMost(Duration.ofSeconds(20)).until(() -> deliveries.containsKey(2L));
        } finally {
            w.stop(Duration.ofSeconds(2));
        }
    }

    @Test
    void gracefulStopFinishesInFlightWorkAndPullsNothingNew() throws Exception {
        AtomicInteger done = new AtomicInteger();
        PullWorker w = new PullWorker(js, js.consumerSpec(Subjects.CONSUMER_EVIDENCE), 1, m -> {
            Thread.sleep(1500);
            done.incrementAndGet();
            return Disposition.ACK;
        });
        w.start();
        js.publish(Subjects.WORK_EVIDENCE, command(Ids.v7(), Ids.v7()));
        Awaitility.await().atMost(Duration.ofSeconds(10)).until(() -> w.activeTasks() == 1);
        js.publish(Subjects.WORK_EVIDENCE, command(Ids.v7(), Ids.v7()));
        w.stop(Duration.ofSeconds(10));
        assertEquals(1, done.get(), "the in-flight task completed during drain");
        Awaitility.await().atMost(Duration.ofSeconds(5)).until(() ->
                js.consumerInfo(Subjects.WORK_STREAM, Subjects.CONSUMER_EVIDENCE).getNumPending()
                        + js.consumerInfo(Subjects.WORK_STREAM, Subjects.CONSUMER_EVIDENCE).getNumAckPending() == 1);
    }

    @Test
    void consumerSpanContinuesTheProducerTrace() throws Exception {
        List<String> seenTrace = new CopyOnWriteArrayList<>();
        PullWorker w = new PullWorker(js, js.consumerSpec(Subjects.CONSUMER_EVIDENCE), 1, m -> {
            seenTrace.add(Span.current().getSpanContext().getTraceId());
            return Disposition.ACK;
        });
        w.start();
        String traceId;
        Span root = Tracing.tracer().spanBuilder("request").startSpan();
        try (Scope ignored = root.makeCurrent()) {
            traceId = root.getSpanContext().getTraceId();
            js.publish(Subjects.WORK_EVIDENCE, Tracing.stampCurrent(command(Ids.v7(), Ids.v7())));
        } finally {
            root.end();
        }
        try {
            Awaitility.await().atMost(Duration.ofSeconds(10)).until(() -> !seenTrace.isEmpty());
            assertEquals(traceId, seenTrace.getFirst());
            Awaitility.await().atMost(Duration.ofSeconds(5)).until(() -> SPANS.getFinishedSpanItems().stream()
                    .anyMatch(s -> s.getName().endsWith(" process")));
            SpanData publish = SPANS.getFinishedSpanItems().stream().filter(s -> s.getName().endsWith(" publish")).findFirst().orElseThrow();
            SpanData process = SPANS.getFinishedSpanItems().stream().filter(s -> s.getName().endsWith(" process")).findFirst().orElseThrow();
            assertEquals(publish.getSpanId(), process.getParentSpanId(), "consumer span is a child of the producer span");
            assertEquals(traceId, process.getTraceId());
        } finally {
            w.stop(Duration.ofSeconds(2));
        }
    }
}
