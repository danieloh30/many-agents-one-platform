package io.github.danieloh30.maop.evidence;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import io.github.danieloh30.maop.common.contract.Topic;
import io.github.danieloh30.maop.common.model.DeterministicScript;
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
            case DEPLOYMENT_HISTORY -> "[deterministic] A new release of the service was rolled out shortly before the errors began.";
            case CONFIG_DIFF -> "[deterministic] The latest release lowered the payment client timeout and disabled retries.";
            case ERROR_SUMMARY -> "[deterministic] Checkout POST errors rose sharply, dominated by payment client timeouts.";
            case SERVICE_CATALOG -> "[deterministic] The service is tier 1 and depends on the payment gateway.";
        };
        String text = "{\"finding\":\"" + finding + "\",\"confidence\":0.75,\"facts\":[" + String.join(",", facts) + "]}";
        return ChatResponse.builder().aiMessage(AiMessage.from(text)).build();
    }
}
