package io.github.danieloh30.maop.common.events;

import tools.jackson.databind.JsonNode;
import io.github.danieloh30.maop.common.json.Json;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * A CloudEvents 1.0 event with JSON data. Extension attribute names follow the spec (lowercase a-z0-9):
 * {@code traceparent}, {@code tracestate}, {@code maoprunid}, {@code maoptaskid}, {@code maopdeadline},
 * {@code maopmodelmode}.
 */
public record CloudEvent(String id, String source, String type, String subject, OffsetDateTime time,
        String dataSchema, Map<String, String> extensions, JsonNode data) {

    public static final String SPEC_VERSION = "1.0";
    public static final String DATA_CONTENT_TYPE = "application/json";
    public static final String SCHEMA_BASE = "https://github.com/danieloh30/many-agents-one-platform/contracts/events/";

    public static final String EXT_TRACEPARENT = "traceparent";
    public static final String EXT_TRACESTATE = "tracestate";
    public static final String EXT_RUN_ID = "maoprunid";
    public static final String EXT_TASK_ID = "maoptaskid";
    public static final String EXT_DEADLINE = "maopdeadline";
    public static final String EXT_MODEL_MODE = "maopmodelmode";

    public CloudEvent {
        extensions = extensions == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(extensions));
    }

    public static Builder builder(String service, String typeSuffix) {
        return new Builder(service, typeSuffix);
    }

    public String typeSuffix() {
        return EventTypes.suffix(type);
    }

    public String ext(String name) {
        return extensions.get(name);
    }

    public UUID runId() {
        String v = ext(EXT_RUN_ID);
        return v == null ? null : UUID.fromString(v);
    }

    public UUID taskId() {
        String v = ext(EXT_TASK_ID);
        return v == null ? null : UUID.fromString(v);
    }

    public <T> T dataAs(Class<T> type) {
        return Json.convert(data, type);
    }

    public CloudEvent withExtension(String name, String value) {
        Map<String, String> m = new LinkedHashMap<>(extensions);
        if (value == null) {
            m.remove(name);
        } else {
            m.put(name, value);
        }
        return new CloudEvent(id, source, type, subject, time, dataSchema, m, data);
    }

    public static final class Builder {
        private String id;
        private final String source;
        private final String typeSuffix;
        private String subject;
        private OffsetDateTime time;
        private final Map<String, String> ext = new LinkedHashMap<>();
        private JsonNode data;

        private Builder(String service, String typeSuffix) {
            this.source = "/maop/" + service;
            this.typeSuffix = typeSuffix;
        }

        public Builder id(String id) {
            this.id = id;
            return this;
        }

        public Builder id(UUID id) {
            this.id = id.toString();
            return this;
        }

        public Builder subject(String subject) {
            this.subject = subject;
            return this;
        }

        public Builder run(UUID runId) {
            if (runId != null) {
                ext.put(EXT_RUN_ID, runId.toString());
                if (subject == null) {
                    subject = "runs/" + runId;
                }
            }
            return this;
        }

        public Builder task(UUID runId, UUID taskId) {
            run(runId);
            if (taskId != null) {
                ext.put(EXT_TASK_ID, taskId.toString());
                subject = "runs/" + runId + "/tasks/" + taskId;
            }
            return this;
        }

        public Builder extension(String name, String value) {
            if (value != null) {
                ext.put(name, value);
            }
            return this;
        }

        public Builder time(OffsetDateTime time) {
            this.time = time;
            return this;
        }

        public Builder data(Object data) {
            this.data = data instanceof JsonNode n ? n : Json.tree(data);
            return this;
        }

        public CloudEvent build() {
            return new CloudEvent(id != null ? id : io.github.danieloh30.maop.common.Ids.v7().toString(), source,
                    EventTypes.full(typeSuffix), subject, time != null ? time : OffsetDateTime.now(ZoneOffset.UTC),
                    SCHEMA_BASE + typeSuffix + ".schema.json", ext, data != null ? data : Json.MAPPER.createObjectNode());
        }
    }
}
