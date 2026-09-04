package eu.wohlben.qits.platform.deployments.environments.persistence;

import eu.wohlben.qits.platform.deployments.environments.entity.PdEnvironment;
import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.List;
import java.util.Optional;

/** Panache DAO for {@link PdEnvironment} (keyed by its String UUID row id). */
@ApplicationScoped
public class PdEnvironmentRepository implements PanacheRepositoryBase<PdEnvironment, String> {

  public Optional<PdEnvironment> findByName(String name) {
    return find("name = ?1", name).firstResultOptional();
  }

  /** All environments, newest-first. */
  public List<PdEnvironment> listNewestFirst() {
    return list("order by createdAt desc, id desc");
  }

  /**
   * The platform environment — the tier a release enters at, and the tier the platform plane is
   * deployed into.
   *
   * <p>A list rather than an optional because the schema does not enforce the "at most one" (V1's
   * header declines the partial unique index, because {@code EnvironmentService.designate} moves
   * the flag in one transaction and an index would forbid that statement order's own intermediate
   * state), and a query that threw on a second row would turn a repairable state into an outage.
   * Ordered, so the answer is at least stable if one ever appears.
   */
  public List<PdEnvironment> listPlatform() {
    return list("platform = true order by createdAt, id");
  }
}
