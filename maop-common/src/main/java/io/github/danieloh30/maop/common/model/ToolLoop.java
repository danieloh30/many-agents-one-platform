package io.github.danieloh30.maop.common.model;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import io.github.danieloh30.maop.common.BudgetTracker;
import io.github.danieloh30.maop.common.tools.ToolCatalog;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * An explicit, bounded tool-calling loop: at most {@code budget.maxModelCalls} model calls and
 * {@code budget.maxToolCalls} tool calls. The last permitted call is made without tool specifications so the
 * model must answer. Returns the final assistant text.
 */
public final class ToolLoop {

    private ToolLoop() {
    }

    public static List<ToolSpecification> specifications(List<ToolCatalog.Tool> tools) {
        List<ToolSpecification> specs = new ArrayList<>();
        for (ToolCatalog.Tool t : tools) {
            JsonObjectSchema.Builder p = JsonObjectSchema.builder();
            List<String> required = new ArrayList<>();
            for (ToolCatalog.Param param : t.params()) {
                switch (param.type()) {
                    case "integer" -> p.addIntegerProperty(param.name(), param.description());
                    case "boolean" -> p.addBooleanProperty(param.name(), param.description());
                    default -> p.addStringProperty(param.name(), param.description());
                }
                if (param.required()) {
                    required.add(param.name());
                }
            }
            p.required(required);
            specs.add(ToolSpecification.builder().name(t.name().replace('.', '_')).description(t.description())
                    .parameters(p.build()).build());
        }
        return specs;
    }

    public static String toolName(String specName) {
        int i = specName.indexOf('_');
        return i < 0 ? specName : specName.substring(0, i) + "." + specName.substring(i + 1);
    }

    public static String run(ModelGateway gateway, String purpose, List<ChatMessage> messages,
            List<ToolSpecification> tools, Function<ToolExecutionRequest, String> executor, BudgetTracker budget,
            Instant deadline, int maxOutputTokens) {
        int calls = budget.budget().maxModelCalls();
        for (int i = 0; i < calls; i++) {
            boolean last = i == calls - 1 || !budget.toolCallsRemaining();
            ChatRequest.Builder req = ChatRequest.builder().messages(messages).maxOutputTokens(maxOutputTokens);
            if (!last && !tools.isEmpty()) {
                req.toolSpecifications(tools);
            }
            AiMessage ai = gateway.call(purpose, req.build(), deadline, budget).response().aiMessage();
            if (ai.hasToolExecutionRequests() && !last) {
                messages.add(ai);
                for (ToolExecutionRequest r : ai.toolExecutionRequests()) {
                    String result = budget.toolCallsRemaining() ? executor.apply(r)
                            : "{\"error\":\"tool-call budget exhausted\"}";
                    messages.add(ToolExecutionResultMessage.from(r, result));
                }
                if (i == calls - 2) {
                    messages.add(UserMessage.from("Now answer with ONLY the final JSON object."));
                }
                continue;
            }
            return ai.text();
        }
        return null;
    }
}
