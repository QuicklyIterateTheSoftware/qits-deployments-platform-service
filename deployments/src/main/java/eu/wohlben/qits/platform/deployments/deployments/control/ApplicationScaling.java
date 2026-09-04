package eu.wohlben.qits.platform.deployments.deployments.control;

import static eu.wohlben.qits.platform.deployments.deployments.control.DeployService.CUTOVER_BUDGET;

import eu.wohlben.qits.db.DbRetry;
import eu.wohlben.qits.platform.deployments.deployments.entity.PdDeployment;
import eu.wohlben.qits.platform.deployments.deployments.entity.PdDeploymentStatus;
import eu.wohlben.qits.platform.deployments.deployments.persistence.PdDeploymentRepository;
import eu.wohlben.qits.platform.deployments.environments.control.ApplicationKeys;
import eu.wohlben.qits.platform.deployments.environments.control.PdIdentifiers;
import eu.wohlben.qits.platform.deployments.environments.error.BadRequestException;
import eu.wohlben.qits.platform.deployments.environments.error.ConflictException;
import eu.wohlben.qits.platform.deployments.environments.error.NotFoundException;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Instant;
import org.jboss.logging.Logger;

/**
 * The operator's two levers over a deployed application: <b>how many of it run</b>, and <b>replace
 * what is running</b>. Neither is a deployment, and that is the whole design.
 *
 * <p><b>Why it exists.</b> qits-ci wedged on a disk-full incident: the process was up, its health
 * probe answered, and its in-memory dispatch queue had been orphaned — a state its own boot sweep
 * recovers from in seconds. The only lever an operator had was re-firing a same-sha push to {@code
 * environment/dev} so a rebuild and a redeploy would replace the container: a quarter of an hour of
 * ceremony, a new deployment row, and a fresh image build, to restart a process. A restart is an
 * operation, so it is an operation here.
 *
 * <p><b>Everything runs on the deploy worker.</b> The door validates, resolves the row and returns;
 * the {@code docker service update} and the row it writes happen on {@code pd-deploy-worker} behind
 * whatever the queue already holds — see {@link DeployService#enqueueOperation}, which argues why
 * that is a correctness requirement rather than a convention. So the API answers <b>202</b> and the
 * deployment listing is where the result is read, exactly as it is for a build-succeeded event.
 *
 * <p><b>What it does to the row, and it is deliberately little.</b> A deployment row is the record
 * of an attempt to put one commit live, and none of that changes when somebody bounces the container
 * or stops it for an hour. So:
 *
 * <ul>
 *   <li><b>a restart writes no status at all</b> — it stamps the row's {@code detail} and nothing
 *       else. The id, the sha, the container name, the timestamps and the history are untouched,
 *       which is the difference between a bounce and a redeploy stated where a reader will see it;
 *   <li><b>a scale to zero writes {@link PdDeploymentStatus#SCALED_TO_ZERO}</b>, because a place
 *       that is deliberately empty is a fact about the platform an operator has to be able to read
 *       — and because leaving it {@code ACTIVE} would have the deployment listing claim a service
 *       that is stopped is serving;
 *   <li><b>a scale back up writes no status</b>. It stamps the row and leaves it {@code
 *       SCALED_TO_ZERO} for {@link DeploymentObserver} to promote once the tasks are actually
 *       healthy. Writing {@code ACTIVE} here would be this component claiming a health verdict it
 *       has not asked for; the observation is the one place that verdict is reached, and the
 *       recovery arm it reaches it through already exists.
 * </ul>
 *
 * <p><b>The stamp replaces the previous stamp rather than stacking.</b> An operator action is a
 * statement about now and a script hammering the door must not grow a text column without bound, so
 * a new stamp drops a leading one and keeps everything under it — which is the deployment's own
 * diagnosis, and the thing that must never be lost.
 *
 * <p><b>Nothing arriving over HTTP shapes an argv.</b> The caller names an application id; the
 * service name the update is issued against comes off the <b>deployment row</b> ({@code
 * container_name}, which under swarm is the service's name and therefore its address), the same
 * place {@link DeploymentObserver} takes it from and for the same reason: only the service a row
 * named may be acted on for that row. The replica count is an integer, bounded below.
 */
@ApplicationScoped
public class ApplicationScaling {

  private static final Logger LOG = Logger.getLogger(ApplicationScaling.class);

