package io.github.danieloh30.maop.common.model;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.output.TokenUsage;
import io.github.danieloh30.maop.common.BudgetTracker;
import io.github.danieloh30.maop.common.Deadlines;
import io.github.danieloh30.maop.common.TaskFailure;
import io.github.danieloh30.maop.common.contract.FailureReason;
import io.github.danieloh30.maop.common.contract.ModelInfo;
import io.github.danieloh30.maop.common.contract.ModelMode;
import io.github.danieloh30.maop.common.tracing.Tracing;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * The only path from agent code to a model. Enforces the per-pod concurrency limit, deadline-derived timeouts and
 * task budgets; records provider-reported token usage, metrics and a span per call. Never logs or traces prompt or
 * completion text. There is no fallback between live and deterministic modes.
 */
@ApplicationScoped
public class ModelGateway {

    private static final Logger LOG = Logger.getLogger(ModelGateway.class);

    @Inject
    Instance<ChatModel> liveModel;
    @Inject
    Instance<DeterministicScript> script;
    @Inject
    MeterRegistry registry;

    @ConfigProperty(name = "maop.model.mode", defaultValue = "live")
    String modeName;
    @ConfigProperty(name = "maop.model.name", defaultValue = "unknown")
    String modelName;
    @ConfigProperty(name = "maop.model.max-concurrent", defaultValue = "2")
    int maxConcurrent;
    @ConfigProperty(name = "maop.model.permit-timeout", defaultValue = "10s")
    Duration permitTimeout;
    @ConfigProperty(name = "maop.model.call-timeout", defaultValue = "20s")
    Duration callTimeout;
    @ConfigProperty(name = "quarkus.application.name", defaultValue = "maop")
    String service;

    private Semaphore permits;
    private ModelMode mode;
    private volatile ChatModel model;
    private final List<Consumer<ModelCall>> listeners = new CopyOnWriteArrayList<>();

    @PostConstruct
    void init() {
        mode = ModelMode.valueOf(modeName.trim().toUpperCase(Locale.ROOT));
        permits = new Semaphore(maxConcurrent, true);
        Gauge.builder("maop.model.permits.in.use", permits, p -> maxConcurrent - p.availablePermits())
                .tag("service", service).description("Model call permits currently held in this pod").register(registry);
        LOG.infof("Model mode %s, model %s, max concurrent calls %d", mode, displayName(), maxConcurrent);
    }

    /** Test hook: use a specific model instance. */
    void useModel(ChatModel m) {
        this.model = m;
    }

    public ModelMode mode() {
        return mode;
    }

    public String displayName() {
        return mode == ModelMode.DETERMINISTIC ? "scripted" : modelName;
    }

    public ModelInfo info() {
        return new ModelInfo(mode, displayName());
    }

    public void addListener(Consumer<ModelCall> listener) {
        listeners.add(listener);
    }

    public int permitsInUse() {
        return maxConcurrent - permits.availablePermits();
    }

    private ChatModel model() {
        ChatModel m = model;
        if (m == null) {
            synchronized (this) {
                if (model == null) {
                    if (mode == ModelMode.DETERMINISTIC) {
                        if (!script.isResolvable()) {
                            throw new IllegalStateException("Deterministic mode selected but no DeterministicScript bean");
                        }
                        model = new ScriptedChatModel(script.get());
                    } else {
                        if (!liveModel.isResolvable()) {
                            throw new IllegalStateException("Live model mode selected but no ChatModel is configured");
                        }
                        model = liveModel.get();
                    }
                }
                m = model;
            }
        }
        return m;
    }

