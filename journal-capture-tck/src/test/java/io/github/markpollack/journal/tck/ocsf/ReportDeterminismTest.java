package io.github.markpollack.journal.tck.ocsf;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

/**
 * The verify report of one archive is the same bytes in every JVM: the retained TX Bench 2.1.319 recording verified in
 * separate JVMs gives one report. Map.of iteration order is salted per JVM, so an expected value built from Map.of is
 * written in a different member order from run to run.
 */
class ReportDeterminismTest {
    @TempDir Path temp;

    @Test void verifyingOneArchiveInSeparateJvmsWritesOneReport() throws Exception {
        Path events = TerminalRealRecordingTest.root("claude-txbench-2.1.319");
        Path sources = events.getParent().resolve("sources");
        Path expectation = TerminalRealRecordingTest.CASES.resolve("claude-txbench-2.1.319/assembly-expectation.json");
        Set<String> reports = new HashSet<>();
        for (int i = 0; i < 8; i++) {
            Path report = temp.resolve("report-" + i + ".json");
            List<String> cmd = new ArrayList<>(List.of(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                    "-cp", System.getProperty("java.class.path"), OcsfReaderTool.class.getName(), "verify", events.toString(), sources.toString(),
                    report.toString(), "--acquisition", sources.resolve("acquisition.json").toString(), "--assembly", expectation.toString()));
            Process p = new ProcessBuilder(cmd).redirectErrorStream(true).redirectOutput(temp.resolve("out-" + i).toFile()).start();
            assertThat(p.waitFor()).as(Files.readString(temp.resolve("out-" + i))).isZero();
            reports.add(Files.readString(report));
        }
        assertThat(reports).as("distinct report texts across 8 JVMs").hasSize(1);
    }
}
