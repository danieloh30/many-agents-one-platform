package io.github.danieloh30.maop.tools;

import io.opentelemetry.api.trace.Span;
import io.vertx.core.http.HttpServerRequest;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.core.Response;
import java.util.Optional;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.resteasy.reactive.server.ServerRequestFilter;

/**
 * Rejects any request whose verified peer certificate is not an X.509-SVID from the configured trust domain.
 * With {@code quarkus.http.ssl.client-auth=required} a connection without a client certificate never gets here.
 */
public class CallerFilter {

    public static final String PROPERTY = "maop.verified-caller";

    @ConfigProperty(name = "maop.trust-domain")
    String trustDomain;

    @ServerRequestFilter(preMatching = true)
    public Optional<Response> verify(ContainerRequestContext ctx, HttpServerRequest request) {
        Optional<VerifiedCaller> caller = VerifiedCaller.from(request);
        if (caller.isEmpty()) {
            return Optional.of(Problems.of(401, "IDENTITY_REQUIRED", "A valid X.509-SVID client certificate is required."));
        }
        if (!trustDomain.equals(caller.get().trustDomain())) {
            return Optional.of(Problems.of(401, "UNTRUSTED_DOMAIN",
                    "Caller trust domain '" + caller.get().trustDomain() + "' is not accepted."));
        }
        ctx.setProperty(PROPERTY, caller.get());
        Span.current().setAttribute("maop.caller.spiffe_id", caller.get().spiffeId());
        return Optional.empty();
    }
}
