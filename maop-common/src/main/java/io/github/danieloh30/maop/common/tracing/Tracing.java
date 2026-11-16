package io.github.danieloh30.maop.common.tracing;

import io.github.danieloh30.maop.common.events.CloudEvent;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.propagation.TextMapGetter;
import io.opentelemetry.context.propagation.TextMapPropagator;
import io.opentelemetry.context.propagation.TextMapSetter;
import java.util.HashMap;
import java.util.Map;

/**
 * W3C trace-context propagation through CloudEvents extensions (and therefore NATS headers and outbox rows).
 * Uses the OpenTelemetry instance configured by Quarkus (registered globally by the extension) and falls back
 * to the no-op implementation when none is configured (plain unit tests).
 */
public final class Tracing {

    public static final String INSTRUMENTATION = "io.github.danieloh30.maop";

    private static volatile OpenTelemetry override;

    private static final TextMapSetter<Map<String, String>> SETTER = (carrier, key, value) -> carrier.put(key, value);
    private static final TextMapGetter<Map<String, String>> GETTER = new TextMapGetter<>() {
        @Override
        public Iterable<String> keys(Map<String, String> carrier) {
            return carrier.keySet();
        }

        @Override
        public String get(Map<String, String> carrier, String key) {
            return carrier == null ? null : carrier.get(key);
        }
    };

    private Tracing() {
    }

    /** For tests: use a specific OpenTelemetry instance. */
    public static void use(OpenTelemetry otel) {
        override = otel;
    }

    public static OpenTelemetry otel() {
        OpenTelemetry o = override;
        return o != null ? o : GlobalOpenTelemetry.get();
    }

    public static io.opentelemetry.api.trace.Tracer tracer() {
        return otel().getTracer(INSTRUMENTATION);
    }

    private static TextMapPropagator propagator() {
        return otel().getPropagators().getTextMapPropagator();
    }

    /** Serialise a context to {traceparent, tracestate?}. Empty map when the context carries no valid span. */
    public static Map<String, String> inject(Context context) {
        Map<String, String> carrier = new HashMap<>();
        if (Span.fromContext(context).getSpanContext().isValid()) {
            propagator().inject(context, carrier, SETTER);
        }
        return carrier;
    }

    public static Context extract(Map<String, String> carrier) {
        return propagator().extract(Context.root(), carrier, GETTER);
    }

    /** Stamp the given context into the event's trace extensions. */
    public static CloudEvent stamp(CloudEvent e, Context context) {
        Map<String, String> c = inject(context);
        return e.withExtension(CloudEvent.EXT_TRACEPARENT, c.get("traceparent"))
                .withExtension(CloudEvent.EXT_TRACESTATE, c.get("tracestate"));
    }

    public static CloudEvent stampCurrent(CloudEvent e) {
        return stamp(e, Context.current());
    }

    public static Context extract(CloudEvent e) {
        Map<String, String> c = new HashMap<>();
        if (e.ext(CloudEvent.EXT_TRACEPARENT) != null) {
            c.put("traceparent", e.ext(CloudEvent.EXT_TRACEPARENT));
        }
        if (e.ext(CloudEvent.EXT_TRACESTATE) != null) {
            c.put("tracestate", e.ext(CloudEvent.EXT_TRACESTATE));
        }
        return extract(c);
    }

    public static Context fromTraceparent(String traceparent) {
        if (traceparent == null || traceparent.isBlank()) {
            return Context.root();
        }
        return extract(Map.of("traceparent", traceparent));
    }

    public static String currentTraceparent() {
        return inject(Context.current()).get("traceparent");
    }

    public static String traceId(Context context) {
        SpanContext sc = Span.fromContext(context).getSpanContext();
        return sc.isValid() ? sc.getTraceId() : null;
    }
}
