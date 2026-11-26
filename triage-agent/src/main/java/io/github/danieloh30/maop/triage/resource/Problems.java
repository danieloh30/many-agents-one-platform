package io.github.danieloh30.maop.triage.resource;

import tools.jackson.databind.node.ObjectNode;
import io.github.danieloh30.maop.common.json.Json;
import io.opentelemetry.api.trace.Span;
import jakarta.ws.rs.core.Response;

/** RFC 9457 problem responses with a machine-readable code and the trace ID. */
public final class Problems {

    private Problems() {
    }

    public static Response of(int status, String code, String detail) {
        ObjectNode n = Json.MAPPER.createObjectNode();
        n.put("type", "about:blank");
        Response.Status st = Response.Status.fromStatusCode(status);
        n.put("title", st != null ? st.getReasonPhrase() : "Error");
        n.put("status", status);
        n.put("code", code);
        n.put("detail", detail);
        n.put("traceId", Span.current().getSpanContext().isValid() ? Span.current().getSpanContext().getTraceId() : null);
        return Response.status(status).type("application/problem+json").entity(n).build();
    }

    /** Thrown inside service code to produce a problem response (rolls back the transaction). */
    public static final class ApiException extends RuntimeException {
        public final int status;
        public final String code;

        public ApiException(int status, String code, String detail) {
            super(detail, null, false, false);
            this.status = status;
            this.code = code;
        }

        public Response toResponse() {
            return of(status, code, getMessage());
        }
    }
}
