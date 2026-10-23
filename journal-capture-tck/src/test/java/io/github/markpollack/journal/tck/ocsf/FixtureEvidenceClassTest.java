package io.github.markpollack.journal.tck.ocsf;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Evidence classes stay exclusive and honest. Each committed fixture's stated origin agrees with its provenance; a copy
 * relabelled to another class, or carrying another class's provenance, is reported; and a sanitized real stream recorded
 * outside a live run of this capture is a replay, never synthetic or live.
 */
class FixtureEvidenceClassTest {

    static Path fixtures() throws URISyntaxException {
        return Path.of(OcsfFixtures.class.getResource("/ocsf/fixtures/README.md").toURI()).getParent();
    }

    /** Reproduced on the unchanged a7673e3 LiveScenarioTest.fixtureClass, which read synthetic for both sanitized real streams. */
    @Test
    void eachCommittedFixtureHasExactlyItsStatedClass() throws Exception {
        assertThat(LiveScenarioTest.fixtureClass(OcsfFixtures.CHILD)).isEqualTo("replay");
        assertThat(LiveScenarioTest.fixtureClass(OcsfFixtures.INTERRUPTED)).isEqualTo("replay");
        assertThat(LiveScenarioTest.fixtureClass(OcsfFixtures.LIVE_CODEX_MCP)).isEqualTo("live");
        assertThat(LiveScenarioTest.fixtureClass(OcsfFixtures.CODEX_FAMILIES)).isEqualTo("synthetic");
        try (Stream<Path> dirs = Files.list(fixtures())) {
            for (Path dir : dirs.filter(Files::isDirectory).toList()) {
                assertThat(FixtureEvidenceClass.problems(dir)).as(dir.getFileName().toString()).isEmpty();
            }
        }
    }

    @Test
    void aRelabelledOrMixedClassIsReported(@TempDir Path work) throws Exception {
        Path synthetic = copyTree(fixtures().resolve(OcsfFixtures.CODEX_FAMILIES), work.resolve("synthetic-as-live"));
        relabel(synthetic, "LABELLED SYNTHETIC", "SANITIZED REAL LIVE RECORDING");
        assertThat(FixtureEvidenceClass.problems(synthetic)).singleElement().asString().contains("labelled a live recording without the frozen live process a live provenance manifest");

        Path live = copyTree(fixtures().resolve(OcsfFixtures.LIVE_CODEX_MCP), work.resolve("live-as-synthetic"));
        relabel(live, "SANITIZED REAL LIVE RECORDING", "LABELLED SYNTHETIC RECORDING");
        assertThat(FixtureEvidenceClass.problems(live)).singleElement().asString().contains("labelled SYNTHETIC but carries a frozen live process a live provenance manifest");

        Path real = copyTree(fixtures().resolve(OcsfFixtures.CHILD), work.resolve("real-with-provenance"));
        Files.copy(fixtures().resolve(OcsfFixtures.LIVE_CODEX_MCP).resolve("provenance.json"), real.resolve("provenance.json"));
        assertThat(FixtureEvidenceClass.problems(real)).singleElement().asString().contains("labelled RECORDED_STREAM but carries a live provenance manifest");

        Path unlabelled = copyTree(fixtures().resolve(OcsfFixtures.CODEX_FAMILIES), work.resolve("unlabelled"));
        relabel(unlabelled, "LABELLED SYNTHETIC", "SOMETHING");
        assertThat(FixtureEvidenceClass.origin(unlabelled)).isNull();
        assertThat(FixtureEvidenceClass.problems(unlabelled)).singleElement().asString().contains("states no evidence class");
    }

    static void relabel(Path dir, String from, String to) throws IOException {
        Path spec = dir.resolve("spec.json");
        String s = Files.readString(spec, StandardCharsets.UTF_8);
        assertThat(s).contains(from);
        Files.writeString(spec, s.replaceFirst("\"label\": \"" + java.util.regex.Pattern.quote(from), "\"label\": \"" + java.util.regex.Matcher.quoteReplacement(to)), StandardCharsets.UTF_8);
        assertThat(Files.readString(spec, StandardCharsets.UTF_8)).contains(to);
    }

    static Path copyTree(Path from, Path to) throws IOException {
        try (Stream<Path> s = Files.walk(from)) {
            for (Path p : s.toList()) {
                Path t = to.resolve(from.relativize(p).toString());
                if (Files.isDirectory(p)) {
                    Files.createDirectories(t);
                }
                else {
                    Files.copy(p, t);
                }
            }
        }
        return to;
    }
}
