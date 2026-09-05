package eu.wohlben.qits.platform.deployments.deployments.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;

/**
 * What the schema itself promises, against a real PostgreSQL with the real migration — plain JUnit,
 * no Quarkus, because the subject is the SQL.
 *
 * <p><b>Real postgres, not an in-memory stand-in.</b> The store moved off H2 with the resource
 * mechanism, and two of the claims below exist only on the database that ships: identity columns
 * and {@code unique nulls not distinct}. {@link EmbeddedPg} spawns the binaries as a child process,
 * so this still needs no docker.
 *
 * <p>Each test migrates its OWN database, so one test's rows are never another's starting state.
 * The claims:
 *
 * <ul>
 *   <li>the ordering column is the <b>database's</b> and is monotonic, because the deployment
 *       listings read "the current one per application" straight off it and {@code created_at} ties
 *       for the rows one build-succeeded event writes;
 *   <li>a deployment row needs <b>no topology row</b> — no FK, so history outlives the service and
 *       the tier it names, which is what keeps the rollback pins answering;
 *   <li>the startup sweep's predecessor lookup, as SQL: {@code (application_name, environment_id)}
 *       has to match across a <b>null</b> tier. This is the query that decides whether a
 *       self-updating deployer comes back ACTIVE or comes back having failed its own deployment,
 *       and nulls are distinct to {@code =} — so it is written with an explicit null test and
 *       pinned here;
 *   <li>and the same fact from the other side on {@code pd_resource}: its uniqueness is declared
 *       {@code nulls not distinct}, so a row with no {@code environment_name} — which is what the
 *       platform plane wrote before V8 — gets one row per resource rather than one per deployment.
 * </ul>
 *
 * <p><b>Two of them migrate halfway</b>, and they are the only tests here that do: V8 carries this
 * lineage's one backfill, and a backfill is invisible to a suite that always starts from an empty
 * schema. They stop at V7, write the rows the old code wrote, and migrate the rest of the way.
 */
public class PdSchemaTest {

  private static final String SHA_A = "a".repeat(40);
  private static final String SHA_B = "b".repeat(40);
  private static final String SHA_C = "c".repeat(40);

  @Test
  public void theOrderingColumnIsTheDatabasesAndItIsMonotonic() throws Exception {
    try (Connection connection = migrated();
        Statement sql = connection.createStatement()) {
      // A deliberately identical created_at: two deployments queued by ONE build-succeeded event
      // land in the same tick, and nothing but the tiebreak may decide their order.
      deployment(sql, "d-1", "qits-workspaces", "'env-1'", SHA_A, "DECOMMISSIONED");
      deployment(sql, "d-2", "qits-workspaces", "'env-1'", SHA_B, "ACTIVE");
      deployment(sql, "d-3", "qits-idp", "null", SHA_C, "ACTIVE");

      assertEquals(
          List.of("d-1", "d-2", "d-3"),
          rows(sql, "select id from pd_deployment order by seq"),
          "the ordering follows the order the history was recorded in");

      try (ResultSet answered = sql.executeQuery("select seq from pd_deployment where id = 'd-3'")) {
        assertTrue(answered.next());
        assertNotNull(answered.getObject(1), "the database assigns the ordering column itself");
        assertTrue(answered.getLong(1) >= 3, "and it counts up: " + answered.getLong(1));
      }
    }
  }

  @Test
  public void aDeploymentNeedsNoServiceRowAndNoEnvironmentRow() throws Exception {
    // No FK into the topology, deliberately: a service removed from the catalogue or a tier torn
    // down must not take its deployment history with it, and the rollback pins are read off that
    // history while qits-artifacts' image GC waits on the answer.
    try (Connection connection = migrated();
        Statement sql = connection.createStatement()) {
      deployment(sql, "d-orphan", "a-service-no-row-describes", "'a-tier-no-row-describes'", SHA_A, "ACTIVE");
      assertEquals(List.of("d-orphan"), rows(sql, "select id from pd_deployment"));
    }
  }

