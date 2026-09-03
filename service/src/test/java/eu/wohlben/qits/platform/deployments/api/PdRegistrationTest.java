package eu.wohlben.qits.platform.deployments.api;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import eu.wohlben.qits.platform.deployments.deployments.control.DeployService;
import eu.wohlben.qits.platform.deployments.deployments.control.DeploymentDriver;
import eu.wohlben.qits.platform.deployments.deployments.control.FakeDeploymentDriver;
import eu.wohlben.qits.platform.deployments.deployments.control.FakeResourceProvisioner;
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
 * Derived registration and deploy resolution: what a green build <b>writes</b> into the catalogue,
 * and what it <b>reads back</b> to decide where to deploy.
 *
 * <p>The ancestor's version of this suite ran against a stub HTTP server standing in for
 * qits-serviceregistry, and had a twin ({@code CdRegistryOutageTest}) for the case where the peer
 * was down. Both dissolved with the merge: registration is a local transaction, so there is no wire
 * to stub and no outage to have a posture about. Every claim the first suite made survives here,
 * asserted through this component's own read surface instead of through a stub's recorded calls.
 * The claims of the second are gone with the failure mode — except one, which was never about the
 * peer at all and is held by {@code PdPinApiTest}: the pins read nothing but deployment rows.
 */
@QuarkusTest
public class PdRegistrationTest {

  // Released versions, not commit shas: a deployment is created from the image the release tagged.
  private static final String V_A = "2026.903.1";
  private static final String V_B = "2026.903.2";

  /** What a githost repository key looks like since the identity rollback: opaque, and internal. */
  private static final String STORAGE_UUID = "6d0c2b1e-3a44-4b0e-9a5b-2b1c0d9e4f88";

  @Inject FakeDeploymentDriver driver;
  @Inject FakeSpecSource specs;
  @Inject FakeResourceProvisioner provisioner;
  @Inject DeployService deployService;

  @BeforeEach
  void reset() {
    driver.reset();
    specs.reset();
    provisioner.reset();
  }

  @Test
  public void aReleaseRegistersTheServiceAndLinksItIntoTheTierItEntersAt() {
    // Branch matching is gone: a release lands in the designated platform environment, which is
    // where this platform enters. What the row records is a link into that tier and no branch of
    // its own.
    String entry = createEnvironment("reg-one");
    postRelease("repo-reg", V_A);
    awaitApplied(1);

    Map<String, Object> service = service("repo-reg");
    assertEquals("ENVIRONMENT", service.get("target"));
    assertNull(service.get("branch"), "an environment service takes its branch from its tier");
    assertEquals(List.of(entry), service.get("environmentIds"));
  }

  @Test
  public void aReleaseKeepsTheLinksAnotherTierAlreadyHad() {
    // The regression this guards, and it outlives branch matching: the upsert replaces the link
    // set, so a release that sent only the tier it entered would silently unlink every tier a
    // promotion had already reached. Staged by moving the designation, which is what creating a
    // platform environment does.
    String preprod = createEnvironment("reg-preprod");
    postRelease("repo-both", V_A);
    awaitApplied(1);
    String dev = createEnvironment("reg-dev");
    postRelease("repo-both", V_B);
    awaitApplied(2);

    assertEquals(
        List.of(preprod, dev),
        service("repo-both").get("environmentIds"),
        "the preprod link survived the release that entered at dev");
  }

  @Test
  public void aPublicNodeIsRegisteredWithAvailableOnEnv() {
    String environmentId = createEnvironment("reg-hub");
    specs.script(
        "repo-reg-gw",
        new SpecSource.DeploymentSpec(PdDeploymentTarget.ENVIRONMENT, true, null, null, null, null));
    postRelease("repo-reg-gw", V_A);
    awaitApplied(1);

    Map<String, Object> service = service("repo-reg-gw");
    assertEquals(true, service.get("availableOnEnv"), "the spec's availableOnEnv is written down");
    assertEquals(List.of(environmentId), service.get("environmentIds"));
  }

