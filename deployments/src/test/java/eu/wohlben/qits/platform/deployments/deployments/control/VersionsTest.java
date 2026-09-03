package eu.wohlben.qits.platform.deployments.deployments.control;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * The order two released versions are in — the comparison the monotonic collapse rests on.
 *
 * <p>Plain JUnit: it is a pure function and a booted application would prove nothing extra.
 */
class VersionsTest {

  @Test
  void anUnpaddedStampOrdersByTheNumberAndNotByTheText() {
    // THE case, and the reason this class exists. The platform's stamp is built by integer
    // arithmetic with no zero padding, so 19:30:59 is `193059` and 09:30:59 the same morning is
    // `93059`. As text "1…" sorts before "9…", which would make the LATER release read as the
    // older one — and a catch-up would then roll a nine-hour-old version over the live one.
    assertTrue("2026.731.193059".compareTo("2026.731.93059") < 0, "the trap this guards");
    assertTrue(Versions.isNewerThan("2026.731.193059", "2026.731.93059"));
    assertFalse(Versions.isNewerThan("2026.731.93059", "2026.731.193059"));
  }

  @Test
  void theSegmentsAreCompareedLeftToRight() {
    assertTrue(Versions.isNewerThan("2027.101.0", "2026.1231.235959"), "the year wins first");
    assertTrue(Versions.isNewerThan("2026.1231.0", "2026.903.235959"), "then the day");
    assertTrue(Versions.isNewerThan("2026.903.2", "2026.903.1"), "then the time");
  }

  @Test
  void oneVersionIsNeverNewerThanItself() {
    assertFalse(Versions.isNewerThan("2026.903.113443", "2026.903.113443"));
  }

  @Test
  void aVersionWithMoreSegmentsWinsTheTie() {
    assertTrue(Versions.isNewerThan("2026.903.1.1", "2026.903.1"));
    assertFalse(Versions.isNewerThan("2026.903.1", "2026.903.1.1"));
  }

  @Test
  void nothingIsOlderThanEverythingAndEqualToItself() {
    // Null is the floor a first release is compared against: it must never refuse one.
    assertTrue(Versions.isNewerThan("2026.101.0", null));
    assertFalse(Versions.isNewerThan(null, "2026.101.0"));
    assertFalse(Versions.isNewerThan(null, null));
  }

  @Test
  void aVersionThisComponentDidNotMintStillOrdersRatherThanThrowing() {
    // The manual door accepts a tag somebody pushed by hand. A non-numeric segment is compared as
    // text, which is an order rather than an exception — the guard must never be the thing that
    // fails a deployment.
    assertTrue(Versions.isNewerThan("2026.903.1-rc2", "2026.903.1-rc1"));
    assertFalse(Versions.isNewerThan("2026.903.1-rc1", "2026.903.1-rc2"));
    assertTrue(Versions.isNewerThan("2026.903.2", "2026.903.1-rc9"), "numbers still win by number");
  }

  @Test
  void anAbsurdlyLongSegmentIsTextRatherThanAnOverflow() {
    // A hostile value must not reach Long.parseLong. It is compared as text, and that is an answer.
    String huge = "2026.903." + "9".repeat(40);
    assertFalse(Versions.isNewerThan(huge, huge));
    assertTrue(Versions.compare(huge, "2026.903.1") != 0);
  }
}
