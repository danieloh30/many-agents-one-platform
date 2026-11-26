package io.github.danieloh30.maop.console.service;

import io.github.danieloh30.maop.common.tracing.Tracing;
import io.github.danieloh30.maop.console.resource.Problems;
import io.opentelemetry.context.Context;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.core.Response;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/** Forwards console API calls to triage-agent (cluster-internal), propagating trace context. */
@ApplicationScoped
public class Proxy {

    @ConfigProperty(name = "maop.triage.url", defaultValue = "http://triage-agent:8080")
    String triageUrl;

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();

    public HttpResponse<String> send(String method, String path, String body, Map<String, String> headers) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(triageUrl + path)).timeout(Duration.ofSeconds(15))
                .header("Accept", "application/json");
        if (body != null) {
            b.header("Content-Type", "application/json").method(method, HttpRequest.BodyPublishers.ofString(body));
        } else {
            b.method(method, HttpRequest.BodyPublishers.noBody());
        }
        headers.forEach((k, v) -> {
            if (v != null) {
                b.header(k, v);
            }
        });
        Tracing.inject(Context.current()).forEach(b::header);
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    @io.opentelemetry.instrumentation.annotations.WithSpan("maop.console.forward")
    public Response forward(String method, String path, String body, Map<String, String> headers) {
        try {
            HttpResponse<String> r = send(method, path, body, headers);
            String ct = r.headers().firstValue("Content-Type").orElse("application/json");
            Response.ResponseBuilder rb = Response.status(r.statusCode()).type(ct);
            r.headers().firstValue("ETag").ifPresent(e -> rb.header("ETag", e));
            r.headers().firstValue("Retry-After").ifPresent(e -> rb.header("Retry-After", e));
            return r.body() == null || r.body().isEmpty() ? rb.build() : rb.entity(r.body()).build();
        } catch (Exception e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return Problems.of(502, "TRIAGE_UNAVAILABLE", "triage-agent is not reachable: " + e.getClass().getSimpleName());
        }
    }
}
