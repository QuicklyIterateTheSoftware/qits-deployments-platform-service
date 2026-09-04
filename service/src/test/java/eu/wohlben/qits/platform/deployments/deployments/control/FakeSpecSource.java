package eu.wohlben.qits.platform.deployments.deployments.control;

import io.quarkus.test.Mock;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The suite's stand-in for the git host — {@code @Mock}, so no {@code @QuarkusTest} in this module
 * ever makes cd's one outbound HTTP call.
 *
 * <p><b>Its default answer is {@link DeploymentSpec#DEFAULTS}</b>, and that is the backward
 * compatibility contract in one line: a repository that declares nothing behaves exactly as every
 * repository did before the file existed. A test that wants a spec scripts one for its repository
 * by name.
 *
 * <p>Application-scoped and therefore shared: reset it in {@code @BeforeEach} and use distinct
 * repository ids per test. State is read through methods only — the injected reference is a CDI
 * client proxy.
 */
@Mock
@ApplicationScoped
public class FakeSpecSource implements SpecSource {

  /** The commit every scripted read reports the rev resolved to. One value: no test varies it. */
  public static final String RESOLVED_COMMIT = "0f1e2d3c4b5a69788796a5b4c3d2e1f009182736";

  private final Map<String, DeploymentSpec> specs = new ConcurrentHashMap<>();
  private final Map<String, String> failures = new ConcurrentHashMap<>();
  private final Map<String, String> revs = new ConcurrentHashMap<>();

  public void reset() {
    specs.clear();
    failures.clear();
    revs.clear();
  }

  /**
   * The rev this application's spec was last read at — {@code refs/tags/<version>} on every release
   * path. It is recorded rather than asserted here so a test can hold the claim that the file the
   * deployment ran on is the file the RELEASED TAG carries, which is the difference between
   * deploying a version and deploying whatever a branch happens to point at.
   */
  public String revOf(String applicationName) {
    return revs.get(applicationName);
  }

  /**
   * What this application declares, whatever rev is asked for.
   *
   * <p><b>Keyed by the APPLICATION NAME</b>, which is the repository's name when the announcement
   * carried one and its storage id when it did not — the same answer {@link
   * RepositoryRef#applicationName()} gives the deployment. A test that announces an id alone
   * scripts by that id, exactly as it always did; a test that announces the name pair scripts by
   * the name. Keying by the raw id instead would make a name-addressed test script a spec no read
   * could find.
   */
  public void script(String applicationName, DeploymentSpec spec) {
    specs.put(applicationName, spec);
  }

  /** Script the git host being unreachable, or the file being unreadable, for this application. */
  public void scriptFailure(String applicationName, String message) {
    failures.put(applicationName, message);
  }

  @Override
  public SpecRead read(RepositoryRef repository, String rev) {
    revs.put(repository.applicationName(), rev);
    String failure = failures.get(repository.applicationName());
    if (failure != null) {
      throw new SpecException(failure);
    }
    return new SpecRead(
        specs.getOrDefault(repository.applicationName(), DeploymentSpec.DEFAULTS),
        RESOLVED_COMMIT);
  }
}
