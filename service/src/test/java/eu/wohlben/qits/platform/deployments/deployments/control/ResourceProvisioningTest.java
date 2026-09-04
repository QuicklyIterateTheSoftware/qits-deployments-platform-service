package eu.wohlben.qits.platform.deployments.deployments.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.platform.deployments.deployments.control.SpecSource.DeploymentSpec.ResourceSpec;
import eu.wohlben.qits.platform.deployments.deployments.entity.PdResource;
import eu.wohlben.qits.platform.deployments.deployments.persistence.PdResourceRepository;
import eu.wohlben.qits.platform.deployments.environments.control.EnvironmentService;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The orchestration between the registry and the postgres seam: which password is sent, which one
 * comes back into the row, which address the resources are provisioned at, and what is refused.
 *
 * <p>The seam itself is {@link FakeResourceProvisioner} here — what a real postgres does with these
 * requests is proven against a real one in {@code PgResourceProvisionerTest}. What is under test is
 * the pair the drift cases hang on: <b>what the registry knew</b> going in, and <b>what it records</b>
 * coming out.
 */
@QuarkusTest
public class ResourceProvisioningTest {

  @Inject ResourceProvisioning provisioning;
  @Inject FakeResourceProvisioner provisioner;
  @Inject PdResourceRepository resources;
  @Inject EnvironmentService environments;

  @BeforeEach
  void reset() {
    provisioner.reset();
  }

  private Optional<PdResource> row(String application, String environment, String name) {
    return QuarkusTransaction.requiringNew()
        .call(() -> resources.findOne(application, environment, name));
  }

