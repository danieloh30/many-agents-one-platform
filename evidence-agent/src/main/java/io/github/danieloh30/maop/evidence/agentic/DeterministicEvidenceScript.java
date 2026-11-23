package io.github.danieloh30.maop.evidence.agentic;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import io.github.danieloh30.maop.common.model.Topic;
import io.github.danieloh30.maop.common.agentic.DeterministicScript;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Explicit test substitute: summarises the real tool results it is given (citing their real invocation ids) with
 * fixed wording. It never requests extra tools and reports no token usage.
 */
@ApplicationScoped
public class DeterministicEvidenceScript implements DeterministicScript {

    private static final Pattern INVOCATION = Pattern.compile("\"invocationId\":\"([^\"]+)\",\"toolName\":\"([^\"]+)\",\"status\":\"EXECUTED\"");

    @Override
    public ChatResponse respond(String purpose, ChatRequest request) {
        Topic topic = null;
        String user = "";
        for (ChatMessage m : request.messages()) {
            if (m instanceof SystemMessage s && topic == null) {
                topic = EvidenceAgent.topicOf(s.text());
            }
            if (m instanceof UserMessage u && user.isEmpty()) {
                user = u.singleText();
            }
        }
        List<String> facts = new ArrayList<>();
        Matcher mt = INVOCATION.matcher(user);
        while (mt.find() && facts.size() < 3) {
            facts.add("{\"statement\":\"[deterministic] Result of " + mt.group(2) + " was used.\",\"invocationId\":\""
                    + mt.group(1) + "\",\"toolName\":\"" + mt.group(2) + "\"}");
        }
        String finding = switch (topic == null ? Topic.SERVICE_CATALOG : topic) {
            case DEPLOYMENT_HISTORY -> "[deterministic] checkout 2.14.0 rolled out shortly before the errors began; the previous"
                    + " version 2.13.2 ran for about 26 hours at 0.2% errors on POST /checkout.";
            case CONFIG_DIFF -> "[deterministic] 2.14.0 lowered the payment client timeout from 2000 ms to 300 ms and disabled"
                    + " retries; it has no database migrations and the 2.13.2 image is still available.";
            case ERROR_SUMMARY -> "[deterministic] POST /checkout errors rose to 7.1% (baseline 0.2%), mostly payment client"
                    + " timeouts; payment-gateway p99 latency is 880 ms, unchanged from its baseline.";
            case SERVICE_CATALOG -> "[deterministic] The service is tier 1 and depends on the payment gateway.";
            case LOG_ANALYSIS -> "[deterministic] A sandboxed log analysis of 500 error lines found 68% payment client timeouts"
                    + " at 300 ms, first seen in 2.14.0; every timed-out call would have completed within the previous"
                    + " 2000 ms timeout.";
        };
        String text = "{\"finding\":\"" + finding + "\",\"confidence\":0.75,\"facts\":[" + String.join(",", facts) + "]}";
        return ChatResponse.builder().aiMessage(AiMessage.from(text)).build();
    }
}