  /**
   * The largest replica count this door accepts.
   *
   * <p><b>One, and it is the platform's shape rather than an orchestrator's limit.</b> Swarm would
   * run any number; the applications here would not survive it. They bind host ports from inside the
   * task ({@code publish_mode: host} is the default and what every publishing service uses), several
   * are single-writer stores on one volume, and one holds a config volume — a second task is a
   * refused schedule or a corrupted cluster, not capacity. Raising it is a decision about those
   * applications, and it belongs in the same commit as whatever makes one of them able to run twice.
   */
  public static final int MAX_REPLICAS = 1;

  @Inject PdDeploymentRepository deployments;
  @Inject DeploymentDriver driver;
  @Inject DeployService worker;

  /**
   * What the door answers with: what it resolved, and what it queued. It is not an outcome —
   * nothing has been issued yet when this is returned.
   *
   * <p>{@code replicas} is null on a restart, which asks for no count.
   */
  public record Accepted(
      String applicationId,
      String applicationName,
      String environmentId,
      String serviceName,
      String deploymentId,
      Integer replicas) {}

  /** The place an action was resolved to, as plain values — never an entity past this line. */
  private record Placed(
      String applicationId,
      String applicationName,
      String environmentId,
      String serviceName,
      String deploymentId) {}

  /**
   * Ask the orchestrator to run {@code replicas} tasks of this application.
   *
   * @param applicationId the derived key, {@code <environmentId>:<name>} or {@code platform:<name>}
   * @param replicas 0 to stop the workload, 1 to bring it back — see {@link #MAX_REPLICAS}
   * @param actor who asked, for the row's stamp; null reads as "an operator"
   */
  public Accepted scale(String applicationId, int replicas, String actor) {
    if (replicas < 0) {
      throw new BadRequestException("replicas cannot be negative");
    }
    if (replicas > MAX_REPLICAS) {
      throw new BadRequestException(
          "replicas must be between 0 and "
              + MAX_REPLICAS
              + ": every application here is deployed as a single task, and a second one would"
              + " collide on a published host port or on a store with one writer");
    }
    Placed placed = resolve(applicationId);
    worker.enqueueOperation(
        "Scaling " + placed.serviceName() + " to " + replicas,
        () -> applyScale(placed, replicas, actor));
    return accepted(placed, replicas);
  }

  /**
   * Ask the orchestrator to replace the tasks running under this application's name, unchanged.
   *
   * @param actor who asked, for the row's stamp; null reads as "an operator"
   */
  public Accepted restart(String applicationId, String actor) {
    Placed placed = resolve(applicationId);
    worker.enqueueOperation(
        "Restarting " + placed.serviceName(), () -> applyRestart(placed, actor));
    return accepted(placed, null);
  }

  private static Accepted accepted(Placed placed, Integer replicas) {
    return new Accepted(
        placed.applicationId(),
        placed.applicationName(),
        placed.environmentId(),
        placed.serviceName(),
        placed.deploymentId(),
        replicas);
  }

  /**
   * The application id, turned into the row an action acts on — on the <b>request</b> thread, so a
   * caller learns about a bad id, an unknown application or a place nothing ever deployed as an
   * answer rather than as a log line under a 202.
   *
   * <p>Deliberately <b>not</b> wrapped in {@code PdReadPatience}: this read is part of a write, and
   * a write's patience lives one layer down. What it would buy — a scale surviving a database
   * outage — is not worth a request thread sleeping while the operator has no idea whether anything
   * was issued.
   */
  private Placed resolve(String applicationId) {
    ApplicationKeys.Key key =
        ApplicationKeys.parse(applicationId)
            .orElseThrow(
                () ->
                    new BadRequestException(
                        "'"
                            + applicationId
                            + "' is not an application id: it reads <environmentId>:<name>, or"
                            + " platform:<name> for an application on no tier"));
    // The name reaches a query and, through the row, a service name — checked where every other
    // stored identifier is, at the boundary.
    PdIdentifiers.requireName(key.applicationName(), "application name");
    Placed placed =
        QuarkusTransaction.joiningExisting()
            .call(
                () ->
                    deployments
                        .newestForPlace(key.applicationName(), key.environmentId())
                        .map(
                            row ->
                                new Placed(
                                    applicationId,
                                    row.applicationName,
                                    row.environmentId,
                                    row.containerName,
                                    row.id))
                        .orElse(null));
    if (placed == null) {
      throw new NotFoundException(
          "nothing has ever been deployed for " + applicationId + ", so there is nothing to run");
    }
    if (placed.serviceName() == null || placed.serviceName().isBlank()) {
      throw new ConflictException(
          "the newest deployment of "
              + applicationId
              + " never reached the orchestrator, so no service carries this application yet");
    }
    return placed;
  }

