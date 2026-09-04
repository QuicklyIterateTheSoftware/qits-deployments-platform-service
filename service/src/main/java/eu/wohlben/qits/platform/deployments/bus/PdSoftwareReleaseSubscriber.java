package eu.wohlben.qits.platform.deployments.bus;

import eu.wohlben.qits.eventstream.QitsDurableEventListener;
import eu.wohlben.qits.eventstream.control.CanonicalJson;
import eu.wohlben.qits.eventstream.control.EventFrame;
import eu.wohlben.qits.platform.deployments.deployments.control.PackageNames;
import eu.wohlben.qits.platform.deployments.deployments.control.ReleaseAnnouncements;
import eu.wohlben.qits.platform.deployments.deployments.control.ReleaseTips;
import eu.wohlben.qits.platform.deployments.deployments.control.RepositoryRef;
import eu.wohlben.qits.platform.deployments.environments.error.BadRequestException;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.Set;
import java.util.UUID;
import org.jboss.logging.Logger;

/**
 * The bus door: qits-ci's {@code SoftwareRelease}, consumed durably, announced to the same {@link
 * ReleaseAnnouncements#announce} the HTTP intake calls.
 *
 * <p><b>This replaces the {@code BuildSuccessful} subscriber outright, and the replacement is the
 * epic.</b> A green build no longer deploys anything. What deploys is a release: a version was
 * minted, a tag was pushed, an image was published under that tag, and this is the event that says
 * so. The old subscriber is gone rather than dormant — two doors, one keyed on a branch and one on
 * a tag, would deploy the same application from two coordinates and race each other's cutover.
 *
 * <h2>What is selected, and what a release means here</h2>
 *
 * <p>qits-ci publishes <b>one event per released artifact</b> — a maven jar, an npm package, a docs
 * bundle and a docker image of one release are four events sharing a version. Only {@code
 * packageType: "docker"} names something this component can put live, so {@link #selects} answers
 * false to everything else and those events are stored nowhere at all. That is what keeps the claim
 * ledger proportional to the deployments rather than to the release log.
 *
 * <p><b>The application name comes out of {@code packageName}, not out of the repository.</b> A
 * {@code SoftwareRelease} carries {@code repoId}, {@code repository} (the same string under the
 * platform's newer name for it), an optional {@code projectId} and, since 2026-09-04, an optional
 * {@code repoName}. What it also carries is the package it published, registry-unqualified: {@code
 * qits/qits-ci}. {@link PackageNames} takes the image path out of it, and <b>that</b> string is the
 * application everywhere below — never {@code repoName}. The two are genuinely different facts: a
 * repository may declare an {@code application} of its own and a repository may be renamed without
 * moving the service that runs from it, so deriving the deployed identity from the repository's
 * name would be exactly the coupling {@code packageName} exists to avoid.
 *
 * <p><b>{@code repoName} is for the ADDRESS, and for nothing else.</b> qits-githost serves the same
 * blob under two routes — the public {@code /git/<projectId>/<repoName>} and the internal {@code
 * /git/<repoId>} — and only the second needs no resolver. The event carried no name until
 * 2026-09-04, so this door could only ever build the id-addressed reference; it binds the field now
 * and hands the whole pair to {@link RepositoryRef}, which takes the public route when both halves
 * are present and the id route when either is missing. Nothing downstream had to change: the choice
 * has always been the ref's, this door was simply never able to give it anything to choose from.
 *
 * <p><b>{@code projectId} and {@code repoName} may both be absent and that is tolerated rather than
 * handled.</b> An older publisher — anything that fired before the field landed, and any event
 * replayed from before it — carries neither, binds both to null, and takes the id route byte for
 * byte as it always did. Half a pair takes it too: {@code /git/<projectId>/} names no repository and
 * {@link RepositoryRef#nameAddressed()} says so itself.
 *
 * <h2>What the library gives, and what it does not</h2>
 *
 * <p>Given: exactly-once <b>effect</b> per {@code (consumerId, event id)}. A live frame and a
 * catch-up row take one funnel, the claim and this handler commit together, and a duplicate arrival
 * finds the claim and is dropped. So nothing here counts, deduplicates or remembers an event id.
 *
 * <p>Not given: order. Catch-up delivers late, so a <b>different, older</b> release can arrive after
 * a newer one is already live — which for this consumer is the difference between a deployment and
 * a rollback nobody asked for. {@link ReleaseTips} is the collapse, and it is mandatory rather than
 * defensive. It compares the VERSIONS rather than the events' timestamps, which is the one thing
 * releases make easier than builds.
 *
 * <h2>Failure, which is the other thing that cannot be got wrong</h2>
 *
 * <p>A throw out of {@link #onFrame} rolls the claim back and leaves the event owed <b>forever</b>:
 * it is offered again on every sweep and the watermark stays behind it, so one poison event stops
 * this consumer's catch-up. So the rule here is the library's: <b>swallow what cannot be fixed by
 * retrying, throw only what can.</b> A payload that will not parse, one whose package names no
 * application, or one whose identifiers this component refuses, is warned about and settled — the
 * next attempt would refuse it identically. A database that could not answer is not caught, because
 * the next attempt is exactly what fixes it.
 *
 * <p>Registration is "be a bean": the dispatcher injects {@code Instance<QitsDurableEventListener>},
 * which is what ArC counts as a use, so no {@code @Unremovable} is needed — and {@code
 * PdEventstreamDarknessTest} asserts that rather than trusting it, since a removed listener
 * subscribes to nothing and says nothing about it.
 */
