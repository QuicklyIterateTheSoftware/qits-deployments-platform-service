package eu.wohlben.qits.deployments.environments.control;

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
 * </ul>
 *
 * <p><b>There is no third network and no {@code qits-platform} overlay any more.</b> It was where
 * the platform plane's services ran, and the plane is deleted: every service is an environment
 * service in one tier, so a network whose whole meaning was "belongs to no environment" has nothing
 * left to hold.
 *
 * <p>Only the bundle name is ever stored — on the environment row, so a tier's public-node network
 * can be something other than the convention (dev's is {@code qits-net} by history). The other is
 * computed at deploy time and read back from docker's labels. <b>Nothing here is persisted.</b>
 * A network's membership is docker's bookkeeping, never a row — a copy in this database would be a
 * second answer that goes stale the first time a container is replaced.
 *
 * <p>It lives in the topology module because the topology is what the names describe, and because
 * both halves of the component need them: environment creation fills the bundle default, and the
 * deploy orchestration derives the rest.
 */
public final class PdNetworks {

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
   * <p><b>It is {@code <environment>-<application>}, unconditionally, and the absence of a second
   * arm is the point.</b> The qualifier is what lets two tiers hold the same application's address
   * on one shared network (the flat overlay is shared by all of them) without one resolving as the
   * other; every service has a tier now, so every address carries it. The bare spelling
   * ({@code qits-ci}) was the platform plane's and went with the plane.
   *
   * <p><b>The rename this caused was a cutover, not a refactor, and it was paid for once.</b> A
   * swarm service's name IS its address and swarm cannot rename one, so the deployment that first
   * derived a qualified name for a former platform service created {@code <env>-<app>} beside the
   * bare-named service that was serving. What made it survivable is that the qualified name was
   * granted as an extra network alias first, one release earlier, so a peer that had already moved
   * to it kept resolving across the gap. The bare-named predecessor is not found by any lookup here
   * — it is a service of another name — and was removed by hand; see AGENTS.md, <i>Retiring the
   * plane's bare-named services</i>.
   */
  public static String alias(String environmentName, String applicationName) {
    return environmentName + "-" + applicationName;
  }
}
