package io.github.danieloh30.maop.triage;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.danieloh30.maop.common.contract.Budget;
import io.github.danieloh30.maop.common.contract.FaultInjection;
import io.github.danieloh30.maop.common.contract.Incident;
import io.github.danieloh30.maop.common.contract.Outcome;
import io.github.danieloh30.maop.common.contract.PlanSource;
import io.github.danieloh30.maop.common.contract.RemediationMode;
import io.github.danieloh30.maop.common.contract.RunState;
import io.github.danieloh30.maop.common.contract.Scenario;
import io.github.danieloh30.maop.common.contract.Usage;
import java.time.Instant;
import java.util.UUID;

/** Mutable in-transaction view of a run row. Persisted only by {@link RunStore#update}. */
public final class Run {
    public UUID runId;
    public int version;
    public RunState state;
    public Outcome outcome;
    public String outcomeDetail;
    public Scenario scenario;
    public RemediationMode remediationMode;
    public FaultInjection faultInjection;
    public PlanSource planSource;
    public UUID burstId;
    public Incident incident;
    public JsonNode classification;
    public Budget budget;
    public Usage usage;
    public String modelMode;
    public String modelName;
    public String traceId;
    public String traceparent;
    public JsonNode recommendation;
    public JsonNode executionAttempt;
    public String evidenceCompleteness;
    public JsonNode approval;
    public JsonNode labels;
    public Instant createdAt;
    public Instant updatedAt;
    public Instant deadline;
    public Instant approvalDeadline;
}
