package eu.wohlben.qits.platform.deployments.api;

import eu.wohlben.qits.platform.deployments.deployments.control.DeployService;
import eu.wohlben.qits.platform.deployments.deployments.control.EnvironmentOperations;
import eu.wohlben.qits.platform.deployments.deployments.dto.PdDeploymentDto;
import eu.wohlben.qits.platform.deployments.deployments.entity.PdDeployment;
import eu.wohlben.qits.platform.deployments.deployments.mapper.DeploymentMapper;
import eu.wohlben.qits.platform.deployments.environments.control.ApplicationKeys;
import eu.wohlben.qits.platform.deployments.environments.error.BadRequestException;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import java.util.List;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;

/**
 * The deployment read surface. The deployment is the entity and the environment is a required
 * <b>filter</b> ({@code ?environmentId=}), the ci-runs shape: an unscoped listing would return
 * every deployment on the instance, and a missing environment must say so (404) rather than answer
 * with an empty list.
 *
 * <p><b>The platform plane is asked for by name:</b> {@code ?environmentId=platform} returns the
 * deployments on that PLANE. That is the {@code platform:} stand-in from {@link ApplicationKeys},
 * reused rather than respelled, so the word a client already reads at the front of a platform
 * application's id is the word it filters with. It cannot be mistaken for a tier — an environment
 * id is a random UUID — and it is a named plane rather than a widening: dropping the filter
 * altogether still answers 400, because an unscoped listing would return every deployment on the
 * instance.
 *
 * <p><b>The two filters overlap now, and that is the honest answer rather than a leak.</b> A
 * platform service is deployed INTO the designated environment, so its rows carry that tier and a
 * tier's listing shows them — which is what an operator asking "what is running in dev" means. The
 * plane's own listing is the same rows asked for by plane, off {@code pd_deployment}'s own column
 * rather than off a missing tier, which is what it was before V8 and could not stay.
 */
@Path("/deployments")
@Produces(MediaType.APPLICATION_JSON)
@jakarta.annotation.security.RolesAllowed("qits-platform:admin")
public class PdDeploymentController {

  @Inject DeployService deployService;
  @Inject EnvironmentOperations environments;
  @Inject DeploymentMapper mapper;
  @Inject PdReadPatience reads;

  public record ListDeploymentsResponse(List<PdDeploymentDto> deployments) {}

  @GET
  @Operation(
      summary = "One plane's recorded deployments, newest-first — an environment id, or 'platform'")
  @APIResponse(responseCode = "200", description = "The deployments")
  @APIResponse(responseCode = "400", description = "environmentId was not given")
  @APIResponse(responseCode = "404", description = "No such environment")
  public ListDeploymentsResponse list(@QueryParam("environmentId") String environmentId) {
    if (environmentId == null || environmentId.isBlank()) {
      throw new BadRequestException("environmentId is required");
    }
    List<PdDeployment> rows;
    if (ApplicationKeys.isPlatform(environmentId)) {
      rows = deployService.platformDeployments();
    } else {
      // Ordered: a tier that does not exist is a 404 rather than an empty list. The platform plane
      // takes no such check — it is not a row, so there is nothing that could be missing.
      //
      // Held through a short database outage (PdReadPatience): a lost connection here would turn
      // "which deployments does this tier have" into a 404 for a tier that exists.
      reads.run("The tier check for " + environmentId, () -> environments.require(environmentId));
      rows = deployService.deploymentsFor(environmentId);
    }
    return new ListDeploymentsResponse(rows.stream().map(mapper::toDto).toList());
  }
}
