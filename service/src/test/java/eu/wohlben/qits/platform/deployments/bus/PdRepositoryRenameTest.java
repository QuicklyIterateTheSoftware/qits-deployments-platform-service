package eu.wohlben.qits.platform.deployments.bus;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.eventstream.control.EventFrame;
import eu.wohlben.qits.platform.deployments.deployments.control.ReleaseAcceptance;
import eu.wohlben.qits.platform.deployments.deployments.entity.PdOwedRelease;
import eu.wohlben.qits.platform.deployments.deployments.persistence.PdOwedReleaseRepository;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * <b>A repository renamed in qits-projects is renamed on this component's acceptance ledger, so an
 * owed release keeps addressing a blob that exists.</b>
 *
 * <p>The defect, measured 2026-09-07. {@code pd_owed_release} is the only durable place in this
 * schema that stores a repository NAME, and {@code OwedReleaseSweep} rebuilds the whole announcement
 * out of that row — another process, after a cutover, with the event long since claimed. So a row
 * still naming yesterday's name hands {@code RepositoryRef} both halves of a public address and the
 * spec read goes to {@code /git/<projectId>/<oldName>}, which qits-githost stopped serving the
 * moment the rename committed. The id route does not rescue it — {@code /git/<repoId>} is refused
 * for every caller that is not qits-projects and a 403 is classified retryable — so the release
 * holds sixty minutes at {@code SPEC_UNREADABLE} for nothing. Two renames happened live on the
 * platform that day.
 *
 * <p><b>It drives {@code onFrame} directly rather than through a stream</b>, exactly as {@code
 * PdBusReleaseIntakeTest} does and for the same reason: the bus is dark in the suite, and what
 * belongs here is this component's half — the decode, the guards, and what lands in the column. The
 * funnel, the claim ledger and the catch-up sweep are the library's and are proved in its own
 * repository.
 *
 * <p><b>A row is staged by hand</b>, the {@code PdOwedReleaseTest} shape: an obligation carrying the
 * old name is exactly the state a release accepted before the rename leaves behind, and writing it
 * is the whole fixture. Every method uses repository ids and names of its own, because the suite
 * shares one embedded postgres across classes and this door's write is keyed by repository id.
 */
@QuarkusTest
public class PdRepositoryRenameTest {

  @Inject PdRepositoryRenamedSubscriber subscriber;
  @Inject PdOwedReleaseRepository owed;
  @Inject ReleaseAcceptance acceptance;

  @Test
  public void theConsumerIdIsNewStorageAndThisConsumerReplaysTheWholeLog() {
    // A NEW id: it shares no watermark with pd-software-released, whose ledger is measured in
    // SoftwareRelease rows and says nothing about which renames have been applied.
    assertEquals("pd-repository-rename", subscriber.consumerId());
    assertEquals(Set.of("RepositoryRenamed"), subscriber.signatures());

    // TRUE here, and false for pd-software-released — the asymmetry is the reason each of them
    // states its answer rather than inheriting the default. That consumer replays DEPLOYMENTS, so
    // an epoch replay would redeploy the platform's whole release history in log order. This one
    // replays a NAME CORRECTION: a projection repair, bounded by the signature filter to the
    // handful of RepositoryRenamed frames the log holds, and converging because the write is an
    // UPDATE keyed by repository id — A→B then B→C in log order lands on C. Started at the head
    // instead, a fresh consumer would miss the two renames of 2026-09-07, which are the renames
    // this door was written for.
    assertTrue(
        subscriber.replayFromEpoch(),
        "a rename this component never saw is a durable row still addressing a dead name");
  }

  @Test
  public void anOwedReleaseOfARenamedRepositoryAddressesTheNewName() {
    String repoId = "rename-plain-" + UUID.randomUUID();
    String id = stageOwed(repoId, "qits", "rename-plain-old", null);

    subscriber.onFrame(renameFrame("qits", repoId, "rename-plain-old", "rename-plain-new"));

    assertEquals("rename-plain-new", rowOf(id).repoName, "the address the sweep will read it at");
    assertEquals(repoId, rowOf(id).repoId, "the storage id is what a rename does NOT change");
  }

