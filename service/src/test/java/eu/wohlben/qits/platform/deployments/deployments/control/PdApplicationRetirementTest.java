package eu.wohlben.qits.platform.deployments.deployments.control;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.platform.deployments.deployments.entity.PdDeployment;
import eu.wohlben.qits.platform.deployments.deployments.entity.PdDeploymentStatus;
import eu.wohlben.qits.platform.deployments.deployments.persistence.PdDeploymentRepository;
import eu.wohlben.qits.platform.deployments.environments.control.ApplicationKeys;
import eu.wohlben.qits.platform.deployments.environments.entity.PdDeploymentTarget;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import java.time.Instant;
import java.util.UUID;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;

/**
 * The third lever: an application that is over says so, and its history survives saying it.
 *
 * <p>Package-local and writing rows straight to the table for the reason {@link
 * PdApplicationScaleTest} is: what a retirement acts on is an application that was deployed and is
 * not any more, and staging a real green build to reach that state would assert the deployment path
 * a second time rather than this one.
 *
 * <p><b>Nothing here waits on the worker</b>, and that is the assertion rather than an omission:
 * this door issues no orchestrator call, so it answers 200 with the write already done. A test that
 * needed {@code awaitIdle()} would mean the door had grown a queue it has no reason to have.
 */
@QuarkusTest
public class PdApplicationRetirementTest {

  private static final String SHA = "d".repeat(40);

  private static final String APPLICATIONS = "/platform-deployments/api/applications/";

  @jakarta.inject.Inject PdDeploymentRepository deployments;

