package io.github.danieloh30.maop.console;

import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.core.Response;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Optional;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.resteasy.reactive.server.ServerRequestFilter;

/**
 * Every mutating console endpoint (and the session check) requires {@code Authorization: Bearer <presenter token>}.
 * Read endpoints stay open so an audience can browse. The token is never returned by any API.
 */
public class PresenterAuth {

    @ConfigProperty(name = "maop.presenter-token")
    Optional<String> token;

    @ServerRequestFilter
    public Optional<Response> check(ContainerRequestContext ctx) {
        String path = ctx.getUriInfo().getPath();
        boolean mutation = !"GET".equals(ctx.getMethod()) && !"HEAD".equals(ctx.getMethod()) && !"OPTIONS".equals(ctx.getMethod());
        if (!path.startsWith("/api/") || !(mutation || path.equals("/api/v1/session"))) {
            return Optional.empty();
        }
        if (token.isEmpty() || token.get().isBlank()) {
            return Optional.of(Problems.of(403, "PRESENTER_TOKEN_NOT_CONFIGURED",
                    "Mutations are disabled: no presenter token is configured for this deployment."));
        }
        String header = ctx.getHeaderString("Authorization");
        String presented = header != null && header.startsWith("Bearer ") ? header.substring(7).trim() : "";
        if (!MessageDigest.isEqual(presented.getBytes(StandardCharsets.UTF_8), token.get().getBytes(StandardCharsets.UTF_8))) {
            return Optional.of(Problems.of(401, "PRESENTER_TOKEN_REQUIRED", "A valid presenter token is required."));
        }
        return Optional.empty();
    }
}
