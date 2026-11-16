package io.github.danieloh30.maop.common.agentic;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;

/** Adapts a {@link DeterministicScript} to the ChatModel interface. The purpose comes from the request marker. */
public final class ScriptedChatModel implements ChatModel {

    private final DeterministicScript script;

    public ScriptedChatModel(DeterministicScript script) {
        this.script = script;
    }

    @Override
    public ChatResponse doChat(ChatRequest request) {
        return script.respond(Purposes.of(request), request);
    }
}
