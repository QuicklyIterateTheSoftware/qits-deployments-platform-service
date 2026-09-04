package eu.wohlben.qits.platform.deployments.environments.control;

import java.util.Optional;

/**
 * The id an application is addressed by on the read surface, derived rather than stored.
 *
 * <p>A service has one row, carrying N environment links, while a deployment row names only {@code
 * (application_name, environment_id)}. The client joins the two listings on an id, so the id has to
 * be computable from both sides: {@code <environmentId>:<name>}, and {@code platform:<name>} for a
 * service that belongs to no tier.
 *
 * <p>It is also the grouping key of the rollback pins: one service name in two environments is two
 * histories, and merging them would name the wrong rollback target.
 *
 * <p>The stand-in used to read {@code singleton:}; it reads {@code platform:} now, with the rest of
 * the vocabulary. Nothing persists it, so there is nothing to migrate — but a client that cached an
 * id across the rename would fail to join, which is why it is spelled once, here.
 */
public final class ApplicationKeys {

  /**
   * Where a platform service's key stands in for an environment id — no environment can take the
   * place. An environment id is a random UUID, so this word is unambiguous wherever one is
   * expected, which is what lets the deployment listing take it as a filter value.
   */
  public static final String PLATFORM = "platform";

  private ApplicationKeys() {}

  public static String of(String environmentId, String applicationName) {
    return (environmentId == null ? PLATFORM : environmentId) + ":" + applicationName;
  }

  /** Whether a value written where an environment id goes names the platform plane instead. */
  public static boolean isPlatform(String environmentId) {
    return PLATFORM.equals(environmentId);
  }

  /**
   * The pair a key was built from — {@code environmentId} null for the platform plane, which is how
   * every table and every query in this component spells "belongs to no tier".
   */
  public record Key(String environmentId, String applicationName) {}

  /**
   * The inverse of {@link #of}, or empty for anything that is not one of these keys.
   *
   * <p>It exists because the surface grew a door that <b>acts</b> on an application rather than
   * listing one, and a door has to turn the id a client already holds back into the pair the rows
   * are keyed by. The derivation was one-way for as long as the id was only ever a join key.
   *
   * <p><b>The split is at the FIRST colon and the rest is the name</b>, which is exact rather than
   * lenient: an environment id is a UUID and an application name is a DNS label, so neither half can
   * contain one. Splitting at the last colon would be the same answer today and a different one the
   * day something malformed arrives, and this way the malformed value is refused rather than
   * silently truncated.
   */
  public static Optional<Key> parse(String applicationId) {
    if (applicationId == null) {
      return Optional.empty();
    }
    int colon = applicationId.indexOf(':');
    if (colon <= 0 || colon == applicationId.length() - 1) {
      return Optional.empty();
    }
    String plane = applicationId.substring(0, colon);
    String name = applicationId.substring(colon + 1);
    return Optional.of(new Key(isPlatform(plane) ? null : plane, name));
  }
}