  @Test
  public void aNullProjectIdIsFilledInAndADifferentOneIsLeftExactlyWhereItIs() {
    // Two rows, one event, opposite answers — because the two are different questions.
    // RepositoryRef.nameAddressed() needs BOTH halves, so a row with a name and no project still
    // falls back to the refused id route and is worth completing. But a rename is not a MOVE: a row
    // already naming another project is not this event's to correct, and overwriting it would be
    // this component inventing a repository's ownership out of an event that never claimed one.
    String repoId = "rename-project-" + UUID.randomUUID();
    String halfAddressed = stageOwed(repoId, null, "rename-project-old", null);
    String elsewhere = stageOwed(repoId, "some-other-project", "rename-project-old", null);

    subscriber.onFrame(renameFrame("qits", repoId, "rename-project-old", "rename-project-new"));

    assertEquals("qits", rowOf(halfAddressed).projectId, "a null is the one thing filled in");
    assertEquals("rename-project-new", rowOf(halfAddressed).repoName);
    assertEquals(
        "some-other-project",
        rowOf(elsewhere).projectId,
        "a non-null project id is never overwritten by a rename");
    assertEquals(
        "rename-project-new",
        rowOf(elsewhere).repoName,
        "and the name moves regardless — the project is what this event does not restate");
  }

  @Test
  public void aSettledRowIsRenamedToo() {
    // The decision worth pinning. This column is the ADDRESS the spec is read at, not a record of
    // what the repository was called at acceptance time — an address that no longer resolves is
    // worth nothing as history, and the settled EXHAUSTED row is exactly the one a person opens
    // when they go looking for why a release never deployed.
    String repoId = "rename-settled-" + UUID.randomUUID();
    String settled = stageOwed(repoId, "qits", "rename-settled-old", Instant.now());
    String stillOwed = stageOwed(repoId, "qits", "rename-settled-old", null);

    subscriber.onFrame(renameFrame("qits", repoId, "rename-settled-old", "rename-settled-new"));

    assertEquals("rename-settled-new", rowOf(settled).repoName);
    assertNotNull(rowOf(settled).settledAt, "renamed, not resurrected — it is still settled");
    assertEquals("rename-settled-new", rowOf(stillOwed).repoName);
  }

  @Test
  public void aRenameOfARepositoryThisComponentOwesNothingForIsACleanNoOp() {
    // The ordinary case, and most of what an epoch replay walks: this platform holds far more
    // repositories than this component has ever accepted a release from. It is a debug line and no
    // write, and — the half worth asserting — it touches nobody else's rows.
    String bystander = "rename-bystander-" + UUID.randomUUID();
    String untouched = stageOwed(bystander, "qits", "rename-bystander-name", null);

    assertDoesNotThrow(
        () ->
            subscriber.onFrame(
                renameFrame(
                    "qits",
                    "rename-unknown-" + UUID.randomUUID(),
                    "rename-unknown-old",
                    "rename-unknown-new")));

    assertEquals("rename-bystander-name", rowOf(untouched).repoName);
    assertEquals("qits", rowOf(untouched).projectId);
  }

  @Test
  public void anUnreadablePayloadIsSwallowedRatherThanThrownAndWritesNothing() {
    // A throw here rolls the library's claim back and leaves the event owed FOREVER — offered again
    // on every sweep with the watermark stuck behind it, so one poison event stops this consumer's
    // catch-up. Retrying a payload that will not parse changes nothing, so it is warned about and
    // settled.
    String repoId = "rename-poison-" + UUID.randomUUID();
    String id = stageOwed(repoId, "qits", "rename-poison-old", null);

    assertDoesNotThrow(() -> subscriber.onFrame(frame("not json")));

    assertEquals("rename-poison-old", rowOf(id).repoName, "nothing was written");
  }

