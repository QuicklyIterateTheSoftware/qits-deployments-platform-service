package eu.wohlben.qits.platform.deployments.deployments.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.platform.deployments.deployments.control.SpecSource.DeploymentSpec;
import eu.wohlben.qits.platform.deployments.deployments.control.SpecSource.DeploymentSpec.ResourceSpec;
import eu.wohlben.qits.platform.deployments.environments.entity.PdDeploymentTarget;
import eu.wohlben.qits.platform.deployments.events.NavigationEntry;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The strict reader of {@code .config/qits/deployments.yml}, plain JUnit — the file decides where a
 * container runs and what may reach it, so what it rejects matters as much as what it accepts, and
 * every rejection has to name the file a person has to go and fix.
 */
class DeploymentSpecParserTest {

  private static final String SOURCE = ".config/qits/deployments.yml of qits-idp@abc1234";

  private DeploymentSpec parse(String yaml) {
    return DeploymentSpecParser.parse(yaml, SOURCE);
  }

  private String messageOf(String yaml) {
    SpecException thrown = assertThrows(SpecException.class, () -> parse(yaml));
    assertTrue(
        thrown.getMessage().startsWith(SOURCE), "every error names the file: " + thrown.getMessage());
    return thrown.getMessage();
  }

  @Test
  void anEmptyFileIsEveryDefault() {
    // The whole of backward compatibility: a repository that says nothing is an ordinary
    // environment application, exactly as every repository was before this file existed.
    assertEquals(DeploymentSpec.DEFAULTS, parse(""));
    assertEquals(DeploymentSpec.DEFAULTS, parse("# nothing to say yet\n"));
  }

  @Test
  void aRepositoryAsksForADatabaseOfItsOwnWithOneLine() {
    // The three shapes the grammar has, and all three are in the plan's own examples: the bare
    // default, an explicit database, and two resources on one line. The type is spelled out so a
    // second one can arrive without changing the shape.
    assertEquals(
        List.of(new ResourceSpec("db", null)), parse("resources: postgresql:db\n").resources());
    assertEquals(
        List.of(new ResourceSpec("db", "qits_artifacts")),
        parse("resources: postgresql:db:qits_artifacts\n").resources());
    assertEquals(
        List.of(new ResourceSpec("projects", null), new ResourceSpec("epics", "qits_epics")),
        parse("resources: postgresql:projects, postgresql:epics:qits_epics\n").resources());
  }

  @Test
  void anOmittedDatabaseIsNullBecauseTheParserDoesNotKnowTheApplication() {
    // Null is "the convention", not "no database": the default is qits_ plus the application name
    // without its qits- prefix, and this parser has never been told which application it is
    // reading for. DeployService.register resolves it.
    assertNull(parse("resources: postgresql:db\n").resources().get(0).database());
  }

  @Test
  void aFileThatNamesNoResourcesGetsAnEmptyListAndNotANull() {
    assertEquals(List.of(), parse("available_on_env: true\n").resources());
    assertEquals(List.of(), DeploymentSpec.DEFAULTS.resources());
  }

  @Test
  void routesNavigationAndPortAreAnOrderedServiceOwnedPublicSurface() {
    DeploymentSpec spec =
        parse(
            """
            routes: /refinement,/refinement/api
            upstream_port: 8181
            host: refinement
            navigation-entries: services.details.Refinement:12, platform.Refinement:1
            """);

    assertEquals(List.of("/refinement", "/refinement/api"), spec.routes());
    assertEquals(8181, spec.upstreamPort());
    assertEquals("refinement", spec.host());
    assertTrue(spec.browserHostDeclared());
    assertEquals(
        List.of(
            new NavigationEntry("services.details", "Refinement", 12),
            new NavigationEntry("platform", "Refinement", 1)),
        spec.navigationEntries());
  }

  @Test
  void anEntryIsReadFromTheRightSoALabelMayCarryAColon() {
    // The whole grammar in one line: the last colon takes the position off the end, the last dot of
    // what remains takes the label off, and everything before it is the slot. That order is what
    // lets a label contain a colon while the slot vocabulary stays closed.
    assertEquals(
        List.of(new NavigationEntry("services.details", "CI:v2", 1)),
        parse("routes: /ci\nnavigation-entries: services.details.CI:v2:1\n").navigationEntries());
  }

