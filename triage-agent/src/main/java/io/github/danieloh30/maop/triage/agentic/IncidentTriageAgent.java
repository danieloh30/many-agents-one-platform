package io.github.danieloh30.maop.triage.agentic;

import dev.langchain4j.agentic.Agent;
import dev.langchain4j.agentic.declarative.ChatModelSupplier;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.service.MemoryId;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;
import dev.langchain4j.service.memory.ChatMemoryAccess;
import io.github.danieloh30.maop.common.agentic.GatewayChatModel;
import io.github.danieloh30.maop.common.model.TriagePlan;

/**
 * Declarative triage agent (LangChain4j agentic {@code @Agent}, a CDI bean via quarkus-langchain4j-agentic).
 * Its model calls go through {@link GatewayChatModel}, so deadlines, budgets, per-pod limits, token accounting
 * and the deterministic test mode apply unchanged. Orchestration across agents stays durable (JetStream +
 * PostgreSQL), not an in-process agent workflow.
 */
public interface IncidentTriageAgent extends ChatMemoryAccess {

    @SystemMessage("""
            [purpose:triage.classify]
            You are the triage agent of an incident-response platform. Classify the incident and plan the evidence
            to collect. Respond with ONLY a JSON object, no prose, matching:
            {"category": one of RELEASE_REGRESSION|DEPENDENCY_FAILURE|CAPACITY|CONFIGURATION|UNKNOWN,
             "severity": one of SEV1|SEV2|SEV3|SEV4,
             "confidence": number 0..1,
             "rationale": "one or two sentences for an operator (max 300 chars)",
             "tasks": [{"topic": one of SERVICE_CATALOG|DEPLOYMENT_HISTORY|CONFIG_DIFF|ERROR_SUMMARY,
                        "question": "what the evidence agent should find out (max 200 chars)"}]}
            Plan between 1 and {{maxTasks}} tasks with distinct topics. Do not include reasoning steps.""")
    @UserMessage("""
            Incident title: {{title}}
            Affected service: {{service}}
            Description: {{description}}
            {{feedback}}""")
    @Agent(name = "incident-triage", description = "Classifies an incident and plans the evidence to collect",
            outputKey = "triagePlan")
    TriagePlan classify(@MemoryId Object memoryId, @V("title") String title, @V("service") String service, @V("description") String description,
            @V("maxTasks") int maxTasks, @V("feedback") String feedback);

    @ChatModelSupplier
    static ChatModel chatModel() {
        return GatewayChatModel.instance();
    }
}
