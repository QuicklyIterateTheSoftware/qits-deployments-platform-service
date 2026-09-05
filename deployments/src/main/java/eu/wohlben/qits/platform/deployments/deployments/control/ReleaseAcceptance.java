package eu.wohlben.qits.platform.deployments.deployments.control;

import eu.wohlben.qits.db.DbRetry;
import eu.wohlben.qits.platform.deployments.deployments.entity.PdOwedRelease;
import eu.wohlben.qits.platform.deployments.deployments.persistence.PdOwedReleaseRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jboss.logging.Logger;

/**
 * <b>The acceptance ledger.</b> What this component has taken responsibility for deploying, and
 * whether it has finished.
 *
 * <h2>The hole this fills, which is not the one the bus already fills</h2>
 *
 * <p>qits-eventstream makes the door durable: a {@code SoftwareRelease} broadcast while this
 * component is down is read back out of qits-events' log by the catch-up sweep and handed to the
 * subscriber exactly once, and the claim in {@code consumed_event} commits with the handler. That
 * guarantee ends where the handler returns — and {@link ReleaseAnnouncements#announce} returns as
 * soon as the release is a {@code Runnable} on {@code pd-deploy-worker}. The queue is in memory,
 * deployments are serialized platform-wide so it is legitimately an hour deep, and {@code
 * DeployService}'s {@code @PreDestroy} calls {@code shutdownNow()} on it.
 *
 * <p>So a cutover of this component — which it performs on itself, {@code stop-first} — used to drop
 * every release in that queue while the bus's ledger said, correctly, that the event had been
 * handled. The successor's sweep found the claim, skipped the event, and no deployment request was
 * ever written. Seven applications' releases went that way on 2026-09-05; four were replayed by hand
 * and three were rescued only because a later release folded their tags.
 *
 * <h2>Why a second ledger rather than a longer transaction</h2>
 *
 * <p>Because there cannot be a longer transaction. {@code consumed_event} is in the {@code
 * eventstream} database and these rows are in {@code platformdeployments}; both datasources are
 * non-XA and Narayana refuses two of them in one transaction — the note {@link ReleaseTips} already
 * carries for its floor read. The claim and the obligation cannot commit together, so the ORDER is
 * chosen instead: <b>the obligation is written first, before anything is queued</b>. What that
 * leaves is a duplicate (obligation written, claim rolled back), never a loss — and a duplicate is
 * exactly what the re-drive guards in {@link OwedReleaseSweep} collapse.
 *
 * <h2>{@link #instanceId()}, which is the whole re-drive predicate</h2>
 *
 * <p>A uuid minted once per JVM. A row carrying it is on this process's worker queue and must not be
 * re-driven however long it sits there; a row carrying anybody else's belonged to a queue that did
 * not survive its JVM and is orphaned by definition. A time-based predicate cannot do this job: any
 * grace period longer than a legitimate hour-deep queue is longer than the outage it would be
 * covering.
 *
 * <p>An instance field rather than a static one, deliberately: {@code service/} compiles to a
 * GraalVM native image, and a static initializer is evaluated at build time and baked into the image
 * heap — every container of one image would then share one "process" id and no row would ever look
 * orphaned. An {@code @ApplicationScoped} bean is instantiated at runtime, so this is.
 *
 * <p>Every bracket here is a {@link DbRetry} in a transaction of its own. The callers are the bus
 * door (inside the library's claim transaction, which has the other datasource enlisted and must be
 * suspended) and the sweep's daemon thread (which has no transaction at all), and this component
 * deploys the postgres it is writing to.
 */
@ApplicationScoped
public class ReleaseAcceptance {

  private static final Logger LOG = Logger.getLogger(ReleaseAcceptance.class);

  /**
   * How many times one obligation may be taken up — the live acceptance and every re-drive after it.
   *
   * <p>It bounds the poison case the bus library warns about, moved one layer down: an obligation
   * that fails identically on every boot would otherwise be announced again at every boot forever.
   * Five is generous on purpose — a re-drive costs one queued deployment, and the failures worth
   * retrying (a git host that was down, a postgres mid-cutover) do not survive five restarts.
   */
  public static final int MAX_ATTEMPTS = 5;

  /** What became of an obligation. Stored as its name; see V10 on why there is no check constraint. */
  public enum Outcome {

    /** The worker read the spec, wrote the request rows and cut the container over. The happy word. */
    DISCHARGED,

    /**
     * A deployment request for this {@code (application, version)} already existed when the re-drive
     * looked. The obligation was met by the process that died holding it, or by the manual door.
     */
    ALREADY_REQUESTED,

    /**
     * A newer version of the same application has since been requested. Re-driving would be a
     * rollback nobody asked for — {@link ReleaseTips}' whole subject.
     */
    SUPERSEDED,

    /** {@link #MAX_ATTEMPTS} taken up without a discharge. Parked, and said out loud. */
    EXHAUSTED
  }

