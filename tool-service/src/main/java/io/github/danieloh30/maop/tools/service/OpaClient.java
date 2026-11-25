package io.github.danieloh30.maop.tools.service;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;
import io.github.danieloh30.maop.common.model.PolicyDecision;
import io.github.danieloh30.maop.common.json.Json;
import io.github.danieloh30.maop.common.tracing.Tracing;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/** Policy decision point client (OPA REST data API). Any failure is reported as unavailable: callers fail closed. */
@ApplicationScoped
public class OpaClient {

    public static class PolicyUnavailableException extends Exception {
        public PolicyUnavailableException(String message) {
            super(message);
        }
    }

    @ConfigProperty(name = "maop.opa.url", defaultValue = "http://127.0.0.1:8181")
    String url;
    @ConfigProperty(name = "maop.opa.timeout", defaultValue = "500ms")
    Duration timeout;
    @ConfigProperty(name = "maop.opa.decision-path", defaultValue = "maop/tools/decision")
    String path;
    @Inject
    MeterRegistry registry;

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofMillis(500)).build();

    public PolicyDecision decide(ObjectNode input) throws PolicyUnavailableException {
        Span span = Tracing.tracer().spanBuilder("policy.evaluate").setSpanKind(SpanKind.CLIENT)
                .setAttribute("maop.policy.action", input.path("action").asString())
                .setAttribute("maop.tool.name", input.path("tool").path("name").asString(""))
                .startSpan();
        try (Scope ignored = span.makeCurrent()) {
            ObjectNode body = Json.MAPPER.createObjectNode();
            body.set("input", input);
            HttpRequest.Builder rb = HttpRequest.newBuilder(URI.create(url + "/v1/data/" + path)).timeout(timeout)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(Json.write(body)));
            Tracing.inject(Context.current()).forEach(rb::header);
            HttpResponse<String> r;
            try {
                r = http.send(rb.build(), HttpResponse.BodyHandlers.ofString());
            } catch (Exception e) {
                if (e instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
                throw unavailable(span, "OPA unreachable: " + e.getClass().getSimpleName());
            }
            if (r.statusCode() != 200) {
                throw unavailable(span, "OPA returned HTTP " + r.statusCode());
            }
            JsonNode json = Json.parse(r.body());
            JsonNode result = json.get("result");
            if (result == null || !result.has("allow")) {
                throw unavailable(span, "Policy decision undefined");
            }
            List<String> reasons = new ArrayList<>();
            if (result.get("reasons") instanceof ArrayNode arr) {
                arr.forEach(n -> reasons.add(n.asString()));
            }
            PolicyDecision d = new PolicyDecision(result.get("allow").asBoolean(false),
                    json.path("decision_id").asString(null), result.path("revision").asString(null), reasons);
            span.setAttribute("maop.policy.decision", d.allow() ? "allow" : "deny");
            if (d.decisionId() != null) {
                span.setAttribute("maop.policy.decision_id", d.decisionId());
            }
            span.setAttribute("maop.policy.reasons", String.join(",", reasons));
            Counter.builder("maop.policy.decisions").tag("decision", d.allow() ? "allow" : "deny")
                    .tag("reason", reasons.isEmpty() ? "none" : reasons.getFirst()).register(registry).increment();
            return d;
        } finally {
            span.end();
        }
    }

    private PolicyUnavailableException unavailable(Span span, String message) {
        span.setStatus(StatusCode.ERROR, message);
        Counter.builder("maop.policy.decisions").tag("decision", "unavailable").tag("reason", "policy_unavailable")
                .register(registry).increment();
        return new PolicyUnavailableException(message);
    }
}
