package eu.wohlben.qits.deployments.environments.dto;

import java.time.Instant;
import java.util.List;

/**
 * One service, flattened with the environments it is linked into.
 *
 * <p>{@code environmentIds} is where it runs, and it is the whole of what a service says about
 * itself: empty means the catalogue links it nowhere, which since the platform plane was deleted is
 * a service that runs nowhere rather than one that runs everywhere.
 *
 * <p>{@code branch} is <b>vestigial</b> and reads null on everything derived registration writes: a
 * release names a tag, so a service has no deploy ref of its own to report. See {@code
 * PdService.branch}.
 *
 * <p>The ids round-trip: what is read here is what {@code PUT
 * /platform-deployments/api/services/{name}} accepts back, so a caller can read a service, change
 * one link and write it whole.
 */
public record PdServiceDto(
    String id,
    String name,
    String branch,
    boolean availableOnEnv,
    String healthPath,
    Instant createdAt,
    List<String> environmentIds) {}