  /** One acceptance, in exactly the arguments {@link ReleaseAnnouncements#announce} takes. */
  public record Accepted(
      String eventId,
      String runId,
      RepositoryRef repository,
      String applicationName,
      String version,
      String packageName,
      UUID causationId) {}

  /** An obligation read back out of the ledger: what it is, and how often it has been tried. */
  public record Owed(String id, Accepted accepted, int attempts) {}

  @Inject PdOwedReleaseRepository owed;

  /**
   * This process, for the life of this process. See the class javadoc: it is the re-drive predicate,
   * and it is an instance field so a native image cannot bake one value into every container.
   */
  private final String instanceId = UUID.randomUUID().toString();

  public String instanceId() {
    return instanceId;
  }

  /**
   * Take responsibility for this release, and return the obligation's id.
   *
   * <p><b>Idempotent on the event id</b>, which is what makes a re-drive re-take the same row rather
   * than open a second one: an obligation cannot fan out across restarts however many times it is
   * announced. Every call — the live one and every re-drive — stamps this process as the holder and
   * counts an attempt, so the sweep's predicate and the attempt bound are both maintained here and
   * nowhere else.
   */
  public String accept(Accepted acceptance) {
    return DbRetry.inNewTx(
        "Accepting the release of "
            + acceptance.applicationName()
            + "@"
            + acceptance.version(),
        () -> {
          PdOwedRelease row = owed.findByEventId(acceptance.eventId()).orElse(null);
          if (row == null) {
            row = new PdOwedRelease();
            row.id = UUID.randomUUID().toString();
            row.eventId = acceptance.eventId();
            row.acceptedAt = Instant.now();
            row.applicationName = acceptance.applicationName();
            row.version = acceptance.version();
            row.packageName = acceptance.packageName();
            row.repoId = acceptance.repository().repoId();
            row.projectId = acceptance.repository().projectId();
            row.repoName = acceptance.repository().repoName();
            row.runId = acceptance.runId();
            row.causationId = acceptance.causationId();
            owed.persist(row);
          }
          row.acceptedBy = instanceId;
          row.attempts = row.attempts + 1;
          // The body's last statement, this repo's rule for every DbRetry.inNewTx: an ORM flushes
          // at commit, which would put every statement on the far side of the one round trip
          // nothing can place. Flushed, a lost connection is a BODY failure and is retried.
          owed.flush();
          return row.id;
        },
        DeployService.CUTOVER_BUDGET);
  }

  /** Discharge or park an obligation. Terminal: nothing re-drives a settled row. */
  public void settle(String id, Outcome outcome, String detail) {
    DbRetry.runInNewTx(
        "Settling the accepted release " + id,
        () ->
            owed.findByIdOptional(id)
                .ifPresent(
                    row -> {
                      row.settledAt = Instant.now();
                      row.outcome = outcome.name();
                      row.detail = detail;
                      owed.flush();
                    }),
        DeployService.CUTOVER_BUDGET);
  }

  /**
   * Hand an obligation back without settling it: this process took it up and could not discharge it.
   *
   * <p>The row stays owed and its holder becomes nobody, so <b>this</b> process's next sweep retries
   * it rather than leaving it for the next restart. That is the difference between a transient
   * failure costing a minute and costing a deployment.
   */
  public void release(String id, String detail) {
    DbRetry.runInNewTx(
        "Releasing the accepted release " + id,
        () ->
            owed.findByIdOptional(id)
                .ifPresent(
                    row -> {
                      row.acceptedBy = null;
                      row.detail = detail;
                      owed.flush();
                    }),
        DeployService.CUTOVER_BUDGET);
  }

  /**
   * Everything still owed that this process is not holding, oldest first — the sweep's whole input.
   *
   * <p>Read as plain values in a transaction of its own: the sweep announces each of these through
   * the ordinary door, which is a queue hop and a spec read away from any session this could keep
   * open.
   */
  public List<Owed> orphaned() {
    return DbRetry.inNewTx(
        "Reading the accepted releases nobody is holding",
        () -> owed.listOwedByOthers(instanceId).stream().map(ReleaseAcceptance::owedOf).toList(),
        DeployService.CUTOVER_BUDGET);
  }

  private static Owed owedOf(PdOwedRelease row) {
    return new Owed(
        row.id,
        new Accepted(
            row.eventId,
            row.runId,
            new RepositoryRef(row.repoId, row.projectId, row.repoName),
            row.applicationName,
            row.version,
            row.packageName,
            row.causationId),
        row.attempts);
  }

  /** Said once per parked obligation, at ERROR: nothing else will ever deploy that release. */
  void reportExhausted(Owed row) {
    LOG.errorf(
        "The release of %s@%s was accepted %d times and never discharged; it is parked and will"
            + " NOT deploy. Replay it through POST /platform-deployments/api/events/software-released",
        row.accepted().applicationName(), row.accepted().version(), row.attempts());
  }
}
