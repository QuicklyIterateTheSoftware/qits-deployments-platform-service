package eu.wohlben.qits.platform.deployments.deployments.control;

/**
 * The application a released docker package names.
 *
 * <p>A {@code SoftwareRelease} carries no repository name — it carries the package it published,
 * and for a docker artifact that is a <b>registry-unqualified OCI path</b>: {@code qits/qits-ci},
 * {@code qits/build-images/ci-base}. qits-ci states why it is unqualified in that event's own
 * javadoc: the registry is {@code qits-artifacts:8080} inside a step container and
 * {@code registry.dev.localhost:8080} outside it, so no qualified reference is portable and the
 * consumer qualifies it.
 *
 * <p><b>So the application name is the LAST segment.</b> That is the string every derivation in
 * this component takes — the catalogue key, the wire alias, the container name, the image tag the
 * deployment pulls, the provisioned database and role, the GC pin key — and it is exactly what the
 * repository name used to supply before releases became the trigger. The image reference this
 * component then builds ({@link ImageRefs}) is {@code <registry>/<repository>/<application>:<tag>},
 * which for the two-segment names every deployable application publishes is the released package
 * put back together.
 *
 * <p><b>A deeper path is accepted here and refused one layer down, and that asymmetry is
 * deliberate.</b> {@code qits/build-images/ci-base} yields {@code ci-base}, which is a perfectly
 * good dns label and a perfectly bad thing to deploy — nothing is registered for it, so
 * {@code DeployService} finds no place and does nothing, which is the same answer it gives for
 * every release of a package this platform does not run. Refusing it here would need this class to
 * know which prefixes are applications, and it does not.
 */
public final class PackageNames {

  private PackageNames() {}

  /**
   * The application a docker {@code packageName} names, or null when it names nothing usable — a
   * blank value, or a path whose last segment is empty ({@code qits/}).
   *
   * <p>The caller is expected to treat null as "this release is not addressed at anything this
   * component deploys" and settle the event rather than throw: a package name that will not resolve
   * now will not resolve on the thousandth sweep either.
   */
  public static String applicationOf(String packageName) {
    if (packageName == null) {
      return null;
    }
    String trimmed = packageName.strip();
    int slash = trimmed.lastIndexOf('/');
    String segment = slash < 0 ? trimmed : trimmed.substring(slash + 1);
    return segment.isEmpty() ? null : segment;
  }
}
