package io.github.danieloh30.maop.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.agroal.api.AgroalDataSource;
import io.github.danieloh30.maop.common.Ids;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import java.net.http.HttpResponse;
import java.sql.ResultSet;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** With the policy decision point unreachable, the tool service fails closed and executes nothing. */
@QuarkusTest
@TestProfile(PolicyUnavailableTest.OpaDown.class)
@QuarkusTestResource(value = ToolServiceResources.class, restrictToAnnotatedClass = true)
class PolicyUnavailableTest {

    public static class OpaDown implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("maop.opa.url", "http://127.0.0.1:1");
        }
    }

    @Inject
    AgroalDataSource ds;

    @Test
    void failsClosedWhenOpaIsUnavailable() throws Exception {
        UUID run = Ids.v7();
        HttpResponse<String> r = ToolClients.invoke("recommendation-agent", "catalog.get_service", "opa-" + Ids.v7(), run,
                Ids.v7(), Map.of("service", "checkout"), null);
        assertEquals(503, r.statusCode(), r.body());
        assertEquals("POLICY_UNAVAILABLE", ToolClients.json(r).get("code").asString());
        try (var c = ds.getConnection(); var s = c.createStatement();
                ResultSet rs = s.executeQuery("select count(*) from tool_invocation where run_id = '" + run + "'")) {
            rs.next();
            assertEquals(0, rs.getLong(1), "no invocation recorded or executed");
        }
    }
}
