package eu.wohlben.qits.platform.deployments.bus;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

/**
 * The readiness document names {@code eventstream-catchup}, which is the whole of what this
 * component gained from qits-eventstream 2026.908.70503.
 *
 * <p><b>It is asserted because its absence is silent, and silence is the defect it answers.</b> On
 * 2026-09-08 this service's catch-up sweep parked in a PostgreSQL socket read at 00:11 and consumed
 * nothing until 06:30 — with every health check green the whole time, because every check was
 * answering a different question (is the HTTP server up, is the datasource reachable) and none was
 * answering this one. The check arrives by nothing more than the jar being on the classpath: it is
 * an {@code @Readiness} bean in a library, discovered by the smallrye-health extension this module
 * already carries, wired by no line of ours. That is exactly the kind of thing a version bump can
 * take away again without a single compilation failing, so the claim is written down here rather
 * than believed.
 *
 * <p><b>The path is this component's own health gate's.</b> {@code
 * /platform-deployments/q/health/ready} is what a peer's {@code --health-cmd} curls and what the
 * health-path convention derives for this service's own name, so a check that goes DOWN here takes
 * this instance out of rotation — which is the honest statement a stalled consumer can make, and
 * the reason the library ships {@code @Readiness} and deliberately not {@code @Liveness}.
 *
 * <p><b>UP is the right answer in the suite, and it is not a weak assertion.</b> The bus is dark in
 * {@code %test} ({@link PdEventstreamDarknessTest}), and the check's own first rule is that nothing
 * to do is UP — a library must never make a consumer red over a feature it does not use. So what is
 * pinned here is the pair: the check is PRESENT, and a dark deployable is still ready. A build in
 * which the check went DOWN on an application that consumes nothing would fail every deployment on
 * the platform, and it would fail them at the health gate, minutes after the release looked fine.
 */
@QuarkusTest
public class PdCatchupReadinessTest {

  @Test
  public void theReadinessDocumentNamesTheCatchupCheck() {
    given()
        .when()
        .get("/platform-deployments/q/health/ready")
        .then()
        .statusCode(200)
        .body("status", equalTo("UP"))
        .body("checks.name", hasItem("eventstream-catchup"));
  }
}
