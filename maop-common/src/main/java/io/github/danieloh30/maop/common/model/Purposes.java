package io.github.danieloh30.maop.common.model;

import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.model.chat.request.ChatRequest;

/**
 * The model call's purpose is carried in the first system message as a marker line {@code [purpose:<name>]} so a
 * deterministic script can answer without inspecting free text. The marker is harmless for live models.
 */
public final class Purposes {

    private Purposes() {
    }

    public static String marker(String purpose) {
        return "[purpose:" + purpose + "]";
    }

    public static String of(ChatRequest request) {
        for (ChatMessage m : request.messages()) {
            if (m instanceof SystemMessage s) {
                String t = s.text();
                int i = t.indexOf("[purpose:");
                if (i >= 0) {
                    int j = t.indexOf(']', i);
                    return t.substring(i + 9, j);
                }
            }
        }
        return "unknown";
    }
}
