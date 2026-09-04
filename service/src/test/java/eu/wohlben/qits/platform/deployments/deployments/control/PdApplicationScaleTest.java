package eu.wohlben.qits.platform.deployments.deployments.control;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The operator's two levers, end to end: the door, the worker, the orchestrator call and the row.
 *
 * <p>Package-local for the reason {@link PdDeploymentObservationTest} is — the recovery half of a
 * scale is an observation pass, and driving one directly is the only way to assert it without a
 * ticker. Rows are written straight to the table for the same reason too: what these levers act on
 * is an application that is already deployed, and staging a real green build to reach that state
 * would be asserting the deployment path a second time.
 *
 * <p><b>Every call is answered 202 and the work happens on the deploy worker</b>, so each assertion
 * waits on {@link DeployService#awaitIdle()} rather than on the response — which is exactly what a
 * caller of this API is told to do, and what makes these tests immune to the worker's timing.
 */
@QuarkusTest
public class PdApplicationScaleTest {

  private static final String SHA = "c".repeat(40);

  private static final String APPLICATIONS = "/platform-deployments/api/applications/";

  @Inject FakeDeploymentDriver driver;
  @Inject FakeSpecSource specs;
  @Inject FakeResourceProvisioner provisioner;
  @Inject DeploymentObserver observer;
  @Inject DeployService deployService;
  @Inject PdDeploymentRepository deployments;

  @BeforeEach
  void reset() {
    driver.reset();
    specs.reset();
    provisioner.reset();
  }

  /**
   * One settled deployment of one application in one place, as the world it acts on.
   *
   * <p>The plane is derived from the tier the way {@link PdDeploymentObservationTest}'s twin does —
   * a fixture with no tier is a platform row, which is the pre-V8 shape V8's backfill leaves on an
   * install with nothing designated. {@link #deployment(String, String, PdDeploymentTarget,
   * PdDeploymentStatus, String)} is the arm that states the two independently, and the plane test
   * below uses it to write the shape a designated install actually has.
   */
  private String deployment(
      String applicationName,
      String environmentId,
      PdDeploymentStatus status,
      String containerName) {
    return deployment(
        applicationName,
        environmentId,
        environmentId == null ? PdDeploymentTarget.PLATFORM : PdDeploymentTarget.ENVIRONMENT,
        status,
        containerName);
  }

  private String deployment(
      String applicationName,
      String environmentId,
      PdDeploymentTarget target,
      PdDeploymentStatus status,
      String containerName) {
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
              row.createdAt = Instant.now();
              row.finishedAt = Instant.now();
              deployments.persist(row);
            });
    return id;
  }

  private PdDeployment rowOf(String deploymentId) {
    return QuarkusTransaction.requiringNew()
        .call(
            () -> {
              PdDeployment row = deployments.findById(deploymentId);
              assertNotNull(row, "the row is still there");
              PdDeployment copy = new PdDeployment();
              copy.id = row.id;
              copy.status = row.status;
              copy.detail = row.detail;
              copy.commitSha = row.commitSha;
              copy.containerName = row.containerName;
              copy.finishedAt = row.finishedAt;
              return copy;
            });
  }

  private int rowCount(String applicationName, String environmentId) {
    return QuarkusTransaction.requiringNew()
        .call(() -> deployments.listByApplication(applicationName, environmentId).size());
  }

  private void awaitWorker() {
    try {
      deployService.awaitIdle();
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  // --- scaling ------------------------------------------------------------------------------------

  @Test
  public void scalingToZeroStopsTheWorkloadAndTheReadSurfaceSaysSo() {
    // The platform plane, because the deployment listing this asserts against reaches it without a
    // tier row to create first — `?environmentId=platform` is the stand-in the client already uses.
    String service = "scale-down";
    String id = deployment("scale-down", null, PdDeploymentStatus.ACTIVE, service);

    given()
        .contentType(ContentType.JSON)
        .body("{\"replicas\":0}")
        .when()
        .post(
            APPLICATIONS
                + ApplicationKeys.of(PdDeploymentTarget.PLATFORM, null, "scale-down")
                + "/scale")
        .then()
        .statusCode(202)
        .body("serviceName", org.hamcrest.Matchers.equalTo(service))
        .body("replicas", org.hamcrest.Matchers.equalTo(0))
        // The row it acts on, named in the answer: a bounce and a scale are about a DEPLOYMENT, and
        // a caller that could not tell which one would have to guess from a listing.
        .body("deploymentId", org.hamcrest.Matchers.equalTo(id));

    awaitWorker();

    assertTrue(driver.calls().contains("scale:" + service + "=0"), driver.calls().toString());
    PdDeployment row = rowOf(id);
    assertEquals(PdDeploymentStatus.SCALED_TO_ZERO, row.status);
    assertTrue(row.detail.contains("scaled to 0"), row.detail);
    // The deployment kept its identity: this is a stopped application, not a failed one.
    assertEquals(SHA, row.commitSha);
    assertEquals(service, row.containerName);

    // ...and the listing a client reads says so rather than claiming the place is serving.
    given()
        .when()
        .get("/platform-deployments/api/deployments?environmentId=platform")
        .then()
        .statusCode(200)
        .body("deployments.find { it.id == '" + id + "' }.status", org.hamcrest.Matchers.equalTo("SCALED_TO_ZERO"));
  }

  @Test
  public void scalingBackUpAsksTheOrchestratorAndTheObservationSettlesTheRow() {
    // The row is deliberately NOT written ACTIVE here: this component reaches a health verdict in
    // exactly one place, and it is the observation. A scale that wrote ACTIVE would be claiming a
    // gate it never ran.
    String service = "scale-up";
    String id = deployment("scale-up", null, PdDeploymentStatus.SCALED_TO_ZERO, service);
    driver.scriptDeclaredReplicas(service, 0);

    given()
        .contentType(ContentType.JSON)
        .body("{\"replicas\":1}")
        .when()
        .post(
            APPLICATIONS
                + ApplicationKeys.of(PdDeploymentTarget.PLATFORM, null, "scale-up")
                + "/scale")
        .then()
        .statusCode(202);
    awaitWorker();

    assertTrue(driver.calls().contains("scale:" + service + "=1"), driver.calls().toString());
    assertEquals(
        PdDeploymentStatus.SCALED_TO_ZERO,
        rowOf(id).status,
        "still stopped as far as this component knows: no task is healthy yet");

    // The tasks come up, and the ordinary recovery arm — the one FAILED and GONE already use —
    // takes the row back without anything about the deployment changing.
    driver.scriptObservation(service, "running/healthy");
    observer.observeOnce();

    PdDeployment row = rowOf(id);
    assertEquals(PdDeploymentStatus.ACTIVE, row.status);
    assertEquals(SHA, row.commitSha, "the same deployment, come back");
  }

  @Test
  public void aScaleTheOrchestratorRefusesLeavesTheRowAlone() {
    String service = "env-scale-refused-scale-refused";
    String id =
        deployment("scale-refused", "env-scale-refused", PdDeploymentStatus.ACTIVE, service);
    driver.scriptScale(
        new DeploymentDriver.ScaleResult(
            DeploymentDriver.ScaleOutcome.REFUSED, "Error response from daemon: rpc error"));

    given()
        .contentType(ContentType.JSON)
        .body("{\"replicas\":0}")
        .when()
        .post(
            APPLICATIONS
                + ApplicationKeys.of(
                    PdDeploymentTarget.ENVIRONMENT, "env-scale-refused", "scale-refused")
                + "/scale")
        .then()
        .statusCode(202);
    awaitWorker();

    // Nothing was stopped, so nothing may say it was. The 202 was honest — it queued the attempt —
    // and the row is where an operator reads that the attempt did not land.
    PdDeployment row = rowOf(id);
    assertEquals(PdDeploymentStatus.ACTIVE, row.status);
    assertNull(row.detail, "a refused action stamps nothing");
  }

  // --- restarting ---------------------------------------------------------------------------------

  @Test
  public void aRestartBouncesTheServiceAndCreatesNoDeploymentRow() {
    String service = "env-bounce-bounce";
    String id = deployment("bounce", "env-bounce", PdDeploymentStatus.ACTIVE, service);
    int before = rowCount("bounce", "env-bounce");

    given()
        .when()
        .post(
            APPLICATIONS
                + ApplicationKeys.of(PdDeploymentTarget.ENVIRONMENT, "env-bounce", "bounce")
                + "/restart")
        .then()
        .statusCode(202)
        .body("serviceName", org.hamcrest.Matchers.equalTo(service))
        .body("replicas", org.hamcrest.Matchers.nullValue());
    awaitWorker();

    assertTrue(driver.calls().contains("restart:" + service), driver.calls().toString());
    assertEquals(before, rowCount("bounce", "env-bounce"), "a bounce is not a deployment");

    // The row keeps everything that identifies the deployment, and gains one line saying somebody
    // bounced it. That line is the whole audit trail, and it is deliberately not a status.
    PdDeployment row = rowOf(id);
    assertEquals(PdDeploymentStatus.ACTIVE, row.status);
    assertEquals(SHA, row.commitSha);
    assertEquals(service, row.containerName);
    assertTrue(row.detail.contains("restarted by"), row.detail);
    assertTrue(row.detail.contains("this deployment is unchanged"), row.detail);
  }

  @Test
  public void anOperatorStampReplacesThePreviousOneAndKeepsTheDeploymentsOwnDiagnosis() {
    // The stamp is a statement about now; the text under it is what made eaa34fbc findable, and it
    // is what must survive every bounce anybody performs.
    String diagnosis = "[unexpected: JDBCConnectionException: Unable to acquire JDBC Connection]";
    String first = ApplicationScaling.stamped("[restarted by alice at t1]", diagnosis);
    String second = ApplicationScaling.stamped("[scaled to 0 by bob at t2]", first);

    assertTrue(second.startsWith("[scaled to 0 by bob at t2]"), second);
    assertFalse(second.contains("alice"), "yesterday's stamp is not kept");
    assertTrue(second.endsWith(diagnosis), second);
  }

  // --- what the door refuses ----------------------------------------------------------------------

  @Test
  public void aMalformedApplicationIdIsRefusedBeforeAnythingIsQueued() {
    given()
        .contentType(ContentType.JSON)
        .body("{\"replicas\":0}")
        .when()
        .post(APPLICATIONS + "not-an-application-id/scale")
        .then()
        .statusCode(400)
        .body("message", org.hamcrest.Matchers.containsString("is not an application id"));
    awaitWorker();
    assertEquals(List.of(), driver.calls());
  }

  @Test
  public void aReplicaCountAboveOneIsRefusedWithTheReasonThisPlatformHasOne() {
    deployment("too-many", "env-too-many", PdDeploymentStatus.ACTIVE, "env-too-many-too-many");

    given()
        .contentType(ContentType.JSON)
        .body("{\"replicas\":2}")
        .when()
        .post(
            APPLICATIONS
                + ApplicationKeys.of(PdDeploymentTarget.ENVIRONMENT, "env-too-many", "too-many")
                + "/scale")
        .then()
        .statusCode(400)
        .body("message", org.hamcrest.Matchers.containsString("single task"));
  }

  @Test
  public void aScaleWithNoCountIsRefusedRatherThanReadAsZero() {
    given()
        .contentType(ContentType.JSON)
        .body("{}")
        .when()
        .post(APPLICATIONS + "platform:no-count/scale")
        .then()
        .statusCode(400)
        .body("message", org.hamcrest.Matchers.containsString("replicas is required"));
  }

  @Test
  public void anApplicationNothingEverDeployedHasNothingToScale() {
    given()
        .contentType(ContentType.JSON)
        .body("{\"replicas\":0}")
        .when()
        .post(APPLICATIONS + "platform:never-deployed-anywhere/scale")
        .then()
        .statusCode(404);
    given()
        .when()
        .post(APPLICATIONS + "platform:never-deployed-anywhere/restart")
        .then()
        .statusCode(404);
  }

  @Test
  public void aDeploymentThatNeverReachedTheOrchestratorCarriesNoServiceToActOn() {
    // IMAGE_MISSING is the everyday shape of this: the row exists, the pull failed, and there is no
    // service anywhere carrying the application. A 409 rather than a 404 — the application is
    // known, the state is wrong.
    deployment("never-ran", null, PdDeploymentStatus.IMAGE_MISSING, null);

    given()
        .when()
        .post(
            APPLICATIONS
                + ApplicationKeys.of(PdDeploymentTarget.PLATFORM, null, "never-ran")
                + "/restart")
        .then()
        .statusCode(409)
        .body("message", org.hamcrest.Matchers.containsString("never reached the orchestrator"));
  }

  @Test
  public void aPlatformApplicationIsAddressedByTheSameKeyItIsListedUnder() {
    // `platform:<name>` is the stand-in an application on the plane already carries in the listing,
    // so the lever takes the id a client is holding rather than a second spelling.
    String service = "platform-lever";
    String id = deployment("platform-lever", null, PdDeploymentStatus.ACTIVE, service);

    given()
        .when()
        .post(APPLICATIONS + "platform:platform-lever/restart")
        .then()
        .statusCode(202)
        .body("environmentId", org.hamcrest.Matchers.nullValue())
        .body("deploymentId", org.hamcrest.Matchers.equalTo(id));
    awaitWorker();

    assertTrue(driver.calls().contains("restart:" + service), driver.calls().toString());
  }

  @Test
  public void aPlatformApplicationIsFOUNDBYITSPLANEEvenThoughItNamesTheDesignatedTier() {
    // The regression this pins is V8's, and it is silent: a platform deployment carries the
    // designated tier now, so `newestForPlace` reading `platform:` as "the rows with no tier" would
    // find the pre-V8 rows alone — an operator's lever acting on a deployment years old, or 404 on
    // an application that is plainly serving. The plane is the column, so the plane is the query.
    String service = "planed-lever";
    String id =
        deployment(
            "planed-lever",
            "env-designated",
            PdDeploymentTarget.PLATFORM,
            PdDeploymentStatus.ACTIVE,
            service);

    given()
        .when()
        .post(APPLICATIONS + "platform:planed-lever/restart")
        .then()
        .statusCode(202)
        // The tier the plane is deployed INTO comes back, because that is what the row says. The
        // key is still `platform:` — the plane is what a reader of the id is being told.
        .body("environmentId", org.hamcrest.Matchers.equalTo("env-designated"))
        .body("deploymentId", org.hamcrest.Matchers.equalTo(id));
    awaitWorker();

    assertTrue(driver.calls().contains("restart:" + service), driver.calls().toString());
  }
}
