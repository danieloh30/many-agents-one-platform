package io.github.danieloh30.maop.triage;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import io.github.danieloh30.maop.common.model.DeterministicScript;
import jakarta.enterprise.context.ApplicationScoped;

/** Explicit test substitute (maop.model.mode=deterministic). Reports no token usage: nothing is invented. */
@ApplicationScoped
public class DeterministicTriageScript implements DeterministicScript {

    @Override
    public ChatResponse respond(String purpose, ChatRequest request) {
        String text = switch (purpose) {
            case TriagePlanner.PURPOSE -> """
                    {"category":"RELEASE_REGRESSION","severity":"SEV2","confidence":0.8,
                     "rationale":"[deterministic] Error increase follows a recent release of the affected service.",
                     "tasks":[{"topic":"DEPLOYMENT_HISTORY","question":"Which releases of the service happened in the last hour?"},
                              {"topic":"CONFIG_DIFF","question":"What changed between the last two versions?"},
                              {"topic":"ERROR_SUMMARY","question":"Which endpoints and error types increased, and when?"}]}""";
            default -> "{}";
        };
        return ChatResponse.builder().aiMessage(AiMessage.from(text)).build();
    }
}