  @Test
  public void aPlatformServiceIsRegisteredWithNoBranchAndNoLinks() {
    // A platform service has NO links, and that absence is the model: it is implicitly present
    // everywhere, which is what makes a new environment pick it up without anyone editing it. It
    // carries no branch either, and that is the newer half: the plane has no deploy ref of its own,
    // so there is nothing to write down.
    createEnvironment("reg-platform");
    specs.script(
        "repo-reg-idp",
        new SpecSource.DeploymentSpec(PdDeploymentTarget.PLATFORM, false, null, null, null, null));
    postRelease("repo-reg-idp", V_A);
    awaitApplied(1);

    Map<String, Object> service = service("repo-reg-idp");
    assertEquals("PLATFORM", service.get("target"));
    assertNull(service.get("branch"), "the plane has no deploy ref of its own any more");
    assertEquals(List.of(), service.get("environmentIds"));
  }

  @Test
  public void aPlatformServiceShipsIntoTheDesignatedEntryTier() {
    // The entry tier is one question, asked the same way on both planes: is a platform environment
    // designated. What it deploys is one instance with no LINKS — and, since V8, one instance IN
    // that tier: the plane's deployments carry the environment on the row, in the labels, in
    // QITS_ENVIRONMENT and on all four events, because "no environment" was a fact the plane could
    // not state about itself rather than a fact about the plane.
    String environmentId = createEnvironment("reg-trunk");
    specs.script(
        "repo-reg-trunk",
        new SpecSource.DeploymentSpec(PdDeploymentTarget.PLATFORM, false, null, null, null, null));
    postRelease("repo-reg-trunk", V_A);
    awaitApplied(1);

    Map<String, Object> service = service("repo-reg-trunk");
    assertEquals("PLATFORM", service.get("target"));
    assertEquals(
        List.of(), service.get("environmentIds"), "still no link: it is present everywhere");
    DeploymentDriver.ServiceSpec spec = driver.applied().get(0);
    assertEquals(environmentId, spec.environmentId(), "and deployed into the designated tier");
    assertEquals("reg-trunk", spec.environmentName());
    assertEquals(PdDeploymentTarget.PLATFORM, spec.target(), "the plane is stated, not inferred");
    // ...and the one thing that does NOT take the tier: the address.
    assertEquals("repo-reg-trunk", spec.wireAlias(), "the bare alias is what makes it reachable"
        + " from every tier without knowing which one the plane runs in");
  }

  @Test
  public void theRetiredSingletonSpellingStillRegistersAPlatformService() {
    // The alias is not a parser curiosity: a repository still carrying the word must keep deploying
    // across the cutover, and what it gets has to be the platform plane in every respect.
    createEnvironment("reg-alias");
    specs.script(
        "repo-alias", DeploymentSpecParserAlias.parse("deployment_target: singleton\n"));
    postRelease("repo-alias", V_A);
    awaitApplied(1);

    assertEquals("PLATFORM", service("repo-alias").get("target"));
    // No environment segment in the derived name: the plane's names are unqualified even now that
    // it has a tier, and the word that used to fill the gap is in the repository names.
    assertTrue(
        driver.applied().get(0).deploymentName().startsWith("qits-pd-repo-alias-"),
        driver.applied().get(0).deploymentName());
  }

