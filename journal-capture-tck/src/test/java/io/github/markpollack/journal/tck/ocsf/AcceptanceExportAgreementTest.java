package io.github.markpollack.journal.tck.ocsf;

import java.io.IOException;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The reader and the canonical export agree: every archive the reader ACCEPTS, among the TCK's sealed real recordings
 * ({@code ocsf/terminal-real}) and its fixture packages ({@code ocsf/fixtures}), also exports. A core the export refuses
 * (refused, or combined without a final assembly) is one acceptance must not accept either.
 */
class AcceptanceExportAgreementTest {

    private static List<Path> dirs(Path parent) throws IOException {
        try (Stream<Path> s = Files.list(parent)) {
            return s.filter(Files::isDirectory).sorted().toList();
        }
    }

    private static void exports(OcsfPackage pkg) throws IOException {
        CanonicalExport.write(pkg, new StringWriter());
    }

    @Test void everyAcceptedRealRecordingExports() throws IOException {
        List<String> accepted = new ArrayList<>();
        for (Path c : dirs(TerminalRealRecordingTest.CASES)) {
            String name = c.getFileName().toString();
            if (!"ACCEPTED".equals(TerminalRealRecordingTest.acceptance(name).verdict())) { continue; }
            exports(PackageReader.open(TerminalRealRecordingTest.root(name)));
            accepted.add(name);
        }
        assertThat(accepted).as("accepted real recordings, each exported").contains("claude-heartbeat", "claude-mcp", "claude-sub-agent");
    }

    @Test void everyAcceptedFixturePackageExports() throws IOException {
        int accepted = 0;
        for (Path fixture : dirs(Path.of("src/test/resources/ocsf/fixtures"))) {
            if (!Files.isRegularFile(OcsfFixtures.pkg(fixture).resolve("events.jsonl")) || !Files.isDirectory(OcsfFixtures.natives(fixture))) { continue; }
            if (!"ACCEPTED".equals(OcsfReaderTool.acceptance(OcsfFixtures.verify(fixture)).verdict())) { continue; }
            exports(OcsfFixtures.open(fixture));
            accepted++;
        }
        assertThat(accepted).as("accepted fixture packages, each exported").isPositive();
    }
}
