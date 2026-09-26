package eu.wohlben.qits.deployments.deployments.control;

import eu.wohlben.qits.deployments.events.EnvironmentChanged;
import eu.wohlben.qits.deployments.events.EnvironmentCreated;
import eu.wohlben.qits.deployments.events.EnvironmentDeleted;

/**
 * The port {@link EnvironmentOperations} announces a <b>tier's own lifecycle</b> through — created,
 * changed, deleted. {@link DeployAnnouncer}'s sibling, and everything argued there about why this is
 * an interface, why the methods take the event record, and why an implementation must neither throw
 * nor block for long applies here unchanged.
 *
 * <p>Resolved via {@code Instance} and <b>absent is a supported configuration</b>: a component
 * without the bus creates and deletes tiers exactly as before and tells nobody.
 *
 * <p><b>A second port rather than four more methods on {@link DeployAnnouncer}</b>, which is the one
 * place this departs from its sibling. That port's four methods are one statement made four times
 * about ONE deployment, in a fixed order, and nothing could implement three of them sensibly. These
 * three are about a different subject entirely — the tier, which outlives every deployment into it —
 * and they are made by a different class, on a different thread, under a different transaction
 * discipline. A consumer may reasonably want the environment set and not the deployment firehose.
 *
 * <p><b>No {@code cause} parameter, and that is the difference that matters.</b> {@link
 * DeployAnnouncer} takes one because every call there happens on {@code pd-deploy-worker}, behind a
 * queue hop, where {@code CausationScope} is empty and the cause has to travel as data. These three
 * are called on the request thread of {@code POST}/{@code PATCH}/{@code DELETE
 * /deployments/api/environments}, with the scope {@code CausationServerFilter} restored from the
 * caller's header still standing — so the ambient cause is the right one and is already what {@code
 * publish(event)} reads. Passing a parameter would mean inventing a value to pass.
 *
 * <p><b>Every call is made AFTER the transaction that made the statement true</b>, never inside it.
 * An announcement of a tier that then rolls back is worse than a missing one: a consumer's
 * projection would hold a tier this component has no row for, and nothing would ever retract it.
 */
public interface EnvironmentAnnouncer {

  /** A tier exists. One call per row {@code EnvironmentService.create} committed. */
  void onCreated(EnvironmentCreated event);

  /** A tier was renamed, or the platform designation moved onto it. */
  void onChanged(EnvironmentChanged event);

  /** A tier is gone: containers reaped, networks removed, row deleted. */
  void onDeleted(EnvironmentDeleted event);
}
