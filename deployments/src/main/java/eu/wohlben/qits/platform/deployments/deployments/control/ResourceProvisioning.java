package eu.wohlben.qits.platform.deployments.deployments.control;

import eu.wohlben.qits.platform.deployments.deployments.control.SpecSource.DeploymentSpec.ResourceSpec;
import eu.wohlben.qits.platform.deployments.deployments.entity.PdResource;
import eu.wohlben.qits.platform.deployments.deployments.persistence.PdResourceRepository;
import eu.wohlben.qits.platform.deployments.environments.control.PdIdentifiers;
import eu.wohlben.qits.platform.deployments.environments.control.PdNetworks;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * Everything between "the repository asked for a resource" and "the container is started with the
 * credential for it": read the registry, resolve which server to talk to, drive the right seam,
 * record what came back, and answer with the bindings the argv needs.
 *
 * <p><b>It runs on the deploy worker, before the pull.</b> The worker has no request context, so
 * every read and every write brackets itself in {@link QuarkusTransaction#requiringNew()} — and,
 * just as deliberately, <b>no transaction spans the call to a seam</b>. Both seams open a socket to
 * another server; holding a database transaction across either would put this component's own
 * connection pool behind somebody else's server for as long as it takes to answer.
 *
 * <p><b>Nothing here is ever dropped.</b> The failure modes are all shaped as convergence: a role
 * that is missing is created, a database that is missing is created, an ownership that drifted is
 * put back, an idp client nobody remembers is recreated or rotated. A resource the deployment no
 * longer declares is left exactly where it is.
 *
 * <p><b>Two resource types now, dispatched by {@link ResourceSpec.Type}</b>: {@code postgresql} over
 * {@link ResourceProvisioner}, one idempotent {@code ensure} call; {@code idp-client} over {@link
 * IdpClientProvisioner}, a read and up to two writes, because qits-idp's own API has no single call
 * that means "converge, whatever the state" — see that interface's javadoc. The admin password is
 * read, and demanded, only when a postgres resource is actually declared: an idp-only declaration
 * needs no postgres credential at all.
 */
@ApplicationScoped
public class ResourceProvisioning {

  private static final Logger LOG = Logger.getLogger(ResourceProvisioning.class);

  /** Every platform repository carries it, and no database identifier may. */
  private static final String NAME_PREFIX = "qits-";

  /** The postgres resource type, and the application name the platform's postgres deploys under. */
  static final String POSTGRES_APPLICATION = "qits-oci-postgresql";

  /**
   * Postgres' own port, inside the network. It is a constant rather than a config key because the
   * address it belongs to is derived too: the only reachable postgres is the one this component
   * deploys, under the alias its own naming rule produced.
   */
  static final int POSTGRES_PORT = 5432;

  static final String RESOURCE_TYPE = "postgresql";

  /**
   * The idp client's fixed resource name — reserved, because there is one idp per platform and
   * therefore nothing for a repository to name. {@code DeploymentSpecParser} refuses a repository
   * that tries to use it for a postgres resource instead.
   */
  static final String IDP_RESOURCE_NAME = "idp";

  static final String IDP_RESOURCE_TYPE = "idp-client";

  /** The application qits-platform-idp deploys under, and the port every service reaches it on. */
  static final String IDP_APPLICATION = "qits-platform-idp";

  static final int IDP_PORT = 8080;

  /** 128 bits, hex — argv-safe, URL-safe, and it needs no quoting in a SQL string literal. */
  private static final int PASSWORD_BYTES = 16;

  @Inject PdResourceRepository resources;
  @Inject ResourceProvisioner provisioner;
  @Inject IdpClientProvisioner idpClients;

  @ConfigProperty(name = "qits.platform.deployments.postgres.admin-username")
  String adminUsername;

  /**
   * Deliberately without a default. There is no password this repository could ship that would be
   * right, and a wrong one fails at the first CREATE ROLE with an authentication error nobody reads
   * as "nothing configured this". Absent, a deployment that declares a postgres resource fails
   * naming the key — which is the only actionable thing to say. Read only when a postgres resource
   * is actually declared: an idp-only declaration never touches it.
   */
  @ConfigProperty(name = "qits.platform.deployments.postgres.admin-password")
  Optional<String> adminPassword;

  /**
   * One resource with its database resolved — what a {@code Target} carries. The spec's null
   * database has been replaced by the convention here for a postgres resource, so nothing downstream
   * has to know there was ever a default; an idp-client resource's database stays null, because it
   * has none.
   *
   * <p>The two-argument constructor keeps defaulting to {@link ResourceSpec.Type#POSTGRESQL}, so
   * every existing caller of the postgres shape is unaffected.
   */
  public record Resolved(String name, String database, ResourceSpec.Type type) {

    public Resolved(String name, String database) {
      this(name, database, ResourceSpec.Type.POSTGRESQL);
    }
  }

  /**
   * Fill in the databases the file left out, and refuse the collision only a resolved list can see.
   *
   * <p>The convention is {@code qits_} plus the application name without its {@code qits-} prefix,
   * dashes to underscores — so qits-artifacts gets {@code qits_artifacts} and this component gets
   * {@code qits_deployments}. It is resolved here, at registration, because this is the first place
   * that knows the application's name; the parser never does. An {@code idp-client} entry has no
   * database to resolve and passes through unchanged.
   *
   * <p>The parser already refused two entries naming one <b>literal</b> database. What it could not
   * see is two entries whose defaults collide, which after resolution is the same mistake, so it is
   * caught in the same shape rather than left to become two applications' worth of writes into one
   * store.
   */
  public static List<Resolved> resolve(String applicationName, List<ResourceSpec> declared) {
    if (declared == null || declared.isEmpty()) {
      return List.of();
    }
    List<Resolved> resolved = new ArrayList<>();
    Set<String> databases = new HashSet<>();
    for (ResourceSpec spec : declared) {
      if (spec.type() == ResourceSpec.Type.IDP_CLIENT) {
        resolved.add(new Resolved(spec.name(), null, spec.type()));
        continue;
      }
      String database =
          spec.database() != null ? spec.database() : conventionDatabase(applicationName);
      if (!databases.add(database)) {
        throw new ResourceException(
            "two resources of "
                + applicationName
                + " resolve to the database `"
                + database
                + "` — name one of them explicitly");
      }
      resolved.add(new Resolved(spec.name(), database, spec.type()));
    }
    return List.copyOf(resolved);
  }

  /** {@code qits_} + the application name without its {@code qits-} prefix, dashes underscored. */
  static String conventionDatabase(String applicationName) {
    String segment =
        applicationName.startsWith(NAME_PREFIX)
            ? applicationName.substring(NAME_PREFIX.length())
            : applicationName;
    return PdIdentifiers.requireDatabaseName(
        "qits_" + segment.replace('-', '_').toLowerCase(Locale.ROOT));
  }

  /**
   * Make every declared resource exist and answer with what to inject for it.
   *
   * <p><b>The tier is part of the key and there is always one.</b> It used to be null for a
   * platform-plane deployment, and that null was doing two jobs: it named the plane, and it was the
   * {@code pd_resource} lookup key those rows were written under. Both jobs are an ordinary tier
   * name now — the postgres a deployment talks to is its tier's, which is the same instance the null
   * arm resolved to, and its registry rows are keyed by that tier's name. {@code
   * BootResourceRegistration} resolves the same name for its own rows, from the same designation,
   * which is what keeps this component's first self-deploy on the no-op arm rather than rotating a
   * password its pools are holding.
   *
   * <p><b>There was a three-argument form and a {@code target} beside this one</b>, and both went
   * with the plane: the short form delegated with {@code ENVIRONMENT}, and the plane was what an
   * idp-client resource's client id used to be derived with (bare on the plane, tier-qualified
   * otherwise). {@link PdNetworks#alias} answers one way now, so there is one derivation and one
   * method.
   *
   * @param environmentName the tier this deployment goes into
   * @throws ResourceException with an operator-facing sentence, and no credential in it
   */
  public List<DeploymentDriver.ResourceBinding> ensureAll(
      String applicationName, String environmentName, List<Resolved> declared) {
    if (declared == null || declared.isEmpty()) {
      return List.of();
    }
    if (environmentName == null) {
      throw new ResourceException(
          "this deployment declares resources and names no environment, so there is nowhere to"
              + " provision them — designate a platform environment");
    }

    boolean needsPostgres =
        declared.stream().anyMatch(r -> r.type() == ResourceSpec.Type.POSTGRESQL);
    String host = needsPostgres ? PdNetworks.alias(environmentName, POSTGRES_APPLICATION) : null;
    String admin = needsPostgres ? requireAdminPassword() : null;

    List<DeploymentDriver.ResourceBinding> bindings = new ArrayList<>();
    for (Resolved resource : declared) {
      bindings.add(
          switch (resource.type()) {
            case POSTGRESQL ->
                ensurePostgres(applicationName, environmentName, host, admin, resource);
            case IDP_CLIENT -> ensureIdpClient(applicationName, environmentName, resource);
          });
    }
    return List.copyOf(bindings);
  }

  private String requireAdminPassword() {
    return adminPassword
        .map(String::strip)
        .filter(password -> !password.isEmpty())
        .orElseThrow(
            () ->
                new ResourceException(
                    "this deployment declares resources and nothing configured"
                        + " qits.platform.deployments.postgres.admin-password"));
  }

  private DeploymentDriver.ResourceBinding ensurePostgres(
      String applicationName,
      String environmentName,
      String host,
      String admin,
      Resolved resource) {
    String database = PdIdentifiers.requireDatabaseName(resource.database());
    String name = PdIdentifiers.requireResourceName(resource.name());

    // The registry read, and the cross-application check, in one bracket: the worker thread has no
    // session of its own, and the answer is a plain String that outlives the transaction.
    String stored =
        QuarkusTransaction.requiringNew()
            .call(
                () -> {
                  for (PdResource claim : resources.listByDatabase(database)) {
                    if (!claim.applicationName.equals(applicationName)) {
                      throw new ResourceException(
                          "the database `"
                              + database
                              + "` is already provisioned for "
                              + claim.applicationName
                              + " — two repositories cannot share one database, so name a"
                              + " different one in `resources:`");
                    }
                  }
                  // Keyed by the tier this deployment goes into. The repository still tests null
                  // rather than comparing it, because rows written before the plane had a tier keep
                  // theirs — and `= null` matches nothing, which would rotate a working password on
                  // every deploy.
                  return resources
                      .findOne(applicationName, environmentName, name)
                      .map(row -> row.password)
                      .orElse(null);
                });

    // OUTSIDE any transaction, deliberately — see the class javadoc.
    String fresh = freshPassword();
    ResourceProvisioner.Result result =
        provisioner.ensure(
            new ResourceProvisioner.Request(
                host,
                POSTGRES_PORT,
                adminUsername,
                admin,
                database,
                // The role IS the database: one login per database, and nothing else may use it.
                database,
                stored,
                fresh));
    if (!result.ok()) {
      throw new ResourceException(
          "could not provision the database `" + database + "` on " + host + ": " + result.detail());
    }

    String inEffect = result.passwordInEffect();
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              Optional<PdResource> existing =
                  resources.findOne(applicationName, environmentName, name);
              PdResource row = existing.orElseGet(PdResource::new);
              if (existing.isEmpty()) {
                row.id = UUID.randomUUID().toString();
                row.applicationName = applicationName;
                row.environmentName = environmentName;
                row.resourceName = name;
                row.createdAt = Instant.now();
              }
              row.resourceType = RESOURCE_TYPE;
              row.databaseName = database;
              row.roleName = database;
              row.clientId = null;
              row.password = inEffect;
              row.lastProvisionedAt = Instant.now();
              // Persist LAST, with every not-null column set: Hibernate queues the insert with the
              // state the entity had at persist() and applies later writes as a following update.
              if (existing.isEmpty()) {
                resources.persist(row);
              }
            });

    LOG.infof(
        "Resource %s of %s (%s) is database %s on %s",
        name, applicationName, environmentName, database, host);
    return DeploymentDriver.ResourceBinding.postgres(
        name, "jdbc:postgresql://" + host + ":" + POSTGRES_PORT + "/" + database, database, inEffect);
  }

  /**
   * The four-arm idp-client matrix — the registry row crossed with what qits-idp answers for the
   * client id:
   *
   * <table>
   *   <caption>the matrix</caption>
   *   <tr><th>row</th><th>idp</th><th>action</th></tr>
   *   <tr><td>present</td><td>present</td><td>nothing; inject the stored secret</td></tr>
   *   <tr><td>present</td><td>absent</td><td>create → store → inject</td></tr>
   *   <tr><td>absent</td><td>present</td><td>rotate → store → inject</td></tr>
   *   <tr><td>absent</td><td>absent</td><td>create → store → inject (409 falls back to rotate
   *       once)</td></tr>
   * </table>
   *
   * <p>The presence check is one {@code GET} and is always made — it is what tells "nothing to do"
   * apart from "the row is stale", which a caller cannot see from its own registry alone.
   */
  private DeploymentDriver.ResourceBinding ensureIdpClient(
      String applicationName, String environmentName, Resolved resource) {
    String clientId =
        PdIdentifiers.requireName(
            PdNetworks.alias(environmentName, applicationName), "idp client id");

    // The registry read, and the cross-application check, in one bracket — the postgres arm's own
    // shape. Structurally this should never fire (the client id is derived one-to-one from
    // (application, environment)), but a derivation bug is exactly the case worth refusing
    // loudly rather than handing one application's credential to another's container.
    String stored =
        QuarkusTransaction.requiringNew()
            .call(
                () -> {
                  for (PdResource claim : resources.listByClientId(clientId)) {
                    if (!claim.applicationName.equals(applicationName)) {
                      throw new ResourceException(
                          "the idp client "
                              + clientId
                              + " is already provisioned for "
                              + claim.applicationName
                              + " — two applications cannot share one idp client");
                    }
                  }
                  return resources
                      .findOne(applicationName, environmentName, IDP_RESOURCE_NAME)
                      .map(row -> row.password)
                      .orElse(null);
                });

    // OUTSIDE any transaction, deliberately — see the class javadoc.
    boolean idpHasIt = idpClients.databaseClientPresent(clientId);

    String secret = stored;
    if (stored == null || !idpHasIt) {
      IdpClientProvisioner.Result result;
      if (stored != null) {
        // present/absent: idp lost the database row a reset or a restore would explain; a fresh
        // client replaces the stale secret, since qits-idp never accepts a caller-supplied one.
        result = idpClients.create(clientId);
      } else if (idpHasIt) {
        // absent/present: this registry lost its row, but idp already knows the client — rotate.
        result = rotateGuarded(applicationName, clientId);
      } else {
        // absent/absent: create, falling back to one rotate on a 409 — a drift the presence check
        // above did not catch, such as another process creating it between the two calls.
        IdpClientProvisioner.Result created = idpClients.create(clientId);
        result = created.conflict() ? rotateGuarded(applicationName, clientId) : created;
      }
      if (!result.ok()) {
        throw new ResourceException(
            "could not provision the idp client " + clientId + ": " + result.detail());
      }
      secret = result.secret();
      String freshSecret = secret;
      QuarkusTransaction.requiringNew()
          .run(() -> storeIdpRow(applicationName, environmentName, clientId, freshSecret));
    }

    LOG.infof(
        "Resource %s of %s (%s) is the idp client %s",
        IDP_RESOURCE_NAME, applicationName, environmentName, clientId);
    return DeploymentDriver.ResourceBinding.idp(
        IDP_RESOURCE_NAME, idpUrl(environmentName), clientId, secret);
  }

  /**
   * The rotate arm, refused for this component's own client (D9): rotating the deployer's own
   * secret mid-deployment would wedge the platform — no extras read, no idp call and no image pull
   * work without it, and there is no third party left to redeploy it.
   */
  private IdpClientProvisioner.Result rotateGuarded(String applicationName, String clientId) {
    if (BootResourceRegistration.APPLICATION.equals(applicationName)) {
      throw new ResourceException(
          "this is qits-deployments' own idp client, and it never rotates its own secret — a"
              + " lost row is recovered by BootResourceRegistration from its own environment, not"
              + " by asking qits-idp for a new one");
    }
    return idpClients.rotate(clientId);
  }

  private void storeIdpRow(
      String applicationName, String environmentName, String clientId, String secret) {
    Optional<PdResource> existing =
        resources.findOne(applicationName, environmentName, IDP_RESOURCE_NAME);
    PdResource row = existing.orElseGet(PdResource::new);
    if (existing.isEmpty()) {
      row.id = UUID.randomUUID().toString();
      row.applicationName = applicationName;
      row.environmentName = environmentName;
      row.resourceName = IDP_RESOURCE_NAME;
      row.createdAt = Instant.now();
    }
    row.resourceType = IDP_RESOURCE_TYPE;
    row.databaseName = null;
    row.roleName = null;
    row.clientId = clientId;
    row.password = secret;
    row.lastProvisionedAt = Instant.now();
    // Persist LAST, with every not-null column set — the postgres arm's own note applies here too.
    if (existing.isEmpty()) {
      resources.persist(row);
    }
  }

  /**
   * {@code http://<tier>-qits-platform-idp:8080/idp} — derived, like the postgres host, never
   * configured.
   *
   * <p><b>It gained the tier when the plane was deleted, and that is a cutover rather than a
   * cosmetic change.</b> It read {@code http://qits-platform-idp:8080/idp} while the plane's services
   * answered on their bare names; qits-platform-idp is an ordinary service in the one tier now, so
   * the address a provisioned container is handed has to carry the tier or it resolves to nothing the
   * moment the bare-named predecessor is retired.
   */
  private static String idpUrl(String environmentName) {
    return "http://" + PdNetworks.alias(environmentName, IDP_APPLICATION) + ":" + IDP_PORT + "/idp";
  }

  /**
   * 32 lowercase hex characters from {@link SecureRandom}. The charset is the point: it survives an
   * argv, a JDBC url and a SQL string literal without one escaping rule between them.
   */
  private static String freshPassword() {
    // Created per call, not held in a static: a build-time SecureRandom lands in the native
    // image heap with a cached seed, and GraalVM refuses to build the image over it.
    // Provisioning is rare; the construction cost is nothing.
    byte[] bytes = new byte[PASSWORD_BYTES];
    new SecureRandom().nextBytes(bytes);
    StringBuilder hex = new StringBuilder(PASSWORD_BYTES * 2);
    for (byte b : bytes) {
      hex.append(Character.forDigit((b >> 4) & 0xf, 16)).append(Character.forDigit(b & 0xf, 16));
    }
    return hex.toString();
  }
}
