package eu.wohlben.qits.platform.deployments.events;

/**
 * One placement of an application in the platform's navigation, carried by {@link
 * DeploymentActive}. <b>It belongs to the application, not to a route</b> — that is the whole
 * change from the label a primary endpoint used to carry: one deployment can appear under several
 * headings, and none of them is a property of a path prefix.
 *
 * <p>{@code slot} is the heading the shell renders it under, from a closed vocabulary the spec
 * parser is the guard of: {@code services.details}, {@code daemons.details}, {@code libs.details},
 * {@code frontends.details}, {@code cli.details}, {@code images.details}, {@code project.detail},
 * {@code platform} and {@code system}. A consumer that meets a word outside it refuses that entry
 * rather than inventing a heading.
 *
 * <p>{@code position} is the repository's own number, not a rank: the consumer sorts on it, and
 * two applications naming the same number is an ordinary tie the consumer breaks by label.
 *
 * <p>{@code subpath} is the view this entry opens, relative to the scope the shell composes —
 * {@code api-docs} under {@code /<project>/<category>/<repository>/} — and null is the entry every
 * application declared before the field existed: the application's root under that same scope. It
 * is a client-side route segment, never an edge route, which is why no published-route validation
 * touches it.
 */
public record NavigationEntry(String slot, String label, int position, String subpath) {

  public NavigationEntry {
    subpath = subpath == null || subpath.isBlank() ? null : subpath;
  }

  /** The placement every entry was before subpaths existed: the application's root under scope. */
  public NavigationEntry(String slot, String label, int position) {
    this(slot, label, position, null);
  }
}
