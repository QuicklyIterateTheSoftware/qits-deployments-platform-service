package eu.wohlben.qits.platform.deployments.deployments.control;

import org.jboss.logging.Logger;

/**
 * The priority a release declares, made safe to <b>record</b> — and nothing else.
 *
 * <p>qits-projects puts a priority on a participating branch of a release request, qits-ci
 * transcribes the request's effective value onto {@code SoftwareRelease}, and this component writes
 * it on the rows a release produces. <b>Nothing here acts on it.</b> The deploy worker is FIFO and
 * stays FIFO; the value is display-only, for a person reading the deployment requests and for the
 * queue-ordering feature that comes later.
 *
 * <p><b>So it is a String and not an enum</b>, deliberately. The vocabulary lives in qits-projects
 * ({@code LOWEST … BLOCKING}) and a local copy here would be a second spelling of somebody else's
 * decision — the no-shared-vocabulary rule the whole intake path already follows for the release's
 * other fields. A word this component has never heard of is recorded verbatim and rendered by
 * whoever reads it.
 *
 * <p><b>And it is LENIENT, which is the whole reason this class exists rather than a line in {@link
 * DeploymentIdentifiers}.</b> That validator throws, and everything it guards can escape an argv, a
 * URL or an image path. This value reaches none of those: it is one column and one DTO field. An
 * advisory field must never be able to refuse a release — a deployment that failed because somebody
 * typed a long word into a priority select would be this component refusing to put a green release
 * live over a badge. So a value too long for its column is dropped with a WARN and the release
 * deploys, exactly as a release that declared no priority at all does.
 *
 * <p><b>Null is a real answer and is never backfilled</b>: it means "the release stated none",
 * which is every release cut before the field existed and every event replayed from before it.
 */
public final class ReleasePriorities {

  private static final Logger LOG = Logger.getLogger(ReleasePriorities.class);

  /**
   * What the column holds — {@code varchar(32)} on both {@code pd_deployment_request} and {@code
   * pd_owed_release}, which is qits-projects' own {@code varchar(32)} for the enum it stores.
   */
  public static final int MAX_LENGTH = 32;

  private ReleasePriorities() {}

  /**
   * This priority as it may be recorded: trimmed, or null for absent, blank or overlong.
   *
   * @param priority whatever the door was given, including null
   * @param what the release this is about, for the warning — a deployment is never refused, so the
   *     log line is the only place an overlong value is ever mentioned
   */
  public static String recorded(String priority, String what) {
    if (priority == null) {
      return null;
    }
    String trimmed = priority.strip();
    if (trimmed.isEmpty()) {
      return null;
    }
    if (trimmed.length() > MAX_LENGTH) {
      LOG.warnf(
          "The release of %s declares a priority of %d characters, which does not fit the %d the"
              + " column holds; it is recorded as none rather than refusing the release",
          what, trimmed.length(), MAX_LENGTH);
      return null;
    }
    return trimmed;
  }
}
