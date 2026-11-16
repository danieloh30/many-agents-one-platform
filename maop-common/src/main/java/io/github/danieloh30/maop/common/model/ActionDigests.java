package io.github.danieloh30.maop.common.model;

import io.github.danieloh30.maop.common.json.Json;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

/** Binds an approval to exactly one action: sha256 over canonical {runId, actionType, target, parameters}. */
public final class ActionDigests {

    private ActionDigests() {
    }

    public static String digest(UUID runId, ActionType actionType, String target, Map<String, ?> parameters) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("runId", runId.toString());
        m.put("actionType", actionType.name());
        m.put("target", target);
        m.put("parameters", parameters == null ? Map.of() : new TreeMap<>(parameters));
        return Json.digest(m);
    }

    public static ProposedAction withDigest(UUID runId, ProposedAction a) {
        return new ProposedAction(a.actionType(), a.target(), a.parameters(), a.actionType().sideEffect(),
                digest(runId, a.actionType(), a.target(), a.parameters()));
    }
}