  @Test
  public void aPlatformDeploymentIsReadBackByAskingForThePlaneByName() {
    // The gap this closes: the deployment listing's filter is required, so the plane could not be
    // asked for at all — every platform row was recorded and then unreadable, and a client drawing
    // "what is deployed" showed the tiers and nothing else. `platform` is the stand-in the
    // application id already carries, reused as the filter value.
    //
    // IT IS A PLANE QUESTION AND NOT A NULL SCAN NOW. The rows carry the designated tier, so both
    // the listing and the `platform:` id come off pd_deployment.deployment_target — the id most of
    // all, since the catalogue side has no link to derive a tier from and the two have to join.
    createEnvironment("reg-plane");
    specs.script(
        "repo-reg-plane",
        new SpecSource.DeploymentSpec(PdDeploymentTarget.PLATFORM, false, null, null, null, null));
    postRelease("repo-reg-plane", V_A);
    awaitApplied(1);
    awaitWorkerIdle();

    Map<String, Object> deployment =
        platformDeployments().stream()
            .filter(d -> "repo-reg-plane".equals(d.get("applicationName")))
            .findFirst()
            .orElseGet(() -> fail("the platform listing did not carry the deployment"));
    assertEquals("ACTIVE", deployment.get("status"));
    assertEquals(
        "platform:repo-reg-plane",
        deployment.get("applicationId"),
        "the id a client joins against the applications listing");
  }

  @Test
  public void thePlatformPlaneCarriesNoTieredDeployment() {
    // The filter is a filter, not a widening: asking for the plane must not answer with the rows of
    // every tier as well. An environment deployment and a platform one, and only the latter comes
    // back — which is a sharper claim than it was, because both rows now name the same tier and
    // only the plane column tells them apart.
    createEnvironment("reg-planes");
    postRelease("repo-reg-tiered", V_A);
    awaitApplied(1);
    specs.script(
        "repo-reg-crossplane",
        new SpecSource.DeploymentSpec(PdDeploymentTarget.PLATFORM, false, null, null, null, null));
    postRelease("repo-reg-crossplane", V_A);
    awaitApplied(2);
    awaitWorkerIdle();

    List<String> names = platformDeployments().stream().map(d -> (String) d.get("applicationName")).toList();
    assertTrue(names.contains("repo-reg-crossplane"), names.toString());
    assertTrue(!names.contains("repo-reg-tiered"), "a tier's rows stayed out of the plane: " + names);
  }

  @Test
  public void aRepositoryThatNamesNoHealthPathGetsTheConventionOne() {
    // The debt this closes: registration once had no source for the path, so every row was written
    // null and every service mounted under its own prefix failed a gate against a URL that 404s.
    createEnvironment("reg-health");
    postRelease("qits-observability", V_A);
    awaitApplied(1);

    assertEquals(
        "/observability/q/health/ready",
        service("qits-observability").get("healthPath"),
        "the convention is derived from the name and WRITTEN, not left to the deploy default");
    assertEquals("/observability/q/health/ready", driver.applied().get(0).healthPath());
  }

  @Test
  public void theRepositorysOwnHealthPathWinsOverTheConvention() {
    // The gateway owns the root path space, so the convention would send its gate to a 404.
    createEnvironment("reg-health-gw");
    specs.script(
        "qits-gateway",
        new SpecSource.DeploymentSpec(
            PdDeploymentTarget.ENVIRONMENT, true, null, "/q/health/ready", null, null));
    postRelease("qits-gateway", V_A);
    awaitApplied(1);

    assertEquals("/q/health/ready", service("qits-gateway").get("healthPath"));
  }

  @Test
  public void aDeclaredHealthCmdReachesTheDriverAndNoRowHoldsIt() {
    // The deployable-image case end to end: a plain image names its own probe, the driver is
    // handed it, and nothing is written down — the spec is read again before every
    // deployment, so a column would only be a second copy to keep right.
    createEnvironment("reg-health-cmd");
    specs.script(
        "qits-db",
        new SpecSource.DeploymentSpec(
            PdDeploymentTarget.ENVIRONMENT,
            false,
            null,
            null,
            "pg_isready -U postgres || exit 1",
            null));
    postRelease("qits-db", V_A);
    awaitApplied(1);

    assertEquals("pg_isready -U postgres || exit 1", driver.applied().get(0).healthCmd());
    // The row keeps the convention path it always would have: the command is this deployment's,
    // not the service's identity.
    assertEquals("/db/q/health/ready", service("qits-db").get("healthPath"));
  }

