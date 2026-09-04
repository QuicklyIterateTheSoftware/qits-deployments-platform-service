package eu.wohlben.qits.platform.deployments.deployments.control;

import eu.wohlben.qits.db.DbRetry;
import eu.wohlben.qits.platform.deployments.deployments.control.SpecSource.DeploymentSpec;
import eu.wohlben.qits.platform.deployments.deployments.entity.PdDeployment;
import eu.wohlben.qits.platform.deployments.deployments.entity.PdDeploymentRequest;
import eu.wohlben.qits.platform.deployments.deployments.entity.PdDeploymentStatus;
import eu.wohlben.qits.platform.deployments.deployments.entity.PdQualityGate;
import eu.wohlben.qits.platform.deployments.deployments.persistence.PdDeploymentRepository;
import eu.wohlben.qits.platform.deployments.deployments.persistence.PdDeploymentRequestRepository;
import eu.wohlben.qits.platform.deployments.environments.control.ApplicationKeys;
import eu.wohlben.qits.platform.deployments.environments.control.EnvironmentService;
import eu.wohlben.qits.platform.deployments.environments.control.PdIdentifiers;
import eu.wohlben.qits.platform.deployments.environments.control.PdNetworks;
import eu.wohlben.qits.platform.deployments.environments.control.ServiceCatalog;
import eu.wohlben.qits.platform.deployments.environments.control.ServiceCatalog.LinkedService;
import eu.wohlben.qits.platform.deployments.environments.entity.PdDeploymentTarget;
import eu.wohlben.qits.platform.deployments.environments.entity.PdEnvironment;
import eu.wohlben.qits.platform.deployments.events.DeploymentActive;
import eu.wohlben.qits.platform.deployments.events.DeploymentEndpoint;
import eu.wohlben.qits.platform.deployments.events.DeploymentFailed;
import eu.wohlben.qits.platform.deployments.events.DeploymentQueued;
import eu.wohlben.qits.platform.deployments.events.DeploymentStarted;
import eu.wohlben.qits.platform.deployments.events.NavigationEntry;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.runtime.LaunchMode;
import io.quarkus.runtime.StartupEvent;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * The deployment orchestrator: a build-succeeded event → the repository's deployment spec at that
 * commit → the environments that spec addresses → one recorded deployment each, driven pull → run →
 * join → health gate → cutover on a single-threaded daemon worker (the intake returns immediately;
 * deployments across all environments are serialized — parallelism is an explicit follow-up, and
 * serial is what makes "the previous ACTIVE deployment" an uncontended read).
 *
 * <p><b>Registration is derived, and it is a local write.</b> Nothing declares an application over
 * the API. A release carries this component to {@code .config/qits/deployments.yml} in the
 * repository at that tag, and the service row is created or brought up to date from it: an {@code
 * environment} target is linked into the entry tier, a {@code platform} target keeps no links at
 * all and deploys once for the whole platform. A repository with no such file gets the defaults and
 * behaves exactly as it did before the file existed.
 *
 * <p><b>Both planes deploy INTO a tier, and the tier is the same one</b> — {@code
 * pd_environment.platform}, the designated entry environment. What makes the platform plane the
 * platform plane is stated by the spec ({@code deployment_target: platform}) and carried as {@link
 * Target#target()}, never inferred from a missing environment: the plane's deployments name the
 * main tier on the row, in the labels, in {@code QITS_ENVIRONMENT} and on all four events, and what
 * stays different is the <b>bare wire alias</b> and a membership in every environment's networks.
 *
 * <p><b>This is what the merge bought.</b> Registration and resolution used to be HTTP calls onto
 * qits-serviceregistry: a port, a {@code java.net.http} implementation, a stub server in the suite,
 * a bearer to mint, and a whole recorded-FAILED posture for the case where the peer was down —
 * because a deployer that cannot ask where a build belongs must never guess a topology. All of it
 * is gone. Registration writes rows in the same transaction as everything else here, resolution is
 * a repository query with an index behind it, and there is no outage to have a posture about. The
 * one remote call left is the spec read, and its posture stays exactly as it was.
 *
 * <p><b>The worker carries one thing that is not an event</b>: the periodic observation pass ({@link
 * DeploymentObserver}), enqueued by a ticker here and running in queue order like everything else.
 * That is the whole reason it may touch deployment rows at all — see {@link #enqueueObservation()}.
 *
 * <p>Each DB transition sits in its own {@link QuarkusTransaction#requiringNew()} bracket so the
 * slow docker work never holds a transaction, and everything the docker calls need is copied into a
 * plain {@link Plan} first — the worker thread has no request context and no open session.
 *
 * <p><b>Three of those brackets are wrapped in {@link DbRetry}</b> — the platform's, from
 * qits-db-core — because this component deploys the postgres its own registry lives in and a cutover
 * of qits-oci-postgresql kills the connections of the very deployment performing it: the catalogue
 * read an event opens with, the post-gate cutover bookkeeping, and {@link #finish}. Connection-class
 * failures only, and {@link #CUTOVER_BUDGET} only — the rest of the brackets are deliberately left
 * alone, and {@link #queue} says why.
 *
 * <p><b>Which of the two spellings each one takes is decided by who owns the transaction.</b> The
 * catalogue read is a {@link DbRetry#call} around a read that brackets itself; the two writes are
 * {@link DbRetry#inNewTx}, which <i>is</i> the {@code requiringNew} — the retry owning the boundary
 * is what lets it retry only attempts that certainly did not commit, and leaves the one undecidable
 * round trip (the commit acknowledgement) reported rather than repeated.
 *
 * <p><b>The cutover is the DRIVER's, and that is the shape this class settled into.</b> What is
 * left here is one path with no branches in it: resolve the target, provision what the repository
 * declared, pull so a missing image is its own outcome, {@link DeploymentDriver#apply} the spec,
 * {@link DeploymentDriver#awaitConverged wait for the verdict}, record it. A predecessor search, a
 * stop-before-start and a hand-rolled rollback used to sit in the middle of that, and each was a
 * statement about how one orchestrator replaces a container rather than about deploying. Nothing
 * about the sequence above is orchestrator-shaped, which is the test that it is in the right place.
 *
 * <p><b>What stayed is the bookkeeping</b>: the row per place, the four announcements, the cutover
 * bracket that decommissions the prior {@code ACTIVE} rows of an (application, tier) and marks this
 * one live, and the reap that follows it — rows first, services after, so a bracket that has to
 * retry for thirty seconds has not already removed the predecessor.
 *
 * <p><b>One outcome is neither success nor failure and is worth knowing about here</b>: a
 * deployment that replaces THIS process comes back {@link DeploymentDriver.ApplyOutcome#HANDED_OFF
 * HANDED_OFF}, and the row is deliberately left {@code STARTING}. Neither instance can arbitrate
 * its own succession, so the outcome is recorded by whichever survives: the next boot's {@link
 * #sweepInFlight() sweep} settles the row from the image the service is running.
 */
@ApplicationScoped
public class DeployService implements ReleaseAnnouncements {

  private static final Logger LOG = Logger.getLogger(DeployService.class);

  /** Every platform repository carries it, and no path segment does. */
  private static final String NAME_PREFIX = "qits-";

  /** The longer prefix a platform-tier repository carries; stripped before {@link #NAME_PREFIX}. */
  private static final String PLATFORM_NAME_PREFIX = "qits-platform-";

  /** What an in-flight row says when nothing is running under its name — see {@link #sweepInFlight()}. */
  private static final String INTERRUPTED = "[interrupted by a qits-platform-deployments restart]";

  /**
   * How long a self-inflicted blip may last before it is a failure worth recording — longer than
   * {@link DbRetry#DEFAULT_DEADLINE}, and stated at every call site here rather than taken from the
   * library, because this worker's outage is one it caused itself: it deploys the postgres its own
   * registry lives in, and the container has to come all the way back before the bracket can
   * succeed. Safe to sleep for, because the worker is single-threaded — nothing is queued behind a
   * lock and the next event simply waits. Do not lift it onto a request thread.
   *
   * <p>Package-private because {@link DeploymentObserver} runs on the same worker and wraps its
   * brackets for the same reason, and one budget spelled twice would drift.
   */
  static final Duration CUTOVER_BUDGET = Duration.ofSeconds(30);

  /**
   * How hard the startup sweep tries to read a spec it should not have needed — see {@link
   * #legacyEndpoints}. Deliberately a constant and not a config key: it applies to rows queued
   * before V3 and there will never be another one, so a knob would outlive everything it tunes.
   */
  private static final int ADOPTION_SPEC_ATTEMPTS = 3;

  private static final Duration ADOPTION_SPEC_RETRY = Duration.ofSeconds(2);

  @Inject PdDeploymentRepository deployments;
  @Inject PdDeploymentRequestRepository requests;
  @Inject DeploymentDriver driver;
  @Inject SpecSource specs;
  @Inject ServiceCatalog catalog;
  @Inject EnvironmentService environments;
  @Inject ResourceProvisioning resourceProvisioning;
  @Inject DeploymentObserver observer;

  /**
   * Where this component's own events leave it ({@link DeployAnnouncer}). An {@code Instance}
   * because zero implementations is a supported configuration: a build without the bus deploys
   * exactly as before and says nothing to anybody.
   */
  @Inject Instance<DeployAnnouncer> announcers;

  @ConfigProperty(name = "qits.artifacts.registry-host")
  String registryHost;

  @ConfigProperty(name = "qits.artifacts.image-repository")
  String imageRepository;

  /**
   * The last resort at deploy time, for a service row that carries no path. Registration writes
   * {@link #conventionHealthPath} now, so this only reaches rows nothing has registered since.
   */
  @ConfigProperty(name = "qits.platform.deployments.default-health-path")
  String defaultHealthPath;

  @ConfigProperty(name = "qits.platform.deployments.health-timeout-seconds")
  long healthTimeoutSeconds;

  /** How often the observation pass is enqueued; {@code 0} switches the observer off entirely. */
  @ConfigProperty(name = "qits.platform.deployments.observe-interval-seconds")
  long observeIntervalSeconds;

  /**
   * The network every fresh container additionally joins while the platform still holds direct
   * cross-application URLs. Emptying it is the enforcement flip: from then on an application can
   * only be reached through the gateway route or a hub join, and a URL nobody migrated fails loudly
   * instead of resolving on a flat network.
   *
   * <p>{@code Optional} because SmallRye reads an empty value as ABSENT, not as an empty string —
   * so the flip's own spelling ({@code QITS_PLATFORM_DEPLOYMENTS_LEGACY_NETWORK=}) would fail this
   * bean's injection if the field were a plain String.
   */
  @ConfigProperty(name = "qits.platform.deployments.legacy-network")
  Optional<String> legacyNetwork;

  private final ExecutorService worker =
      Executors.newSingleThreadExecutor(
          r -> {
            Thread t = new Thread(r, "pd-deploy-worker");
            t.setDaemon(true);
            return t;
          });

  /**
   * At most one observation pass is pending behind the deploy queue at a time. A tick that fires
   * while one is already waiting collapses into it: an observation is a statement about NOW, so
   * stacking ten of them behind a long deploy queue would only re-answer a question the first one is
   * about to answer.
   */
  private final AtomicBoolean observationPending = new AtomicBoolean();

  /**
   * The observation tick — a bare daemon thread, the worker's own shape, rather than the
   * quarkus-scheduler extension. It has one job (submit a runnable every n seconds), it must not run
   * the pass itself, and a scheduler extension would add a managed thread pool and a second
   * concurrency model to a component whose whole ordering story is "one worker, in queue order".
   */
  private volatile Thread observerTicker;

  @PreDestroy
  void shutdown() {
    Thread ticker = observerTicker;
    if (ticker != null) {
      ticker.interrupt();
    }
    worker.shutdownNow();
  }

  /**
   * A deployment left {@code QUEUED} or {@code STARTING} by a crash can never make progress — the
   * worker queue does not survive the JVM — so it would show as forever-deploying. Settle those
   * once at startup, from what the runtime says: see {@link #sweepInFlight()}. The
   * containers are deliberately NOT reaped: a deployed application outlives its deployer, and
   * whatever was {@code ACTIVE} before the restart is still serving.
   */
  void onStart(@Observes StartupEvent event) {
    if (LaunchMode.current() == LaunchMode.TEST) {
      return;
    }
    try {
      sweepInFlight();
    } catch (RuntimeException e) {
      LOG.warnf(e, "Could not sweep interrupted deployments at startup");
    }
    startObserving();
  }

  /**
   * Start the periodic tick that keeps deployment rows honest ({@link DeploymentObserver}).
   *
   * <p>Nothing here in test mode, for the same reason the sweep is skipped: {@code onStart} returns
   * before this on a {@code LaunchMode.TEST} boot, so no {@code @QuarkusTest} has a ticker running
   * behind it and the suite's observation tests drive {@link #enqueueObservation()} themselves. That
   * is also why the interval keeps its shipped default in the suite — a test-resource override would
   * be re-declaring an app-level setting to disable something that never starts.
   */
  private void startObserving() {
    if (observeIntervalSeconds <= 0) {
      LOG.info(
          "Deployment observation is off (qits.platform.deployments.observe-interval-seconds=0):"
              + " a row's status will be whatever the deployment that wrote it said");
      return;
    }
    Thread ticker =
        new Thread(
            () -> {
              while (!Thread.currentThread().isInterrupted()) {
                try {
                  Thread.sleep(Duration.ofSeconds(observeIntervalSeconds).toMillis());
                } catch (InterruptedException e) {
                  Thread.currentThread().interrupt();
                  return;
                }
                enqueueObservation();
              }
            },
            "pd-observation-ticker");
    ticker.setDaemon(true);
    observerTicker = ticker;
    ticker.start();
    LOG.infof("Observing deployment rows every %ds on the deploy worker", observeIntervalSeconds);
  }

  /**
   * Queue one observation pass <b>on the deploy worker</b>. Package-private so the suite drives a
   * pass without waiting on the tick.
   *
   * <p>The placement is the concurrency contract, not a convenience. Serial execution is what makes
   * "the previous ACTIVE deployment" an uncontended read during a cutover, and an observer thread
   * reading and writing those same rows while a cutover runs would take that invariant away — it
   * would see the half-written state between a cutover's own brackets and could demote a row a
   * deployment is in the middle of promoting. On the worker there is no middle: the pass runs between
   * events, never inside one.
   */
  void enqueueObservation() {
    if (!observationPending.compareAndSet(false, true)) {
      LOG.debugf("An observation pass is already queued; this tick collapses into it");
      return;
    }
    worker.submit(
        () -> {
          // Cleared as the pass BEGINS, not when it ends: a tick arriving during a long pass may
          // queue the next one, so the queue holds at most one pending pass plus the running one.
          observationPending.set(false);
          try {
            observer.observeOnce();
          } catch (RuntimeException e) {
            LOG.warnf(e, "The deployment observation pass failed; the next tick tries again");
          }
        });
  }

  /**
   * Queue an <b>operator's</b> action on the deploy worker — a scale, a bounce — behind whatever the
   * queue already holds. Package-private: {@link ApplicationScaling} is the only caller, and it is
   * in this package for exactly that reason.
   *
   * <p><b>The placement is a correctness requirement, not tidiness.</b> A scale and a bounce are
   * both {@code docker service update} on a service a deployment may be cutting over this second,
   * and two concurrent updates of one service is the least of it: swarm's {@code UpdateStatus} holds
   * the most recent update, so an operator's restart issued from a request thread mid-cutover would
   * be the status {@link DeploymentDriver#awaitConverged} reads as that deployment's verdict. On the
   * worker there is no mid-cutover — the action runs between events, never inside one, which is the
   * same guarantee {@link #enqueueObservation()} takes.
   *
   * <p>Unlike an observation, two of these never collapse: they are two things a person asked for.
   *
   * @param description what a failure names in the log — the action, not the outcome
   */
  void enqueueOperation(String description, Runnable action) {
    worker.submit(
        () -> {
          try {
            action.run();
          } catch (RuntimeException e) {
            LOG.warnf(e, "%s failed", description);
          }
        });
  }

  /**
   * Settle every row a previous process left in flight, from what the runtime is running now.
   * Package-private so the suite drives the sweep without a real StartupEvent.
   *
   * <p><b>This is the self-update bookkeeping, and it is the only part of it left.</b> An instance
   * deploying itself hands the succession to the orchestrator and dies with the row {@code
   * STARTING}; whichever instance boots next has to say what became of it. The question is asked of
   * the deployment rather than of the deployer — {@link DeploymentDriver#runningImage} on the name
   * the row itself carries — which is why it is one arm for both orchestrators and for every
   * application, not a special case for this one.
   *
   * <table>
   *   <caption>What settles a row, and on what evidence</caption>
   *   <tr><th>row</th><th>the runtime says</th><th>verdict</th></tr>
   *   <tr><td>{@code QUEUED}, or {@code STARTING} with no name</td><td>nothing was asked</td>
   *       <td>{@code FAILED}: nothing ever ran, and the queue did not survive</td></tr>
   *   <tr><td>{@code STARTING}</td><td>no such service</td>
   *       <td>{@code FAILED}: it was interrupted, or the orchestrator took it away</td></tr>
   *   <tr><td>{@code STARTING}</td><td>an image carrying this row's sha</td>
   *       <td>{@code ACTIVE}, prior actives of the place decommissioned</td></tr>
   *   <tr><td>{@code STARTING}</td><td>an image carrying another sha</td>
   *       <td>{@code SUPERSEDED}: a rollback, or a later deployment, took the place</td></tr>
   * </table>
   *
   * <p><b>The last two rows used to be one word, and telling them apart is the point.</b> An
   * interrupted row with no evidence leaves nobody known to be serving, which is {@code FAILED};
   * a row whose place a newer sha is already serving is {@code SUPERSEDED}, and nothing is owed
   * about it. The sweep is the only writer of that word.
   *
   * <p><b>An adopted row is not a claim that the gate passed</b>, and it does not need to be: on
   * what carries the row's image is what is serving under the row's name, and a
   * container that is in fact dying is demoted by {@link DeploymentObserver} on the next two
   * passes. The prior actives are matched with an explicit null test for the platform plane
   * ({@code environment_id = ?} would silently match nothing, and a self-updated instance would
   * come back having failed its own deployment).
   *
   * <p><b>An adoption ANNOUNCES, and it is the only correction here that does.</b> {@code
   * DeploymentActive} carries the routing snapshot the platform edge projects its route table from,
   * so an adopted deployment that said nothing is an application the edge never learns a route for —
   * and the one application that reaches {@code ACTIVE} through this sweep every single time is
   * <b>this one</b>, whose self-update is handed to the orchestrator and never announces from {@link
   * #execute}. That cost it its own route and its own navigation entry: the deployer's UI was
   * unreachable and absent from the menu on every platform, while every other application announced
   * normally. {@link DeploymentObserver}'s later corrections still announce nothing — a demotion and
   * a recovery restate an outcome hours later and a consumer would have to know the second statement
   * supersedes the first — but an adoption is not a correction: it is the FIRST statement anybody
   * makes about a deployment that went live, made by the instance that lived to make it.
   *
   * <p><b>It announces from the ROW and reaches no peer</b> — V3's routing columns, written at queue
   * time — because this runs at boot and the boot in question is the one right after a cutover. See
   * {@link #announceAdopted}; and a snapshot that cannot be established announces <b>nothing</b>
   * rather than an empty one, because consumers replace rather than merge and an empty snapshot
   * deletes the routes it could not describe.
   *
   * <p>The shape is the observation pass's: read the rows in one transaction, ask the runtime
   * outside every one of them — a driver call is a child process, and no bracket of this
   * component's own may span one — then write each row in a bracket of its own. The announcement
   * follows that write, the way every other announcement here follows the transaction that made it
   * true.
   */
  void sweepInFlight() {
    List<InFlight> rows = QuarkusTransaction.requiringNew().call(this::inFlight);
    int adopted = 0;
    int settled = 0;
    for (InFlight row : rows) {
      Verdict verdict = verdictOn(row);
      Instant recordedAt = QuarkusTransaction.requiringNew().call(() -> record(row, verdict));
      if (verdict.adopt()) {
        adopted++;
        LOG.infof(
            "Adopted deployment %s at startup: %s is running its image",
            row.deploymentId(), row.containerName());
        if (recordedAt != null) {
          announceAdopted(row, recordedAt);
        }
      } else {
        settled++;
      }
    }
    if (settled > 0) {
      LOG.infof(
          "Settled %d deployment(s) left in flight by a previous shutdown — SUPERSEDED where a"
              + " newer sha is serving the place, FAILED where nothing is",
          settled);
    }
    if (adopted > 0) {
      LOG.infof("Adopted %d deployment(s) the previous instance could not record", adopted);
    }
  }

  /**
   * One in-flight row as plain values — nothing carries an entity across a driver call.
   *
   * <p>Everything from {@code runId} down is carried for the adoption's announcement alone: it is a
   * {@code DeploymentActive} like any other and owes the same pointer back to the build, the same
   * trace edge and the same routing snapshot. They are read here rather than re-read later for the
   * reason everything else here is — the announcement happens outside every transaction this sweep
   * opens, and {@link #announceAdopted} must not need one.
   *
   * <p>{@code upstreamPort} is the snapshot's sentinel: null is a row queued before V3 added the
   * columns, not a row without a port. See {@link #adoptedSnapshot}.
   *
   * <p>The last two are V3's retired pair, carried for one reason: a row queued before V4 has a
   * label and no entries, and the sweep announces it as {@code system.<label>} rather than as
   * nothing. Nothing writes them.
   */
  private record InFlight(
      String deploymentId,
      String applicationName,
      String environmentId,
      PdDeploymentTarget target,
      PdDeploymentStatus status,
      String containerName,
      String imageTag,
      String commitSha,
      String runId,
      UUID causationId,
      String routes,
      Integer upstreamPort,
      String browserHost,
      String navigationEntries,
      String apiDocs,
      String navigationLabel,
      Integer navigationPosition) {}

  /**
   * What settles this row: the status to write, and the detail that argues it. It carries the word
   * rather than a boolean because the sweep has three answers now — adopt, superseded, failed — and
   * a boolean could only spell two of them.
   */
  private record Verdict(PdDeploymentStatus status, String detail) {

    /** Adoption is the one verdict with bookkeeping of its own: the prior actives of the place. */
    boolean adopt() {
      return status == PdDeploymentStatus.ACTIVE;
    }
  }

  private List<InFlight> inFlight() {
    List<PdDeployment> rows = new ArrayList<>(deployments.listByStatus(PdDeploymentStatus.QUEUED));
    rows.addAll(deployments.listByStatus(PdDeploymentStatus.STARTING));
    return rows.stream()
        .map(
            row ->
                new InFlight(
                    row.id,
                    row.applicationName,
                    row.environmentId,
                    // The plane, carried out with the tier: the alias this sweep rebuilds for an
                    // adopted deployment's routes is bare on the platform plane and qualified in a
                    // tier, and both kinds of row name a tier now.
                    row.deploymentTarget,
                    row.status,
                    row.containerName,
                    row.imageTag(),
                    row.commitSha,
                    row.runId,
                    row.causationId,
                    row.routes,
                    row.upstreamPort,
                    row.browserHost,
                    row.navigationEntries,
                    row.apiDocs,
                    row.navigationLabel,
                    row.navigationPosition))
        .toList();
  }

  /** The decision table above, asked of the runtime. */
  private Verdict verdictOn(InFlight row) {
    if (row.status() != PdDeploymentStatus.STARTING || row.containerName() == null) {
      return new Verdict(PdDeploymentStatus.FAILED, INTERRUPTED);
    }
    DeploymentDriver.RunningImage running = driver.runningImage(row.containerName()).orElse(null);
    if (running == null) {
      return new Verdict(PdDeploymentStatus.FAILED, INTERRUPTED);
    }
    // The VERSION, through the row's own coalesce: a row written before V7 is tagged with a sha
    // and still has to adopt. Getting this wrong makes a self-updated instance come back SUPERSEDED
    // by its own successor.
    if (ImageRefs.carries(running.imageRef(), row.imageTag())) {
      return new Verdict(
          PdDeploymentStatus.ACTIVE,
          "[adopted at startup: " + row.containerName() + " is running this deployment]");
    }
    return new Verdict(
        PdDeploymentStatus.SUPERSEDED,
        "[superseded: "
            + row.containerName()
            + " runs "
            + running.imageRef()
            + ", which is another deployment"
            + (running.detail() == null || running.detail().isBlank()
                ? ""
                : "; " + firstLine(running.detail()))
            + "]");
  }

  /**
   * @return the {@code finished_at} this wrote, which an adoption announces as its {@code
   *     occurredAt}, or null when there was no row left to write — the same "no row, no event" rule
   *     {@link #finish} states.
   */
  private Instant record(InFlight row, Verdict verdict) {
    PdDeployment deployment = deployments.findById(row.deploymentId());
    if (deployment == null) {
      return null; // the environment was torn down between the read and this write
    }
    if (verdict.adopt()) {
      for (PdDeployment previous :
          deployments.listActiveByApplication(row.applicationName(), row.environmentId())) {
        previous.status = PdDeploymentStatus.DECOMMISSIONED;
        previous.finishedAt = Instant.now();
      }
    }
    deployment.status = verdict.status();
    deployment.detail = verdict.detail();
    deployment.finishedAt = Instant.now();
    return deployment.finishedAt;
  }

  /**
   * The adopted deployment's {@code DeploymentActive}, made by the instance that survived the
   * succession — see {@link #sweepInFlight()} for why this one correction speaks.
   *
   * <p><b>The routing snapshot comes off the ROW</b> (V3's columns, written at queue time from the
   * same {@code Target} the deployment was performed with), so this reaches no peer at all. That is
   * the whole point of storing it: this runs at BOOT, and the boot in question is the one right
   * after a cutover — asking the git host to re-read a file here would make the deployer's own route
   * depend on another service answering during the seconds it is coming up, with nothing to retry,
   * because the row settles {@code ACTIVE} and no later sweep asks again. The only local read left
   * is the tier's name, which is this component's own database.
   *
   * <p><b>What cannot be rebuilt announces NOTHING</b>, and that asymmetry is the whole care in this
   * method. A consumer replaces its snapshot rather than merging, so an event carrying an empty
   * endpoint list DELETES an application's routes — which is a strictly worse answer than silence
   * when the truth is "the tier is gone" or "this row never recorded its routing". A repository that
   * genuinely declares no routes is a different thing and does announce its empty snapshot.
   *
   * <p><b>An adoption is not a claim that the health gate passed</b> — {@link #sweepInFlight()} says
   * so about the row, and the event inherits it. It costs nothing here: an endpoint's host is the
   * wire ALIAS, which is the service's own address and is the same whichever task is answering it,
   * so a route announced for a deployment that turns out to be sick points where a route for it
   * would have pointed anyway. {@link DeploymentObserver} demotes the row two passes later, and the
   * routes are the next deployment's to replace.
   */
  private void announceAdopted(InFlight row, Instant finishedAt) {
    String environmentName;
    try {
      environmentName =
          row.environmentId() == null ? null : environments.require(row.environmentId()).name;
    } catch (RuntimeException gone) {
      LOG.warnf(
          "Adopted deployment %s names environment %s, which is no longer there; its routes are"
              + " announced by nobody",
          row.deploymentId(), row.environmentId());
      return;
    }
    Snapshot snapshot = adoptedSnapshot(row, environmentName);
    if (snapshot == null) {
      return; // said why at the point it decided
    }
    announce(
        row.deploymentId(),
        announcer ->
            announcer.onActive(
                new DeploymentActive(
                    row.deploymentId(),
                    row.applicationName(),
                    row.environmentId(),
                    environmentName,
                    row.imageTag(),
                    row.commitSha(),
                    row.runId(),
                    row.containerName(),
                    finishedAt,
                    snapshot.browserHost(),
                    snapshot.apiDocsPath(),
                    snapshot.navigation(),
                    snapshot.endpoints()),
                row.causationId()));
  }

  /**
   * Everything {@code DeploymentActive} says about where an application is reached: the routes, the
   * host they are also served at, and where it asks to appear. One value because the three are one
   * statement — a consumer replaces them together or not at all.
   */
  private record Snapshot(
      List<DeploymentEndpoint> endpoints,
      String browserHost,
      String apiDocsPath,
      List<NavigationEntry> navigation) {}

  /**
   * {@link Plan#endpoints()} for a row this process did not deploy: the same three rules — the host
   * is the wire alias, one endpoint per declared route, navigation on the primary one — over the
   * snapshot the row carries instead of over a live {@code Target}.
   *
   * <p><b>A null return is "announce nothing"</b>, and it has exactly one cause: a row queued before
   * V3 added the columns, which cannot say what it routed. See {@link #legacySnapshot} for what is
   * done about those and why it is bounded. An empty LIST is the other answer entirely — an
   * application that declares no public routes — and it is announced.
   *
   * <p><b>A row queued between V3 and V4 carries a navigation LABEL and no entries</b>, and it is
   * announced as {@code system.<label>} with no host — the placement a flat menu's option always
   * had, and the statement that row was written to make. Nothing is derived for it: a host it never
   * asked for would put an application on a vhost the release it was queued by never promised.
   */
  private Snapshot adoptedSnapshot(InFlight row, String environmentName) {
    if (row.upstreamPort() == null) {
      return legacySnapshot(row, environmentName);
    }
    List<NavigationEntry> navigation = DeploymentSpecParser.parseEntries(row.navigationEntries());
    if (navigation.isEmpty() && row.navigationLabel() != null) {
      navigation =
          List.of(new NavigationEntry("system", row.navigationLabel(), row.navigationPosition()));
    }
    return new Snapshot(
        resolveEndpoints(
            splitRoutes(row.routes()),
            PdNetworks.alias(row.target(), environmentName, row.applicationName()),
            row.upstreamPort()),
        row.browserHost(),
        row.apiDocs(),
        navigation);
  }

  /**
   * The one deployment that has no snapshot to read is the one that SHIPS the snapshot: the old
   * build queued its row, this build's successor swept it, and V3's columns were null the whole
   * time. So this reads the spec at the row's sha after all — the transitional path, and it exists
   * for exactly one deployment per platform, ever.
   *
   * <p><b>It is patient, because it is the one moment the network can cost the deployer its own
   * route.</b> The read is this component's single outbound call and it runs at boot, seconds after
   * a cutover; the git host is up on any ordinary platform and briefly unreachable is exactly the
   * case worth surviving, so it is attempted {@link #ADOPTION_SPEC_ATTEMPTS} times a couple of
   * seconds apart. The sleeps are safe here and nowhere else in this class: the sweep runs before
   * the worker is doing anything and nothing is queued behind it.
   *
   * <p>Exhausted, it announces nothing rather than an empty snapshot — deleting routes it could not
   * describe would be worse than leaving them — and the recovery is the application's next
   * deployment, which will carry the columns and never come back here.
   */
  private Snapshot legacySnapshot(InFlight row, String environmentName) {
    for (int attempt = 1; attempt <= ADOPTION_SPEC_ATTEMPTS; attempt++) {
      try {
        // Id-addressed on purpose: a row carries the application NAME and no storage id, and this
        // path only ever runs for deployments queued before V3's routing columns existed — which
        // is to say before the identity rollback, when the id and the name were the same string.
        // A SHA rev, not a tag: this path runs only for rows queued before V3's routing columns
        // existed, which is to say long before a version was the coordinate.
        DeploymentSpec spec =
            specs.read(RepositoryRef.ofId(row.applicationName()), row.commitSha()).spec();
        LOG.infof(
            "Adopted deployment %s predates the routing columns; its snapshot was read from the"
                + " spec of %s@%s",
            row.deploymentId(), row.applicationName(), row.commitSha());
        return new Snapshot(
            resolveEndpoints(
                spec.routes(),
                PdNetworks.alias(row.target(), environmentName, row.applicationName()),
                spec.upstreamPort()),
            browserHost(row.applicationName(), spec),
            spec.apiDocs(),
            spec.navigationEntries());
      } catch (RuntimeException unreadable) {
        LOG.warnf(
            "Could not read the deployment spec of %s@%s while adopting %s (attempt %d of %d): %s",
            row.applicationName(),
            row.commitSha(),
            row.deploymentId(),
            attempt,
            ADOPTION_SPEC_ATTEMPTS,
            unreadable.getMessage());
        if (attempt < ADOPTION_SPEC_ATTEMPTS && !pause(ADOPTION_SPEC_RETRY)) {
          break;
        }
      }
    }
    LOG.warnf(
        "The routing of adopted deployment %s could not be established, so it is announced by"
            + " nobody rather than as an empty snapshot; %s keeps whatever routes it has until its"
            + " next deployment",
        row.deploymentId(), row.applicationName());
    return null;
  }

  /** One endpoint per route, the host resolved once. */
  private static List<DeploymentEndpoint> resolveEndpoints(
      List<String> routes, String upstreamHost, int upstreamPort) {
    List<DeploymentEndpoint> endpoints = new ArrayList<>();
    for (String route : routes) {
      endpoints.add(new DeploymentEndpoint(route, upstreamHost, upstreamPort));
    }
    return List.copyOf(endpoints);
  }

  /**
   * The DNS label an application is served at, resolved <b>here</b> because this is the first place
   * that holds both the spec and the application's name — the {@code ResourceProvisioning.resolve}
   * arrangement, applied to the other value the parser cannot derive.
   *
   * <p>Null is the answer for every application that asked for no host of its own, and that is the
   * common case: a file carrying the retired {@code navigation} key alone keeps being reached under
   * its path prefix, exactly as it was before hosts existed. A file that named {@code host} or
   * {@code navigation-entries} gets one — its own label where it stated one, and otherwise the
   * name without its platform prefix ({@code qits-ci} → {@code ci}, {@code qits-platform-events} →
   * {@code events}).
   */
  static String browserHost(String applicationName, DeploymentSpec spec) {
    if (!spec.browserHostDeclared()) {
      return null;
    }
    if (spec.host() != null) {
      return spec.host();
    }
    String name = applicationName == null ? "" : applicationName;
    if (name.startsWith(PLATFORM_NAME_PREFIX)) {
      return name.substring(PLATFORM_NAME_PREFIX.length());
    }
    return name.startsWith(NAME_PREFIX) ? name.substring(NAME_PREFIX.length()) : name;
  }

  /**
   * The stored spelling back into the list it was joined from. Blank is no routes — the answer most
   * applications give — and never a one-element list containing nothing, which would be a route.
   */
  private static List<String> splitRoutes(String stored) {
    if (stored == null || stored.isBlank()) {
      return List.of();
    }
    return Arrays.stream(stored.split(","))
        .map(String::strip)
        .filter(route -> !route.isEmpty())
        .toList();
  }

  /** @return false when the wait was interrupted, which ends the retry rather than ignoring it. */
  private static boolean pause(Duration duration) {
    try {
      Thread.sleep(duration);
      return true;
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      return false;
    }
  }

  /**
   * The async entry every announcement door calls ({@link ReleaseAnnouncements}). It validates,
   * hands the release to the worker and returns — the sender is fire-and-forget and has nothing to
   * do with the answer.
   *
   * <p><b>The whole event runs on the worker, registration included</b>, and that placement is the
   * concurrency contract rather than a detail. Derived registration is a read-then-write — "what
   * does the catalogue hold for this service, and what should it hold now" — and two green builds
   * of one repository arriving together would each read the old state and each write a link set
   * computed from it. The worker is single-threaded, so putting the read and the write on it is
   * what makes the pair atomic against every other event — the same reason the cutover lives there,
   * applied to the rows instead of the containers. ({@code ServiceCatalog.upsert} is synchronized
   * as the belt for every other caller.)
   *
   * <p>{@code runId} is optional and is recorded on every row this queues, verbatim: it is the only
   * pointer from a deployment back to the run that produced it, and it is resolved against nothing
   * — a reader takes it to qits-ci. A {@code SoftwareRelease} carries none, so the bus door passes
   * null and the row honestly names no build. The pair that actually drives the deployment is
   * (application, version).
   *
   * <p><b>The application name arrives by VALUE and is not derived from the repository here.</b> A
   * release names a package ({@code qits/qits-ci}) and no repository name at all, so the caller has
   * already taken the image path out of it ({@link PackageNames}). Everything below takes that
   * string: the catalogue key, the {@link Target}, the health path, the provisioned database and
   * role, the wire alias, the container name and the image reference. The {@link RepositoryRef} is
   * carried alongside for one purpose only — the spec read, which addresses the repository rather
   * than the application. Passing a repository id where an application name is meant is the
   * regression to watch for: it would tag images {@code qits/<uuid>:<version>} while every pipeline
   * publishes {@code qits/<name>}.
   *
   * <p><b>The application name can still be OVERRIDDEN one step later</b>, in {@link #deploy}, by
   * {@code application:} in the file this has not read yet. Absent — every file today — the
   * released package's name is the whole answer and this paragraph describes both.
   *
   * <p><b>{@code causationId} is carried by value from here on, and that is the whole reason it is
   * a parameter.</b> {@code CausationScope} is a ThreadLocal: the door's scope — the frame's for the
   * bus, the request filter's restored one for the HTTP intake — stands on THIS thread and is gone
   * the instant the lambda below runs somewhere else. So the cause travels the way {@code runId}
   * always has, as data, and the rows this event writes set it explicitly rather than hoping a
   * listener finds a scope that is not there. It is validated by nothing: an announcement is never
   * refused over a trace column.
   */
  @Override
  public void announce(
      String runId,
      RepositoryRef repository,
      String applicationName,
      String version,
      String packageName,
      UUID causationId) {
    DeploymentIdentifiers.requireRunId(runId);
    RepositoryRef repo = repository.validated();
    String released = DeploymentIdentifiers.requireApplicationName(applicationName);
    DeploymentIdentifiers.requireVersion(version);
    worker.submit(
        () -> {
          try {
            deploy(runId, repo, released, version, packageName, causationId);
          } catch (RuntimeException e) {
            LOG.errorf(
                e,
                "The software-release event for %s@%s could not be handled",
                released,
                version);
          }
        });
  }

  /**
   * One place this build deploys to: one application in one tier, or the platform plane. Resolved
   * before anything is queued and carried by value from there on — the docker work must not need a
   * second query to know where it is going.
   *
   * <p>{@code healthCmd}, {@code resources}, routing and the orchestrator options are the
   * spec's, and are <b>the only fields here no row holds</b>. They need none: the spec is read
   * fresh from the repository before every deployment, and the one path that resolves targets from
   * the catalogue instead ({@link #alreadyRegistered}) records a failure and deploys nothing. Null
   * is the HTTP probe over {@code healthPath}; an empty resource list is every application that
   * stores nothing; the default order is {@code start-first} and the default publish mode is
   * {@code host}.
   */
  record Target(
      String applicationName,
      String environmentId,
      String environmentName,
      String bundleNetwork,
      PdDeploymentTarget target,
      boolean availableOnEnv,
      String healthPath,
      String healthCmd,
      List<ResourceProvisioning.Resolved> resources,
      DeploymentDriver.UpdateOrder updateOrder,
      DeploymentDriver.PublishMode publishMode,
      List<String> routes,
      int upstreamPort,
      String browserHost,
      List<NavigationEntry> navigation,
      String apiDocs) {}

  /**
   * One software-release event, start to finish, on the worker thread: read what the repository
   * declares at the released tag, bring the catalogue up to date with it, record what each place
   * was asked for, and deploy it.
   *
   * <p>The spec read comes first because it decides <b>which places exist</b> — there is nothing to
   * request or queue until it has answered. A read that fails (the git host is down, the file does not parse)
   * does not guess: the places this repository is already registered in each get a recorded {@code
   * FAILED} deployment naming the cause, and a repository with nothing registered gets nothing,
   * exactly as an unknown repository always has.
   *
   * <p><b>The spec read is also where the application name can stop being the repository's</b>, and
   * this is the only place that substitution happens. {@code application:} in the file names what
   * this repository deploys AS, and from the line below every derivation takes that string: the
   * catalogue key, the {@link Target}, the wire alias, the container name, the image reference, the
   * provisioned database and role, the derived host, the extras family and every event this
   * deployment publishes. Absent — which is every file that exists today — the name is the
   * announcement's own and nothing about this method changed. The key exists so a repository can be
   * RENAMED without moving anything that runs: {@code qits-ci-service} declaring {@code
   * application: qits-ci} deploys exactly what {@code qits-ci} deployed, down to the image tag its
   * pipeline still pushes.
   *
   * <p><b>An unreadable spec keeps the repository's own name, and it has to.</b> The override lives
   * in the file that could not be read, so the failure rows go to the places registered under the
   * announcement's name — which for a repository that has been renamed AND overrides is no place at
   * all. That is the honest answer rather than a gap: nothing here knows which application a file it
   * could not read was speaking for, and guessing would record a failure against somebody else's.
   */
  private void deploy(
      String runId,
      RepositoryRef repository,
      String releasedName,
      String version,
      String packageName,
      UUID causationId) {
    DeploymentSpec spec = null;
    String commitSha = null;
    String failure = null;
    try {
      // The reference, not the application: the git host serves this blob under the repository's
      // own address. The REV is the released TAG, fully qualified — a bare version would let a
      // branch of the same name win, and the file that decides where this container runs has to be
      // the file the version was cut from. The answer carries the commit the tag resolved to, which
      // is the only place a release deployment can learn one.
      SpecSource.SpecRead read = specs.read(repository, SpecSource.tagRev(version));
      spec = read.spec();
      commitSha = read.commitSha();
    } catch (RuntimeException e) {
      failure = "[deployment spec unreadable: " + e.getMessage() + "]";
      LOG.warnf(
          "Could not read the deployment spec of %s@%s: %s",
          releasedName, version, e.getMessage());
    }

    String applicationName = applicationName(releasedName, spec);

    List<Target> targets;
    if (spec == null) {
      targets = alreadyRegistered(applicationName);
    } else {
      try {
        targets = register(runId, applicationName, version, spec, causationId);
      } catch (RuntimeException e) {
        // Registration is a local transaction, so this is a bug rather than an outage — and a bug
        // here is exactly the shape that once cost an hour of silence: a fire-and-forget sender,
        // no row, no signal. It is recorded where an operator looks, in the tiers this application
        // is already registered in.
        LOG.errorf(e, "Registration of %s@%s failed", applicationName, version);
        failure = "[registration failed: " + e.getMessage() + "]";
        targets = alreadyRegistered(applicationName);
      }
    }

    List<Queued> queued =
        queue(
            new Release(
                runId, repository, applicationName, version, commitSha, packageName, causationId),
            targets);
    if (failure != null) {
      for (Queued row : queued) {
        finish(row.deploymentId(), row.target(), PdDeploymentStatus.FAILED, failure);
      }
      return;
    }
    for (Queued row : queued) {
      try {
        execute(row.deploymentId(), row.target(), version, commitSha);
      } catch (RuntimeException e) {
        LOG.errorf(e, "Deployment %s failed unexpectedly", row.deploymentId());
        finish(
            row.deploymentId(), row.target(), PdDeploymentStatus.FAILED, "[unexpected: " + e + "]");
      }
    }
  }

  /**
   * One accepted release, as the values every row it writes needs. It exists so the request and the
   * deployment are written from one description rather than from eight positional arguments — the
   * {@link Plan} stance, applied one step earlier.
   */
  private record Release(
      String runId,
      RepositoryRef repository,
      String applicationName,
      String version,
      String commitSha,
      String packageName,
      UUID causationId) {}

  /**
   * The name this release deploys under: the released package's, unless the file the repository
   * carries says otherwise.
   *
   * <p>One line, and it is the whole of the {@code application:} key. It sits here rather than in
   * the parser because the parser never knows which repository it is reading for — the {@code
   * ResourceProvisioning.resolve} and {@link #browserHost} arrangement, applied to the value those
   * two are themselves derived from.
   *
   * <p><b>Two repositories declaring one application name is last-wins and nothing here can refuse
   * it</b>: this schema records an application NAME and no repository identity, by V1's own rule, so
   * there is nothing to compare a second claimant against. It is not a hazard the key introduces —
   * two repositories in two projects may already carry one name and already collapse the same way —
   * so what the key adds is the log line below, which puts the claim on the record of every
   * deployment that makes it.
   */
  static String applicationName(String releasedName, DeploymentSpec spec) {
    if (spec == null || spec.application() == null || spec.application().equals(releasedName)) {
      return releasedName;
    }
    LOG.infof(
        "The release of %s declares `application: %s`, so it deploys as %s",
        releasedName, spec.application(), spec.application());
    return spec.application();
  }

  /**
   * The catalogue read every event starts with — and the first database access a build-succeeded
   * event makes, which is why it is retried.
   *
   * <p>An event arriving while this component's own postgres is cutting over used to die here and
   * be swallowed: the intake is fire-and-forget, the worker logs the exception, and the build that
   * caused it is simply never deployed. There is no row to look at afterwards, because a row is
   * what the read was on the way to creating.
   */
  private Optional<LinkedService> findService(String applicationName) {
    return DbRetry.call(
        "The catalogue lookup of " + applicationName,
        () -> catalog.find(applicationName),
        CUTOVER_BUDGET);
  }

  /**
   * <b>Where a release enters the platform</b> — the topology half of the same read, and the whole
   * of what replaced branch matching.
   *
   * <p>A build used to name a branch, and the tiers listening to that branch were where it went. A
   * release names no branch — it names a tag — so the tier a version lands in is a property of the
   * PLATFORM rather than of the thing being deployed. The designated platform environment
   * ({@code pd_environment.platform}, of which there is exactly one) is that tier: it is already
   * the row that says which tier this install deploys from, and there is nothing else in this
   * schema that could answer the question.
   *
   * <p><b>Empty is a real answer</b>, not only a fresh-database one: an install can be mid-bootstrap
   * with no tier designated yet, and a release then registers nothing and deploys nothing rather
   * than picking a tier at random. That is the same answer {@code registerPlatform} always gave.
   *
   * <p><b>It is the platform plane's tier too</b>, and that is what makes it one question rather
   * than two. A platform service is deployed INTO the environment a release enters at — same row,
   * same labels, same {@code QITS_ENVIRONMENT}, same event attribution — so both register arms take
   * their {@link Target} from exactly this answer and neither has an absence to interpret.
   *
   * <p><b>A promotion ladder is the follow-up this shape is waiting for</b>, and it is deliberately
   * not here: a version that entered dev is promoted to the next tier by a decision somebody makes,
   * which is a deployment REQUEST of its own against another environment — the row this component
   * now writes. What must not come back is a branch: {@code pd_environment.branch} is gone from the
   * schema (V8) and nothing anywhere reads one.
   */
  private List<PdEnvironment> entryTiers() {
    return DbRetry.call(
        "The entry-tier lookup",
        () -> environments.platformEnvironment().map(List::of).orElseGet(List::of),
        CUTOVER_BUDGET);
  }

  /**
   * Bring the catalogue up to date with what the repository declares, and answer where to deploy.
   * The whole of derived registration.
   */
  private List<Target> register(
      String runId,
      String applicationName,
      String version,
      DeploymentSpec spec,
      UUID causationId) {
    if (!isDeployableName(applicationName)) {
      // The application name is the image path segment and the network alias, so it has to be a
      // dns label. A repository whose name is not one cannot be deployed by convention at all, and
      // the intake is fire-and-forget — saying so in the log beats a 400 nobody reads.
      LOG.warnf("%s cannot be an application name, so nothing was registered", applicationName);
      return List.of();
    }
    Optional<LinkedService> known = findService(applicationName);
    return spec.target() == PdDeploymentTarget.PLATFORM
        ? registerPlatform(applicationName, spec, known, causationId)
        : registerInEnvironments(runId, applicationName, version, spec, known, causationId);
  }

  /**
   * The environment half. A repository that is <b>already a platform service</b> is refused here
   * rather than registered: the two planes are not symmetric, and going back is not a conversion.
   *
   * <p>Coming the other way, environment links become the platform plane because there is exactly
   * one destination to move the history to. Going back has as many destinations as there are tiers
   * the service is linked into, no answer to which of them inherits the deployment history, and a
   * running service under the plane's BARE name that the environment deployment would not even
   * find — it would create {@code <env>-<app>} beside it and leave a row saying {@code ACTIVE}
   * about a container nothing replaced. So this refuses, loudly and on the record.
   *
   * <p>The link set written is the <b>union</b> of what the catalogue already holds and the entry
   * tier this release lands in. A release entering dev says nothing about whether the service also
   * belongs in preprod, and the upsert replaces the whole set — so sending only the entry tier
   * would silently unlink every other tier a promotion has already reached.
   */
  private List<Target> registerInEnvironments(
      String runId,
      String applicationName,
      String version,
      DeploymentSpec spec,
      Optional<LinkedService> known,
      UUID causationId) {
    if (known.filter(s -> s.service().deploymentTarget == PdDeploymentTarget.PLATFORM).isPresent()) {
      LOG.errorf(
          "%s is registered as a platform service and its deployments.yml now asks for"
              + " deployment_target: environment. Going back is not a conversion and was refused —"
              + " remediate deliberately (retire the platform service, then push again).",
          applicationName);
      recordRejection(
          applicationName,
          runId,
          version,
          "[refused: "
              + applicationName
              + " is a platform service and this commit asks for deployment_target: environment."
              + " An environment application converts into a platform service, never the reverse —"
              + " there is no one environment to inherit the history and the running platform"
              + " container would be removed by the first environment deployment. Retire the"
              + " platform service deliberately, then push again.]",
          causationId);
      return List.of();
    }

    List<PdEnvironment> matching = entryTiers();
    if (matching.isEmpty()) {
      // No tier is designated as the platform's entry tier — a mid-bootstrap install. Nothing to
      // link into, so nothing is written.
      return List.of();
    }

    Set<String> links =
        new LinkedHashSet<>(known.map(LinkedService::environmentIds).orElse(List.of()));
    for (PdEnvironment environment : matching) {
      links.add(environment.id);
    }
    String healthPath = resolveHealthPath(applicationName, spec, known);
    // The spec's databases, with the convention filled in where the file left it out. Resolved
    // ONCE, here, because this is the first place that holds both the application name and the
    // spec — and before any Target is built, so a collision is one refusal rather than one per
    // tier. The name is the repository's, never its storage id: a database called after a UUID
    // would be a fresh, empty store on the first deployment after the identity rollback.
    List<ResourceProvisioning.Resolved> resources =
        ResourceProvisioning.resolve(applicationName, spec.resources());
    catalog.upsert(
        new ServiceCatalog.Upsert(
            applicationName,
            PdDeploymentTarget.ENVIRONMENT,
            null, // PdService.branch is vestigial; nothing decides a deployment on it
            spec.availableOnEnv(),
            healthPath,
            List.copyOf(links)),
        causationId);

    List<Target> targets = new ArrayList<>();
    for (PdEnvironment environment : matching) {
      if (known.isEmpty() || !known.get().environmentIds().contains(environment.id)) {
        LOG.infof("Registered %s in environment %s", applicationName, environment.name);
      }
      targets.add(
          new Target(
              applicationName,
              environment.id,
              environment.name,
              environment.network,
              PdDeploymentTarget.ENVIRONMENT,
              spec.availableOnEnv(),
              healthPath,
              spec.healthCmd(),
              resources,
              spec.updateOrder(),
              spec.publishMode(),
              spec.routes(),
              spec.upstreamPort(),
              browserHost(applicationName, spec),
              spec.navigationEntries(),
              spec.apiDocs()));
    }
    return List.copyOf(targets);
  }

  /**
   * The platform half, including the conversion a service goes through when it becomes
   * cross-environment: a repository that was an environment application until this release had links
   * in every environment it was in, and those links go rather than sit beside the platform row. Its
   * deployment history is <b>moved onto the platform plane</b> — the active rows decommissioned,
   * since the application they described is about to be replaced from a different plane. Moving
   * rather than deleting is what keeps an in-flight self-update row alive across the component's own
   * conversion.
   *
   * <p>There is no "this name already belongs to another repository" check, and there is nothing to
   * check: the catalogue holds one identity for a service and derived registration has always named
   * an application after its repository, so the name IS the repository.
   *
   * <p><b>A platform service is deployed INTO the main environment, and that is the whole of this
   * arm now.</b> The two register arms answer the same question — which tier does this release enter
   * at ({@link #entryTiers()}) — and produce a {@link Target} carrying that tier's id, name and
   * bundle network in both cases. Nothing about the target says "no environment" any more.
   *
   * <p>What still tells the planes apart is {@link PdDeploymentTarget#PLATFORM} on the target, and
   * it decides three things and no others: the wire alias is <b>bare</b>, so a peer in any tier
   * reaches qits-ci by writing {@code qits-ci}; the membership is every environment's networks
   * rather than one tier's; and the read surface keys it {@code platform:<name>}. Everything else —
   * the row, the labels, the injected {@code QITS_ENVIRONMENT}, the four events — is an ordinary
   * deployment into the designated tier.
   *
   * <p><b>Why the plane deploys somewhere at all.</b> "No environment" was a defensible spelling of
   * "serves all of them" while nothing needed to know where a platform container ran — and it cost
   * exactly what an unstated fact costs: a platform service booted without {@code QITS_ENVIRONMENT}
   * and could not tell its own telemetry, its own peers or its own resource rows which install it
   * was part of, while four lifecycle events announced a null tier to consumers projecting a route
   * table per environment. The main environment is the honest answer: there is one, it is
   * designated, and the plane already provisions its databases on that tier's postgres.
   */
  private List<Target> registerPlatform(
      String applicationName,
      DeploymentSpec spec,
      Optional<LinkedService> known,
      UUID causationId) {
    List<PdEnvironment> entry = entryTiers();
    if (entry.isEmpty()) {
      return List.of();
    }
    PdEnvironment main = entry.get(0);
    if (known.isEmpty()) {
      LOG.infof(
          "Registered %s as a platform service, deploying into %s", applicationName, main.name);
    }
    String healthPath = resolveHealthPath(applicationName, spec, known);
    List<ResourceProvisioning.Resolved> resources =
        ResourceProvisioning.resolve(applicationName, spec.resources());
    catalog.upsert(
        new ServiceCatalog.Upsert(
            applicationName,
            PdDeploymentTarget.PLATFORM,
            null, // PdService.branch is vestigial; a release names a tag
            false,
            healthPath,
            List.of()),
        causationId);

    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              List<PdDeployment> scoped = deployments.listEnvironmentScoped(applicationName);
              for (PdDeployment deployment : scoped) {
                if (deployment.status == PdDeploymentStatus.ACTIVE) {
                  deployment.status = PdDeploymentStatus.DECOMMISSIONED;
                  deployment.finishedAt = Instant.now();
                }
                // The history moves onto the plane, which is now a column and a tier rather than
                // the absence of one. It used to be spelled `environmentId = null`; that null was
                // read as "the platform plane" by every query underneath, and both halves of the
                // statement are said out loud here instead.
                deployment.deploymentTarget = PdDeploymentTarget.PLATFORM;
                deployment.environmentId = main.id;
              }
              if (!scoped.isEmpty()) {
                LOG.infof(
                    "Converted %s from an environment application to a platform service in %s",
                    applicationName, main.name);
              }
            });

    return List.of(
        new Target(
            applicationName,
            main.id,
            main.name,
            main.network,
            PdDeploymentTarget.PLATFORM,
            false,
            healthPath,
            spec.healthCmd(),
            resources,
            spec.updateOrder(),
            spec.publishMode(),
            spec.routes(),
            spec.upstreamPort(),
            browserHost(applicationName, spec),
            spec.navigationEntries(),
            spec.apiDocs()));
  }

  /**
   * Where a service's health path comes from, in this order: the repository's own {@code
   * health_path}, then the value the catalogue already holds, then the convention derived from the
   * name. The convention is <b>written to the row</b> like every other derived fact, so a fresh
   * database gets working health gates with nothing to fill in by hand.
   *
   * <p>The stored value sits between the two on purpose. An operator who set a path is fixing
   * something the convention got wrong, and a later green build must not undo the fix; a repository
   * that states its own path is the more specific statement and does.
   *
   * <p>What this replaces: registration once had no source for the path at all, so every row was
   * written null, every deployment fell back to {@code
   * qits.platform.deployments.default-health-path} ({@code /q/health/ready}), and every service
   * mounted under its own prefix — all of them but the gateway — failed a health gate against a URL
   * that 404s while the container was fine.
   */
  private static String resolveHealthPath(
      String applicationName, DeploymentSpec spec, Optional<LinkedService> known) {
    if (spec.healthPath() != null) {
      return spec.healthPath();
    }
    return known
        .map(linked -> linked.service().healthPath)
        .filter(path -> path != null && !path.isBlank())
        .orElseGet(() -> conventionHealthPath(applicationName));
  }

  /**
   * The platform's path convention: a service serves everything under its own name without the
   * {@code qits-} prefix, so qits-observability answers on {@code /observability/q/health/ready}
   * and this component on {@code /platform-deployments/q/health/ready}. A name that does not carry
   * the prefix keeps the whole name.
   */
  static String conventionHealthPath(String applicationName) {
    String segment =
        applicationName.startsWith(NAME_PREFIX)
            ? applicationName.substring(NAME_PREFIX.length())
            : applicationName;
    // A repository called exactly `qits-` would leave nothing to mount under; keep its whole name
    // rather than compose a path with an empty segment in it.
    return "/" + (segment.isBlank() ? applicationName : segment) + "/q/health/ready";
  }

  /**
   * A refused registration, written down where the operator will look for it: one {@code FAILED}
   * deployment on the platform plane. A log line alone would say the same thing to nobody — the
   * intake is fire-and-forget, so the row is the only surface a refusal can surface on.
   *
   * <p>The only caller is the environment arm refusing a repository that is already a platform
   * service, so the row is the plane's and names the tier the plane deploys into — the same place
   * the deployment it is refusing would have gone. A mid-bootstrap install with no designated tier
   * records the refusal with none; a row with no tier and no successor is a worse answer than a
   * refusal nobody can see, and it is the one place a null environment survives.
   */
  private void recordRejection(
      String applicationName, String runId, String version, String detail, UUID causationId) {
    String environmentId =
        entryTiers().stream().findFirst().map(environment -> environment.id).orElse(null);
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              PdDeployment rejected = new PdDeployment();
              rejected.id = UUID.randomUUID().toString();
              // See queue(): the stamp finds no scope on this thread, so the cause is set here.
              rejected.causationId = causationId;
              rejected.applicationName = applicationName;
              rejected.environmentId = environmentId;
              rejected.deploymentTarget = PdDeploymentTarget.PLATFORM;
              rejected.version = version;
              rejected.runId = runId;
              rejected.status = PdDeploymentStatus.FAILED;
              rejected.detail = detail;
              rejected.createdAt = Instant.now();
              rejected.finishedAt = Instant.now();
              deployments.persist(rejected);
            });
  }

  /**
   * What a failed spec read falls back to: where this application is already registered, read off
   * the catalogue. It answers where to record the failure — never where to deploy.
   *
   * <p>Which is why every target here carries a null {@code healthCmd} and needs nothing better:
   * no container starts off one of them.
   */
  private List<Target> alreadyRegistered(String applicationName) {
    Optional<LinkedService> known = findService(applicationName);
    if (known.isEmpty()) {
      return List.of();
    }
    LinkedService linked = known.get();
    if (linked.service().deploymentTarget == PdDeploymentTarget.PLATFORM) {
      // The same entry-tier question the deploying path asks, so a spec read that failed records
      // the failure exactly where a successful one would have deployed — the tier included, since
      // that is where a platform service deploys.
      List<PdEnvironment> entry = entryTiers();
      if (entry.isEmpty()) {
        return List.of();
      }
      PdEnvironment main = entry.get(0);
      return List.of(
          new Target(
              applicationName,
              main.id,
              main.name,
              main.network,
              PdDeploymentTarget.PLATFORM,
              false,
              linked.service().healthPath,
              null,
              List.of(),
              null,
              null,
              List.of(),
              DeploymentSpecParser.DEFAULT_UPSTREAM_PORT,
              null,
              null,
              null));
    }
    List<Target> targets = new ArrayList<>();
    for (PdEnvironment environment : entryTiers()) {
      if (linked.environmentIds().contains(environment.id)) {
        targets.add(
            new Target(
                applicationName,
                environment.id,
                environment.name,
                environment.network,
                PdDeploymentTarget.ENVIRONMENT,
                linked.service().availableOnEnv,
                linked.service().healthPath,
                null,
                List.of(),
                null,
                null,
                List.of(),
                DeploymentSpecParser.DEFAULT_UPSTREAM_PORT,
                null,
                null,
                null));
      }
    }
    return List.copyOf(targets);
  }

  /**
   * Write one deployment REQUEST per place this release addresses, answer the quality gate, and —
   * where it is met — write the {@code QUEUED} deployment row the request hands off to.
   *
   * <p><b>The request and the deployment are written in ONE transaction, and the gate is answered
   * between them.</b> That ordering is the model in miniature: the platform records what was asked
   * for, something decides, and only a {@link PdQualityGate#MET} answer produces an execution row —
   * which the request then points at. Today the decision is {@link #gate}, a placeholder that says
   * yes to everything, so the two rows always come in pairs; the day a real gate says no, the
   * request stands with no {@code deployment_id} and this loop simply queues nothing for it. One
   * transaction rather than two because a request with a settled gate and no deployment must mean
   * "refused", never "the process died in between".
   *
   * <p><b>Deliberately not retried</b>, and it is the same answer for {@link #recordRejection}, the
   * {@code STARTING} transition and the platform conversion. They INSERT or move rows, so a commit
   * whose outcome the connection died before reporting would be duplicated by a second attempt; and
   * they all run before anything docker-side has happened, so losing one drops the event with
   * nothing half-done and a resent event replays it. The retried brackets are the ones that come
   * AFTER a container is already running, where dropping the work leaves a live container with no
   * row that admits it.
   *
   * <p>Each created row is announced as {@code DeploymentQueued} <b>after the transaction
   * commits</b>, so a consumer that reads the deployment back finds it. One event per row: a
   * release addressing three tiers queues three deployments and says so three times.
   */
  private List<Queued> queue(Release release, List<Target> targets) {
    if (targets.isEmpty()) {
      return List.of();
    }
    List<Queued> rows =
        QuarkusTransaction.requiringNew()
            .call(
                () -> {
                  List<Queued> queued = new ArrayList<>();
                  for (Target target : targets) {
                    PdDeploymentRequest request = persistRequest(release, target);
                    Gate verdict = gate(release, target);
                    request.qualityGate = verdict.state();
                    request.gateDetail = verdict.detail();
                    request.gateSettledAt = Instant.now();
                    if (verdict.state() != PdQualityGate.MET) {
                      LOG.warnf(
                          "The quality gate refused %s@%s for %s: %s",
                          release.applicationName(),
                          release.version(),
                          target.environmentName() == null
                              ? "the platform"
                              : target.environmentName(),
                          verdict.detail());
                      continue;
                    }
                    Queued row = persistQueued(release, target);
                    request.deploymentId = row.deploymentId();
                    queued.add(row);
                  }
                  return queued;
                });
    for (Queued row : rows) {
      announceQueued(row, release);
    }
    return rows;
  }

  /**
   * What the quality gate said. A record rather than a boolean because the detail is the whole
   * value of a refusal — a request that says {@code UNMET} and nothing else tells an operator
   * nothing.
   */
  private record Gate(PdQualityGate state, String detail) {}

  /**
   * <b>The placeholder quality gate: everything passes, immediately.</b>
   *
   * <p>It is a method rather than an absence on purpose. A released version has already been
   * through qits-ci's pipeline and this component has nothing further to ask today, so the honest
   * implementation is one line — but the SHAPE has to exist before a real gate does, or the first
   * one arrives as a change to the transaction that queues deployments rather than as a change to
   * this method. Whatever asks a question later (a userflow suite against the tier, a manual
   * approval, a soak window on the version already there) answers here, and {@link #queue} already
   * does the right thing with a no.
   *
   * <p>It takes the release and the target because a real gate is about a (version, place) pair —
   * the same version may be waved into dev and held out of prod — and a signature that could not
   * express that would have to change on the day it mattered.
   */
  private Gate gate(Release release, Target target) {
    return new Gate(PdQualityGate.MET, null);
  }

  /** A queued row, and the facts its announcement needs, carried out of the transaction. */
  private record Queued(String deploymentId, Target target, Instant queuedAt) {}

  /**
   * One deployment request. Inside {@link #queue}'s transaction, never on its own — the gate's
   * answer and the deployment it hands off to are written in the same bracket.
   */
  private PdDeploymentRequest persistRequest(Release release, Target target) {
    PdDeploymentRequest request = new PdDeploymentRequest();
    request.id = UUID.randomUUID().toString();
    // Set EXPLICITLY, for the reason every row this component writes on the worker is: the
    // CausationStamp listener reads a ThreadLocal the queue hop already left behind.
    request.causationId = release.causationId();
    request.applicationName = target.applicationName();
    request.version = release.version();
    request.environmentId = target.environmentId();
    request.packageName = release.packageName();
    request.repoId = release.repository() == null ? null : release.repository().repoId();
    request.projectId = release.repository() == null ? null : release.repository().projectId();
    request.qualityGate = PdQualityGate.UNMET;
    request.createdAt = Instant.now();
    requests.persist(request);
    return request;
  }

  /** One {@code QUEUED} row. Inside {@link #queue}'s transaction, never on its own. */
  private Queued persistQueued(Release release, Target target) {
    PdDeployment deployment = new PdDeployment();
    deployment.id = UUID.randomUUID().toString();
    // The generic causation column, set EXPLICITLY. This runs on pd-deploy-worker, behind the
    // queue hop the intake made: CausationScope does not follow work, so CausationStamp would read
    // null here and record a decision nobody made. An author-set value is what the stamp yields to.
    // Every row of one event shares it — one build going green is one cause, however many tiers it
    // fans out over.
    deployment.causationId = release.causationId();
    deployment.applicationName = target.applicationName();
    deployment.environmentId = target.environmentId();
    // The plane, stated. Both arms of register() now hand over the designated tier's id above, so
    // "which plane is this" stopped being answerable from the row's environment and is written
    // instead — see PdDeployment.deploymentTarget and V8.
    deployment.deploymentTarget = target.target();
    // The released coordinate — the tag the image carries and what everything below derives from
    // — and, beside it, the commit that tag resolved to. The second is null when the spec read
    // could not resolve one, which is a real answer and never a reason to refuse a deployment.
    deployment.version = release.version();
    deployment.commitSha = release.commitSha();
    deployment.runId = release.runId();
    deployment.status = PdDeploymentStatus.QUEUED;
    deployment.createdAt = Instant.now();
    // The routing snapshot, written where the deployment is written. It is the spec's, and V3's
    // header argues why a row holds a spec value: a SELF-UPDATE announces from the successor's
    // startup sweep, which has this row and no live process that read the file. Storing it is what
    // keeps that announcement off the network — see announceAdopted.
    deployment.routes = String.join(",", target.routes());
    deployment.upstreamPort = target.upstreamPort();
    deployment.browserHost = target.browserHost();
    deployment.navigationEntries = DeploymentSpecParser.joinEntries(target.navigation());
    deployment.apiDocs = target.apiDocs();
    deployments.persist(deployment);
    return new Queued(deployment.id, target, deployment.createdAt);
  }

  /**
   * Everything a deployment needs off the worker thread — plain values, never entities.
   *
   * <p><b>The last three are for the announcements and nothing else.</b> They are read off the row
   * inside the {@code STARTING} transaction because that is the one place a deployment has the row
   * open; re-reading it at each of the three later announcement points would be three queries for
   * values that cannot change. {@code startedAt} is a clock reading rather than a column — there is
   * no {@code started_at} — taken at the transition rather than at publish, which is the property an
   * event log actually needs.
   */
  private record Plan(
      String deploymentId,
      Target target,
      String version,
      String commitSha,
      String healthPath,
      String healthCmd,
      String runId,
      UUID cause,
      Instant startedAt) {

    String applicationName() {
      return target.applicationName();
    }

    String environmentId() {
      return target.environmentId();
    }

    String environmentName() {
      return target.environmentName();
    }

    String bundleNetwork() {
      return target.bundleNetwork();
    }

    boolean availableOnEnv() {
      return target.availableOnEnv();
    }

    List<ResourceProvisioning.Resolved> resources() {
      return target.resources();
    }

    boolean platform() {
      return target.target() == PdDeploymentTarget.PLATFORM;
    }

    /** The one network {@code docker run} can take; every other membership is a join. */
    String primaryNetwork() {
      return platform()
          ? PdNetworks.PLATFORM
          : PdNetworks.application(environmentName(), applicationName());
    }

    DeploymentDriver.UpdateOrder updateOrder() {
      return target.updateOrder();
    }

    DeploymentDriver.PublishMode publishMode() {
      return target.publishMode();
    }

    /**
     * The address peers dial this by, on every network it is on: {@code
     * <environment>-<application>} for a tier's copy, the bare application name for a platform
     * service. It is derived in one place because everything that has to agree on it takes it from
     * here — swarm's service NAME first of all, which is why a changed spelling is a second service
     * beside the one that was serving rather than a rename.
     *
     * <p><b>The PLANE decides it, not the tier.</b> A platform service has an environment name now
     * and its alias still must not carry it: the whole point of the bare spelling is that a peer in
     * any tier reaches qits-ci by writing {@code qits-ci}, without knowing where the platform's own
     * tier is or that it moved.
     */
    String wireAlias() {
      return PdNetworks.alias(target.target(), environmentName(), applicationName());
    }

    /**
     * The event's complete route projection. The host is resolved only after the target is known,
     * from the same wire alias the runtime registered; a consumer must never recreate that naming
     * convention.
     */
    List<DeploymentEndpoint> endpoints() {
      // The same builder the startup sweep's adoption uses over the row's stored snapshot, so the
      // two ways this component can announce a deployment cannot describe one differently.
      return resolveEndpoints(target.routes(), wireAlias(), target.upstreamPort());
    }

    /** The DNS label this deployment is also served at, or null — resolved at registration. */
    String browserHost() {
      return target.browserHost();
    }

    /** Where this application asks to appear. Application-level, so it is the target's own. */
    List<NavigationEntry> navigation() {
      return target.navigation();
    }

    /** Where the application's browsable API document lives, or null — the spec's own value. */
    String apiDocsPath() {
      return target.apiDocs();
    }
  }

  /** The synchronous deployment — package-private so tests drive it without the worker. */
  void execute(String deploymentId, Target target, String version, String commitSha) {
    Plan plan =
        QuarkusTransaction.requiringNew()
            .call(
                () -> {
                  PdDeployment deployment = deployments.findById(deploymentId);
                  if (deployment == null || deployment.status != PdDeploymentStatus.QUEUED) {
                    return null; // torn down or swept while queued — nothing to do
                  }
                  deployment.status = PdDeploymentStatus.STARTING;
                  return new Plan(
                      deploymentId,
                      target,
                      version,
                      commitSha,
                      target.healthPath() != null ? target.healthPath() : defaultHealthPath,
                      // No default to fall back on, and none to want: an image that named no
                      // command is one the HTTP probe describes.
                      target.healthCmd(),
                      deployment.runId,
                      deployment.causationId,
                      Instant.now());
                });
    if (plan == null) {
      return;
    }
    announceStarted(plan);

    // PROVISIONING GOES HERE, between the row's STARTING transition and the pull, and the placement
    // is three decisions at once:
    //   * AFTER the transition, because the row is what a failure is recorded on — a resource that
    //     cannot be made to exist has to be readable as a failed deployment, not as a log line
    //     under a fire-and-forget intake;
    //   * BEFORE the pull, because nothing docker-side has happened yet: there is no fresh
    //     container to remove and no predecessor stopped to restart, so the failure path is a
    //     single `finish` and the previous deployment is untouched;
    //   * on plain values, because Plan is plain values — no transaction and no open session spans
    //     the DDL call to another server.
    List<DeploymentDriver.ResourceBinding> bindings;
    try {
      bindings =
          resourceProvisioning.ensureAll(
              plan.applicationName(), plan.environmentName(), plan.resources());
    } catch (RuntimeException e) {
      LOG.warnf(
          "Could not provision the resources of %s: %s", plan.applicationName(), e.getMessage());
      finish(
          deploymentId,
          target,
          PdDeploymentStatus.FAILED,
          "[resource provisioning failed: " + e.getMessage() + "]");
      return;
    }

    // The released version is the tag: `qits/<application>:<version>`, which is the coordinate the
    // release pipeline published under. Never the commit — an image tagged with a sha is what the
    // retired build trigger pulled.
    String imageRef =
        ImageRefs.imageRef(registryHost, imageRepository, plan.applicationName(), plan.version());

    // The registry having no image for a green build is an expected outcome (nothing may publish
    // this application yet) and gets its own state rather than a generic failure.
    DeploymentDriver.PullResult pulled = driver.pull(imageRef);
    switch (pulled.outcome()) {
      case IMAGE_MISSING -> {
        finish(
            deploymentId,
            target,
            PdDeploymentStatus.IMAGE_MISSING,
            "no image " + imageRef + "\n" + safe(pulled.detail()));
        return;
      }
      case AUTH_REFUSED -> {
        // A refusal is a FAILED deployment rather than IMAGE_MISSING, and the detail is why the
        // two are told apart at all: what has to be fixed is the credential the daemon reads, not
        // the pipeline that publishes the image.
        finish(
            deploymentId,
            target,
            PdDeploymentStatus.FAILED,
            "the registry refused the pull of "
                + imageRef
                + " — check the deployer's registry credential\n"
                + safe(pulled.detail()));
        return;
      }
      case ERROR -> {
        finish(deploymentId, target, PdDeploymentStatus.FAILED, safe(pulled.detail()));
        return;
      }
      case OK -> {
        /* fall through */
      }
    }

    // Named after the deployment, not the sha: re-deploying the same commit must never collide
    // with what it is about to replace. Whether that name is what the runtime uses is the driver's
    // answer, below: docker names one container per deployment, a swarm service's name IS its
    // address and a replace updates it in place.
    String deploymentName =
        ContainerNames.of(
            plan.target().target(), plan.environmentName(), plan.applicationName(), deploymentId);

    // Networks are re-ensured on every deployment rather than trusted from creation time — an
    // environment created while docker was down must heal, not stay broken.
    String primaryNetwork = plan.primaryNetwork();
    driver.ensureNetwork(primaryNetworkSpec(plan));
    if (!plan.platform() && plan.availableOnEnv()) {
      driver.ensureNetwork(
          new DeploymentDriver.Network(
              plan.bundleNetwork(),
              plan.environmentId(),
              DeploymentDriver.NetworkKind.BUNDLE,
              null));
    }
    // The FULL membership, primary first, handed over in one piece. It is a list rather than a
    // primary plus a join set because an orchestrator that cannot join after the fact has to
    // declare all of it at create time — every swarm `--network-add` recreates the task — and the
    // docker driver still starts on the first and joins the rest.
    List<String> networks = new ArrayList<>();
    networks.add(primaryNetwork);
    networks.addAll(desiredJoins(plan, primaryNetwork));

    DeploymentDriver.ServiceSpec spec =
        serviceSpec(plan, List.copyOf(networks), imageRef, deploymentName, bindings);
    // What the runtime will call it, and therefore what the row records: asked BEFORE anything is
    // applied, so a crash in the middle leaves a STARTING row the startup sweep can still identify.
    String name = driver.nameOf(spec);
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              PdDeployment deployment = deployments.findById(deploymentId);
              if (deployment != null) {
                deployment.containerName = name;
              }
            });

    // The replace, whole, in one call. What it costs — a predecessor stopped and restartable, a
    // hand-rolled rollback — is the docker driver's business; under swarm it is one `service
    // update` and the orchestrator's own update policy.
    DeploymentDriver.ApplyResult applied = driver.apply(spec);
    switch (applied.outcome()) {
      case REFUSED -> {
        finish(deploymentId, target, PdDeploymentStatus.FAILED, safe(applied.detail()));
        return;
      }
      case HANDED_OFF -> {
        // Deploying this component itself. The row stays STARTING on purpose: whichever instance
        // survives the succession records the outcome — the successor's sweep adopts it, a
        // rolled-back predecessor's sweep fails it.
        LOG.infof(
            "Self-update handed over: %s succeeds this instance, and the row stays STARTING until"
                + " whoever survives records it (%s)",
            name, safe(applied.detail()));
        return;
      }
      case APPLIED -> {
        /* fall through to the verdict */
      }
    }

    DeploymentDriver.Convergence converged =
        driver.awaitConverged(name, Duration.ofSeconds(healthTimeoutSeconds));
    if (!converged.converged()) {
      // Nothing runs that did not run before: the orchestrator reverted the spec itself, and the
      // predecessor is serving again by the time this returns.
      //
      // ROLLED_BACK is that verdict kept rather than flattened. The orchestrator already answered
      // the question a reader has ("is anything serving this place?") and FAILED threw the answer
      // away — a rolled-back deployment and a refused apply asked for very different reactions and
      // read identically on the row. A convergence that failed WITHOUT a rollback keeps FAILED,
      // because there nothing is known to serve.
      finish(
          deploymentId,
          target,
          converged.outcome() == DeploymentDriver.ConvergenceOutcome.ROLLED_BACK
              ? PdDeploymentStatus.ROLLED_BACK
              : PdDeploymentStatus.FAILED,
          safe(converged.detail()));
      return;
    }

    // Cutover: the new deployment is the application's ACTIVE one, whatever was ACTIVE before is
    // decommissioned — rows first, then whatever is left to reap (the rows' own names and the
    // predecessors the driver retired alike; a set, since the healthy path usually sees the same
    // ones from both angles).
    //
    // THE RETRY IS NOT DECORATION HERE. This component deploys the platform's postgres, and a
    // cutover of qits-oci-postgresql kills every connection this process holds — in the middle of
    // the deployment that just performed it. The container was healthy; only the bookkeeping died,
    // and it recorded the whole deployment FAILED for it. Re-running the bracket is safe because it
    // re-reads its entities and writes them the same values.
    //
    // `inNewTx` rather than `call` around a `requiringNew`: the retry owns the transaction, so an
    // attempt that fails is one it knows never committed. The two spelled separately would retry a
    // lost commit acknowledgement as well, which is the one round trip nothing can place.
    Cutover cutover =
        DbRetry.inNewTx(
            "The cutover bookkeeping of " + deploymentId,
            () -> {
              List<String> old = new ArrayList<>();
              for (PdDeployment previous :
                  deployments.listActiveByApplication(
                      plan.applicationName(), plan.environmentId())) {
                previous.status = PdDeploymentStatus.DECOMMISSIONED;
                previous.finishedAt = Instant.now();
                if (previous.containerName != null) {
                  old.add(previous.containerName);
                }
              }
              PdDeployment deployment = deployments.findById(deploymentId);
              deployment.status = PdDeploymentStatus.ACTIVE;
              deployment.finishedAt = Instant.now();
              // Flushed here rather than left to the commit, and that is what makes the retry
              // above worth having: an ORM flushes at commit by default, which would put these
              // statements on the far side of the one round trip nothing can place. Flushed, a
              // lost connection is a body failure — certainly not committed, so certainly safe to
              // run again.
              deployments.flush();
              // The timestamp comes back out rather than being taken again afterwards: the event
              // says when the cutover happened, and a retry that ran the body twice must announce
              // the value that is on the row.
              return new Cutover(old, deployment.finishedAt);
            },
            CUTOVER_BUDGET);
    Set<String> toRemove = new LinkedHashSet<>(cutover.oldContainers());
    toRemove.addAll(converged.retired());
    // Never the thing that just went live. Under swarm that removes the whole set by itself: a
    // replace is in place, so the predecessor row names the same service this deployment applied.
    toRemove.remove(name);
    driver.reap(List.copyOf(toRemove));
    LOG.infof(
        "Deployed %s@%s into %s (%s)",
        plan.applicationName(),
        plan.version(),
        plan.platform() ? "the platform" : plan.environmentName(),
        name);
    // Last, so an unreachable qits-events delays nothing this deployment still has to do. The
    // timestamp is the row's, so announcing late does not make the event late.
    announceActive(plan, name, cutover.finishedAt());
  }

  /** What the cutover bracket carries out: the containers to reap, and when it happened. */
  private record Cutover(List<String> oldContainers, Instant finishedAt) {}

  private DeploymentDriver.Network primaryNetworkSpec(Plan plan) {
    return plan.platform()
        ? new DeploymentDriver.Network(
            PdNetworks.PLATFORM, null, DeploymentDriver.NetworkKind.PLATFORM, null)
        : new DeploymentDriver.Network(
            plan.primaryNetwork(),
            plan.environmentId(),
            DeploymentDriver.NetworkKind.APPLICATION,
            plan.applicationName());
  }

  /**
   * Everything the driver needs, as plain values — the {@link Plan} stance carried one step
   * further, since a driver may run the spec on a thread and in a process this one does not own.
   */
  private DeploymentDriver.ServiceSpec serviceSpec(
      Plan plan,
      List<String> networks,
      String imageRef,
      String deploymentName,
      List<DeploymentDriver.ResourceBinding> bindings) {
    return new DeploymentDriver.ServiceSpec(
        plan.environmentId(),
        plan.environmentName(),
        ApplicationKeys.of(plan.target().target(), plan.environmentId(), plan.applicationName()),
        plan.applicationName(),
        plan.deploymentId(),
        plan.version(),
        deploymentName,
        plan.wireAlias(),
        networks,
        imageRef,
        plan.healthPath(),
        plan.healthCmd(),
        plan.target().target(),
        plan.availableOnEnv(),
        plan.updateOrder(),
        plan.publishMode(),
        bindings);
  }

  /**
   * Every network this deployment belongs on beyond its primary one — a membership the driver
   * declares at create time or joins after the start, whichever its orchestrator can do.
   *
   * <ul>
   *   <li>the legacy network, while {@code qits.platform.deployments.legacy-network} names one —
   *       the transition membership that keeps today's direct cross-application URLs resolving;
   *   <li>a public node ({@code availableOnEnv}) additionally joins its environment's bundle and
   *       <b>every</b> per-application network of that environment: that is the hub, and it is how
   *       an application reaches the gateway and how the gateway proxies every application;
   *   <li>a platform service joins every per-application network of every environment — being
   *       locally reachable everywhere is what makes it platform-plane rather than a shared service
   *       that needs a route.
   * </ul>
   */
  private List<String> desiredJoins(Plan plan, String primaryNetwork) {
    Set<String> joins = new LinkedHashSet<>();
    legacyNetwork.map(String::strip).filter(n -> !n.isEmpty()).ifPresent(joins::add);
    if (plan.platform()) {
      for (DeploymentDriver.Network network : driver.networks()) {
        if (network.kind() == DeploymentDriver.NetworkKind.APPLICATION) {
          joins.add(network.name());
        }
      }
    } else if (plan.availableOnEnv()) {
      joins.add(plan.bundleNetwork());
      for (DeploymentDriver.Network network : driver.networks()) {
        if (network.kind() == DeploymentDriver.NetworkKind.APPLICATION
            && plan.environmentId().equals(network.environmentId())) {
          joins.add(network.name());
        }
      }
    }
    joins.remove(primaryNetwork);
    return List.copyOf(joins);
  }

  /**
   * Record the outcome. Retried on a connection-class failure for the same reason the cutover
   * bookkeeping is: this is the last write of a deployment that may have just replaced the database
   * it writes to, and an outcome nobody could record is a row that says {@code STARTING} forever.
   *
   * <p><b>The one funnel every recorded failure goes through, which is why the announcement lives
   * here</b> rather than at the nine call sites that reach it. It announces only when the written
   * status is not {@code ACTIVE} — the same test the WARN already made, and nothing calls this with
   * {@code ACTIVE} today; the happy path announces from the cutover, which knows things this does
   * not.
   *
   * <p><b>The word travels.</b> {@code DeploymentFailed} already carries the status as a string, so
   * a {@code ROLLED_BACK} outcome announces that word and consumers that only care "this did not go
   * live" are unaffected — which is why refining the vocabulary needed no fifth event.
   *
   * <p><b>No row, no event.</b> A deployment whose environment was torn down mid-deploy has nothing
   * to record and nothing to announce: the bracket returns null and this returns without publishing.
   *
   * <p>{@code target} is the caller's, and it is the only thing here the row cannot supply — {@code
   * pd_deployment} holds the environment's ID and never its NAME. Every call site already has one.
   */
  private void finish(
      String deploymentId, Target target, PdDeploymentStatus status, String detail) {
    Finished finished =
        DbRetry.inNewTx(
            "Recording deployment " + deploymentId + " as " + status,
            () -> {
              PdDeployment deployment = deployments.findById(deploymentId);
              if (deployment == null) {
                return null; // environment torn down mid-deploy
              }
              deployment.status = status;
              deployment.detail = detail;
              deployment.finishedAt = Instant.now();
              // statement phase, so a lost connection is retriable — see the cutover bracket
              deployments.flush();
              return new Finished(
                  deployment.imageTag(),
                  deployment.commitSha,
                  deployment.runId,
                  deployment.causationId,
                  deployment.finishedAt);
            },
            CUTOVER_BUDGET);
    if (status != PdDeploymentStatus.ACTIVE) {
      LOG.warnf("Deployment %s ended %s: %s", deploymentId, status, firstLine(detail));
      if (finished != null) {
        announceFailed(deploymentId, target, status, detail, finished);
      }
    }
  }

  /** What the outcome bracket carries out — the row's own values, for the announcement. */
  private record Finished(
      String version, String commitSha, String runId, UUID cause, Instant finishedAt) {}

  /**
   * The four announcements, each called after the transaction that made its statement true.
   *
   * <p><b>They are wrapped, every one of them, and that is the rule rather than caution.</b> An
   * announcement is a statement about a deployment, never part of it: a bus that is unreachable, a
   * serializer that refuses a value, an implementation with a bug must all cost the log line below
   * and nothing else. The port already says an implementation must not throw; this is what makes a
   * broken one survivable.
   *
   * <p>Every announcer is offered the event — {@code Instance} is a set, usually of one and validly
   * of none — and one that throws does not stop the next.
   */
  private void announceQueued(Queued row, Release release) {
    announce(
        row.deploymentId(),
        announcer ->
            announcer.onQueued(
                new DeploymentQueued(
                    row.deploymentId(),
                    row.target().applicationName(),
                    row.target().environmentId(),
                    row.target().environmentName(),
                    release.version(),
                    release.commitSha(),
                    release.runId(),
                    row.queuedAt()),
                release.causationId()));
  }

  private void announceStarted(Plan plan) {
    announce(
        plan.deploymentId(),
        announcer ->
            announcer.onStarted(
                new DeploymentStarted(
                    plan.deploymentId(),
                    plan.applicationName(),
                    plan.environmentId(),
                    plan.environmentName(),
                    plan.version(),
                    plan.commitSha(),
                    plan.runId(),
                    plan.startedAt()),
                plan.cause()));
  }

  private void announceActive(Plan plan, String containerName, Instant finishedAt) {
    announce(
        plan.deploymentId(),
        announcer ->
            announcer.onActive(
                new DeploymentActive(
                    plan.deploymentId(),
                    plan.applicationName(),
                    plan.environmentId(),
                    plan.environmentName(),
                    plan.version(),
                    plan.commitSha(),
                    plan.runId(),
                    containerName,
                    finishedAt,
                    plan.browserHost(),
                    plan.apiDocsPath(),
                    plan.navigation(),
                    plan.endpoints()),
                plan.cause()));
  }

  private void announceFailed(
      String deploymentId,
      Target target,
      PdDeploymentStatus status,
      String detail,
      Finished finished) {
    announce(
        deploymentId,
        announcer ->
            announcer.onFailed(
                new DeploymentFailed(
                    deploymentId,
                    target.applicationName(),
                    target.environmentId(),
                    target.environmentName(),
                    finished.version(),
                    finished.commitSha(),
                    finished.runId(),
                    status.name(),
                    detail,
                    finished.finishedAt()),
                finished.cause()));
  }

  /** See {@link #announceQueued} for why every one of these is wrapped. */
  private void announce(String deploymentId, Consumer<DeployAnnouncer> announcement) {
    for (DeployAnnouncer announcer : announcers) {
      try {
        announcement.accept(announcer);
      } catch (RuntimeException e) {
        LOG.warnf(e, "Announcing deployment %s failed", deploymentId);
      }
    }
  }

  /** An environment's deployments across all its applications, newest-first. */
  public List<PdDeployment> deploymentsFor(String environmentId) {
    return deployments.listByEnvironmentNewestFirst(environmentId);
  }

  /**
   * The platform plane's deployments across all its applications, newest-first — the same question
   * as {@link #deploymentsFor}, asked of the plane that has no environment id to ask with.
   */
  public List<PdDeployment> platformDeployments() {
    return deployments.listPlatformNewestFirst();
  }

  /**
   * An environment's deployment REQUESTS, newest-first — what was asked for here, whatever the gate
   * said and whatever became of it.
   *
   * <p>It is the listing beside {@link #deploymentsFor}, and it is a separate question rather than a
   * richer answer to that one: a request the gate refused produced no deployment at all, so it is
   * invisible in the deployment listing by construction. {@code applicationName} narrows it to one
   * service and is optional — a null asks about the whole tier.
   */
  public List<PdDeploymentRequest> deploymentRequestsFor(
      String environmentId, String applicationName) {
    return applicationName == null || applicationName.isBlank()
        ? requests.listByEnvironmentNewestFirst(environmentId)
        : requests.listByEnvironmentAndApplicationNewestFirst(environmentId, applicationName);
  }

  /** Drop this environment's recorded deployments — the first step of a teardown. */
  public void forgetEnvironment(String environmentId) {
    QuarkusTransaction.requiringNew()
        .run(() -> deployments.delete("environmentId = ?1", environmentId));
  }

  private static boolean isDeployableName(String applicationName) {
    try {
      PdIdentifiers.requireName(applicationName, "application name");
      return true;
    } catch (RuntimeException e) {
      return false;
    }
  }

  private static String safe(String detail) {
    return detail == null ? "" : detail;
  }

  private static String firstLine(String output) {
    if (output == null || output.isBlank()) {
      return "(no detail)";
    }
    String trimmed = output.strip();
    int newline = trimmed.indexOf('\n');
    return newline < 0 ? trimmed : trimmed.substring(0, newline);
  }

  /**
   * Test hook: waits for the work queued at this moment to drain. Public because the whole of an
   * event runs on the worker — registration included — so a suite that asserts "nothing was
   * registered" has to be able to wait for the worker rather than for a row that never appears.
   */
  public void awaitIdle() throws Exception {
    worker.submit(() -> {}).get();
  }
}
