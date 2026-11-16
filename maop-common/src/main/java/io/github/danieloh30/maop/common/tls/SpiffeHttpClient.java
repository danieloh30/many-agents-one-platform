package io.github.danieloh30.maop.common.tls;

import io.github.danieloh30.maop.common.tracing.Tracing;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.GeneralSecurityException;
import java.time.Duration;
import java.util.Map;

/**
 * HTTPS client that authenticates with the workload's X.509-SVID and authenticates the server by SPIFFE ID. The
 * underlying {@link HttpClient} is rebuilt when spiffe-helper rotates the SVID, so rotation needs no restart.
 * Outgoing requests carry W3C trace context and produce a CLIENT span.
 */
public class SpiffeHttpClient {

    private final SvidSource source;
    private final String expectedServerId;
    private final Duration connectTimeout;
    private volatile HttpClient client;
    private volatile long builtFor = Long.MIN_VALUE;

    public SpiffeHttpClient(SvidSource source, String expectedServerId, Duration connectTimeout) {
        this.source = source;
        this.expectedServerId = expectedServerId;
        this.connectTimeout = connectTimeout;
    }

    public String localSpiffeId() {
        return source.current().spiffeId();
    }

    private HttpClient client() throws GeneralSecurityException {
        SvidSource.Material m = source.current();
        if (client == null || builtFor != m.fingerprint()) {
            synchronized (this) {
                if (client == null || builtFor != m.fingerprint()) {
                    client = HttpClient.newBuilder().sslContext(SpiffeTls.clientContext(m, expectedServerId))
                            .connectTimeout(connectTimeout).version(HttpClient.Version.HTTP_1_1).build();
                    builtFor = m.fingerprint();
                }
            }
        }
        return client;
    }

    public HttpResponse<String> send(String method, URI uri, String jsonBody, Map<String, String> headers, Duration timeout)
            throws IOException, InterruptedException {
        Span span = Tracing.tracer().spanBuilder(method + " " + uri.getPath()).setSpanKind(SpanKind.CLIENT)
                .setAttribute("http.request.method", method)
                .setAttribute("url.full", uri.toString())
                .setAttribute("server.address", uri.getHost())
                .setAttribute("server.port", (long) uri.getPort())
                .setAttribute("maop.peer.expected_spiffe_id", expectedServerId == null ? "" : expectedServerId)
                .startSpan();
        try (Scope ignored = span.makeCurrent()) {
            HttpRequest.Builder b = HttpRequest.newBuilder(uri).timeout(timeout).header("Accept", "application/json");
            if (jsonBody != null) {
                b.header("Content-Type", "application/json").method(method, HttpRequest.BodyPublishers.ofString(jsonBody));
            } else {
                b.method(method, HttpRequest.BodyPublishers.noBody());
            }
            headers.forEach(b::header);
            Tracing.inject(Context.current()).forEach(b::header);
            HttpResponse<String> r = client().send(b.build(), HttpResponse.BodyHandlers.ofString());
            span.setAttribute("http.response.status_code", (long) r.statusCode());
            if (r.statusCode() >= 500) {
                span.setStatus(StatusCode.ERROR);
            }
            return r;
        } catch (GeneralSecurityException e) {
            span.recordException(e);
            span.setStatus(StatusCode.ERROR);
            throw new IOException("TLS configuration error: " + e.getMessage(), e);
        } catch (IOException | InterruptedException | RuntimeException e) {
            span.recordException(e);
            span.setStatus(StatusCode.ERROR);
            throw e;
        } finally {
            span.end();
        }
    }
}
