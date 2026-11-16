package io.github.danieloh30.maop.common.messaging;

import io.github.danieloh30.maop.common.events.CloudEvent;
import io.github.danieloh30.maop.common.events.CloudEventCodec;
import io.github.danieloh30.maop.common.events.Subjects;
import io.github.danieloh30.maop.common.tracing.Tracing;
import io.nats.client.Connection;
import io.nats.client.Dispatcher;
import io.nats.client.JetStream;
import io.nats.client.JetStreamApiException;
import io.nats.client.JetStreamManagement;
import io.nats.client.Nats;
import io.nats.client.Options;
import io.nats.client.PublishOptions;
import io.nats.client.PurgeOptions;
import io.nats.client.api.AckPolicy;
import io.nats.client.api.ConsumerConfiguration;
import io.nats.client.api.ConsumerInfo;
import io.nats.client.api.MessageInfo;
import io.nats.client.api.PublishAck;
import io.nats.client.api.RetentionPolicy;
import io.nats.client.api.StorageType;
import io.nats.client.api.StreamConfiguration;
import io.nats.client.impl.NatsMessage;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/** The project's single entry point to NATS JetStream: connection, topology provisioning, publish, inspection. */
@ApplicationScoped
public class JetStreamClient {

    private static final Logger LOG = Logger.getLogger(JetStreamClient.class);

    @ConfigProperty(name = "maop.nats.url", defaultValue = "nats://localhost:4222")
    String url;
    @ConfigProperty(name = "maop.nats.user")
    Optional<String> user;
    @ConfigProperty(name = "maop.nats.password")
    Optional<String> password;
    @ConfigProperty(name = "maop.nats.connect-timeout", defaultValue = "60s")
    Duration connectTimeout;
    @ConfigProperty(name = "maop.nats.evidence-ack-wait", defaultValue = "20s")
    Duration evidenceAckWait;
    @ConfigProperty(name = "maop.nats.provision", defaultValue = "true")
    boolean provision;
    @ConfigProperty(name = "quarkus.application.name", defaultValue = "maop")
    String serviceName;

    private volatile Connection connection;
    private volatile JetStream jetStream;
    private volatile JetStreamManagement management;

    void onStart(@Observes @Priority(10) StartupEvent ev) throws Exception {
        connect();
    }

    void onStop(@Observes @Priority(Integer.MAX_VALUE - 10) ShutdownEvent ev) {
        Connection c = connection;
        if (c != null) {
            try {
                c.drain(Duration.ofSeconds(5)).get(6, TimeUnit.SECONDS);
            } catch (Exception e) {
                LOG.debug("NATS drain incomplete", e);
            }
        }
    }

    public synchronized void connect() throws Exception {
        if (connection != null) {
            return;
        }
        Options.Builder ob = Options.builder().server(url).connectionName(serviceName).maxReconnects(-1)
                .reconnectWait(Duration.ofSeconds(1)).connectionTimeout(Duration.ofSeconds(5));
        if (user.isPresent() && !user.get().isBlank()) {
            ob.userInfo(user.get(), password.orElse(""));
        }
        Options options = ob.build();
        Instant until = Instant.now().plus(connectTimeout);
        Exception last = null;
        while (Instant.now().isBefore(until)) {
            try {
                connection = Nats.connect(options);
                break;
            } catch (IOException e) {
                last = e;
                LOG.infof("Waiting for NATS at %s: %s", url, e.getMessage());
                Thread.sleep(1000);
            }
        }
        if (connection == null) {
            throw new IllegalStateException("Could not connect to NATS at " + url, last);
        }
        jetStream = connection.jetStream();
        management = connection.jetStreamManagement();
        if (provision) {
            provisionTopology();
        }
        LOG.infof("Connected to NATS %s as %s", url, serviceName);
    }

    /** Create or update the three streams and all durable consumers. Idempotent. */
    public void provisionTopology() throws IOException, JetStreamApiException {
        ensureStream(StreamConfiguration.builder().name(Subjects.WORK_STREAM)
                .subjects(Subjects.WORK_TRIAGE, Subjects.WORK_EVIDENCE, Subjects.WORK_RECOMMENDATION)
                .retentionPolicy(RetentionPolicy.WorkQueue).storageType(StorageType.File)
                .maxAge(Duration.ofHours(1)).duplicateWindow(Topology.DUPLICATE_WINDOW).replicas(1).build());
        ensureStream(StreamConfiguration.builder().name(Subjects.EVENTS_STREAM).subjects(Subjects.EVENTS_ALL)
                .retentionPolicy(RetentionPolicy.Limits).storageType(StorageType.File)
                .maxAge(Duration.ofHours(24)).duplicateWindow(Topology.DUPLICATE_WINDOW).replicas(1).build());
        ensureStream(StreamConfiguration.builder().name(Subjects.DLQ_STREAM).subjects(Subjects.DLQ_PREFIX + ">")
                .retentionPolicy(RetentionPolicy.Limits).storageType(StorageType.File)
                .maxAge(Duration.ofDays(7)).duplicateWindow(Topology.DUPLICATE_WINDOW).replicas(1).build());
        for (String retired : Topology.RETIRED_WORK_CONSUMERS) {
            try {
                management.deleteConsumer(Subjects.WORK_STREAM, retired);
                LOG.infof("Deleted retired consumer %s on %s", retired, Subjects.WORK_STREAM);
            } catch (JetStreamApiException e) {
                if (e.getApiErrorCode() != 10014) { // 10014: consumer not found
                    throw e;
                }
            }
        }
        for (ConsumerSpec c : Topology.consumers(evidenceAckWait)) {
            ConsumerConfiguration cc = ConsumerConfiguration.builder().durable(c.durable())
                    .filterSubjects(c.filterSubjects()).ackPolicy(AckPolicy.Explicit).ackWait(c.ackWait())
                    .maxDeliver(c.maxDeliver()).maxAckPending(c.maxAckPending()).build();
            management.addOrUpdateConsumer(c.stream(), cc);
        }
    }

