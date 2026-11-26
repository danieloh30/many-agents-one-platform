package io.github.danieloh30.maop.common.events;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;
import io.github.danieloh30.maop.common.json.Json;
import io.nats.client.impl.Headers;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * CloudEvents NATS protocol binding: binary content mode (attributes as {@code ce-} headers, data as payload)
 * and the JSON event format for structured mode (SSE, DLQ, outbox storage).
 */
public final class CloudEventCodec {

    public static final String CE_PREFIX = "ce-";
    public static final String NATS_MSG_ID = "Nats-Msg-Id";
    public static final String STRUCTURED_CONTENT_TYPE = "application/cloudevents+json";
    private static final Set<String> CORE = Set.of("specversion", "id", "source", "type", "subject", "time",
            "datacontenttype", "dataschema", "data");

    private CloudEventCodec() {
    }

    public static Headers toHeaders(CloudEvent e) {
        Headers h = new Headers();
        h.put(CE_PREFIX + "specversion", CloudEvent.SPEC_VERSION);
        h.put(CE_PREFIX + "id", e.id());
        h.put(CE_PREFIX + "source", e.source());
        h.put(CE_PREFIX + "type", e.type());
        if (e.subject() != null) {
            h.put(CE_PREFIX + "subject", e.subject());
        }
        h.put(CE_PREFIX + "time", e.time().toString());
        h.put(CE_PREFIX + "datacontenttype", CloudEvent.DATA_CONTENT_TYPE);
        if (e.dataSchema() != null) {
            h.put(CE_PREFIX + "dataschema", e.dataSchema());
        }
        e.extensions().forEach((k, v) -> h.put(CE_PREFIX + k, v));
        // Transport headers: JetStream de-duplication and un-prefixed W3C trace context for standard propagators.
        h.put(NATS_MSG_ID, e.id());
        String tp = e.ext(CloudEvent.EXT_TRACEPARENT);
        if (tp != null) {
            h.put("traceparent", tp);
        }
        String ts = e.ext(CloudEvent.EXT_TRACESTATE);
        if (ts != null) {
            h.put("tracestate", ts);
        }
        return h;
    }

    public static byte[] toPayload(CloudEvent e) {
        return Json.bytes(e.data());
    }

    public static CloudEvent fromBinary(Headers h, byte[] payload) {
        if (h == null || h.getFirst(CE_PREFIX + "id") == null) {
            throw new IllegalArgumentException("Message is not a binary-mode CloudEvent (missing ce-id)");
        }
        String spec = h.getFirst(CE_PREFIX + "specversion");
        if (!CloudEvent.SPEC_VERSION.equals(spec)) {
            throw new IllegalArgumentException("Unsupported CloudEvents specversion: " + spec);
        }
        Map<String, String> ext = new LinkedHashMap<>();
        for (String key : h.keySet()) {
            if (key.regionMatches(true, 0, CE_PREFIX, 0, CE_PREFIX.length())) {
                String name = key.substring(CE_PREFIX.length()).toLowerCase();
                if (!CORE.contains(name)) {
                    ext.put(name, h.getFirst(key));
                }
            }
        }
        JsonNode data = payload == null || payload.length == 0 ? Json.MAPPER.createObjectNode()
                : Json.parse(new String(payload, StandardCharsets.UTF_8));
        String time = h.getFirst(CE_PREFIX + "time");
        return new CloudEvent(h.getFirst(CE_PREFIX + "id"), h.getFirst(CE_PREFIX + "source"),
                h.getFirst(CE_PREFIX + "type"), h.getFirst(CE_PREFIX + "subject"),
                time == null ? null : OffsetDateTime.parse(time), h.getFirst(CE_PREFIX + "dataschema"), ext, data);
    }

    public static ObjectNode toStructured(CloudEvent e) {
        ObjectNode n = Json.MAPPER.createObjectNode();
        n.put("specversion", CloudEvent.SPEC_VERSION);
        n.put("id", e.id());
        n.put("source", e.source());
        n.put("type", e.type());
        if (e.subject() != null) {
            n.put("subject", e.subject());
        }
        n.put("time", e.time().toString());
        n.put("datacontenttype", CloudEvent.DATA_CONTENT_TYPE);
        if (e.dataSchema() != null) {
            n.put("dataschema", e.dataSchema());
        }
        e.extensions().forEach(n::put);
        n.set("data", e.data());
        return n;
    }

    public static CloudEvent fromStructured(JsonNode n) {
        if (!CloudEvent.SPEC_VERSION.equals(n.path("specversion").asString())) {
            throw new IllegalArgumentException("Unsupported CloudEvents specversion");
        }
        Map<String, String> ext = new LinkedHashMap<>();
        Iterator<String> names = n.propertyNames().iterator();
        while (names.hasNext()) {
            String name = names.next();
            if (!CORE.contains(name)) {
                ext.put(name, n.get(name).asString());
            }
        }
        return new CloudEvent(n.path("id").asString(), n.path("source").asString(), n.path("type").asString(),
                n.hasNonNull("subject") ? n.get("subject").asString() : null,
                n.hasNonNull("time") ? OffsetDateTime.parse(n.get("time").asString()) : null,
                n.hasNonNull("dataschema") ? n.get("dataschema").asString() : null, ext, n.path("data"));
    }
}