  @Test
  public void anOperatorsHealthPathSurvivesAReRegistration() {
    // A path already on the row is somebody's fix for a service the convention could not guess. A
    // later green build that says nothing about the path must leave it alone.
    String environmentId = createEnvironment("reg-health-keep");
    given()
        .contentType(ContentType.JSON)
        .body(
            Map.of(
                "deploymentTarget", "ENVIRONMENT",
                "availableOnEnv", false,
                "healthPath", "/hand/placed/health",
                "environmentIds", List.of(environmentId)))
        .when()
        .put("/platform-deployments/api/services/qits-odd")
        .then()
        .statusCode(201);

    postRelease("qits-odd", V_A);
    awaitApplied(1);

    assertEquals("/hand/placed/health", service("qits-odd").get("healthPath"));
  }

  @Test
  public void aPlatformServiceGetsTheSameHealthPathResolution() {
    // The platform plane is not a different rule: the convention, the spec and an existing value
    // rank the same way there.
    createEnvironment("reg-health-plane");
    specs.script(
        "qits-idp",
        new SpecSource.DeploymentSpec(PdDeploymentTarget.PLATFORM, false, null, null, null, null));
    postRelease("qits-idp", V_A);
    awaitApplied(1);

    assertEquals("/idp/q/health/ready", service("qits-idp").get("healthPath"));
    assertEquals("/idp/q/health/ready", driver.applied().get(0).healthPath());
  }

  @Test
  public void aRepositoryWhoseIdIsNotADnsLabelRegistersNothing() {
    // The name is the image path segment and the network alias, so a repository that cannot be one
    // cannot be deployed by convention at all. The intake is fire-and-forget, so this is a log line
    // and a silence rather than a row nobody can act on.
    createEnvironment("reg-badname");
    postRelease("Repo-Bad", V_A);
    awaitWorkerIdle();

    assertEquals(List.of(), driver.applied());
  }

  @Test
  public void aBuildCarryingTheNamePairIsNamedAfterTheNameAndNeverAfterTheStorageId() {
    // THE invariant of the identity rollback. The git host's repository key is an opaque UUID now,
    // and every pipeline yml still pushes a literal qits/<name>:<sha> — so the moment the deployer
    // named an application after the storage id, every deployment would end IMAGE_MISSING and the
    // orchestrator's garbage collector would delete the images that are live. The name wins, and
    // it wins in all five derived places at once: the catalogue key, the image reference, the wire
    // alias, the container name and the row the pins are read off.
    String environmentId = createEnvironment("reg-named");
    specs.script(
        "repo-reg-named",
        new SpecSource.DeploymentSpec(PdDeploymentTarget.ENVIRONMENT, true, null, null, null, null));

    postRelease(STORAGE_UUID, "qits", "repo-reg-named", V_A);
    awaitApplied(1);

    Map<String, Object> service = service("repo-reg-named");
    assertEquals(List.of(environmentId), service.get("environmentIds"));
    assertEquals(true, service.get("availableOnEnv"), "the spec was read for the NAME");
    assertNull(service(STORAGE_UUID), "the storage id registered nothing of its own");

    DeploymentDriver.ServiceSpec applied = driver.applied().get(0);
    assertEquals("repo-reg-named", applied.applicationName());
    assertEquals("reg-named-repo-reg-named", applied.wireAlias());
    assertTrue(
        applied.imageRef().endsWith("/repo-reg-named:" + V_A), "image ref: " + applied.imageRef());
    assertTrue(
        applied.deploymentName().startsWith("qits-pd-reg-named-repo-reg-named-"),
        applied.deploymentName());
    assertFalse(applied.imageRef().contains(STORAGE_UUID), "no UUID anywhere in the image path");
    assertFalse(applied.deploymentName().contains(STORAGE_UUID), applied.deploymentName());
  }

