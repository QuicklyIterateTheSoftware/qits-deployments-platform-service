package eu.wohlben.qits.platform.deployments.api;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.servicemock.idp.MockIdp;
import eu.wohlben.qits.userflows.Interactions;
import eu.wohlben.qits.userflows.UserStory;
import eu.wohlben.qits.userflows.UserStoryDescription;
import eu.wohlben.qits.userflows.report.ReportAssertions;
import eu.wohlben.qits.userflows.report.UserflowReport;
import io.quarkus.test.junit.QuarkusIntegrationTest;
import io.quarkus.test.junit.TestProfile;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;

/**
 * The whole service as it is <b>packaged</b> — like {@link PdPackagedSurfaceIT} beside it, but with
 * the OIDC tenant <b>on</b>, which no {@code @QuarkusTest} here can prove. The shipped tenant is
 * {@code quarkus.oidc.tenant-enabled=${qits.auth.machine.required:false}} and every suite in this
 * repository leaves that gate shut but one — and that one, {@link MachineGuardEnforcedTest}, opens
 * it by <b>inlining the verification key</b> and clearing {@code auth-server-url}, precisely so it
 * needs no idp. So the half of the shipped {@code quarkus.oidc.*} block that is about REACHING
 * qits-platform-idp — the auth-server-url, {@code discovery-enabled=false} with {@code
 * jwks-path=jwks} joined onto it, the boot-time fetch that {@code connection-delay} retries — is
 * exercised nowhere else at all. The far side here is {@link MockIdp}, whose recordings make the
 * interaction assertable on <b>both ends</b>.
 *
 * <p>It is also this repo's first <b>userflow</b>: the proof doubles as documentation, emitted
 * under {@code target/userstories/} with the interactions drawn as a sequence diagram. The story is
 * browserless (no {@code Flow} parameter), so no Chromium is involved anywhere.
 *
 * <p><b>The route both stories drive is {@code GET /platform-deployments/api/pins}</b>, and it is
 * chosen because it is the one guarded read here whose caller is a MACHINE: {@link PdPinController}
 * takes {@code qits-platform:system} rather than the {@code qits-platform:admin} the environment
 * and deployment listings take, its one caller is qits-platform-artifacts' OCI garbage collector,
 * and it reads nothing but deployment rows — so what it answers is a fact about this instance's
 * own history and never about a peer this IT would then have to stand in for. A write would have
 * been the other candidate and is worse on every count: it has docker side effects.
 *
 * <p><b>ITs are skipped by default here and this one does NOT flip that</b>, unlike qits-githost's
 * namesake. {@code skipITs} is {@code true} in the root pom because {@link PdPackagedSurfaceIT} is
 * this module's other integration test and half of it is about the CLIENT — the base href, the deep
 * links, the fallback that must not swallow a machine path — which the userflow pipeline
 * deliberately does not build ({@code -Dquarkus.quinoa=false}, since the qits-spa-deployments
 * submodule arrives empty in a step container). A blanket {@code -DskipITs=false} would make that
 * run red on a test that is right. {@code .config/qits/ci-event-userflows.yml} names this class
 * instead ({@code -DskipITs=false "-Dit.test=TokenValidationBootstrapIT"}), which is also what
 * keeps the userflow pipeline about these stories and nothing else.
 */
@QuarkusIntegrationTest
@TestProfile(TokenValidationBootstrapIT.PackagedWithMockIdp.class)
public class TokenValidationBootstrapIT {

  static final String CATEGORY = "authentication";
  static final String ACCEPTED_SLUG = "on-start-the-deployer-fetches-the-platform-s-signing-keys";
  static final String DENIED_SLUG = "a-stranger-s-token-never-opens-the-deployment-pin-ledger";

  private static final String PINS = "/platform-deployments/api/pins";

