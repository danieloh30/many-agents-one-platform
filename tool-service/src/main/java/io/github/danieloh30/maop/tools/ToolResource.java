package io.github.danieloh30.maop.tools;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.danieloh30.maop.common.contract.ApprovalGrant;
import io.github.danieloh30.maop.common.contract.ToolInvocationRequest;
import io.github.danieloh30.maop.common.json.Json;
import io.github.danieloh30.maop.common.tools.ToolCatalog;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.vertx.core.http.HttpServerRequest;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.Optional;
import java.util.UUID;

/** Tool API (contracts.md section 2.3). Reachable only over mTLS with an X.509-SVID. */
@Path("/v1")
@Produces(MediaType.APPLICATION_JSON)
public class ToolResource {

    @Inject
    ToolInvocationService service;
    @Inject
    InvocationStore store;

    private static VerifiedCaller caller(HttpServerRequest request) {
        return VerifiedCaller.from(request).orElseThrow(); // guaranteed by CallerFilter
    }

    private static Response rejected(ToolInvocationService.Outcome.Rejected r) {
        return Problems.of(r.status(), r.code(), r.detail());
    }

    @POST
    @Path("/tools/{tool}/invocations")
    public Response invoke(@PathParam("tool") String tool, @HeaderParam("Idempotency-Key") String key,
            ToolInvocationRequest body, HttpServerRequest request) {
        return switch (service.invoke(tool, key, body, caller(request))) {
            case ToolInvocationService.Outcome.Executed e -> Response.ok(e.body()).build();
            case ToolInvocationService.Outcome.Denied d -> Problems.of(403, d.body());
            case ToolInvocationService.Outcome.Rejected r -> rejected(r);
        };
    }

    @GET
    @Path("/tools")
    public Response tools(HttpServerRequest request) {
        Optional<ToolInvocationService.Outcome.Rejected> denied = service.authorize(caller(request), "tools.list");
        if (denied.isPresent()) {
            return rejected(denied.get());
        }
        ArrayNode items = Json.MAPPER.createArrayNode();
        ToolCatalog.TOOLS.forEach(t -> {
            ObjectNode n = items.addObject();
            n.put("name", t.name());
            n.put("toolClass", t.toolClass().name());
            n.put("description", t.description());
            n.put("simulated", true);
            n.set("parameters", Json.tree(t.params()));
        });
        return Response.ok(Json.MAPPER.createObjectNode().set("items", items)).build();
    }

    @PUT
    @Path("/approvals/{approvalId}")
    public Response registerApproval(@PathParam("approvalId") UUID approvalId, ApprovalGrant grant,
            HttpServerRequest request) {
        VerifiedCaller caller = caller(request);
        Optional<ToolInvocationService.Outcome.Rejected> denied = service.authorize(caller, "approval.register");
        if (denied.isPresent()) {
            return rejected(denied.get());
        }
        if (grant == null || grant.runId() == null || grant.actionDigest() == null || grant.expiresAt() == null
                || grant.approvedAt() == null || grant.approver() == null) {
            return Problems.of(422, "INVALID_REQUEST", "runId, actionDigest, approver, approvedAt, expiresAt are required");
        }
        try {
            boolean created = QuarkusTransaction.requiringNew()
                    .call(() -> store.registerApproval(approvalId, grant, caller.spiffeId()));
            return Response.status(created ? 201 : 200).entity(Json.MAPPER.createObjectNode()
                    .put("approvalId", approvalId.toString()).put("created", created)).build();
        } catch (IllegalStateException e) {
            return Problems.of(409, "APPROVAL_CONFLICT", e.getMessage());
        }
    }

    @GET
    @Path("/invocations")
    public Response audit(@QueryParam("runId") UUID runId, HttpServerRequest request) throws Exception {
        Optional<ToolInvocationService.Outcome.Rejected> denied = service.authorize(caller(request), "audit.read");
        if (denied.isPresent()) {
            return rejected(denied.get());
        }
        ArrayNode items = QuarkusTransaction.requiringNew().call(() -> store.audit(runId, null));
        return Response.ok(Json.MAPPER.createObjectNode().set("items", items)).build();
    }

    @GET
    @Path("/invocations/{invocationId}")
    public Response auditOne(@PathParam("invocationId") UUID invocationId, HttpServerRequest request) throws Exception {
        Optional<ToolInvocationService.Outcome.Rejected> denied = service.authorize(caller(request), "audit.read");
        if (denied.isPresent()) {
            return rejected(denied.get());
        }
        ArrayNode items = QuarkusTransaction.requiringNew().call(() -> store.audit(null, invocationId));
        return items.isEmpty() ? Problems.of(404, "NOT_FOUND", "No such invocation") : Response.ok(items.get(0)).build();
    }

    @POST
    @Path("/admin/reset")
    public Response reset(HttpServerRequest request) throws Exception {
        Optional<ToolInvocationService.Outcome.Rejected> denied = service.authorize(caller(request), "admin.reset");
        if (denied.isPresent()) {
            return rejected(denied.get());
        }
        QuarkusTransaction.requiringNew().run(() -> {
            try {
                store.reset();
            } catch (java.sql.SQLException e) {
                throw new IllegalStateException(e);
            }
        });
        return Response.noContent().build();
    }
}
