package eu.wohlben.qits.platform.deployments.api;

import static io.restassured.RestAssured.given;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.Test;

/**
 * The shipped posture: {@code qits.auth.machine.required} defaults to false, so every guarded write
 * accepts the credential-free call its sender makes today. This is the test that says adopting the
 * guard changed nothing — it runs on the default profile, against the same config a deployment
 * gets.
 *
 * <p>It matters here for the reason it did in both ancestors: this component is deployed before
 * qits-platform-idp knows the {@code qits-platform-deployments} audience exists, so "the guard is
 * present and inert" is the state it ships in and lives in until that grant lands.
 */
@QuarkusTest
class MachineGuardOffTest {

  @Test
  void theIntakeAcceptsACredentialFreeCall() {
    given()
        .contentType(ContentType.JSON)
        .body(
            """
            {"repoId":"unguarded-repo","version":"2026.903.193059"}
            """)
        .when()
        .post("/platform-deployments/api/events/software-released")
        .then()
        .statusCode(202);
  }

  @Test
  void anEnvironmentIsCreatedWithNoCredentialAtAll() {
    given()
        .contentType(ContentType.JSON)
        .body("{\"name\":\"unguarded-env\"}")
        .when()
        .post("/platform-deployments/api/environments")
        .then()
        .statusCode(201);
  }

  @Test
  void aServiceIsUpsertedWithNoCredentialAtAll() {
    given()
        .contentType(ContentType.JSON)
        .body("{\"deploymentTarget\":\"PLATFORM\",\"branch\":\"main\",\"availableOnEnv\":false}")
        .when()
        .put("/platform-deployments/api/services/unguarded-service")
        .then()
        .statusCode(201);
  }
}
