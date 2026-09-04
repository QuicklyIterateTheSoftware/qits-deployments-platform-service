package eu.wohlben.qits.platform.deployments.api;

import eu.wohlben.qits.platform.deployments.deployments.control.ApplicationScaling;
import eu.wohlben.qits.platform.deployments.environments.control.ServiceCatalog;
import eu.wohlben.qits.platform.deployments.environments.dto.PdApplicationDto;
import eu.wohlben.qits.platform.deployments.environments.error.BadRequestException;
import eu.wohlben.qits.platform.deployments.environments.mapper.EnvironmentMapper;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.SecurityContext;
import java.util.List;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;

/**
 * Every application this component deploys, in one flat list — the environments' and the platform's
 * together — and the two levers an operator has over one of them.
 *
 * <p>It is flat because a platform service belongs to no environment: reading through the
 * environments would leave qits-platform-idp and this component out of it, which are the two a
 * reader most wants to find. Each row says which plane it is on ({@code target}) and, for an
 * environment application, which tier ({@code environmentId}/{@code environmentName}).
 *
 * <p><b>The listing is read-only and the catalogue still is</b>: rows here are derived from each
 * repository's own {@code .config/qits/deployments.yml} on every green build, and nothing on this
 * surface creates, renames or removes one. What the two POSTs below write is not the catalogue and
 * not a deployment — they act on the <b>running service</b> an application's newest deployment left
 * behind. See {@link ApplicationScaling}, which argues the whole shape; the short version is that a
 * restart used to cost a same-sha push and fifteen minutes of rebuild.
 *
 * <p><b>Both are 202, and that is the component's own idiom rather than a hedge.</b> Every docker
 * call here happens on the single deploy worker, so an operator's action queues behind whatever is
 * deploying — the same place a build-succeeded event goes, for the same reason. The deployment
 * listing is where the result is read.
 *
 * <p><b>Both take {@code qits-platform:admin}, the reader's role, and that is a decision.</b> This
 * is a person's operational action, driven from this component's own web client through the
 * platform edge's forwarded {@code X-Qits-Roles} header — the same caller every read on this surface
 * has. The machine role {@code qits-platform:system} is deliberately NOT granted: the two sets do
 * not overlap, and nothing on the platform should be able to stop an application as a side effect of
 * holding a service token. A machine door for this is a separate decision with a separate argument.
 *
 * <p><b>How it differs from {@code GET /services}</b>, which is the same data: this one has one
 * entry per (service, tier) and carries a derived {@code id} the client joins against a
 * deployment's {@code applicationId}; that one has one entry per service, with its link set, and
 * round-trips into the upsert. Two questions, two shapes, one catalogue.
 */
@Path("/applications")
@Produces(MediaType.APPLICATION_JSON)
@jakarta.annotation.security.RolesAllowed("qits-platform:admin")
public class PdApplicationController {

  @Inject ServiceCatalog catalog;
  @Inject EnvironmentMapper mapper;
  @Inject PdReadPatience reads;
  @Inject ApplicationScaling scaling;

  public record ListApplicationsResponse(List<PdApplicationDto> applications) {}

  /**
   * How many tasks of this application should run. {@code 0} stops it, {@code 1} brings it back.
   *
   * <p>It is an {@code Integer} rather than an {@code int} so an omitted field is a 400 that names
   * itself, instead of a scale to zero nobody asked for.
   */
  public record ScaleRequest(Integer replicas) {}

  /**
   * What was resolved and what was queued — never an outcome, because nothing has been issued when
   * this is written. {@code replicas} is null on a restart, which asks for no count.
   */
  public record OperationResponse(
      String applicationId,
      String applicationName,
      String environmentId,
      String serviceName,
      String deploymentId,
      Integer replicas) {}

  /** Held through a short database outage rather than answering 500 — see {@link PdReadPatience}. */
  @GET
  @Operation(summary = "Every application deployed here — environment applications and platform services")
  @APIResponse(responseCode = "200", description = "The applications, one entry per tier")
  public ListApplicationsResponse list() {
    return new ListApplicationsResponse(
        reads.call("The application listing", catalog::allApplications).stream()
            .map(mapper::toDto)
            .toList());
  }

  /**
   * Set how many tasks of this application run — {@code 0} to stop it, {@code 1} to start it again.
   *
   * <p>Scaling to zero keeps the service, its image, its environment, its networks, its mounts and
   * its published ports, so scaling back up is the same deployment coming back rather than a new
   * one. The deployment row keeps its identity either way; what it gains is the word {@code
   * SCALED_TO_ZERO} while the workload is stopped.
   *
   * <p>Deliberately <b>not</b> wrapped in {@link PdReadPatience}: no write on this surface is.
   */
  @POST
  @Path("/{applicationId}/scale")
  @Consumes(MediaType.APPLICATION_JSON)
  @Operation(summary = "Scale an application's workload — 0 stops it, 1 runs it")
  @APIResponse(responseCode = "202", description = "Queued on the deploy worker")
  @APIResponse(responseCode = "400", description = "No replica count, a negative one, or one above the single task every application here is deployed as")
  @APIResponse(responseCode = "404", description = "Nothing has ever been deployed for this application")
  @APIResponse(responseCode = "409", description = "Its newest deployment never reached the orchestrator")
  public Response scale(
      @PathParam("applicationId") String applicationId,
      ScaleRequest request,
      @Context SecurityContext security) {
    if (request == null || request.replicas() == null) {
      throw new BadRequestException("replicas is required");
    }
    return accepted(scaling.scale(applicationId, request.replicas(), actor(security)));
  }

  /**
   * Replace the tasks running under this application's name, unchanged — the bounce.
   *
   * <p><b>No deployment row is created and none is re-stated.</b> A restart is an operation on a
   * container, not an attempt to put a commit live: the sha, the image, the timestamps and the
   * history stay exactly as the deployment left them, and what a reader sees afterwards is a stamp
   * on that row's detail saying who bounced it and when.
   */
  @POST
  @Path("/{applicationId}/restart")
  @Operation(summary = "Restart an application in place — its tasks are replaced, its deployment is not")
  @APIResponse(responseCode = "202", description = "Queued on the deploy worker")
  @APIResponse(responseCode = "400", description = "Not an application id")
  @APIResponse(responseCode = "404", description = "Nothing has ever been deployed for this application")
  @APIResponse(responseCode = "409", description = "Its newest deployment never reached the orchestrator")
  public Response restart(
      @PathParam("applicationId") String applicationId, @Context SecurityContext security) {
    return accepted(scaling.restart(applicationId, actor(security)));
  }

  private static Response accepted(ApplicationScaling.Accepted queued) {
    return Response.accepted()
        .entity(
            new OperationResponse(
                queued.applicationId(),
                queued.applicationName(),
                queued.environmentId(),
                queued.serviceName(),
                queued.deploymentId(),
                queued.replicas()))
        .build();
  }

  /**
   * Who asked, as the row's stamp records it — the forwarded identity and nothing derived. Null
   * outside an authenticated call, which reads as "an operator" and is the honest answer for a
   * posture where the edge asserts the role and not a name.
   */
  private static String actor(SecurityContext security) {
    return security == null || security.getUserPrincipal() == null
        ? null
        : security.getUserPrincipal().getName();
  }
}