  @Test
  void aHostIsDerivedElsewhereButAskedForHere() {
    // The parser cannot derive the default — it never knows which application it reads for — so it
    // answers the question that decides whether there is anything to derive.
    assertNull(parse("routes: /ci\nnavigation-entries: system.CI:1\n").host());
    assertTrue(parse("routes: /ci\nnavigation-entries: system.CI:1\n").browserHostDeclared());
    assertTrue(parse("routes: /ci\nhost: ci\n").browserHostDeclared());
    // And the retired singular asks for none: a file nobody has rewritten keeps its path prefix.
    assertFalse(parse("routes: /ci\nnavigation: CI:1\n").browserHostDeclared());
  }

  @Test
  void theRetiredNavigationKeyMapsOntoOneSystemEntry() {
    // A spec is read at the BUILT sha, so every unrewritten file and every rollback pin still
    // presents this key. It maps onto the list that replaced it rather than being tolerated twice.
    assertEquals(
        List.of(new NavigationEntry("system", "Refinement", 12)),
        parse("routes: /refinement\nnavigation: Refinement:12\n").navigationEntries());
    // Two answers to one question is an error, and the message names the key to keep.
    assertTrue(
        messageOf("routes: /ci\nnavigation: CI:1\nnavigation-entries: system.CI:1\n")
            .contains("navigation-entries"));
  }

  @Test
  void noRouteDeclarationIsTheCompatibleEmptySnapshot() {
    assertEquals(List.of(), DeploymentSpec.DEFAULTS.routes());
    assertEquals(8080, DeploymentSpec.DEFAULTS.upstreamPort());
    assertNull(DeploymentSpec.DEFAULTS.host());
    assertFalse(DeploymentSpec.DEFAULTS.browserHostDeclared());
    assertEquals(List.of(), DeploymentSpec.DEFAULTS.navigationEntries());
  }

  @Test
  void routesAndNavigationAreStrictBecauseTheyBecomeTheEdgesPublicSurface() {
    assertTrue(messageOf("routes: refinement\n").contains("routes"));
    assertTrue(messageOf("routes: /refinement,/refinement\n").contains("twice"));
    assertTrue(messageOf("routes: /refinement\nupstream_port: 0\n").contains("upstream_port"));
    // Navigation has to lead to a published route, whichever key asks for it.
    assertTrue(messageOf("navigation: Refinement:3\n").contains("navigation"));
    assertTrue(messageOf("routes: /refinement\nnavigation: Refinement:0\n").contains("navigation"));
    assertTrue(
        messageOf("navigation-entries: system.Refinement:3\n").contains("navigation-entries"));
    assertTrue(messageOf("host: refinement\n").contains("host"));
  }

  @Test
  void aSlotOutsideTheVocabularyNamesTheWholeVocabulary() {
    // The strictness that matters here: a typo answered with a default is an application that
    // silently appears nowhere, and nothing in the file says why.
    String message = messageOf("routes: /ci\nnavigation-entries: services.CI:1\n");
    assertTrue(message.contains("services.details"), message);
    assertTrue(message.contains("project.detail"), message);
    assertTrue(messageOf("routes: /ci\nnavigation-entries: CI:1\n").contains("slots"));
  }

  @Test
  void anAbsentApplicationKeyIsTheRepositorysOwnName() {
    // The whole of the compatibility contract for this key: every file that exists today says
    // nothing, and null is what "the repository's own name" is spelled as here — the parser never
    // knows which repository it reads for, so it cannot fill the value in.
    assertNull(DeploymentSpec.DEFAULTS.application());
    assertNull(parse("").application());
    assertNull(parse("deployment_target: platform\nroutes: /ci\n").application());
  }

