package io.github.danieloh30.maop.common.agentic;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import io.quarkus.arc.Arc;

/**
 * The ChatModel handed to every {@code @Agent} through its {@code @ChatModelSupplier}. It delegates each call to
 * {@link ModelGateway}, which applies the per-pod concurrency limit, the deadline-derived timeout, the task budget,
 * provider-reported token accounting, metrics and spans, and selects the live or the labelled deterministic model.
 * The call's purpose comes from the {@code [purpose:...]} marker in the agent's system message.
 */
public final class GatewayChatModel implements ChatModel {

    private static final GatewayChatModel INSTANCE = new GatewayChatModel();

    private GatewayChatModel() {
    }

    public static ChatModel instance() {
        return INSTANCE;
    }

    @Override
    public ChatResponse doChat(ChatRequest request) {
        AgentInvocation.Limits limits = AgentInvocation.current();
        ModelGateway gateway = Arc.container().instance(ModelGateway.class).get();
        return gateway.call(Purposes.of(request), request, limits == null ? null : limits.deadline(),
                limits == null ? null : limits.budget()).response();
    }
}
