package eu.wohlben.qits.deployments.deployments.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.deployments.deployments.control.SpecSource.DeploymentSpec;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * This repository deploys ITSELF, so its own {@code .config/qits/deployments.yml} is the one spec on
 * the estate whose reader is in the same reactor — and the only one a build here can hold to account.
 * {@code OwnDeclarationTest} is the sibling for the other file; this is the same idea for the one
 * that decides where the container runs.
 *
 * <p><b>Unlike the declaration, this file IS parsed here, with the real parser.</b> The argument that
 * keeps {@code OwnDeclarationTest} from being a parser — qits-configuration owns that grammar and a
 * second opinion would disagree with it — runs the other way round for this document:
 * {@link DeploymentSpecParser} is this component's own, a spec is fetched at the released tag, and an
 * unknown key fails a deployment. So parsing it here is not a second opinion, it is the only opinion.
 *
 * <p><b>What it pins is the self-deploy's health gate, and it is a trap rather than a tidiness
 * rule.</b> {@link DeployService#resolveHealthPath} asks the spec, then the STORED {@code
 * pd_service.health_path} row, then {@link DeployService#conventionHealthPath}. The convention now
 * derives exactly what this file states — the route segment and the application name finally agree —
 * which makes the {@code health_path:} line look like a redundant override somebody could delete as
 * a simplification. It is not: the stored row on the live platform still holds
 * {@code /platform-deployments/q/health/ready}, written when the route really was there, and the row
 * beats the convention. Delete the key and every self-deploy curls a path the new image 404s, never
 * converges, and rolls back — and an operator-corrected row is not durable across a re-bootstrap.
 *
 * <p>So the assertion is deliberately the awkward one: the key must be PRESENT, and its value must
 * equal what the convention derives. Either half alone would pass the edit that breaks a deployment.
 */
class OwnSpecTest {

  /** The application this repository deploys as — short, and not the repository's name. */
  private static final String APPLICATION = "qits-deployments";

  /** The spec, found by walking up from whatever directory surefire started this module in. */
  private static Path spec() {
    Path at = Path.of("").toAbsolutePath();
    for (int up = 0; up < 4 && at != null; up++, at = at.getParent()) {
      Path candidate = at.resolve(SpecSource.SPEC_PATH);
      if (Files.isRegularFile(candidate)) {
        return candidate;
      }
    }
    throw new AssertionError("no " + SpecSource.SPEC_PATH + " above " + Path.of("").toAbsolutePath());
  }

  private static DeploymentSpec parsed() throws IOException {
    return DeploymentSpecParser.parse(Files.readString(spec()), SpecSource.SPEC_PATH);
  }

  @Test
  void thisRepositoryDeploysAsTheShortApplicationName() throws IOException {
    assertEquals(
        APPLICATION,
        parsed().application(),
        "the repository is named for its role and the application is not — see the file's own header");
  }

  @Test
  void theHealthPathIsSTATEDAndIsWhatTheConventionDerives() throws IOException {
    String stated = parsed().healthPath();

    // PRESENT: the stored pd_service.health_path row still names the retired prefix and beats the
    // convention, so the override is what stands between a self-deploy and a gate against a 404.
    assertNotNull(
        stated,
        "health_path: must stay in .config/qits/deployments.yml — the stored pd_service row holds"
            + " the retired path and beats the convention, so removing the key gates every"
            + " self-deploy against a path this image 404s");

    // AND EQUAL TO THE CONVENTION: the route segment and the application name agree now, so a
    // value that does not match the convention is a value that does not match the served route.
    assertEquals(
        DeployService.conventionHealthPath(APPLICATION),
        stated,
        "the stated health path and the convention derived from the application name are the same"
            + " string now; a difference means one of the two moved without the other");
  }

  @Test
  void theRoutesDeclareTheServedPrefixAndTheRetiredOne() throws IOException {
    // The edge projects its route table from `routes:`, so the live prefix has to be there or the
    // API is reachable on no vhost at all. The retired one is there for one release, which is what
    // keeps the edge routing it here while api/LegacyPrefixReroute rewrites it — remove that entry
    // in the commit that removes the reroute, not before.
    assertEquals(
        java.util.List.of("/deployments", "/platform-deployments"),
        parsed().routes(),
        "the served prefix first, the retired one behind it for one release");
  }

  @Test
  void theApiDocsLinkSitsUnderTheServedPrefix() throws IOException {
    String apiDocs = parsed().apiDocs();

    assertNotNull(apiDocs, "the shell's Api Docs viewer reads this off the navigation document");
    assertTrue(
        apiDocs.startsWith("/deployments/"),
        "a documented API at a prefix this service does not serve is a dead link in the shell: "
            + apiDocs);
  }
}
