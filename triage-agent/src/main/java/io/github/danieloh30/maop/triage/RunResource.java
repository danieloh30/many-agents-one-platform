package io.github.danieloh30.maop.triage;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.danieloh30.maop.common.json.Json;
import io.github.danieloh30.maop.common.model.ModelGateway;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.inject.Inject;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/** Triage API (contracts.md section 2.2). Cluster-internal: called only by ops-console. */
@Path("/v1")
@Produces(MediaType.APPLICATION_JSON)
public class RunResource {

    @Inject
    RunService service;
    @Inject
    RunStore store;
    @Inject
    ModelGateway model;

    @ConfigProperty(name = "maop.burst.max-runs", defaultValue = "12")
    int burstMaxRuns;
    @ConfigProperty(name = "maop.fault-injection.enabled", defaultValue = "true")
    boolean faultInjection;

    @Provider
    public static class ApiExceptionMapper implements ExceptionMapper<Problems.ApiException> {
        @Override
        public Response toResponse(Problems.ApiException e) {
            Response r = e.toResponse();
            if (e.status == 429) {
                return Response.fromResponse(r).header("Retry-After", "30").build();
            }
            return r;
        }
    }

    @POST
    @Path("/runs")
    public Response create(CreateRunRequest req, @HeaderParam("Idempotency-Key") String key) {
        RunService.Created c = service.create(req, key);
        return Response.status(c.replayed() ? 200 : 202).entity(view(c.run().runId)).build();
    }

    @GET
    @Path("/runs")
    public ObjectNode list(@QueryParam("limit") @DefaultValue("30") int limit, @QueryParam("cursor") String cursor) {
        int l = Math.clamp(limit, 1, 100);
        List<Run> runs = QuarkusTransaction.requiringNew()
                .call(() -> store.list(l, cursor == null || cursor.isBlank() ? null : Instant.parse(cursor)));
        ObjectNode n = Json.MAPPER.createObjectNode();
        ArrayNode items = n.putArray("items");
        runs.forEach(r -> items.add(RunViews.summary(r)));
        n.put("nextCursor", runs.size() == l ? runs.getLast().createdAt.toString() : null);
        return n;
    }

    @GET
    @Path("/runs/{runId}")
    public Response get(@PathParam("runId") UUID runId) {
        ObjectNode v = view(runId);
        return Response.ok(v).tag(String.valueOf(v.get("version").asInt())).build();
    }

    private ObjectNode view(UUID runId) {
        return QuarkusTransaction.requiringNew().call(() -> {
            Run r = store.find(runId).orElseThrow(() -> new Problems.ApiException(404, "NOT_FOUND", "No such run"));
            return RunViews.full(r, store.tasks(runId));
        });
    }

    @GET
    @Path("/runs/{runId}/timeline")
    public ObjectNode timeline(@PathParam("runId") UUID runId) {
        return QuarkusTransaction.requiringNew().call(() -> {
            store.find(runId).orElseThrow(() -> new Problems.ApiException(404, "NOT_FOUND", "No such run"));
            ObjectNode n = Json.MAPPER.createObjectNode();
            n.set("items", store.timeline(runId));
            return n;
        });
    }

    @POST
    @Path("/runs/{runId}/approval")
    public ObjectNode approval(@PathParam("runId") UUID runId, ApprovalRequest req, @HeaderParam("If-Match") String ifMatch) {
        Integer version = ifMatch == null || ifMatch.isBlank() ? null : Integer.valueOf(ifMatch.replace("\"", "").trim());
        service.decide(runId, req, version);
        return view(runId);
    }

    @POST
    @Path("/runs/{runId}/cancel")
    public ObjectNode cancel(@PathParam("runId") UUID runId) {
        service.cancel(runId);
        return view(runId);
    }

    public record BurstRequest(Integer runs) {
    }

    @POST
    @Path("/bursts")
    public Response burst(BurstRequest req) {
        return Response.status(202).entity(service.startBurst(req == null ? null : req.runs())).build();
    }

    @GET
    @Path("/bursts/current")
    public ObjectNode currentBurst() {
        return service.currentBurst();
    }

    @POST
    @Path("/bursts/stop")
    public ObjectNode stopBurst() throws Exception {
        return service.stopBurst();
    }

    @POST
    @Path("/admin/reset")
    public Response reset() throws Exception {
        service.reset();
        return Response.noContent().build();
    }

    @GET
    @Path("/info")
    public Map<String, Object> info() {
        return Map.of("model", Map.of("mode", model.mode().name(), "name", model.displayName()),
                "limits", Map.of("burstMaxRuns", burstMaxRuns, "maxEvidenceTasks", 4),
                "features", Map.of("faultInjection", faultInjection));
    }
}
