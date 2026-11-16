package io.github.danieloh30.maop.console.resource;

import io.github.danieloh30.maop.console.service.EventHub;
import io.github.danieloh30.maop.console.service.Proxy;
import io.github.danieloh30.maop.console.service.SnapshotService;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.danieloh30.maop.common.json.Json;
import io.github.danieloh30.maop.common.tools.ToolClient;
import jakarta.inject.Inject;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.sse.Sse;
import jakarta.ws.rs.sse.SseEventSink;
import java.net.URLEncoder;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/** Console API (contracts.md sections 2.1 and 5). Mutations require the presenter token (PresenterAuth). */
@Path("/api/v1")
@Produces(MediaType.APPLICATION_JSON)
public class ConsoleResource {

    @Inject
    Proxy triage;
    @Inject
    ToolClient tools;
    @Inject
    SnapshotService snapshots;
    @Inject
    EventHub hub;

    @ConfigProperty(name = "maop.profile", defaultValue = "summit")
    String profile;
    @ConfigProperty(name = "maop.version", defaultValue = "0.1.0")
    String version;
    @ConfigProperty(name = "maop.trust-domain", defaultValue = "maop.example")
    String trustDomain;
    @ConfigProperty(name = "maop.identity.mode", defaultValue = "SPIFFE")
    String identityMode;
    @ConfigProperty(name = "maop.presenter-auth.enabled", defaultValue = "true")
    boolean presenterAuth;
    @ConfigProperty(name = "maop.links.jaeger")
    Optional<String> jaeger;
    @ConfigProperty(name = "maop.links.grafana")
    Optional<String> grafana;

    private static Optional<String> clean(Optional<String> v) {
        return v.map(String::trim).filter(s -> !s.isEmpty()).map(s -> s.endsWith("/") ? s.substring(0, s.length() - 1) : s);
    }

    @GET
    @Path("/config")
    public ObjectNode config() {
        ObjectNode n = Json.MAPPER.createObjectNode();
        n.put("product", "Agent Operations Console");
        n.put("version", version);
        n.put("profile", profile);
        JsonNode info = null;
        try {
            HttpResponse<String> r = triage.send("GET", "/v1/info", null, Map.of());
            if (r.statusCode() == 200) {
                info = Json.parse(r.body());
            }
        } catch (Exception ignored) {
            // reported as unknown below
        }
        ObjectNode model = n.putObject("model");
        model.put("mode", info == null ? null : info.at("/model/mode").asText(null));
        model.put("name", info == null ? null : info.at("/model/name").asText(null));
        n.putObject("identity").put("mode", identityMode).put("trustDomain", trustDomain);
        ObjectNode links = n.putObject("links");
        Optional<String> j = clean(jaeger);
        Optional<String> g = clean(grafana);
        links.put("jaeger", j.orElse(null));
        links.put("grafana", g.orElse(null));
        links.put("grafanaDashboard", g.map(u -> u + "/d/maop-overview").orElse(null));
        n.putObject("limits").put("burstMaxRuns", info == null ? 12 : info.at("/limits/burstMaxRuns").asInt(12))
                .put("maxEvidenceTasks", 4);
        n.putObject("features").put("faultInjection", info != null && info.at("/features/faultInjection").asBoolean(false));
        n.putObject("auth").put("presenterTokenRequired", presenterAuth);
        return n;
    }

    @GET
    @Path("/scenarios")
    public ObjectNode scenarios() {
        ObjectNode n = Json.MAPPER.createObjectNode();
        ArrayNode items = n.putArray("items");
        items.add(scenario("SUCCESS", "Successful investigation",
                "Agents gather evidence through protected tools and propose an evidence-backed action. One trace connects every hop.",
                run("SUCCESS", "PROPOSE_ONLY", "NONE")));
        items.add(scenario("POLICY_DENIAL", "Unapproved remediation is denied",
                "The recommendation agent tries a simulated rollback without approval; policy denies it before execution.",
                run("POLICY_DENIAL", "ATTEMPT_UNAPPROVED", "NONE")));
        items.add(scenario("BURST", "Bounded queue burst",
                "A capped batch of evidence work raises queue lag; KEDA adds evidence workers and removes them afterwards.",
                Json.MAPPER.createObjectNode()));
        items.add(scenario("REDELIVERY", "Redelivery without a duplicate effect",
                "One evidence worker abandons its task after the tool call commits; the redelivery replays the stored result.",
                run("REDELIVERY", "PROPOSE_ONLY", "DROP_ACK_ONCE")));
        return n;
    }

    private static ObjectNode scenario(String id, String title, String description, ObjectNode request) {
        ObjectNode s = Json.MAPPER.createObjectNode();
        s.put("id", id);
        s.put("title", title);
        s.put("description", description);
        s.set("request", request);
        return s;
    }

