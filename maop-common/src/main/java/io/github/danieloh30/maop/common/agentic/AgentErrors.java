package io.github.danieloh30.maop.common.agentic;

import io.github.danieloh30.maop.common.TaskFailure;

/** Finds a classified {@link TaskFailure} (deadline, budget, model unavailable) inside framework exceptions. */
public final class AgentErrors {

    private AgentErrors() {
    }

    public static TaskFailure taskFailure(Throwable t) {
        for (Throwable c = t; c != null; c = c.getCause()) {
            if (c instanceof TaskFailure tf) {
                return tf;
            }
            if (c.getCause() == c) {
                break;
            }
        }
        return null;
    }

    public static String repairFeedback(String error) {
        return "Your previous answer was invalid: " + error + ". Reply again with ONLY the corrected JSON object.";
    }
}
