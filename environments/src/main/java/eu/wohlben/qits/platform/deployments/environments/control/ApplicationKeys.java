package eu.wohlben.qits.platform.deployments.environments.control;

import java.util.Optional;

/**
 * The id an application is addressed by on the read surface, derived rather than stored.
 *
 * <p>A service has one row, carrying N environment links, while a deployment row names {@code
 * (application_name, environment_id)}. The client joins the two listings on an id, so the id has to
 * be computable from both sides: {@code <environmentId>:<name>}, and nothing else.
 *
 * <p><b>There is no {@code platform:} stand-in any more, and no plane in the key.</b> It stood in
 * for an environment id while a platform service carried no link and its deployments named no tier,
 * and both halves of that are gone: the plane is deleted, every service is linked into the tier it
 * runs in, and both sides of the join now take the id off the same tier. A client that cached an id
 * across the change fails to join — which is why the derivation is spelled once, here.
 *
 * <p>It is also the grouping key of the rollback pins: one service name in two environments is two
 * histories, and merging them would name the wrong rollback target.
 */
public final class ApplicationKeys {

  private ApplicationKeys() {}

  /**
   * @param environmentId the tier the application runs in. Null is tolerated and keys the entry
   *     {@code :<name>} — a row written before every deployment named a tier, and the one shape a
   *     reader can still meet.
   */
  public static String of(String environmentId, String applicationName) {
    return (environmentId == null ? "" : environmentId) + ":" + applicationName;
  }

  /** The pair a key was built from. */
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
    return Optional.of(
        new Key(applicationId.substring(0, colon), applicationId.substring(colon + 1)));
  }
}
