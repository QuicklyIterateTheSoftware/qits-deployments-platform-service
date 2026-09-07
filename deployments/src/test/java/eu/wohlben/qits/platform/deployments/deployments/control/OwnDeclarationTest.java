package eu.wohlben.qits.platform.deployments.deployments.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * This repository declares ITSELF, and this holds the fact rather than the grammar.
 *
 * <p><b>Why the file is committed at all.</b> The extras read is addressed by the released version,
 * and qits-configuration answers 404 for a version it holds no declaration for; the deployer seeds a
 * declaration only for a release whose tag carries {@link SpecSource#DECLARATION_PATH}. So the
 * release that ships the version-less fallback ({@link DeploymentExtrasSource#forDeployment}) has to
 * be deployable by the deployer that is still running WITHOUT the fallback — which it is, because
 * this file is there: the seed runs, the store holds this version's declaration, and the
 * version-addressed read finds one.
 *
 * <p><b>What this test deliberately is NOT.</b> It is not a parser. qits-configuration owns that
 * grammar and is the estate's one strict parser of this document — a second opinion here would
 * disagree with it the day the grammar grows a key, which is the day a release refuses a file the
 * store takes perfectly well. That is the same reason {@code GitHostSpecSource} returns the
 * declaration UNPARSED. The document itself was checked against the real {@code DeclarationParser}
 * by running it, once, when it was written; what is worth a standing assertion is only what a hand
 * edit here can break without anybody noticing until a release is refused: the file being present,
 * at the exact path the deployer fetches, carrying the one top-level key and no duplicate key names.
 */
class OwnDeclarationTest {

  /** The document, found by walking up from whatever directory surefire started this module in. */
  private static Path declaration() {
    Path at = Path.of("").toAbsolutePath();
    for (int up = 0; up < 4 && at != null; up++, at = at.getParent()) {
      Path candidate = at.resolve(SpecSource.DECLARATION_PATH);
      if (Files.isRegularFile(candidate)) {
        return candidate;
      }
    }
    throw new AssertionError(
        "no " + SpecSource.DECLARATION_PATH + " above " + Path.of("").toAbsolutePath());
  }

  @Test
  void thisRepositoryCarriesItsOwnDeclarationWhereTheDeployerLooksForIt() throws IOException {
    // The path is the deployer's own constant rather than a literal, because the fetch and the file
    // have to be the same string: a declaration at a path nothing reads is a release that seeds
    // nothing and is refused by the very read this file exists to satisfy.
    String raw = Files.readString(declaration());

    assertFalse(raw.isBlank(), "an empty document is refused by the store");
  }

  @Test
  void theTopLevelIsTheOneKeyTheStoreAccepts() throws IOException {
    // The document is closed at the top: `keys` and nothing else, and the store refuses a second
    // top-level key rather than ignoring it. A stray unindented line is the easiest way to break
    // this file by hand and the hardest to see, so it is the one shape worth pinning.
    List<String> topLevel = new ArrayList<>();
    for (String line : Files.readAllLines(declaration())) {
      if (line.isBlank() || line.startsWith("#") || line.startsWith(" ")) {
        continue;
      }
      topLevel.add(line);
    }

    assertEquals(List.of("keys:"), topLevel);
  }

  @Test
  void noKeyIsDeclaredTwice() throws IOException {
    // The store's loader refuses duplicate keys outright — "the last one wins" is not an answer a
    // store of record may give — so a copy-paste that repeats a name is a DECLARATION_REFUSED
    // release rather than a document with one redundant line.
    Set<String> seen = new LinkedHashSet<>();
    for (String line : Files.readAllLines(declaration())) {
      String trimmed = line.strip();
      if (!trimmed.startsWith("env.") || !trimmed.endsWith(":")) {
        continue;
      }
      assertTrue(seen.add(trimmed), "declared twice: " + trimmed);
    }

    assertFalse(seen.isEmpty(), "the document declares no keys at all");
  }
}
