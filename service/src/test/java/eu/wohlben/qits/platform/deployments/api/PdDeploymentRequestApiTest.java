package eu.wohlben.qits.platform.deployments.api;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import eu.wohlben.qits.platform.deployments.deployments.control.FakeDeploymentDriver;
import eu.wohlben.qits.platform.deployments.deployments.control.FakeSpecSource;
import eu.wohlben.qits.platform.deployments.deployments.control.SpecSource;
import eu.wohlben.qits.platform.deployments.environments.entity.PdDeploymentTarget;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * {@code GET /platform-deployments/api/deployment-requests} over real releases, driven through the
 * intake exactly as a release drives it.
 *
 * <p>What this surface exists to say, and what nothing else can: <b>a version was asked for here,
 * and this is what the gate did with it</b>. The deployments listing answers a different question —
 * what ran — and a request the gate refused never becomes a row in it, so the refusal would be
 * invisible. The gate is a placeholder that says yes to everything today, which is why the cases
 * below assert the shape of a MET request and its edge back to the deployment rather than a
 * refusal: the refusal has no producer yet, and inventing one in a test would assert a fiction.
 *
 * <p>The listing is tier-scoped, so every case makes its own environment. Creating one MOVES the
 * designation, which is what makes a release land in it.
 */
@QuarkusTest
public class PdDeploymentRequestApiTest {

  // Released versions: what a request is about. Never a commit sha.
  private static final String V_A = "2026.903.11";
  private static final String V_B = "2026.903.12";

  @Inject FakeDeploymentDriver driver;
  @Inject FakeSpecSource specs;

  @BeforeEach
  void reset() {
    driver.reset();
    specs.reset();
  }

  @Test
  public void aReleaseLeavesARequestThatPointsAtTheDeploymentItProduced() {
    String environmentId = createEnvironment("req-one");
    release("repo-req", V_A, environmentId, 1);

    Map<String, Object> request = onlyRequestOf(environmentId, "repo-req");
    assertEquals("repo-req", request.get("applicationName"));
    assertEquals(V_A, request.get("version"), "a request is about a version, never a sha");
    assertEquals(environmentId, request.get("environmentId"));
    assertEquals("MET", request.get("qualityGate"), "the placeholder gate says yes");
    assertNull(request.get("gateDetail"), "a silent yes has nothing to say");
    assertNotNull(request.get("gateSettledAt"), "the gate answered, and when is recorded");

    // The edge that makes the lifecycle readable end to end: request → gate → deployment.
    String deploymentId = (String) request.get("deploymentId");
    assertNotNull(deploymentId, "a met gate queues a deployment and the request points at it");
    assertTrue(
        deployments(environmentId).stream().anyMatch(d -> deploymentId.equals(d.get("id"))),
        "the deployment the request names is in the tier's deployment listing");
  }

  @Test
  public void theRequestsOfOneTierComeBackNewestFirst() {
    String environmentId = createEnvironment("req-order");
    release("repo-req-order", V_A, environmentId, 1);
    release("repo-req-order", V_B, environmentId, 2);

    assertEquals(
        List.of(V_B, V_A),
        requests(environmentId, null).stream()
            .filter(r -> "repo-req-order".equals(r.get("applicationName")))
            .map(r -> r.get("version"))
            .toList(),
        "seq desc, because the requests of one release land in one tick");
  }

  @Test
  public void oneApplicationsReleaseHistoryIsAskedForWithApplicationName() {
    String environmentId = createEnvironment("req-filter");
    release("repo-req-mine", V_A, environmentId, 1);
    release("repo-req-other", V_A, environmentId, 2);

    List<Map<String, Object>> mine = requests(environmentId, "repo-req-mine");
    assertEquals(1, mine.size());
    assertEquals("repo-req-mine", mine.get(0).get("applicationName"));
  }

