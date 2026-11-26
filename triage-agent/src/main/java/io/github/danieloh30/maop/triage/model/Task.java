package io.github.danieloh30.maop.triage.model;

import tools.jackson.databind.JsonNode;
import io.github.danieloh30.maop.common.model.TaskKind;
import io.github.danieloh30.maop.common.model.TaskState;
import io.github.danieloh30.maop.common.model.Topic;
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
