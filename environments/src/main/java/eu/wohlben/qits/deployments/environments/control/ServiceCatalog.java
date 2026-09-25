package eu.wohlben.qits.deployments.environments.control;

import eu.wohlben.qits.db.DbRetry;
import eu.wohlben.qits.deployments.environments.entity.PdEnvironment;
import eu.wohlben.qits.deployments.environments.entity.PdService;
import eu.wohlben.qits.deployments.environments.entity.PdServiceLink;
import eu.wohlben.qits.deployments.environments.error.BadRequestException;
import eu.wohlben.qits.deployments.environments.error.NotFoundException;
import eu.wohlben.qits.deployments.environments.persistence.PdEnvironmentRepository;
import eu.wohlben.qits.deployments.environments.persistence.PdServiceLinkRepository;
import eu.wohlben.qits.deployments.environments.persistence.PdServiceRepository;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * The catalogue of services and the environments they are linked into — the topology's other half,
 * and the one the deploy orchestration writes to.
 *
 * <p>Writes arrive as one operation, {@link #upsert}, because the writer is derived: a green build
 * reads a repository's {@code .config/qits/deployments.yml} at that sha and states the whole shape
 * it found. There is no create/update pair and no partial write — a caller that knows the file
 * knows everything about the service, so a merge could only ever preserve something the file has
 * stopped saying.
 *
 * <p><b>One rule lives here and nowhere else: the link set is replaced, never merged.</b> A
 * service's links are exactly the environments the upsert names.
 *
 * <p><b>The other two rules went with the platform plane.</b> They were "a platform service carries
 * no links" (a 400) and "the plane flip is one-way" (a 409 backwards), and both said something about
 * a column this catalogue no longer has. A service is registered with links to the environments it
 * runs in, always — <b>including the nine that used to be the plane</b> — so there is no second
 * shape of row to validate against, nothing to convert and nothing to refuse.
 */
@ApplicationScoped
public class ServiceCatalog {

  @Inject PdServiceRepository services;
  @Inject PdServiceLinkRepository links;
  @Inject PdEnvironmentRepository environments;

  /**
   * How long the REST door's upsert may be held while the database comes back — the request-path
   * deadline the read surface spends, for the same caller. See {@link #upsert(Upsert)}, which is the
   * only method here that spends it.
   */
  @ConfigProperty(name = "qits.deployments.db-retry-deadline")
  Duration writeDeadline;

  /**
   * What an upsert states. {@code branch} is <b>vestigial</b> — nothing decides a deployment on it
   * any more — and derived registration sends null. It is stored as it arrives, so an operator's
   * write over the API round-trips as it always did. See {@code PdService.branch}.
   */
  public record Upsert(
      String name,
      String branch,
      boolean availableOnEnv,
      String healthPath,
      List<String> environmentIds) {}

  /** A service together with the environments it is linked into. */
  public record LinkedService(PdService service, List<String> environmentIds) {}

  /**
   * What an upsert did. {@code created} is reported rather than inferred by the caller: an
   * "exists?" read outside the write's own transaction would be a second, racing answer, and the
   * only thing it decides is 201 against 200.
   */
  public record UpsertResult(LinkedService service, boolean created) {}

  /**
   * One service as the flat read surface reports it: a row flattened into one tier. {@code
   * environmentId} and {@code environmentName} are null exactly for a service the catalogue links
   * nowhere — a row registered before this release, or one whose links an operator emptied.
   */
  public record ApplicationView(PdService service, String environmentId, String environmentName) {}

  /**
   * Register or update one service, whole.
   *
   * <p><b>Synchronized, and that is load-bearing.</b> "Is there a row for this name yet, and if not
   * make one" is a read-then-write with no constraint able to turn a lost race into anything but a
   * 500 — and the writer fans a green build out over every environment tracking a branch, so it can
   * arrive twice at once. The deploy worker is single-threaded for exactly this reason and this
   * lock is the belt for every other caller; it costs nothing, since an upsert is three short
   * statements against one local database.
   *
   * <p><b>There is one shape of row and therefore no flip to arbitrate.</b> This method used to
   * convert a service onto the platform plane and refuse the way back with a 409; the plane is gone,
   * so an upsert states a link set and the row takes it.
   *
   * @throws BadRequestException on a failed validation
   * @throws NotFoundException if an environment id names no environment
   */
  public UpsertResult upsert(Upsert request) {
    // The REST door. No hop stands between the request thread and the insert below, so the
    // CausationStamp listener fills PdService.causationId from the scope CausationServerFilter
    // restored — passing a value here would only overwrite a better one with nothing.
    //
    // IT IS ALSO WHERE THE PATIENCE GOES, and this door is the only place it fits. The write below
    // is `synchronized`, so a retry INSIDE it would sleep holding the catalogue's monitor — the one
    // placement the platform's db-patience rules forbid. Wrapped here, each attempt takes the lock
    // and releases it, and the pause between attempts holds nothing.
    //
    // `call` rather than `inNewTx`, and the difference is the transaction boundary. `inNewTx` is
    // for a write whose safety comes from the retry OWNING that boundary; this write's boundary has
    // to stay inside the monitor, because the lock is what makes "is there a row for this name yet,
    // and if not make one" atomic — a commit outside it would let two callers both read "no row".
    // What makes a second attempt safe here is the write's own shape instead: an upsert by name
    // converges. Re-running it finds the row the lost attempt may have written and updates it to
    // exactly the same values, so the effect is once whatever happened to the first attempt. That
    // judgement belongs at a call site, and this is the call site.
    //
    // The worker's door below is deliberately NOT wrapped: derived registration runs before
    // anything docker-side has happened, where losing an event leaves nothing half-done — the same
    // rule that leaves `queue` and `recordRejection` bare. See DeployService.
    return DbRetry.call(
        "The registration of service " + request.name(), () -> upsert(request, null), writeDeadline);
  }

  /**
   * The same upsert, stating the cause as data.
   *
   * <p>For derived registration only, and it exists because that caller stands on {@code
   * pd-deploy-worker}: the announcement crossed an executor hop to get there and {@code
   * CausationScope} — a ThreadLocal — did not follow it, so the stamp would write null on the one
   * row where the answer is actually known. {@code null} means "let the stamp decide", which is
   * what the four-argument form above passes.
   */
  public synchronized UpsertResult upsert(Upsert request, UUID causationId) {
    String name = PdIdentifiers.requireName(request.name(), "service name");
    List<String> requestedEnvironments =
        request.environmentIds() == null ? List.of() : request.environmentIds();
    String healthPath =
        isBlank(request.healthPath()) ? null : PdIdentifiers.requireHealthPath(request.healthPath());
    // Vestigial, and kept only so an operator's write round-trips: nothing decides a deployment on
    // it. It used to be stored beside PLATFORM alone and dropped otherwise, which was the plane
    // being asked a question about a column it had no opinion on.
    String branch =
        isBlank(request.branch()) ? null : PdIdentifiers.requireBranch(request.branch());

    // Deduplicated in request order: naming an environment twice states the same link twice, which
    // is a caller's redundancy rather than an error, and the unique constraint would otherwise turn
    // it into a 500.
    List<String> environmentIds = new ArrayList<>(new LinkedHashSet<>(requestedEnvironments));

    return QuarkusTransaction.requiringNew()
        .call(
            () -> {
              PdService service = services.findByName(name).orElse(null);
              boolean created = service == null;
              if (created) {
                service = new PdService();
                service.id = UUID.randomUUID().toString();
                service.name = name;
                // Only on the insert, and only ever the insert: the column answers "which event
                // put this service in the catalogue", and every later upsert is an update of a row
                // that already has its answer. Null leaves the stamp to fill it from the scope of
                // whatever thread is calling — see the two entry points above.
                service.causationId = causationId;
                service.createdAt = Instant.now();
                services.persist(service);
              }
              service.branch = branch;
              service.availableOnEnv = request.availableOnEnv();
              service.healthPath = healthPath;

              // Replace, never merge.
              links.deleteByService(service.id);
              for (String environmentId : environmentIds) {
                PdEnvironment environment =
                    environments
                        .findByIdOptional(environmentId)
                        .orElseThrow(
                            () -> new NotFoundException("No such environment: " + environmentId));
                PdServiceLink link = new PdServiceLink();
                link.id = UUID.randomUUID().toString();
                link.service = service;
                link.environment = environment;
                link.createdAt = Instant.now();
                links.persist(link);
              }
              return new UpsertResult(new LinkedService(service, environmentIds), created);
            });
  }

  /** Remove a service and its links — the operator's deliberate act on a derived catalogue. */
  public void delete(String name) {
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              PdService service = require(name);
              links.deleteByService(service.id);
              services.delete(service);
            });
  }

  /**
   * <b>Every read below brackets itself with {@link QuarkusTransaction#joiningExisting()}</b>, and
   * that is not decoration. A JAX-RS caller has a request context and Hibernate would answer a read
   * without a transaction — but the catalogue's other caller is the deploy worker, a bare daemon
   * thread with neither, and there the same call throws {@code ContextNotActiveException}. That
   * hazard is new with the merge: derived registration used to reach the catalogue over HTTP, which
   * needs no session at all. Joining rather than requiring a new one keeps a caller that already
   * has a transaction ({@link #delete}) in it, so the entity it reads stays managed.
   */

  /** Every service, oldest first, each with the environments it is linked into. */
  public List<LinkedService> list() {
    return QuarkusTransaction.joiningExisting()
        .call(
            () -> {
              List<LinkedService> catalogue = new ArrayList<>();
              for (PdService service : services.listOldestFirst()) {
                catalogue.add(new LinkedService(service, links.listEnvironmentIdsOf(service.id)));
              }
              return List.copyOf(catalogue);
            });
  }

  /** The one row a name can have, or empty — the deploy orchestration's read half. */
  public Optional<LinkedService> find(String name) {
    return QuarkusTransaction.joiningExisting()
        .call(
            () ->
                services
                    .findByName(name)
                    .map(
                        service ->
                            new LinkedService(service, links.listEnvironmentIdsOf(service.id))));
  }

  public PdService require(String name) {
    return QuarkusTransaction.joiningExisting()
        .call(
            () ->
                services
                    .findByName(name)
                    .orElseThrow(() -> new NotFoundException("No such service: " + name)));
  }

  /**
   * The pull query: every service present in one environment — which is exactly the ones linked
   * into it.
   *
   * <p><b>It was a composition and is one query now.</b> The links used to be half the answer: every
   * platform service was appended, because the plane's way of saying "present everywhere" was to
   * carry no link at all. The plane is deleted and qits-idp and this component are linked into the
   * tier they run in like everything else, so the second half described nothing and reading it would
   * be a rule with no rows behind it.
   *
   * @throws NotFoundException if the environment does not exist
   */
  public List<PdService> linksOf(String environmentId) {
    return QuarkusTransaction.joiningExisting()
        .call(
            () -> {
              environments
                  .findByIdOptional(environmentId)
                  .orElseThrow(
                      () -> new NotFoundException("No such environment: " + environmentId));
              return List.copyOf(links.listServicesOf(environmentId));
            });
  }

  /**
   * The services of one environment as the environment aggregate reports them.
   *
   * <p>It answers the same rows as {@link #linksOf} now and is kept as a separate method because the
   * two have different shapes and different readers — an aggregate carries the tier's name with each
   * row, a reconciliation does not.
   */
  public List<ApplicationView> applicationsOf(PdEnvironment environment) {
    return QuarkusTransaction.joiningExisting()
        .call(
            () -> {
              List<ApplicationView> scoped = new ArrayList<>();
              for (PdService service : links.listServicesOf(environment.id)) {
                scoped.add(new ApplicationView(service, environment.id, environment.name));
              }
              return List.copyOf(scoped);
            });
  }

  /**
   * Every application this component deploys, flat: one row per environment link.
   *
   * <p>A service the catalogue links nowhere still gets one row, with no tier on it. That used to be
   * the platform plane's shape and is now the honest report of a service that runs nowhere — a row
   * registered before the plane was deleted, or one whose links an operator emptied — which is a
   * thing a reader has to be able to see rather than a row to hide.
   */
  public List<ApplicationView> allApplications() {
    return QuarkusTransaction.joiningExisting()
        .call(
            () -> {
              Map<String, String> environmentNames = new LinkedHashMap<>();
              for (PdEnvironment environment : environments.listNewestFirst()) {
                environmentNames.put(environment.id, environment.name);
              }
              List<ApplicationView> views = new ArrayList<>();
              for (LinkedService linked : list()) {
                if (linked.environmentIds().isEmpty()) {
                  views.add(new ApplicationView(linked.service(), null, null));
                  continue;
                }
                for (String environmentId : linked.environmentIds()) {
                  views.add(
                      new ApplicationView(
                          linked.service(), environmentId, environmentNames.get(environmentId)));
                }
              }
              return List.copyOf(views);
            });
  }

  private static boolean isBlank(String value) {
    return value == null || value.isBlank();
  }
}
