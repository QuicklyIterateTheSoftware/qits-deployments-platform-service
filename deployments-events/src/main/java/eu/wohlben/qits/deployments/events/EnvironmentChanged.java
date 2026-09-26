package eu.wohlben.qits.deployments.events;

import eu.wohlben.qits.eventstream.QitsEvent;
import java.time.Instant;
import java.util.UUID;

/**
 * A tier's mutable facts moved: it was renamed, or the platform designation was moved onto it. The
 * middle of the three environment lifecycle events; the fields are {@link EnvironmentCreated}'s,
 * argued there.
 *
 * <p><b>{@code environmentId} is the identity, and this event is where that decision earns its
 * keep.</b> The row's id is assigned at creation and never changes; its {@code environmentName} is mutable —
 * {@code PATCH /deployments/api/environments/{id}} renames a tier, and that is a supported
 * operation rather than an accident. A consumer keyed by id therefore handles a rename as what it
 * is: an <b>in-place update</b> of one row's name. A consumer keyed by name could not. It would have
 * to guess that {@code preprod} and {@code staging} are the same tier, and having failed to, would
 * carry the old name for ever — an orphan that nothing will ever retire, because no {@link
 * EnvironmentDeleted} is coming for a tier that was never deleted.
 *
 * <p><b>That is also why there is no {@code previousName} field.</b> A previous name is only
 * interesting to a consumer that has no stable key to update under — it is the repair for the design
 * this one does not have. Carrying it would invite exactly the name-keyed projection the id exists
 * to make unnecessary, and would then have to be right on every historical frame that predates it.
 * The id is the answer; the old name is not needed, and a consumer holding one is holding a
 * duplicate key.
 *
 * <p><b>The event is the whole current state, not a diff.</b> Both mutable fields travel on every
 * occurrence whether they moved or not, so a consumer replaces the row it holds rather than working
 * out which half of the statement is news. A rename and a designation move in one {@code PATCH} are
 * one event, for the same reason.
 *
 * <p><b>What does NOT announce: the tier that LOST the designation.</b> Designating is a move — the
 * previous holder's flag goes false inside the same transaction — and only the tier named here
 * announces. A consumer that tracks the flag must therefore read this event as "this one, and
 * therefore no other", which is safe because at most one row is ever true. A consumer that only
 * wants the environment list, which is the case this event exists for, is untouched by it.
 *
 * <p>{@code occurredAt} is {@code changedAt}: when the update committed, not when the row was
 * created.
 */
public record EnvironmentChanged(
    UUID eventId, String environmentId, String environmentName, boolean designated, Instant changedAt)
    implements QitsEvent {

  public EnvironmentChanged {
    if (eventId == null) {
      eventId = UUID.randomUUID();
    }
  }

  /** The constructor a publisher uses: the facts, with the identity taken care of. */
  public EnvironmentChanged(
      String environmentId, String environmentName, boolean designated, Instant changedAt) {
    this(null, environmentId, environmentName, designated, changedAt);
  }

  @Override
  public Instant occurredAt() {
    return changedAt;
  }
}