    private void ensureStream(StreamConfiguration sc) throws IOException, JetStreamApiException {
        try {
            management.getStreamInfo(sc.getName());
            management.updateStream(sc);
        } catch (JetStreamApiException e) {
            if (e.getApiErrorCode() == 10059 || e.getErrorCode() == 404) {
                management.addStream(sc);
            } else {
                throw e;
            }
        }
    }

    public Connection connection() {
        return connection;
    }

    public JetStream jetStream() {
        return jetStream;
    }

    public JetStreamManagement management() {
        return management;
    }

    public boolean connected() {
        Connection c = connection;
        return c != null && c.getStatus() == Connection.Status.CONNECTED;
    }

    public Duration evidenceAckWait() {
        return evidenceAckWait;
    }

    public ConsumerSpec consumerSpec(String durable) {
        return Topology.consumer(durable, evidenceAckWait);
    }

    /**
     * Publish a CloudEvent in binary mode with {@code Nats-Msg-Id = id}. A PRODUCER span is created as a child of
     * the event's stamped trace context (or the current context), and the event is re-stamped with that span so the
     * consumer's span becomes its child.
     */
    public PublishAck publish(String subject, CloudEvent event) throws IOException, JetStreamApiException {
        Context parent = event.ext(CloudEvent.EXT_TRACEPARENT) != null ? Tracing.extract(event) : Context.current();
        Span span = Tracing.tracer().spanBuilder(subject + " publish").setSpanKind(SpanKind.PRODUCER)
                .setParent(parent)
                .setAttribute("messaging.system", "nats")
                .setAttribute("messaging.operation.type", "publish")
                .setAttribute("messaging.destination.name", subject)
                .setAttribute("messaging.message.id", event.id())
                .setAttribute("cloudevents.event_type", event.type())
                .startSpan();
        try (Scope ignored = span.makeCurrent()) {
            if (event.runId() != null) {
                span.setAttribute("maop.run.id", event.runId().toString());
            }
            CloudEvent stamped = Tracing.stamp(event, Context.current());
            NatsMessage msg = NatsMessage.builder().subject(subject).headers(CloudEventCodec.toHeaders(stamped))
                    .data(CloudEventCodec.toPayload(stamped)).build();
            PublishAck ack = jetStream.publish(msg, PublishOptions.builder().messageId(stamped.id()).build());
            span.setAttribute("maop.publish.duplicate", ack.isDuplicate());
            return ack;
        } catch (IOException | JetStreamApiException | RuntimeException e) {
            span.recordException(e);
            span.setStatus(StatusCode.ERROR);
            throw e;
        } finally {
            span.end();
        }
    }

    /** Best-effort publish for observation events (never affects workflow state). */
    public void publishQuietly(String subject, CloudEvent event) {
        try {
            publish(subject, event);
        } catch (Exception e) {
            LOG.debugf("Observation event %s not published: %s", event.type(), e.getMessage());
        }
    }

    public ConsumerInfo consumerInfo(String stream, String durable) throws IOException, JetStreamApiException {
        return management.getConsumerInfo(stream, durable);
    }

    /** Timestamp of the oldest message still stored for a subject (in a work-queue stream: oldest unacknowledged). */
    public Optional<Instant> oldestMessageTime(String stream, String subject) throws IOException {
        try {
            MessageInfo mi = management.getFirstMessage(stream, subject);
            return Optional.ofNullable(mi.getTime()).map(t -> t.toInstant());
        } catch (JetStreamApiException e) {
            return Optional.empty(); // no message for subject
        }
    }

    public long purgeSubject(String stream, String subject) throws IOException, JetStreamApiException {
        return management.purgeStream(stream, PurgeOptions.subject(subject)).getPurged();
    }

    public long purgeStream(String stream) throws IOException, JetStreamApiException {
        return management.purgeStream(stream).getPurged();
    }

    public Dispatcher dispatcher() {
        return connection.createDispatcher();
    }
}