  /**
   * {@link PdPackagedSurfaceIT.PackagedUnderTarget} — the two {@code QITS_RESOURCE_*} triples on
   * this JVM's embedded postgres and the deliberately absent container runtime, parked in system
   * properties because a test profile is instantiated in more than one classloader — <b>plus the
   * two things these stories are about</b>: the gate that turns the shipped OIDC tenant on, and
   * where the idp is.
   *
   * <p>Extending rather than copying is deliberate. What a launched qits-platform-deployments needs
   * in order to boot at all is one answer, it is written out at length over there (both triples are
   * mandatory: neither jar's expressions have a default behind them), and a second copy of the
   * parking trick would be a second place for it to drift. What is added here is only the seams
   * these stories move.
   *
   * <p>The mock idp starts <b>before</b> the application, via {@link MockIdp#ensureStarted()},
   * which parks its coordinates (and its keypair) in system properties for the same classloader
   * reason — that is also how a story method's {@link MockIdp#attach()} reaches the very server the
   * launched process fetched its keys from.
   *
   * <p>Every key below is a RUNTIME key. A packaged process takes its configuration as {@code -D}
   * arguments on a jar that was already built, so a build-time key here would be silently ignored
   * and these tests would prove the opposite of what they say.
   */
  public static class PackagedWithMockIdp extends PdPackagedSurfaceIT.PackagedUnderTarget {

    /**
     * The audience this service enforces, and it is a LITERAL rather than a variable name — the
     * difference from qits-githost's IT, which hands its launched process {@code
     * QITS_AUTH_MACHINE_AUDIENCE} because the shipped expression there reads that variable. Here
     * {@code qits.auth.machine.audience=qits-platform-deployments} is spelled out in
     * {@code application.properties} and {@code quarkus.oidc.token.audience} references it, so the
     * audience under test is the shipped one and there is no expression to feed. A deployment still
     * overrides it by environment — prod sends {@code prod-qits-deployments}.
     */
    static final String AUDIENCE = "qits-platform-deployments";

    @Override
    public Map<String, String> getConfigOverrides() {
      MockIdp idp = MockIdp.ensureStarted();
      Map<String, String> overrides = new LinkedHashMap<>(super.getConfigOverrides());
      // THE GATE, and turning it on is the point: the shipped tenant is
      // quarkus.oidc.tenant-enabled=${qits.auth.machine.required:false}, so this one key is the
      // difference between a service that validates machine bearers and one that does not. The
      // application.properties block says what flipping it implies for startup — with it on there
      // IS a tenant, and the tenant fetches a JWKS at boot — and this is where that is proved
      // rather than described.
      overrides.put("qits.auth.machine.required", "true");
      // The one seam these stories move: where the idp is. Runtime key, so the packaged artifact is
      // otherwise exactly what ships — discovery stays off and jwks-path stays `jwks`, joined onto
      // this URL.
      overrides.put("quarkus.oidc.auth-server-url", idp.baseUrl());
      // Dark outside a deployment, like %dev/%test — both are runtime keys, and both are shipped
      // ENABLED because a library that shipped dark would be one whose first deployment discovers
      // it was never wired up. A step container has no qits-observability and no qits-events, and
      // an exporter retrying against an unresolvable host would bury the story's own log. The
      // eventstream DATASOURCE is still opened and migrated — dark stops dialling, sweeping and
      // claiming, never the datasource — which is why the inherited second triple is not optional.
      overrides.put("quarkus.otel.sdk.disabled", "true");
      overrides.put("qits.eventstream.enabled", "false");
      return overrides;
    }
  }

  @UserStory(
      value = "On start, the deployer fetches the platform's signing keys",
      category = "authentication")
  @UserStoryDescription(
      """
      A freshly deployed qits-platform-deployments must validate service bearers before any
      caller arrives: at startup it fetches the signing keys (JWKS) from qits-platform-idp —
      discovery stays off, the path is configured — so the very first machine request is
      accepted. qits-platform-artifacts' image collector reads the rollback pins with exactly
      this credential before it deletes anything.
      """)
  void serviceBootFetchesJwksAndAcceptsPlatformTokens(Interactions story) {
    MockIdp idp = MockIdp.attach();

    story.note(
        "qits-platform-deployments starts with the OIDC tenant on, beside a reachable"
            + " qits-platform-idp");
    given().get("/platform-deployments/q/health/ready").then().statusCode(200);

    // End (a), the idp side: the JWKS was served during startup — before this story presented any
    // token at all. That is the claim the inlined-key suite cannot make, because it clears
    // auth-server-url precisely so that nothing is ever fetched.
    assertTrue(
        idp.recordedRequests().stream().anyMatch(r -> "/idp/jwks".equals(r.path())),
        "the packaged service never fetched /idp/jwks at startup");
    story
        .happened("qits-platform-deployments", "qits-platform-idp", "GET /idp/jwks (at startup)")
        .as("jwks-fetched");

    // End (b), this service's side: those keys are what token validation now runs on. A platform
    // peer's bearer (aud = this service, roles in `groups`) opens the pin ledger — the read
    // qits-platform-artifacts plans its OCI sweep on, fail-closed, so an unreachable or refused
    // answer aborts the plan with nothing deleted.
    String collectorToken =
        idp.token()
            .subject("qits-platform-artifacts")
            .audience(PackagedWithMockIdp.AUDIENCE)
            .groups("qits-platform:system")
            .mint();
    given()
        .header("Authorization", "Bearer " + collectorToken)
        .get(PINS)
        .then()
        .statusCode(200)
        .body("pins", notNullValue());
    story
        .happened(
            "qits-platform-artifacts",
            "qits-platform-deployments",
            "GET /platform-deployments/api/pins (Bearer, groups=[qits-platform:system])")
        .as("pins-served");
  }

