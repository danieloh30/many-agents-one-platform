package io.github.danieloh30.maop.console;

import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.core.Response;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Optional;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.resteasy.reactive.server.ServerRequestFilter;

/**
 * When enabled, every mutating console endpoint (and the session check) requires
 * {@code Authorization: Bearer <presenter token>}; read endpoints stay open. When disabled
 * ({@code MAOP_PRESENTER_AUTH=false}), anyone who can reach the console can run scenarios and reset the
 * demonstration; triage admission limits still bound the load. The token is never returned by any API.
 */
public class PresenterAuth {

    @ConfigProperty(name = "maop.presenter-token")
    Optional<String> token;
    @ConfigProperty(name = "maop.presenter-auth.enabled", defaultValue = "true")
    boolean enabled;

    @ServerRequestFilter
    public Optional<Response> check(ContainerRequestContext ctx) {
        String path = ctx.getUriInfo().getPath();
        boolean mutation = !"GET".equals(ctx.getMethod()) && !"HEAD".equals(ctx.getMethod()) && !"OPTIONS".equals(ctx.getMethod());
        if (!enabled || !path.startsWith("/api/") || !(mutation || path.equals("/api/v1/session"))) {
            return Optional.empty(); // open mode (maop.presenter-auth.enabled=false): admission limits still apply
        }
        String expected = token.map(String::strip).orElse("");
        if (expected.isEmpty()) {
            return Optional.of(Problems.of(403, "PRESENTER_TOKEN_NOT_CONFIGURED",
                    "Mutations are disabled: no presenter token is configured for this deployment."));
        }
        String header = ctx.getHeaderString("Authorization");
        String presented = header != null && header.startsWith("Bearer ") ? header.substring(7).trim() : "";
        // Compare trimmed values: secrets created from files often carry a trailing newline.
        if (!MessageDigest.isEqual(presented.getBytes(StandardCharsets.UTF_8), expected.getBytes(StandardCharsets.UTF_8))) {
            return Optional.of(Problems.of(401, "PRESENTER_TOKEN_REQUIRED", "A valid presenter token is required."));
        }
        return Optional.empty();
    }
}
