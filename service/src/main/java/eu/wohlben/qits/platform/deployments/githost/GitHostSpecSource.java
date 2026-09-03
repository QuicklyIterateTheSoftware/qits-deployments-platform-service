package eu.wohlben.qits.platform.deployments.githost;

import eu.wohlben.qits.platform.deployments.deployments.control.DeploymentSpecParser;
import eu.wohlben.qits.platform.deployments.deployments.control.RepositoryRef;
import eu.wohlben.qits.platform.deployments.deployments.control.SpecException;
import eu.wohlben.qits.platform.deployments.deployments.control.SpecSource;
import jakarta.enterprise.context.ApplicationScoped;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * The sole production implementation of {@link SpecSource}: one {@code GET} against the platform
 * git host's blob endpoint ({@code qits.platform.deployments.git-host-url}), the same contract
 * qits-ci reads a pipeline definition through.
 *
 * <pre>
 * GET &lt;git-host-url&gt;/git/&lt;projectId&gt;/&lt;repoName&gt;/blob/&lt;rev&gt;/.config/qits/deployments.yml
 * GET &lt;git-host-url&gt;/git/&lt;repoId&gt;/blob/&lt;rev&gt;/.config/qits/deployments.yml
 * </pre>
 *
 * <p><b>The rev is the RELEASED TAG, and it is fully qualified.</b> A deployment is a version now,
 * so the file that decides where its container runs has to be the file that version was cut from —
 * {@code refs/tags/2026.903.113443}, never the tip of a branch and never a bare tag name (a branch
 * of the same name would win). The git host takes a rev as one path segment and its own charset
 * refuses a literal {@code /}, so the slashes are percent-encoded; jgit's {@code
 * Repository#resolve} then peels an annotated tag for us.
 *
 * <p><b>The answer carries the commit the tag resolved to</b>, in the {@code Git-Commit-Sha}
 * header, and that is why this returns a {@link SpecRead} rather than a spec. The git host offers
 * no ref-resolution endpoint at all, so without this header a released deployment could record no
 * commit — and the edge from a container back to a diff would be gone. One request, both answers.
 *
 * <p><b>Two addresses for one blob, and the first is the one to use.</b> The git host's repository
 * key is an opaque storage UUID now, and {@code /git/<repoId>} is its internal scheme; the public
 * address is {@code (projectId, repoName)} and it is what a build event carries. So an announcement
 * with the name pair is read name-addressed, and one without it — an older publisher, or a push
 * that arrived on the internal route — keeps the id URL, which is exactly the request this made
 * before the pair existed.
 *
 * <p>This is the component's <b>only</b> outbound HTTP call, and it is deliberately made with the
 * JDK's own client rather than a generated REST client — one request, one path, no model to share.
 * Every path segment was validated at the intake (the slug discipline for the id, the project and
 * the name; a hex sha) before anything reached here, so none can leave the path it is written into.
 *
 * <p>It used to be one of two: the topology was another service, and every registration and every
 * resolution was a second client with a second failure mode. The merge left this one.
 *
 * <p><b>404 is an answer, not a failure.</b> A repository that carries no spec gets every default
 * and deploys exactly as it did before the file existed. Every other outcome — a refused
 * connection, a 500, a timeout, an unparseable file — raises {@link SpecException} and fails the
 * deployment: the spec decides where the container runs and what may reach it, and a guess there is
 * worse than a recorded failure.
 */
@ApplicationScoped
public class GitHostSpecSource implements SpecSource {

  private static final Logger LOG = Logger.getLogger(GitHostSpecSource.class);

  @ConfigProperty(name = "qits.platform.deployments.git-host-url")
  String gitHostUrl;

  @ConfigProperty(name = "qits.platform.deployments.git-host-timeout-seconds")
  long timeoutSeconds;

  /**
   * One client for the life of the process, the sibling's arrangement (qits-ci's {@code
   * HttpGitConfigSource} holds one too). A {@code HttpClient} owns a selector thread and a
   * connection pool, so building one per green build spends both on a single request; built lazily
   * because the timeout it is configured with is a config value, and config is not injected yet
   * when the field initialiser would run.
   */
  private volatile HttpClient client;

