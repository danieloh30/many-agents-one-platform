package io.github.danieloh30.maop.recommendation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.request.ChatRequest;
import io.github.danieloh30.maop.common.Ids;
import io.github.danieloh30.maop.common.model.EvidenceItem;
import io.github.danieloh30.maop.common.model.Fact;
import io.github.danieloh30.maop.common.model.Recommendation;
import io.github.danieloh30.maop.common.model.RecommendationCommand;
import io.github.danieloh30.maop.common.model.Topic;
import io.github.danieloh30.maop.recommendation.agentic.DeterministicRecommendationScript;
import io.github.danieloh30.maop.recommendation.agentic.RecommendationAgent;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Deterministic mode is what CI's end-to-end run and offline demos use. Its scripted answer must be produced without
 * error and must pass the same validation as a live model's answer.
 */
class DeterministicRecommendationScriptTest {

    @Test
    void scriptedAnswerIsProducedAndPassesValidation() {
        // The wording the deterministic evidence script uses for its findings: it names both versions.
        EvidenceItem history = new EvidenceItem("E-11111111", Ids.v7(), Topic.DEPLOYMENT_HISTORY,
                "[deterministic] checkout 2.14.0 rolled out shortly before the errors began; the previous version 2.13.2 ran"
                        + " for about 26 hours at 0.2% errors on POST /checkout.",
                0.75, List.of(new Fact("[deterministic] Result of deployment.get_history was used.", "inv-1", "deployment.get_history")),
                "SYNTHETIC", null);
        RecommendationCommand cmd = new RecommendationCommand(Ids.v7(), Ids.v7(), null, null, List.of(history), null, null, null,
                Instant.now().plusSeconds(60));
        ChatRequest request = ChatRequest.builder()
                .messages(UserMessage.from("{\"evidence\":[{\"evidenceId\":\"E-11111111\",\"topic\":\"DEPLOYMENT_HISTORY\"}]}"))
                .build();

        String text = new DeterministicRecommendationScript().respond(RecommendationAgent.PURPOSE, request).aiMessage().text();

        assertTrue(text.contains("0.2% errors"), "literal percent signs survive");
        Recommendation r = RecommendationAgent.validate(cmd, text, Set.of("E-11111111"));
        assertEquals(List.of("E-11111111"), r.citations());
        assertEquals("2.13.2", r.proposedAction().parameters().get("toVersion"));
    }
}
