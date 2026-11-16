package io.github.danieloh30.maop.common.agentic;

import io.github.danieloh30.maop.common.BudgetTracker;
import java.time.Instant;
import java.util.function.Supplier;

/**
 * Per-invocation limits for a declarative {@code @Agent} call. The agent's model (a {@link GatewayChatModel})
 * reads them on the calling thread, so every model call the agent makes is bounded by the task's deadline and
 * budget exactly like hand-written calls through {@link ModelGateway}.
 */
public final class AgentInvocation {

    public record Limits(Instant deadline, BudgetTracker budget) {
    }

    private static final ThreadLocal<Limits> CURRENT = new ThreadLocal<>();

    private AgentInvocation() {
    }

    public static <T> T call(Instant deadline, BudgetTracker budget, Supplier<T> agentCall) {
        Limits previous = CURRENT.get();
        CURRENT.set(new Limits(deadline, budget));
        try {
            return agentCall.get();
        } finally {
            if (previous == null) {
                CURRENT.remove();
            } else {
                CURRENT.set(previous);
            }
        }
    }

    public static Limits current() {
        return CURRENT.get();
    }
}
