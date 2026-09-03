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
