package io.github.danieloh30.maop.evidence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.danieloh30.maop.common.Ids;
import io.github.danieloh30.maop.common.TaskFailure;
import io.github.danieloh30.maop.common.contract.EvidenceItem;
import io.github.danieloh30.maop.common.contract.EvidenceTaskCommand;
import io.github.danieloh30.maop.common.contract.FailureReason;
import io.github.danieloh30.maop.common.contract.FaultInjection;
import io.github.danieloh30.maop.common.contract.Incident;
import io.github.danieloh30.maop.common.contract.TaskBudget;
import io.github.danieloh30.maop.common.contract.Topic;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;

class EvidenceValidationTest {

    static final EvidenceTaskCommand CMD = new EvidenceTaskCommand(Ids.v7(), Ids.v7(), Topic.CONFIG_DIFF,
            new Incident("t", "d", "checkout"), "q", new TaskBudget(2, 4, 800), Instant.now().plusSeconds(60),
            FaultInjection.NONE, "SUCCESS");
    static final Map<String, String> RETURNED = Map.of("inv-1", "deployment.get_diff");

    @Test
    void acceptsFactsCitingReturnedInvocations() {
        EvidenceItem e = EvidenceAgent.parse(CMD, "```json\n{\"finding\":\"Timeout lowered\",\"confidence\":0.9,"
                + "\"facts\":[{\"statement\":\"timeout 2000ms -> 300ms\",\"invocationId\":\"inv-1\",\"toolName\":\"x\"}]}\n```", RETURNED);
        assertEquals("deployment.get_diff", e.facts().getFirst().toolName(), "tool name comes from our record, not the model");
        assertEquals("SYNTHETIC", e.dataOrigin());
    }

    @Test
    void rejectsFabricatedInvocationIds() {
        TaskFailure f = assertThrows(TaskFailure.class, () -> EvidenceAgent.parse(CMD,
                "{\"finding\":\"x\",\"confidence\":0.5,\"facts\":[{\"statement\":\"s\",\"invocationId\":\"made-up\"}]}", RETURNED));
        assertEquals(FailureReason.MODEL_OUTPUT_INVALID, f.reason());
    }

    @Test
    void rejectsMissingFactsAndBadConfidence() {
        assertThrows(TaskFailure.class, () -> EvidenceAgent.parse(CMD, "{\"finding\":\"x\",\"confidence\":0.5,\"facts\":[]}", RETURNED));
        assertThrows(TaskFailure.class, () -> EvidenceAgent.parse(CMD,
                "{\"finding\":\"x\",\"confidence\":7,\"facts\":[{\"statement\":\"s\",\"invocationId\":\"inv-1\"}]}", RETURNED));
        assertThrows(TaskFailure.class, () -> EvidenceAgent.parse(CMD, "no json", RETURNED));
    }
}