    private static ObjectNode run(String scenario, String remediation, String fault) {
        ObjectNode r = Json.MAPPER.createObjectNode();
        r.put("title", "Checkout error rate elevated after release");
        r.put("description", "HTTP 5xx on POST /checkout rose from about 0.2% to 7% within minutes of the latest checkout release.");
        r.put("service", "checkout");
        r.put("scenario", scenario);
        r.put("remediationMode", remediation);
        r.put("faultInjection", fault);
        return r;
    }

    @GET
    @Path("/session")
    public Response session() {
        return Response.noContent().build(); // PresenterAuth already validated the bearer token
    }

    @POST
    @Path("/runs")
    public Response create(String body, @HeaderParam("Idempotency-Key") String key) {
        return triage.forward("POST", "/v1/runs", body, Map.of("Idempotency-Key", key == null ? "" : key));
    }

    @GET
    @Path("/runs")
    public Response list(@QueryParam("limit") @DefaultValue("30") int limit, @QueryParam("cursor") String cursor) {
        String q = "?limit=" + limit + (cursor == null ? "" : "&cursor=" + URLEncoder.encode(cursor, StandardCharsets.UTF_8));
        return triage.forward("GET", "/v1/runs" + q, null, Map.of());
    }

    @GET
    @Path("/runs/{runId}")
    public Response get(@PathParam("runId") UUID runId) {
        return triage.forward("GET", "/v1/runs/" + runId, null, Map.of());
    }

    @GET
    @Path("/runs/{runId}/timeline")
    public Response timeline(@PathParam("runId") UUID runId) {
        return triage.forward("GET", "/v1/runs/" + runId + "/timeline", null, Map.of());
    }

    @POST
    @Path("/runs/{runId}/approval")
    public Response approval(@PathParam("runId") UUID runId, String body, @HeaderParam("If-Match") String ifMatch) {
        return triage.forward("POST", "/v1/runs/" + runId + "/approval", body, ifMatch == null ? Map.of() : Map.of("If-Match", ifMatch));
    }

    @POST
    @Path("/runs/{runId}/cancel")
    public Response cancel(@PathParam("runId") UUID runId) {
        return triage.forward("POST", "/v1/runs/" + runId + "/cancel", null, Map.of());
    }

    @POST
    @Path("/bursts")
    public Response burst(String body) {
        return triage.forward("POST", "/v1/bursts", body == null || body.isBlank() ? "{}" : body, Map.of());
    }

    @GET
    @Path("/bursts/current")
    public Response currentBurst() {
        return triage.forward("GET", "/v1/bursts/current", null, Map.of());
    }

    @POST
    @Path("/bursts/stop")
    public Response stopBurst() {
        return triage.forward("POST", "/v1/bursts/stop", null, Map.of());
    }

    @POST
    @Path("/admin/reset")
    public Response reset() {
        Response r = triage.forward("POST", "/v1/admin/reset", null, Map.of());
        if (r.getStatus() >= 300) {
            return r;
        }
        try {
            HttpResponse<String> t = tools.call("POST", "/v1/admin/reset", null, Instant.now().plusSeconds(10));
            if (t.statusCode() >= 300) {
                return Problems.of(502, "TOOL_RESET_FAILED", "Runs were reset but tool audit reset returned " + t.statusCode());
            }
        } catch (Exception e) {
            return Problems.of(502, "TOOL_RESET_FAILED", "Runs were reset but the tool service was not reachable");
        }
        hub.broadcast("reset", Json.MAPPER.createObjectNode());
        return Response.noContent().build();
    }

    @GET
    @Path("/audit/invocations")
    public Response audit(@QueryParam("runId") UUID runId) {
        return toolRead("/v1/invocations" + (runId == null ? "" : "?runId=" + runId));
    }

    @GET
    @Path("/audit/invocations/{invocationId}")
    public Response auditOne(@PathParam("invocationId") UUID invocationId) {
        return toolRead("/v1/invocations/" + invocationId);
    }

    private Response toolRead(String path) {
        try {
            HttpResponse<String> r = tools.call("GET", path, null, Instant.now().plusSeconds(10));
            return Response.status(r.statusCode()).type(r.headers().firstValue("Content-Type").orElse("application/json"))
                    .entity(r.body()).build();
        } catch (Exception e) {
            return Problems.of(502, "TOOL_SERVICE_UNAVAILABLE", "Tool audit is not reachable over mTLS: " + e.getMessage());
        }
    }

    @GET
    @Path("/ops/snapshot")
    public ObjectNode snapshot() {
        return snapshots.refresh(); // observed now, not a cached value
    }

    @GET
    @Path("/stream")
    @Produces(MediaType.SERVER_SENT_EVENTS)
    public void stream(@Context SseEventSink sink, @Context Sse sse, @HeaderParam("Last-Event-ID") String lastEventId,
            @QueryParam("runId") String runId) {
        hub.register(sink, sse, lastEventId, runId);
    }
}
