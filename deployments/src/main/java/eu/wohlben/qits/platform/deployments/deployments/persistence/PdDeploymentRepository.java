package eu.wohlben.qits.platform.deployments.deployments.persistence;

import eu.wohlben.qits.platform.deployments.deployments.entity.PdDeployment;
import eu.wohlben.qits.platform.deployments.deployments.entity.PdDeploymentStatus;
import eu.wohlben.qits.platform.deployments.environments.entity.PdDeploymentTarget;
import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;
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
 * <p><b>The PLANE is a column and the tier is a tier.</b> A platform deployment used to be spelled
 * as a null {@code environment_id}, so the two questions were one query; it names the main
 * environment since V8, so "which plane" is {@code deploymentTarget} and "which tier" is the
 * environment. Where a tier is still matched, {@code null} is tested as a value rather than
 * compared — rows written before V8 on an install with no designated tier keep a null, and {@code
 * environment_id = null} matches nothing at all in SQL.
 */
@ApplicationScoped
public class PdDeploymentRepository implements PanacheRepositoryBase<PdDeployment, String> {

  /**
   * An environment's deployments across all its applications, newest-first — the platform plane's
   * included, since the plane is deployed into the designated tier and its rows name it.
   */
  public List<PdDeployment> listByEnvironmentNewestFirst(String environmentId) {
    return list("environmentId = ?1 order by seq desc", environmentId);
  }

  /**
   * The platform plane's deployments across all its applications, newest-first — asked by PLANE,
   * which is what {@code ?environmentId=platform} means. It was a null-tier scan while the plane
   * had no tier; the rows carry the main environment now and only the column tells them apart.
   */
  public List<PdDeployment> listPlatformNewestFirst() {
    return list("deploymentTarget = ?1 order by seq desc", PdDeploymentTarget.PLATFORM);
  }

  /**
   * Every deployment on this instance, newest-first — the whole history the pin rule reads
   * ({@code RollbackPins}). Unscoped on purpose: a pin is per application name across all tiers.
   */
  public List<PdDeployment> listAllNewestFirst() {
    return list("order by seq desc");
  }

  /** Every deployment of one application in one tier ({@code null} environment = the platform). */
  public List<PdDeployment> listByApplication(String applicationName, String environmentId) {
    return environmentId == null
        ? list("applicationName = ?1 and environmentId is null", applicationName)
        : list("applicationName = ?1 and environmentId = ?2", applicationName, environmentId);
  }

  /**
   * Every ENVIRONMENT-plane deployment of one application — what a conversion to the platform plane
   * absorbs. Asked by plane rather than by "has a tier", which stopped telling the two apart when
   * the platform plane gained one.
   */
  public List<PdDeployment> listEnvironmentScoped(String applicationName) {
    return list(
        "applicationName = ?1 and deploymentTarget = ?2",
        applicationName,
        PdDeploymentTarget.ENVIRONMENT);
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
   * eu.wohlben.qits.platform.deployments.environments.control.ApplicationKeys.Key} carries: a tier
   * id, or {@code null} for the <b>platform plane</b>. So the null arm asks {@code
   * deployment_target}, exactly as {@link #listPlatformNewestFirst} does — <b>not</b> {@code
   * environment_id is null</b>, which was the same question only while the plane had no tier. Since
   * V8 the plane names the designated tier, so the null-tier read would answer with pre-V8 rows
   * alone and an operator's scale would act on a deployment years old, or on nothing at all.
   *
   * <p>The tier arm is deliberately plane-blind: a tier id names one place, and both planes'
   * newest row for that application in that tier is the row that is actually serving there.
   */
  public Optional<PdDeployment> newestForPlace(String applicationName, String environmentId) {
    return environmentId == null
        ? find(
                "applicationName = ?1 and deploymentTarget = ?2 order by seq desc",
                applicationName,
                PdDeploymentTarget.PLATFORM)
            .firstResultOptional()
        : find(
                "applicationName = ?1 and environmentId = ?2 order by seq desc",
                applicationName,
                environmentId)
            .firstResultOptional();
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

  // `newestInPlaces` lived here, and it went with BuildTips: a build resolved a branch to a set of
  // tiers and asked what those tiers were last handed, with the platform plane joined in as `or
  // environmentId is null`. A release has no branch and ReleaseTips' cross-restart floor is a
  // deployment REQUEST row, so the query had no caller left — and its platform arm was the exact
  // absent-environment inference V8 removes.
}
