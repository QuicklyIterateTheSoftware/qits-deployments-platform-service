package eu.wohlben.qits.platform.deployments.deployments.dto;

import eu.wohlben.qits.platform.deployments.deployments.entity.PdQualityGate;
import java.time.Instant;

/**
 * One deployment request on the wire: <b>this version of this application was asked for here, the
 * gate said this, and this is what it became</b>.
 *
 * <p>It is the lifecycle a reader watches — request, gate, deployment — and the only shape in which
 * a release that shipped NOTHING is visible at all. A refused request has a null {@code
 * deploymentId} and a {@code gateDetail} that says why; there is no deployment row anywhere that
 * could carry that sentence.
 *
 * <p><b>The join key is {@code applicationName} and deliberately not a derived {@code
 * applicationId}.</b> {@link PdDeploymentDto} derives one from {@code (plane, tier, name)} because
 * a deployment row records its plane; a request records no plane — it is written before the
 * catalogue is consulted for one — so deriving a key here would mean guessing {@code platform:} or
 * {@code <tier>:} from a column that does not exist. A name is unique per tier by construction (the
 * catalogue holds one identity per service), so a client folds these into its application rows by
 * name, and that is honest rather than lossy.
 *
 * <p>{@code version} is the released CalVer coordinate and is never null: a request is about a
 * version, which is what makes two of them comparable. {@code environmentId} is where it was asked
 * for — a platform service's request names the main environment like its deployment does.
 *
 * <p>{@code gateSettledAt} is null while nothing has answered. Today's placeholder answers in the
 * same transaction that writes the row, so it is always set; the field exists because the first
 * real gate is the one that will leave it null for a while.
 */
public record PdDeploymentRequestDto(
    String id,
    String applicationName,
    String version,
    String environmentId,
    String packageName,
    String repoId,
    String projectId,
    PdQualityGate qualityGate,
    String gateDetail,
    String deploymentId,
    Instant createdAt,
    Instant gateSettledAt) {}
