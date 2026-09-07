package eu.wohlben.qits.platform.deployments.deployments.control;

import eu.wohlben.qits.platform.deployments.environments.entity.PdDeploymentTarget;

/**
 * Where a released repository's {@link SpecSource#DECLARATION_PATH} is handed to qits-configuration,
 * before the deployment that carries it is scheduled.
 *
 * <p><b>The seam rule applied a fifth time</b>: this module owns the port and the moment it is
 * called at, {@code service} owns the one implementation that speaks HTTP ({@code
 * confighost/ConfigHostDeclarationSeed}), and the suite installs a scripted double so a clone's
 * {@code mvn verify} reaches no network. It is the {@link DeploymentExtrasSource} arrangement's
 * sibling and shares its base url, its credential and its patience — the same peer, in the other
 * direction.
 *
 * <p><b>It is NOT a {@code @FunctionalInterface}, and the difference from its sibling is the whole
 * reason.</b> {@code DeploymentExtrasSource} returns a value and a test states one in a lambda;
 * this one holds a CONVERSATION — it POSTs a body, it is answered with a status that is a decision,
 * and the two decisions ({@link DeclarationRefused.Kind}) end the deployment differently. A double
 * for that is something to script and to read back afterwards, which is a {@code @Mock} bean and
 * not a lambda.
 *
 * <p><b>Why the deployment waits for it.</b> The declaration is what the store resolves an
 * application's configuration FROM, and the extras this deployment is about to read are the resolved
 * answer. Seeding after the argv would put a container live against the previous version's
 * declaration; not seeding at all would leave the store answering for a version it has never seen.
 * So the seed happens between the rows being written and the containers being asked for, and a store
 * that refuses the file or cannot be reached ends the deployment {@code DECLARATION_REFUSED} rather
 * than deploying against configuration it could not seed.
 *
 * <p><b>One seed per deployment event, not one per row.</b> The POST is addressed by (application,
 * version) and is idempotent by content hash, so a multi-tier fan-out is one call — the declaration
 * is the repository's statement about a released version and says nothing about where it lands.
 */
public interface DeclarationSeed {

  /**
   * Hand one released version's declaration to the store, raw.
   *
   * @param applicationName the deployed application — the name the store keys declarations by, which
   *     is this deployment's own application name and therefore already the {@code application:}
   *     override where the file states one
   * @param version the released CalVer stamp the declaration was read at
   * @param target which plane this application deploys on, which is the store's {@code
   *     deploymentTarget} — a platform-plane declaration resolves against the platform's own
   *     overrides rather than a tier's
   * @param rawYaml the file's bytes as the git host served them, unparsed
   * @throws DeclarationRefused the store refused the file, or could not be reached within the
   *     budget. Either way this deployment does not run.
   */
  void seed(String applicationName, String version, PdDeploymentTarget target, String rawYaml);
}