  @Test
  public void thePredecessorLookupMatchesAcrossANullTier() throws Exception {
    // The sweep's adoption, as SQL. A platform deployment's environment_id is null on both rows,
    // and `p.environment_id = o.environment_id` matches NOTHING when both are null — so the query
    // tests for null explicitly. Getting this wrong is silent: the successor of a self-update comes up,
    // finds no predecessor to decommission, and two rows claim to be ACTIVE.
    try (Connection connection = migrated();
        Statement sql = connection.createStatement()) {
      deployment(sql, "p-env", "qits-platform-deployments", "'env-1'", SHA_A, "ACTIVE");
      deployment(sql, "p-platform", "qits-platform-deployments", "null", SHA_A, "ACTIVE");
      deployment(sql, "o-platform", "qits-platform-deployments", "null", SHA_B, "STARTING");

      assertEquals(
          List.of("p-platform"),
          rows(
              sql,
              "select p.id from pd_deployment p join pd_deployment o on o.id = 'o-platform'"
                  + " where p.application_name = o.application_name"
                  + " and (p.environment_id = o.environment_id"
                  + "      or (p.environment_id is null and o.environment_id is null))"
                  + " and p.status = 'ACTIVE'"),
          "the platform plane's own predecessor, and not the tier's copy of the same service");
    }
  }

  @Test
  public void theTopologyKeepsOneRowPerServiceAndOneLinkPerTier() throws Exception {
    // The shape that retired the ancestors' composite (environment_id, name) uniqueness and the
    // partial-index problem it carried: a service name is unique outright, so nothing has to be
    // enforced inside a transaction because a null made two rows "distinct".
    try (Connection connection = migrated();
        Statement sql = connection.createStatement()) {
      // Named columns, not positional: a later migration that adds one must not become a change to
      // this file.
      sql.execute(
          "insert into pd_environment (id, name, network, platform, created_at) values"
              + " ('env-1', 'dev', 'qits-net', true, timestamp with time zone"
              + " '2026-08-06 10:00:00Z')");
      sql.execute(
          "insert into pd_service (id, name, deployment_target, branch, available_on_env,"
              + " health_path, created_at) values ('svc-1', 'qits-gateway', 'ENVIRONMENT', null,"
              + " true, '/q/health/ready', timestamp with time zone '2026-08-06 10:00:00Z')");
      sql.execute(
          "insert into pd_service_link (id, service_id, environment_id, created_at) values"
              + " ('link-1', 'svc-1', 'env-1', timestamp with time zone '2026-08-06 10:00:00Z')");

      assertEquals(
          List.of("qits-gateway|env-1"),
          rows(
              sql,
              "select s.name || '|' || l.environment_id from pd_service s"
                  + " join pd_service_link l on l.service_id = s.id"));

      // A platform service has NO link row at all, and that absence is what makes an environment
      // created tomorrow pick it up.
      sql.execute(
          "insert into pd_service (id, name, deployment_target, branch, available_on_env,"
              + " health_path, created_at) values ('svc-2', 'qits-idp', 'PLATFORM', 'main', false,"
              + " '/idp/q/health/ready', timestamp with time zone '2026-08-06 10:00:00Z')");
      assertEquals(
          List.of("qits-idp"),
          rows(
              sql,
              "select name from pd_service where id not in"
                  + " (select service_id from pd_service_link)"));
    }
  }

  @Test
  public void aPlatformPlaneResourceIsUniqueBecauseItsNullsAreNotDistinct() throws Exception {
    // The claim `unique nulls not distinct` exists for, and the reason this table could not have
    // been written before the move off H2. A platform-plane resource has a null environment_name,
    // and under the DEFAULT rule every null is distinct — so the registry would take one row per
    // deployment, the "row exists" arm of the idempotency matrix would never be reached, and every
    // self-deploy would rotate a password that was working.
    try (Connection connection = migrated();
        Statement sql = connection.createStatement()) {
      resource(sql, "r-1", "qits-artifacts", "null", "db", "qits_artifacts");
      assertThrows(
          SQLException.class,
          () -> resource(sql, "r-2", "qits-artifacts", "null", "db", "qits_artifacts"),
          "a second platform-plane row for the same (application, resource) is refused");
    }

    // The same triple in two different tiers is two different resources, which is the whole point
    // of the environment column being part of the key.
    try (Connection connection = migrated();
        Statement sql = connection.createStatement()) {
      resource(sql, "r-1", "qits-projects", "'dev'", "db", "qits_projects");
      resource(sql, "r-2", "qits-projects", "'prod'", "db", "qits_projects");
      assertEquals(
          List.of("dev", "prod"),
          rows(sql, "select environment_name from pd_resource order by environment_name"));
    }
  }

