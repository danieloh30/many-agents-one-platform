package io.github.danieloh30.maop.evidence.agentic;

import dev.langchain4j.agentic.Agent;
import dev.langchain4j.agentic.declarative.ChatModelSupplier;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.service.MemoryId;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;
import dev.langchain4j.service.memory.ChatMemoryAccess;
import io.github.danieloh30.maop.common.agentic.GatewayChatModel;

/**
 * Declarative evidence agent. The worker calls the protected tools itself (SPIFFE mTLS + OPA, bounded and
 * idempotent) and hands the results to this agent, which returns a finding whose facts cite those invocations.
 */
public interface EvidenceAnalystAgent extends ChatMemoryAccess {

    @SystemMessage("""
            [purpose:evidence.summarize] [topic:{{topic}}]
            You are an evidence agent in an incident-response platform. Use ONLY the tool results provided to answer
            the question. All data is synthetic demonstration data.
            Respond with ONLY a JSON object:
            {"finding": "one or two factual sentences (max 300 chars)",
             "confidence": number 0..1,
             "facts": [{"statement": "a specific fact (max 200 chars)", "invocationId": "<id of the tool invocation it came from>",
                        "toolName": "<tool name>"}]}
            Include 1 to 5 facts. Every invocationId MUST be one listed in the tool results.
            Keep exact values from the results in the facts: version numbers, percentages, durations and latencies.
            Prefer facts that show the cause, how the previous version behaved, and whether the change can be undone.
            Do not include reasoning steps.""")
    @UserMessage("""
            Incident: {{title}} (service {{service}})
            Question: {{question}}
            Tool results:
            {{toolResults}}
            {{feedback}}""")
    @Agent(name = "evidence-analyst", description = "Turns protected-tool results into a cited evidence finding",
            outputKey = "evidenceFinding")
    EvidenceFinding analyse(@MemoryId Object memoryId, @V("topic") String topic, @V("title") String title, @V("service") String service,
            @V("question") String question, @V("toolResults") String toolResults, @V("feedback") String feedback);

    @ChatModelSupplier
    static ChatModel chatModel() {
        return GatewayChatModel.instance();
    }
}
