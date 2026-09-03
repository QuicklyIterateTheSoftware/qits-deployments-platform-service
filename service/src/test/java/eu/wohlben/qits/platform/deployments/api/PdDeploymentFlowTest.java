package eu.wohlben.qits.platform.deployments.api;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import eu.wohlben.qits.platform.deployments.deployments.control.FakeDeploymentDriver;
import eu.wohlben.qits.platform.deployments.deployments.control.FakeResourceProvisioner;
import eu.wohlben.qits.platform.deployments.deployments.control.FakeSpecSource;
import eu.wohlben.qits.platform.deployments.deployments.control.ResourceProvisioner;
import eu.wohlben.qits.platform.deployments.deployments.control.DeployService;
import eu.wohlben.qits.platform.deployments.deployments.control.DeploymentDriver;
import eu.wohlben.qits.platform.deployments.deployments.control.SpecSource;
import eu.wohlben.qits.platform.deployments.deployments.entity.PdDeployment;
import eu.wohlben.qits.platform.deployments.deployments.persistence.PdDeploymentRepository;
import eu.wohlben.qits.platform.deployments.environments.entity.PdDeploymentTarget;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The deployment loop end to end, against {@link FakeDeploymentDriver}: intake → queued deployment
 * → pull → apply → convergence → cutover, and each of the recorded failure shapes. The boundary
 * starts at the build-succeeded POST, not at a CI run — what qits-ci sends and when belongs to that
 * repo's tests (the CiPipelineBoundaryTest stance).
 *
 * <p>Deployments execute on cd's worker, so the tests poll the read surface to a deadline rather
 * than reaching into the service — the same way a caller experiences the API.
 */
@QuarkusTest
public class PdDeploymentFlowTest {

  // Released versions, not commit shas. Unpadded on purpose: the platform's stamp is built by
  // integer arithmetic, so V_B is the later of the two and yet sorts FIRST as a string — every
  // ordering claim here would pass by accident against a lexical comparison of padded values.
  private static final String V_A = "2026.903.93059";
  private static final String V_B = "2026.903.193059";

  @Inject FakeDeploymentDriver driver;
  @Inject FakeSpecSource specs;
  @Inject FakeResourceProvisioner provisioner;
  @Inject DeployService deployService;
  @Inject PdDeploymentRepository deployments;

  @BeforeEach
  void reset() {
    driver.reset();
    specs.reset();
    provisioner.reset();
  }

  /**
   * The tier a release enters at. With branch matching gone that is the designated platform
   * environment, and creating one MOVES the designation — so every method here makes its own the
   * entry tier and the suite's shared database never holds two.
   */
  private String createEnvironment(String name) {
    return createEnvironment(name, true);
  }

  private String createEnvironment(String name, boolean platform) {
    return given()
        .contentType(ContentType.JSON)
        .body(Map.of("name", name, "platform", platform))
        .when()
        .post("/platform-deployments/api/environments")
        .then()
        .statusCode(201)
        .extract()
        .path("environment.id");
  }

  private void postRelease(String repoId, String version) {
    postRelease("run-1", repoId, version);
  }

  private void postRelease(String runId, String repoId, String version) {
    given()
        .contentType(ContentType.JSON)
        .body(Map.of("runId", runId, "repoId", repoId, "version", version))
        .when()
        .post("/platform-deployments/api/events/software-released")
        .then()
        .statusCode(202);
  }