@ApplicationScoped
public class PdSoftwareReleaseSubscriber implements QitsDurableEventListener {

  private static final Logger LOG = Logger.getLogger(PdSoftwareReleaseSubscriber.class);

  /**
   * The event name qits-ci publishes under — {@code SoftwareRelease}'s simple class name, which is
   * what {@code QitsEvent.signature()} derives and what qits-events stores in the row's {@code
   * name} column.
   *
   * <p>A string rather than a class, because this component has <b>no compile-time dependency on
   * another context</b> and does not grow one for an event: the payload is a handful of strings on
   * a wire. The cost is that a rename in qits-ci is silent here, which is the cost every cross-repo
   * contract in this component already carries.
   */
  static final String SIGNATURE = "SoftwareRelease";

  /** The only {@code packageType} that names something this platform can put live. */
  static final String DEPLOYABLE_PACKAGE_TYPE = "docker";

  /**
   * This consumer's storage key, in {@code consumed_event} and {@code consumer_watermark}.
   *
   * <p><b>It is deliberately a NEW id</b>, not the retired {@code pd-build-succeeded} renamed. The
   * two consumers want different events and the old one's ledger says nothing about this one's
   * work: reusing the string would carry a watermark measured in {@code BuildSuccessful} rows into
   * a consumer that reads {@code SoftwareRelease}, which is a watermark about the wrong log. The
   * old claims are orphaned on purpose and are pruned by the library's own horizon.
   *
   * <p><b>Never change it again.</b> A new value is a brand-new consumer with the head-init below,
   * which silently skips everything in between. It is a name a person chose precisely so it
   * survives this class being renamed or moved.
   */
  static final String CONSUMER_ID = "pd-software-released";

  /**
   * The fields of the payload this component acts on. The event carries {@code repository} as well
   * — the same string as {@code repoId}, kept for consumers written before the rename — and it is
   * deliberately not bound: two names for one value invite a reader to compare them. Unknown fields
   * are ignored by the library's mapper, which is what lets qits-ci add another.
   *
   * <p><b>{@code projectId} and {@code repoName} are NULLABLE and their absence is spelled as a
   * missing key</b>: qits-ci publishes with {@code NON_NULL}, so a release from a run with no
   * project carries no {@code projectId} at all, and an event published before {@code repoName}
   * existed — or replayed from before it — carries no {@code repoName}. Both bind to null, and null
   * is a complete answer: {@link RepositoryRef} falls back to the id route, which is what this door
   * did for every release it has ever handled.
   */
  public record SoftwareReleasePayload(
      String repoId,
      String projectId,
      String repoName,
      String version,
      String packageType,
      String packageName) {}

  @Inject ReleaseAnnouncements announcements;

  @Inject ReleaseTips tips;

  @Override
  public String consumerId() {
    return CONSUMER_ID;
  }

  @Override
  public Set<String> signatures() {
    return Set.of(SIGNATURE);
  }

  /**
   * <b>This consumer initializes at the HEAD of the log, and it says so rather than inheriting
   * it.</b>
   *
   * <p>{@code false} is the library's default, so the override is a statement and not a change: a
   * consumer whose watermark has never been written starts at the newest event the log holds and
   * consumes from there. The alternative — {@code true}, replay from the epoch — would, on the very
   * first boot after this ships, hand this handler every {@code SoftwareRelease} qits-ci has ever
   * published and redeploy the platform's whole release history in log order, ending on whichever
   * version happened to be last. The collapse in {@link ReleaseTips} would keep that monotonic per
   * application, which makes it survivable and not correct: nobody asked for those deployments.
   *
   * <p>The cost is stated rather than hidden: releases cut in the window between this build and its
   * first boot are never deployed. The recovery is the manual door — an operator posts the version
   * — which is exactly what that door is for.
   */
  @Override
  public boolean replayFromEpoch() {
    return false;
  }

