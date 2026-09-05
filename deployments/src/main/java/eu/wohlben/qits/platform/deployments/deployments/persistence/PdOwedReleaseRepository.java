package eu.wohlben.qits.platform.deployments.deployments.persistence;

import eu.wohlben.qits.platform.deployments.deployments.entity.PdOwedRelease;
import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.List;
import java.util.Optional;

/**
 * Panache DAO for {@link PdOwedRelease}, the acceptance ledger.
 *
 * <p>Two questions and no more: "is this event already accepted" (the upsert key, so accepting is
 * idempotent across restarts) and "what is owed by somebody who is not here any more" (the sweep).
 * Both are index-backed by V10 — the unique {@code event_id} and the partial index over the unsettled
 * rows.
 */
@ApplicationScoped
public class PdOwedReleaseRepository implements PanacheRepositoryBase<PdOwedRelease, String> {

  /** The obligation this event already opened, if it opened one. */
  public Optional<PdOwedRelease> findByEventId(String eventId) {
    return find("eventId = ?1", eventId).firstResultOptional();
  }

  /**
   * Everything still owed that <b>this</b> process is not holding, oldest first.
   *
   * <p>The negation is the whole predicate and it is exact rather than heuristic: a row carrying
   * this process's id is on its worker queue — legitimately for an hour, since deployments are
   * serialized platform-wide — and a row carrying anybody else's belonged to a queue that did not
   * survive its JVM. A null {@code accepted_by} is the worker's own hand-back and is included, which
   * is what lets a failed discharge be retried without a restart.
   *
   * <p>{@code seq desc} is what every other listing here orders by; this one is the exception and
   * ascends, because owed work is re-driven in the order it arrived — so two releases of one
   * application replay in their real order and the monotonic collapse sees the newer one last.
   */
  public List<PdOwedRelease> listOwedByOthers(String instanceId) {
    return list(
        "settledAt is null and (acceptedBy is null or acceptedBy <> ?1) order by seq asc",
        instanceId);
  }
}
