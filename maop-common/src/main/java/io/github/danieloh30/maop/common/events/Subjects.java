package io.github.danieloh30.maop.common.events;

/** Streams, subjects and durable consumer names (contracts.md section 3.2). */
public final class Subjects {

    public static final String WORK_STREAM = "MAOP_WORK";
    public static final String EVENTS_STREAM = "MAOP_EVENTS";
    public static final String DLQ_STREAM = "MAOP_DLQ";

    public static final String WORK_TRIAGE = "maop.work.v1.triage";
    public static final String WORK_EVIDENCE = "maop.work.v1.evidence";
    public static final String WORK_RECOMMENDATION = "maop.work.v1.recommendation";

    public static final String EVENTS_PREFIX = "maop.events.v1.";
    public static final String EVENTS_ALL = "maop.events.v1.>";
    public static final String DLQ_PREFIX = "maop.dlq.v1.";

    public static final String CONSUMER_TRIAGE = "triage-workers";
    public static final String CONSUMER_EVIDENCE = "evidence-agents";
    public static final String CONSUMER_RECOMMENDATION = "recommendation-workers";
    public static final String CONSUMER_TRIAGE_RESULTS = "triage-results";

    public static final String MAX_DELIVERIES_ADVISORY = "$JS.EVENT.ADVISORY.CONSUMER.MAX_DELIVERIES." + WORK_STREAM + ".*";

    private Subjects() {
    }
}
