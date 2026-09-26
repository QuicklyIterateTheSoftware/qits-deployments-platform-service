package eu.wohlben.qits.deployments.bus;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import eu.wohlben.qits.eventstream.QitsEvent;
import io.quarkus.runtime.annotations.RegisterForReflection;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;

/**
 * {@link EventWireReflection} lists every event this component publishes — <b>all</b> of them, not
 * the ones somebody remembered.
 *
 * <p>This is the assertion that annotation has never had, and the failure it guards is the one a
 * green suite is guaranteed to miss. On a JVM these records bind whether or not anybody registered
 * them, so the whole test suite passes with an event missing from the list; in the native binary
 * the unregistered record serializes to an empty payload, and {@code publish} does not fail — it
 * sends {@code {}}. The symptom is a consumer reading a frame of the right name with no fields in
 * it, weeks later, and nothing anywhere saying why.
 *
 * <p><b>It is written as a closure check and never as a count.</b> Asserting "ten targets" would go
 * red on the commit that adds an eleventh, which is the one moment a failing test teaches nothing.
 * Asserting "every {@link QitsEvent} in the events jar is in the list" goes red on exactly the
 * commit that adds one and forgets it, naming the class.
 *
 * <p>Plain JUnit and no container: the annotation is metadata on a class, and the set it should
 * cover is read off the classpath. ArchUnit is here only as a class-file reader — it is already a
 * test dependency for {@code ArchRulesTest} — because the alternative is a hand-maintained list,
 * which is the very thing this test exists to stop trusting.
 */
class PdEventWireRegistrationTest {

  /** The jar of plain event records this component publishes. */
  private static final String EVENTS_PACKAGE = "eu.wohlben.qits.deployments.events";

  @Test
  public void everyPublishedEventIsRegisteredForReflection() {
    Set<String> registered = registeredTargets();
    Set<String> unregistered = new TreeSet<>();
    for (JavaClass candidate : eventsOnTheClasspath()) {
      if (!registered.contains(candidate.getName())) {
        unregistered.add(candidate.getName());
      }
    }
    assertEquals(
        Set.of(),
        unregistered,
        () ->
            "these QitsEvent records are published but absent from EventWireReflection — in the"
                + " native binary each would publish an empty payload rather than failing: "
                + unregistered);
  }

  @Test
  public void theEnvironmentLifecycleIsOnTheWire() {
    // Named explicitly beside the closure check, because these three are the reason a consumer can
    // follow the tier set at all: the edge reads them instead of its old static configuration.
    Set<String> registered = registeredTargets();
    for (String event :
        List.of("EnvironmentCreated", "EnvironmentChanged", "EnvironmentDeleted")) {
      assertTrue(
          registered.contains(EVENTS_PACKAGE + "." + event),
          () -> event + " must be registered for reflection, or the edge reads an empty payload");
    }
  }

  @Test
  public void theEventsJarIsActuallyOnTheClasspathHere() {
    // The closure check above passes vacuously if nothing is found, which is exactly how a
    // scanning test rots into a no-op. This is what stops that happening quietly.
    assertFalse(
        eventsOnTheClasspath().isEmpty(),
        "no QitsEvent found in " + EVENTS_PACKAGE + " — the closure check would be vacuous");
  }

  private static Set<String> registeredTargets() {
    RegisterForReflection annotation =
        EventWireReflection.class.getAnnotation(RegisterForReflection.class);
    Set<String> names = new TreeSet<>();
    for (Class<?> target : annotation.targets()) {
      names.add(target.getName());
    }
    names.addAll(List.of(annotation.classNames()));
    return names;
  }

  private static List<JavaClass> eventsOnTheClasspath() {
    JavaClasses imported = new ClassFileImporter().importPackages(EVENTS_PACKAGE);
    return imported.stream()
        .filter(candidate -> candidate.isAssignableTo(QitsEvent.class))
        .filter(candidate -> !candidate.isInterface())
        .toList();
  }
}
