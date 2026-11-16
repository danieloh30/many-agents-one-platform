package io.github.danieloh30.maop.common.agentic;

import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;

/**
 * Explicit, labelled test substitute for a model (ADR-0013). Each agent service provides one implementation
 * returning fixed structured outputs per purpose. Only used when {@code maop.model.mode=deterministic}.
 */
public interface DeterministicScript {

    ChatResponse respond(String purpose, ChatRequest request);
}
