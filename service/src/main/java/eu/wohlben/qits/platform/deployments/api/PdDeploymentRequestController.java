package eu.wohlben.qits.platform.deployments.api;

import eu.wohlben.qits.platform.deployments.deployments.control.DeployService;
import eu.wohlben.qits.platform.deployments.deployments.control.EnvironmentOperations;
import eu.wohlben.qits.platform.deployments.deployments.dto.PdDeploymentRequestDto;
import eu.wohlben.qits.platform.deployments.deployments.mapper.DeploymentMapper;
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
 * The deployment REQUEST read surface: what a released version asked this platform for, and what the
 * quality gate said about it.
 *
 * <p><b>Why it is not the deployment listing.</b> A request that the gate refused produced no
 * deployment at all — no container, no row, no status word — so it exists nowhere in {@code GET
 * /deployments} and a client reading only that listing would show a release as having simply not
 * happened. This is the listing in which "asked for and declined, because …" is a thing that can be
 * seen. Today the placeholder gate says yes to everything, which is precisely why the surface goes
 * in now: the readers of a refusal have to exist before the first gate has an opinion.
 *
 * <p><b>The environment is a required filter, the deployment listing's shape verbatim</b> ({@code
 * ?environmentId=}). An unscoped listing would return every request on the instance, and a missing
 * tier answers 404 rather than an empty list. {@code ?applicationName=} narrows it further and is
 * optional — one service's release history in one tier.
 *
 * <p><b>There is no {@code ?environmentId=platform}.</b> Its sibling accepts that word because a
 * deployment records which plane it is on; a request does not — it is written before the catalogue
 * is consulted — and a platform service's request names the main environment exactly as its
 * deployment does. So the plane's requests come back in that tier's listing, which is where a reader
 * asking "what has been asked for in dev" wants them, and a word this table cannot answer for is not
 * accepted rather than answered emptily.
 *
 * <p>Read-only and a person's: {@code qits-platform:admin}, the role qits-gateway forwards, like
 * every other listing the web client polls.
 */
@Path("/deployment-requests")
@Produces(MediaType.APPLICATION_JSON)
@jakarta.annotation.security.RolesAllowed("qits-platform:admin")
public class PdDeploymentRequestController {

  @Inject DeployService deployService;
  @Inject EnvironmentOperations environments;
  @Inject DeploymentMapper mapper;
  @Inject PdReadPatience reads;

  public record ListDeploymentRequestsResponse(List<PdDeploymentRequestDto> deploymentRequests) {}

  @GET
  @Operation(summary = "One environment's deployment requests, newest-first — the gate's record")
  @APIResponse(responseCode = "200", description = "The requests")
  @APIResponse(responseCode = "400", description = "environmentId was not given")
  @APIResponse(responseCode = "404", description = "No such environment")
  public ListDeploymentRequestsResponse list(
      @QueryParam("environmentId") String environmentId,
      @QueryParam("applicationName") String applicationName) {
    if (environmentId == null || environmentId.isBlank()) {
      throw new BadRequestException("environmentId is required");
    }
    // Ordered, and held through a short database outage for the deployment listing's reason: a lost
    // connection here would turn "which releases were asked for in this tier" into a 404 for a tier
    // that exists.
    reads.run("The tier check for " + environmentId, () -> environments.require(environmentId));
    return new ListDeploymentRequestsResponse(
        reads
            .call(
                "The deployment requests of " + environmentId,
                () -> deployService.deploymentRequestsFor(environmentId, applicationName))
            .stream()
            .map(mapper::toDto)
            .toList());
  }
}
