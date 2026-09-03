package eu.wohlben.qits.platform.deployments.deployments.control;

import java.util.UUID;

/**
 * How a released version reaches the deploy orchestration. One method, implemented by {@link
 * DeployService}, called by whatever door the release came through.
 *
 * <p><b>This replaces {@code BuildAnnouncements}, and the replacement is the point of the
 * epic.</b> A green build is no longer a reason to deploy anything. What deploys is a RELEASE: a
 * version was minted, a tag was pushed, an image was published under that tag, and only then does
 * the platform put it live. The old seam took {@code (repository, branch, commitSha)} and derived
 * an image from the sha; this one takes {@code (repository, application, version)} and derives it
 * from the version. There is no branch anywhere on this path — a release is not on a branch, it is
 * a tag — and no commit sha either, because a release does not name one.
 *
 * <p>Two doors, and neither wins: both funnel into {@link #announce} and everything downstream of
 * it — the spec read, derived registration, the request row, the queue, the health-gated cutover —
 * cannot tell them apart.
 *
 * <ul>
 *   <li>The bus ({@code bus/PdSoftwareReleaseSubscriber}) — a durable consumer of qits-ci's {@code
 *       SoftwareRelease}, and the door a deployment follows from: the publisher retries it, the log
 *       replays it after a cutover, and the eventstream library hands it over exactly once per
 *       event whichever channel delivered it.
 *   <li>{@code POST /platform-deployments/api/events/software-released} — the direct HTTP intake
 *       ({@code api/PdEventController}). It stays, and it stays the <b>manual and bootstrap</b>
 *       door: a bootstrap replays a lost release through it by hand, an operator redeploys or rolls
 *       back a version with it, and it is the door that still works before qits-events exists. It
 *       is fire-and-forget and nobody retries it.
 * </ul>
 *
 * <p><b>Idempotency was never this seam's job.</b> Two announcements of one version are two
 * deployments of one version: the container is named after the deployment rather than the version,
 * the predecessor search finds the first one and cuts it over. What the bus adds is a guarantee the
 * POST never had — an event is not lost when nobody was listening — and one obligation the POST
 * never had, which is ordering. A replayed event can name an <em>older</em> version than the one
 * already live, so the subscriber collapses to the tip ({@link ReleaseTips}) before it calls this.
 * That check belongs to the door, not here: the manual door is an operator choosing a version, and
 * guarding it would be refusing the choice — including the deliberate choice to go back one.
 *
 * <p><b>The application name arrives by value and is not derived from the repository.</b> A {@code
 * SoftwareRelease} carries no repository NAME at all — it carries the package it published ({@code
 * qits/qits-ci}), and the application is the image path in it. So the two coordinates travel side
 * by side: the {@link RepositoryRef} is what the spec is read by, the application name is what
 * everything else is derived from (the catalogue key, the wire alias, the container name, the image
 * tag, the provisioned database and role, the GC pin key). A repository's own {@code deployments.yml}
 * may still override the application name one step later, in {@code DeployService.deploy}, exactly
 * as it always could.
 *
 * <p><b>{@code causationId} is a parameter rather than something the far side reads off an ambient
 * scope</b>, because everything downstream runs on {@code pd-deploy-worker} and an executor hop is
 * where {@code CausationScope} — a plain ThreadLocal — dies. Each door knows the answer on its own
 * thread and states it.
 */
public interface ReleaseAnnouncements {

  /**
   * One released version of one application. Returns as soon as the release is accepted: the
   * deployment runs on this component's own worker, and the announcer — a fire-and-forget POST or a
   * bus subscriber — has nothing to do with the outcome. A bus subscriber in particular must return
   * from its handler rather than wait: it is holding the claim transaction open while it does.
   *
   * @param runId the qits-ci run that produced the release, optional and resolved against nothing.
   *     A {@code SoftwareRelease} does not carry one, so the bus door passes null and the row
   *     honestly names no build; the manual door may supply one.
   * @param repository which repository was released, in whatever coordinate the door had. A release
   *     event carries the storage id and the project but no repository name, so it is id-addressed
   *     and the spec is read through {@code /git/<repoId>}.
   * @param applicationName what this release deploys under, taken from the released package rather
   *     than from the repository. Never null and never a storage id.
   * @param version the released CalVer stamp — the git tag, and the image tag
   * @param packageName the package the release announced, verbatim and registry-unqualified, or
   *     null when the door named an application directly. Recorded on the request row so the
   *     derivation above is visible rather than re-performed.
   * @param causationId the event this announcement is the effect of, recorded on every row it
   *     produces. Null is a rootless announcement — a bootstrap's hand-made POST — and never a
   *     reason to refuse one: causation is advisory and a deployment must not fail over a column
   *     only the trace graph reads.
   * @throws eu.wohlben.qits.platform.deployments.environments.error.BadRequestException if any of
   *     the identifiers could escape an argv or overrun its column
   */
  void announce(
      String runId,
      RepositoryRef repository,
      String applicationName,
      String version,
      String packageName,
      UUID causationId);
}
