package io.github.danieloh30.maop.triage.model;

import io.github.danieloh30.maop.common.model.AnalysisMode;
import io.github.danieloh30.maop.common.model.FaultInjection;
import io.github.danieloh30.maop.common.model.RemediationMode;
import io.github.danieloh30.maop.common.model.Scenario;
import java.util.Map;

/** POST /v1/runs body (contracts.md section 2.2). */
public record CreateRunRequest(String title, String description, String service, Scenario scenario,
        RemediationMode remediationMode, FaultInjection faultInjection, AnalysisMode analysisMode, Map<String, String> labels) {
}