  @Test
  public void theRoutingSnapshotHoldsAHostAndThePlacementsAndBothStayOptional() throws Exception {
    // V4's columns, and the null that matters: a row written without either is an application
    // reached under its path prefix alone, which is most of them and was all of them. The legacy
    // pair stays, nullable and read-only, so a row queued before V4 can still say what it meant.
    try (Connection connection = migrated();
        Statement sql = connection.createStatement()) {
      deployment(sql, "d-plain", "qits-workspaces", "'env-1'", SHA_A, "ACTIVE");
      assertEquals(
          List.of("|"),
          rows(
              sql,
              "select coalesce(browser_host, '') || '|' || coalesce(navigation_entries, '')"
                  + " from pd_deployment where id = 'd-plain'"),
          "a deployment that asks for neither is written with neither");

      sql.execute(
          "update pd_deployment set browser_host = 'ci', navigation_entries ="
              + " 'services.details.CI:2,system.CI:1' where id = 'd-plain'");
      assertEquals(
          List.of("ci|services.details.CI:2,system.CI:1"),
          rows(
              sql,
              "select browser_host || '|' || navigation_entries from pd_deployment"
                  + " where id = 'd-plain'"),
          "the entries are stored in the spec's own spelling, one column, comma-separated");

      sql.execute(
          "update pd_deployment set navigation_label = 'CI', navigation_position = 2"
              + " where id = 'd-plain'");
      assertEquals(
          List.of("CI|2"),
          rows(
              sql,
              "select navigation_label || '|' || navigation_position from pd_deployment"
                  + " where id = 'd-plain'"),
          "V3's pair is still readable, which is what a pre-V4 row is announced from");
    }
  }

  @Test
  public void theV8BackfillMovesThePlatformPlaneOntoTheDesignatedTier() throws Exception {
    // THE ONE BACKFILL IN THIS LINEAGE, and the only test that migrates halfway. Everything else
    // here runs against an empty schema, so a backfill would be untested by them: this stops at V7,
    // writes the rows the old code wrote, and migrates the rest of the way.
    //
    // What the old code wrote is the statement being translated: a platform deployment had no
    // environment_id, and that null was the whole of how anything knew which plane a row was on.
    String url = EmbeddedPg.url("pd_deployments_" + UUID.randomUUID().toString().replace("-", ""));
    migrate(url, "7");
    try (Connection connection = DriverManager.getConnection(url, EmbeddedPg.USER, EmbeddedPg.PASSWORD);
        Statement sql = connection.createStatement()) {
      sql.execute(
          "insert into pd_environment (id, name, branch, network, platform, created_at) values"
              + " ('env-dev', 'dev', 'environment/dev', 'qits-net', true, timestamp with time zone"
              + " '2026-08-06 10:00:00Z'),"
              + " ('env-prod', 'prod', 'environment/prod', 'qits-env-prod', false, timestamp with"
              + " time zone '2026-08-06 10:00:00Z')");
      // A platform deployment, as V7 spelled one: no tier at all.
      sql.execute(
          "insert into pd_deployment (id, application_name, environment_id, version, status,"
              + " container_name, created_at) values ('d-plat', 'qits-ci', null, '2026.901.1',"
              + " 'ACTIVE', 'qits-ci', timestamp with time zone '2026-08-06 12:00:00Z')");
      sql.execute(
          "insert into pd_deployment (id, application_name, environment_id, version, status,"
              + " container_name, created_at) values ('d-env', 'qits-gateway', 'env-prod',"
              + " '2026.901.2', 'ACTIVE', 'prod-qits-gateway', timestamp with time zone"
              + " '2026-08-06 12:00:00Z')");
      sql.execute(
          "insert into pd_deployment_request (id, application_name, version, environment_id,"
              + " quality_gate, created_at) values ('rq-plat', 'qits-ci', '2026.901.1', null,"
              + " 'MET', timestamp with time zone '2026-08-06 12:00:00Z')");
      // The plane's own resource row, and the leftover from the era when the plane ran per tier.
      resource(sql, "r-plane", "qits-ci", "null", "db", "qits_ci");
      resource(sql, "r-stale", "qits-ci", "'dev'", "db", "qits_ci");
    }

    migrate(url, null);

    try (Connection connection = DriverManager.getConnection(url, EmbeddedPg.USER, EmbeddedPg.PASSWORD);
        Statement sql = connection.createStatement()) {
      assertEquals(
          List.of("d-env|ENVIRONMENT|env-prod", "d-plat|PLATFORM|env-dev"),
          rows(
              sql,
              "select id || '|' || deployment_target || '|' || environment_id from pd_deployment"
                  + " order by id"),
          "the null meant the platform plane, and it is now the plane plus the tier it deploys into");

      assertEquals(
          List.of("env-dev"),
          rows(sql, "select environment_id from pd_deployment_request where id = 'rq-plat'"),
          "the request names the place its deployment names");

      assertEquals(
          List.of("r-plane|dev"),
          rows(
              sql,
              "select id || '|' || environment_name from pd_resource"
                  + " where application_name = 'qits-ci'"),
          "the plane's credential row moves onto the tier's key, and the leftover it would have"
              + " collided with is dropped — a lookup that missed would rotate a live password");

      assertEquals(
          List.of(),
          rows(
              sql,
              "select column_name from information_schema.columns where table_name ="
                  + " 'pd_environment' and column_name = 'branch'"),
          "and the branch semantics have left the component");
    }
  }

