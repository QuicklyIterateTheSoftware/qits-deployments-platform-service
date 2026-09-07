package eu.wohlben.qits.platform.deployments.deployments.control;

import eu.wohlben.qits.platform.deployments.environments.entity.PdDeploymentTarget;
import io.quarkus.test.Mock;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The suite's stand-in for qits-configuration's declaration intake — the FOURTH {@code @Mock},
 * joining {@code FakeDeploymentDriver}, {@code FakeSpecSource} and {@code FakeResourceProvisioner},
 * so no {@code @QuarkusTest} here POSTs a declaration at anybody.
 *
 * <p><b>It is a {@code @Mock} bean where {@code DeploymentExtrasSource}'s double is a lambda, and
 * the difference is the seam's own shape.</b> That one returns a value; this one holds a
 * conversation — a body goes out, a decision comes back, and the two decisions end a deployment
 * differently. What a test wants of it is therefore both directions: script the answer, then read
 * back what was sent. A lambda gives neither half.
 *
 * <p><b>What it records is the tuple, not just the bytes.</b> A seed is addressed by (application,
 * version) and carries a plane and a body, and each of the four is a claim some test makes: that a
 * multi-tier fan-out seeds ONCE rather than per row, that the version is the released one, that the
 * plane is the spec's own, and that the yaml is byte-identical to what the git host served.
 *
 * <p>Application-scoped and therefore shared: reset it in {@code @BeforeEach} and use distinct
 * application names per test. State is read through methods only — the injected reference is a CDI
 * client proxy, and a field read on a proxy sees the proxy's fields.
 */
@Mock
@ApplicationScoped
public class FakeDeclarationSeed implements DeclarationSeed {

  /** One accepted seed, whole — see the class javadoc for why all four values are kept. */
  public record Seeded(
      String applicationName, String version, PdDeploymentTarget target, String yaml) {}

  private final List<Seeded> seeded = new ArrayList<>();

  /** Applications the store refuses, and with which of the two verdicts. */
  private final Map<String, DeclarationRefused> refusals = new ConcurrentHashMap<>();

  public synchronized void reset() {
    seeded.clear();
    refusals.clear();
  }

  /** Every seed this store was handed, in order. */
  public synchronized List<Seeded> seeded() {
    return List.copyOf(seeded);
  }

  /** The store READ this application's declaration and refused it — a broken file at the tag. */
  public void refuseBroken(String applicationName, String message) {
    refusals.put(
        applicationName,
        new DeclarationRefused(DeclarationRefused.Kind.DECLARATION_BROKEN, message));
  }

  /** The store never answered — the file was never judged, and the deployment is refused anyway. */
  public void refuseUnavailable(String applicationName, String message) {
    refusals.put(
        applicationName,
        new DeclarationRefused(DeclarationRefused.Kind.SERVICE_UNAVAILABLE, message));
  }

  @Override
  public void seed(
      String applicationName, String version, PdDeploymentTarget target, String rawYaml) {
    DeclarationRefused refusal = refusals.get(applicationName);
    if (refusal != null) {
      // Thrown fresh rather than re-thrown, so a scripted refusal reads the same on the second
      // deployment of a test as on the first — a shared exception instance would accumulate a
      // stack trace from whichever worker threw it first.
      throw new DeclarationRefused(refusal.kind(), refusal.getMessage());
    }
    synchronized (this) {
      seeded.add(new Seeded(applicationName, version, target, rawYaml));
    }
  }
}
