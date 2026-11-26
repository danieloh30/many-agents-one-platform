package io.github.danieloh30.maop.common.agentic;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import java.time.Duration;

/** Adapts a {@link DeterministicScript} to the ChatModel interface. The purpose comes from the request marker. */
public final class ScriptedChatModel implements ChatModel {

    private final DeterministicScript script;
    private final Duration delay;

    public ScriptedChatModel(DeterministicScript script) {
        this(script, Duration.ZERO);
    }

    public ScriptedChatModel(DeterministicScript script, Duration delay) {
        if (delay.isNegative()) {
            throw new IllegalArgumentException("Deterministic model delay must not be negative");
        }
        this.script = script;
        this.delay = delay;
    }

    @Override
    public ChatResponse doChat(ChatRequest request) {
        if (!delay.isZero()) {
            try {
                Thread.sleep(delay);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Deterministic model call interrupted", e);
            }
        }
        return script.respond(Purposes.of(request), request);
    }
}
