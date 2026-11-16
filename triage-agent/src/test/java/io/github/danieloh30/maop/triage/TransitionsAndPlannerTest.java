package io.github.danieloh30.maop.triage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.danieloh30.maop.common.contract.RunState;
import io.github.danieloh30.maop.common.contract.TriagePlan;
import org.junit.jupiter.api.Test;

class TransitionsAndPlannerTest {

    @Test
    void onlyDocumentedTransitionsArePermitted() {
        assertTrue(Transitions.allowed(RunState.SUBMITTED, RunState.TRIAGING));
        assertTrue(Transitions.allowed(RunState.RECOMMENDING, RunState.AWAITING_APPROVAL));
        assertFalse(Transitions.allowed(RunState.SUBMITTED, RunState.RECOMMENDING));
        assertFalse(Transitions.allowed(RunState.AWAITING_APPROVAL, RunState.TIMED_OUT), "approval waits use their own deadline");
        assertFalse(Transitions.allowed(RunState.TRIAGING, RunState.EXECUTING_ACTION));
        for (RunState terminal : RunState.values()) {
            if (terminal.terminal()) {
                for (RunState to : RunState.values()) {
                    assertFalse(Transitions.allowed(terminal, to), terminal + " is immutable");
                }
            }
        }
        for (RunState s : RunState.values()) {
            if (!s.terminal()) {
                assertTrue(Transitions.allowed(s, RunState.CANCELLED));
                assertTrue(Transitions.allowed(s, RunState.NEEDS_REVIEW));
            }
        }
        assertThrows(Transitions.IllegalTransitionException.class, () -> Transitions.check(RunState.COMPLETED, RunState.TRIAGING));
    }

    @Test
    void plannerRejectsInvalidModelOutput() {
        TriagePlan ok = TriagePlanner.parse("""
                ```json
                {"category":"RELEASE_REGRESSION","severity":"SEV2","confidence":0.7,"rationale":"r",
                 "tasks":[{"topic":"CONFIG_DIFF","question":"q"}]}
                ```""");
        assertEquals(1, ok.tasks().size());
        assertThrows(IllegalArgumentException.class, () -> TriagePlanner.parse("no json"));
        assertThrows(IllegalArgumentException.class, () -> TriagePlanner.parse(
                "{\"category\":\"RELEASE_REGRESSION\",\"severity\":\"SEV2\",\"confidence\":1.7,\"rationale\":\"r\",\"tasks\":[{\"topic\":\"CONFIG_DIFF\",\"question\":\"q\"}]}"));
        assertThrows(IllegalArgumentException.class, () -> TriagePlanner.parse(
                "{\"category\":\"RELEASE_REGRESSION\",\"severity\":\"SEV2\",\"confidence\":0.5,\"rationale\":\"r\",\"tasks\":[{\"topic\":\"CONFIG_DIFF\",\"question\":\"q\"},{\"topic\":\"CONFIG_DIFF\",\"question\":\"q\"}]}"));
    }
}
