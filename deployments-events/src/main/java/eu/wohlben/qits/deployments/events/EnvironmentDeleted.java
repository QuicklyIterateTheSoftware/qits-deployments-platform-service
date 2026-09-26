package eu.wohlben.qits.deployments.events;

import eu.wohlben.qits.eventstream.QitsEvent;
import java.time.Instant;
import java.util.UUID;

/**
 * The tier is gone: its containers were reaped, its networks removed, and the row deleted. The last
 * of the three environment lifecycle events, and the one that makes the other two safe to act on —
 * without it a consumer's environment set could only ever grow.
 *
 * <p><b>{@code environmentId} is the identity here too</b>, and it is what a consumer removes by.
 * See {@link EnvironmentChanged} for why the id rather than the name.
 *
 * <p><b>{@code environmentName} travels anyway, and it is not a second key.</b> It is there for the log line
 * and the audit trail — "prod-old was deleted" is readable, an opaque id is not — and because a
 * consumer that has somehow never seen this tier has nothing else to say about it. It is the name as
 * of the deletion, which a rename may have moved since the tier was created.
 *
 * <p><b>The deletion is a hard row delete on this side, and the tombstone is the consumer's.</b>
 * {@code pd_environment} carries no lifecycle column and is not getting one: the row is gone, so
 * this event is the only statement that it ever went, and a consumer that needs to remember a
 * deletion — to suppress a late frame, or to keep a projection ordered — records that in its own
 * projection. qits-projects puts the tombstone in the same place.
 *
 * <p><b>{@code designated} is absent, unlike on the other two.</b> The designated tier cannot be
 * deleted — the delete refuses it, because a release would then enter nowhere — so the field could
 * only ever carry {@code false}, and a field with one possible value is noise on the wire.
 *
 * <p>{@code occurredAt} is {@code deletedAt}: when the removal committed.
 */
public record EnvironmentDeleted(
    UUID eventId, String environmentId, String environmentName, Instant deletedAt) implements QitsEvent {

  public EnvironmentDeleted {
    if (eventId == null) {
      eventId = UUID.randomUUID();
    }
  }

  /** The constructor a publisher uses: the facts, with the identity taken care of. */
  public EnvironmentDeleted(String environmentId, String environmentName, Instant deletedAt) {
    this(null, environmentId, environmentName, deletedAt);
  }

  @Override
  public Instant occurredAt() {
    return deletedAt;
  }
}