  @Test
  public void aPlatformServicesRequestIsInTheTierItIsDeployedInto() {
    // The whole of V8, from this surface: a platform service belongs to no tier in the catalogue
    // and is deployed INTO the main environment, so its request names that environment like its
    // deployment does. There is no `?environmentId=platform` here — a request records no plane —
    // and this is what makes the plane's releases visible at all.
    String environmentId = createEnvironment("req-plane");
    specs.script(
        "repo-req-plane",
        new SpecSource.DeploymentSpec(PdDeploymentTarget.PLATFORM, false, null, null, null, null));
    release("repo-req-plane", V_A, environmentId, 1);

    Map<String, Object> request = onlyRequestOf(environmentId, "repo-req-plane");
    assertEquals(environmentId, request.get("environmentId"));
    assertNotNull(request.get("deploymentId"));
  }

  @Test
  public void theTierIsRequiredAndOneThatDoesNotExistIsA404() {
    // The deployments listing's shape verbatim: an unscoped listing would answer with every request
    // on the instance, and a missing tier must say so rather than answer with an empty list.
    given()
        .when()
        .get("/platform-deployments/api/deployment-requests")
        .then()
        .statusCode(400);
    given()
        .when()
        .get("/platform-deployments/api/deployment-requests?environmentId=no-such-tier")
        .then()
        .statusCode(404);
  }

  @Test
  public void theWordPlatformIsNotAnEnvironmentHere() {
    // Its sibling accepts `platform` because a deployment records which plane it is on. A request
    // does not, so the word names no tier this table can answer for and is a 404 rather than a
    // silently empty list.
    given()
        .when()
        .get("/platform-deployments/api/deployment-requests?environmentId=platform")
        .then()
        .statusCode(404);
  }

  private Map<String, Object> onlyRequestOf(String environmentId, String applicationName) {
    List<Map<String, Object>> mine =
        requests(environmentId, null).stream()
            .filter(r -> applicationName.equals(r.get("applicationName")))
            .toList();
    assertEquals(1, mine.size(), "requests of " + applicationName);
    return mine.get(0);
  }

  private List<Map<String, Object>> requests(String environmentId, String applicationName) {
    String query =
        "?environmentId=" + environmentId
            + (applicationName == null ? "" : "&applicationName=" + applicationName);
    return given()
        .when()
        .get("/platform-deployments/api/deployment-requests" + query)
        .then()
        .statusCode(200)
        .extract()
        .jsonPath()
        .getList("deploymentRequests");
  }

  private List<Map<String, Object>> deployments(String environmentId) {
    return given()
        .when()
        .get("/platform-deployments/api/deployments?environmentId=" + environmentId)
        .then()
        .statusCode(200)
        .extract()
        .jsonPath()
        .getList("deployments");
  }

  /** The entry tier: creating one moves the designation, and a release lands there. */
  private String createEnvironment(String name) {
    return given()
        .contentType(ContentType.JSON)
        .body(Map.of("name", name, "platform", true))
        .when()
        .post("/platform-deployments/api/environments")
        .then()
        .statusCode(201)
        .extract()
        .path("environment.id");
  }

  /** One release, awaited to a settled deployment — the request is written before it. */
  private void release(String repoId, String version, String environmentId, int expected) {
    given()
        .contentType(ContentType.JSON)
        .body(Map.of("runId", "run-req", "repoId", repoId, "version", version))
        .when()
        .post("/platform-deployments/api/events/software-released")
        .then()
        .statusCode(202);
    settle(environmentId, expected);
  }

  private void settle(String environmentId, int expected) {
    long deadline = System.currentTimeMillis() + 15_000;
    while (System.currentTimeMillis() < deadline) {
      List<Map<String, Object>> deployments = deployments(environmentId);
      boolean settled =
          deployments.size() == expected
              && deployments.stream()
                  .noneMatch(
                      d -> "QUEUED".equals(d.get("status")) || "STARTING".equals(d.get("status")));
      if (settled) {
        return;
      }
      try {
        Thread.sleep(50);
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
      }
    }
    fail("deployments of " + environmentId + " did not settle to " + expected);
  }
}
