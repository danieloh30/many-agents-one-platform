package io.github.danieloh30.maop.evidence;

import dev.langchain4j.model.chat.ChatModel;
import io.quarkus.arc.Unremovable;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * A direct ChatModel injection point. Quarkus LangChain4j registers the provider's ChatModel bean only when an
 * application bean injects it; ModelGateway looks the bean up lazily, so this keeps it registered. Creating the
 * bean does not call the provider.
 */
@ApplicationScoped
@Unremovable
public class LiveModelWiring {

    @Inject
    ChatModel chatModel;
}
