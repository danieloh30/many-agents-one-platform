package io.github.danieloh30.maop.common.agentic;

import dev.langchain4j.service.memory.ChatMemoryAccess;
import io.github.danieloh30.maop.common.BudgetTracker;
import io.github.danieloh30.maop.common.Ids;
import java.time.Instant;
import java.util.function.Function;

/**
 * Per-invocation limits for a declarative {@code @Agent} call. The agent's model (a {@link GatewayChatModel})
 * reads them on the calling thread, so every model call the agent makes is bounded by the task's deadline and
 * budget exactly like hand-written calls through {@link ModelGateway}.
 *
 * <p>Every call also gets its own chat memory: the framework otherwise attaches one shared memory to the agent, so
 * each prompt would carry earlier tasks' conversations (other incidents' data, growing token use, and stale
 * invocation ids). The agent method takes the fresh id as its {@code @MemoryId} parameter and the memory is evicted
 * when the call returns.
 */
public final class AgentInvocation {

    public record Limits(Instant deadline, BudgetTracker budget) {
    }

    private static final ThreadLocal<Limits> CURRENT = new ThreadLocal<>();

    private AgentInvocation() {
    }

    public static <T> T call(Object agent, Instant deadline, BudgetTracker budget, Function<Object, T> agentCall) {
        Object memoryId = Ids.v7().toString();
        Limits previous = CURRENT.get();
        CURRENT.set(new Limits(deadline, budget));
        try {
            return agentCall.apply(memoryId);
        } finally {
            if (previous == null) {
                CURRENT.remove();
            } else {
                CURRENT.set(previous);
            }
            if (agent instanceof ChatMemoryAccess access) {
                access.evictChatMemory(memoryId);
            }
        }
    }

    public static Limits current() {
        return CURRENT.get();
    }
}