  // --- on the worker -----------------------------------------------------------------------------

  private void applyScale(Placed placed, int replicas, String actor) {
    DeploymentDriver.ScaleResult result = driver.scale(placed.serviceName(), replicas);
    if (!result.applied()) {
      LOG.warnf(
          "Could not scale %s to %d: %s", placed.serviceName(), replicas, result.detail());
      return;
    }
    Instant at = Instant.now();
    if (replicas == 0) {
      settle(
          placed,
          PdDeploymentStatus.SCALED_TO_ZERO,
          "[scaled to 0 by "
              + who(actor)
              + " at "
              + at
              + ": "
              + placed.serviceName()
              + " is declared to run no tasks, and the deployment is otherwise untouched]");
      return;
    }
    settle(
        placed,
        null,
        "[scaled to "
            + replicas
            + " by "
            + who(actor)
            + " at "
            + at
            + ": the observation settles this row when the tasks are healthy]");
  }

  private void applyRestart(Placed placed, String actor) {
    DeploymentDriver.ScaleResult result = driver.restart(placed.serviceName());
    if (!result.applied()) {
      LOG.warnf("Could not restart %s: %s", placed.serviceName(), result.detail());
      return;
    }
    settle(
        placed,
        null,
        "[restarted by "
            + who(actor)
            + " at "
            + Instant.now()
            + ": the tasks of "
            + placed.serviceName()
            + " were replaced, and this deployment is unchanged]");
  }

  /**
   * Write the stamp, and the status when the action has one to write.
   *
   * <p>{@code status} null means "stamp only", which is a restart and a scale back up. A {@code
   * QUEUED} or {@code STARTING} row keeps its status whatever the action was: those belong to the
   * worker's own state machine, a deployment is queued for this place behind us, and that deployment
   * is what will settle the row.
   *
   * <p>A {@link DbRetry#runInNewTx} bracket for the reason the observer's are: this is bookkeeping
   * that runs <i>after</i> a docker call, so losing it leaves the platform in a state no row admits
   * to. The docker call is outside it, never inside.
   */
  private void settle(Placed placed, PdDeploymentStatus status, String stamp) {
    DbRetry.runInNewTx(
        "The operator action on deployment " + placed.deploymentId(),
        () -> {
          PdDeployment row = deployments.findById(placed.deploymentId());
          if (row == null) {
            return; // torn down while the action ran
          }
          if (status != null
              && row.status != PdDeploymentStatus.QUEUED
              && row.status != PdDeploymentStatus.STARTING) {
            row.status = status;
          }
          row.detail = stamped(stamp, row.detail);
          // Flushed rather than left to the commit, so a lost connection is a body failure the
          // retry can safely repeat — the observer's own brackets say the same thing at length.
          deployments.flush();
        },
        CUTOVER_BUDGET);
    LOG.infof("%s on %s (deployment %s)", stamp, placed.serviceName(), placed.deploymentId());
  }

  /**
   * The new stamp over whatever the row already said, with a previous operator stamp dropped.
   *
   * <p>Package-private for its own test. The deployment's diagnosis is everything under the stamp
   * and is kept whole — eaa34fbc is the row where that text was the reason a bug was findable at
   * all — while a stamp is a statement about now and there is no reason to keep yesterday's.
   */
  static String stamped(String stamp, String detail) {
    String previous = withoutLeadingStamp(detail);
    return previous.isBlank() ? stamp : stamp + "\n" + previous;
  }

  private static String withoutLeadingStamp(String detail) {
    if (detail == null) {
      return "";
    }
    String trimmed = detail.stripLeading();
    if (!trimmed.startsWith("[scaled to ") && !trimmed.startsWith("[restarted by ")) {
      return detail;
    }
    int newline = trimmed.indexOf('\n');
    return newline < 0 ? "" : trimmed.substring(newline + 1);
  }

  private static String who(String actor) {
    return actor == null || actor.isBlank() ? "an operator" : actor;
  }
}
