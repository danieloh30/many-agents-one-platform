package io.github.danieloh30.maop.triage;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.danieloh30.maop.common.contract.TaskKind;
import io.github.danieloh30.maop.common.contract.TaskState;
import io.github.danieloh30.maop.common.contract.Topic;
import java.time.Instant;
import java.util.UUID;

/** A delegated unit of work owned by a run. */
public final class Task {
    public UUID taskId;
    public UUID runId;
    public TaskKind kind;
    public Topic topic;
    public TaskState state;
    public int deliveries;
    public String failureReason;
    public String detail;
    public JsonNode evidence;
    public JsonNode usage;
    public Instant createdAt;
    public Instant finishedAt;
}
