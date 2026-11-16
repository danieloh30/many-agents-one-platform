package io.github.danieloh30.maop.common.agentic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.output.TokenUsage;
import io.github.danieloh30.maop.common.BudgetTracker;
import io.github.danieloh30.maop.common.TaskFailure;
import io.github.danieloh30.maop.common.model.FailureReason;
import io.github.danieloh30.maop.common.model.TaskBudget;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class ModelGatewayTest {

    private static ModelGateway gateway(ChatModel model, int permits, Duration callTimeout) {
        ModelGateway g = new ModelGateway();
        g.registry = new SimpleMeterRegistry();
        g.modeName = "live";
        g.modelName = "test-model";
        g.maxConcurrent = permits;
        g.permitTimeout = Duration.ofMillis(200);
        g.callTimeout = callTimeout;
        g.service = "test";
        g.init();
        g.useModel(model);
        return g;
    }

    private static ChatRequest req() {
        return ChatRequest.builder().messages(SystemMessage.from(Purposes.marker("unit") + " hi")).build();
    }

    @Test
    void recordsProviderReportedTokensAndNotifiesListeners() {
        ChatModel m = new ChatModel() {
            @Override
            public ChatResponse doChat(ChatRequest r) {
                return ChatResponse.builder().aiMessage(AiMessage.from("ok")).tokenUsage(new TokenUsage(12, 3)).build();
            }
        };
        ModelGateway g = gateway(m, 1, Duration.ofSeconds(5));
        AtomicInteger seen = new AtomicInteger();
        g.addListener(c -> seen.incrementAndGet());
        BudgetTracker b = new BudgetTracker(new TaskBudget(2, 0, 100));
        ModelCall c = g.call("unit", req(), Instant.now().plusSeconds(30), b);
        assertEquals(12, c.inputTokens());
        assertEquals(15, b.usage().totalTokens());
        assertEquals(1, seen.get());
        assertEquals(15.0, g.registry.get("maop.model.tokens").counters().stream().mapToDouble(x -> x.count()).sum());
    }

    @Test
    void slowModelTimesOutAsRetryableFailure() {
        ChatModel slow = new ChatModel() {
            @Override
            public ChatResponse doChat(ChatRequest r) {
                try {
                    Thread.sleep(5_000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return ChatResponse.builder().aiMessage(AiMessage.from("late")).build();
            }
        };
        ModelGateway g = gateway(slow, 1, Duration.ofMillis(300));
        long start = System.nanoTime();
        TaskFailure f = assertThrows(TaskFailure.class, () -> g.call("unit", req(), Instant.now().plusSeconds(30), null));
        assertEquals(FailureReason.MODEL_UNAVAILABLE, f.reason());
        assertTrue(f.retryable());
        assertTrue((System.nanoTime() - start) / 1_000_000 < 2_000, "returned at the timeout, not after the model");
        assertEquals(0, g.permitsInUse(), "permit released after timeout");
    }

    @Test
    void providerErrorIsRetryableAndCredentialsAreRedacted() {
        ChatModel failing = new ChatModel() {
            @Override
            public ChatResponse doChat(ChatRequest r) {
                throw new RuntimeException("401 invalid api_key: sk-abcdefghijklmnop");
            }
        };
        TaskFailure f = assertThrows(TaskFailure.class,
                () -> gateway(failing, 1, Duration.ofSeconds(2)).call("unit", req(), Instant.now().plusSeconds(30), null));
        assertTrue(f.retryable());
        assertTrue(!f.getMessage().contains("sk-abcdefghijklmnop"), f.getMessage());
    }

    @Test
    void refusesToStartTooCloseToTheDeadline() {
        AtomicInteger calls = new AtomicInteger();
        ChatModel m = new ChatModel() {
            @Override
            public ChatResponse doChat(ChatRequest r) {
                calls.incrementAndGet();
                return ChatResponse.builder().aiMessage(AiMessage.from("x")).build();
            }
        };
        TaskFailure f = assertThrows(TaskFailure.class,
                () -> gateway(m, 1, Duration.ofSeconds(5)).call("unit", req(), Instant.now().plusSeconds(1), null));
        assertEquals(FailureReason.DEADLINE_EXCEEDED, f.reason());
        assertEquals(0, calls.get(), "no model call was started");
    }

    @Test
    void concurrencyIsBoundedPerPod() throws Exception {
        CountDownLatch inside = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ChatModel blocking = new ChatModel() {
            @Override
            public ChatResponse doChat(ChatRequest r) {
                inside.countDown();
                try {
                    release.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return ChatResponse.builder().aiMessage(AiMessage.from("x")).build();
            }
        };
        ModelGateway g = gateway(blocking, 1, Duration.ofSeconds(5));
        Thread t = Thread.ofVirtual().start(() -> g.call("unit", req(), Instant.now().plusSeconds(30), null));
        inside.await();
        assertEquals(1, g.permitsInUse());
        TaskFailure f = assertThrows(TaskFailure.class, () -> g.call("unit", req(), Instant.now().plusSeconds(30), null));
        assertEquals(FailureReason.MODEL_UNAVAILABLE, f.reason());
        assertTrue(f.retryable(), "no permit -> retry later via nak");
        release.countDown();
        t.join();
    }

    @Test
    void budgetExhaustionStopsBeforeCallingTheModel() {
        AtomicInteger calls = new AtomicInteger();
        ChatModel m = new ChatModel() {
            @Override
            public ChatResponse doChat(ChatRequest r) {
                calls.incrementAndGet();
                return ChatResponse.builder().aiMessage(AiMessage.from("x")).build();
            }
        };
        ModelGateway g = gateway(m, 1, Duration.ofSeconds(5));
        BudgetTracker b = new BudgetTracker(new TaskBudget(1, 0, 100));
        g.call("unit", req(), Instant.now().plusSeconds(30), b);
        TaskFailure f = assertThrows(TaskFailure.class, () -> g.call("unit", req(), Instant.now().plusSeconds(30), b));
        assertEquals(FailureReason.BUDGET_EXHAUSTED, f.reason());
        assertEquals(1, calls.get());
    }
}
