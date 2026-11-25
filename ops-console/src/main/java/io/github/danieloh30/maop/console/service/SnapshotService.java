package io.github.danieloh30.maop.console.service;

import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;
import io.fabric8.kubernetes.api.model.GenericKubernetesResource;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.github.danieloh30.maop.common.events.CloudEvent;
import io.github.danieloh30.maop.common.events.EventTypes;
import io.github.danieloh30.maop.common.events.Subjects;
import io.github.danieloh30.maop.common.json.Json;
import io.github.danieloh30.maop.common.messaging.JetStreamClient;
import io.github.danieloh30.maop.common.tls.SvidSource;
import io.github.danieloh30.maop.common.tools.ToolClient;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.nats.client.api.ConsumerInfo;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Builds the runtime snapshot (contracts.md section 2.5) from live sources at observation time: JetStream consumer
 * info, the Kubernetes API (read-only), worker heartbeats, and component health endpoints. A source that cannot be
 * read yields null values and UNAVAILABLE — never zero or OK. Nothing here is persisted as business state.
 */
@ApplicationScoped
public class SnapshotService {

    static final List<String> WORKLOADS = List.of("triage-agent", "evidence-agent", "recommendation-agent", "tool-service",
            "ops-console");

    record Heartbeat(String service, int activeTasks, int permits, Instant at) {
    }

    @Inject
    JetStreamClient js;
    @Inject
    Instance<KubernetesClient> kube;
    @Inject
    ToolClient tools;
    @Inject
    MeterRegistry registry;

    @ConfigProperty(name = "maop.kubernetes.enabled", defaultValue = "false")
    boolean kubernetesEnabled;
    @ConfigProperty(name = "maop.namespace", defaultValue = "maop")
    String namespace;
    @ConfigProperty(name = "maop.spiffe.dir", defaultValue = "/spiffe")
    String spiffeDir;
    @ConfigProperty(name = "maop.prometheus.url")
    Optional<String> prometheusUrl;
    @ConfigProperty(name = "maop.jaeger.internal-url")
    Optional<String> jaegerUrl;
    @ConfigProperty(name = "maop.grafana.internal-url")
    Optional<String> grafanaUrl;
    @ConfigProperty(name = "maop.evidence.min-replicas", defaultValue = "1")
    int evidenceMin;
    @ConfigProperty(name = "maop.evidence.max-replicas", defaultValue = "6")
    int evidenceMax;

