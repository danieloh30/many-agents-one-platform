package io.github.danieloh30.maop.common;

import io.github.danieloh30.maop.common.contract.TaskBudget;
import io.github.danieloh30.maop.common.contract.Usage;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/** Per-task budget enforcement and usage accounting (architecture.md section 4.3). Thread-safe. */
public final class BudgetTracker {

    private final TaskBudget budget;
    private final AtomicInteger modelCalls = new AtomicInteger();
    private final AtomicInteger toolCalls = new AtomicInteger();
    private final AtomicLong inputTokens = new AtomicLong();
    private final AtomicLong outputTokens = new AtomicLong();
    private final AtomicInteger denials = new AtomicInteger();

    public BudgetTracker(TaskBudget budget) {
        this.budget = budget;
    }

    public TaskBudget budget() {
        return budget;
    }

    public void beforeModelCall() {
        if (modelCalls.incrementAndGet() > budget.maxModelCalls()) {
            modelCalls.decrementAndGet();
            throw TaskFailure.budget("model calls per task (" + budget.maxModelCalls() + ")");
        }
    }

    public void beforeToolCall() {
        if (toolCalls.incrementAndGet() > budget.maxToolCalls()) {
            toolCalls.decrementAndGet();
            throw TaskFailure.budget("tool calls per task (" + budget.maxToolCalls() + ")");
        }
    }

    public boolean toolCallsRemaining() {
        return toolCalls.get() < budget.maxToolCalls();
    }

    public void tokens(Integer in, Integer out) {
        if (in != null) {
            inputTokens.addAndGet(in);
        }
        if (out != null) {
            outputTokens.addAndGet(out);
        }
    }

    public void denial() {
        denials.incrementAndGet();
    }

    public Usage usage() {
        return new Usage(modelCalls.get(), toolCalls.get(), inputTokens.get(), outputTokens.get(), denials.get());
    }
}