  @Test
  void aStatedApplicationIsTheNameThisRepositoryDeploysAs() {
    // The rename: the repository is qits-ci-service and the application stays qits-ci, so nothing
    // that is running moves. What the name then drives is DeployService's, not this parser's.
    assertEquals("qits-ci", parse("application: qits-ci\n").application());
    assertEquals(
        "qits-ci",
        parse("application: qits-ci\ndeployment_target: platform\nroutes: /ci\n").application(),
        "it sits beside every other key and changes none of them");
    // Naming the repository's own name is a no-op rather than an error — a file may say it out loud.
    assertEquals("qits-ci", parse("application: 'qits-ci'\n").application(), "quoted like any value");
  }

  @Test
  void anApplicationNameIsCheckedLikeEveryOtherStoredName() {
    // It becomes a service name, a network alias, an image path segment and half a postgres
    // identifier, so it takes PdIdentifiers' stored-name rule: lowercase, digits, inner dashes.
    // Uppercase is refused outright, which is what makes two spellings differing only in case
    // impossible rather than merely discouraged.
    assertTrue(messageOf("application: Qits-CI\n").contains("application"));
    assertTrue(messageOf("application: qits/ci\n").contains("application"));
    assertTrue(messageOf("application: qits ci\n").contains("application"));
    assertTrue(messageOf("application: qits.ci\n").contains("application"));
    assertTrue(messageOf("application: -qits-ci\n").contains("application"));
    assertTrue(messageOf("application: qits-ci-\n").contains("application"));
    assertTrue(messageOf("application:\n").contains("application"));
    assertTrue(messageOf("application: " + "c".repeat(64) + "\n").contains("application"));
    // And it is one key like any other: stating it twice is the duplicate every key is refused for.
    assertTrue(messageOf("application: qits-ci\napplication: qits-ci\n").contains("duplicate"));
  }

  @Test
  void oneApplicationHangsSeveralRowsUnderOneHeading() {
    // The workspaces shape, byte for byte: one application, one container, two rows under the
    // project node. The claim is the (slot, label) pair — keyed on the slot alone this file was
    // refused whole ("claims the slot `project.detail` twice"), so the deployment failed as
    // "deployment spec unreadable" while the build that produced it stayed green.
    assertEquals(
        List.of(
            new NavigationEntry("project.detail", "Workspaces", 1),
            new NavigationEntry("project.detail", "Editor", 2, "editor")),
        parse(
                "routes: /workspaces\n"
                    + "navigation-entries: project.detail.Workspaces:1,"
                    + " project.detail.Editor:2=editor\n")
            .navigationEntries());
    // And a label that is only a label away from another one is still two rows: what makes them
    // distinct is the pair, not how different the words are.
    assertEquals(
        2,
        parse("routes: /ci\nnavigation-entries: system.CI:1, system.Runs:2\n")
            .navigationEntries()
            .size());
  }

  @Test
  void oneApplicationClaimsEachSlotAndLabelOnce() {
    // One row asked for twice, which no consumer can draw two of. The message names the pair rather
    // than the slot, so a file with four project.detail entries says which one is the duplicate.
    String message = messageOf("routes: /ci\nnavigation-entries: system.CI:1, system.CI:2\n");
    assertTrue(message.contains("twice"), message);
    assertTrue(message.contains("system.CI"), message);
    // The subpath is not part of the claim either: two views of one application under one heading
    // are two ROWS and need two labels to be told apart in the tree.
    assertTrue(
        messageOf(
                "routes: /ci\nnavigation-entries: project.detail.Editor:1=editor,"
                    + " project.detail.Editor:2=terminal\n")
            .contains("twice"));
  }

  @Test
  void twoRowsOfOneApplicationMayNameOnePosition() {
    // A position is the repository's own number and a tie is broken by label — the consumer already
    // does that between applications (NavigationEntry's javadoc says so, and EdgeRoutes sorts slot,
    // position, label, application). Refusing the tie within one application would be a rule the
    // navigation document does not have, and the two rows would still both render.
    assertEquals(
        List.of(
            new NavigationEntry("project.detail", "Workspaces", 1),
            new NavigationEntry("project.detail", "Editor", 1, "editor")),
        parse(
                "routes: /workspaces\n"
                    + "navigation-entries: project.detail.Workspaces:1,"
                    + " project.detail.Editor:1=editor\n")
            .navigationEntries());
  }