  private void existingRow(
      String application, String environment, String name, String database, String password) {
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              PdResource row = new PdResource();
              row.id = UUID.randomUUID().toString();
              row.applicationName = application;
              row.environmentName = environment;
              row.resourceName = name;
              row.resourceType = "postgresql";
              row.databaseName = database;
              row.roleName = database;
              row.password = password;
              row.createdAt = Instant.now();
              resources.persist(row);
            });
  }

  private static List<ResourceProvisioning.Resolved> one(String name, String database) {
    return List.of(new ResourceProvisioning.Resolved(name, database));
  }

  @Test
  public void aResourceNothingHasRecordedIsCreatedWithAFreshPasswordAndWrittenDown() {
    // The matrix's second row: no registry row, nothing on the server. The provisioner is told
    // there is no stored password, answers with the fresh one, and the row that lands holds it.
    List<DeploymentDriver.ResourceBinding> bindings =
        provisioning.ensureAll("prov-new", "prov-a", one("db", "qits_prov_new"));

    ResourceProvisioner.Request request = provisioner.requests().get(0);
    assertNull(request.storedPassword(), "the registry knew nothing, and said so");
    assertEquals("qits_prov_new", request.databaseName());
    assertEquals("qits_prov_new", request.roleName(), "the role IS the database");
    assertEquals("postgres", request.adminUsername());
    assertEquals(32, request.freshPassword().length());
    assertTrue(request.freshPassword().matches("[0-9a-f]{32}"), request.freshPassword());

    PdResource row = row("prov-new", "prov-a", "db").orElseThrow();
    assertEquals(request.freshPassword(), row.password);
    assertEquals("qits_prov_new", row.databaseName);
    assertEquals("postgresql", row.resourceType);
    assertNotEquals(null, row.lastProvisionedAt, "a provisioning stamps when it confirmed");

    assertEquals(1, bindings.size());
    assertEquals("db", bindings.get(0).name());
    assertEquals(
        "jdbc:postgresql://prov-a-qits-oci-postgresql:5432/qits_prov_new", bindings.get(0).url());
    assertEquals("qits_prov_new", bindings.get(0).username());
    assertEquals(request.freshPassword(), bindings.get(0).password());
  }

  @Test
  public void aRedeploymentSendsTheRecordedPasswordAndInjectsItBack() {
    // The matrix's first row, and the one that must never rotate anything: the application is
    // running on that credential right now.
    existingRow("prov-known", "prov-b", "db", "qits_prov_known", "an-already-working-password");

    List<DeploymentDriver.ResourceBinding> bindings =
        provisioning.ensureAll("prov-known", "prov-b", one("db", "qits_prov_known"));

    assertEquals("an-already-working-password", provisioner.requests().get(0).storedPassword());
    assertEquals("an-already-working-password", bindings.get(0).password());
    assertEquals("an-already-working-password", row("prov-known", "prov-b", "db").orElseThrow().password);
  }

  @Test
  public void aSelfHealKeepsTheRecordedPasswordAndAReconcileTakesTheFreshOne() {
    // The two drift arms, told apart by what the SEAM answers rather than by what the caller
    // guessed: this component records whatever came back, which is what keeps the row and the
    // server agreeing after either kind of reset.
    existingRow("prov-heal", "prov-c", "db", "qits_prov_heal", "the-recorded-one");
    provisioner.scriptResult(new ResourceProvisioner.Result(true, "the-recorded-one", null));
    provisioning.ensureAll("prov-heal", "prov-c", one("db", "qits_prov_heal"));
    assertEquals("the-recorded-one", row("prov-heal", "prov-c", "db").orElseThrow().password);

    // ...and the reconcile: the server rotated the role because nothing knew its password.
    provisioner.reset();
    provisioner.scriptResult(new ResourceProvisioner.Result(true, "a-rotated-one", null));
    List<DeploymentDriver.ResourceBinding> bindings =
        provisioning.ensureAll("prov-reconcile", "prov-c", one("db", "qits_prov_reconcile"));
    assertEquals("a-rotated-one", row("prov-reconcile", "prov-c", "db").orElseThrow().password);
    assertEquals("a-rotated-one", bindings.get(0).password(), "and the container is told the new one");
  }

  @Test
  public void aRefusedProvisioningIsAFailureWithPostgresOwnWordsAndNoPasswordInIt() {
    provisioner.scriptResult(
        new ResourceProvisioner.Result(false, null, "postgres refused: permission denied"));

    ResourceException refused =
        assertThrows(
            ResourceException.class,
            () -> provisioning.ensureAll("prov-refused", "prov-d", one("db", "qits_prov_refused")));

    assertTrue(refused.getMessage().contains("qits_prov_refused"), refused.getMessage());
    assertTrue(refused.getMessage().contains("permission denied"), refused.getMessage());
    assertTrue(row("prov-refused", "prov-d", "db").isEmpty(), "and nothing was written down");
  }

  @Test
  public void anotherApplicationsDatabaseIsRefusedRatherThanTakenOver() {
    // Two repositories naming one database is the one collision the qits_ allowlist cannot prevent,
    // so the registry answers it — and it answers with a FAILED deployment rather than a silent
    // no-op that would hand one application's store to another.
    existingRow("prov-owner", "prov-e", "db", "qits_prov_shared", "the-owners-password");

    ResourceException refused =
        assertThrows(
            ResourceException.class,
            () -> provisioning.ensureAll("prov-thief", "prov-e", one("db", "qits_prov_shared")));

    assertTrue(refused.getMessage().contains("prov-owner"), refused.getMessage());
    assertTrue(refused.getMessage().contains("qits_prov_shared"), refused.getMessage());
    assertEquals(List.of(), provisioner.requests(), "the seam was never even called");
  }

  @Test
  public void aPlatformPlaneResourceIsKeyedByTheTierItIsDeployedInto() {
    // A platform service is deployed INTO the designated environment since V8, so it arrives here
    // with that tier's name like every other application — and this method used to be the one that
    // reached for the designation on its behalf, off a null.
    //
    // BOTH HALVES MATTER AND ONLY ONE OF THEM IS VISIBLE. The address is the same string it always
    // was, because the null arm resolved to exactly this tier's postgres. What changed is the
    // registry KEY: the row is written under the tier's name, which is what the next deployment
    // will look it up by. Written under null while the deployment reads the tier, the lookup would
    // miss on every deploy, the provisioner would take its reconcile arm, and a working password
    // would be rotated under a running container's open pools.
    QuarkusTransaction.requiringNew()
        .run(() -> environments.create("prov-plane-tier", "qits-net", true));

    List<DeploymentDriver.ResourceBinding> bindings =
        provisioning.ensureAll("prov-plane", "prov-plane-tier", one("db", "qits_prov_plane"));

    assertEquals(
        "jdbc:postgresql://prov-plane-tier-qits-oci-postgresql:5432/qits_prov_plane",
        bindings.get(0).url());
    assertTrue(
        row("prov-plane", "prov-plane-tier", "db").isPresent(),
        "the row is keyed by the tier, which is the key the next deployment looks it up by");
    assertTrue(
        row("prov-plane", null, "db").isEmpty(),
        "and nothing is left under the key the plane used to write");
  }

  @Test
  public void aDeploymentWithNoTierAtAllIsRefusedRatherThanGuessedAt() {
    // The guard that survived the branch above. Both register arms come from entryTiers(), so a
    // queued deployment always carries a tier; a call that does not is a mid-bootstrap install with
    // no environment designated, and there is no postgres to name.
    ResourceException refused =
        assertThrows(
            ResourceException.class,
            () -> provisioning.ensureAll("prov-tierless", null, one("db", "qits_prov_tierless")));
    assertTrue(refused.getMessage().contains("platform environment"), refused.getMessage());
    assertEquals(List.of(), provisioner.requests(), "the seam was never even called");
  }

  @Test
  public void anApplicationThatDeclaresNothingNeverTouchesTheSeam() {
    assertEquals(List.of(), provisioning.ensureAll("prov-none", "prov-f", List.of()));
    assertEquals(List.of(), provisioning.ensureAll("prov-none", "prov-f", null));
    assertEquals(List.of(), provisioner.requests());
  }

  @Test
  public void theDefaultDatabaseIsTheApplicationNameWithoutTheQitsPrefix() {
    assertEquals(
        List.of(new ResourceProvisioning.Resolved("db", "qits_artifacts")),
        ResourceProvisioning.resolve("qits-artifacts", List.of(new ResourceSpec("db", null))));
    // Dashes become underscores, because a dash is not a postgres identifier character here.
    assertEquals(
        "qits_platform_deployments", ResourceProvisioning.conventionDatabase("qits-platform-deployments"));
    // A name without the prefix keeps the whole of itself.
    assertEquals("qits_gateway", ResourceProvisioning.conventionDatabase("gateway"));
    // An explicit database wins over the convention, which is how a repository names a database it
    // shares nothing with — qits-artifacts asking for qits_artifacts rather than the default.
    assertEquals(
        List.of(new ResourceProvisioning.Resolved("db", "qits_chosen")),
        ResourceProvisioning.resolve(
            "qits-something", List.of(new ResourceSpec("db", "qits_chosen"))));
  }

  @Test
  public void twoResourcesWhoseDefaultsCollideAreRefusedAfterResolution() {
    // The duplicate the parser could not see: two entries with no database named both resolve to
    // the convention, which is one store for two of a repository's own resources.
    ResourceException refused =
        assertThrows(
            ResourceException.class,
            () ->
                ResourceProvisioning.resolve(
                    "qits-twice",
                    List.of(new ResourceSpec("primary", null), new ResourceSpec("replica", null))));
    assertTrue(refused.getMessage().contains("qits_twice"), refused.getMessage());
    assertTrue(refused.getMessage().contains("name one of them explicitly"), refused.getMessage());
  }
}
