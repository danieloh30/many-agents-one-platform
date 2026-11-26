package io.github.danieloh30.maop.console.service;

import tools.jackson.databind.JsonNode;
import io.github.danieloh30.maop.common.events.CloudEvent;
import io.github.danieloh30.maop.common.events.CloudEventCodec;
import io.github.danieloh30.maop.common.events.EventTypes;
import io.github.danieloh30.maop.common.events.Subjects;
import io.github.danieloh30.maop.common.json.Json;
import io.github.danieloh30.maop.common.messaging.JetStreamClient;
import io.nats.client.Dispatcher;
import io.nats.client.Message;
import io.nats.client.PushSubscribeOptions;
import io.nats.client.api.ConsumerConfiguration;
import io.nats.client.api.DeliverPolicy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.sse.OutboundSseEvent;
import jakarta.ws.rs.sse.Sse;
import jakarta.ws.rs.sse.SseEventSink;
import java.time.ZonedDateTime;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import org.jboss.logging.Logger;

/**
 * Reads MAOP_EVENTS with an ordered ephemeral consumer, keeps a bounded replay buffer keyed by stream sequence,
 * and fans events out to SSE clients. This is a rendering view only; durable state comes from REST.
 */
@ApplicationScoped
public class EventHub {

    private static final Logger LOG = Logger.getLogger(EventHub.class);
    private static final int BUFFER = 5000;

    record Buffered(long seq, String runId, String json) {
    }

    record Client(SseEventSink sink, Sse sse, String runFilter) {
    }

    @Inject
    JetStreamClient js;

    private final Deque<Buffered> buffer = new ArrayDeque<>();
    private final Map<UUID, Client> clients = new ConcurrentHashMap<>();
    private final List<Consumer<CloudEvent>> listeners = new ArrayList<>();
    private Dispatcher dispatcher;

    public void addListener(Consumer<CloudEvent> l) {
        listeners.add(l);
    }

    public void start() throws Exception {
        dispatcher = js.dispatcher();
        js.jetStream().subscribe(Subjects.EVENTS_ALL, dispatcher, this::onMessage, true, PushSubscribeOptions.builder()
                .ordered(true).configuration(ConsumerConfiguration.builder().deliverPolicy(DeliverPolicy.ByStartTime)
                        .startTime(ZonedDateTime.now().minusMinutes(15)).build())
                .build());
    }

    private void onMessage(Message m) {
        try {
            CloudEvent e = CloudEventCodec.fromBinary(m.getHeaders(), m.getData());
            long seq = m.metaData().streamSequence();
            for (Consumer<CloudEvent> l : listeners) {
                l.accept(e);
            }
            if (EventTypes.DEMO_RESET.equals(e.typeSuffix())) {
                synchronized (buffer) {
                    buffer.clear();
                }
                broadcastReset();
            }
            Buffered b = new Buffered(seq, e.runId() == null ? null : e.runId().toString(),
                    Json.write(CloudEventCodec.toStructured(e)));
            synchronized (buffer) {
                buffer.addLast(b);
                while (buffer.size() > BUFFER) {
                    buffer.removeFirst();
                }
            }
            clients.forEach((id, c) -> send(id, c, b));
        } catch (Exception ex) {
            LOG.debugf("Skipping undecodable event: %s", ex.getMessage());
        }
    }

    /** Register an SSE client, replaying everything after {@code lastEventId} (or a reset if it is too old). */
    public void register(SseEventSink sink, Sse sse, String lastEventId, String runFilter) {
        UUID id = UUID.randomUUID();
        Client c = new Client(sink, sse, runFilter);
        List<Buffered> replay = new ArrayList<>();
        boolean reset = false;
        synchronized (buffer) {
            if (lastEventId != null && !lastEventId.isBlank()) {
                long last;
                try {
                    last = Long.parseLong(lastEventId.trim());
                } catch (NumberFormatException e) {
                    last = -1;
                }
                long oldest = buffer.isEmpty() ? Long.MAX_VALUE : buffer.peekFirst().seq();
                if (last < 0 || (last + 1 < oldest && !buffer.isEmpty())) {
                    reset = true;
                } else {
                    for (Buffered b : buffer) {
                        if (b.seq() > last) {
                            replay.add(b);
                        }
                    }
                }
            }
            clients.put(id, c);
        }
        if (reset) {
            sink.send(sse.newEventBuilder().name("reset").data("{}").build());
        }
        for (Buffered b : replay) {
            send(id, c, b);
        }
    }

    private void send(UUID id, Client c, Buffered b) {
        if (c.runFilter() != null && !c.runFilter().equals(b.runId())) {
            return;
        }
        emit(id, c, c.sse().newEventBuilder().id(Long.toString(b.seq())).name("run-event").data(b.json()).build());
    }

    public void broadcast(String name, JsonNode data) {
        String json = Json.write(data);
        clients.forEach((id, c) -> emit(id, c, c.sse().newEventBuilder().name(name).data(json).build()));
    }

    public void heartbeat() {
        clients.forEach((id, c) -> emit(id, c, c.sse().newEventBuilder().comment("heartbeat").build()));
    }

    private void broadcastReset() {
        clients.forEach((id, c) -> emit(id, c, c.sse().newEventBuilder().name("reset").data("{}").build()));
    }

    private void emit(UUID id, Client c, OutboundSseEvent ev) {
        if (c.sink().isClosed()) {
            clients.remove(id);
            return;
        }
        c.sink().send(ev).whenComplete((v, err) -> {
            if (err != null) {
                clients.remove(id);
                try {
                    c.sink().close();
                } catch (Exception ignored) {
                    // client already gone
                }
            }
        });
    }

    public int clientCount() {
        return clients.size();
    }
}
