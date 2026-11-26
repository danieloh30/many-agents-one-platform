package io.github.danieloh30.maop.tools;

import tools.jackson.databind.JsonNode;
import io.github.danieloh30.maop.common.json.Json;
import io.github.danieloh30.maop.common.tls.SpiffeHttpClient;
import io.github.danieloh30.maop.common.tls.SvidSource;
import java.net.URI;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Test helpers: call the tool API as a given workload over real SPIFFE mTLS. */
final class ToolClients {

    static final String BASE = "https://localhost:" + Integer.getInteger("quarkus.http.test-ssl-port", 8444);

    private ToolClients() {
    }

    static SpiffeHttpClient as(String workload) {
        return new SpiffeHttpClient(new SvidSource(ToolServiceResources.dir(workload)),
                ToolServiceResources.id("tool-service"), Duration.ofSeconds(2));
    }

    static HttpResponse<String> invoke(String workload, String tool, String key, UUID runId, UUID taskId,
            Map<String, Object> args, UUID approvalId) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("runId", runId);
        body.put("taskId", taskId);
        body.put("arguments", args);
        body.put("approvalId", approvalId);
        return as(workload).send("POST", URI.create(BASE + "/v1/tools/" + tool + "/invocations"), Json.write(body),
                Map.of("Idempotency-Key", key), Duration.ofSeconds(10));
    }

    static HttpResponse<String> call(String workload, String method, String path, Object body) throws Exception {
        return as(workload).send(method, URI.create(BASE + path), body == null ? null : Json.write(body), Map.of(),
                Duration.ofSeconds(10));
    }

    static JsonNode json(HttpResponse<String> r) {
        return Json.parse(r.body());
    }
}
