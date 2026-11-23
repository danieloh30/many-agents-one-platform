package io.github.danieloh30.maop.common.tools;

import io.github.danieloh30.maop.common.model.ActionType;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The tool catalogue shared by tool-service (validation, execution) and agents (model tool specifications).
 * All data is synthetic; all remediation is simulated; sandboxed execution is simulated unless tool-service is
 * configured with a real sandbox runtime.
 */
public final class ToolCatalog {

    public enum ToolClass { READ, REMEDIATION, SANDBOXED }

    public record Param(String name, String type, boolean required, String description, Integer min, Integer max) {
        static Param str(String name, String description) {
            return new Param(name, "string", true, description, null, null);
        }

        static Param integer(String name, String description, int min, int max) {
            return new Param(name, "integer", false, description, min, max);
        }

        static Param bool(String name, String description) {
            return new Param(name, "boolean", true, description, null, null);
        }
    }

    public record Tool(String name, ToolClass toolClass, String description, List<Param> params, ActionType actionType) {
    }

    public static final String ANALYSIS_RUN_SCRIPT = "analysis.run_script";

    public static final List<Tool> TOOLS = List.of(
            new Tool("catalog.get_service", ToolClass.READ, "Service catalogue entry: owner, tier, runtime, feature flags.",
                    List.of(Param.str("service", "Service name, e.g. checkout")), null),
            new Tool("catalog.get_dependencies", ToolClass.READ, "Upstream and downstream dependencies of a service.",
                    List.of(Param.str("service", "Service name"), Param.integer("depth", "Graph depth 1-2", 1, 2)), null),
            new Tool("deployment.get_history", ToolClass.READ, "Recent rollouts of a service with versions, times and change notes.",
                    List.of(Param.str("service", "Service name"), Param.integer("limit", "Max entries 1-10", 1, 10)), null),
            new Tool("deployment.get_diff", ToolClass.READ, "Configuration and image changes between two versions of a service.",
                    List.of(Param.str("service", "Service name"), Param.str("fromVersion", "Older version"),
                            Param.str("toVersion", "Newer version")), null),
            new Tool("telemetry.get_error_summary", ToolClass.READ, "Error-rate summary by endpoint for a recent time window.",
                    List.of(Param.str("service", "Service name"), Param.integer("windowMinutes", "Window 1-60", 1, 60)), null),
            new Tool("remediation.rollback_deployment", ToolClass.REMEDIATION,
                    "SIMULATED rollback of a service to a version. Records the intent only; no cluster change.",
                    List.of(Param.str("service", "Service name"), Param.str("toVersion", "Target version")),
                    ActionType.ROLLBACK_DEPLOYMENT),
            new Tool("remediation.set_feature_flag", ToolClass.REMEDIATION,
                    "SIMULATED feature-flag change. Records the intent only; no system change.",
                    List.of(Param.str("service", "Service name"), Param.str("flag", "Flag name"),
                            Param.bool("enabled", "Desired state")),
                    ActionType.SET_FEATURE_FLAG),
            new Tool(ANALYSIS_RUN_SCRIPT, ToolClass.SANDBOXED,
                    "Runs a short analysis script over recent error logs of a service inside a sandbox. Never offered to the model.",
                    List.of(Param.str("service", "Service name"), Param.str("script", "Analysis script (max 120 characters)"),
                            Param.integer("windowMinutes", "Window 1-60", 1, 60)), null));

    private static final Map<String, Tool> BY_NAME = new LinkedHashMap<>();

    static {
        TOOLS.forEach(t -> BY_NAME.put(t.name(), t));
    }

    private ToolCatalog() {
    }

    public static Optional<Tool> find(String name) {
        return Optional.ofNullable(BY_NAME.get(name));
    }

    public static List<Tool> readTools() {
        return TOOLS.stream().filter(t -> t.toolClass() == ToolClass.READ).toList();
    }

    /** Validate arguments; returns an error message or null when valid. Unknown arguments are rejected. */
    public static String validate(Tool tool, Map<String, Object> args) {
        if (args == null) {
            return "arguments are required";
        }
        for (String k : args.keySet()) {
            if (tool.params().stream().noneMatch(p -> p.name().equals(k))) {
                return "unknown argument '" + k + "'";
            }
        }
        for (Param p : tool.params()) {
            Object v = args.get(p.name());
            if (v == null) {
                if (p.required()) {
                    return "argument '" + p.name() + "' is required";
                }
                continue;
            }
            switch (p.type()) {
                case "string" -> {
                    if (!(v instanceof String s) || s.isBlank() || s.length() > 120) {
                        return "argument '" + p.name() + "' must be a non-empty string (max 120)";
                    }
                }
                case "integer" -> {
                    if (!(v instanceof Number n) || n.doubleValue() != Math.rint(n.doubleValue())) {
                        return "argument '" + p.name() + "' must be an integer";
                    }
                    if ((p.min() != null && n.intValue() < p.min()) || (p.max() != null && n.intValue() > p.max())) {
                        return "argument '" + p.name() + "' must be between " + p.min() + " and " + p.max();
                    }
                }
                case "boolean" -> {
                    if (!(v instanceof Boolean)) {
                        return "argument '" + p.name() + "' must be a boolean";
                    }
                }
                default -> throw new IllegalStateException(p.type());
            }
        }
        return null;
    }
}