  @Test
  void anEntryNeedsANonBlankLabelAndAPositivePosition() {
    assertTrue(messageOf("routes: /ci\nnavigation-entries:\n").contains("blank"));
    assertTrue(messageOf("routes: /ci\nnavigation-entries: system.CI:1,\n").contains("blank"));
    assertTrue(messageOf("routes: /ci\nnavigation-entries: system.:1\n").contains("label"));
    assertTrue(messageOf("routes: /ci\nnavigation-entries: system.CI:0\n").contains("position"));
    assertTrue(messageOf("routes: /ci\nnavigation-entries: system.CI\n").contains("position"));
    assertTrue(
        messageOf("routes: /ci\nnavigation-entries: system." + "C".repeat(65) + ":1\n")
            .contains("64"));
  }

  @Test
  void anEntryMayNameTheViewItOpensWithASubpath() {
    // The tail after the last colon splits at its first `=`: position on the left, subpath on the
    // right. The subpath is a client-side route segment the shell appends after the scope — it is
    // not an edge route, so no published-route rule touches it beyond the entry's own.
    assertEquals(
        List.of(new NavigationEntry("services.details", "Api Docs", 6, "api-docs")),
        parse("routes: /projects\nnavigation-entries: services.details.Api Docs:6=api-docs\n")
            .navigationEntries());
    // A label's right to carry a colon survives, because the tail was already taken off the end.
    assertEquals(
        List.of(new NavigationEntry("services.details", "CI:v2", 1, "runs/latest")),
        parse("routes: /ci\nnavigation-entries: services.details.CI:v2:1=runs/latest\n")
            .navigationEntries());
  }

  @Test
  void aSubpathOutsideItsCharsetIsAnError() {
    // The charset has no colon, dot or comma by construction — that is what keeps the entry list's
    // separators and the right-to-left parse safe around it — and no slash at either end, because
    // the shell composes the joint.
    assertTrue(messageOf("routes: /ci\nnavigation-entries: system.CI:1=\n").contains("subpath"));
    assertTrue(messageOf("routes: /ci\nnavigation-entries: system.CI:1=/api\n").contains("subpath"));
    assertTrue(
        messageOf("routes: /ci\nnavigation-entries: system.CI:1=api-docs/\n").contains("subpath"));
    assertTrue(
        messageOf("routes: /ci\nnavigation-entries: system.CI:1=Api Docs\n").contains("subpath"));
    assertTrue(
        messageOf("routes: /ci\nnavigation-entries: system.CI:1=" + "a".repeat(129) + "\n")
            .contains("128"));
  }

  @Test
  void theStoredSpellingRoundTripsExactly() {
    // adoptedSnapshot re-announces a self-update from the stored column alone, so the join and the
    // parse are one serialization format: what the file said is what the row says, byte for byte.
    for (String spelled :
        List.of(
            "services.details.CI:2,platform.Deployments:4",
            "services.details.Api Docs:6=api-docs",
            "project.detail.Workspaces:1,project.detail.Editor:2=editor",
            "services.details.CI:v2:1=runs/latest,system.CI:1")) {
      assertEquals(
          spelled,
          DeploymentSpecParser.joinEntries(DeploymentSpecParser.parseEntries(spelled)));
    }
  }

  @Test
  void apiDocsMustSitUnderAPublishedRoute() {
    // A document nobody can reach describes nothing: the edge only proxies what `routes` declares.
    assertEquals(
        "/ci/q/swagger-ui", parse("routes: /ci\napi-docs: /ci/q/swagger-ui\n").apiDocs());
    assertEquals("/ci", parse("routes: /ci\napi-docs: /ci\n").apiDocs(), "the route itself is in");
    assertTrue(messageOf("routes: /ci\napi-docs: /docs/q/swagger-ui\n").contains("api-docs"));
    assertTrue(
        messageOf("routes: /cd\napi-docs: /cdn\n").contains("api-docs"),
        "a sibling prefix is not under the route");
    assertTrue(messageOf("api-docs: /ci/q/swagger-ui\n").contains("api-docs"));
    assertNull(DeploymentSpec.DEFAULTS.apiDocs());
  }

