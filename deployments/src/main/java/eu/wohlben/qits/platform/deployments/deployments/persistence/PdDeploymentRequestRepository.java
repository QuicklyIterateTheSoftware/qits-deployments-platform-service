package eu.wohlben.qits.platform.deployments.deployments.persistence;

import eu.wohlben.qits.platform.deployments.deployments.entity.PdDeploymentRequest;
import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.List;
import java.util.Optional;

/**
 * Panache DAO for {@link PdDeploymentRequest}.
 *
 * <p>Every listing orders by {@code seq desc} — V6's identity column — for {@link
 * PdDeploymentRepository}'s reason: {@code createdAt} is not unique and the requests of one release
 * land in one tick, so a {@code createdAt desc, id desc} order would swap them at random.
 */
@ApplicationScoped
public class PdDeploymentRequestRepository
    implements PanacheRepositoryBase<PdDeploymentRequest, String> {

  /** One application's requests, newest-first — its whole release history on this instance. */
  public List<PdDeploymentRequest> listByApplicationNewestFirst(String applicationName) {
    return list("applicationName = ?1 order by seq desc", applicationName);
  }

  /**
   * One tier's requests across every application asked for in it, newest-first — the read surface's
   * listing, and the platform plane's requests are in it for the reason its deployments are: a
   * platform service is deployed into the main environment and its request names that tier.
   *
   * <p>{@code environment_id} is nullable and the null is never matched here, exactly as on {@link
   * PdDeploymentRepository#listByEnvironmentNewestFirst}: a request written before V8 on an install
   * with no designated tier carries one, and {@code environment_id = null} matches nothing in SQL
   * anyway.
   */
  public List<PdDeploymentRequest> listByEnvironmentNewestFirst(String environmentId) {
    return list("environmentId = ?1 order by seq desc", environmentId);
  }

  /** The same listing narrowed to one application — "what has this service been asked for here". */
  public List<PdDeploymentRequest> listByEnvironmentAndApplicationNewestFirst(
      String environmentId, String applicationName) {
    return list(
        "environmentId = ?1 and applicationName = ?2 order by seq desc",
        environmentId,
        applicationName);
  }

  /**
   * One PROJECT's requests across every application and every tier, newest-first — the listing the
   * project-scoped screen reads.
   *
   * <p><b>{@code project_id} is a foreign identity and is not resolved here</b>, exactly like {@code
   * repo_id} beside it: this component holds no project rows and never asks qits-projects whether
   * one exists. So a project nothing was ever released for answers with an empty list, which is the
   * honest answer — "no release of this project reached this platform" — rather than a 404 about a
   * row this schema does not have.
   *
   * <p>It is unscoped by tier on purpose. A project's releases enter at whatever tier the platform
   * designates, and the designation moves; a listing narrowed to today's entry tier would silently
   * lose everything asked for before it moved.
   */
  public List<PdDeploymentRequest> listByProjectNewestFirst(String projectId) {
    return list("projectId = ?1 order by seq desc", projectId);
  }

  /**
   * Every request written for one released version of one repository, newest-first — the join a
   * release page follows to ask "what did this release deploy".
   *
   * <p>The pair is {@code (repo_id, version)} rather than {@code (applicationName, version)} because
   * the caller is holding a release request over in qits-projects, which knows a repository and a
   * version and nothing about the name this platform deploys under — the application name may be the
   * repository's, or the spec's {@code application:} override, and only this table knows which.
   *
   * <p>More than one row is an ordinary answer rather than a surprise: one version is asked for once
   * per place, and a redeploy of the same version writes a second request. Newest first, so the
   * first result is the one a reader means.
   */
  public List<PdDeploymentRequest> listByRepoAndVersionNewestFirst(String repoId, String version) {
    return list("repoId = ?1 and version = ?2 order by seq desc", repoId, version);
  }

  /**
   * The newest version this application was ever asked for, whatever the gate said and whatever
   * became of it.
   *
   * <p><b>It is the cross-restart floor of the monotonic collapse</b> ({@code ReleaseTips}), and it
   * is asked of the REQUESTS rather than of the deployments on purpose: a request is written the
   * moment a release is accepted, before the spec read and before anything is queued, so it records
   * "this version was seen here" even for a release that then failed to deploy. A floor read off
   * deployment rows alone would let a stale catch-up replay a version whose newer sibling never got
   * as far as a container.
   */
  public Optional<String> newestVersionOf(String applicationName) {
    return find("applicationName = ?1 order by seq desc", applicationName)
        .firstResultOptional()
        .map(request -> request.version);
  }
}
