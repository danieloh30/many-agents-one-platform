package io.github.danieloh30.maop.triage;

import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.langchain4j.model.chat.ChatModel;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

/** Regression: the OpenAI-compatible ChatModel bean must exist so live mode can resolve it (no network call). */
@QuarkusTest
@QuarkusTestResource(value = TriageResources.class, restrictToAnnotatedClass = true)
class LiveModelWiringTest {

    @Inject
    Instance<ChatModel> chatModel;

    @Test
    void providerChatModelIsResolvable() {
        assertTrue(chatModel.isResolvable(), "quarkus-langchain4j-openai ChatModel bean is registered");
    }
}
