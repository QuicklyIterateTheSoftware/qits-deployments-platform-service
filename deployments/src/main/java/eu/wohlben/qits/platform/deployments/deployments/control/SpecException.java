package eu.wohlben.qits.platform.deployments.deployments.control;

/**
 * The repository's deployment spec could not be read or could not be understood.
 *
 * <p>Deliberately not one of the HTTP-mapped exceptions: this never answers a caller. It ends a
 * deployment — recorded with this message in its {@code detail} — because the alternative is
 * guessing a topology, and a guessed topology puts a container on the wrong networks under the
 * wrong name. A missing file is not this: no file means every default, and every repository without
 * one behaves exactly as it did before the file existed.
 *
 * <p><b>{@link #retryable()} is the one thing a caller branches on, and it is the difference
 * between a stranded release and a slow one.</b> The read is this component's single outbound
 * call, so it inherits every way a network hop can fail for a minute and then stop failing: a
 * connection refused while the git host cuts over, a 500, a timeout, and — measured on 2026-09-04,
 * three releases stranded 13-17 minutes apiece — an intermittent 403 from qits-githost on a blob a
 * second request reads perfectly well. None of those says anything about the repository, so a
 * deployment recorded terminally {@code FAILED} on one is a release that has to be nudged by hand.
 * The deployment is recorded {@link
 * eu.wohlben.qits.platform.deployments.deployments.entity.PdDeploymentStatus#SPEC_UNREADABLE}
 * instead, and {@code DeployService} reads the file again on its observation cadence until it
 * answers or a newer version supersedes it.
 *
 * <p><b>Everything else is permanent and stays terminal</b>: a file that does not parse, a key the
 * schema refuses, a value out of range. The file exists, it was read, and reading it again will
 * produce exactly the same answer — looping on it would turn a broken commit into a permanent
 * background job. The default constructors say <em>permanent</em>, so a call site has to state
 * retryability on purpose.
 */
public class SpecException extends RuntimeException {

  /** Whether reading again may yet answer — see the class javadoc. */
  private final boolean retryable;

  public SpecException(String message) {
    this(message, false);
  }

  public SpecException(String message, Throwable cause) {
    this(message, cause, false);
  }

  public SpecException(String message, boolean retryable) {
    super(message);
    this.retryable = retryable;
  }

  public SpecException(String message, Throwable cause, boolean retryable) {
    super(message, cause);
    this.retryable = retryable;
  }

  /**
   * Whether this failure is one the same read may survive — a transport failure or a status that
   * describes the git host's moment rather than the repository's content.
   */
  public boolean retryable() {
    return retryable;
  }

  /**
   * Whether the cause of a failed spec read asks to be tried again. Anything that is not a {@code
   * SpecException} is a bug in this component rather than an outage in the git host, and a bug does
   * not become correct by being repeated every thirty seconds.
   */
  public static boolean isRetryable(Throwable failure) {
    return failure instanceof SpecException spec && spec.retryable();
  }
}
