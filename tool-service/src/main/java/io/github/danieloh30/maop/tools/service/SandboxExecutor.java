package io.github.danieloh30.maop.tools.service;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.Map;

/**
 * Runs a sandboxed tool after policy allowed it. The only implementation today is the labelled simulation; a real
 * sandbox runtime would be a second implementation selected by {@code maop.sandbox.mode} (ADR-0018).
 */
public interface SandboxExecutor {

    /** The mode reported on every result, e.g. {@code SIMULATED}. */
    String mode();

    JsonNode run(String tool, Map<String, Object> args, Instant now);
}
