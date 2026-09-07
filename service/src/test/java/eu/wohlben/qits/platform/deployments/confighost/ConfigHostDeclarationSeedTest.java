package eu.wohlben.qits.platform.deployments.confighost;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.platform.deployments.deployments.control.DeclarationRefused;
import eu.wohlben.qits.platform.deployments.environments.entity.PdDeploymentTarget;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The other direction of the same peer: a released repository's declaration going INTO
 * qits-configuration, before the deployment that carries it is scheduled.
 *
 * <p>An HTTP stub on a real socket rather than a fake at the seam, {@code ConfigHostExtrasSourceTest}'s
 * arrangement and its reason: what is under test IS the request — the address it is built at, the
 * headers and the credential it carries, the bytes it sends, and which answers it spends patience
 * on. A fake at {@code DeclarationSeed} would assert this suite's own model of a client.
 *
 * <p><b>The claim underneath all of it: a deployment never runs against configuration that was not
 * seeded.</b> Every arm here that could quietly return and let the deployment proceed is a test,
 * because a container live against the previous version's declaration is exactly the invisible
 * failure the whole line of work exists to end — and the one exception, an unset url, is a platform
 * that has no store at all.
 */
class ConfigHostDeclarationSeedTest {

  private static final ExtrasBearer NONE = Optional::empty;

  private static final String APPLICATION = "qits-ci";

  private static final String VERSION = "2026.903.113443";

  /** The file as the git host served it — bytes, not a document this component understands. */
  private static final String DECLARATION =
      """
      defaults:
        QITS_FEATURE_FLAGS: trace-headers
      """;

  private ExtrasStub stub;

  @BeforeEach
  void start() {
    stub = new ExtrasStub();
  }

  @AfterEach
  void stop() {
    stub.close();
  }

  @Test
  void theDeclarationIsPostedAtTheApplicationsOwnVersionedAddress() {
    stub.answers(201, "{}");

    stub.seed(NONE).seed(APPLICATION, VERSION, PdDeploymentTarget.ENVIRONMENT, DECLARATION);

    assertEquals(List.of("POST"), stub.methods(), "a seed is a write");
    assertEquals(
        List.of(
            "/configuration/api/applications/"
                + APPLICATION
                + "/declarations/"
                + VERSION
                + "?deploymentTarget=environment"),
        stub.targets(),
        "the declaration is stored under the version it was released at");
  }

  @Test
  void thePlaneRidesAlongBecauseAPlatformDeclarationResolvesAgainstOtherOverrides() {
    stub.answers(201, "{}");

    stub.seed(NONE).seed(APPLICATION, VERSION, PdDeploymentTarget.PLATFORM, DECLARATION);

    assertTrue(
        stub.targets().get(0).endsWith("?deploymentTarget=platform"), stub.targets().toString());
  }

  @Test
  void theBodyIsTheFileVerbatimAndIsSentAsYaml() {
    // Nothing here parses it, so nothing here may reshape it: the store owns the grammar, and a
    // difference between what a repository wrote and what the platform holds would be introduced
    // by exactly this hop or by nothing.
    stub.answers(201, "{}");

    stub.seed(NONE).seed(APPLICATION, VERSION, PdDeploymentTarget.ENVIRONMENT, DECLARATION);

    assertEquals(List.of(DECLARATION), stub.bodies());
    assertEquals(List.of("application/yaml"), stub.contentTypes());
  }

  @Test
  void anIdempotentNoOpIsTheSameAnswerAsACreate() {
    // 201 is created and 200 is "this hash is already stored" — the same event announced twice, or
    // a release re-driven through the manual door. To this component they are one answer: the store
    // has this version's declaration, so the deployment proceeds.
    stub.answers(200, "{\"created\":false}");

    stub.seed(NONE).seed(APPLICATION, VERSION, PdDeploymentTarget.ENVIRONMENT, DECLARATION);

    assertEquals(1, stub.methods().size(), "no retry, no refusal");
  }

