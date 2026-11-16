package io.github.danieloh30.maop.common.model;

import dev.langchain4j.model.chat.response.ChatResponse;

/** One completed model call with provider-reported token usage (null when the provider did not report it). */
public record ModelCall(String purpose, ChatResponse response, Integer inputTokens, Integer outputTokens,
        long durationMs, String mode, String model) {
}
