package eu.wohlben.qits.platform.deployments.deployments.control;

import eu.wohlben.qits.platform.deployments.environments.entity.PdDeploymentTarget;
import eu.wohlben.qits.platform.deployments.events.NavigationEntry;
import java.util.List;

/**
 * The seam that fetches a repository's deployment spec at a commit — the {@link DeploymentDriver}
 * arrangement again: this module owns the port and the state machine that calls it, {@code service}
 * owns the one implementation that speaks HTTP, and the suites install a scripted fake so a clone's
 * {@code mvn verify} reaches no network.
 *
 * <p>The seam exists because this is the component's <b>one outbound HTTP call</b>. Keeping the
 * client out of a domain module is the same rule that keeps docker out of one: the orchestration
 * must be testable without either. The merge removed the second such client — the topology used to
 * be another service and is now a repository query — so this is the only one left.
 */
public interface SpecSource {

  /** The file every repository may carry, at the path this reads it from. */
  String SPEC_PATH = ".config/qits/deployments.yml";

  /**
   * The rev a released version is read at — <b>fully qualified, and that is the point</b>.
   *
   * <p>The git host resolves a bare {@code 2026.903.113443} perfectly well, but a bare name is
   * whatever the repository happens to hold under it: a branch of that name would win, and a
   * repository is free to have one. {@code refs/tags/<version>} can only ever be the tag the
   * release pushed, which is the ref whose contents this deployment is supposed to be.
   */
  static String tagRev(String version) {
    return "refs/tags/" + version;
  }

  /**
   * Read the spec a repository declares at {@code rev}.
   *
   * <p><b>It takes the whole {@link RepositoryRef} rather than one id</b>, because the git host
   * serves the same blob under two addresses and only the caller knows which one this release has:
   * the public {@code /git/<projectId>/<repoName>} when the announcement carried the name pair, and
   * the internal {@code /git/<repoId>} when it did not. Both arms are live on the release path: a
   * {@code SoftwareRelease} has carried {@code repoName} since 2026-09-04, and one published or
   * replayed from before it still carries none.
   *
   * <p><b>{@code rev} is a git rev and not a sha</b>, and the ordinary caller passes {@link
   * #tagRev}. The startup sweep's one legacy path passes a sha, because that is what the row it is
   * adopting recorded.
   *
   * @return {@link DeploymentSpec#DEFAULTS} when the repository carries no such file at that rev
   * @throws SpecException when the file exists but could not be fetched or understood — the
   *     deployment fails on it rather than guessing a topology
   */
  SpecRead read(RepositoryRef repository, String rev);

  /**
   * A spec, and the commit the rev it was read at resolved to.
   *
   * <p><b>The commit comes back because the read already knows it and nothing else does.</b> A
   * release names a version and no sha, so without this the deployment row could record no commit
   * at all and there would be no edge from a running container back to a diff. The git host answers
   * every blob read with the resolved commit in a header, so this costs no second request and no
   * ref-resolution endpoint — which is just as well, because it has none.
   *
   * <p><b>{@code commitSha} is null when the rev could not be resolved to one</b>, which is the
   * 404 case: a repository that carries no {@code deployments.yml} gets the defaults and no commit,
   * because a missing blob says nothing about where a tag points. It is a real answer, and {@code
   * pd_deployment.commit_sha} is nullable for it.
   */
  record SpecRead(DeploymentSpec spec, String commitSha) {}

