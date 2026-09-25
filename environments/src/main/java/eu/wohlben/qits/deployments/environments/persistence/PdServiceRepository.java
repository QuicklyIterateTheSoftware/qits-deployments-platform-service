package eu.wohlben.qits.deployments.environments.persistence;

import eu.wohlben.qits.deployments.environments.entity.PdService;
import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.List;
import java.util.Optional;

/** Panache DAO for {@link PdService}. */
@ApplicationScoped
public class PdServiceRepository implements PanacheRepositoryBase<PdService, String> {

  /** The one row a service name can have — the upsert's read half. */
  public Optional<PdService> findByName(String name) {
    return find("name = ?1", name).firstResultOptional();
  }

  /** Every service, oldest first: the flat catalogue. */
  public List<PdService> listOldestFirst() {
    return list("order by createdAt, id");
  }

  // `listPlatformServices` lived here and went with the plane. It answered "every service that is
  // present in an environment by carrying no link", which is a sentence the schema can no longer
  // say: a service is present where it is linked, and the link query is the whole answer.
}