  @Test
  void anApiDocsValueOutsideItsCharsetIsAnError() {
    assertTrue(messageOf("routes: /ci\napi-docs: ci/q/swagger-ui\n").contains("api-docs"));
    assertTrue(messageOf("routes: /ci\napi-docs: /\n").contains("api-docs"));
    assertTrue(messageOf("routes: /ci\napi-docs: /ci/Q/Swagger\n").contains("api-docs"));
    assertTrue(messageOf("routes: /ci\napi-docs:\n").contains("api-docs"));
    assertTrue(
        messageOf("routes: /ci\napi-docs: /ci/" + "a".repeat(255) + "\n").contains("api-docs"));
  }

  @Test
  void aHostIsOneDnsLabelBecauseTheEdgeBuildsTheAuthorityAroundIt() {
    assertEquals("registry", parse("routes: /artifacts\nhost: registry\n").host());
    assertTrue(messageOf("routes: /ci\nhost: CI\n").contains("host"));
    assertTrue(messageOf("routes: /ci\nhost: -ci\n").contains("host"));
    assertTrue(messageOf("routes: /ci\nhost: ci-\n").contains("host"));
    assertTrue(messageOf("routes: /ci\nhost: ci.dev\n").contains("host"));
    assertTrue(messageOf("routes: /ci\nhost: " + "c".repeat(64) + "\n").contains("host"));
  }

  @Test
  void aResourceTypeThisComponentCannotProvisionIsAnError() {
    // The strictness that matters most here: a typo answered with a default would be a deployment
    // whose application boots without the credential it declared it needs.
    String message = messageOf("resources: mysql:db\n");
    assertTrue(message.contains("postgresql"), message);
    assertTrue(messageOf("resources: db\n").contains("resources"), "a bare name is not an entry");
    assertTrue(
        messageOf("resources: postgresql:db:qits_a:extra\n").contains("resources"),
        "and neither is a fourth segment");
  }

  @Test
  void aResourceNameOrDatabaseOutsideItsCharsetIsAnError() {
    // Both values are repository-authored and both end up somewhere that cannot be parametrized —
    // the name in an env key on a docker run, the database in DDL against the platform's shared
    // postgres. So they are allowlists, and this is the parser end of the three checkpoints.
    assertTrue(messageOf("resources: postgresql:DB\n").contains("resources"));
    assertTrue(messageOf("resources: postgresql:db name\n").contains("resources"));
    assertTrue(messageOf("resources: postgresql:-db\n").contains("resources"));
    assertTrue(messageOf("resources: postgresql:db-\n").contains("resources"));
    // The qits_ prefix is what structurally excludes postgres, template0/1 and every pg_* name.
    assertTrue(messageOf("resources: postgresql:db:postgres\n").contains("qits_"));
    assertTrue(messageOf("resources: postgresql:db:template1\n").contains("qits_"));
    assertTrue(messageOf("resources: postgresql:db:pg_catalog\n").contains("qits_"));
    assertTrue(messageOf("resources: postgresql:db:qits_a; drop database x\n").contains("qits_"));
  }

  @Test
  void aBlankEntryIsAnError() {
    // `resources:` with nothing after it, and a trailing comma: a writer who meant to say
    // something. The deploy_branches rule, applied to the key that provisions.
    assertTrue(messageOf("resources:\n").contains("resources"));
    assertTrue(messageOf("resources: postgresql:db,\n").contains("resources"));
    assertTrue(messageOf("resources: postgresql:db, ,postgresql:other\n").contains("resources"));
  }

  @Test
  void namingOneThingTwiceIsAnError() {
    // A repeated name would make one env triple silently overwrite another; a repeated literal
    // database would point two of a repository's own resources at one store.
    assertTrue(
        messageOf("resources: postgresql:db, postgresql:db\n").contains("twice"),
        "the same resource name");
    assertTrue(
        messageOf("resources: postgresql:a:qits_x, postgresql:b:qits_x\n").contains("twice"),
        "the same database under two names");
  }

  @Test
  void anIndentedResourceListIsStillNesting() {
    // The grammar is flat because the file is. A YAML sequence would need a parser this file
    // deliberately does not have, which is why the entries share one comma-separated line.
    assertTrue(
        messageOf("resources: postgresql:db\n  - postgresql:other\n").contains("nesting"),
        "an indented list is refused before it can look like it works");
  }

