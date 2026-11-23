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

@QuarkusTest
@QuarkusTestResource(NatsTestResource.class)
@TestProfile(ConsoleApiTest.Profile.class)
class ConsoleApiTest {

    public static class Profile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("maop.presenter-token", "test-token-for-console-api", "maop.presenter-auth.enabled", "true",
                    "maop.triage.url", "http://127.0.0.1:1",
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
        given().get("/api/v1/scenarios").then().statusCode(200)
                .body("items.id", org.hamcrest.Matchers.contains("SUCCESS", "POLICY_DENIAL", "BURST", "REDELIVERY", "SANDBOX"))
                .body("items[4].request.analysisMode", org.hamcrest.Matchers.equalTo("SANDBOXED_SCRIPT"))
                .body("items[0].request.analysisMode", org.hamcrest.Matchers.nullValue());
    }

    @Test
    void unreachableSourcesAreReportedAsUnavailableNotZero() {
        given().get("/api/v1/ops/snapshot").then().statusCode(200)
                .body("workloads.find { it.name == 'evidence-agent' }.status", equalTo("UNAVAILABLE"))
                .body("workloads.find { it.name == 'evidence-agent' }.desiredReplicas", nullValue())
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

    @Test
    void indexIsRevalidatedAndOnlyHashedAssetsAreImmutable() {
        // A cached index.html would keep pointing at the previous build's assets after a redeploy.
        given().get("/").then().header("Cache-Control", "no-cache");
        given().get("/index.html").then().header("Cache-Control", "no-cache");
        given().get("/assets/cache-probe.txt").then().statusCode(200)
                .header("Cache-Control", "public, max-age=31536000, immutable");
    }
}
