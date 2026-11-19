package io.github.danieloh30.maop.console;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.nullValue;

import io.github.danieloh30.maop.testing.NatsTestResource;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** MAOP_PRESENTER_AUTH=false: mutations need no token and the UI is told so. */
@QuarkusTest
@QuarkusTestResource(NatsTestResource.class)
@TestProfile(OpenModeConsoleTest.Profile.class)
class OpenModeConsoleTest {

    public static class Profile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("maop.presenter-auth.enabled", "false", "maop.triage.url", "http://127.0.0.1:1",
                    "maop.spiffe.dir", "target/no-svid", "maop.tool-service.spiffe-id", "spiffe://maop.test/ns/maop/sa/tool-service",
                    "maop.prometheus.url", "", "maop.jaeger.internal-url", "", "maop.grafana.internal-url", "");
        }
    }

    @Test
    void mutationsAreOpenAndConfigSaysNoTokenIsRequired() {
        given().get("/api/v1/config").then().statusCode(200).body("auth.presenterTokenRequired", equalTo(false))
                .body("kubernetes.enabled", equalTo(false)).body("links.prometheus", nullValue());
        // Reaches the triage proxy (unreachable here) instead of being rejected for a missing token.
        given().contentType("application/json").header("Idempotency-Key", "k").body("{}").post("/api/v1/runs")
                .then().statusCode(502).body("code", equalTo("TRIAGE_UNAVAILABLE"));
        given().get("/api/v1/session").then().statusCode(204);
    }
}
