package eu.wohlben.qits.platform.deployments.environments.control;

import eu.wohlben.qits.platform.deployments.environments.entity.PdDeploymentTarget;

/**
 * The id an application is addressed by on the read surface, derived rather than stored.
 *
 * <p>A service has one row, carrying N environment links, while a deployment row names {@code
 * (application_name, environment_id, deployment_target)}. The client joins the two listings on an
 * id, so the id has to be computable from both sides: {@code <environmentId>:<name>}, and {@code
 * platform:<name>} for a service on the platform plane.
 *
 * <p><b>The PLANE decides, not the tier, and V8 is why that distinction had to be made.</b> This
 * used to read the platform stand-in off a null environment id, which was the same statement while
 * a platform deployment belonged to no tier. It belongs to the main one now — its row, labels and
 * events all name it — and a key derived from that tier would move every platform application's id
 * out from under the client on one deployment, while the catalogue side (a platform service still
 * carries no link) went on saying {@code platform:}. So both sides state the plane.
 *
 * <p>It is also the grouping key of the rollback pins: one service name in two environments is two
 * histories, and merging them would name the wrong rollback target.
 *
 * <p>The stand-in used to read {@code singleton:}; it reads {@code platform:} now, with the rest of
 * the vocabulary. Nothing persists it, so there is nothing to migrate — but a client that cached an
 * id across the rename would fail to join, which is why it is spelled once, here.
 */
public final class ApplicationKeys {

  /**
   * Where a platform service's key stands in for an environment id — the plane is what a reader of
   * this id is being told, and it outranks the tier the plane happens to run in. An environment id
   * is a random UUID, so this word is unambiguous wherever one is expected, which is what lets the
   * deployment listing take it as a filter value.
   */
  public static final String PLATFORM = "platform";

  private ApplicationKeys() {}

  /**
   * @param target which plane — {@link PdDeploymentTarget#PLATFORM} takes the stand-in whatever
   *     tier it is deployed into. Null is tolerated and reads as an environment application, which
   *     is what a row written before the plane was recorded means.
   */
  public static String of(
      PdDeploymentTarget target, String environmentId, String applicationName) {
    return (target == PdDeploymentTarget.PLATFORM || environmentId == null
            ? PLATFORM
            : environmentId)
        + ":"
        + applicationName;
  }

  /** Whether a value written where an environment id goes names the platform plane instead. */
  public static boolean isPlatform(String environmentId) {
    return PLATFORM.equals(environmentId);
  }
}
