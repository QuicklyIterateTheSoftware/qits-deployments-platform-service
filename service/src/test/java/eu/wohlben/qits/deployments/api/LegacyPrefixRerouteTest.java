package eu.wohlben.qits.deployments.api;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.Test;

/**
 * The retired {@code /platform-deployments} prefix is answered by rewriting it onto {@code
 * /deployments}, and the rewrite reaches both surfaces under the segment.
 *
 * <p><b>This is the cheap half of the claim; {@link PdPackagedSurfaceIT} is the expensive one.</b>
 * Quinoa is disabled in test mode, so nothing here can say what a path matching no route is answered
 * with — that needs a packaged process with a client in it, and it is asserted there. What a
 * {@code @QuarkusTest} can say is the part a refactor is likeliest to break: that a legacy path
 * reaches the resource behind the live one, with the identity intact across the restart, and that
 * the reroute rewrites the PREFIX and nothing else.
 *
 * <p>No {@code @TestProfile}: this asserts the shipped configuration, and a profile here would be a
 * second Quarkus application for a filter that needs no configuring.
 *
 * <p><b>It is deleted with the filter.</b> The reroute lives for one release — see {@link
 * LegacyPrefixReroute} — and these tests are part of what goes with it.
 */
@QuarkusTest
class LegacyPrefixRerouteTest {

  private static final String LEGACY = "/platform-deployments/api";

  private static final String CURRENT = "/deployments/api";

  @Test
  void aLegacyPathReachesTheResourceBehindTheLiveOne() {
    // The applications listing: a read with no required filter, so the answer is the resource's own
    // rather than a 400 from validation that would also be a "the route was found" signal.
    String legacy =
        given()
            .header("X-Qits-User", "legacy-prefix-reroute")
            .header("X-Qits-Roles", "qits:admin")
            .when()
            .get(LEGACY + "/applications")
            .then()
            .statusCode(200)
            .contentType(ContentType.JSON)
            .extract()
            .asString();

    String current =
        given()
            .header("X-Qits-User", "legacy-prefix-reroute")
            .header("X-Qits-Roles", "qits:admin")
            .when()
            .get(CURRENT + "/applications")
            .then()
            .statusCode(200)
            .contentType(ContentType.JSON)
            .extract()
            .asString();

    // The same document, because it is the same resource: the rewrite happens before anything routes
    // and nothing downstream can tell which prefix asked.
    org.junit.jupiter.api.Assertions.assertEquals(current, legacy);
  }

  /**
   * The identity survives the reroute. Vert.x' {@code reroute} restarts the router, so every
   * handler that already ran — the authentication one included — runs again against the new path;
   * a rewrite that lost the forwarded pair would turn every legacy call into a 401 while the route
   * itself was perfectly reachable.
   */
  @Test
  void aLegacyPathIsStillJudgedByTheRoleItsDoorNames() {
    given()
        .header("X-Qits-User", "legacy-prefix-reroute-agent")
        .header("X-Qits-Roles", "qits:agent")
        .contentType(ContentType.JSON)
        .body("{\"replicas\":0}")
        .when()
        .post(LEGACY + "/applications/dev:nothing/scale")
        .then()
        // The operator's lever is qits:admin's; an agent is refused on the legacy prefix exactly as
        // it is on the live one, and never allowed through because the path was rewritten.
        .statusCode(403);
  }

  /**
   * Only a LEADING whole segment is rewritten. A filter matching the substring would rewrite the
   * middle of somebody's identifier — a tier or a service could legitimately be called
   * {@code platform-deployments} — and a filter matching a prefix of characters rather than of
   * segments would claim {@code /platform-deployments-other} as well.
   *
   * <p>Asserted on the decision function rather than over HTTP, because what is being pinned is
   * exactly that function: the routes those paths would reach do not exist, so a request would
   * answer 404 whether the rewrite fired or not.
   */
  @Test
  void onlyALeadingLegacySegmentIsRewritten() {
    assertTrue(LegacyPrefixReroute.matches("/platform-deployments"), "the bare segment matches");
    assertTrue(LegacyPrefixReroute.matches("/platform-deployments/api/pins"), "a path under it");
    assertFalse(
        LegacyPrefixReroute.matches("/deployments/api/platform-deployments"),
        "the word inside a path is not a prefix");
    assertFalse(
        LegacyPrefixReroute.matches("/platform-deployments-other/api"),
        "a longer segment starting with the same characters is a different segment");
    assertFalse(LegacyPrefixReroute.matches(null), "no path is no match");
  }
}
