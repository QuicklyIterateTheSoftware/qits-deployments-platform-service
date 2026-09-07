package eu.wohlben.qits.platform.deployments.bus;

import eu.wohlben.qits.eventstream.QitsDurableEventListener;
import eu.wohlben.qits.eventstream.control.CanonicalJson;
import eu.wohlben.qits.eventstream.control.EventFrame;
import eu.wohlben.qits.platform.deployments.deployments.control.ReleaseAcceptance;
import eu.wohlben.qits.platform.deployments.deployments.control.RepositoryRef;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.Set;
import org.jboss.logging.Logger;

/**
 * The second bus door: qits-projects' {@code RepositoryRenamed}, consumed durably, applied to the
 * one column in this schema that stores a repository's public NAME.
 *
 * <h2>What it is for, measured 2026-09-07</h2>
 *
 * <p>qits-projects renames a repository with {@code PATCH /projects/api/repositories/{repoId}} and
 * announces it. Nothing moves on the git host — the bare is keyed by the opaque storage id — so the
 * blob is served at {@code /git/<projectId>/<newName>} the moment that row commits, and at the old
 * name never again. <b>Two live renames happened on this platform today</b>, which is what turned
 * this from a prediction into a defect.
 *
 * <p>The defect is one column. {@code pd_owed_release} (V10) durably stores {@code repo_id}, {@code
 * project_id} and {@code repo_name}, and {@code OwedReleaseSweep} rebuilds the <em>whole</em>
 * announcement out of that row — in another process, after a cutover, with the event long since
 * claimed and nothing left to ask. So a held or owed release whose repository was renamed in the
 * meantime keeps the dead name, {@link RepositoryRef} sees both halves of a public address and takes
 * the name-first route, and the spec read addresses a name that no longer resolves. <b>The id-route
 * fallback does not rescue it</b>: {@code /git/<repoId>} is refused by qits-githost's storage-client
 * guard for every caller that is not qits-projects, a 403 is classified retryable, and the release
 * becomes a sixty-minute {@code SPEC_UNREADABLE} hold for nothing. One event, correcting one
 * address, is the whole of this class.
 *
 * <h2>What it writes, and what it deliberately leaves alone</h2>
 *
 * <p>It writes {@code repo_name} on <b>every</b> {@code pd_owed_release} row of that repository id,
 * the settled ones included, and fills {@code project_id} only where the row has none. Both
 * decisions are argued at {@link ReleaseAcceptance#renameRepository}, which owns the write; the bus
 * package is an adapter and holds no SQL, exactly as {@link PdSoftwareReleaseSubscriber} holds none.
 *
 * <p><b>{@code DeployService.specRetries} is deliberately NOT touched</b>, and this is the decision
 * rather than an oversight. It is an in-memory {@code Map<String, SpecRetry>} keyed by the released
 * name, and each {@code SpecRetry} carries a {@link RepositoryRef} with exactly the staleness this
 * class exists to fix — but every entry expires within {@code SPEC_RETRY_DEADLINE} (≤60 minutes) and
 * the map is owned by the deploy worker, which is the one thread that reads it. A bus listener
 * reaching in from the library's claim transaction would add a race, on a structure whose whole
 * lifetime is shorter than the hold it would be shortening. The rows are what survive a restart and
 * the rows are what this door corrects; a held release that expires re-announces from the ledger,
 * which by then names the right address.
 *
 * <p>It writes nothing else, because there is nothing else. {@code pd_deployment_request} carries
 * {@code repo_id} and {@code project_id} and no repository name at all; {@code pd_deployment},
 * {@code pd_service} and {@code pd_resource} record an APPLICATION name and no repository identity
 * whatsoever — V1's header states that as the schema's rule. <b>An application name is not a
 * repository name</b> and must never be rewritten from one: {@code application:} in {@code
 * .config/qits/deployments.yml} exists precisely so a repository can be renamed with nothing on the
 * platform moving.
 *
 * <h2>The transaction, which is the one thing that cannot be got wrong</h2>
 *
 * <p>{@link #onFrame} runs inside the library's claim transaction, and that transaction is on the
 * {@code eventstream} datasource. Two non-XA resources in one transaction is a thing Narayana
 * refuses outright — the note {@code ReleaseTips} has carried since its floor read, and the reason
 * {@code ReleaseAcceptance} brackets every one of its writes. So this door's write takes a {@code
 * QuarkusTransaction.requiringNew()} of its own on the deployments datasource, which is
 * {@link ReleaseAcceptance#renameRepository}'s bracket rather than one spelled here.
 *
 * <p>The consequence is stated rather than hidden: the rename commits <b>before</b> the claim does,
 * so a claim that then rolls back leaves the event owed with the correction already applied. That is
 * the {@code ReleaseAcceptance} trade in miniature and it costs nothing here, because the write is a
 * converging UPDATE — replaying it lands on the same row values it landed on the first time.
 *
 * <h2>Failure</h2>
 *
 * <p>A throw out of {@link #onFrame} rolls the claim back and leaves the event owed <b>forever</b>:
 * it is offered again on every sweep and the watermark stays behind it, so one poison event stops
 * this consumer's catch-up. So the rule is the library's and the sibling's: swallow what retrying
 * cannot fix — an unreadable payload, a frame naming no repository, a frame naming no new name —
 * each with a WARN and a settled event. A database that could not answer is not caught, because the
 * next attempt is exactly what fixes it.
 *
 * <p>Registration is "be a bean": the dispatcher injects {@code Instance<QitsDurableEventListener>},
 * which is what ArC counts as a use, so no {@code @Unremovable} is needed — and {@code
 * PdEventstreamDarknessTest} asserts it of <b>both</b> listeners rather than trusting it, since a
 * removed listener subscribes to nothing and says nothing about it.
 */
