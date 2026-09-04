package eu.wohlben.qits.platform.deployments.githost;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import eu.wohlben.qits.platform.deployments.deployments.control.RepositoryRef;
import eu.wohlben.qits.platform.deployments.deployments.control.SpecException;
import eu.wohlben.qits.platform.deployments.deployments.control.SpecSource;
import eu.wohlben.qits.platform.deployments.deployments.control.SpecSource.DeploymentSpec;
import eu.wohlben.qits.platform.deployments.environments.entity.PdDeploymentTarget;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Which URL the spec is read at — the one thing the identity rollback changed about this component's
 * single outbound call.
 *
 * <p>An HTTP stub on a real socket rather than a fake at the seam, for the reason {@code ExtrasStub}
 * gives next door: what is under test IS the request, and a fake would assert this test's own model
 * of a client. The JDK's own server, so nothing arrives on the classpath and no docker is involved.
 *
 * <p>Plain JUnit and no {@code @QuarkusTest}: the two config values are package-private fields, so
 * the class is built and pointed at the stub directly.
 */
public class GitHostSpecSourceTest {

  private static final String SHA = "a".repeat(40);
  private static final String UUID_ID = "6d0c2b1e-3a44-4b0e-9a5b-2b1c0d9e4f88";

  /** What a release deploys, and what the spec has to be read at. */
  private static final String VERSION = "2026.903.113443";

  private HttpServer server;

  /** RAW paths: the encoding of the rev segment is exactly what these tests are about. */
  private final List<String> paths = new ArrayList<>();
  private volatile int status = 200;
  private volatile String body = "deployment_target: platform\n";
  private volatile String commitSha = SHA;

  @BeforeEach
  void start() throws IOException {
    paths.clear();
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/", this::handle);
    server.start();
  }

  @AfterEach
  void stop() {
    server.stop(0);
  }

  private void handle(HttpExchange exchange) throws IOException {
    synchronized (paths) {
      paths.add(exchange.getRequestURI().getRawPath());
    }
    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
    if (status != 404 && commitSha != null) {
      // What the git host answers every blob read with: the commit the rev resolved to.
      exchange.getResponseHeaders().add(GitHostSpecSource.COMMIT_SHA_HEADER, commitSha);
    }
    exchange.sendResponseHeaders(status, status == 404 ? -1 : bytes.length);
    if (status != 404) {
      try (OutputStream out = exchange.getResponseBody()) {
        out.write(bytes);
      }
    }
  }

  @Test
  public void anEventCarryingTheNamePairIsReadNameAddressed() {
    // The public address. The storage id is not in the URL at all — githost serves blob and tree
    // under (projectId, repoName) since it became dumb storage, and the id route is internal.
    DeploymentSpec spec =
        source().read(new RepositoryRef(UUID_ID, "qits", "qits-gateway"), SHA).spec();

    assertEquals(PdDeploymentTarget.PLATFORM, spec.target());
    assertEquals(
        "/git/qits/qits-gateway/blob/" + SHA + "/.config/qits/deployments.yml", onlyPath());
  }

  @Test
  public void anEventWithNoNamesKeepsTheIdAddressedUrlItAlwaysUsed() {
    // The regression arm: byte for byte the request this made before the name fields existed.
    source().read(RepositoryRef.ofId("qits-gateway"), SHA);

    assertEquals("/git/qits-gateway/blob/" + SHA + "/.config/qits/deployments.yml", onlyPath());
  }

  @Test
  public void halfAnAddressTakesTheIdRouteRatherThanBuildingHalfAPath() {
    source().read(new RepositoryRef(UUID_ID, "qits", null), SHA);

    assertEquals("/git/" + UUID_ID + "/blob/" + SHA + "/.config/qits/deployments.yml", onlyPath());
  }

