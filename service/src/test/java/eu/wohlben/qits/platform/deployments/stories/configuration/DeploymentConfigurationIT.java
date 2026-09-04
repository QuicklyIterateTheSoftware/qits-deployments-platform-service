package eu.wohlben.qits.platform.deployments.stories.configuration;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.hasItem;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import eu.wohlben.qits.platform.deployments.api.TokenValidationBootstrapIT;
import eu.wohlben.qits.platform.deployments.stories.support.StoryIdentities;
import eu.wohlben.qits.platform.deployments.stories.support.StoryPeers;
import eu.wohlben.qits.platform.deployments.stories.support.StoryPlatform;
import eu.wohlben.qits.platform.deployments.stories.support.StoryProfile;
import eu.wohlben.qits.platform.deployments.stories.support.StorySwarm;
import eu.wohlben.qits.platform.deployments.stories.support.StoryTarget;
import eu.wohlben.qits.userflows.Interactions;
import eu.wohlben.qits.userflows.Network;
import eu.wohlben.qits.userflows.NetworkCapture;
import eu.wohlben.qits.userflows.NetworkEdge;
import eu.wohlben.qits.userflows.NetworkTaps;
import eu.wohlben.qits.userflows.UserStory;
import eu.wohlben.qits.userflows.UserStoryDescription;
import eu.wohlben.qits.userflows.UserflowRunsAfter;
import eu.wohlben.qits.userflows.report.ReportAssertions;
import eu.wohlben.qits.userflows.report.UserflowReport;
import io.quarkus.test.junit.QuarkusIntegrationTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.http.ContentType;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.TestMethodOrder;

/**
 * <b>Where a deployed container's configuration comes from</b>, and what happens when the place it
 * comes from cannot be reached.
 *
 * <p>The direction is the first thing to read off these diagrams, because it is the opposite of what
 * the word "configuration" suggests: <b>nothing pushes anything into a deployment</b>. This
 * component PULLS, with its own machine identity, at the moment it builds an argv — {@code GET
 * /configuration/api/applications/&lt;app&gt;/resolved}, once per argv, per deployment. No caller of
 * this component's API can name a key, and the url it reads from is deployment config like
 * everything else.
 *
 * <p><b>Set, that service is AUTHORITATIVE — and authoritative means SOLE.</b> The config volume's
 * file is then not read at all. It was layered above the file for one release, on the reasoning that
 * a half-migrated platform should keep deploying applications whose keys had not moved yet, and it
 * read well and deployed badly: a key DELETED from the service came straight back out of a file
 * nobody had emptied, so the one operation the service exists to make possible was the one it could
 * not perform. Two sources cannot both be authoritative, and the one left on a volume is the stale
 * one by construction.
 *
 * <p>Which is why the second story is the important one. An unreachable configuration service
 * REFUSES the deployment, and there is deliberately no fall-back — not to the file, not to the boot
 * config, not to anything read earlier. A stale extras value ships invisibly, as a green deployment,
 * and that failure cost a day on 2026-08-16.
 *
 * <p><b>The credential is the {@code configuration} named oidc client and the peer count is one.</b>
 * It ships disabled, because a platform running qits-configuration behind forward-auth on qits-net
 * is a supported migration posture; {@code StoryProfile} turns it on, so these diagrams carry the
 * hop that fail-closed reading actually costs — a token minted at the idp before the read that
 * presents it.
 */
