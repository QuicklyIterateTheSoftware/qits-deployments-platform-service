package eu.wohlben.qits.platform.deployments.bus;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.eventstream.QitsDurableEventListener;
import eu.wohlben.qits.eventstream.control.DurableFunnel;
import eu.wohlben.qits.eventstream.control.EventFrame;
import eu.wohlben.qits.eventstream.control.EventStreamSubscriber;
import eu.wohlben.qits.platform.deployments.deployments.control.FakeDeploymentDriver;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.enterprise.inject.Any;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.stream.StreamSupport;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.Test;

/**
 * Two facts about the shipped configuration that would otherwise only be discovered by their
 * consequences, and one of them silently.
 *
 * <p><b>The bus is dark in the suite, and dark means nothing happens.</b> Every other test class
 * here runs on the default test config, and this says what that means: the switch is off, no stream
 * was dialled, and an event offered through the library's funnel touches no table and reaches no
 * handler. Without the switch a clone-alone {@code ./mvnw verify} would redial an unresolvable host
 * and page a log nobody answers, once every thirty seconds — which reads as slowness rather than as
 * misconfiguration.
 *
 * <p><b>Both subscriber beans survive ArC.</b> {@link PdSoftwareReleaseSubscriber} and {@link
 * PdRepositoryRenamedSubscriber} are injected nowhere by name in the shipped code — each is reached
 * only through {@code Instance<QitsDurableEventListener>} — and unused-bean removal would leave a
 * deployment that subscribes to nothing, consumes nothing and says nothing to admit it. An {@code
 * Instance} injection point counts as a use, which is why no {@code @Unremovable} is needed; this is
 * the assertion that keeps that true rather than believed. It is asserted <b>per listener</b> and
 * never as a count: a size claim would go red on the commit that adds a third door, which is the one
 * moment nobody wants a test failing for a reason that is not a defect.
 *
 * <p>What is NOT asserted here is the datasource: dark does not mean absent, the {@code
 * eventstream} store opens and migrates at boot regardless, and the whole suite failing to start is
 * how that would be discovered.
 */
@QuarkusTest
public class PdEventstreamDarknessTest {

  @ConfigProperty(name = "qits.eventstream.enabled")
  boolean enabled;

  @Inject EventStreamSubscriber subscriber;

  @Inject DurableFunnel funnel;

  @Inject FakeDeploymentDriver driver;

  @Inject @Any Instance<QitsDurableEventListener> durableListeners;

  @Test
  public void theBusIsDarkOutsideADeployment() {
    assertFalse(enabled, "%test must ship qits.eventstream.enabled=false");
    assertFalse(subscriber.connected(), "a dark module dials nothing");
  }

  @Test
  public void aDarkModuleDeliversNothingToTheSubscriber() {
    driver.reset();
    EventFrame frame =
        new EventFrame(
            UUID.randomUUID().toString(),
            "SoftwareRelease",
            Instant.now(),
            "{\"packageName\":\"qits/repo-dark\",\"packageType\":\"docker\","
                + "\"repoId\":\"repo-dark\",\"version\":\"2026.903.193059\"}",
            null,
            null, null);

    assertEquals(
        DurableFunnel.Result.SKIPPED,
        funnel.offer(theSubscriber(), frame),
        "the funnel is closed while the module is dark — no claim row, no handler");
    assertEquals(List.of(), driver.applied());
  }

  @Test
  public void theSubscriberIsARegisteredDurableBean() {
    assertTrue(
        StreamSupport.stream(durableListeners.spliterator(), false)
            .anyMatch(PdSoftwareReleaseSubscriber.class::isInstance),
        "the release subscriber must survive unused-bean removal, or nothing consumes the bus");
  }

  @Test
  public void theRenameSubscriberIsARegisteredDurableBeanToo() {
    // The same claim for the second door, stated separately rather than as a count. A removed
    // rename listener is quieter than a removed release one: nothing fails, releases keep
    // deploying, and the only symptom is an owed release of a renamed repository holding sixty
    // minutes at an address that stopped resolving.
    assertTrue(
        StreamSupport.stream(durableListeners.spliterator(), false)
            .anyMatch(PdRepositoryRenamedSubscriber.class::isInstance),
        "the rename subscriber must survive unused-bean removal, or no address is ever corrected");
  }

  private QitsDurableEventListener theSubscriber() {
    return StreamSupport.stream(durableListeners.spliterator(), false)
        .filter(PdSoftwareReleaseSubscriber.class::isInstance)
        .findFirst()
        .orElseThrow();
  }
}
