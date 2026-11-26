package io.github.danieloh30.maop.common.json;

import com.fasterxml.jackson.annotation.JsonInclude;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.List;
import tools.jackson.core.JacksonException;

/** Shared JSON configuration plus canonical serialisation (RFC 8785 subset) and digests. */
public final class Json {

    public static final ObjectMapper MAPPER = JsonMapper.builder()
            .changeDefaultPropertyInclusion(incl -> incl.withContentInclusion(JsonInclude.Include.ALWAYS).withValueInclusion(JsonInclude.Include.ALWAYS))
            .build();

    private Json() {
    }

    public static String write(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (JacksonException e) {
            throw new IllegalArgumentException("Cannot serialise " + value.getClass().getSimpleName(), e);
        }
    }

    public static byte[] bytes(Object value) {
        return write(value).getBytes(StandardCharsets.UTF_8);
    }

    public static JsonNode tree(Object value) {
        return MAPPER.valueToTree(value);
    }

    public static <T> T read(String json, Class<T> type) {
        try {
            return MAPPER.readValue(json, type);
        } catch (JacksonException e) {
            throw new IllegalArgumentException("Invalid JSON for " + type.getSimpleName() + ": " + e.getOriginalMessage(), e);
        }
    }

    public static <T> T read(byte[] json, Class<T> type) {
        return read(new String(json, StandardCharsets.UTF_8), type);
    }

    public static <T> T convert(JsonNode node, Class<T> type) {
        try {
            return MAPPER.treeToValue(node, type);
        } catch (JacksonException e) {
            throw new IllegalArgumentException("Invalid payload for " + type.getSimpleName() + ": " + e.getOriginalMessage(), e);
        }
    }

    public static JsonNode parse(String json) {
        try {
            return MAPPER.readTree(json);
        } catch (JacksonException e) {
            throw new IllegalArgumentException("Invalid JSON: " + e.getOriginalMessage(), e);
        }
    }

    /**
     * Canonical JSON: object members sorted by key (UTF-16 code units), no insignificant whitespace.
     * Sufficient for digests over the strings, integers, booleans and nested objects used in this project.
     */
    public static String canonical(Object value) {
        JsonNode node = value instanceof JsonNode n ? n : tree(value);
        StringBuilder sb = new StringBuilder();
        writeCanonical(node, sb);
        return sb.toString();
    }

    private static void writeCanonical(JsonNode node, StringBuilder sb) {
        if (node instanceof ObjectNode obj) {
            List<String> names = new ArrayList<>();
            obj.propertyNames().iterator().forEachRemaining(names::add);
            names.sort(null);
            sb.append('{');
            for (int i = 0; i < names.size(); i++) {
                if (i > 0) {
                    sb.append(',');
                }
                sb.append(MAPPER.getNodeFactory().textNode(names.get(i)).toString()).append(':');
                writeCanonical(obj.get(names.get(i)), sb);
            }
            sb.append('}');
        } else if (node instanceof ArrayNode arr) {
            sb.append('[');
            Iterator<JsonNode> it = arr.values().iterator();
            boolean first = true;
            while (it.hasNext()) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                writeCanonical(it.next(), sb);
            }
            sb.append(']');
        } else {
            sb.append(node == null ? "null" : node.toString());
        }
    }

    public static String sha256(String text) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return "sha256:" + HexFormat.of().formatHex(md.digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public static String digest(Object value) {
        return sha256(canonical(value));
    }

    /** Extract the first top-level JSON object from model output (tolerates code fences and prose). */
    public static String extractObject(String text) {
        if (text == null) {
            return null;
        }
        int start = text.indexOf('{');
        if (start < 0) {
            return null;
        }
        int depth = 0;
        boolean inString = false;
        boolean escape = false;
        for (int i = start; i < text.length(); i++) {
            char c = text.charAt(i);
            if (inString) {
                if (escape) {
                    escape = false;
                } else if (c == '\\') {
                    escape = true;
                } else if (c == '"') {
                    inString = false;
                }
            } else if (c == '"') {
                inString = true;
            } else if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return text.substring(start, i + 1);
                }
            }
        }
        return null;
    }
}