@QuarkusIntegrationTest
@TestProfile(StoryProfile.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class DeploymentConfigurationIT {

  static final String CATEGORY = "configuration";

  static final String READ_SLUG =
      "a-deployment-reads-its-configuration-with-the-deployers-own-machine-identity";
  static final String REFUSED_SLUG =
      "a-configuration-service-that-cannot-be-read-refuses-the-deployment";

  static final String CONFIGURED = StoryPeers.CONFIGURED;

  static final String CONFIGURED_REPO_ID = "story-configured-storage-id";

  static final String MISCONFIGURED = StoryPeers.MISCONFIGURED;

  static final String MISCONFIGURED_REPO_ID = "story-misconfigured-storage-id";

  static final String CONFIGURED_VERSION = "2026.903.101";
  static final String MISCONFIGURED_VERSION = "2026.903.102";

  static final String CONFIGURED_SERVICE = StoryTarget.wireAlias(CONFIGURED);

  static final String MISCONFIGURED_SERVICE = StoryTarget.wireAlias(MISCONFIGURED);

  static final String STORE = "postgresql";

  static final String STORE_LABEL = "the deployment row, written before anything is attempted";

  private static final List<String> MINTED = new ArrayList<>();

  @BeforeAll
  static void tapEveryHopADeploymentMakes() {
    NetworkTaps.restAssured(StoryTarget.SERVICE);
    StorySwarm.installSource();
    StoryPeers.install();
  }

  @BeforeEach
  void theTierExists() {
    StoryPlatform.provision();
  }

  @UserStory(
      value = "A deployment reads its configuration with the deployers own machine identity",
      category = CATEGORY)
  @UserStoryDescription(
      """
      An application needs more than its image: an extra environment variable, a volume, a published
      port, a DNS name it also answers to. None of that is in the repository — where a name resolves
      and which host directory is mounted are properties of the platform, not of the code — so it
      lives in qits-configuration and this component reads it while it is assembling the
      orchestrator's argv.

      Two things about the read are the whole story. It happens ONCE PER ARGV and the answer is one
      snapshot, because every reading of one deployment has to agree with every other; and it
      presents a credential of this component's own, minted at the idp, because qits-configuration
      is credential-bearing infrastructure whose read surface is guarded. What comes back is a flat
      map in the deployer's own grammar, and the one entry this application states arrives on the
      created service as an environment variable.
      """)
  @UserflowRunsAfter(TokenValidationBootstrapIT.class)
  @Order(1)
  void theArgvIsAssembledFromWhatQitsConfigurationServes(Interactions story, Network network) {
    NetworkCapture.actor(StoryIdentities.CI);
    String bearer = StoryIdentities.machineToken(StoryIdentities.CI);
    MINTED.add(bearer);

    StoryIdentities.bearer(given(), bearer)
        .contentType(ContentType.JSON)
        .body(
            softwareReleased(
                "run-story-configured-1", CONFIGURED_REPO_ID, CONFIGURED, CONFIGURED_VERSION))
        .post(StoryTarget.SOFTWARE_RELEASED_PATH)
        .then()
        .statusCode(202);
    story
        .note("a green build of " + CONFIGURED + ", an application qits-configuration has an entry for")
        .as("build-announced");

    JsonNode row = StoryPlatform.awaitSettled(CONFIGURED, CONFIGURED_VERSION);
    assertEquals("ACTIVE", row.path("status").asText(), "the deployment did not go live: " + row);
    story
        .note(
            "before the orchestrator is asked for anything, the deployer mints a token of its own"
                + " and reads " + CONFIGURED + "'s configuration with it")
        .as("configuration-read");

    List<String> argv = StorySwarm.argvOf("service create " + CONFIGURED_SERVICE);
    assertTrue(
        StorySwarm.flagValues(argv, "--env").contains(StoryPeers.EXTRA_ENV_VARIABLE),
        "what qits-configuration serves never reached the argv: " + argv);
    story
        .note(
            "the one entry it serves — " + StoryPeers.EXTRA_ENV_KEY + " — is on the created"
                + " service as " + StoryPeers.EXTRA_ENV_VARIABLE + ", and nothing translated the"
                + " key on the way: ServiceExtras is the single parser of that grammar")
        .as("entry-reached-the-container");

    // This component writes four variables of its own on every argv, BEFORE the deployment's own,
    // because docker keeps the last assignment of a repeated key — so they are defaults an
    // operator overrides rather than values that win.
    List<String> environment = StorySwarm.flagValues(argv, "--env");
    assertTrue(
        environment.indexOf("QITS_APPLICATION=" + CONFIGURED)
            < environment.indexOf(StoryPeers.EXTRA_ENV_VARIABLE),
        "the deployer's own variables must be written before config's: " + environment);
    story
        .note(
            "the deployer's own identity variables are written first, so what configuration says"
                + " outranks what this component defaults — the ordering IS the precedence rule")
        .as("config-outranks-the-default");

    network.declare(NetworkEdge.JDBC, StoryTarget.SERVICE, STORE, STORE_LABEL);
  }

  @UserStory(
      value = "A configuration service that cannot be read refuses the deployment",
      category = CATEGORY)
  @UserStoryDescription(
      """
      There is no fall-back, and the absence is the feature. If qits-configuration answers anything
      but 200 — it is being redeployed, its database is gone, it has never heard of this application
      — this deployment is REFUSED, naming the url. It does not fall back to the config volume's
      file, to the boot config, or to anything read earlier: a value read earlier may be months out
      of date, and a deployment carrying one ships green and serves the wrong thing until somebody
      notices.

      The patience is a bounded budget rather than a wait — a service being redeployed is a few
      seconds of refusals and no deployment should die of one, while an outage that outlasts the
      budget must be loud rather than an unbounded wait on a worker that has every other event
      queued behind it. And the refusal costs nothing: the argv is built before the command runs, so
      a deployment that is refused here has created nothing, removed nothing and touched no network.
      """)
  @UserflowRunsAfter(TokenValidationBootstrapIT.class)
  @Order(2)
  void anUnreadableConfigurationIsARefusalRatherThanAStaleValue(
      Interactions story, Network network) {
    int before = StorySwarm.mark();
    NetworkCapture.actor(StoryIdentities.CI);
    String bearer = StoryIdentities.machineToken(StoryIdentities.CI);
    MINTED.add(bearer);

    StoryIdentities.bearer(given(), bearer)
        .contentType(ContentType.JSON)
        .body(
            softwareReleased(
                "run-story-misconfigured-1",
                MISCONFIGURED_REPO_ID,
                MISCONFIGURED,
                MISCONFIGURED_VERSION))
        .post(StoryTarget.SOFTWARE_RELEASED_PATH)
        .then()
        .statusCode(202);
    story
        .note("a green build of " + MISCONFIGURED + ", whose configuration this platform cannot read")
        .as("build-announced");

    JsonNode row = StoryPlatform.awaitSettled(MISCONFIGURED, MISCONFIGURED_VERSION);
    assertEquals(
        "FAILED",
        row.path("status").asText(),
        "an unreadable configuration must fail the deployment rather than deploy without it: " + row);
    String detail = row.path("detail").asText();
    assertTrue(
        detail.contains("/configuration/api/applications/" + MISCONFIGURED + "/resolved"),
        "the refusal does not name the url it could not read: " + detail);
    story
        .note(
            "the deployment is FAILED and the row names the url — not a warning and not a"
                + " deployment carrying whatever was read last time")
        .as("refusal-names-the-url");

    List<String> calls = StorySwarm.callsSince(before);
    assertFalse(
        calls.contains(StorySwarm.label("service create " + MISCONFIGURED_SERVICE, "0")),
        "a refused deployment created a service: " + calls);
    assertFalse(
        calls.contains(StorySwarm.label("service update " + MISCONFIGURED_SERVICE, "0")),
        "a refused deployment updated a service: " + calls);
    story
        .note(
            "and nothing was applied: the argv is assembled before the command runs, so the"
                + " refusal happens with the orchestrator still only having been asked questions")
        .as("nothing-applied");

    NetworkCapture.actor(StoryIdentities.OPERATOR);
    StoryIdentities.person(given(), "story-operator")
        .get(StoryTarget.DEPLOYMENTS_PATH + "?environmentId=" + StoryPlatform.tierId())
        .then()
        .statusCode(200)
        .body("deployments.applicationName", hasItem(MISCONFIGURED));
    story
        .note("an operator finds the refusal where every other outcome is — on the tier's listing")
        .as("refusal-is-readable");

    network.declare(NetworkEdge.JDBC, StoryTarget.SERVICE, STORE, STORE_LABEL);
  }

  private static Map<String, Object> softwareReleased(
      String runId, String repoId, String repoName, String version) {
    return Map.of(
        "runId", runId,
        "repoId", repoId,
        "projectId", StoryTarget.PROJECT,
        "repoName", repoName,
        "version", version);
  }

  @AfterAll
  static void everyConfigurationStoryIsComplete() {
    // --- the read ---------------------------------------------------------------------------------
    ReportAssertions.assertComplete(CATEGORY, READ_SLUG, UserflowReport.PASSED);
    for (String step :
        List.of(
            "build-announced",
            "configuration-read",
            "entry-reached-the-container",
            "config-outranks-the-default")) {
      ReportAssertions.assertStepId(CATEGORY, READ_SLUG, step);
    }
    intake(READ_SLUG);
    peer(READ_SLUG, StoryPeers.GIT_HOST, StoryPeers.specLabel(CONFIGURED, CONFIGURED_VERSION, 200));
    // The credential, and then the read that presents it. Two arrows rather than one, because they
    // are two peers — and the mint is what makes the read fail-closed rather than anonymous.
    peer(READ_SLUG, StoryPeers.IDP, StoryPeers.tokenLabel());
    peer(READ_SLUG, StoryPeers.CONFIGURATION, StoryPeers.resolvedLabel(CONFIGURED, 200));
    for (String call : StorySwarm.createCalls(CONFIGURED_SERVICE, CONFIGURED, CONFIGURED_VERSION)) {
      swarm(READ_SLUG, call);
    }
    ReportAssertions.assertDeclaredEdge(
        CATEGORY, READ_SLUG, NetworkEdge.JDBC, StoryTarget.SERVICE, STORE, STORE_LABEL);
    // One event in, three peers read, eight questions to the orchestrator, one store. No operator
    // read in this one: the story is about what a deployment ASKS FOR, not about reading it back.
    ReportAssertions.assertEdgeCount(CATEGORY, READ_SLUG, 13);
    ReportAssertions.assertOnlyEdgesFrom(
        CATEGORY, READ_SLUG, List.of(StoryIdentities.CI, StoryTarget.SERVICE));

    // --- the refusal -------------------------------------------------------------------------------
    ReportAssertions.assertComplete(CATEGORY, REFUSED_SLUG, UserflowReport.PASSED);
    for (String step :
        List.of(
            "build-announced", "refusal-names-the-url", "nothing-applied", "refusal-is-readable")) {
      ReportAssertions.assertStepId(CATEGORY, REFUSED_SLUG, step);
    }
    intake(REFUSED_SLUG);
    peer(
        REFUSED_SLUG,
        StoryPeers.GIT_HOST,
        StoryPeers.specLabel(MISCONFIGURED, MISCONFIGURED_VERSION, 200));
    // No mint here, and the absence is the deployer's rather than the stand-in's: the token the
    // story above acquired is still cached, so this deployment presents it without asking for a new
    // one. See StoryPeers.
    // ONE arrow for a read that was attempted twice. Two attempts a second apart are the whole
    // budget, and they are the same request with the same answer — a diagram says which
    // dependencies exist, and the retry count belongs to the log.
    peer(
        REFUSED_SLUG,
        StoryPeers.CONFIGURATION,
        StoryPeers.resolvedLabel(MISCONFIGURED, StoryPeers.REFUSED_STATUS));
    for (String call : refusedCalls(MISCONFIGURED_SERVICE, MISCONFIGURED, MISCONFIGURED_VERSION)) {
      swarm(REFUSED_SLUG, call);
    }
    operatorRead(REFUSED_SLUG);
    ReportAssertions.assertDeclaredEdge(
        CATEGORY, REFUSED_SLUG, NetworkEdge.JDBC, StoryTarget.SERVICE, STORE, STORE_LABEL);
    // NINE, and the four orchestrator edges are all QUESTIONS. Nothing was created, so nothing has
    // to be undone — which is the whole reason the extras are read while the argv is assembled and
    // not after it has been run.
    ReportAssertions.assertEdgeCount(CATEGORY, REFUSED_SLUG, 9);
    ReportAssertions.assertOnlyEdgesFrom(
        CATEGORY,
        REFUSED_SLUG,
        List.of(StoryIdentities.CI, StoryIdentities.OPERATOR, StoryTarget.SERVICE));

    for (String slug : List.of(READ_SLUG, REFUSED_SLUG)) {
      for (String bearer : MINTED) {
        ReportAssertions.assertNotLeaked(CATEGORY, slug, bearer);
      }
    }
  }

  /**
   * The four questions a refused deployment gets as far as asking — and the list stops exactly where
   * {@code buildCreateArgv} throws. The seed twin is never even asked about: that happens after the
   * argv is built, which is what makes a refusal cost nothing.
   */
  private static List<String> refusedCalls(String service, String application, String sha) {
    return List.of(
        StorySwarm.label(
            "pull " + StorySwarm.image(StoryTarget.imageRef(application, sha)), "0"),
        StorySwarm.label("network inspect qits-net", "0"),
        StorySwarm.label("inspect self", "0"),
        StorySwarm.label("service inspect " + service, "1"));
  }

  private static void intake(String slug) {
    ReportAssertions.assertEdge(
        CATEGORY,
        slug,
        NetworkEdge.HTTP,
        StoryIdentities.CI,
        StoryTarget.SERVICE,
        "POST " + StoryTarget.SOFTWARE_RELEASED_PATH + " -> 202");
  }

  private static void operatorRead(String slug) {
    ReportAssertions.assertEdge(
        CATEGORY,
        slug,
        NetworkEdge.HTTP,
        StoryIdentities.OPERATOR,
        StoryTarget.SERVICE,
        "GET " + StoryTarget.DEPLOYMENTS_PATH + " -> 200");
  }

  private static void peer(String slug, String peer, String label) {
    ReportAssertions.assertEdge(
        CATEGORY, slug, NetworkEdge.HTTP, StoryTarget.SERVICE, peer, label);
  }

  private static void swarm(String slug, String label) {
    ReportAssertions.assertEdge(
        CATEGORY, slug, StorySwarm.KIND, StoryTarget.SERVICE, StorySwarm.ORCHESTRATOR, label);
  }
}