  /**
   * Only docker artifacts. A release of a jar, an npm package or a docs bundle is not something
   * this component can put live, and an event this rejects is stored nowhere at all — which is what
   * keeps the claim ledger proportional to the work rather than to the log.
   *
   * <p>It reads nothing but the payload, so it cannot fail on a database and cannot leave an event
   * owed for a reason that is not about the event.
   */
  @Override
  public boolean selects(EventFrame frame) {
    SoftwareReleasePayload release = decode(frame);
    return release != null && DEPLOYABLE_PACKAGE_TYPE.equalsIgnoreCase(release.packageType());
  }

  @Override
  public void onFrame(EventFrame frame) {
    SoftwareReleasePayload release = decode(frame);
    if (release == null) {
      // Warned in decode. Returning settles the event: a payload that will not parse now will not
      // parse on the thousandth sweep either, and an event nothing can read must not block the
      // watermark of everything behind it.
      return;
    }
    if (!DEPLOYABLE_PACKAGE_TYPE.equalsIgnoreCase(release.packageType())) {
      // selects() already said so; this is the belt for a frame delivered by any other route.
      return;
    }
    if (isBlank(release.repoId()) || isBlank(release.version())) {
      LOG.warnf(
          "%s %s carries no (repoId, version) to deploy; it is skipped", frame.name(), frame.id());
      return;
    }
    String applicationName = PackageNames.applicationOf(release.packageName());
    if (applicationName == null) {
      LOG.warnf(
          "%s %s released `%s`, which names no application to deploy; it is skipped",
          frame.name(), frame.id(), release.packageName());
      return;
    }
    if (!tips.claim(applicationName, release.version())) {
      LOG.infof(
          "%s %s is not the newest release of %s any more (it names %s); it is skipped rather than"
              + " deployed over the newer one",
          frame.name(), frame.id(), applicationName, release.version());
      return;
    }
    try {
      announcements.announce(
          null,
          // Both coordinates, and the ref picks. A release that carries (projectId, repoName) is
          // read through the public /git/<projectId>/<repoName>; one that carries either half or
          // neither falls back to /git/<repoId>, which needs no resolver and is what every release
          // took before the name was published. Half a pair is no pair, and RepositoryRef says so
          // itself — this door states both fields and decides nothing.
          new RepositoryRef(release.repoId(), release.projectId(), release.repoName()),
          applicationName,
          release.version(),
          release.packageName(),
          causeOf(frame));
    } catch (BadRequestException e) {
      // An identifier this component refuses — a version that could escape an argv, an application
      // name that could escape the image path, a repository id that could escape the blob URL.
      // Retrying refuses it again, so it is settled and said out loud.
      LOG.warnf(
          "%s %s was refused: %s (%s@%s)",
          frame.name(), frame.id(), e.getMessage(), applicationName, release.version());
    }
  }

  /**
   * This frame as a cause, for the {@code causation_id} of every row the deployment writes.
   *
   * <p><b>It is read here and passed as data on purpose.</b> The dispatcher does establish a {@code
   * CausationScope} of this id around {@link #onFrame}, but {@code announce} hands the whole
   * release to {@code pd-deploy-worker} and returns — and a ThreadLocal does not follow work across
   * an executor. So the far side is told rather than left to look, which is the same trade every
   * other value on that call already makes.
   *
   * <p>Null for an id that is not a UUID: the deployment runs and loses its trace edge, never the
   * other way round. Causation is advisory, and refusing a release over it would be the one failure
   * this whole mechanism must not be able to cause.
   */
  private static UUID causeOf(EventFrame frame) {
    if (frame.id() == null) {
      return null;
    }
    try {
      return UUID.fromString(frame.id());
    } catch (IllegalArgumentException e) {
      return null;
    }
  }

  /** Null on anything that will not read as this payload, warned about once, never thrown. */
  private SoftwareReleasePayload decode(EventFrame frame) {
    try {
      return CanonicalJson.payloadTo(frame.payload(), SoftwareReleasePayload.class);
    } catch (RuntimeException e) {
      LOG.warnf("%s %s has an unreadable payload: %s", frame.name(), frame.id(), e.getMessage());
      return null;
    }
  }

  private static boolean isBlank(String value) {
    return value == null || value.isBlank();
  }
}
