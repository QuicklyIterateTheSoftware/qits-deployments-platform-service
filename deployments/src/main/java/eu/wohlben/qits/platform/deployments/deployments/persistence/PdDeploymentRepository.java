package eu.wohlben.qits.platform.deployments.deployments.persistence;

import eu.wohlben.qits.platform.deployments.deployments.entity.PdDeployment;
import eu.wohlben.qits.platform.deployments.deployments.entity.PdDeploymentStatus;
import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * Panache DAO for {@link PdDeployment}.
 *
 * <p>Every listing orders by {@code seq desc} — V1's identity column — rather than by {@code
 * createdAt desc, id desc}. The id is a random UUID, so the older tiebreak swapped two rows
 * recorded in the same tick at random, which is exactly what the deployments of one
 * build-succeeded event are.
 *
 * <p><b>There is one place a deployment can be and it is a TIER.</b> Half the queries here used to
 * come in pairs — one arm asking the tier, the other asking {@code deployment_target} for the
 * platform plane — and the plane is deleted (V13), so every question is the tier's. Where a tier is
 * matched, {@code null} is tested as a value rather than compared: a row written on an install with
 * no designated tier keeps a null, and {@code environment_id = null} matches nothing at all in SQL.
 * <b>What must not come back is a query that reads that null as a plane.</b>
 */
@ApplicationScoped
public class PdDeploymentRepository implements PanacheRepositoryBase<PdDeployment, String> {

  /** An environment's deployments across all its applications, newest-first. */
  public List<PdDeployment> listByEnvironmentNewestFirst(String environmentId) {
    return list("environmentId = ?1 order by seq desc", environmentId);
  }

  /**
   * Every deployment on this instance, newest-first — the whole history the pin rule reads
   * ({@code RollbackPins}). Unscoped on purpose: a pin is per application name across all tiers.
   */
  public List<PdDeployment> listAllNewestFirst() {
    return list("order by seq desc");
  }

  /** Every deployment of one application in one tier. */
  public List<PdDeployment> listByApplication(String applicationName, String environmentId) {
    return environmentId == null
        ? list("applicationName = ?1 and environmentId is null", applicationName)
        : list("applicationName = ?1 and environmentId = ?2", applicationName, environmentId);
  }

  /** The application's currently serving deployment(s) in one tier — by invariant at most one. */
  public List<PdDeployment> listActiveByApplication(String applicationName, String environmentId) {
    return environmentId == null
        ? list(
            "applicationName = ?1 and environmentId is null and status = ?2",
            applicationName,
            PdDeploymentStatus.ACTIVE)
        : list(
            "applicationName = ?1 and environmentId = ?2 and status = ?3",
            applicationName,
            environmentId,
            PdDeploymentStatus.ACTIVE);
  }

  /**
   * The newest deployment of one application in one place — the row an operator's scale or restart
   * acts on, and the row the observation settles.
   *
   * <p>The place is what {@link
   * eu.wohlben.qits.platform.deployments.environments.control.ApplicationKeys.Key} carries, which
   * since V13 is a tier id and nothing else: the {@code platform:} stand-in went with the plane, so
   * the second arm this method carried — "every row whose {@code deployment_target} is PLATFORM" —
   * has no key that can reach it. A null tier is tested as a value, for the class rule above.
   */
  public Optional<PdDeployment> newestForPlace(String applicationName, String environmentId) {
    return environmentId == null
        ? find("applicationName = ?1 and environmentId is null order by seq desc", applicationName)
            .firstResultOptional()
        : find(
                "applicationName = ?1 and environmentId = ?2 order by seq desc",
                applicationName,
                environmentId)
            .firstResultOptional();
  }

  /**
   * One application's whole history in ONE PLACE, newest-first — every row {@link #newestForPlace}
   * would pick the first of.
   *
   * <p>Whole rather than newest because a retirement has a second row to settle: {@code
   * SPEC_UNREADABLE} is re-read on the observation's cadence wherever it sits in the history, so
   * stopping that retry means finding it. See {@code ApplicationRetirement}.
   */
  public List<PdDeployment> listForPlaceNewestFirst(String applicationName, String environmentId) {
    return environmentId == null
        ? list("applicationName = ?1 and environmentId is null order by seq desc", applicationName)
        : list(
            "applicationName = ?1 and environmentId = ?2 order by seq desc",
            applicationName,
            environmentId);
  }

  /**
   * One application's whole history, newest-first — what a build falls back to when the spec at
   * that sha cannot be read at all.
   */
  public List<PdDeployment> listByApplicationNewestFirst(String applicationName) {
    return list("applicationName = ?1 order by seq desc", applicationName);
  }

  public List<PdDeployment> listByStatus(PdDeploymentStatus status) {
    return list("status = ?1", status);
  }

  /**
   * The deployments a set of requests point at, in one query — the batch half of the request →
   * deployment join.
   *
   * <p>It exists so that a listing of N requests costs one query rather than N. The alternative
   * shape, a {@code findById} per row, is the one that reads fine in a test with three rows and
   * turns a project's release history into a hundred round trips on a real platform.
   *
   * <p><b>Unordered on purpose</b>, which is the one place this class departs from its own {@code
   * seq desc} rule: the caller already holds the requests in their order and is building a map by
   * id, so an order here would be sorted work nobody reads. An empty collection answers with an
   * empty list without asking the database — {@code in ()} is not valid SQL, and a request set with
   * no deployments at all (every one of them refused) is an ordinary answer rather than an edge
   * case.
   */
  public List<PdDeployment> listByIds(Collection<String> ids) {
    return ids.isEmpty() ? List.of() : list("id in ?1", ids);
  }

  // `newestInPlaces` lived here, and it went with BuildTips: a build resolved a branch to a set of
  // tiers and asked what those tiers were last handed, with the platform plane joined in as `or
  // environmentId is null`. A release has no branch and ReleaseTips' cross-restart floor is a
  // deployment REQUEST row, so the query had no caller left.
  //
  // `listPlatformNewestFirst` and `listEnvironmentScoped` went with the plane itself (V13). The
  // first answered `?environmentId=platform`, a filter value the listing no longer takes; the
  // second was the conversion's read — "every ENVIRONMENT-plane row of this application" — and a
  // conversion between planes is not a thing that can be asked for any more.
}
