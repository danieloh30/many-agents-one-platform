package io.github.danieloh30.maop.common.contract;

public enum ActionType {
    ROLLBACK_DEPLOYMENT,
    SET_FEATURE_FLAG,
    ESCALATE_TO_HUMAN,
    OBSERVE;

    /** Whether executing this action type requires a (simulated) remediation tool call. */
    public boolean sideEffect() {
        return this == ROLLBACK_DEPLOYMENT || this == SET_FEATURE_FLAG;
    }

    /** The remediation tool that performs this action, or null for actions without side effects. */
    public String toolName() {
        return switch (this) {
            case ROLLBACK_DEPLOYMENT -> "remediation.rollback_deployment";
            case SET_FEATURE_FLAG -> "remediation.set_feature_flag";
            default -> null;
        };
    }
}