    /**
     * Execute one model call for {@code purpose}. Throws {@link TaskFailure}: DEADLINE_EXCEEDED / BUDGET_EXHAUSTED
     * (not retryable) or MODEL_UNAVAILABLE (retryable: timeout, transport error, rate limit, no permit).
     */
    public ModelCall call(String purpose, ChatRequest request, Instant deadline, BudgetTracker budget) {
        Duration timeout = Deadlines.callTimeout(deadline, callTimeout, "model call (" + purpose + ")");
        if (budget != null) {
            budget.beforeModelCall();
        }
        boolean acquired;
        try {
            acquired = permits.tryAcquire(permitTimeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new TaskFailure(FailureReason.MODEL_UNAVAILABLE, true, "Interrupted waiting for a model permit");
        }
        if (!acquired) {
            count(purpose, "no_permit");
            throw new TaskFailure(FailureReason.MODEL_UNAVAILABLE, true, "No model permit within " + permitTimeout);
        }
        Span span = Tracing.tracer().spanBuilder("chat " + purpose).setSpanKind(SpanKind.CLIENT)
                .setAttribute("gen_ai.operation.name", "chat")
                .setAttribute("gen_ai.request.model", displayName())
                .setAttribute("maop.model.mode", mode.name())
                .setAttribute("maop.model.purpose", purpose)
                .startSpan();
        long start = System.nanoTime();
        try (Scope ignored = span.makeCurrent()) {
            ChatModel m = model();
            Context ctx = Context.current();
            CompletableFuture<ChatResponse> f = new CompletableFuture<>();
            Thread worker = Thread.ofVirtual().name("maop-model-call").start(ctx.wrap(() -> {
                try {
                    f.complete(m.chat(request));
                } catch (Throwable t) {
                    f.completeExceptionally(t);
                }
            }));
            ChatResponse response;
            try {
                response = f.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
            } catch (TimeoutException e) {
                worker.interrupt();
                count(purpose, "timeout");
                throw new TaskFailure(FailureReason.MODEL_UNAVAILABLE, true, "Model call timed out after " + timeout);
            } catch (ExecutionException e) {
                Throwable cause = e.getCause() == null ? e : e.getCause();
                count(purpose, "error");
                throw new TaskFailure(FailureReason.MODEL_UNAVAILABLE, true,
                        "Model call failed: " + cause.getClass().getSimpleName() + ": " + safe(cause.getMessage()), cause);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                worker.interrupt();
                throw new TaskFailure(FailureReason.MODEL_UNAVAILABLE, true, "Interrupted during model call");
            }
            long durationMs = (System.nanoTime() - start) / 1_000_000;
            TokenUsage tu = response.tokenUsage();
            Integer in = tu == null ? null : tu.inputTokenCount();
            Integer out = tu == null ? null : tu.outputTokenCount();
            if (budget != null) {
                budget.tokens(in, out);
            }
            if (in != null) {
                span.setAttribute("gen_ai.usage.input_tokens", in.longValue());
                Counter.builder("maop.model.tokens").tag("service", service).tag("type", "input")
                        .description("Provider-reported tokens").register(registry).increment(in);
            }
            if (out != null) {
                span.setAttribute("gen_ai.usage.output_tokens", out.longValue());
                Counter.builder("maop.model.tokens").tag("service", service).tag("type", "output")
                        .description("Provider-reported tokens").register(registry).increment(out);
            }
            if (response.finishReason() != null) {
                span.setAttribute("gen_ai.response.finish_reasons", response.finishReason().name());
            }
            Timer.builder("maop.model.call.duration").tag("service", service).tag("purpose", purpose)
                    .publishPercentileHistogram().register(registry).record(Duration.ofMillis(durationMs));
            count(purpose, "ok");
            ModelCall call = new ModelCall(purpose, response, in, out, durationMs, mode.name(), displayName());
            for (Consumer<ModelCall> l : listeners) {
                try {
                    l.accept(call);
                } catch (RuntimeException e) {
                    LOG.debug("Model call listener failed", e);
                }
            }
            return call;
        } catch (TaskFailure e) {
            span.setStatus(StatusCode.ERROR, e.reason().name());
            span.setAttribute("maop.failure.reason", e.reason().name());
            throw e;
        } finally {
            span.end();
            permits.release();
        }
    }

    private void count(String purpose, String status) {
        Counter.builder("maop.model.calls").tag("service", service).tag("purpose", purpose).tag("status", status)
                .tag("mode", mode.name()).description("Model calls by outcome").register(registry).increment();
    }

    private static String safe(String message) {
        if (message == null) {
            return "";
        }
        // Never echo credentials that a provider error might reflect.
        String m = message.replaceAll("(?i)(bearer\\s+|api[-_ ]?key[\"':= ]+)[A-Za-z0-9._\\-]{8,}", "$1[redacted]")
                .replaceAll("sk-[A-Za-z0-9_\\-]{8,}", "[redacted]");
        return m.length() > 300 ? m.substring(0, 300) : m;
    }
}
