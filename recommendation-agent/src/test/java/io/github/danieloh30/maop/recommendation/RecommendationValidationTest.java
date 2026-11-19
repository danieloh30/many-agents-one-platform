package io.github.danieloh30.maop.recommendation;

import io.github.danieloh30.maop.recommendation.agentic.RecommendationAgent;
import io.github.danieloh30.maop.recommendation.service.RecommendationHandler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.danieloh30.maop.common.Ids;
import io.github.danieloh30.maop.common.model.ActionDigests;
import io.github.danieloh30.maop.common.model.ActionType;
import io.github.danieloh30.maop.common.model.EvidenceItem;
import io.github.danieloh30.maop.common.model.Fact;
import io.github.danieloh30.maop.common.model.Recommendation;
import io.github.danieloh30.maop.common.model.RecommendationCommand;
import io.github.danieloh30.maop.common.model.Topic;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class RecommendationValidationTest {

    static final EvidenceItem HISTORY = new EvidenceItem("E-1", Ids.v7(), Topic.DEPLOYMENT_HISTORY,
            "checkout v2.14.0 replaced 2.13.2 nine minutes ago.", 0.9,
            List.of(new Fact("2.13.2 ran 1551 minutes at 0.2% errors on POST /checkout.", "inv-1", "deployment.get_history")),
            "SYNTHETIC", null);
    static final RecommendationCommand CMD = new RecommendationCommand(Ids.v7(), Ids.v7(), null, null, List.of(HISTORY), null,
            null, null, Instant.now().plusSeconds(60));

    static String json(String action, String citations) {
        return json("because", action, citations);
    }

    static String json(String explanation, String action, String citations) {
        return "{\"summary\":\"Roll back\",\"explanation\":\"" + explanation + "\",\"proposedAction\":" + action
                + ",\"citations\":" + citations + ",\"confidence\":0.8,\"risks\":[\"r\"]}";
    }

    static final String ROLLBACK_2_13_2 =
            "{\"actionType\":\"ROLLBACK_DEPLOYMENT\",\"target\":\"checkout\",\"parameters\":{\"toVersion\":\"2.13.2\"}}";

    @Test
    void versionsMustComeFromTheEvidence() {
        Recommendation ok = RecommendationAgent.validate(CMD, json(
                "2.14.0 cut the timeout; v2.13.2 ran 26 h at 0.2% errors.", ROLLBACK_2_13_2, "[\"E-1\"]"), Set.of("E-1"));
        assertEquals("2.13.2", ok.proposedAction().parameters().get("toVersion"));
        IllegalArgumentException wrongTarget = assertThrows(IllegalArgumentException.class, () -> RecommendationAgent.validate(CMD,
                json("because", ROLLBACK_2_13_2.replace("2.13.2", "2.13.0"), "[\"E-1\"]"), Set.of("E-1")));
        assertTrue(wrongTarget.getMessage().contains("2.13.0"), wrongTarget.getMessage());
        assertThrows(IllegalArgumentException.class, () -> RecommendationAgent.validate(CMD,
                json("Rolling back to version 2.13.0 restores it.", ROLLBACK_2_13_2, "[\"E-1\"]"), Set.of("E-1")));
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
