package eu.wohlben.qits.platform.deployments.deployments.control;

import eu.wohlben.qits.platform.deployments.deployments.entity.PdDeployment;
import eu.wohlben.qits.platform.deployments.deployments.entity.PdDeploymentRequest;
import eu.wohlben.qits.platform.deployments.deployments.entity.PdDeploymentStatus;
import java.util.Set;

/**
 * Whether a deployment request is still MOVING, said once so that everything answering it answers
 * the same thing.
 *
 * <p>A request is the first step of a lifecycle that has three — request → gate → deployment — and
 * the question a reader actually asks of it is "is the platform still doing something about this
 * one". That question spans two rows: the gate lives on the request and the container lives on the
 * deployment the request handed off to. Neither row can answer it alone, which is why this is a pair
 * of static predicates over both rather than a method on either entity.
 *
 * <p><b>Pending is two things and completed is everything else</b>, and stating it in that direction
 * is deliberate. A positive list of the states that are still moving cannot go stale when the status
 * vocabulary grows: {@link PdDeploymentStatus} is a varchar with no check constraint precisely so a
 * word can be added without a migration, and a {@code != ACTIVE}-shaped rule would then quietly
 * class a new terminal word as in-flight. The rule here is the deployments listing's own rule
 * ({@code IN_FLIGHT}), plus the gate:
 *
 * <ul>
 *   <li>the gate has not answered ({@code gateSettledAt == null}) — unreachable while the
 *       placeholder answers inside the transaction that writes the row, and modelled anyway,
 *       because the first real gate is the one that leaves it null for a while;
 *   <li>or the deployment it produced is {@code QUEUED}, {@code STARTING} or {@code
 *       SPEC_UNREADABLE}. The third is the one worth naming: nothing is starting, but the deployer
 *       is re-reading the repository's spec on its observation cadence and this release is still
 *       going somewhere.
 * </ul>
 *
 * <p><b>A REFUSAL is completed</b>, and that is the arm this classification exists to get right: a
 * settled gate with no {@code deploymentId} queued nothing at all, so there is no status to ask
 * about and nothing will ever move again. Reading a null status as "not yet" would leave a refused
 * release polling forever.
 *
 * <p><b>The SPA mirrors this</b> — {@code isCompletedRequest} in qits-deployments-platform-frontend's
 * {@code api/dto.ts} — because the client decides which of its two sections a row belongs to and
 * whether to keep polling. The two spellings have to agree; move one, move both.
 */
public final class RequestLifecycle {

  /**
   * The deployment states in which the platform has not finished with the release. The deployments
   * listing's own set, restated here rather than reached for: this is a question about a REQUEST,
   * and it must keep its answer if that set ever grows an entry that is not one.
   */
  private static final Set<PdDeploymentStatus> IN_FLIGHT =
      Set.of(
          PdDeploymentStatus.QUEUED,
          PdDeploymentStatus.STARTING,
          PdDeploymentStatus.SPEC_UNREADABLE);

  private RequestLifecycle() {}

  /**
   * Whether this request is still moving: the gate has not answered, or the deployment it produced
   * has not landed.
   *
   * @param deploymentStatus the status of the deployment this request handed off to, or {@code null}
   *     — which is both a refusal and a request whose deployment row has been forgotten by an
   *     environment teardown, and neither is pending
   */
  public static boolean isPending(
      PdDeploymentRequest request, PdDeploymentStatus deploymentStatus) {
    return request.gateSettledAt == null
        || (deploymentStatus != null && IN_FLIGHT.contains(deploymentStatus));
  }

  /** The complement of {@link #isPending}: nothing about this request will change again. */
  public static boolean isCompleted(
      PdDeploymentRequest request, PdDeploymentStatus deploymentStatus) {
    return !isPending(request, deploymentStatus);
  }

  /** The same question asked of the deployment row itself, which may be absent. */
  public static boolean isPending(PdDeploymentRequest request, PdDeployment deployment) {
    return isPending(request, deployment == null ? null : deployment.status);
  }
}
