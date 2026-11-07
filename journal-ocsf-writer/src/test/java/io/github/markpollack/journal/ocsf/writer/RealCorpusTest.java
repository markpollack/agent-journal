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
 * offline through this writer and read by the independent reader, must be accepted. Each run's sealed Client manifest is
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
        OcsfReaderTool.Acceptance acceptance = RealRecording.seal(run, temp.resolve(run.getFileName().toString())).acceptance();
        assertThat(acceptance.verdict()).as(acceptance.toString()).isEqualTo("ACCEPTED");
    }
}