  @Test
  void theScalarKeysAreReadAndCommentsAndQuotesAreNot() {
    DeploymentSpec spec =
        parse(
            """
            ---
            deployment_target: platform   # cross-environment

            deploy_branches: "environment/prod"
            health_path: /idp/q/health/ready
            """);
    assertEquals(PdDeploymentTarget.PLATFORM, spec.target());
    assertEquals(List.of("environment/prod"), spec.deployBranches());
    assertFalse(spec.availableOnEnv());
    assertEquals("/idp/q/health/ready", spec.healthPath());
    assertNull(spec.healthCmd());
  }

  @Test
  void aNonHttpImageDeclaresItsOwnProbeSpacesAndAll() {
    // The deployable-image case, and the reason the value gets no charset: postgres has neither
    // curl nor anything on 8080, so a path-shaped gate can never pass. The command is one argv
    // element that docker runs with /bin/sh -c, so its spaces, flags and || are the shell's.
    assertEquals(
        "pg_isready -U postgres || exit 1",
        parse("health_cmd: pg_isready -U postgres || exit 1\n").healthCmd());
    assertEquals(
        "test -f /var/lib/ready", parse("health_cmd: \"test -f /var/lib/ready\"\n").healthCmd());
  }

  @Test
  void aFileThatNamesNoHealthCmdGetsTheHttpProbe() {
    // Null is the statement "this image has an HTTP surface", which is every service the platform
    // had before deployable images existed.
    assertNull(parse("health_path: /idp/q/health/ready\n").healthCmd());
    assertNull(DeploymentSpec.DEFAULTS.healthCmd());
  }

  @Test
  void aHealthCmdAndAHealthPathTogetherAreAnError() {
    // Not two settings on one gate: the command replaces the whole HTTP mechanism, so a file with
    // both says two things about one thing and the writer has to pick.
    String message = messageOf("health_path: /q/health/ready\nhealth_cmd: pg_isready\n");
    assertTrue(message.contains("health_cmd"), message);
    assertTrue(message.contains("health_path"), message);
    // Either order, since neither is the one that "came second".
    assertTrue(
        messageOf("health_cmd: pg_isready\nhealth_path: /q/health/ready\n").contains("health_cmd"));
  }

  @Test
  void aBlankOrOversizedHealthCmdIsAnError() {
    // What is left to check once the charset is deliberately open: a probe that says nothing, and
    // one long enough to be a mistake.
    assertTrue(messageOf("health_cmd:\n").contains("health_cmd"));
    assertTrue(messageOf("health_cmd: \"   \"\n").contains("health_cmd"));
    assertTrue(messageOf("health_cmd: " + "x".repeat(513) + "\n").contains("health_cmd"));
    assertEquals("x".repeat(512), parse("health_cmd: " + "x".repeat(512) + "\n").healthCmd());
  }

  @Test
  void singletonIsAnAcceptedAliasForPlatformAndParsesToTheSameThing() {
    // The retired vocabulary. Repositories that carry the word were written against qits-cd and
    // must keep deploying across the cutover without a commit each, so it is a tolerance rather
    // than a second spelling: nothing downstream can tell the two apart.
    assertEquals(parse("deployment_target: platform\n"), parse("deployment_target: singleton\n"));
    assertEquals(PdDeploymentTarget.PLATFORM, parse("deployment_target: singleton\n").target());
  }

  @Test
  void theErrorMessageNamesTheCanonicalWordAndNotTheAlias() {
    // A repository being corrected is pointed at the word to use, not at the one it may keep using.
    String message = messageOf("deployment_target: Platform\n");
    assertTrue(message.contains("platform"), message);
    assertFalse(message.contains("singleton"), message);
    assertTrue(messageOf("deployment_target: everywhere\n").contains("environment"));
  }

  @Test
  void aFileThatNamesNoHealthPathLeavesItToTheConvention() {
    // Null is the statement "this repository said nothing", and registration turns that into the
    // derived path. The parser must not invent one here — it does not know the service's name.
    assertNull(parse("available_on_env: true\n").healthPath());
  }

