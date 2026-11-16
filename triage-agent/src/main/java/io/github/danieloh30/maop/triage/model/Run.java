package io.github.danieloh30.maop.triage.model;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.danieloh30.maop.common.model.Budget;
import io.github.danieloh30.maop.common.model.FaultInjection;
import io.github.danieloh30.maop.common.model.Incident;
import io.github.danieloh30.maop.common.model.Outcome;
import io.github.danieloh30.maop.common.model.PlanSource;
import io.github.danieloh30.maop.common.model.RemediationMode;
import io.github.danieloh30.maop.common.model.RunState;
import io.github.danieloh30.maop.common.model.Scenario;
import io.github.danieloh30.maop.common.model.Usage;
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
