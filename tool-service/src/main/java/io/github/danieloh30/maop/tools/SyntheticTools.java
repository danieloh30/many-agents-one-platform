package io.github.danieloh30.maop.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.danieloh30.maop.common.json.Json;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;

/** Read tools over the clearly labelled synthetic scenario. Times are rendered relative to "now". */
@ApplicationScoped
public class SyntheticTools {

    private JsonNode data;

    @PostConstruct
    void load() throws IOException {
        try (InputStream in = getClass().getResourceAsStream("/synthetic/scenario.json")) {
            data = Json.MAPPER.readTree(in);
        }
    }

    public JsonNode execute(String tool, Map<String, Object> args, Instant now) {
        String service = String.valueOf(args.get("service"));
        ObjectNode out = Json.MAPPER.createObjectNode();
        out.put("dataOrigin", "SYNTHETIC");
        out.put("service", service);
        JsonNode svc = data.path("services").path(service);
        switch (tool) {
            case "catalog.get_service" -> {
                if (svc.isMissingNode()) {
                    return notFound(out, service);
                }
                out.put("owner", svc.path("owner").asText());
                out.put("tier", svc.path("tier").asInt());
                out.put("runtime", svc.path("runtime").asText());
                out.set("featureFlags", svc.path("featureFlags"));
            }
            case "catalog.get_dependencies" -> {
                if (svc.isMissingNode()) {
                    return notFound(out, service);
                }
                out.set("upstream", svc.path("upstream"));
                out.set("downstream", svc.path("downstream"));
                int depth = args.get("depth") instanceof Number n ? n.intValue() : 1;
                if (depth > 1) {
                    ObjectNode second = out.putObject("secondLevel");
                    svc.path("downstream").forEach(d -> second.set(d.asText(),
                            data.path("services").path(d.asText()).path("downstream")));
                }
            }
            case "deployment.get_history" -> {
                JsonNode list = data.path("deployments").path(service);
                if (list.isMissingNode()) {
                    return notFound(out, service);
                }
                int limit = args.get("limit") instanceof Number n ? n.intValue() : 5;
                ArrayNode arr = out.putArray("deployments");
                for (int i = 0; i < Math.min(limit, list.size()); i++) {
                    JsonNode d = list.get(i);
                    ObjectNode e = arr.addObject();
                    e.put("version", d.path("version").asText());
                    e.put("deployedAt", at(now, d.path("minutesAgo").asLong()));
                    e.put("deployedBy", d.path("by").asText());
                    e.put("change", d.path("change").asText());
                }
            }
            case "deployment.get_diff" -> {
                String key = service + ":" + args.get("fromVersion") + ":" + args.get("toVersion");
                JsonNode diff = data.path("diffs").path(key);
                out.put("fromVersion", String.valueOf(args.get("fromVersion")));
                out.put("toVersion", String.valueOf(args.get("toVersion")));
                if (diff.isMissingNode()) {
                    out.put("found", false);
                    out.put("note", "No recorded diff between these versions");
                } else {
                    out.put("found", true);
                    out.set("image", diff.path("image"));
                    out.set("config", diff.path("config"));
                }
            }
            case "telemetry.get_error_summary" -> {
                JsonNode e = data.path("errors").path(service);
                int window = args.get("windowMinutes") instanceof Number n ? n.intValue() : 30;
                out.put("windowMinutes", window);
                out.put("windowEnd", now.truncatedTo(ChronoUnit.SECONDS).toString());
                if (e.isMissingNode()) {
                    out.put("found", false);
                } else {
                    out.put("found", true);
                    JsonNode onset = e.path("onsetMinutesAgo");
                    if (onset.isNumber() && onset.asLong() <= window) {
                        out.put("onsetAt", at(now, onset.asLong()));
                    } else {
                        out.putNull("onsetAt");
                    }
                    out.set("endpoints", e.path("endpoints"));
                }
            }
            default -> throw new IllegalArgumentException("Not a read tool: " + tool);
        }
        return out;
    }

    private static ObjectNode notFound(ObjectNode out, String service) {
        out.put("found", false);
        out.put("note", "Service '" + service + "' is not in the synthetic catalogue");
        return out;
    }

    private static String at(Instant now, long minutesAgo) {
        return now.minus(minutesAgo, ChronoUnit.MINUTES).truncatedTo(ChronoUnit.SECONDS).toString();
    }
}