  @Test
  void aHealthPathThatIsNotAnAbsolutePathIsAnError() {
    // The value ends up in a container's --health-cmd, so it is checked as strictly as the API's.
    assertTrue(messageOf("health_path: q/health/ready\n").contains("health_path"));
    assertTrue(messageOf("health_path: /q/health/ready?x=1\n").contains("health_path"));
    assertTrue(messageOf("health_path:\n").contains("health_path"));
  }

  @Test
  void aFileThatNamesNoDeployBranchesSaysSoWithAnEmptyList() {
    // The deployer decides nothing on this key — a build deploys wherever an environment listens to
    // its branch, on either plane — so "said nothing" has to stay distinguishable from "said none"
    // for the reader that does use it, the release flow.
    assertEquals(List.of(), parse("deployment_target: platform\n").deployBranches());
    assertEquals(List.of(), DeploymentSpec.DEFAULTS.deployBranches());
  }

  @Test
  void deployBranchesIsACommaSeparatedRefListAndEveryRefIsChecked() {
    // One line, because this file has no YAML sequences; a comma cannot occur in a ref name, which
    // is what makes the separator safe.
    assertEquals(
        List.of("environment/prod", "environment/dev"),
        parse("deploy_branches: environment/prod, environment/dev\n").deployBranches());
    assertTrue(messageOf("deploy_branches: ../../etc\n").contains("deploy_branches"));
    // A trailing comma, or the key with nothing after it, is a writer who meant to say something.
    assertTrue(messageOf("deploy_branches: environment/prod,\n").contains("deploy_branches"));
    assertTrue(messageOf("deploy_branches:\n").contains("deploy_branches"));
  }

  @Test
  void theRetiredBranchKeyIsNoLongerKnown() {
    // It named the platform plane's own deploy ref, and the plane has none: `environment/<name>` is
    // the whole set. A repository still carrying the key is corrected rather than half-obeyed.
    assertTrue(messageOf("deployment_target: platform\nbranch: release\n").contains("unknown key"));
  }

  @Test
  void aPublicNodeSaysSoAndNothingElseDoes() {
    assertTrue(parse("available_on_env: true\n").availableOnEnv());
    assertFalse(parse("available_on_env: false\n").availableOnEnv());
  }

  @Test
  void anApplicationThatCannotOverlapItselfSaysStopFirst() {
    // The default is the lossless one — the successor starts beside the predecessor, so an
    // orchestrator that fails it reverts to something that never stopped serving. What a repository
    // says here is that it cannot be two processes at once: one binder per published host port, one
    // writer per store, one holder of a config volume.
    assertEquals(
        DeploymentDriver.UpdateOrder.START_FIRST, parse("").updateOrder(), "the default");
    assertEquals(
        DeploymentDriver.UpdateOrder.START_FIRST,
        parse("update_order: start-first\n").updateOrder());
    assertEquals(
        DeploymentDriver.UpdateOrder.STOP_FIRST, parse("update_order: stop-first\n").updateOrder());
  }

  @Test
  void anUpdateOrderOutsideThePairIsAnError() {
    // Refused rather than defaulted, like every other value here: the difference between the two is
    // whether an application is ever two processes at once, and a silent default answers that
    // question for a repository that was trying to answer it itself.
    String message = messageOf("update_order: rolling\n");
    assertTrue(message.contains("start-first"), message);
    assertTrue(message.contains("stop-first"), message);
    assertTrue(message.contains("rolling"), "the message names what was written: " + message);
    // The enum's own spelling is the file's, so neither side has to translate.
    assertTrue(messageOf("update_order: STOP_FIRST\n").contains("update_order"));
  }

  @Test
  void aServiceWhosePortMustSurviveItsOwnReplacementSaysIngress() {
    // The default is what every publishing service does today — the task binds the port on the
    // node — so a file that says nothing is deployed byte-for-byte as before. `ingress` gives the
    // port to swarm's routing mesh, which keeps holding it while the successor starts.
    assertEquals(DeploymentDriver.PublishMode.HOST, parse("").publishMode(), "the default");
    assertEquals(
        DeploymentDriver.PublishMode.HOST, parse("publish_mode: host\n").publishMode());
    assertEquals(
        DeploymentDriver.PublishMode.INGRESS, parse("publish_mode: ingress\n").publishMode());
  }