  @Test
  public void theBackfillLeavesAnUndesignatedInstallAlone() throws Exception {
    // The other half of the same statement: an install mid-bootstrap has no platform environment,
    // so the subselect answers null and nothing moves. Every database the suite migrates is this
    // case, which is why it is worth pinning rather than assuming.
    String url = EmbeddedPg.url("pd_deployments_" + UUID.randomUUID().toString().replace("-", ""));
    migrate(url, "7");
    try (Connection connection = DriverManager.getConnection(url, EmbeddedPg.USER, EmbeddedPg.PASSWORD);
        Statement sql = connection.createStatement()) {
      sql.execute(
          "insert into pd_deployment (id, application_name, environment_id, version, status,"
              + " container_name, created_at) values ('d-plat', 'qits-ci', null, '2026.901.1',"
              + " 'ACTIVE', 'qits-ci', timestamp with time zone '2026-08-06 12:00:00Z')");
      resource(sql, "r-plane", "qits-ci", "null", "db", "qits_ci");
    }

    migrate(url, null);

    try (Connection connection = DriverManager.getConnection(url, EmbeddedPg.USER, EmbeddedPg.PASSWORD);
        Statement sql = connection.createStatement()) {
      assertEquals(
          List.of("PLATFORM|"),
          rows(
              sql,
              "select deployment_target || '|' || coalesce(environment_id, '') from pd_deployment"),
          "the plane is still stated; there is simply no tier to name");
      assertEquals(
          List.of(""),
          rows(sql, "select coalesce(environment_name, '') from pd_resource"),
          "and the credential row keeps the key its writer will keep using");
    }
  }

  /** A freshly created, freshly migrated database — one per test, so no test inherits rows. */
  @Test
  public void anAcceptedReleaseIsUniqueOnItsEventAndTheOwedQueryReadsOnlyTheUnsettledRows()
      throws Exception {
    // V10, the acceptance ledger. Two claims, and both are the storage half of a guarantee the
    // code makes above it.
    try (Connection connection = migrated();
        Statement sql = connection.createStatement()) {
      owedRelease(sql, "o-1", "ev-1", "qits-ci", "2026.903.93059", "a-dead-process");

      // ONE OBLIGATION PER EVENT. A re-drive re-takes the same row rather than opening a second
      // one, and the unique index is what makes that true even if two processes accept at once —
      // which is exactly the shape a duplicate delivery leaves when the bus's claim rolled back
      // after this row was already written.
      assertThrows(
          SQLException.class,
          () -> owedRelease(sql, "o-2", "ev-1", "qits-ci", "2026.903.93059", "another-process"),
          "a second obligation for one event is refused by the database");

      // SETTLED IS TERMINAL, and the sweep's query says so rather than trusting a status word.
      // Without this a discharged obligation would be re-driven on every tick forever.
      owedRelease(sql, "o-3", "ev-2", "qits-docs", "2026.903.193059", "a-dead-process");
      sql.execute(
          "update pd_owed_release set settled_at = now(), outcome = 'DISCHARGED' where id = 'o-3'");
      assertEquals(
          List.of("o-1"),
          rows(
              sql,
              "select id from pd_owed_release where settled_at is null"
                  + " and (accepted_by is null or accepted_by <> 'me') order by seq"),
          "only the unsettled obligation of a process that is not me is owed work");

      // AND A ROW THIS PROCESS HOLDS IS NOT OWED WORK, however long it sits there: it is on the
      // deploy queue, which is serialized platform-wide and legitimately an hour deep.
      sql.execute("update pd_owed_release set accepted_by = 'me' where id = 'o-1'");
      assertEquals(
          List.of(),
          rows(
              sql,
              "select id from pd_owed_release where settled_at is null"
                  + " and (accepted_by is null or accepted_by <> 'me') order by seq"));
    }
  }

