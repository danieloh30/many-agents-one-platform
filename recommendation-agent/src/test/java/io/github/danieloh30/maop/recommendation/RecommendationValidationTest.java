package io.github.danieloh30.maop.recommendation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.danieloh30.maop.common.Ids;
import io.github.danieloh30.maop.common.contract.ActionDigests;
import io.github.danieloh30.maop.common.contract.ActionType;
import io.github.danieloh30.maop.common.contract.Recommendation;
import io.github.danieloh30.maop.common.contract.RecommendationCommand;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class RecommendationValidationTest {

    static final RecommendationCommand CMD = new RecommendationCommand(Ids.v7(), Ids.v7(), null, null, List.of(), null, null,
            null, Instant.now().plusSeconds(60));

    static String json(String action, String citations) {
        return "{\"summary\":\"Roll back\",\"explanation\":\"because\",\"proposedAction\":" + action
                + ",\"citations\":" + citations + ",\"confidence\":0.8,\"risks\":[\"r\"]}";
    }

    @Test
    void validRollbackGetsADigestBoundToRunAndArguments() {
        Recommendation r = RecommendationAgent.validate(CMD, json(
                "{\"actionType\":\"ROLLBACK_DEPLOYMENT\",\"target\":\"checkout\",\"parameters\":{\"toVersion\":\"2.13.2\"}}",
                "[\"E-1\"]"), Set.of("E-1"));
        assertTrue(r.proposedAction().sideEffect());
        assertEquals(ActionDigests.digest(CMD.runId(), ActionType.ROLLBACK_DEPLOYMENT, "checkout", Map.of("toVersion", "2.13.2")),
                r.proposedAction().actionDigest());
        assertEquals(Map.of("service", "checkout", "toVersion", "2.13.2"), RecommendationHandler.arguments(r.proposedAction()));
    }

    @Test
    void citationsMustReferenceCollectedEvidence() {
        assertThrows(IllegalArgumentException.class, () -> RecommendationAgent.validate(CMD, json(
                "{\"actionType\":\"OBSERVE\",\"target\":\"checkout\",\"parameters\":{}}", "[\"E-404\"]"), Set.of("E-1")));
        assertThrows(IllegalArgumentException.class, () -> RecommendationAgent.validate(CMD, json(
                "{\"actionType\":\"OBSERVE\",\"target\":\"checkout\",\"parameters\":{}}", "[]"), Set.of("E-1")));
    }

    @Test
    void actionParametersAreChecked() {
        assertThrows(IllegalArgumentException.class, () -> RecommendationAgent.validate(CMD, json(
                "{\"actionType\":\"ROLLBACK_DEPLOYMENT\",\"target\":\"checkout\",\"parameters\":{}}", "[\"E-1\"]"), Set.of("E-1")));
        assertThrows(IllegalArgumentException.class, () -> RecommendationAgent.validate(CMD, json(
                "{\"actionType\":\"DELETE_CLUSTER\",\"target\":\"checkout\",\"parameters\":{}}", "[\"E-1\"]"), Set.of("E-1")));
        Recommendation observe = RecommendationAgent.validate(CMD, json(
                "{\"actionType\":\"ESCALATE_TO_HUMAN\",\"target\":\"checkout\",\"parameters\":{\"x\":1}}", "[\"E-1\"]"), Set.of("E-1"));
        assertEquals(false, observe.proposedAction().sideEffect());
        assertTrue(observe.proposedAction().parameters().isEmpty(), "no parameters smuggled into side-effect-free actions");
    }
}
