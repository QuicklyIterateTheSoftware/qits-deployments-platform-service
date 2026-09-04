package eu.wohlben.qits.platform.deployments.deployments.control;

import java.util.Comparator;

/**
 * How two released versions are ordered — <b>segment by segment, numerically</b>.
 *
 * <p><b>Lexical comparison is wrong here, and it is wrong on ordinary values rather than on edge
 * cases.</b> The platform's CalVer stamp is {@code YYYY.MMDD.HHMMSS} built by integer arithmetic
 * with <em>no zero padding</em> — deliberately, so no identifier can carry a leading zero — so
 * 19:30:59 stamps as {@code 2026.731.193059} and 09:30:59 the same morning stamps as {@code
 * 2026.731.93059}. Compared as strings, {@code "1…"} sorts before {@code "9…"} and the LATER
 * release reads as the older one. That is the exact mistake this class exists to make impossible:
 * an out-of-order catch-up would then look like progress and roll a nine-hour-old version over the
 * live one.
 *
 * <p>So each dot-separated segment is compared as a number when both sides are numeric, and
 * lexically only when one of them is not — a version this component did not mint (a hand-made
 * request through the manual door, a tag someone pushed by hand) still orders deterministically
 * rather than throwing. A version with more segments than the other wins the tie, so {@code
 * 2026.903.1.1} is after {@code 2026.903.1}.
 *
 * <p>Nothing here validates. {@link DeploymentIdentifiers#requireVersion} is the boundary check;
 * this is only the order.
 */
public final class Versions {

  /** Newest first — what a listing of released versions is usually asked for. */
  public static final Comparator<String> NEWEST_FIRST = Versions::compare;

  /** A numeric segment, bounded so a hostile value cannot overflow the parse. */
  private static final int MAX_NUMERIC_DIGITS = 18;

  private Versions() {}

  /**
   * @return a negative number when {@code left} is older than {@code right}, zero when they are the
   *     same version, positive when {@code left} is newer. Null is older than everything, and two
   *     nulls are equal — a version nobody stated cannot outrank one somebody did.
   */
  public static int compare(String left, String right) {
    if (left == null || right == null) {
      return left == null ? (right == null ? 0 : -1) : 1;
    }
    String[] leftParts = left.split("\\.", -1);
    String[] rightParts = right.split("\\.", -1);
    int shared = Math.min(leftParts.length, rightParts.length);
    for (int i = 0; i < shared; i++) {
      int segment = compareSegment(leftParts[i], rightParts[i]);
      if (segment != 0) {
        return segment;
      }
    }
    return Integer.compare(leftParts.length, rightParts.length);
  }

  /** True when {@code candidate} is strictly newer than {@code floor} — the collapse's question. */
  public static boolean isNewerThan(String candidate, String floor) {
    return compare(candidate, floor) > 0;
  }

  private static int compareSegment(String left, String right) {
    if (isNumeric(left) && isNumeric(right)) {
      return Long.compare(Long.parseLong(left), Long.parseLong(right));
    }
    return left.compareTo(right);
  }

  /**
   * Digits only, and short enough to parse as a {@code long}. A segment that is neither — {@code
   * rc1}, an empty segment, a two-hundred-digit number — is compared as text, which is an order
   * rather than an exception.
   */
  private static boolean isNumeric(String segment) {
    if (segment.isEmpty() || segment.length() > MAX_NUMERIC_DIGITS) {
      return false;
    }
    for (int i = 0; i < segment.length(); i++) {
      if (segment.charAt(i) < '0' || segment.charAt(i) > '9') {
        return false;
      }
    }
    return true;
  }
}