  @Test
  void aFileTheStoreCannotParseIsTheRepositorysProblemAndIsNeverRetried() {
    // 422: it was READ and refused. A second POST of the same bytes answers the same thing, so
    // spending the budget on it would only make a broken release slower to say so.
    stub.answers(422, "line 3: mapping values are not allowed here");
    ConfigHostDeclarationSeed seed = stub.seed(NONE);
    seed.attempts = 3;

    DeclarationRefused refused =
        assertThrows(
            DeclarationRefused.class,
            () -> seed.seed(APPLICATION, VERSION, PdDeploymentTarget.ENVIRONMENT, DECLARATION));

    assertEquals(DeclarationRefused.Kind.DECLARATION_BROKEN, refused.kind());
    assertTrue(refused.getMessage().contains(APPLICATION + "@" + VERSION), refused.getMessage());
    assertTrue(refused.getMessage().contains("mapping values"), refused.getMessage());
    assertTrue(
        refused.getMessage().contains("cut a new release"),
        "a broken file sends a person to the repository: " + refused.getMessage());
    assertEquals(1, stub.methods().size(), "a read-and-refused file is not patience material");
  }

  @Test
  void aDifferentFileUnderAVersionAlreadyStoredIsTheSameKindOfRefusal() {
    // 409: the store holds this version already and its content hash differs. That is a statement
    // about the released tag — a tag that was moved, or two releases claiming one version — and it
    // is the repository's to answer, so it takes the same word and the same no-retry stance.
    stub.answers(409, "2026.903.113443 is already stored with different content");
    ConfigHostDeclarationSeed seed = stub.seed(NONE);
    seed.attempts = 3;

    DeclarationRefused refused =
        assertThrows(
            DeclarationRefused.class,
            () -> seed.seed(APPLICATION, VERSION, PdDeploymentTarget.ENVIRONMENT, DECLARATION));

    assertEquals(DeclarationRefused.Kind.DECLARATION_BROKEN, refused.kind());
    assertEquals(1, stub.methods().size());
  }

  @Test
  void aStoreBeingRedeployedIsWaitedOutWithinTheBudget() {
    // The whole reason there is a budget at all: qits-configuration cutting over is a few seconds
    // of 503s, and no deployment should die of one.
    stub.then(503, "restarting").answers(201, "{}");
    ConfigHostDeclarationSeed seed = stub.seed(NONE);
    seed.attempts = 2;

    seed.seed(APPLICATION, VERSION, PdDeploymentTarget.ENVIRONMENT, DECLARATION);

    assertEquals(2, stub.methods().size(), "the second attempt is the one that was accepted");
  }

  @Test
  void anOutageThatOutlastsTheBudgetRefusesTheDeploymentNamingTheUrl() {
    // Bounded, and then loud. An unbounded wait here is a wait on pd-deploy-worker, which is
    // single-threaded with every other event of the platform queued behind it.
    stub.answers(503, "still restarting");
    ConfigHostDeclarationSeed seed = stub.seed(NONE);
    seed.attempts = 3;

    DeclarationRefused refused =
        assertThrows(
            DeclarationRefused.class,
            () -> seed.seed(APPLICATION, VERSION, PdDeploymentTarget.ENVIRONMENT, DECLARATION));

    assertEquals(DeclarationRefused.Kind.SERVICE_UNAVAILABLE, refused.kind());
    assertTrue(refused.getMessage().contains(stub.url()), refused.getMessage());
    assertTrue(refused.getMessage().contains("after 3 attempts"), refused.getMessage());
    assertEquals(3, stub.methods().size(), "spent, not unbounded");
  }

  @Test
  void aStoreThatDoesNotAnswerAtAllRefusesTheDeploymentToo() {
    // No fall-back, and the absence is the feature: deploying anyway would put a container live
    // against whatever declaration the store happened to hold for an earlier version.
    String unreachable = "http://127.0.0.1:1";
    ConfigHostDeclarationSeed seed = ExtrasStub.seed(NONE, unreachable);

    DeclarationRefused refused =
        assertThrows(
            DeclarationRefused.class,
            () -> seed.seed(APPLICATION, VERSION, PdDeploymentTarget.ENVIRONMENT, DECLARATION));

    assertEquals(DeclarationRefused.Kind.SERVICE_UNAVAILABLE, refused.kind());
    assertTrue(refused.getMessage().contains(unreachable), refused.getMessage());
  }