  @Test
  void aPublishModeOutsideThePairIsAnError() {
    // Refused rather than defaulted: the difference is whether a replacement can start while the
    // predecessor still holds the port, which is the whole reason the key exists.
    String message = messageOf("publish_mode: mesh\n");
    assertTrue(message.contains("host"), message);
    assertTrue(message.contains("ingress"), message);
    assertTrue(message.contains("mesh"), "the message names what was written: " + message);
    // Docker's own spelling is the file's, so neither side has to translate.
    assertTrue(messageOf("publish_mode: INGRESS\n").contains("publish_mode"));
  }

  @Test
  void thePublishModeAndTheUpdateOrderAreIndependentStatements() {
    // Nothing here derives one from the other. An ingress-mode service keeps the default
    // start-first — that is the point of ingress — and one that declares stop-first gets it.
    DeploymentSpec ingress = parse("publish_mode: ingress\n");
    assertEquals(DeploymentDriver.UpdateOrder.START_FIRST, ingress.updateOrder());
    DeploymentSpec both = parse("publish_mode: ingress\nupdate_order: stop-first\n");
    assertEquals(DeploymentDriver.UpdateOrder.STOP_FIRST, both.updateOrder());
    assertEquals(DeploymentDriver.PublishMode.INGRESS, both.publishMode());
    // ...and a host-mode service is not pushed onto stop-first either: the repository says so.
    assertEquals(
        DeploymentDriver.UpdateOrder.START_FIRST, parse("publish_mode: host\n").updateOrder());
  }

  @Test
  void anUnknownKeyIsAnError() {
    // A lenient parser answers a typo with a default, which deploys the wrong topology in silence.
    String message = messageOf("deployment_targets: platform\n");
    assertTrue(message.contains("unknown key"), message);
    // ...and the message lists every key there is, so a typo is answered with the vocabulary.
    assertTrue(message.contains("resources"), message);
    assertTrue(message.contains("update_order"), message);
    assertTrue(message.contains("publish_mode"), message);
    assertTrue(message.contains("navigation-entries"), message);
    assertTrue(message.contains("application"), message);
  }

  @Test
  void aDuplicateKeyIsAnError() {
    assertTrue(
        messageOf("deployment_target: environment\ndeployment_target: platform\n")
            .contains("duplicate key"));
  }

  @Test
  void aValueOutsideTheEnumIsAnError() {
    assertTrue(messageOf("available_on_env: yes\n").contains("true"));
  }

  @Test
  void aPublicPlatformServiceIsAContradiction() {
    // It already runs on every environment's networks, and the bundle is environment-scoped. The
    // alias has to hit the same wall, or the retired spelling would be a way around the rule.
    assertTrue(
        messageOf("deployment_target: platform\navailable_on_env: true\n")
            .contains("available_on_env"));
    assertTrue(
        messageOf("deployment_target: singleton\navailable_on_env: true\n")
            .contains("available_on_env"));
  }

  @Test
  void nestingAndNonMappingLinesAreErrors() {
    assertTrue(
        messageOf("available_on_env: false\n  deployment_target: platform\n").contains("nesting"));
    assertTrue(messageOf("just a sentence\n").contains("key: value"));
  }

  @Test
  void deployBranchesIsReadForSomebodyElseAndAcceptedOnEitherPlane() {
    // The key belongs to the release flow, which reads the same file for its promotion targets.
    // This parser is strict, so a key another reader needs is a key this reader has to know —
    // accepted-and-unused rather than a second file, and on both planes, since a strict parser that
    // refused it on one would fail those deployments outright.
    assertEquals(
        List.of("environment/prod"),
        parse("deployment_target: environment\ndeploy_branches: environment/prod\n")
            .deployBranches());
    assertEquals(
        List.of("environment/prod"),
        parse("deployment_target: platform\ndeploy_branches: environment/prod\n").deployBranches());
  }
}
