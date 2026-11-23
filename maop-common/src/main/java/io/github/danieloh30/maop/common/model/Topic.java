package io.github.danieloh30.maop.common.model;

public enum Topic {
    SERVICE_CATALOG,
    DEPLOYMENT_HISTORY,
    CONFIG_DIFF,
    ERROR_SUMMARY,
    /** Planned by code for runs with {@link AnalysisMode#SANDBOXED_SCRIPT}; the model cannot plan it. */
    LOG_ANALYSIS
}
