package eu.wohlben.qits.deployments.events;

import eu.wohlben.qits.eventstream.QitsEvent;
import java.time.Instant;
import java.util.UUID;

/**
 * A tier now exists. One of the three statements this component makes about an <b>environment's own
 * lifecycle</b>, as opposed to the four it makes about a deployment.
 *
 * <p><b>Why a lifecycle event at all, when {@link DeploymentActive} already names a tier.</b>
 * Deriving the environment set from deployments was considered and rejected: {@code
 * DeploymentActive} says "a change is live", not "this tier exists". A tier created this morning
 * would be invisible until something deployed into it — which may be never — and a tier deleted or
 * renamed would go on existing in every consumer's projection for ever, because nothing on that
 * event says a tier stopped being. The set of environments is a lifecycle fact and needs lifecycle
 * events.
 *
 * <p><b>{@code environmentId} is the identity</b>, on this event and on its two siblings; see {@link
 * EnvironmentChanged}, where the argument matters most.
 *
 * <p><b>{@code environmentName} is the slug a person sees</b> — {@code dev}, {@code preprod} — and
 * is what a consumer displays, addresses with, and builds hostnames out of. It is mutable: a rename
 * arrives as {@link EnvironmentChanged} carrying the same id and a different name.
 *
 * <p><b>The field is {@code environmentName} and CANNOT be called {@code name}</b>, which is worth
 * a paragraph because the short spelling is the obvious one and it fails silently in two directions
 * at once. {@link eu.wohlben.qits.eventstream.QitsEvent} declares {@code name()} as the event's own
 * human label, defaulting to the signature — so a record component called {@code name} <b>overrides
 * it</b>. The event log would then show {@code preprod} where it should show {@code
 * EnvironmentCreated}; and because the interface's four methods are excluded from the canonical
 * payload by design, the tier's name would be stripped out of the payload entirely, leaving a
 * consumer an id and nothing to call it. Neither is a compile error. {@code environmentName} is
 * also what {@link DeploymentActive} and its three siblings already spell, so the wire reads the
 * same way across every event this component publishes.
 *
 * <p><b>{@code designated} is the platform-environment flag</b>: the one tier a release enters the
 * platform at. At most one environment carries it, and the designation MOVES rather than being set —
 * so a consumer that tracks it treats "this one is designated now" as implying the previous holder
 * is not, rather than waiting for a second event to say so.
 *
 * <p>The bundle network is deliberately absent. It is a docker-runtime detail of how this component
 * wires containers together, no consumer of this bus deploys anything, and a name that means
 * something only on the deployment host is worse on the wire than no field.
 *
 * <p>{@code occurredAt} is {@code createdAt}, the value written on the row.
 */
public record EnvironmentCreated(
    UUID eventId, String environmentId, String environmentName, boolean designated, Instant createdAt)
    implements QitsEvent {

  public EnvironmentCreated {
    if (eventId == null) {
      eventId = UUID.randomUUID();
    }
  }

  /** The constructor a publisher uses: the facts, with the identity taken care of. */
  public EnvironmentCreated(
      String environmentId, String environmentName, boolean designated, Instant createdAt) {
    this(null, environmentId, environmentName, designated, createdAt);
  }

  @Override
  public Instant occurredAt() {
    return createdAt;
  }
}