  private HttpClient client() {
    HttpClient existing = client;
    if (existing == null) {
      synchronized (this) {
        existing = client;
        if (existing == null) {
          existing =
              HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(timeoutSeconds)).build();
          client = existing;
        }
      }
    }
    return existing;
  }

  /** The header the git host answers every blob and tree read with: the resolved commit. */
  static final String COMMIT_SHA_HEADER = "Git-Commit-Sha";

  @Override
  public SpecRead read(RepositoryRef repository, String rev) {
    String url = blobUrl(address(repository), rev);
    HttpResponse<String> response = get(url);

    if (response.statusCode() == 404) {
      // A NAME-addressed 404 can be a false miss rather than "no spec". The name route resolves
      // through qits-projects and its database, and the very read that decides how to deploy an
      // infrastructure service (the database, or qits-projects itself) can land in the window that
      // service is being cut over — the resolver answers 404 and this deploys the DEFAULTS, whose
      // HTTP health gate a plain postgres cannot pass, so it crash-loops and never recovers. The
      // id-addressed route needs no resolver, so a name-addressed miss is retried there before it
      // is believed. A true no-spec repository 404s on both and still gets the defaults.
      if (repository.nameAddressed()) {
        String idUrl = blobUrl(repository.repoId(), rev);
        HttpResponse<String> byId = get(idUrl);
        if (byId.statusCode() == 200) {
          return new SpecRead(
              DeploymentSpecParser.parse(
                  byId.body(), SPEC_PATH + " of " + repository.applicationName() + "@" + rev),
              commitShaOf(byId));
        }
        if (byId.statusCode() != 404) {
          throw new SpecException(
              "could not read " + idUrl + ": the git host answered " + byId.statusCode());
        }
      }
      LOG.debugf(
          "%s carries no %s at %s — deploying with the defaults",
          repository.applicationName(), SPEC_PATH, rev);
      // No blob, so no commit: a 404 says nothing about where the rev points, and inventing a sha
      // here would put a commit on the row that nobody resolved.
      return new SpecRead(DeploymentSpec.DEFAULTS, null);
    }
    if (response.statusCode() != 200) {
      throw new SpecException(
          "could not read " + url + ": the git host answered " + response.statusCode());
    }
    return new SpecRead(
        DeploymentSpecParser.parse(
            response.body(), SPEC_PATH + " of " + repository.applicationName() + "@" + rev),
        commitShaOf(response));
  }

  /**
   * The commit the rev resolved to, or null when the answer carried no such header — an older git
   * host, or a proxy that dropped it. Null costs the row its commit and never the deployment: the
   * version is the coordinate, and the sha is the trace edge.
   */
  private static String commitShaOf(HttpResponse<String> response) {
    return response
        .headers()
        .firstValue(COMMIT_SHA_HEADER)
        .map(String::strip)
        .filter(sha -> !sha.isEmpty())
        .orElse(null);
  }

  /**
   * A rev is ONE path segment to the git host, whose route regex is {@code [^/]+} and whose own
   * charset refuses a literal slash — so {@code refs/tags/<version>} has to arrive percent-encoded.
   * Package-private so {@code GitHostSpecSourceTest} can spell the expected url without a socket.
   */
  static String blobUrl(String gitHostUrl, String addressSegment, String rev) {
    return trimTrailingSlash(gitHostUrl)
        + "/git/"
        + addressSegment
        + "/blob/"
        + rev.replace("/", "%2F")
        + "/"
        + SPEC_PATH;
  }

  private String blobUrl(String addressSegment, String rev) {
    return blobUrl(gitHostUrl, addressSegment, rev);
  }

  private HttpResponse<String> get(String url) {
    try {
      HttpRequest request =
          HttpRequest.newBuilder(URI.create(url))
              .timeout(Duration.ofSeconds(timeoutSeconds))
              .header("X-Qits-User", "qits-deployments")
              .header("X-Qits-Roles", "qits:system")
              .GET()
              .build();
      return client().send(request, HttpResponse.BodyHandlers.ofString());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new SpecException("interrupted while reading " + url, e);
    } catch (Exception e) {
      throw new SpecException("could not read " + url + ": " + e, e);
    }
  }

  /**
   * The path segments that name the repository: the public pair when the event carried one, the
   * internal storage id when it did not. Package-private so {@code GitHostSpecSourceTest} can hold
   * both arms without a socket.
   */
  static String address(RepositoryRef repository) {
    return repository.nameAddressed()
        ? repository.projectId() + "/" + repository.repoName()
        : repository.repoId();
  }

  private static String trimTrailingSlash(String url) {
    return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
  }
}