  @Test
  public void aBuildWithNoNamesIsNamedAfterItsRepositoryIdExactlyAsBefore() {
    // The compatibility arm, byte for byte: before the rollback the storage id WAS the name, so an
    // announcement carrying neither field derives every identifier from the id, as it always did.
    createEnvironment("reg-unnamed");

    postRelease("repo-reg-unnamed", V_A);
    awaitApplied(1);

    DeploymentDriver.ServiceSpec applied = driver.applied().get(0);
    assertEquals("repo-reg-unnamed", applied.applicationName());
    assertEquals("reg-unnamed-repo-reg-unnamed", applied.wireAlias());
    assertTrue(
        applied.imageRef().endsWith("/repo-reg-unnamed:" + V_A),
        "image ref: " + applied.imageRef());
    assertEquals(
        List.of("repo-reg-unnamed"),
        List.of((String) service("repo-reg-unnamed").get("name")),
        "the catalogue is keyed by the same string it always was");
  }

  @Test
  public void aRepositoryThatDeclaresAnApplicationDeploysUnderThatNameAndNotItsOwn() {
    // The rename in one test. The repository is qits-reg-ci-service, its deployments.yml says
    // `application: qits-reg-ci`, and every name the platform can see stays what it was: the
    // catalogue key, the health path, the wire alias, the container name, the image the pipeline
    // still pushes, and the database this deployment provisions. Nothing about the running platform
    // moves when a repository is renamed — that is the whole point of the key.
    String environmentId = createEnvironment("reg-app");
    specs.script(
        "qits-reg-ci-service",
        DeploymentSpecParserAlias.parse(
            "application: qits-reg-ci\nresources: postgresql:db\n"));

    postRelease(STORAGE_UUID, "qits", "qits-reg-ci-service", V_A);
    awaitApplied(1);

    assertNull(service("qits-reg-ci-service"), "the repository's own name registered nothing");
    Map<String, Object> service = service("qits-reg-ci");
    assertEquals(List.of(environmentId), service.get("environmentIds"));
    assertEquals(
        "/reg-ci/q/health/ready",
        service.get("healthPath"),
        "the convention path follows the application, not the repository");

    DeploymentDriver.ServiceSpec applied = driver.applied().get(0);
    assertEquals("qits-reg-ci", applied.applicationName());
    assertEquals("reg-app-qits-reg-ci", applied.wireAlias());
    assertTrue(applied.imageRef().endsWith("/qits-reg-ci:" + V_A), "image: " + applied.imageRef());
    assertTrue(
        applied.deploymentName().startsWith("qits-pd-reg-app-qits-reg-ci-"),
        applied.deploymentName());
    assertFalse(applied.imageRef().contains("service"), "image: " + applied.imageRef());
    assertEquals(
        "qits_reg_ci",
        provisioner.requests().get(0).databaseName(),
        "the default database is derived from the application name too");
  }

  @Test
  public void aRepositoryThatStatesNoApplicationIsNamedAfterItselfExactlyAsBefore() {
    // The other arm, and it is the one that has to stay byte-identical: a file that says nothing
    // about the key — which is every file that exists — deploys under the repository's own name.
    createEnvironment("reg-noapp");
    specs.script(
        "qits-reg-plain",
        DeploymentSpecParserAlias.parse("resources: postgresql:db\nroutes: /reg-plain\n"));

    postRelease(STORAGE_UUID, "qits", "qits-reg-plain", V_A);
    awaitApplied(1);

    DeploymentDriver.ServiceSpec applied = driver.applied().get(0);
    assertEquals("qits-reg-plain", applied.applicationName());
    assertEquals("reg-noapp-qits-reg-plain", applied.wireAlias());
    assertTrue(applied.imageRef().endsWith("/qits-reg-plain:" + V_A), applied.imageRef());
    assertEquals("/reg-plain/q/health/ready", service("qits-reg-plain").get("healthPath"));
    assertEquals("qits_reg_plain", provisioner.requests().get(0).databaseName());
  }

