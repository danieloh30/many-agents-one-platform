package io.github.danieloh30.maop.recommendation.agentic;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import io.github.danieloh30.maop.common.agentic.DeterministicScript;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Explicit test substitute: proposes the rollback the synthetic scenario implies, citing the real evidence ids. */
@ApplicationScoped
public class DeterministicRecommendationScript implements DeterministicScript {

    private static final Pattern EVIDENCE_ID = Pattern.compile("\"evidenceId\":\"([^\"]+)\"");

    @Override
    public ChatResponse respond(String purpose, ChatRequest request) {
        String user = "";
        for (ChatMessage m : request.messages()) {
            if (m instanceof UserMessage u) {
                user = u.singleText();
                break;
            }
        }
        List<String> ids = new ArrayList<>();
        Matcher mt = EVIDENCE_ID.matcher(user);
        while (mt.find()) {
            ids.add("\"" + mt.group(1) + "\"");
        }
        String text = """
                {"summary":"[deterministic] Roll back checkout to 2.13.2",
                 "explanation":"[deterministic] checkout 2.14.0 cut the payment client timeout to 300 ms and disabled retries, while payment-gateway p99 latency is an unchanged 880 ms, so slow payments now fail. 2.13.2 ran about 26 hours at 0.2% errors. The release has no database migrations, so the rollback is reversible; expect POST /checkout errors back near 0.2%.",
                 "proposedAction":{"actionType":"ROLLBACK_DEPLOYMENT","target":"checkout","parameters":{"toVersion":"2.13.2"}},
                 "citations":[%s],"confidence":0.8,"risks":["Rollback also disables the new checkout flow"]}"""
                .formatted(String.join(",", ids));
        return ChatResponse.builder().aiMessage(AiMessage.from(text)).build();
    }
}