  /**
   * What a repository declares about how it is deployed. Every key optional, and the shape a
   * repository with no file at all gets is {@link #DEFAULTS}.
   *
   * <p>{@code healthPath} is the exception rather than the rule: a service that says nothing gets
   * the convention path derived from its name, and only a service whose path does not follow the
   * convention (the gateway owns the root path space) has to name one.
   *
   * <p>{@code healthCmd} <b>replaces</b> that HTTP probe rather than adjusting it: a plain image
   * with no HTTP surface — postgres is the first — declares the command that says it is ready, and
   * the parser refuses a file that sets both. Null means the HTTP probe, which is every service
   * this platform had before deployable images existed.
   *
   * <p>{@code resources} is what the repository asks to have provisioned before its container
   * starts — a database of its own, whose credential arrives as {@code QITS_RESOURCE_<NAME>_*}. An
   * empty list is every application that stores nothing, which is most of them.
   *
   * <p>{@code updateOrder} is how a replacement may overlap what it replaces — {@code start-first}
   * unless the repository says otherwise. It is a repository's answer rather than a platform-wide
   * one because only the repository knows whether two of its processes may run at once: a public
   * host port, a single-writer store or a held config volume each make the overlap impossible. See
   * {@link DeploymentDriver.UpdateOrder}.
   *
   * <p>{@code publishMode} is where a published host port is held — {@code host} unless the
   * repository says {@code ingress}. Only the repository knows, for the same reason: a front door
   * that must survive its own replacement wants the routing mesh holding its port, and everything
   * else keeps the per-node bind it has today. See {@link DeploymentDriver.PublishMode}.
   *
   * <p>{@code host} is the DNS label this application is also served at, and null means "derive
   * it": the parser does not know the application's name, exactly as it does not for a resource's
   * database. {@code browserHostDeclared} is the question that decides whether there is anything to
   * derive — a file that named {@code host} or {@code navigation-entries} asks for a host of its
   * own, and a file carrying only the retired {@code navigation} key asks for none.
   *
   * <p>{@code navigationEntries} is where the application asks to appear. Application-level and a
   * LIST, because one application sits under several headings; empty is an application that creates
   * no navigation option, which is most of them.
   *
   * <p>{@code apiDocs} is where the application's browsable API document lives, under one of its
   * published routes ({@code /ci/q/swagger-ui}). Null is a real answer — a service that documents
   * no HTTP surface — and the parser has already refused a path that sits under no route.
   *
   * <p>{@code application} is the name this repository deploys AS, and <b>null is the answer every
   * file gives today</b>: the application name is the repository's own. A file that states it
   * decouples the deployed identity from the repository name, so a repository can be renamed
   * without moving the service, the alias, the image, the database or the routes that are running.
   * The substitution is {@code DeployService.deploy}'s, the first place holding both this spec and
   * the announcement it was read for — see {@code DeploymentSpecParser} for what it costs and what
   * it cannot refuse.
   *
   * <p><b>{@code deployBranches} is read and not used here</b>, and that is deliberate — see {@link
   * #deployBranches()}.
   */
  record DeploymentSpec(
      PdDeploymentTarget target,
      boolean availableOnEnv,
      List<String> deployBranches,
      String healthPath,
      String healthCmd,
      List<ResourceSpec> resources,
      DeploymentDriver.UpdateOrder updateOrder,
      DeploymentDriver.PublishMode publishMode,
      List<String> routes,
      int upstreamPort,
      String host,
      boolean browserHostDeclared,
      List<NavigationEntry> navigationEntries,
      String apiDocs,
      String application) {

    /** A null list and an empty one are the same statement: the file named none. */
    public DeploymentSpec {
      deployBranches = deployBranches == null ? List.of() : List.copyOf(deployBranches);
      resources = resources == null ? List.of() : List.copyOf(resources);
      updateOrder = updateOrder == null ? DeploymentDriver.UpdateOrder.START_FIRST : updateOrder;
      publishMode = publishMode == null ? DeploymentDriver.PublishMode.HOST : publishMode;
      routes = routes == null ? List.of() : List.copyOf(routes);
      navigationEntries =
          navigationEntries == null ? List.of() : List.copyOf(navigationEntries);
    }

    /**
     * A spec that says nothing about the order or the publish mode takes both defaults, which is
     * most of them.
     */
    public DeploymentSpec(
        PdDeploymentTarget target,
        boolean availableOnEnv,
        List<String> deployBranches,
        String healthPath,
        String healthCmd,
        List<ResourceSpec> resources) {
      this(target, availableOnEnv, deployBranches, healthPath, healthCmd, resources, null, null);
    }

    /**
     * The pre-routing shape: no routes is the compatible, empty endpoint declaration, and no
     * {@code application} is the repository's own name — both of them what a file that says
     * nothing has always meant.
     */
    public DeploymentSpec(
        PdDeploymentTarget target,
        boolean availableOnEnv,
        List<String> deployBranches,
        String healthPath,
        String healthCmd,
        List<ResourceSpec> resources,
        DeploymentDriver.UpdateOrder updateOrder,
        DeploymentDriver.PublishMode publishMode) {
      this(
          target,
          availableOnEnv,
          deployBranches,
          healthPath,
          healthCmd,
          resources,
          updateOrder,
          publishMode,
          List.of(),
          8080,
          null,
          false,
          List.of(),
          null,
          null);
    }

    /**
     * One resource a repository declares — {@code postgresql:<name>[:<database>]}.
     *
     * <p>{@code database} is <b>null when the file omitted it</b>, and that is not a default this
     * record could fill in: the convention is {@code qits_} plus the application name without its
     * {@code qits-} prefix, and the parser does not know the application name. {@code
     * DeployService.register} resolves it, where the repository id is in hand.
     *
     * <p>There is no type field because there is one type. When a second arrives it becomes one,
     * and the grammar already carries it in the entry's first segment.
     */
    public record ResourceSpec(String name, String database) {}

    /** No file, or a file that sets nothing: an ordinary environment application. */
    public static final DeploymentSpec DEFAULTS =
        new DeploymentSpec(PdDeploymentTarget.ENVIRONMENT, false, List.of(), null, null, List.of());

    /**
     * The refs the repository declares itself deployable from — {@code deploy_branches:} in the
     * file.
     *
     * <p><b>Nothing in this component matches on it.</b> Where a build deploys is decided by the
     * environment rows: a green build deploys wherever an environment listens to its branch, on
     * either plane. The key is parsed and validated because the <b>release flow</b> reads the same
     * file for its promotion targets, and this parser is strict — an unknown key fails a
     * deployment, so a key another reader needs has to be one this reader knows. Reading it and
     * ignoring it is cheaper than two files, and far cheaper than a lenient parser.
     */
    public List<String> deployBranches() {
      return deployBranches;
    }
  }
}
