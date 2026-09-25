package eu.wohlben.qits.deployments.environments.dto;

import java.time.Instant;

/**
 * One deployable application, flattened into one tier — the shape the web client reads.
 *
 * <p>{@code environmentId} and {@code environmentName} are null exactly when the catalogue links
 * this service nowhere. That used to mean the platform plane — "present everywhere by carrying no
 * link" — and now means what it says: a row nothing has registered into a tier since the plane was
 * deleted.
 *
 * <p>{@code branch} is <b>vestigial</b> and reads null on everything derived registration writes:
 * a release names a tag. See {@code PdService.branch}.
 *
 * <p>{@code id} is DERIVED from the tier ({@code ApplicationKeys}) rather than being the service
 * row's id, because a service has one row across every tier while this listing has one entry per
 * tier — and the client joins it against a deployment's {@code applicationId}, which is derived the
 * same way on the other side. Both sides say {@code <environmentId>:<name>}.
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
    boolean availableOnEnv,
    String branch,
    String healthPath,
    Instant createdAt) {}