  private List<Map<String, Object>> awaitDeployments(String environmentId, int count) {
    long deadline = System.currentTimeMillis() + 15_000;
    while (System.currentTimeMillis() < deadline) {
      List<Map<String, Object>> deployments =
          given()
              .when()
              .get("/platform-deployments/api/deployments?environmentId=" + environmentId)
              .then()
              .statusCode(200)
              .extract()
              .jsonPath()
              .getList("deployments");
      boolean settled =
          deployments.size() == count
              && deployments.stream()
                  .noneMatch(
                      d -> "QUEUED".equals(d.get("status")) || "STARTING".equals(d.get("status")));
      if (settled) {
        return deployments;
      }
      try {
        Thread.sleep(50);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }
    return fail("deployments of " + environmentId + " did not settle to " + count);
  }

  /**
   * Drain the worker. A build-succeeded event is handled there in one piece — spec read,
   * registration, queueing, deployment — so "nothing happened" is only assertable once the worker
   * has had the event and finished with it. No sleep: the hook queues a no-op behind the work and
   * waits on it.
   */
  private void awaitWorkerIdle() {
    try {
      deployService.awaitIdle();
    } catch (Exception e) {
      throw new IllegalStateException("the deploy worker did not drain", e);
    }
  }

  /**
   * A platform deployment is unreachable through the environment-scoped listing, so a test about
   * one reads the row directly — the same thing PdSweepAdoptionTest does for the same reason.
   */
  private PdDeployment deploymentOf(String applicationName, String environmentId, String sha) {
    return QuarkusTransaction.requiringNew()
        .call(
            () ->
                deployments.listByApplication(applicationName, environmentId).stream()
                    .filter(d -> sha.equals(d.commitSha))
                    .findFirst()
                    .orElseThrow(
                        () -> new AssertionError("no deployment of " + applicationName + " at " + sha)));
  }

  /** Platform deployments have no environment to read through — wait on the driver instead. */
  private void awaitApplied(int count) {
    long deadline = System.currentTimeMillis() + 15_000;
    while (driver.applied().size() < count && System.currentTimeMillis() < deadline) {
      try {
        Thread.sleep(50);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }
    assertEquals(count, driver.applied().size(), "applied services");
  }

  @Test
  public void aGreenBuildOnTheListenedBranchDeploys() {
    String environmentId = createEnvironment("flow-green");
    postRelease("repo-green", V_A);

    List<Map<String, Object>> deployments = awaitDeployments(environmentId, 1);
    Map<String, Object> deployment = deployments.get(0);
    assertEquals("ACTIVE", deployment.get("status"));
    assertEquals(V_A, deployment.get("commitSha"));
    assertEquals("repo-green", deployment.get("applicationName"));
    // The run that caused it, straight from the intake and out again on the read surface — this is
    // the whole deployment -> /ci/runs/<runId> click-through.
    assertEquals("run-1", deployment.get("runId"));
    // The row records the name the ORCHESTRATOR gave it, which is the wire alias: a service name
    // is the address, so a replace is an update of that same service.
    assertEquals("flow-green-repo-green", deployment.get("containerName"));

    // The image reference is DERIVED — the convention is the contract under test.
    assertEquals(
        List.of("qits-platform-artifacts:8080/qits/repo-green:" + V_A), driver.pulled());
    DeploymentDriver.ServiceSpec spec = driver.applied().get(0);
    // The primary network is the application's OWN, not the environment's bundle: an ordinary
    // application is a spoke, and only its own services are on it.
    assertEquals("qits-env-flow-green-repo-green", spec.primaryNetwork());
    assertEquals("repo-green", spec.applicationName());
    assertEquals(PdDeploymentTarget.ENVIRONMENT, spec.target());
    // ...and the legacy network is declared with it, which is the transition membership that keeps
    // today's direct cross-application URLs resolving. It is in the SAME list as the primary one:
    // an orchestrator that cannot join after the fact has to be told the whole membership at once.
    assertTrue(
        spec.networks().contains("qits-net"),
        "the legacy network is part of the declared membership: " + spec.networks());
    // The container-shaped name is still derived — it is what a person greps the host for — even
    // though it is not what the service is called.
    assertTrue(
        spec.deploymentName().startsWith("qits-pd-flow-green-repo-green-"),
        "named after environment, application and deployment: " + spec.deploymentName());
    // Nothing named a health path, so registration derived the convention one from the name — and
    // that is what the gate curls.
    assertEquals("/repo-green/q/health/ready", spec.healthPath());
    // Nothing was decommissioned — there was nothing before.
    assertEquals(List.of(), driver.reaped());
  }

  @Test
  public void theNextGreenBuildCutsOverAndDecommissionsThePrevious() {
    String environmentId = createEnvironment("flow-cutover");
    postRelease("repo-cutover", V_A);
    awaitDeployments(environmentId, 1);

    postRelease("repo-cutover", V_B);
    List<Map<String, Object>> deployments = awaitDeployments(environmentId, 2);

    // Newest-first: the sha-B deployment is ACTIVE, the sha-A one decommissioned.
    assertEquals("ACTIVE", deployments.get(0).get("status"));
    assertEquals(V_B, deployments.get(0).get("commitSha"));
    assertEquals("DECOMMISSIONED", deployments.get(1).get("status"));
    // Both rows name the same service, which is what an in-place replace is — so there is nothing
    // to reap, and reaping it would remove the deployment that just went live.
    assertEquals(
        deployments.get(0).get("containerName"), deployments.get(1).get("containerName"));
    assertEquals(List.of(), driver.reaped());
  }

  @Test
  public void aMissingImageIsItsOwnRecordedOutcome() {
    driver.scriptPull(
        new DeploymentDriver.PullResult(
            DeploymentDriver.PullOutcome.IMAGE_MISSING, "manifest unknown"));
    String environmentId = createEnvironment("flow-noimage");
    postRelease("repo-noimage", V_A);

    List<Map<String, Object>> deployments = awaitDeployments(environmentId, 1);
    assertEquals("IMAGE_MISSING", deployments.get(0).get("status"));
    String detail = (String) deployments.get(0).get("detail");
    assertTrue(
        detail.contains("qits-platform-artifacts:8080/qits/repo-noimage:" + V_A),
        "the detail names the reference nothing published: " + detail);
    // Nothing was applied and nothing reaped — the previous state is untouched.
    assertEquals(List.of(), driver.applied());
    assertEquals(List.of(), driver.reaped());
  }

  @Test
  public void aRefusedRegistryIsAFailedDeploymentThatNamesTheCredential() {
    driver.scriptPull(
        new DeploymentDriver.PullResult(
            DeploymentDriver.PullOutcome.AUTH_REFUSED,
            "pull access denied for qits/repo-denied, repository does not exist or may require"
                + " 'docker login'"));
    String environmentId = createEnvironment("flow-denied");
    postRelease("repo-denied", V_A);

    List<Map<String, Object>> deployments = awaitDeployments(environmentId, 1);
    // FAILED rather than IMAGE_MISSING: the image may well be published, and what has to be fixed
    // is the credential rather than the pipeline.
    assertEquals("FAILED", deployments.get(0).get("status"));
    String detail = (String) deployments.get(0).get("detail");
    assertTrue(detail.contains("registry credential"), "the detail says what to fix: " + detail);
    assertTrue(
        detail.contains("qits-platform-artifacts:8080/qits/repo-denied:" + V_A),
        "...and which reference it was refused: " + detail);
    // Nothing was applied and nothing reaped — the previous state is untouched.
    assertEquals(List.of(), driver.applied());
    assertEquals(List.of(), driver.reaped());
  }

  @Test
  public void aSuccessorThatNeverConvergesLeavesTheOldOneServing() {
    String environmentId = createEnvironment("flow-unhealthy");
    postRelease("repo-unhealthy", V_A);
    awaitDeployments(environmentId, 1);

    driver.scriptConvergence(
        DeploymentDriver.Convergence.rolledBack("the successor never went healthy"));
    postRelease("repo-unhealthy", V_B);
    List<Map<String, Object>> deployments = awaitDeployments(environmentId, 2);

    assertEquals(
        "ROLLED_BACK",
        deployments.get(0).get("status"),
        "the orchestrator put the predecessor back, and the word says so");
    assertEquals(V_B, deployments.get(0).get("commitSha"));
    assertTrue(
        ((String) deployments.get(0).get("detail")).contains("never went healthy"),
        "the orchestrator's own words are on the row: " + deployments.get(0).get("detail"));
    // The invariant: the previous deployment is still ACTIVE, and nothing was reaped — a rollback
    // is the predecessor never having stopped.
    assertEquals("ACTIVE", deployments.get(1).get("status"));
    assertEquals(List.of(), driver.reaped());
  }

  @Test
  public void aRefusedApplyIsAFailedDeployment() {
    // Nothing was applied and nothing rolled anything back, so nothing is known to serve — the
    // narrowed FAILED, and the reference point for the three words beside it.
    driver.scriptApply(
        new DeploymentDriver.ApplyResult(
            DeploymentDriver.ApplyOutcome.REFUSED, "docker: connection refused"));
    String environmentId = createEnvironment("flow-refused");
    postRelease("repo-refused", V_A);

    List<Map<String, Object>> deployments = awaitDeployments(environmentId, 1);
    assertEquals("FAILED", deployments.get(0).get("status"));
  }

  @Test
  public void aDeclaredResourceIsProvisionedBeforeThePullAndInjectedIntoTheService() {
    // The whole mechanism through the front door: the repository says `resources: postgresql:db`,
    // the role and the database are made to exist before anything runtime-side happens, and the
    // service is applied with the generic triple for them.
    String environmentId = createEnvironment("flow-resource");
    specs.script(
        "qits-storing",
        new SpecSource.DeploymentSpec(
            PdDeploymentTarget.ENVIRONMENT,
            false,
            null,
            null,
            null,
            List.of(new SpecSource.DeploymentSpec.ResourceSpec("db", null))));
    postRelease("qits-storing", V_A);

    List<Map<String, Object>> deployments = awaitDeployments(environmentId, 1);
    assertEquals("ACTIVE", deployments.get(0).get("status"));

    assertEquals(1, provisioner.requests().size(), "the seam saw exactly one resource");
    ResourceProvisioner.Request request = provisioner.requests().get(0);
    // The database defaulted from the application name, and the address from the tier.
    assertEquals("qits_storing", request.databaseName());
    assertEquals("flow-resource-qits-oci-postgresql", request.host());
    assertNull(request.storedPassword(), "nothing had recorded one yet");

    DeploymentDriver.ServiceSpec started = driver.applied().get(0);
    assertEquals(1, started.resources().size());
    DeploymentDriver.ResourceBinding binding = started.resources().get(0);
    assertEquals("db", binding.name());
    assertEquals(
        "jdbc:postgresql://flow-resource-qits-oci-postgresql:5432/qits_storing", binding.url());
    assertEquals("qits_storing", binding.username());
    assertEquals(request.freshPassword(), binding.password());
  }

  @Test
  public void aResourceThatCannotBeProvisionedFailsTheDeploymentBeforeAnythingRuntimeSide() {
    // The placement of the hook, asserted as behaviour: the row exists to record the failure on,
    // and nothing was pulled or applied — so whatever was serving is still serving.
    String environmentId = createEnvironment("flow-resource-refused");
    specs.script(
        "qits-refused",
        new SpecSource.DeploymentSpec(
            PdDeploymentTarget.ENVIRONMENT,
            false,
            null,
            null,
            null,
            List.of(new SpecSource.DeploymentSpec.ResourceSpec("db", null))));
    provisioner.scriptResult(
        new ResourceProvisioner.Result(false, null, "postgres refused: too many connections"));
    postRelease("qits-refused", V_A);

    List<Map<String, Object>> deployments = awaitDeployments(environmentId, 1);
    assertEquals("FAILED", deployments.get(0).get("status"));
    String detail = (String) deployments.get(0).get("detail");
    assertTrue(detail.contains("resource provisioning failed"), detail);
    assertTrue(detail.contains("too many connections"), "postgres' own words are on the row: " + detail);
    assertEquals(List.of(), driver.pulled(), "nothing was pulled");
    assertEquals(List.of(), driver.applied(), "and nothing was applied");
  }

  @Test
  public void aRepositoryThatDeclaresNoResourceIsDeployedExactlyAsBefore() {
    // The backward-compatibility half, which is every application on the platform today: the seam
    // is never called and the service is told about nothing.
    String environmentId = createEnvironment("flow-resource-none");
    postRelease("repo-nostore", V_A);

    assertEquals("ACTIVE", awaitDeployments(environmentId, 1).get(0).get("status"));
    assertEquals(List.of(), provisioner.requests());
    assertEquals(List.of(), driver.applied().get(0).resources());
  }

  @Test
  public void aRepositoryTheEnvironmentNeverHeardOfRegistersItself() {
    // Derived registration: nothing declared repo-derived anywhere, and a green build on the
    // environment's branch is the whole registration. The row is named after the repository and
    // carries the defaults its (absent) deployments.yml implies.
    String environmentId = createEnvironment("flow-derive");
    postRelease("repo-derived", V_A);

    List<Map<String, Object>> deployments = awaitDeployments(environmentId, 1);
    assertEquals("ACTIVE", deployments.get(0).get("status"));
    assertEquals("repo-derived", deployments.get(0).get("applicationName"));

    Map<String, Object> registered =
        given()
            .when()
            .get("/platform-deployments/api/applications")
            .then()
            .statusCode(200)
            .extract()
            .jsonPath()
            .<Map<String, Object>>getList("applications")
            .stream()
            .filter(a -> "repo-derived".equals(a.get("repoId")))
            .findFirst()
            .orElseThrow();
    assertEquals("ENVIRONMENT", registered.get("target"));
    assertEquals(false, registered.get("availableOnEnv"));
    assertEquals("flow-derive", registered.get("environmentName"));
    assertNull(registered.get("branch"), "an environment application takes its tier's branch");
  }

  @Test
  public void aPublicNodeDeclaresTheBundleAndEveryApplicationNetworkOfItsEnvironment() {
    String environmentId = createEnvironment("flow-hub");
    // One application network of this environment already exists — the hub has to end up on it.
    driver.scriptExistingNetwork(
        new DeploymentDriver.Network(
            "qits-env-flow-hub-app-hub-seed",
            environmentId,
            DeploymentDriver.NetworkKind.APPLICATION,
            "app-hub-seed"));
    specs.script(
        "repo-gw", new SpecSource.DeploymentSpec(PdDeploymentTarget.ENVIRONMENT, true, null, null, null, null));
    postRelease("repo-gw", V_A);

    awaitDeployments(environmentId, 1);
    DeploymentDriver.ServiceSpec spec = driver.applied().get(0);
    assertEquals("qits-env-flow-hub-repo-gw", spec.primaryNetwork());
    assertTrue(spec.availableOnEnv());
    assertTrue(
        spec.networks().contains("qits-env-flow-hub"),
        "the public node is on its environment's bundle: " + spec.networks());
    assertTrue(
        spec.networks().contains("qits-env-flow-hub-app-hub-seed"),
        "and every application network of that environment: " + spec.networks());
    // One alias throughout, whichever network it is reached on.
    assertEquals("flow-hub-repo-gw", spec.wireAlias());
  }

  @Test
  public void aPlatformServiceRunsOnThePlatformNetworkAndDeclaresEveryApplicationNetwork() {
    String environmentId = createEnvironment("flow-single");
    driver.scriptExistingNetwork(
        new DeploymentDriver.Network(
            "qits-env-flow-single-app-single-seed",
            environmentId,
            DeploymentDriver.NetworkKind.APPLICATION,
            "app-single-seed"));
    specs.script(
        "repo-idp", new SpecSource.DeploymentSpec(PdDeploymentTarget.PLATFORM, false, null, null, null, null));
    // An environment's own branch, because that is the only kind of deploy ref there is — and what
    // comes out of it is still platform-shaped.
    postRelease("repo-idp", V_A);

    awaitApplied(1);
    DeploymentDriver.ServiceSpec spec = driver.applied().get(0);
    assertEquals("qits-platform", spec.primaryNetwork());
    assertEquals(PdDeploymentTarget.PLATFORM, spec.target());
    assertNull(spec.environmentId(), "a platform service belongs to no tier");
    assertNull(spec.environmentName());
    assertTrue(
        spec.deploymentName().startsWith("qits-pd-repo-idp-"),
        "no tier segment in the derived name, because there is no tier: " + spec.deploymentName());
    // Its wire alias stays the bare application name — one instance for the whole platform has
    // nothing to be qualified against.
    assertEquals("repo-idp", spec.wireAlias());
    assertTrue(
        spec.networks().contains("qits-env-flow-single-app-single-seed"),
        "a platform service is on every application network of every environment: "
            + spec.networks());
    assertTrue(
        spec.networks().contains("qits-net"),
        "and the legacy network while the transition lasts: " + spec.networks());

    // The environment it is not part of deploys nothing on this event.
    assertEquals(
        0,
        given()
            .when()
            .get("/platform-deployments/api/deployments?environmentId=" + environmentId)
            .then()
            .statusCode(200)
            .extract()
            .jsonPath()
            .getList("deployments")
            .size());
  }

  @Test
  public void thePlatformPlaneIsRolledOnceByTheTierTheReleaseEntersAt() {
    // The claim that survived branch matching, restated. There is one designated platform
    // environment, and it is what decides that the plane may be rolled at all — with one instance
    // and no environment id, a per-tier fan-out was never a fan-out anyway, it was several tiers
    // taking turns overwriting one container.
    createEnvironment("flow-otherplane");
    createEnvironment("flow-thisplane");
    specs.script(
        "repo-planegate",
        new SpecSource.DeploymentSpec(PdDeploymentTarget.PLATFORM, false, null, null, null, null));

    postRelease("repo-planegate", V_B);
    awaitApplied(1);
    assertEquals(1, driver.applied().size(), "one instance, once");
    assertTrue(driver.applied().get(0).imageRef().endsWith(":" + V_B));
    assertNull(driver.applied().get(0).environmentId(), "still one instance, on no tier");
  }

  @Test
  public void declaringItselfPlatformConvertsTheEnvironmentRowsItHad() {
    // The conversion, in one test: a repository that is an environment application today can
    // become a platform service with the commit that adds its deployments.yml. The old rows must
    // not sit beside the new one — one repository deploys to one place.
    String environmentId = createEnvironment("flow-convert");
    postRelease("repo-convert", V_A);
    awaitDeployments(environmentId, 1);

    specs.script(
        "repo-convert", new SpecSource.DeploymentSpec(PdDeploymentTarget.PLATFORM, false, null, null, null, null));
    postRelease("repo-convert", V_B);
    awaitApplied(2);

    List<Map<String, Object>> registered =
        given()
            .when()
            .get("/platform-deployments/api/applications")
            .then()
            .statusCode(200)
            .extract()
            .jsonPath()
            .<Map<String, Object>>getList("applications")
            .stream()
            .filter(a -> "repo-convert".equals(a.get("repoId")))
            .toList();
    assertEquals(1, registered.size(), "one row, not two: " + registered);
    assertEquals("PLATFORM", registered.get(0).get("target"));
    assertNull(registered.get(0).get("environmentId"));
    assertNull(registered.get(0).get("branch"), "the plane has no deploy ref of its own");

    // The history moved with it: the environment it left has no deployments of it any more, and
    // the row that was serving is decommissioned rather than deleted.
    assertEquals(
        0,
        given()
            .when()
            .get("/platform-deployments/api/deployments?environmentId=" + environmentId)
            .then()
            .statusCode(200)
            .extract()
            .jsonPath()
            .getList("deployments")
            .size());
  }

  @Test
  public void flippingAPlatformServiceBackToAnEnvironmentIsRefusedOnTheRecord() {
    // The conversion runs one way only. Coming back has no answer to "which environment inherits
    // the history", and the environment deployment would find the running platform container
    // through the legacy network and remove it — leaving a row saying ACTIVE about nothing. So it
    // is refused, and the refusal is written where an operator looks: a FAILED row on the plane.
    createEnvironment("flow-unflip");
    specs.script(
        "repo-unflip", new SpecSource.DeploymentSpec(PdDeploymentTarget.PLATFORM, false, null, null, null, null));
    postRelease("repo-unflip", V_A);
    awaitApplied(1);

    // The file goes back to saying `environment`, on the tier's own branch this time.
    specs.script(
        "repo-unflip", new SpecSource.DeploymentSpec(PdDeploymentTarget.ENVIRONMENT, false, null, null, null, null));
    postRelease("repo-unflip", V_B);
    awaitWorkerIdle();

    // Nothing was registered into the environment and nothing new was deployed.
    List<Map<String, Object>> rows =
        given()
            .when()
            .get("/platform-deployments/api/applications")
            .then()
            .statusCode(200)
            .extract()
            .jsonPath()
            .<Map<String, Object>>getList("applications")
            .stream()
            .filter(a -> "repo-unflip".equals(a.get("repoId")))
            .toList();
    assertEquals(1, rows.size(), "still one row, still the platform service: " + rows);
    assertEquals("PLATFORM", rows.get(0).get("target"));
    assertEquals(1, driver.applied().size(), "the refused build deployed nothing");

    // ...and the refusal is on the record, naming the flip.
    PdDeployment refused = deploymentOf("repo-unflip", null, V_B);
    assertEquals("FAILED", refused.status.name());
    assertTrue(
        refused.detail.contains("deployment_target: environment"),
        "the detail names the flip: " + refused.detail);
    assertTrue(
        refused.detail.contains("Retire the platform service deliberately"),
        "and says what to do about it: " + refused.detail);
  }

  @Test
  public void twoIdenticalEventsArrivingTogetherRegisterOnePlatformService() {
    // Derived registration is a read-then-write. The catalogue's unique service name is one belt
    // and ServiceCatalog.upsert's own lock is another, but the contract under test is the worker:
    // handling the WHOLE event on one thread is what makes read-then-write atomic against every
    // other event — which is what the ancestor's null-environment_id row had no constraint for.
    createEnvironment("flow-once");
    specs.script(
        "repo-once", new SpecSource.DeploymentSpec(PdDeploymentTarget.PLATFORM, false, null, null, null, null));
    int senders = 8;
    java.util.concurrent.ExecutorService pool =
        java.util.concurrent.Executors.newFixedThreadPool(senders);
    java.util.concurrent.CountDownLatch go = new java.util.concurrent.CountDownLatch(1);
    List<java.util.concurrent.Future<?>> sent = new java.util.ArrayList<>();
    try {
      for (int i = 0; i < senders; i++) {
        sent.add(
            pool.submit(
                () -> {
                  go.await();
                  postRelease("repo-once", V_A);
                  return null;
                }));
      }
      go.countDown(); // every sender is parked on the latch, so they enter the intake together
      for (java.util.concurrent.Future<?> one : sent) {
        one.get();
      }
    } catch (Exception e) {
      throw new IllegalStateException("the concurrent senders failed", e);
    } finally {
      pool.shutdownNow();
    }
    awaitWorkerIdle();

    List<Map<String, Object>> rows =
        given()
            .when()
            .get("/platform-deployments/api/applications")
            .then()
            .statusCode(200)
            .extract()
            .jsonPath()
            .<Map<String, Object>>getList("applications")
            .stream()
            .filter(a -> "repo-once".equals(a.get("repoId")))
            .toList();
    assertEquals(1, rows.size(), "one platform row for one repository: " + rows);
    assertEquals("PLATFORM", rows.get(0).get("target"));
  }

  @Test
  public void aSpecThatCannotBeReadFailsTheDeploymentRatherThanGuessing() {
    // One green build first, so the registry knows where this repository deploys. That order is
    // the contract, not scaffolding: a spec read that fails for a repository nothing has
    // registered has no row to fail and records nothing (the 202-and-silence an unknown
    // repository always got); one that fails for a registered application fails it, there.
    String environmentId = createEnvironment("flow-nospec");
    postRelease("repo-nospec", V_A);
    awaitDeployments(environmentId, 1);
    driver.reset();

    specs.scriptFailure("repo-nospec", "the git host answered 500");
    postRelease("repo-nospec", V_B);

    List<Map<String, Object>> deployments = awaitDeployments(environmentId, 2);
    assertEquals("FAILED", deployments.get(0).get("status"));
    assertEquals(V_B, deployments.get(0).get("commitSha"));
    assertTrue(
        ((String) deployments.get(0).get("detail")).contains("the git host answered 500"),
        "the cause is on the row: " + deployments.get(0).get("detail"));
    // Nothing was pulled and nothing started — cd never guesses a topology.
    assertEquals(List.of(), driver.pulled());
    assertEquals(List.of(), driver.applied());
    // ...and what was serving is still serving.
    assertEquals("ACTIVE", deployments.get(1).get("status"));
  }

  @Test
  public void aSpecThatCannotBeReadForAnUnknownRepositoryRecordsNothing() {
    String environmentId = createEnvironment("flow-nospec-unknown");
    specs.scriptFailure("repo-nospec-unknown", "the git host answered 500");
    postRelease("repo-nospec-unknown", V_A);

    awaitWorkerIdle();
    awaitDeployments(environmentId, 0);
    assertEquals(List.of(), driver.pulled());
  }

  @Test
  public void eachDeploymentCarriesTheRunOfTheBuildThatCausedIt() {
    // Two green builds of the same application: each row names its own run, so the click-through
    // from a historical deployment reaches the build that produced THAT image, not the newest one.
    String environmentId = createEnvironment("flow-runid");
    postRelease("6f31a0c4-1c2b-4f7a-9b03-2ee45c1f8d61", "repo-runid", V_A);
    awaitDeployments(environmentId, 1);
    postRelease("b41d7e90-9a11-4c33-8f0d-77c0e13a4412", "repo-runid", V_B);

    List<Map<String, Object>> deployments = awaitDeployments(environmentId, 2);
    assertEquals("b41d7e90-9a11-4c33-8f0d-77c0e13a4412", deployments.get(0).get("runId"));
    assertEquals(V_B, deployments.get(0).get("commitSha"));
    assertEquals("6f31a0c4-1c2b-4f7a-9b03-2ee45c1f8d61", deployments.get(1).get("runId"));
    assertEquals(V_A, deployments.get(1).get("commitSha"));
  }

  @Test
  public void aDeploymentWithNoRunNamesNoneRatherThanInventingOne() {
    // The sender may omit runId — a SoftwareRelease carries none at all, so this is the ordinary
    // shape now and the read surface must say null rather than guess a run from the version.
    String environmentId = createEnvironment("flow-norunid");
    given()
        .contentType(ContentType.JSON)
        .body(Map.of("repoId", "repo-norunid", "version", V_A))
        .when()
        .post("/platform-deployments/api/events/software-released")
        .then()
        .statusCode(202);

    List<Map<String, Object>> deployments = awaitDeployments(environmentId, 1);
    assertEquals("ACTIVE", deployments.get(0).get("status"));
    assertNull(deployments.get(0).get("runId"));
  }

  @Test
  public void anOversizedRunIdIsRejectedRatherThanFailingTheInsert() {
    // The column is bounded, and the sender is fire-and-forget: without the boundary check this is
    // a 500 on an insert and a deployment that silently never happens.
    given()
        .contentType(ContentType.JSON)
        .body(
            Map.of(
                "runId", "r".repeat(300),
                "repoId", "repo-bigrun",
                "version", V_A))
        .when()
        .post("/platform-deployments/api/events/software-released")
        .then()
        .statusCode(400);
  }

  @Test
  public void malformedIdentifiersAreRejectedNotQueued() {
    // The intake is attacker-reachable; a version that could escape an image reference must never
    // reach a docker argv (400 from this component's own validation, not a queued deployment).
    given()
        .contentType(ContentType.JSON)
        .body(
            Map.of(
                "repoId", "repo-x",
                "version", "latest; docker run --privileged evil"))
        .when()
        .post("/platform-deployments/api/events/software-released")
        .then()
        .statusCode(400);
  }
}
