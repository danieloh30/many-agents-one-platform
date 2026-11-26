package io.github.danieloh30.maop.tools.service;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;
import io.github.danieloh30.maop.common.json.Json;
import io.github.danieloh30.maop.common.tools.ToolCatalog;
import io.quarkus.runtime.Startup;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Labelled simulation of sandboxed execution. The script is recorded but never executed, and no sandbox runtime is
 * contacted: the result is the synthetic analysis for the service, marked {@code sandbox.mode = SIMULATED}. There is
 * no fallback: any other configured mode fails at startup.
 */
@ApplicationScoped
@Startup
public class SimulatedSandboxExecutor implements SandboxExecutor {

    public static final String MODE = "SIMULATED";

    @ConfigProperty(name = "maop.sandbox.mode", defaultValue = MODE)
    String configuredMode;

    private JsonNode data;

    @PostConstruct
    void load() throws IOException {
        if (!MODE.equals(configuredMode)) {
            throw new IllegalStateException("maop.sandbox.mode=" + configuredMode
                    + " is not supported: only SIMULATED is implemented (docs/decisions/0018-sandboxed-tool-class.md)");
        }
        try (InputStream in = getClass().getResourceAsStream("/synthetic/scenario.json")) {
            data = Json.MAPPER.readTree(in);
        }
    }

    @Override
    public String mode() {
        return MODE;
    }

    @Override
    public JsonNode run(String tool, Map<String, Object> args, Instant now) {
        if (!ToolCatalog.ANALYSIS_RUN_SCRIPT.equals(tool)) {
            throw new IllegalArgumentException("Not a sandboxed tool: " + tool);
        }
        String service = String.valueOf(args.get("service"));
        int window = args.get("windowMinutes") instanceof Number n ? n.intValue() : 30;
        ObjectNode out = Json.MAPPER.createObjectNode();
        out.put("dataOrigin", "SYNTHETIC");
        out.put("service", service);
        ObjectNode sandbox = out.putObject("sandbox");
        sandbox.put("mode", MODE);
        sandbox.put("executed", false);
        sandbox.put("note", "Simulation only: the script was recorded, not executed, and no sandbox runtime was contacted.");
        out.put("script", String.valueOf(args.get("script")));
        out.put("windowMinutes", window);
        out.put("windowEnd", now.truncatedTo(ChronoUnit.SECONDS).toString());
        JsonNode logs = data.path("logAnalysis").path(service);
        if (logs.isMissingNode()) {
            out.put("found", false);
            out.put("note", "No synthetic logs for service '" + service + "'");
            return out;
        }
        out.put("found", true);
        out.setAll((ObjectNode) logs.deepCopy());
        return out;
    }
}
