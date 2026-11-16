package io.github.danieloh30.maop.console.resource;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.danieloh30.maop.common.json.Json;
import io.opentelemetry.api.trace.Span;
import jakarta.ws.rs.core.Response;

/** RFC 9457 problem responses. */
public final class Problems {

    private Problems() {
    }

    public static Response of(int status, String code, String detail) {
        ObjectNode n = Json.MAPPER.createObjectNode();
        n.put("type", "about:blank");
        Response.Status st = Response.Status.fromStatusCode(status);
        n.put("title", st == null ? "Error" : st.getReasonPhrase());
        n.put("status", status);
        n.put("code", code);
        n.put("detail", detail);
        n.put("traceId", Span.current().getSpanContext().isValid() ? Span.current().getSpanContext().getTraceId() : null);
        return Response.status(status).type("application/problem+json").entity(n).build();
    }
}
