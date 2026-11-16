package io.github.danieloh30.maop.triage;

import io.github.danieloh30.maop.common.contract.FaultInjection;
import io.github.danieloh30.maop.common.contract.RemediationMode;
import io.github.danieloh30.maop.common.contract.Scenario;
import java.util.Map;

/** POST /v1/runs body (contracts.md section 2.2). */
public record CreateRunRequest(String title, String description, String service, Scenario scenario,
        RemediationMode remediationMode, FaultInjection faultInjection, Map<String, String> labels) {
}
