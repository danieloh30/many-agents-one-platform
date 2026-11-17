package io.github.danieloh30.maop.recommendation.agentic;

import dev.langchain4j.agentic.Agent;
import dev.langchain4j.agentic.declarative.ChatModelSupplier;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.service.MemoryId;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;
import dev.langchain4j.service.memory.ChatMemoryAccess;
import io.github.danieloh30.maop.common.agentic.GatewayChatModel;

/** Declarative recommendation agent: one evidence-backed proposed next action (a proposal, never an execution). */
public interface RemediationAdvisorAgent extends ChatMemoryAccess {

    @SystemMessage("""
            [purpose:recommendation.propose]
            You are the recommendation agent of an incident-response platform. Propose ONE next action supported by
            the evidence. Any remediation is only a proposal: a human must approve it and execution is simulated.
            Respond with ONLY a JSON object:
            {"summary": "short imperative sentence (max 160 chars)",
             "explanation": "concise justification for an operator, citing the evidence (max 500 chars)",
             "proposedAction": {"actionType": one of ROLLBACK_DEPLOYMENT|SET_FEATURE_FLAG|ESCALATE_TO_HUMAN|OBSERVE,
                                "target": "service name",
                                "parameters": {}},
             "citations": ["evidenceId", ...],
             "confidence": number 0..1,
             "risks": ["short risk", ...]}
            parameters: ROLLBACK_DEPLOYMENT needs {"toVersion": "x.y.z"}; SET_FEATURE_FLAG needs {"flag": "name", "enabled": false};
            otherwise {}. Cite at least one evidenceId from the evidence list and at most 3 risks.
            Copy service names and version numbers exactly as they appear in the evidence facts; never invent a version.
            Do not include reasoning steps.""")
    @UserMessage("""
            {{context}}
            {{feedback}}""")
    @Agent(name = "remediation-advisor", description = "Proposes one evidence-backed next action for an incident",
            outputKey = "recommendation")
    RecommendationDraft propose(@MemoryId Object memoryId, @V("context") String context, @V("feedback") String feedback);

    @ChatModelSupplier
    static ChatModel chatModel() {
        return GatewayChatModel.instance();
    }
}