  @UserStory(
      value = "A stranger's token never opens the deployment pin ledger",
      category = "authentication")
  @UserStoryDescription(
      """
      The flip side of trusting the platform's keys. A token signed by a key the published JWKS
      never carried, or minted for another service's audience, is refused at the door — however
      well-formed it looks: both are 401 and not 403, because the credential never became an
      identity and there is no caller to have been forbidden. A token addressed here and signed
      correctly but carrying only a person's role gets the other answer, 403 — it authenticated
      and covers nothing. The pin ledger is a machine peer's read, and no browser session has
      business in it.
      """)
  void aStrangersTokenIsRefused(Interactions story) {
    MockIdp idp = MockIdp.attach();

    String strangersToken =
        idp.token()
            .audience(PackagedWithMockIdp.AUDIENCE)
            .groups("qits-platform:system")
            .signedByUnknownKey()
            .mint();
    given().header("Authorization", "Bearer " + strangersToken).get(PINS).then().statusCode(401);
    story
        .happened(
            "an impostor",
            "qits-platform-deployments",
            "GET /platform-deployments/api/pins (token signed by an unknown key) -> 401")
        .as("unknown-key-refused");

    String wrongAudienceToken =
        idp.token().audience("some-other-service").groups("qits-platform:system").mint();
    given()
        .header("Authorization", "Bearer " + wrongAudienceToken)
        .get(PINS)
        .then()
        .statusCode(401);
    story
        .happened(
            "an impostor",
            "qits-platform-deployments",
            "GET /platform-deployments/api/pins (another service's audience) -> 401")
        .as("wrong-audience-refused");

    // The third door, and the one that is this service's own shape rather than the fleet's: the
    // roles say who a caller is meant to be, and qits-platform:admin is the PERSON's — it reaches
    // this service only as a forwarded X-Qits-Roles header from an admin session, never in a
    // token. Minted into one anyway it authenticates perfectly and still covers nothing here.
    String adminRoleToken =
        idp.token()
            .subject("an-admin-session")
            .audience(PackagedWithMockIdp.AUDIENCE)
            .groups("qits-platform:admin")
            .mint();
    given().header("Authorization", "Bearer " + adminRoleToken).get(PINS).then().statusCode(403);
    story
        .happened(
            "a person's session",
            "qits-platform-deployments",
            "GET /platform-deployments/api/pins (Bearer, groups=[qits-platform:admin]) -> 403")
        .as("admin-role-refused");
  }

  @AfterAll
  static void bothStoryReportsAreComplete() {
    // The extension emits each report in its afterEach, so both are on disk before @AfterAll runs.
    ReportAssertions.assertComplete(CATEGORY, ACCEPTED_SLUG, UserflowReport.PASSED);
    ReportAssertions.assertInteraction(
        CATEGORY,
        ACCEPTED_SLUG,
        "qits-platform-deployments",
        "qits-platform-idp",
        "GET /idp/jwks (at startup)");
    ReportAssertions.assertStepId(CATEGORY, ACCEPTED_SLUG, "jwks-fetched");
    ReportAssertions.assertStepId(CATEGORY, ACCEPTED_SLUG, "pins-served");

    ReportAssertions.assertComplete(CATEGORY, DENIED_SLUG, UserflowReport.PASSED);
    ReportAssertions.assertStepId(CATEGORY, DENIED_SLUG, "unknown-key-refused");
    ReportAssertions.assertStepId(CATEGORY, DENIED_SLUG, "wrong-audience-refused");
    ReportAssertions.assertStepId(CATEGORY, DENIED_SLUG, "admin-role-refused");
  }
}