@ApplicationScoped
public class PdRepositoryRenamedSubscriber implements QitsDurableEventListener {

  private static final Logger LOG = Logger.getLogger(PdRepositoryRenamedSubscriber.class);

  /**
   * The event name qits-projects publishes under — {@code RepositoryRenamed}'s simple class name,
   * which is what {@code QitsEvent.signature()} derives and what qits-events stores in the row's
   * {@code name} column.
   *
   * <p>A string rather than a class, because this component has <b>no compile-time dependency on
   * another context</b> and does not grow one for an event: the payload is five strings on a wire,
   * and the record itself lives in qits-projects' own {@code service/…/bus/} rather than in any
   * published vocabulary jar. The cost is that a rename over there is silent here — the cost every
   * cross-repo contract in this component already carries, and the one {@code
   * PdSoftwareReleaseSubscriber} states for {@code SoftwareRelease}.
   */
  static final String SIGNATURE = "RepositoryRenamed";

  /**
   * This consumer's storage key, in {@code consumed_event} and {@code consumer_watermark}.
   *
   * <p><b>A new, stable id.</b> It shares nothing with {@code pd-software-released}: that ledger is
   * a watermark measured in {@code SoftwareRelease} rows and says nothing about which renames this
   * consumer has applied, and the two answer {@link #replayFromEpoch()} differently, which one
   * watermark could not express for both.
   *
   * <p><b>Never change it.</b> A new value is a brand-new consumer, initialized by the answer below
   * and with the old ledger orphaned. It is a name a person chose precisely so it survives this
   * class being renamed or moved.
   */
  static final String CONSUMER_ID = "pd-repository-rename";

  /**
   * The rename, as qits-projects publishes it.
   *
   * <p><b>Five fields and no identity.</b> {@code RepositoryRenamed} implements {@code QitsEvent},
   * so its {@code eventId} and {@code occurredAt} are components the library's mix-in keeps OUT of
   * the canonical payload — identity travels in the envelope, and {@code renamedAt} is the
   * timestamp the payload actually carries. Unknown fields are ignored by the library's mapper,
   * which is what lets qits-projects add another without a release here.
   *
   * <p>{@code oldName} is bound for the LOG alone — nothing is looked up by it, because the row is
   * keyed by the storage id, which is the one coordinate a rename does not change.
   */
  public record RepositoryRenamedPayload(
      String projectId, String repositoryId, String oldName, String newName, String renamedAt) {}

  @Inject ReleaseAcceptance acceptance;

  @Override
  public String consumerId() {
    return CONSUMER_ID;
  }

  @Override
  public Set<String> signatures() {
    return Set.of(SIGNATURE);
  }

