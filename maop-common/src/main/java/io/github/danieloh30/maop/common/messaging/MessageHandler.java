package io.github.danieloh30.maop.common.messaging;

/** Handles one message. Throwing means "retryable failure"; the worker applies bounded redelivery. */
@FunctionalInterface
public interface MessageHandler {

    Disposition handle(InboundMessage message) throws Exception;

    /**
     * Called instead of a further redelivery when the handler failed on the final permitted delivery. The
     * implementation records the dead letter (DLQ copy + task.dead_lettered event); the worker then acknowledges
     * the original so the work-queue stream deletes it.
     */
    default void onFinalFailure(InboundMessage message, Exception failure) throws Exception {
    }
}
