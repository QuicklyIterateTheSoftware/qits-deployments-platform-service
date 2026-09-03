package eu.wohlben.qits.platform.deployments.environments.dto;

import eu.wohlben.qits.platform.deployments.environments.entity.PdDeploymentTarget;
import java.time.Instant;

/**
 * One deployable application, flattened into one tier — the shape the web client reads.
 *
 * <p>{@code environmentId} and {@code environmentName} are null exactly when {@code target} is
 * {@code PLATFORM}, and they mean "carries no LINK" rather than "runs nowhere": a platform service
 * is deployed into the designated environment, and its deployment rows name it. The absence here is
 * the catalogue's — a link per tier is what an environment application has and a platform service
 * deliberately has none of, which is what makes a tier created tomorrow pick it up.
 *
 * <p>{@code branch} is <b>vestigial</b> and reads null on everything derived registration writes:
 * a release names a tag. See {@code PdService.branch}.
 *
 * <p>{@code id} is DERIVED from the PLANE and the tier ({@code ApplicationKeys}) rather than being
 * the service row's id, because a service has one row across every tier while this listing has one
 * entry per tier — and the client joins it against a deployment's {@code applicationId}, which is
 * derived the same way on the other side. The plane is part of it precisely because a platform
 * deployment names a tier while this row does not: both sides say {@code platform:<name>}.
 *
 * <p>{@code repoId} repeats {@code name}. There is one identity for a service, and derived
 * registration has always named an application after its repository; the field stays so the
 * client's existing column keeps resolving.
 */
public record PdApplicationDto(
    String id,
    String repoId,
    String name,
    String environmentId,
    String environmentName,
    PdDeploymentTarget target,
    boolean availableOnEnv,
    String branch,
    String healthPath,
    Instant createdAt) {}
