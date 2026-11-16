package io.github.danieloh30.maop.console;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItems;
import static org.hamcrest.Matchers.nullValue;

import io.github.danieloh30.maop.testing.NatsTestResource;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import java.util.Map;
import org.junit.jupiter.api.Test;

@QuarkusTest
@QuarkusTestResource(NatsTestResource.class)
@TestProfile(ConsoleApiTest.Profile.class)
class ConsoleApiTest {

    public static class Profile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("maop.presenter-token", "test-token-for-console-api", "maop.triage.url", "http://127.0.0.1:1",
                    "maop.spiffe.dir", "target/no-svid", "maop.tool-service.spiffe-id", "spiffe://maop.test/ns/maop/sa/tool-service",
                    "maop.prometheus.url", "", "maop.jaeger.internal-url", "", "maop.grafana.internal-url", "");
        }
    }

    @Test
    void mutationsRequireThePresenterToken() {
        given().contentType("application/json").body("{}").post("/api/v1/runs").then().statusCode(401)
                .body("code", equalTo("PRESENTER_TOKEN_REQUIRED"));
        given().header("Authorization", "Bearer wrong").post("/api/v1/admin/reset").then().statusCode(401);
        given().header("Authorization", "Bearer wrong").get("/api/v1/session").then().statusCode(401);
        given().header("Authorization", "Bearer test-token-for-console-api").get("/api/v1/session").then().statusCode(204);
        given().get("/api/v1/scenarios").then().statusCode(200).body("items.id", hasItems("SUCCESS", "POLICY_DENIAL", "BURST", "REDELIVERY"));
    }

    @Test
    void unreachableSourcesAreReportedAsUnavailableNotZero() {
        given().get("/api/v1/ops/snapshot").then().statusCode(200)
                .body("workloads.find { it.name == 'evidence-worker' }.status", equalTo("UNAVAILABLE"))
                .body("workloads.find { it.name == 'evidence-worker' }.desiredReplicas", nullValue())
                .body("platform.find { it.component == 'spire' }.status", equalTo("UNAVAILABLE"))
                .body("platform.find { it.component == 'opa' }.status", equalTo("UNAVAILABLE"))
                .body("platform.find { it.component == 'prometheus' }.status", equalTo("UNAVAILABLE"));
        given().get("/api/v1/config").then().statusCode(200).body("model.mode", nullValue())
                .body("links.jaeger", nullValue());
        given().header("Authorization", "Bearer test-token-for-console-api").contentType("application/json").body("{}")
                .header("Idempotency-Key", "k").post("/api/v1/runs").then().statusCode(502).body("code", equalTo("TRIAGE_UNAVAILABLE"));
    }

    @Test
    void securityHeadersAreSet() {
        given().get("/api/v1/scenarios").then().header("X-Content-Type-Options", "nosniff")
                .header("Content-Security-Policy", org.hamcrest.Matchers.containsString("frame-ancestors 'none'"));
    }
}
