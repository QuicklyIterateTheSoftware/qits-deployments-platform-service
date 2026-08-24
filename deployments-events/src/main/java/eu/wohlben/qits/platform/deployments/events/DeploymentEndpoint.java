package eu.wohlben.qits.platform.deployments.events;

/**
 * One public path prefix of the immutable routing snapshot carried by {@link DeploymentActive}.
 * The deployer resolves {@code upstreamHost} from the deployment's wire alias: consumers never
 * reconstruct it from naming conventions and can proxy the event's exact deployment directly.
 *
 * <p><b>It carries no navigation any more.</b> Navigation is application-level — see {@link
 * NavigationEntry} on the event itself — because one application can appear under several headings
 * and none of them describes a path prefix. A frame from a deployer that predates that move still
 * carries the two fields; reading them is the consumer's compatibility problem, not this record's.
 */
public record DeploymentEndpoint(String path, String upstreamHost, int upstreamPort) {}
