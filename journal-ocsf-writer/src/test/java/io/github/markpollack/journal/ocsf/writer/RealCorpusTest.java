package io.github.markpollack.journal.ocsf.writer;

import io.github.markpollack.journal.tck.ocsf.OcsfReaderTool;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every real recording of the private corpus ({@code -Djournal.corpus=<agent-journal-corpus checkout>}), sealed again
 * offline through this writer and read by the independent reader, must have the verdict its README states
 * ({@code expected_verdict}, ACCEPTED when absent) and, when one is stated, a reason containing {@code expected_reason}. Each run's sealed Client manifest is
 * qualified again by the current qualifier, the stored terminal envelope's association is restated with the requalified
 * sources' extents (as Client restates them when it records), and the envelope's decision is delivered unchanged. No CLI
 * runs. Without the property the corpus is absent and the test is skipped, so a build never depends on private files.
 */
class RealCorpusTest {
    @TempDir Path temp;

    @TestFactory Stream<DynamicTest> everyRealRecordingIsAcceptedWhenSealedAgain() throws IOException {
        String corpus = System.getProperty("journal.corpus");
        Assumptions.assumeTrue(corpus != null, "set journal.corpus to the agent-journal-corpus checkout");
        List<Path> runs;
        try (Stream<Path> files = Files.walk(Path.of(corpus))) {
            runs = files.filter(p -> p.getFileName().toString().equals("README.md") && Files.isDirectory(p.resolveSibling("files")))
                    .map(Path::getParent).sorted().toList();
        }
        assertThat(runs).as("recordings in " + corpus).isNotEmpty();
        return runs.stream().map(run -> DynamicTest.dynamicTest(Path.of(corpus).relativize(run).toString(), () -> accepted(run)));
    }

    private void accepted(Path run) throws IOException {
        java.util.Map<String, String> front = frontMatter(run.resolve("README.md"));
        RealRecording.Sealed sealed = RealRecording.seal(run, temp.resolve(run.getFileName().toString()));
        OcsfReaderTool.Acceptance acceptance = sealed.acceptance();
        assertThat(acceptance.verdict()).as(acceptance.toString()).isEqualTo(front.getOrDefault("expected_verdict", "ACCEPTED"));
        if ("ACCEPTED".equals(acceptance.verdict())) {
            // an accepted archive exports: the reader and the canonical export agree on every core
            java.io.StringWriter canonical = new java.io.StringWriter();
            io.github.markpollack.journal.tck.ocsf.CanonicalExport.write(io.github.markpollack.journal.tck.ocsf.PackageReader.open(sealed.events()), canonical);
            String export = System.getProperty("journal.corpus.export"); // opt-in: keep each accepted run's canonical readback
            if (export != null) {
                Files.createDirectories(Path.of(export));
                Files.writeString(Path.of(export).resolve(run.getFileName() + ".canonical.jsonl"), canonical.toString());
            }
        }
        if (front.containsKey("expected_reason")) {
            assertThat(acceptance.reasons()).as(acceptance.toString()).anyMatch(r -> r.contains(front.get("expected_reason")));
        }
    }

    /** The README's front matter: each {@code key: "value"} line between the opening and closing {@code ---}. */
    private static java.util.Map<String, String> frontMatter(Path readme) throws IOException {
        java.util.Map<String, String> out = new java.util.LinkedHashMap<>();
        List<String> lines = Files.readAllLines(readme);
        for (int i = 1; i < lines.size() && !lines.get(i).equals("---"); i++) {
            int colon = lines.get(i).indexOf(": ");
            Jv value = colon < 0 ? null : Jv.parse(lines.get(i).substring(colon + 2));
            if (value instanceof Jv.Str str) { out.put(lines.get(i).substring(0, colon), str.value()); }
        }
        return out;
    }
}
