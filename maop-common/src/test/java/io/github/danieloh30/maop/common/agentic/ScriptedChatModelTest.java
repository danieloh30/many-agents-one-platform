package io.github.danieloh30.maop.common.agentic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class ScriptedChatModelTest {

    @Test
    void cancelledDelayedCallDoesNotRunTheScript() {
        AtomicInteger calls = new AtomicInteger();
        ScriptedChatModel model = new ScriptedChatModel((purpose, request) -> {
            calls.incrementAndGet();
            return null;
        }, Duration.ofMinutes(1));
        Thread.currentThread().interrupt();
        try {
            assertThrows(IllegalStateException.class, () -> model.doChat(null));
            assertTrue(Thread.currentThread().isInterrupted());
            assertEquals(0, calls.get());
        } finally {
            Thread.interrupted();
        }
    }
}
