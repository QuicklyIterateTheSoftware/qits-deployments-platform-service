package eu.wohlben.qits.platform.deployments.deployments.entity;

import eu.wohlben.qits.eventstream.CausationStamp;
import eu.wohlben.qits.eventstream.CausedRow;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * One place asking for one released version: <b>this version of this application, here</b>. Written
 * by the release intake before anything is queued, and the row that decides whether a deployment
 * happens at all.
 *
 * <p><b>It is not a deployment and must not become one.</b> {@link PdDeployment} is an execution
 * record — a container, a health gate, a terminal word — and it belongs to the worker's state
 * machine from the moment it is written. This is the decision in front of it: a release announces a
 * version, the platform records that it was asked for, a gate answers, and only a {@link
 * PdQualityGate#MET} answer produces the deployment this row then points at. The gate answers yes
 * immediately today; the row exists now so that the day it does not, the refusal has somewhere to
 * be written down.
 *
 * <p><b>It points at the deployment, never the reverse</b> ({@link #deploymentId}, nullable). The
 * request is the cause and is written first, so it cannot hold a key to a row that does not exist
 * yet — and {@code pd_deployment} keeps the exact shape every existing reader of it has. A request
 * with a null {@code deploymentId} and a settled gate is a refusal, which is the whole record of a
 * release that did not ship.
 *
 * <p><b>Every foreign identity here is a plain string with no FK</b> — the application name, the
 * tier, the repository, the deployment — which is {@code pd_deployment}'s stance verbatim and V1's
 * rule for this schema. A request outlives the catalogue row and the tier that described it.
 *
 * <p><b>A {@link CausedRow}, and the cause is set EXPLICITLY.</b> The row is written on {@code
 * pd-deploy-worker}, behind the queue hop the intake made, and {@code CausationScope} is a
 * ThreadLocal that does not follow work across an executor — so {@link CausationStamp} would record
 * null on every one of these. The subscriber reads the frame's id on its own thread and it travels
 * as data, exactly as it does for {@link PdDeployment}.
 */
@Entity
@Table(name = "pd_deployment_request")
@EntityListeners(CausationStamp.class)
public class PdDeploymentRequest extends PanacheEntityBase implements CausedRow {

  @Id public String id;

  /** See the class javadoc; the platform's uniform column, never part of any constraint. */
  @Column(name = "causation_id")
  public UUID causationId;

  @Override
  public UUID causationId() {
    return causationId;
  }

  @Override
  public void causationId(UUID id) {
    this.causationId = id;
  }

  /** What was asked for, by the name this platform deploys it under. */
  @Column(name = "application_name", nullable = false, length = 64)
  public String applicationName;

  /**
   * The released coordinate — the CalVer stamp ({@code 2026.903.113443}) the release minted, which
   * is also the git tag it pushed and the tag the image carries. Never a commit sha: a request is
   * about a version, and the version is what makes two requests comparable.
   */
  @Column(nullable = false, length = 64)
  public String version;

  /** Where it was asked for, or null for the platform plane — {@code pd_deployment}'s null. */
  @Column(name = "environment_id")
  public String environmentId;

  /**
   * The package the release announced, verbatim and registry-unqualified ({@code qits/qits-ci}).
   * The application name was derived from it, and it is kept so a reader can see the derivation
   * rather than re-perform it. Null on a request made through the manual door, which names an
   * application directly.
   */
  @Column(name = "package_name", length = 255)
  public String packageName;

  /** The repository the release came from, in the git host's own storage key. Resolved by nobody. */
  @Column(name = "repo_id")
  public String repoId;

  /** The project that repository belongs to, when the release carried one. */
  @Column(name = "project_id")
  public String projectId;

  /** Whether this request may proceed. See {@link PdQualityGate}: today, always {@code MET}. */
  @Enumerated(EnumType.STRING)
  @Column(name = "quality_gate", nullable = false, length = 32)
  public PdQualityGate qualityGate;

  /** What the gate said, in words. Null on the placeholder's silent yes. */
  @Column(name = "gate_detail", columnDefinition = "text")
  public String gateDetail;

  /** The deployment this request produced, or null: nothing was queued, and the gate says why. */
  @Column(name = "deployment_id")
  public String deploymentId;

  @Column(name = "created_at", nullable = false)
  public Instant createdAt;

  /** When the gate answered. Null while nothing has asked it — a state today's placeholder skips. */
  @Column(name = "gate_settled_at")
  public Instant gateSettledAt;

  /**
   * The listing tiebreak, assigned by the database (V6's identity column) and never written here —
   * so it reads null on a freshly persisted instance. {@link PdDeployment#seq}'s reasoning, for the
   * same reason: the requests of one release are recorded in one tick.
   */
  @Column(name = "seq", insertable = false, updatable = false)
  public Long seq;
}
