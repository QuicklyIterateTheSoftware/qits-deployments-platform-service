package eu.wohlben.qits.platform.deployments.deployments.mapper;

import eu.wohlben.qits.platform.deployments.deployments.dto.PdDeploymentDto;
import eu.wohlben.qits.platform.deployments.deployments.dto.PdDeploymentRequestDto;
import eu.wohlben.qits.platform.deployments.deployments.entity.PdDeployment;
import eu.wohlben.qits.platform.deployments.deployments.entity.PdDeploymentRequest;
import eu.wohlben.qits.platform.deployments.environments.control.ApplicationKeys;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * The deployment wire shape. Hand-written rather than a MapStruct interface for one reason: {@code
 * applicationId} is not a column. It is derived from the row's {@code (deploymentTarget,
 * environmentId, applicationName)} through {@link ApplicationKeys}, which is the same definition
 * the applications listing derives its own id from — that shared derivation is what lets a client
 * keep joining the two listings when neither side has a row to take an id from.
 *
 * <p><b>The PLANE goes into the key, and V8 is why.</b> A platform deployment names the main
 * environment now, so a key taken from the tier alone would have turned {@code platform:qits-ci}
 * into {@code <devId>:qits-ci} on one deployment — while the catalogue side, where a platform
 * service still carries no link, went on saying {@code platform:}. The join would have broken
 * silently, one application at a time, as each was redeployed.
 */
@ApplicationScoped
public class DeploymentMapper {

  public PdDeploymentDto toDto(PdDeployment deployment) {
    return new PdDeploymentDto(
        deployment.id,
        ApplicationKeys.of(
            deployment.deploymentTarget, deployment.environmentId, deployment.applicationName),
        deployment.applicationName,
        deployment.version,
        deployment.commitSha,
        deployment.runId,
        deployment.status,
        deployment.containerName,
        deployment.detail,
        deployment.createdAt,
        deployment.finishedAt);
  }

  /**
   * The deployment REQUEST's wire shape — a field-for-field copy of the row, plus one field that is
   * not on it.
   *
   * <p>No {@code applicationId} is computed. The derivation needs the PLANE, a request has no column
   * for one, and inventing {@code platform:} or {@code <tier>:} from the tier alone is exactly the
   * silent mis-join this class's header describes. The name is the join key, and {@link
   * eu.wohlben.qits.platform.deployments.deployments.dto.PdDeploymentRequestDto} says so.
   *
   * <p><b>The deployment is a parameter and is nullable, and both halves of that are the point.</b>
   * A request the gate refused points at nothing, so there is no row to take a status from and the
   * field is null — the honest answer, and one a client reads as "nothing ran" rather than as "not
   * yet". And the row arrives as an argument rather than being fetched here, because every caller is
   * mapping a LIST: the join is one batch query at the caller, and a mapper that loaded per row
   * would turn a listing into a query per line.
   *
   * @param deployment the deployment {@code request.deploymentId} names, or {@code null}
   */
  public PdDeploymentRequestDto toDto(PdDeploymentRequest request, PdDeployment deployment) {
    return new PdDeploymentRequestDto(
        request.id,
        request.applicationName,
        request.version,
        request.environmentId,
        request.packageName,
        request.repoId,
        request.projectId,
        request.qualityGate,
        request.gateDetail,
        request.deploymentId,
        request.createdAt,
        request.gateSettledAt,
        deployment == null ? null : deployment.status);
  }
}
