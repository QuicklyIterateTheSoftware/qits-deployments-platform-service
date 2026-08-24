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
 */
public record NavigationEntry(String slot, String label, int position) {}
