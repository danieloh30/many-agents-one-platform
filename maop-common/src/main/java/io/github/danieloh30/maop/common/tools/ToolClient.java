package io.github.danieloh30.maop.common.tools;

import tools.jackson.databind.JsonNode;
import io.github.danieloh30.maop.common.BudgetTracker;
import io.github.danieloh30.maop.common.Deadlines;
import io.github.danieloh30.maop.common.TaskFailure;
import io.github.danieloh30.maop.common.model.FailureReason;
import io.github.danieloh30.maop.common.model.PolicyDecision;
import io.github.danieloh30.maop.common.model.ToolInvocationRequest;
import io.github.danieloh30.maop.common.json.Json;
import io.github.danieloh30.maop.common.tls.SpiffeHttpClient;
import io.github.danieloh30.maop.common.tls.SvidSource;
import jakarta.enterprise.context.ApplicationScoped;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * Client for the protected tool API over SPIFFE mTLS. Retries only transport errors, timeouts and 503 (safe
 * because every invocation carries an idempotency key); never retries 403/409/422.
 */
@ApplicationScoped
public class ToolClient {

    private static final Logger LOG = Logger.getLogger(ToolClient.class);

    @ConfigProperty(name = "maop.spiffe.dir", defaultValue = "/spiffe")
    String spiffeDir;
    @ConfigProperty(name = "maop.tool-service.url", defaultValue = "https://tool-service:8443")
    String baseUrl;
    @ConfigProperty(name = "maop.tool-service.spiffe-id")
    String expectedServerId;
    @ConfigProperty(name = "maop.tool.connect-timeout", defaultValue = "2s")
    Duration connectTimeout;
    @ConfigProperty(name = "maop.tool.read-timeout", defaultValue = "5s")
    Duration readTimeout;
    @ConfigProperty(name = "maop.tool.max-retries", defaultValue = "2")
    int maxRetries;

    private volatile SpiffeHttpClient http;

    private SpiffeHttpClient http() {
        if (http == null) {
            synchronized (this) {
                if (http == null) {
                    http = new SpiffeHttpClient(new SvidSource(Path.of(spiffeDir)), expectedServerId, connectTimeout);
                }
            }
        }
        return http;
    }

    public String localSpiffeId() {
        return http().localSpiffeId();
    }

    public static String idempotencyKey(UUID taskId, String toolName, Map<String, ?> arguments, UUID approvalId) {
        String digest = Json.digest(arguments == null ? Map.of() : arguments).substring(7, 23);
        return taskId + ":" + toolName + ":" + digest + (approvalId == null ? "" : ":" + approvalId);
    }

    public ToolResult invoke(UUID runId, UUID taskId, String toolName, Map<String, Object> arguments, UUID approvalId,
            Instant deadline, BudgetTracker budget) {
        if (budget != null) {
            budget.beforeToolCall();
        }
        String key = idempotencyKey(taskId, toolName, arguments, approvalId);
        String body = Json.write(new ToolInvocationRequest(runId, taskId, deadline, arguments, approvalId));
        HttpResponse<String> r = exchange("POST", "/v1/tools/" + toolName + "/invocations", body,
                Map.of("Idempotency-Key", key), deadline, "tool call " + toolName);
        JsonNode json = r.body() == null || r.body().isBlank() ? Json.MAPPER.createObjectNode() : Json.parse(r.body());
        return switch (r.statusCode()) {
            case 200 -> new ToolResult(json.path("invocationId").asString(), toolName, json.path("status").asString(),
                    json.path("replayed").asBoolean(), Json.convert(json.path("decision"), PolicyDecision.class),
                    json.path("result"));
            case 403 -> {
                if (budget != null) {
                    budget.denial();
                }
                yield new ToolResult(json.path("invocationId").asString(), toolName, "DENIED",
                        json.path("replayed").asBoolean(), Json.convert(json.path("decision"), PolicyDecision.class), null);
            }
            case 408, 504 -> throw TaskFailure.deadline("tool call " + toolName);
            case 409, 422 -> throw new TaskFailure(FailureReason.INVALID_COMMAND, false,
                    "Tool rejected request (" + r.statusCode() + "): " + json.path("detail").asString());
            default -> throw new TaskFailure(FailureReason.TOOL_UNAVAILABLE, true,
                    "Tool service returned " + r.statusCode() + ": " + json.path("code").asString());
        };
    }

    /** Generic JSON call (approval grants, audit reads, reset). */
    public HttpResponse<String> call(String method, String path, Object body, Instant deadline) {
        return exchange(method, path, body == null ? null : Json.write(body), Map.of(), deadline, method + " " + path);
    }

    private HttpResponse<String> exchange(String method, String path, String body, Map<String, String> headers,
            Instant deadline, String what) {
        URI uri = URI.create(baseUrl + path);
        TaskFailure last = null;
        for (int attempt = 0; attempt <= maxRetries; attempt++) {
            Duration timeout = Deadlines.callTimeout(deadline, readTimeout, what);
            try {
                HttpResponse<String> r = http().send(method, uri, body, headers, timeout);
                if (r.statusCode() != 503) {
                    return r;
                }
                last = new TaskFailure(FailureReason.TOOL_UNAVAILABLE, true, "Tool service unavailable (503)");
            } catch (IOException e) {
                last = new TaskFailure(FailureReason.TOOL_UNAVAILABLE, true, what + " failed: " + e.getMessage(), e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new TaskFailure(FailureReason.TOOL_UNAVAILABLE, true, "Interrupted during " + what);
            }
            if (attempt < maxRetries) {
                long backoff = 200L * (1L << attempt) + ThreadLocalRandom.current().nextLong(100);
                LOG.debugf("Retrying %s in %d ms: %s", what, backoff, last.getMessage());
                try {
                    Thread.sleep(backoff);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw last;
                }
            }
        }
        throw last;
    }
}
