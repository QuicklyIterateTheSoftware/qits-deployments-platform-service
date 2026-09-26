package eu.wohlben.qits.deployments.api;

import io.quarkus.vertx.http.runtime.filters.Filters;
import io.vertx.ext.web.RoutingContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * The retired {@code /platform-deployments} prefix, answered for exactly one release by rewriting it
 * onto {@code /deployments} — and logged, once per request, so the cutover can be measured rather
 * than guessed at.
 *
 * <p><b>DELETE THIS CLASS THE DAY THE LOG FALLS SILENT.</b> That is not a tidy-up note, it is the
 * whole design: every WARN this filter writes is one caller still addressing a word the platform
 * retired, named with the path it asked for and the identity it presented. While the lines keep
 * coming there is something left to migrate; when a release goes by without one, the class, the
 * second entry in {@code quarkus.quinoa.ignored-path-prefixes}, the second prefix in {@code routes:}
 * and (once no {@code pd_service} row names the old path) {@code health_path:} all go together, and
 * the segment drops from five spellings to four.
 *
 * <p><b>Why the old prefix needs answering at all.</b> The route moved, and the callers did not move
 * with it: the SPA in {@code service/src/main/webui} builds every request as {@code
 * /platform-deployments/api/…} and ships from its own repository on its own gitlink; qits-ci's
 * release notifier POSTs the intake at a configured url and swallows a delivery failure at debug;
 * qits-artifacts' image collector reads the pins. A prefix that simply 404s would take the client
 * out, stop deployments platform-wide with nothing in any log to say why, and fail garbage
 * collection closed. A rewrite keeps all three working while each is moved, and the WARN is what
 * says when they have been.
 *
 * <p><b>Why a {@link Filters} entry rather than a second {@code @Path} tree.</b> Two surfaces sit
 * under the segment and only one of them is JAX-RS: {@code /deployments/api} is this repository's
 * resources, while {@code /deployments/q} is Quarkus' own non-application root — openapi, swagger-ui
 * and the health endpoints the deployer's own gate curls. A duplicated resource tree would cover the
 * first and could not reach the second at all, and the health path is the one that decides whether a
 * self-deployment converges. A filter sits in front of every route, framework routes included, and
 * costs one string comparison on a path that does not match.
 *
 * <p><b>It runs FIRST, at {@link #PRIORITY}, and above Quarkus' own authentication handler (200).</b>
 * {@link RoutingContext#reroute} restarts the router from the top with the new path, so anything that
 * ran before the rewrite ran against an address this service does not serve and then runs again — the
 * authentication handler included. Rewriting before it is one pass instead of two, and it is what
 * keeps this filter's rewrite invisible to everything downstream: by the time any guard, any {@code
 * @RolesAllowed} or any route matcher sees the request, the path is the current one. Nothing here
 * decides anything about identity; the roles on the resources are untouched and answer exactly as
 * they do on the live prefix.
 *
 * <p><b>The rewrite is on the RAW path, never the normalized one.</b> {@code normalizedPath()}
 * resolves traversal and decodes, so building a reroute target from it would re-emit a path with its
 * encoding flattened; the restart normalizes the new path for itself anyway. The query string is
 * carried across verbatim, because the deployment listing's {@code ?environmentId=} is the whole of
 * what that read is.
 *
 * <p><b>The loop is guarded, and the guard was earned.</b> The restart runs this filter again, and
 * Vert.x applies no re-route limit here — so a rewrite that landed back under the legacy prefix would
 * spin until the process ran out of heap rather than answering anything. That is measured: breaking
 * the substring by hand OOM'd the test JVM. In the shipped state the rewritten path no longer carries
 * the prefix and the filter falls straight through on the second pass; the check in {@link #filter}
 * is what keeps a future edit's cost a 404 instead of the process.
 */
@ApplicationScoped
public class LegacyPrefixReroute {

  private static final Logger LOG = Logger.getLogger(LegacyPrefixReroute.class);

  /** The retired spelling, without a trailing slash: both {@code /x} and {@code /x/…} are matched. */
  static final String LEGACY = "/platform-deployments";

  /** What it is rewritten onto — {@code quarkus.rest.path} and the non-application root's segment. */
  static final String CURRENT = "/deployments";

  /**
   * Above Quarkus' authentication (200) and authorization (100) filters, so the path is already the
   * current one when either of them looks at it and the reroute costs one pass rather than two.
   */
  static final int PRIORITY = 1000;

  /**
   * Who asked. The edge asserts this header for a signed-in person and anything on the platform's
   * networks may send it, so it is the best name available for a caller here — and it is what turns
   * a WARN from "somebody is still on the old prefix" into a thing somebody can go and fix. It is
   * read as an identity for the LOG LINE only; nothing here authenticates or refuses.
   */
  @ConfigProperty(name = "qits.auth.forward.user-header")
  String userHeader;

  void register(@Observes Filters filters) {
    filters.register(this::filter, PRIORITY);
  }

  void filter(RoutingContext rc) {
    String path = rc.request().path();
    if (!matches(path)) {
      rc.next();
      return;
    }

    String rewritten = CURRENT + path.substring(LEGACY.length());
    if (matches(rewritten)) {
      // MEASURED, not feared: Vert.x' reroute has NO loop guard on this path — it restarts the
      // router and this filter matches again — so a rewrite that lands back inside the legacy
      // prefix spins until the process dies of heap. Breaking the substring above by hand produced
      // a 21-million-line log and an OutOfMemoryError in the forked JVM, which is the failure this
      // branch converts into a 404 and a line somebody can read. It is unreachable while CURRENT is
      // not itself under LEGACY; it exists because the cost of being wrong about that is the
      // process rather than the request.
      LOG.errorf(
          "Refusing to reroute %s: it rewrites to %s, which is still under %s and would loop",
          path, rewritten, LEGACY);
      rc.next();
      return;
    }
    String query = rc.request().query();
    // One line per hit, and it names both halves of the question: WHICH address is still being
    // asked for, and by WHOM. It is a sentence people and suites grep for, which is why it is
    // built as one rather than deferred behind a format with a dozen call sites.
    LOG.warnf(
        "Rerouted %s %s to %s: the /platform-deployments prefix is retired and this reroute is"
            + " deleted after one release — caller %s",
        rc.request().method(), path, rewritten, caller(rc));
    rc.reroute(rc.request().method(), query == null ? rewritten : rewritten + "?" + query);
  }

  /** True for the bare legacy segment and for everything under it, and for nothing else. */
  static boolean matches(String path) {
    return path != null && (path.equals(LEGACY) || path.startsWith(LEGACY + "/"));
  }

  /**
   * The forwarded user where there is one, and the peer address where there is not — a service
   * dialling this surface over the platform's networks presents no user header, and an ip is still
   * enough to find the container. Never a credential: the {@code Authorization} header is not read
   * here at all, so no token can ride out in a log line.
   */
  private String caller(RoutingContext rc) {
    String user = rc.request().getHeader(userHeader);
    if (user != null && !user.isBlank()) {
      return user;
    }
    return rc.request().remoteAddress() == null
        ? "unknown"
        : rc.request().remoteAddress().hostAddress();
  }
}