  private static void owedRelease(
      Statement sql,
      String id,
      String eventId,
      String applicationName,
      String version,
      String acceptedBy)
      throws SQLException {
    sql.execute(
        "insert into pd_owed_release (id, event_id, application_name, version, accepted_by,"
            + " accepted_at, attempts) values ('"
            + id
            + "', '"
            + eventId
            + "', '"
            + applicationName
            + "', '"
            + version
            + "', '"
            + acceptedBy
            + "', now(), 1)");
  }

  private static Connection migrated() throws Exception {
    String url = EmbeddedPg.url("pd_deployments_" + UUID.randomUUID().toString().replace("-", ""));
    migrate(url, null);
    return DriverManager.getConnection(url, EmbeddedPg.USER, EmbeddedPg.PASSWORD);
  }

  /** @param target the version to stop at, or null for the whole lineage. */
  private static void migrate(String url, String target) {
    var configured =
        Flyway.configure()
            .dataSource(url, EmbeddedPg.USER, EmbeddedPg.PASSWORD)
            .locations("classpath:db/platformdeployments/migration");
    if (target != null) {
      configured = configured.target(target);
    }
    configured.load().migrate();
  }

  private static void deployment(
      Statement sql, String id, String applicationName, String environmentId, String sha, String status)
      throws Exception {
    // The plane is stated, because V8's column is not null and carries no default — pd_service's
    // rule, applied to the execution row. A row with a tier is an environment deployment here; the
    // platform-plane cases below say so explicitly.
    deployment(
        sql,
        id,
        applicationName,
        environmentId,
        sha,
        status,
        "null".equals(environmentId) ? "PLATFORM" : "ENVIRONMENT");
  }

  private static void deployment(
      Statement sql,
      String id,
      String applicationName,
      String environmentId,
      String sha,
      String status,
      String target)
      throws Exception {
    sql.execute(
        "insert into pd_deployment (id, application_name, environment_id, deployment_target,"
            + " commit_sha, status, container_name, created_at) values ('"
            + id
            + "', '"
            + applicationName
            + "', "
            + environmentId
            + ", '"
            + target
            + "', '"
            + sha
            + "', '"
            + status
            + "', 'container-"
            + id
            + "', timestamp with time zone '2026-08-06 12:00:00Z')");
  }

  private static void resource(
      Statement sql,
      String id,
      String applicationName,
      String environmentName,
      String resourceName,
      String databaseName)
      throws SQLException {
    sql.execute(
        "insert into pd_resource (id, application_name, environment_name, resource_name,"
            + " resource_type, database_name, role_name, password, created_at) values ('"
            + id
            + "', '"
            + applicationName
            + "', "
            + environmentName
            + ", '"
            + resourceName
            + "', 'postgresql', '"
            + databaseName
            + "', '"
            + databaseName
            + "', 'not-a-real-secret', timestamp with time zone '2026-08-09 12:00:00Z')");
  }

  private static List<String> rows(Statement sql, String query) throws Exception {
    List<String> values = new ArrayList<>();
    try (ResultSet answered = sql.executeQuery(query)) {
      while (answered.next()) {
        values.add(answered.getString(1));
      }
    }
    return values;
  }
}
