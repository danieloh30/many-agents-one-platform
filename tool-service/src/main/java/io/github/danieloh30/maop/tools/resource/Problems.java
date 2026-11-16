package io.github.danieloh30.maop.tools.resource;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.danieloh30.maop.common.json.Json;
import io.opentelemetry.api.trace.Span;
import jakarta.ws.rs.core.Response;

/** RFC 9457 problem responses with a machine-readable code and the trace ID. */
public final class Problems {

    private Problems() {
    }

    public static ObjectNode body(int status, String code, String detail) {
        ObjectNode n = Json.MAPPER.createObjectNode();
        n.put("type", "about:blank");
        n.put("title", Response.Status.fromStatusCode(status) != null ? Response.Status.fromStatusCode(status).getReasonPhrase() : "Error");
        n.put("status", status);
        n.put("code", code);
        n.put("detail", detail);
        String traceId = Span.current().getSpanContext().isValid() ? Span.current().getSpanContext().getTraceId() : null;
        n.put("traceId", traceId);
        return n;
    }

    public static Response of(int status, String code, String detail) {
        return Response.status(status).type("application/problem+json").entity(body(status, code, detail)).build();
    }

    public static Response of(int status, ObjectNode body) {
        return Response.status(status).type("application/problem+json").entity(body).build();
    }
}
