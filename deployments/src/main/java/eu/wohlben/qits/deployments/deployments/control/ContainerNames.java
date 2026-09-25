package eu.wohlben.qits.deployments.deployments.control;

/**
 * One name shape for every container this component starts: {@code qits-pd-<env>-<app>-<id8>}.
 *
 * <p><b>It is always qualified, and the second shape went with the platform plane.</b> A platform
 * deployment used to drop the environment segment ({@code qits-pd-qits-ci-abc12345}) because the
 * plane's names were unqualified, the wire alias first of all. Every deployment belongs to a tier
 * now, and every name says which.
 *
 * <p><b>The prefix is {@code qits-pd-}, and it stays that way after the namespace rename.</b> The
 * config keys and labels spell the namespace in full ({@code qits.platform.deployments.*}); a
 * container name cannot, because docker's name charset has no dot, and
 * {@code qits-platform-deployments-<env>-<app>-<id8>} spends 26 characters on a prefix before the
 * two words a person actually reads. So {@code qits-pd-} is kept as the namespace's abbreviation —
 * a display convention, nothing resolves through it.
 *
 * <p>Containers a retired qits-cd left behind are named {@code qits-cd-…} and are adopted as
 * predecessors like any other unlabelled holder — the naming is how a person reads the host, never
 * how a predecessor is found (that is the wire alias, {@code PdNetworks.alias}).
 */
public final class ContainerNames {

  /** The prefix of every container this component starts. */
  public static final String PREFIX = "qits-pd-";

  private ContainerNames() {}

  /**
   * @param environmentName the tier. Null keeps the segment out rather than composing {@code
   *     qits-pd--<app>-…}, which is the one shape left that has no tier to name: the refusal row a
   *     mid-bootstrap install records before anything is designated.
   */
  public static String of(String environmentName, String applicationName, String deploymentId) {
    String shortId = deploymentId.length() > 8 ? deploymentId.substring(0, 8) : deploymentId;
    return PREFIX
        + (environmentName == null ? "" : environmentName + "-")
        + applicationName
        + "-"
        + shortId;
  }
}
