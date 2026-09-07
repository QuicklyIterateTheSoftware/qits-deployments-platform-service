package eu.wohlben.qits.platform.deployments.deployments.control;

/**
 * qits-configuration would not take a released version's declaration, so the deployment does not
 * run.
 *
 * <p><b>There are two ways for that to be true and the row has to say which</b>, because they ask
 * different things of a person. A file the store REFUSED is the repository's own problem — the
 * commit carries a broken {@link SpecSource#DECLARATION_PATH} and the fix is a new release. A store
 * that could not be REACHED is the platform's — the file may be perfect, and the fix is
 * qits-configuration coming back. One word on the row with one message under it would send half the
 * readers to the wrong repository, so {@link Kind} is the distinction and the detail's first line
 * carries it.
 *
 * <p><b>Neither is retried by anything.</b> Unlike {@code SPEC_UNREADABLE}, which is held and
 * re-read because nothing was ever decided, both of these are answers: the store read the file and
 * said no, or it was asked until the budget ran out and the deployment had to be decided. See {@code
 * PdDeploymentStatus.DECLARATION_REFUSED} for why that makes the row terminal.
 */
public class DeclarationRefused extends RuntimeException {

  /** Which of the two failures this is — see the class javadoc. */
  public enum Kind {
    /** The store read the file and refused it: a 409 or a 422. The commit is broken. */
    DECLARATION_BROKEN,
    /** The store never answered within the budget. The file was never judged. */
    SERVICE_UNAVAILABLE
  }

  private final Kind kind;

  public DeclarationRefused(Kind kind, String message) {
    super(message);
    this.kind = kind;
  }

  public Kind kind() {
    return kind;
  }

  /**
   * The store read the declaration of {@code application@version} and refused it.
   *
   * <p>The excerpt is bounded by the caller: a store answering a stack trace or an HTML error page
   * must not put a page of it into a deployment row's {@code detail}, which is a text column a
   * person reads in a listing.
   */
  public static DeclarationRefused broken(
      String application, String version, int status, String excerpt) {
    return new DeclarationRefused(
        Kind.DECLARATION_BROKEN,
        "qits-configuration refused the declaration of "
            + application
            + "@"
            + version
            + " ("
            + SpecSource.DECLARATION_PATH
            + "): "
            + status
            + " — "
            + excerpt
            + ". The file at the released tag is broken; fix it and cut a new release.");
  }

  /** The store could not be reached, and the deployment is refused rather than run without it. */
  public static DeclarationRefused unavailable(
      String url, String application, String version, int attempts, String lastFailure) {
    return new DeclarationRefused(
        Kind.SERVICE_UNAVAILABLE,
        url
            + " could not accept the declaration of "
            + application
            + "@"
            + version
            + " after "
            + attempts
            + " attempts: "
            + lastFailure
            + ". qits-configuration is unreachable — the deployment is refused rather than run"
            + " against config it could not seed.");
  }
}