  @Test
  public void aTrailingSlashOnTheConfiguredHostDoesNotDoubleTheSeparator() {
    GitHostSpecSource source = source();
    source.gitHostUrl = source.gitHostUrl + "/";

    source.read(new RepositoryRef(UUID_ID, "qits", "qits-gateway"), SHA);

    assertEquals(
        "/git/qits/qits-gateway/blob/" + SHA + "/.config/qits/deployments.yml", onlyPath());
  }

  @Test
  public void a404IsAnAnswerOnBothArms() {
    // A repository carrying no file deploys with every default — unchanged by which URL asked.
    status = 404;

    assertSame(
        DeploymentSpec.DEFAULTS,
        source().read(new RepositoryRef(UUID_ID, "qits", "gw"), SHA).spec());
    SpecSource.SpecRead byId = source().read(RepositoryRef.ofId("gw"), SHA);
    assertSame(DeploymentSpec.DEFAULTS, byId.spec());
    // No blob, so no commit: a 404 says nothing about where the rev points, and inventing a sha
    // here would put a commit on the deployment row that nobody resolved.
    assertNull(byId.commitSha());
  }

  @Test
  public void anythingElseFailsTheDeploymentNamingTheUrlItAsked() {
    // The fe26a6c stance: a read that could not be answered is a failure, never an empty spec.
    status = 503;

    SpecException refused =
        assertThrows(
            SpecException.class,
            () -> source().read(new RepositoryRef(UUID_ID, "qits", "qits-gateway"), SHA));

    assertTrue(refused.getMessage().contains("/git/qits/qits-gateway/blob/"), refused.getMessage());
    assertTrue(refused.getMessage().contains("503"), refused.getMessage());
  }

  @Test
  public void aReleaseIsReadAtTheTagRefWithItsSlashesEncoded() {
    // THE claim of the version campaign, and it has two halves. The rev is refs/tags/<version>
    // rather than the bare version, because a bare name is whatever the repository holds under it
    // — a branch of that name would win, and the file that decides where this container runs has
    // to be the file the release was cut from. And a rev is ONE path segment to the git host,
    // whose route regex is [^/]+ and whose charset refuses a literal slash, so it arrives encoded.
    source().read(RepositoryRef.ofId("qits-gateway"), SpecSource.tagRev(VERSION));

    assertEquals(
        "/git/qits-gateway/blob/refs%2Ftags%2F" + VERSION + "/.config/qits/deployments.yml",
        onlyPath());
  }

  @Test
  public void theResolvedCommitComesBackWithTheSpecRatherThanFromASecondRequest() {
    // The git host offers no ref-resolution endpoint at all, so without this header a released
    // deployment could record no commit and the edge from a container back to a diff would be
    // gone. One request, both answers.
    SpecSource.SpecRead read =
        source().read(RepositoryRef.ofId("qits-gateway"), SpecSource.tagRev(VERSION));

    assertEquals(SHA, read.commitSha());
    assertEquals(PdDeploymentTarget.PLATFORM, read.spec().target());
    assertEquals(1, paths.size(), "one request, not a read plus a resolution");
  }

  @Test
  public void anAnswerWithNoCommitHeaderCostsTheTraceEdgeAndNotTheDeployment() {
    // An older git host, or a proxy that dropped the header. The version is the coordinate and the
    // sha is advisory, so the read still answers and the row simply names no commit.
    commitSha = null;

    SpecSource.SpecRead read =
        source().read(RepositoryRef.ofId("qits-gateway"), SpecSource.tagRev(VERSION));

    assertNull(read.commitSha());
    assertEquals(PdDeploymentTarget.PLATFORM, read.spec().target());
  }

  private GitHostSpecSource source() {
    GitHostSpecSource source = new GitHostSpecSource();
    source.gitHostUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    source.timeoutSeconds = 5;
    return source;
  }

  private String onlyPath() {
    synchronized (paths) {
      assertEquals(1, paths.size(), "requests made");
      return paths.get(0);
    }
  }
}
