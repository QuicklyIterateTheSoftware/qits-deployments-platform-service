package eu.wohlben.qits.platform.deployments.environments.dto;

import java.time.Instant;
import java.util.List;

/**
 * One environment: a name and its bundle network.
 *
 * <p>There is no {@code branch}. A tier listened to {@code environment/<name>} while a green build
 * was the deploy trigger; a release names a tag, so where a version lands is {@code platform} and
 * nothing else. V8 dropped the column.
 *
 * <p>{@code applications} is the tier's own services, attached by the boundary when one environment
 * is fetched and left <b>null</b> on listings — the difference between "this tier holds nothing"
 * and "you did not ask". It never carries the platform services: those belong to no tier, and a
 * reader that took this field for the whole answer would silently miss qits-idp and this component.
 * {@code GET /platform-deployments/api/environments/{id}/links} is the question that composes both.
 *
 * <p>{@code platform} is true on exactly one environment: the tier a release enters the platform
 * at, and the tier the platform plane is deployed into. It still says nothing about what this tier
 * <em>holds</em> in {@code applications} — a platform service carries no link and is reachable from
 * every environment either way.
 */
public record PdEnvironmentDto(
    String id,
    String name,
    String network,
    boolean platform,
    Instant createdAt,
    List<PdApplicationDto> applications) {}