  private String deployment(
      String applicationName,
      String environmentId,
      PdDeploymentTarget target,
      PdDeploymentStatus status,
      String containerName,
      String detail) {
    String id = UUID.randomUUID().toString();
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              PdDeployment row = new PdDeployment();
              row.id = id;
              row.applicationName = applicationName;
              row.environmentId = environmentId;
              row.deploymentTarget = target;
              row.commitSha = SHA;
              row.status = status;
              row.containerName = containerName;
              row.detail = detail;
              row.createdAt = Instant.now();
              row.finishedAt = Instant.now();
              deployments.persist(row);
            });
    return id;
  }

  private String platformRow(String applicationName, PdDeploymentStatus status, String detail) {
    return deployment(applicationName, null, PdDeploymentTarget.PLATFORM, status, null, detail);
  }

  private PdDeployment rowOf(String deploymentId) {
    return QuarkusTransaction.requiringNew()
        .call(
            () -> {
              PdDeployment row = deployments.findById(deploymentId);
              assertNotNull(row, "the row is still there — a retirement deletes nothing");
              PdDeployment copy = new PdDeployment();
              copy.id = row.id;
              copy.status = row.status;
              copy.detail = row.detail;
              copy.commitSha = row.commitSha;
              copy.containerName = row.containerName;
              copy.createdAt = row.createdAt;
              copy.finishedAt = row.finishedAt;
              return copy;
            });
  }

  private int rowCount(String applicationName) {
    return QuarkusTransaction.requiringNew()
        .call(() -> deployments.listForPlaceNewestFirst(applicationName, null).size());
  }

  // --- what it writes -----------------------------------------------------------------------------

  @Test
  public void theCurrentRowBecomesDecommissionedAndEveryOlderRowKeepsTheWordItEarned() {
    // The shape this door exists for: a repository that was renamed, whose old application name was
    // left behind with the failures of the builds that discovered the rename. Three rows, and the
    // newest is what the listing calls the application's state.
    String failure = "[resource provisioning failed: the database is already provisioned]";
    String oldest = platformRow("retire-renamed", PdDeploymentStatus.FAILED, failure);
    String middle = platformRow("retire-renamed", PdDeploymentStatus.FAILED, failure);
    String newest = platformRow("retire-renamed", PdDeploymentStatus.FAILED, failure);
    int before = rowCount("retire-renamed");

    given()
        .when()
        .post(APPLICATIONS + "platform:retire-renamed/decommission")
        .then()
        .statusCode(200)
        .body("applicationName", Matchers.equalTo("retire-renamed"))
        .body("currentDeploymentId", Matchers.equalTo(newest))
        // The word it replaced, reported rather than looked up afterwards: this call is the only
        // place it survives at all.
        .body("previousStatus", Matchers.equalTo("FAILED"))
        .body("decommissionedIds", Matchers.contains(newest));

    assertEquals(before, rowCount("retire-renamed"), "a retirement is not a delete");

    PdDeployment current = rowOf(newest);
    assertEquals(PdDeploymentStatus.DECOMMISSIONED, current.status);
    assertTrue(current.detail.startsWith("[decommissioned by "), current.detail);
    assertTrue(current.detail.contains("this application is retired"), current.detail);
    // The failure text under the stamp is what made the rename findable in the first place, and it
    // is exactly what a retirement must not take with it.
    assertTrue(current.detail.endsWith(failure), current.detail);
    assertEquals(SHA, current.commitSha, "the attempt is unchanged; only the word on it is new");

    // History is history. Relabelling it would have destroyed the record this door refuses to
    // delete, and a listing of six identical DECOMMISSIONED rows says nothing at all.
    assertEquals(PdDeploymentStatus.FAILED, rowOf(oldest).status);
    assertEquals(failure, rowOf(oldest).detail);
    assertEquals(PdDeploymentStatus.FAILED, rowOf(middle).status);
  }

  @Test
  public void theReadSurfaceSaysDecommissionedRatherThanTheStaleFailure() {
    String id = platformRow("retire-on-the-surface", PdDeploymentStatus.FAILED, "boom");

    given().when().post(APPLICATIONS + "platform:retire-on-the-surface/decommission").then().statusCode(200);

    given()
        .when()
        .get("/platform-deployments/api/deployments?environmentId=platform")
        .then()
        .statusCode(200)
        .body(
            "deployments.find { it.id == '" + id + "' }.status",
            Matchers.equalTo("DECOMMISSIONED"));
  }

  @Test
  public void aSpecUnreadableRowStopsBeingRetriedWhereverItSitsInTheHistory() {
    // SPEC_UNREADABLE is the one word here that is not terminal: DeployService re-reads such a row
    // on the observation's cadence until the git host answers. A retired application's spec never
    // will, so leaving one would be a retry running for ever against an address nobody maintains.
    String stranded = platformRow("retire-stranded", PdDeploymentStatus.SPEC_UNREADABLE, "403");
    String newest = platformRow("retire-stranded", PdDeploymentStatus.FAILED, "boom");

    given()
        .when()
        .post(APPLICATIONS + "platform:retire-stranded/decommission")
        .then()
        .statusCode(200)
        // Newest first, and both of them: the current row because it is the state, the older one
        // because its word keeps asking a question nobody will answer.
        .body("decommissionedIds", Matchers.contains(newest, stranded));

    assertEquals(PdDeploymentStatus.DECOMMISSIONED, rowOf(stranded).status);
    assertEquals(PdDeploymentStatus.DECOMMISSIONED, rowOf(newest).status);
  }

  @Test
  public void retiringTwiceSaysTheSameThingOnceRatherThanGrowingTheColumn() {
    String failure = "[deployment spec unreadable: line 42]";
    String id = platformRow("retire-twice", PdDeploymentStatus.FAILED, failure);

    given().when().post(APPLICATIONS + "platform:retire-twice/decommission").then().statusCode(200);
    given()
        .when()
        .post(APPLICATIONS + "platform:retire-twice/decommission")
        .then()
        .statusCode(200)
        // The second call finds the row already retired and says so — it is the current row, so it
        // is settled again, which is what makes the door safe to re-run.
        .body("previousStatus", Matchers.equalTo("DECOMMISSIONED"));

    PdDeployment row = rowOf(id);
    assertEquals(1, countOf(row.detail, "[decommissioned by "), row.detail);
    assertTrue(row.detail.endsWith(failure), row.detail);
  }

  private static int countOf(String haystack, String needle) {
    int count = 0;
    int at = haystack.indexOf(needle);
    while (at >= 0) {
      count++;
      at = haystack.indexOf(needle, at + needle.length());
    }
    return count;
  }

  @Test
  public void aPlatformApplicationIsFoundByItsPlaneEvenThoughItNamesTheDesignatedTier() {
    // V8's silent regression, guarded on this door too: a platform deployment carries the
    // designated tier now, so a `platform:` read that asked "the rows with no tier" would settle a
    // pre-V8 row and leave the one an operator is looking at exactly as it was.
    String id =
        deployment(
            "retire-planed",
            "env-retire-designated",
            PdDeploymentTarget.PLATFORM,
            PdDeploymentStatus.FAILED,
            null,
            "boom");

    given()
        .when()
        .post(APPLICATIONS + "platform:retire-planed/decommission")
        .then()
        .statusCode(200)
        .body("environmentId", Matchers.equalTo("env-retire-designated"))
        .body("currentDeploymentId", Matchers.equalTo(id));

    assertEquals(PdDeploymentStatus.DECOMMISSIONED, rowOf(id).status);
  }

  @Test
  public void anEnvironmentApplicationIsAddressedByTheKeyTheListingCarries() {
    String id =
        deployment(
            "retire-tiered",
            "env-retire-tiered",
            PdDeploymentTarget.ENVIRONMENT,
            PdDeploymentStatus.FAILED,
            null,
            null);

    given()
        .when()
        .post(
            APPLICATIONS
                + ApplicationKeys.of(
                    PdDeploymentTarget.ENVIRONMENT, "env-retire-tiered", "retire-tiered")
                + "/decommission")
        .then()
        .statusCode(200)
        .body("currentDeploymentId", Matchers.equalTo(id));

    PdDeployment row = rowOf(id);
    assertEquals(PdDeploymentStatus.DECOMMISSIONED, row.status);
    assertTrue(row.detail.startsWith("[decommissioned by "), row.detail);
  }

  // --- what it refuses ----------------------------------------------------------------------------

  @Test
  public void anApplicationThatIsSTILLDEPLOYEDIsRefused() {
    // The id names a place by string, and a typo names a live application just as well as a dead
    // one. This is the refusal that makes the door safe to hand an operator.
    platformRow("retire-serving", PdDeploymentStatus.ACTIVE, null);

    given()
        .when()
        .post(APPLICATIONS + "platform:retire-serving/decommission")
        .then()
        .statusCode(409)
        .body("message", Matchers.containsString("still deployed"));

    assertEquals(
        PdDeploymentStatus.ACTIVE,
        QuarkusTransaction.requiringNew()
            .call(() -> deployments.listForPlaceNewestFirst("retire-serving", null).get(0).status),
        "a refusal writes nothing");
  }

  @Test
  public void aStoppedApplicationStillHasAServiceAndIsRefusedToo() {
    // SCALED_TO_ZERO is not terminal in the sense the others are: the swarm service exists, holds
    // its ports and its volumes, and one scale back up makes the row ACTIVE again. Calling that
    // decommissioned would leave the service with nothing pointing at it.
    platformRow("retire-stopped", PdDeploymentStatus.SCALED_TO_ZERO, null);

    given()
        .when()
        .post(APPLICATIONS + "platform:retire-stopped/decommission")
        .then()
        .statusCode(409)
        .body("message", Matchers.containsString("SCALED_TO_ZERO"));
  }

  @Test
  public void anInFlightDeploymentIsTheWorkersToSettleAndNotThisDoors() {
    // QUEUED and STARTING belong to the worker's state machine, and it is about to write the next
    // word. A row settled out from under it would be overwritten a second later and the operator
    // would never learn their action did nothing.
    String id = platformRow("retire-in-flight", PdDeploymentStatus.QUEUED, null);

    given()
        .when()
        .post(APPLICATIONS + "platform:retire-in-flight/decommission")
        .then()
        .statusCode(409)
        .body("message", Matchers.containsString("deploy worker"));

    PdDeployment row = rowOf(id);
    assertEquals(PdDeploymentStatus.QUEUED, row.status);
    assertNull(row.detail, "a refusal stamps nothing");
  }

  @Test
  public void aMalformedApplicationIdIsRefusedBeforeAnythingIsRead() {
    given()
        .when()
        .post(APPLICATIONS + "not-an-application-id/decommission")
        .then()
        .statusCode(400)
        .body("message", Matchers.containsString("is not an application id"));
  }

  @Test
  public void anApplicationNothingEverDeployedHasNothingToRetire() {
    given()
        .when()
        .post(APPLICATIONS + "platform:retire-never-deployed-anywhere/decommission")
        .then()
        .statusCode(404)
        .body("message", Matchers.containsString("nothing to retire"));
  }
}
