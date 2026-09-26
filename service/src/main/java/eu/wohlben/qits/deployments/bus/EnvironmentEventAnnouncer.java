package eu.wohlben.qits.deployments.bus;

import eu.wohlben.qits.deployments.deployments.control.EnvironmentAnnouncer;
import eu.wohlben.qits.deployments.events.EnvironmentChanged;
import eu.wohlben.qits.deployments.events.EnvironmentCreated;
import eu.wohlben.qits.deployments.events.EnvironmentDeleted;
import eu.wohlben.qits.eventstream.QitsEvent;
import eu.wohlben.qits.eventstream.QitsEventBus;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.control.ActivateRequestContext;
import jakarta.inject.Inject;

/**
 * Hands a tier's three lifecycle events to {@link QitsEventBus}. {@link DeployEventAnnouncer}'s
 * sibling on the producing side, implementing {@link EnvironmentAnnouncer}, and here for the same
 * reason: the {@code deployments} module knows nothing of the bus, so the implementation of its
 * seam lives in {@code bus/}.
 *
 * <p><b>Why this exists at all.</b> The environment set was a static list in qits-edge's
 * configuration, which drifted the moment anybody created or renamed a tier. The edge follows
 * projects over the bus already; these three events are what let it follow environments the same
 * way, from the component that actually owns the rows.
 *
 * <p><b>No {@code cause} argument, unlike {@link DeployEventAnnouncer}.</b> These calls are made on
 * the request thread that served the environment door, where {@code CausationScope} is still
 * standing, so {@code publish(event)} reads the right parent itself — see {@link
 * EnvironmentAnnouncer}.
 *
 * <p>{@code @ActivateRequestContext} is carried anyway, for the reason {@link DeployEventAnnouncer}
 * carries it: the outbox opens its own transaction when the inline PUT does not land, and it needs a
 * context to open one in. The environment door is an HTTP request today and always has one — the
 * annotation is what keeps that from being a precondition, since a bootstrap or a future scheduled
 * caller would have none, and its absence would only show as a runtime failure in the one case
 * nobody exercises. It sits on the three methods and not on the private one because an interceptor
 * binding only applies to a call that crosses the bean's proxy.
 */
@ApplicationScoped
public class EnvironmentEventAnnouncer implements EnvironmentAnnouncer {

  @Inject QitsEventBus bus;

  @Override
  @ActivateRequestContext
  public void onCreated(EnvironmentCreated event) {
    publish(event);
  }

  @Override
  @ActivateRequestContext
  public void onChanged(EnvironmentChanged event) {
    publish(event);
  }

  @Override
  @ActivateRequestContext
  public void onDeleted(EnvironmentDeleted event) {
    publish(event);
  }

  private void publish(QitsEvent event) {
    bus.publish(event);
  }
}
