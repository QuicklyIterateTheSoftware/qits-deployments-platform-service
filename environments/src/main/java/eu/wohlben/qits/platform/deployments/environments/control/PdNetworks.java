package eu.wohlben.qits.platform.deployments.environments.control;

import eu.wohlben.qits.platform.deployments.environments.entity.PdDeploymentTarget;

/**
 * The docker network names this component derives, in one place — the topology is hub-and-spoke and
 * the names are the whole of how it is addressed.
 *
 * <ul>
 *   <li><b>Per service</b> ({@link #application}): where an environment's service actually runs.
 *       Only its own containers are on it, so nothing in the environment can reach it without
 *       being joined to it deliberately.
 *   <li><b>Per environment bundle</b> (the environment row's own {@code network}): the
 *       environment's public nodes. One member today (qits-gateway) — kept because "the public
 *       nodes of this environment" is a set worth having a name for.
 *   <li><b>Platform</b> ({@link #PLATFORM}): where platform services run. They join every
 *       environment's per-service networks on top, which is what makes them locally reachable
 *       everywhere.
 * </ul>
 *
 * <p>Only the bundle name is ever stored — on the environment row, so a tier's public-node network
 * can be something other than the convention (dev's is {@code qits-net} by history). The other two
 * are computed at deploy time and read back from docker's labels. <b>Nothing here is persisted.</b>
 * A network's membership is docker's bookkeeping, never a row — a copy in this database would be a
 * second answer that goes stale the first time a container is replaced.
 *
 * <p>It lives in the topology module because the topology is what the names describe, and because
 * both halves of the component need them: environment creation fills the bundle default, and the
 * deploy orchestration derives the rest.
 */
public final class PdNetworks {

  /** Where platform services run, created on demand. They belong to no environment. */
  public static final String PLATFORM = "qits-platform";

  /** The bundle network an environment gets when its creator names none. */
  public static final String BUNDLE_PREFIX = "qits-env-";

  private PdNetworks() {}

  /** The bundle network of an environment that named none: {@code qits-env-<env>}. */
  public static String bundle(String environmentName) {
    return BUNDLE_PREFIX + environmentName;
  }

  /** One service's own network inside an environment: {@code qits-env-<env>-<service>}. */
  public static String application(String environmentName, String applicationName) {
    return BUNDLE_PREFIX + environmentName + "-" + applicationName;
  }

  /**
   * The <b>wire alias</b> a container answers to on every network it is on — the address peers dial,
   * and under swarm the service's own NAME. It is derived here rather than at the argv, because
   * everything that has to agree about an address takes it from here.
   *
   * <ul>
   *   <li><b>An environment service</b> is {@code <environment>-<application>} — {@code
   *       prod-qits-gateway}. The qualifier is what lets two tiers hold the same application's
   *       address on one shared network (the flat overlay is shared by all of them) without one
   *       resolving as the other.
   *   <li><b>A platform service</b> keeps the bare {@code <application>} — see {@link
   *       #platformAlias}.
   * </ul>
   *
   * <p><b>The plane is a parameter now and used to be a null environment.</b> A platform service is
   * deployed into the main environment since V8, so "no tier" no longer identifies one and an alias
   * derived from the tier would have renamed every platform service on one deployment — which,
   * swarm's service name being its address, is a second service beside the one that was serving.
   *
   * @param target which plane; {@link PdDeploymentTarget#PLATFORM} answers bare
   */
  public static String alias(
      PdDeploymentTarget target, String environmentName, String applicationName) {
    return target == PdDeploymentTarget.PLATFORM
        ? platformAlias(applicationName)
        : alias(environmentName, applicationName);
  }

  /**
   * An environment service's alias, {@code <environment>-<application>}. The tier is required: a
   * caller that has no tier is on the platform plane and says so with {@link #platformAlias}.
   */
  public static String alias(String environmentName, String applicationName) {
    return environmentName + "-" + applicationName;
  }

  /**
   * A platform service's alias: the bare application name, whichever tier it is deployed into.
   *
   * <p><b>This is what "platform" still means after V8.</b> The plane's deployments carry the main
   * environment everywhere else — the row, the labels, {@code QITS_ENVIRONMENT}, the events — and
   * the address is the one thing that deliberately does not, because a consumer in any tier reaches
   * qits-ci by writing {@code qits-ci} and must not have to know where the platform's own tier is.
   */
  public static String platformAlias(String applicationName) {
    return applicationName;
  }
}