    private final Map<String, Heartbeat> heartbeats = new ConcurrentHashMap<>();
    private final AtomicReference<ObjectNode> lastModelCall = new AtomicReference<>();
    private final AtomicReference<ObjectNode> latest = new AtomicReference<>();
    private final Map<String, AtomicReference<Double>> gauges = new ConcurrentHashMap<>();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1)).build();
    private SvidSource svid;

    @PostConstruct
    void init() {
        svid = new SvidSource(Path.of(spiffeDir));
    }

    /** Fed by EventHub: worker heartbeats and model-call observations. */
    public void observe(CloudEvent e) {
        switch (e.typeSuffix()) {
            case EventTypes.WORKER_HEARTBEAT -> heartbeats.put(e.data().path("pod").asString(),
                    new Heartbeat(e.data().path("service").asString(), e.data().path("activeTasks").asInt(),
                            e.data().path("modelPermitsInUse").asInt(), Instant.now()));
            case EventTypes.MODEL_CALL_COMPLETED -> {
                ObjectNode n = ((ObjectNode) e.data()).deepCopy();
                n.put("observedAt", Instant.now().toString());
                lastModelCall.set(n);
            }
            default -> {
                // not used for the snapshot
            }
        }
    }

    public ObjectNode latest() {
        ObjectNode l = latest.get();
        return l != null ? l : refresh();
    }

    private void gauge(String name, String tagKey, String tagValue, Double value) {
        String key = name + "|" + tagValue;
        gauges.computeIfAbsent(key, k -> {
            AtomicReference<Double> ref = new AtomicReference<>(Double.NaN);
            Gauge.builder(name, ref, r -> r.get() == null ? Double.NaN : r.get()).tag(tagKey, tagValue).register(registry);
            return ref;
        }).set(value == null ? Double.NaN : value);
    }

    public ObjectNode refresh() {
        ObjectNode s = Json.MAPPER.createObjectNode();
        s.put("observedAt", Instant.now().toString());
        ArrayNode queues = s.putArray("queues");
        queue(queues, Subjects.CONSUMER_EVIDENCE, Subjects.WORK_EVIDENCE);
        queue(queues, Subjects.CONSUMER_RECOMMENDATION, Subjects.WORK_RECOMMENDATION);
        workloads(s.putArray("workloads"));
        platform(s.putArray("platform"));
        latest.set(s);
        return s;
    }

    private void queue(ArrayNode out, String consumer, String subject) {
        ObjectNode q = out.addObject();
        q.put("stream", Subjects.WORK_STREAM);
        q.put("consumer", consumer);
        try {
            ConsumerInfo ci = js.consumerInfo(Subjects.WORK_STREAM, consumer);
            long pending = ci.getNumPending();
            long inFlight = ci.getNumAckPending();
            q.put("status", "OK");
            q.put("pending", pending);
            q.put("inFlight", inFlight);
            q.put("redelivered", ci.getRedelivered());
            Double age = 0.0;
            if (pending + inFlight > 0) {
                Optional<Instant> oldest = js.oldestMessageTime(Subjects.WORK_STREAM, subject);
                age = oldest.map(t -> Math.max(0, Duration.between(t, Instant.now()).toMillis() / 1000.0)).orElse(null);
            }
            if (age == null) {
                q.putNull("oldestMessageAgeSeconds");
            } else {
                q.put("oldestMessageAgeSeconds", Math.round(age * 10) / 10.0);
            }
            gauge("maop.queue.pending", "consumer", consumer, (double) pending);
            gauge("maop.queue.ack.pending", "consumer", consumer, (double) inFlight);
            gauge("maop.queue.redelivered", "consumer", consumer, (double) ci.getRedelivered());
            gauge("maop.queue.oldest.age.seconds", "consumer", consumer, age);
        } catch (Exception e) {
            q.put("status", "UNAVAILABLE");
            q.putNull("pending");
            q.putNull("inFlight");
            q.putNull("redelivered");
            q.putNull("oldestMessageAgeSeconds");
            q.put("detail", "JetStream consumer info unavailable: " + e.getClass().getSimpleName());
            gauge("maop.queue.pending", "consumer", consumer, null);
            gauge("maop.queue.ack.pending", "consumer", consumer, null);
            gauge("maop.queue.redelivered", "consumer", consumer, null);
            gauge("maop.queue.oldest.age.seconds", "consumer", consumer, null);
        }
    }

    private Integer activeTasks(String pod) {
        Heartbeat h = heartbeats.get(pod);
        return h == null || Duration.between(h.at(), Instant.now()).toSeconds() > 10 ? null : h.activeTasks();
    }

    private void workloads(ArrayNode out) {
        heartbeats.entrySet().removeIf(e -> Duration.between(e.getValue().at(), Instant.now()).toMinutes() > 5);
        KubernetesClient k = kubernetesEnabled && kube.isResolvable() ? kube.get() : null;
        for (String name : WORKLOADS) {
            ObjectNode w = out.addObject();
            w.put("name", name);
            boolean scaled = name.equals("evidence-agent");
            if (k == null) {
                w.put("status", "UNAVAILABLE");
                w.put("detail", "Kubernetes API not configured (local mode)");
                w.putNull("desiredReplicas");
                w.putNull("readyReplicas");
                w.putNull("minReplicas");
                w.putNull("maxReplicas");
                w.putNull("scaler");
                ArrayNode pods = w.putArray("pods");
                heartbeats.forEach((pod, h) -> {
                    if (h.service().equals(name)) {
                        ObjectNode p = pods.addObject();
                        p.put("name", pod);
                        p.putNull("phase");
                        p.putNull("ready");
                        p.putNull("startedAt");
                        Integer at = activeTasks(pod);
                        if (at == null) {
                            p.putNull("activeTasks");
                        } else {
                            p.put("activeTasks", at);
                        }
                    }
                });
                if (scaled) {
                    gauge("maop.evidence.replicas", "kind", "desired", null);
                    gauge("maop.evidence.replicas", "kind", "ready", null);
                }
                continue;
            }
            int statusDesired = -1;
            int statusReady = -1;
            try {
                Deployment d = k.apps().deployments().inNamespace(namespace).withName(name).get();
                if (d == null) {
                    w.put("status", "UNAVAILABLE");
                    w.put("detail", "Deployment not found");
                    w.putNull("desiredReplicas");
                    w.putNull("readyReplicas");
                } else {
                    int desired = d.getSpec().getReplicas() == null ? 0 : d.getSpec().getReplicas();
                    int ready = d.getStatus() == null || d.getStatus().getReadyReplicas() == null ? 0 : d.getStatus().getReadyReplicas();
                    w.put("status", ready >= Math.max(1, desired) ? "OK" : ready > 0 ? "DEGRADED" : "UNAVAILABLE");
                    statusDesired = desired;
                    statusReady = ready;
                    w.put("desiredReplicas", desired);
                    w.put("readyReplicas", ready);
                    if (scaled) {
                        gauge("maop.evidence.replicas", "kind", "desired", (double) desired);
                        gauge("maop.evidence.replicas", "kind", "ready", (double) ready);
                    }
                }
                if (scaled) {
                    scaler(k, w);
                } else {
                    w.putNull("minReplicas");
                    w.putNull("maxReplicas");
                    w.putNull("scaler");
                }
                ArrayNode pods = w.putArray("pods");
                List<Pod> podList = k.pods().inNamespace(namespace).withLabel("app.kubernetes.io/name", name).list().getItems();
                if (statusDesired >= 0 && isScalingUp(statusDesired, statusReady, podList, Instant.now())) {
                    w.put("status", "SCALING");
                    w.put("detail", "Scaling up: " + statusReady + " of " + statusDesired + " replicas ready");
                }
                for (Pod p : podList) {
                    ObjectNode pn = pods.addObject();
                    pn.put("name", p.getMetadata().getName());
                    pn.put("phase", p.getStatus() == null ? null : p.getStatus().getPhase());
                    boolean isReady = p.getStatus() != null && p.getStatus().getConditions() != null && p.getStatus().getConditions()
                            .stream().anyMatch(c -> "Ready".equals(c.getType()) && "True".equals(c.getStatus()));
                    pn.put("ready", isReady);
                    pn.put("startedAt", p.getStatus() == null ? null : p.getStatus().getStartTime());
                    Integer at = activeTasks(p.getMetadata().getName());
                    if (at == null) {
                        pn.putNull("activeTasks");
                    } else {
                        pn.put("activeTasks", at);
                    }
                }
            } catch (Exception e) {
                w.put("status", "UNAVAILABLE");
                w.put("detail", "Kubernetes API error: " + e.getClass().getSimpleName());
                w.putNull("desiredReplicas");
                w.putNull("readyReplicas");
                w.putNull("pods");
            }
        }
    }

    /** Container waiting reasons that mean a pod is failing, not merely starting. */
    private static final Set<String> FAILING_REASONS = Set.of("CrashLoopBackOff", "ImagePullBackOff", "ErrImagePull",
            "CreateContainerConfigError", "CreateContainerError", "InvalidImageName", "RunContainerError");

    /**
     * A planned scale-out, not a fault: some replicas are ready, fewer than desired, and every pod that is not ready
     * yet is new (created within the last five minutes), has not restarted and is not in a failing waiting state.
     */
    static boolean isScalingUp(int desired, int ready, List<Pod> pods, Instant now) {
        if (ready <= 0 || ready >= desired) {
            return false;
        }
        int starting = 0;
        for (Pod p : pods) {
            if (p.getMetadata() != null && p.getMetadata().getDeletionTimestamp() != null) {
                continue;
            }
            var st = p.getStatus();
            boolean podReady = st != null && st.getConditions() != null && st.getConditions().stream()
                    .anyMatch(c -> "Ready".equals(c.getType()) && "True".equals(c.getStatus()));
            if (podReady) {
                continue;
            }
            String created = p.getMetadata() == null ? null : p.getMetadata().getCreationTimestamp();
            if (created == null || Duration.between(Instant.parse(created), now).toMinutes() >= 5) {
                return false;
            }
            if (st != null && st.getContainerStatuses() != null) {
                for (var cs : st.getContainerStatuses()) {
                    if (cs.getRestartCount() != null && cs.getRestartCount() > 0) {
                        return false;
                    }
                    var waiting = cs.getState() == null ? null : cs.getState().getWaiting();
                    if (waiting != null && waiting.getReason() != null && FAILING_REASONS.contains(waiting.getReason())) {
                        return false;
                    }
                }
            }
            starting++;
        }
        return starting > 0 && ready + starting >= desired;
    }

    @SuppressWarnings("unchecked")
    private void scaler(KubernetesClient k, ObjectNode w) {
        try {
            GenericKubernetesResource so = k.genericKubernetesResources("keda.sh/v1alpha1", "ScaledObject")
                    .inNamespace(namespace).withName("evidence-agent").get();
            if (so == null) {
                w.put("minReplicas", evidenceMin);
                w.put("maxReplicas", evidenceMax);
                w.putNull("scaler");
                return;
            }
            Map<String, Object> spec = (Map<String, Object>) so.getAdditionalProperties().get("spec");
            Map<String, Object> status = (Map<String, Object>) so.getAdditionalProperties().get("status");
            w.put("minReplicas", ((Number) spec.getOrDefault("minReplicaCount", evidenceMin)).intValue());
            w.put("maxReplicas", ((Number) spec.getOrDefault("maxReplicaCount", evidenceMax)).intValue());
            ObjectNode sc = w.putObject("scaler");
            List<Map<String, Object>> triggers = (List<Map<String, Object>>) spec.get("triggers");
            sc.put("kind", "KEDA " + (triggers == null || triggers.isEmpty() ? "" : triggers.getFirst().get("type")));
            Boolean active = null;
            Boolean ready = null;
            if (status != null && status.get("conditions") instanceof List<?> conds) {
                for (Object o : conds) {
                    Map<String, Object> c = (Map<String, Object>) o;
                    if ("Active".equals(c.get("type"))) {
                        active = "True".equals(c.get("status"));
                    }
                    if ("Ready".equals(c.get("type"))) {
                        ready = "True".equals(c.get("status"));
                    }
                }
            }
            if (active == null) {
                sc.putNull("active");
            } else {
                sc.put("active", active);
            }
            if (ready == null) {
                sc.putNull("ready");
            } else {
                sc.put("ready", ready);
            }
        } catch (Exception e) {
            w.putNull("minReplicas");
            w.putNull("maxReplicas");
            w.putNull("scaler");
        }
    }

    private void platform(ArrayNode out) {
        // OPA: probed through tool-service, which asks OPA to authorise the console's tools.list call over mTLS.
        try {
            HttpResponse<String> r = tools.call("GET", "/v1/tools", null, Instant.now().plusSeconds(5));
            add(out, "tool-service", "OK", "mTLS call authorised");
            add(out, "opa", r.statusCode() == 200 ? "OK" : r.statusCode() == 503 ? "UNAVAILABLE" : "DEGRADED",
                    r.statusCode() == 200 ? "Policy decision returned" : "Tool service reported HTTP " + r.statusCode());
        } catch (Exception e) {
            add(out, "tool-service", "UNAVAILABLE", "mTLS call failed: " + e.getMessage());
            add(out, "opa", "UNAVAILABLE", "Not reachable through tool-service");
        }
        try {
            SvidSource.Material m = svid.current();
            Instant notAfter = m.leaf().getNotAfter().toInstant();
            add(out, "spire", notAfter.isAfter(Instant.now()) ? "OK" : "UNAVAILABLE",
                    "SVID " + m.spiffeId() + " valid until " + notAfter);
        } catch (Exception e) {
            add(out, "spire", "UNAVAILABLE", "No X.509-SVID available");
        }
        add(out, "nats", js.connected() ? "OK" : "UNAVAILABLE", js.connected() ? "Connected" : "Disconnected");
        probe(out, "jaeger", jaegerUrl.map(u -> u + "/"));
        probe(out, "prometheus", prometheusUrl.map(u -> u + "/-/ready"));
        probe(out, "grafana", grafanaUrl.map(u -> u + "/api/health"));
        ObjectNode mc = lastModelCall.get();
        if (mc != null && Duration.between(Instant.parse(mc.get("observedAt").asString()), Instant.now()).toMinutes() < 10) {
            add(out, "model", "OK", mc.path("mode").asString() + " " + mc.path("model").asString() + ": last successful call at "
                    + mc.get("observedAt").asString());
        }
    }

    private void probe(ArrayNode out, String name, Optional<String> url) {
        if (url.isEmpty() || url.get().isBlank()) {
            add(out, name, "UNAVAILABLE", "Not configured");
            return;
        }
        try {
            HttpResponse<Void> r = http.send(HttpRequest.newBuilder(URI.create(url.get())).timeout(Duration.ofSeconds(2)).GET().build(),
                    HttpResponse.BodyHandlers.discarding());
            add(out, name, r.statusCode() < 400 ? "OK" : "DEGRADED", "HTTP " + r.statusCode());
        } catch (Exception e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            add(out, name, "UNAVAILABLE", "Unreachable: " + e.getClass().getSimpleName());
        }
    }

    private static void add(ArrayNode out, String component, String status, String detail) {
        out.addObject().put("component", component).put("status", status).put("detail", detail);
    }
}