  /**
   * <b>This consumer replays the whole log, and it is the asymmetry with {@link
   * PdSoftwareReleaseSubscriber#replayFromEpoch()} that makes each of them state its answer rather
   * than inherit it.</b>
   *
   * <p>That one returns false because replaying it would <em>redeploy</em> the platform's release
   * history in log order. This one returns true because it replays a <b>name correction</b>: it is a
   * projection repair, which is the exact case the library's own javadoc reserves the answer for.
   * Four things make it safe as well as right:
   *
   * <ul>
   *   <li><b>A fresh consumer would start at the head and miss the two renames of 2026-09-07</b> —
   *       the renames this class was written for. Head-init here would ship a fix that fixes
   *       nothing, and the only recovery would be the hand-edit of a durable row.
   *   <li><b>{@link #signatures()} bounds the replay.</b> Catch-up queries the log WITH the name
   *       filter rather than paging the whole log, so what is replayed is the handful of {@code
   *       RepositoryRenamed} frames qits-projects has ever published — an event that exists because
   *       a person deliberately renamed something.
   *   <li><b>The write converges.</b> It is an UPDATE keyed by repository id with no read-then-write
   *       in it, so replaying A→B and then B→C in log order lands on C, which is where the
   *       repository actually is. Out-of-order delivery is the one thing a projection like this does
   *       not have to collapse for itself — there is no tip to check, only the last statement to
   *       apply.
   *   <li><b>A repository this component has never accepted a release from matches nothing</b>, and
   *       that is the ordinary case for most of the replay. Zero rows moved is a debug line.
   * </ul>
   *
   * <p>Consulted at initialization and never again: once a watermark exists this answer is not
   * asked, so flipping it later replays nothing. The door for a deliberate later re-run is {@code
   * CatchupSweeper.rebuildFromEpoch(CONSUMER_ID)}, which clears both this consumer's watermark and
   * its claim ledger before making that same replay.
   */
  @Override
  public boolean replayFromEpoch() {
    return true;
  }

  // selects() is deliberately left at the library's default — everything the signature matched.
  // There is no narrowing to make: every RepositoryRenamed is potentially about a repository whose
  // releases this component holds, and it cannot be known which without the query onFrame makes
  // anyway. An override that decoded the payload to answer would also be an override that can
  // THROW, and a throw in selects is a failure rather than a "no": the event would stay owed over a
  // question about the event rather than over the work.

  @Override
  public void onFrame(EventFrame frame) {
    RepositoryRenamedPayload rename = decode(frame);
    if (rename == null) {
      // Warned in decode. Returning settles the event: a payload that will not parse now will not
      // parse on the thousandth sweep either, and an event nothing can read must not block the
      // watermark of everything behind it.
      return;
    }
    if (isBlank(rename.repositoryId()) || isBlank(rename.newName())) {
      LOG.warnf(
          "%s %s names no (repositoryId, newName) to move; it is skipped",
          frame.name(), frame.id());
      return;
    }
    // DeploymentIdentifiers.requireRepoName is deliberately NOT run on this value, and that is a
    // decision rather than a gap. This column is STORAGE, and the read path is what validates it:
    // `RepositoryRef.validated()` checks all three coordinates at the moment they become a URL and
    // an argv, which is the boundary the charset rules exist for. Refusing the event here would
    // leave the STALE name in the row — a name that certainly no longer resolves — in place of a
    // fresh one that at worst is refused later, on the deployment that would have been refused
    // either way. The strictness belongs where the value is spent, not where it is recorded.
    int moved =
        acceptance.renameRepository(rename.repositoryId(), rename.projectId(), rename.newName());
    if (moved == 0) {
      // The ordinary case, and not worth an INFO: most of the platform's repositories have never
      // had a release accepted here, and a replay from the epoch walks every rename ever made.
      LOG.debugf(
          "%s %s renamed %s, which owes this component no release; nothing moved",
          frame.name(), frame.id(), rename.repositoryId());
      return;
    }
    LOG.infof(
        "%s %s renamed %s from `%s` to `%s`; %d accepted release(s) now address the new name",
        frame.name(),
        frame.id(),
        rename.repositoryId(),
        rename.oldName(),
        rename.newName(),
        moved);
  }

  /** Null on anything that will not read as this payload, warned about once, never thrown. */
  private RepositoryRenamedPayload decode(EventFrame frame) {
    try {
      return CanonicalJson.payloadTo(frame.payload(), RepositoryRenamedPayload.class);
    } catch (RuntimeException e) {
      LOG.warnf("%s %s has an unreadable payload: %s", frame.name(), frame.id(), e.getMessage());
      return null;
    }
  }

  private static boolean isBlank(String value) {
    return value == null || value.isBlank();
  }
}
