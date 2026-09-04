package eu.wohlben.qits.platform.deployments.environments.entity;

/**
 * Which plane a service runs on: once per environment, or once for the whole platform.
 *
 * <p>The distinction is not a size but a plane. An {@link #ENVIRONMENT} service is part of a tier —
 * each tier gets its own copy, isolated on that tier's networks — so it says which tiers it belongs
 * to by carrying a {@link PdServiceLink} to each. A {@link #PLATFORM} service is cross-environment:
 * one instance serves every environment and is reachable from every environment by design.
 *
 * <p><b>Both planes enter the platform at the same tier.</b> A release lands in the designated
 * platform environment ({@code PdEnvironment.platform}) and the plane decides what is built out of
 * that: one instance per linked tier, or one for the platform — deployed INTO that same designated
 * tier. There are no deploy refs left on either plane; {@code main} is the integration trunk and
 * deploys nothing.
 *
 * <p><b>A platform service carries no links, and that is the whole mechanism.</b> "Present
 * everywhere" is expressed as "linked nowhere in particular", which is what makes an environment
 * created tomorrow pick up qits-platform-idp without anyone editing a row. A stored link per
 * environment would be a set someone has to remember to extend.
 *
 * <p><b>What {@code PLATFORM} decides at deploy time is three things, and no longer a missing
 * tier.</b> The wire alias is bare ({@code qits-ci}, not {@code dev-qits-ci}), so a peer in any
 * environment reaches it without knowing which one the plane runs in; the membership is every
 * environment's networks; and the read surface keys it {@code platform:<name>}. Everything else —
 * the deployment row, the labels, {@code QITS_ENVIRONMENT}, the four lifecycle events — is an
 * ordinary deployment into the designated tier, and used to be a null this component could not
 * explain to anybody.
 *
 * <p><b>The word used to be {@code singleton}.</b> It was wrong in the way that matters: it named a
 * cardinality when the thing being said is which plane the service lives on, and it made this very
 * component — cross-environment in behaviour from its first commit — look like an environment
 * citizen. {@code platform} is the vocabulary everywhere now: the enum, the docker label ({@code
 * qits.platform.deployments.target=platform}) and the network ({@code qits-platform}). The spec
 * parser still accepts {@code singleton} as an alias so a repository that has not been edited yet
 * keeps deploying; see {@code DeploymentSpecParser}.
 *
 * <p>A repository declares this in its {@code .config/qits/deployments.yml}; the deploy
 * orchestration derives the service row from it on every green build.
 */
public enum PdDeploymentTarget {

  /** One instance per environment, in the environment's networks. */
  ENVIRONMENT,

  /**
   * One instance for the whole platform, in every environment's networks, deployed into the
   * designated platform environment and reached under its bare name from all of them.
   */
  PLATFORM
}