  @Test
  public void aFrameNamingNoRepositoryOrNoNewNameIsSettledRatherThanApplied() {
    // Both halves of the guard, and both are permanent: an event missing either field will be
    // missing it on the thousandth sweep too. Canonical JSON is NON_NULL, so an absent field is a
    // MISSING KEY rather than a null one — which is exactly how these two frames are written.
    String repoId = "rename-partial-" + UUID.randomUUID();
    String id = stageOwed(repoId, "qits", "rename-partial-old", null);

    assertDoesNotThrow(
        () ->
            subscriber.onFrame(
                frame(
                    ("{\"newName\":\"rename-partial-new\",\"oldName\":\"rename-partial-old\","
                            + "\"projectId\":\"qits\",\"renamedAt\":\"%s\"}")
                        .formatted(RENAMED_AT))));
    assertDoesNotThrow(
        () ->
            subscriber.onFrame(
                frame(
                    ("{\"oldName\":\"rename-partial-old\",\"projectId\":\"qits\","
                            + "\"renamedAt\":\"%s\",\"repositoryId\":\"%s\"}")
                        .formatted(RENAMED_AT, repoId))));

    assertEquals("rename-partial-old", rowOf(id).repoName, "neither frame moved anything");
    assertNull(rowOf(id).settledAt);
  }

  // --- helpers ----------------------------------------------------------------------------------

  /** When the rename committed — {@code occurredAt}, and the only timestamp in this payload. */
  private static final String RENAMED_AT = "2026-09-07T10:15:30Z";

  /**
   * One rename as qits-projects publishes it: canonical JSON, <b>alphabetical</b> keys, NON_NULL.
   *
   * <p>{@code eventId} is deliberately absent from the body. {@code RepositoryRenamed} implements
   * {@code QitsEvent}, and the library's mix-in keeps everything that interface declares OUT of the
   * canonical payload — identity travels in the envelope, which here is the frame's own id.
   */
  private static EventFrame renameFrame(
      String projectId, String repositoryId, String oldName, String newName) {
    return frame(
        ("{\"newName\":\"%s\",\"oldName\":\"%s\",\"projectId\":\"%s\",\"renamedAt\":\"%s\","
                + "\"repositoryId\":\"%s\"}")
            .formatted(newName, oldName, projectId, RENAMED_AT, repositoryId));
  }

  private static EventFrame frame(String payload) {
    return new EventFrame(
        UUID.randomUUID().toString(),
        "RepositoryRenamed",
        Instant.now(),
        payload,
        null,
        null,
        null);
  }

  /**
   * The acceptance-ledger row a release taken off the bus leaves behind, written by hand. Returns
   * its id, which is what every assertion here reads back by.
   *
   * <p><b>{@code accepted_by} is THIS process's instance id, and that is load-bearing rather than
   * cosmetic.</b> It is the re-drive predicate: a row carrying this process's id is on its worker
   * queue and is left alone however long it sits there, which is exactly the state a release
   * accepted a moment before the rename is in. Stamped with anybody else's — or with null — the row
   * would look orphaned, and the suite shares one database across classes, so {@code
   * PdOwedReleaseTest}'s sweep would pick these fixtures up and try to deploy them.
   */
  private String stageOwed(String repoId, String projectId, String repoName, Instant settledAt) {
    String id = UUID.randomUUID().toString();
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              PdOwedRelease row = new PdOwedRelease();
              row.id = id;
              row.eventId = UUID.randomUUID().toString();
              row.applicationName = "rename-app";
              row.version = "2026.907.101530";
              row.packageName = "qits/rename-app";
              row.repoId = repoId;
              row.projectId = projectId;
              row.repoName = repoName;
              row.acceptedBy = acceptance.instanceId();
              row.acceptedAt = Instant.now();
              row.attempts = 1;
              row.settledAt = settledAt;
              owed.persist(row);
            });
    return id;
  }

  private PdOwedRelease rowOf(String id) {
    // In a transaction of its own, and re-read every time: the door's write is a bulk JPQL UPDATE,
    // which bypasses the persistence context — a row held from before the event would still be
    // carrying the old name and the assertion would be about the session rather than the database.
    return QuarkusTransaction.requiringNew()
        .call(() -> owed.findByIdOptional(id).orElseThrow());
  }
}