  @Test
  void aMisDeployedStoreIsUnavailableRatherThanABrokenFileAndIsNotRetried() {
    // A 404 on a path this component built is a route that is not there, and it will not be there
    // in a second either. It is deliberately NOT DECLARATION_BROKEN: nothing read the file, so
    // pointing a person at the repository would send them to the wrong one.
    stub.answers(404, "no such route");
    ConfigHostDeclarationSeed seed = stub.seed(NONE);
    seed.attempts = 3;

    DeclarationRefused refused =
        assertThrows(
            DeclarationRefused.class,
            () -> seed.seed(APPLICATION, VERSION, PdDeploymentTarget.ENVIRONMENT, DECLARATION));

    assertEquals(DeclarationRefused.Kind.SERVICE_UNAVAILABLE, refused.kind());
    assertEquals(1, stub.methods().size(), "a wrong route is not patience material");
  }

  @Test
  void noExtrasUrlSeedsNothingAndRefusesNothing() {
    // The shipped state, and it has to be a no-op: unset means the extras are the config volume's
    // file, so there is no store to seed. A refusal here would be every file-mode platform failing
    // every deployment for the absence of something it was never configured to have.
    ConfigHostDeclarationSeed seed = ExtrasStub.seed(NONE, null);

    seed.seed(APPLICATION, VERSION, PdDeploymentTarget.ENVIRONMENT, DECLARATION);

    assertTrue(stub.methods().isEmpty(), "an unset url must reach nothing");
  }

  @Test
  void theBearerIsPresentedWhenTheClientHoldsOne() {
    // The read's credential, on the write. One peer, one named oidc client, one token — a second
    // credential here would be a second peer, and the peer count is deliberately one.
    stub.answers(201, "{}");

    stub.seed(() -> Optional.of("a-machine-token"))
        .seed(APPLICATION, VERSION, PdDeploymentTarget.ENVIRONMENT, DECLARATION);

    assertEquals(List.of("Bearer a-machine-token"), stub.authorizations());
  }

  @Test
  void nothingIsPresentedWhenTheClientIsDisabled() {
    // The shipped posture: qits-configuration behind forward-auth on qits-net, and this write
    // carrying the X-Qits-* pair alone. An Authorization header invented here would be a credential
    // nobody minted.
    stub.answers(201, "{}");

    stub.seed(NONE).seed(APPLICATION, VERSION, PdDeploymentTarget.ENVIRONMENT, DECLARATION);

    assertEquals(1, stub.authorizations().size());
    assertNull(stub.authorizations().get(0));
  }

  @Test
  void anApplicationNameOrVersionThatIsNotOneIsRefusedRatherThanEscaped() {
    // The belt at the line before the URL, the resolved read's exactly: a value that would need
    // escaping is a value the rest of this component could not have stored.
    ConfigHostDeclarationSeed seed = stub.seed(NONE);

    assertThrows(
        DeclarationRefused.class,
        () -> seed.seed("qits ci", VERSION, PdDeploymentTarget.ENVIRONMENT, DECLARATION));
    assertThrows(
        DeclarationRefused.class,
        () -> seed.seed(APPLICATION, "../2026", PdDeploymentTarget.ENVIRONMENT, DECLARATION));
    assertTrue(stub.methods().isEmpty(), "a refused address must reach nothing");
  }

  @Test
  void aRefusalsBodyReachesTheRowBoundedRatherThanWhole() {
    // The message lands in a deployment row's detail, which is a text column a person reads in a
    // listing. A store answering an HTML error page must cost that listing a sentence, not a page.
    stub.answers(422, "x".repeat(5000));
    ConfigHostDeclarationSeed seed = stub.seed(NONE);

    DeclarationRefused refused =
        assertThrows(
            DeclarationRefused.class,
            () -> seed.seed(APPLICATION, VERSION, PdDeploymentTarget.ENVIRONMENT, DECLARATION));

    assertTrue(
        refused.getMessage().length() < ConfigHostDeclarationSeed.EXCERPT_LIMIT + 400,
        "the excerpt is unbounded: " + refused.getMessage().length() + " characters");
  }
}