  @Test
  public void anUnreadableSpecResolvesFromTheLinksTheServiceAlreadyHas() {
    // The spec read is the one remote call left, so it is the one that can still fail. When it
    // does, the failure is recorded where the service is already registered — never guessed at.
    String environmentId = createEnvironment("reg-fallback");
    given()
        .contentType(ContentType.JSON)
        .body(
            Map.of(
                "deploymentTarget", "ENVIRONMENT",
                "availableOnEnv", false,
                "environmentIds", List.of(environmentId)))
        .when()
        .put("/platform-deployments/api/services/repo-fallback")
        .then()
        .statusCode(201);
    specs.scriptFailure("repo-fallback", "the git host answered 500");

    postRelease("repo-fallback", V_A);
    List<Map<String, Object>> deployments = awaitDeployments(environmentId, 1);
    assertEquals("FAILED", deployments.get(0).get("status"));
    assertTrue(
        ((String) deployments.get(0).get("detail")).contains("the git host answered 500"),
        "the cause is on the row: " + deployments.get(0).get("detail"));
    assertEquals(List.of(), driver.pulled(), "a topology is never guessed");
  }

  // --- helpers ----------------------------------------------------------------------------------

  /** The spec parser, reached through its own package so the alias case reads as a real file. */
  private static final class DeploymentSpecParserAlias {
    static SpecSource.DeploymentSpec parse(String yaml) {
      return eu.wohlben.qits.platform.deployments.deployments.control.DeploymentSpecParser.parse(
          yaml, "a test file");
    }
  }

  /**
   * The tier a release enters at — the designated platform environment, of which there is exactly
   * one. Creating one MOVES the designation, so every method here makes its own the entry tier and
   * the suite's shared database never holds two.
   */
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

  private void postRelease(String repoId, String version) {
    given()
        .contentType(ContentType.JSON)
        .body(Map.of("runId", "run-reg", "repoId", repoId, "version", version))
        .when()
        .post("/platform-deployments/api/events/software-released")
        .then()
        .statusCode(202);
  }

  /** The storage id, plus the repository's public address — which is what names the application. */
  private void postRelease(String repoId, String projectId, String repoName, String version) {
    given()
        .contentType(ContentType.JSON)
        .body(
            Map.of(
                "runId", "run-reg",
                "repoId", repoId,
                "projectId", projectId,
                "repoName", repoName,
                "version", version))
        .when()
        .post("/platform-deployments/api/events/software-released")
        .then()
        .statusCode(202);
  }

  /** One service off the catalogue read, or null — the surface a caller checks registration on. */
  private Map<String, Object> service(String name) {
    return given()
        .when()
        .get("/platform-deployments/api/services")
        .then()
        .statusCode(200)
        .extract()
        .jsonPath()
        .<Map<String, Object>>getList("services")
        .stream()
        .filter(s -> name.equals(s.get("name")))
        .findFirst()
        .orElse(null);
  }

  private void awaitApplied(int count) {
    long deadline = System.currentTimeMillis() + 15_000;
    while (driver.applied().size() < count && System.currentTimeMillis() < deadline) {
      sleep();
    }
    assertEquals(count, driver.applied().size(), "applied services");
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
      if (deployments.size() == count
          && deployments.stream()
              .noneMatch(
                  d -> "QUEUED".equals(d.get("status")) || "STARTING".equals(d.get("status")))) {
        return deployments;
      }
      sleep();
    }
    return fail("deployments of " + environmentId + " did not settle to " + count);
  }

  /** The plane's own rows, asked for the way a client asks — by the word, not by a tier's id. */
  private List<Map<String, Object>> platformDeployments() {
    return given()
        .when()
        .get("/platform-deployments/api/deployments?environmentId=platform")
        .then()
        .statusCode(200)
        .extract()
        .jsonPath()
        .getList("deployments");
  }

  private void awaitWorkerIdle() {
    try {
      deployService.awaitIdle();
    } catch (Exception e) {
      throw new IllegalStateException("the deploy worker did not drain", e);
    }
  }

  private static void sleep() {
    try {
      Thread.sleep(50);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }
}
