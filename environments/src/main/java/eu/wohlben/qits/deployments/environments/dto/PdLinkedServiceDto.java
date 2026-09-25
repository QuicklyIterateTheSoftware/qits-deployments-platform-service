package eu.wohlben.qits.deployments.environments.dto;

/**
 * One service present in an environment, as a reconciliation needs it: what it is called, whether
 * it is a public node, and where to probe it.
 *
 * <p>Deliberately narrower than {@link PdServiceDto}: this is the pull query's answer, and its
 * reader is reconciling containers and networks rather than editing the topology. It carries no
 * link ids, because the environment asked about IS the link — and it no longer carries a plane,
 * because there is one.
 */
public record PdLinkedServiceDto(
    String id,
    String name,
    boolean availableOnEnv,
    String healthPath) {}
