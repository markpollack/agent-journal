package io.github.markpollack.journal.tck.ocsf;

import io.github.markpollack.journal.tck.ocsf.inventory.AcquisitionVerification;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * A Junie ACP one-shot archive read with the TCK alone: {@code ocsf/junie-acp-3813.7-session-files/archive} was delivered by
 * {@code TerminalReceiver} under {@value JunieAcpFamily#TERMINAL_PROFILE} from the real sanitized junie-2 session files and
 * their labelled stand-in manifest (regenerate with {@code -Djunie.acp.archive.regenerate=<dir>} on
 * {@code TerminalJunieProfileTest} in journal-ocsf-writer). The assembly oracle finds no defect and nothing unresolved, so the
 * archive is ACCEPTED; the per-call {@code time} member is retained as recorded (Owner decision
 * OWNER-2026-11-16-JUNIE-MODELUSAGE-TIME), with no duration derived.
 */
class TerminalJunieArchiveTest {
    static final Path CASE = Path.of("src/test/resources/ocsf/junie-acp-3813.7-session-files/archive");
    @TempDir Path temp;

    private static Path events(Path store) throws IOException {
        try (Stream<Path> files = Files.walk(store.resolve("prepared"))) {
            return files.filter(p -> p.getFileName().toString().equals("events.jsonl") && !p.getParent().getFileName().toString().equals("sources")).findFirst().orElseThrow();
        }
    }

    @Test void theArchiveReadsAloneAndTheOracleFindsNoDefect() throws IOException {
        Path events = events(CASE.resolve("store"));
        OcsfPackage pkg = PackageReader.open(events);
        assertThat(pkg.diagnostics).as(pkg.diagnostics.toString()).noneMatch(d -> d.severity() == Diagnostic.Severity.DEFECT || d.severity() == Diagnostic.Severity.REFUSED);
        TerminalBindingReader.Binding binding = TerminalBindingReader.read(pkg.declarations);
        assertThat(binding.producerNamespace()).isEqualTo("junie-example");
        assertThat(binding.decision()).contains("\"executionId\":\"E\"").contains("\"connectionId\":\"G\"");
        OracleReport report = new AcquisitionVerification().verify(pkg, events.getParent().resolve("sources"), events.getParent().resolve("sources/acquisition.json"),
                CASE.resolve("assembly-expectation.json"));
        assertThat(report.defects()).as(report.toString()).isEmpty();
        OcsfReaderTool.Acceptance acceptance = OcsfReaderTool.acceptance(report);
        assertThat(acceptance.verdict()).isEqualTo("ACCEPTED");
        assertThat(acceptance.reasons()).isEmpty();
        assertThat(Files.readString(events)).contains("\\\"profile\\\":\\\"client-terminal-junie-acp/1\\\"").contains("\"product\":{\"name\":\"Junie\",\"version\":\"3813.7\"");
    }

    /**
     * Agent Client's sealed capture of a fresh live Junie 3813.7 ACP session ({@code ocsf/junie-acp-3813.7-live}, sanitized,
     * delivered the same way): its main agent's result block is reported twice in the session file, in progress and at
     * completion, so the singleton final answer and final accounting repeat within one source. Both sides keep the repeated
     * observations apart, each flagged ambiguous under the one identity they repeat, and the oracle finds no defect.
     */
    @Test void theLiveAcpCaptureWithARepeatedResultBlockReadsWithNoDefect() throws IOException {
        Path live = Path.of("src/test/resources/ocsf/junie-acp-3813.7-live/archive");
        Path events = events(live.resolve("store"));
        OcsfPackage pkg = PackageReader.open(events);
        assertThat(pkg.diagnostics).as(pkg.diagnostics.toString()).noneMatch(d -> d.severity() == Diagnostic.Severity.DEFECT || d.severity() == Diagnostic.Severity.REFUSED);
        OracleReport report = new AcquisitionVerification().verify(pkg, events.getParent().resolve("sources"), events.getParent().resolve("sources/acquisition.json"),
                live.resolve("assembly-expectation.json"));
        assertThat(report.defects()).as(report.toString()).isEmpty();
        assertThat(report.checks()).filteredOn(c -> c.property().equals("assembly.partition.association")).isNotEmpty()
                .allSatisfy(c -> assertThat(c.outcome()).isEqualTo(io.github.markpollack.journal.tck.verify.Check.Outcome.VERIFIED));
        OcsfReaderTool.Acceptance acceptance = OcsfReaderTool.acceptance(report);
        assertThat(acceptance.verdict()).isEqualTo("ACCEPTED");
        assertThat(acceptance.reasons()).isEmpty();
        List<Event> answers = pkg.events("model_response", 3);
        assertThat(answers).hasSize(2).extracting(Event::recordIndex).containsExactly(63, 69);
        assertThat(answers).allSatisfy(e -> {
            assertThat(e.assemblyRole()).isEqualTo(1);
            assertThat(e.integer("assembly", "relation_class_id")).isEqualTo(6);
            assertThat(e.text("assembly", "entity_uid")).isEqualTo("model_response/3/junie.execution_singleton");
        });
    }

    @Test void anArchivedManifestThatDiffersFromItsDeclarationIsADefect() throws IOException {
        Path store = copy(CASE.resolve("store"), temp.resolve("store"));
        Path manifest = events(store).getParent().resolve("sources/acquisition.json");
        Files.writeString(manifest, Files.readString(manifest).replace("\"acp_stop_reason\":\"end_turn\"", "\"acp_stop_reason\":\"cancelled\""));
        OcsfPackage pkg = PackageReader.open(events(store));
        assertThat(pkg.diagnostics).anyMatch(d -> d.code().equals("TERMINAL_JUNIE_INVALID") && d.detail().contains("differs from manifest original"));
    }

    @Test void archivedOriginalsThatNoLongerQualifyAreADefect() throws IOException {
        Path store = copy(CASE.resolve("store"), temp.resolve("store"));
        Path summary = events(store).getParent().resolve("sources/summary.json");
        Files.writeString(summary, Files.readString(summary).replace("COMPLETED", "COMPLETEX"));
        OcsfPackage pkg = PackageReader.open(events(store));
        assertThat(pkg.diagnostics).anyMatch(d -> d.code().equals("TERMINAL_JUNIE_INVALID") && d.detail().contains("changed bytes: summary.json"));
    }

    @Test void detachedOriginalsAreReportedUnavailableNotInvalid() throws IOException {
        Path store = copy(CASE.resolve("store"), temp.resolve("store"));
        Files.delete(events(store).getParent().resolve("sources/acquisition.json"));
        OcsfPackage pkg = PackageReader.open(events(store));
        assertThat(pkg.diagnostics).noneMatch(d -> d.code().equals("TERMINAL_JUNIE_INVALID")).anyMatch(d -> d.code().equals("TERMINAL_SOURCE_UNAVAILABLE"));
    }

    private static Path copy(Path from, Path to) throws IOException {
        try (Stream<Path> walk = Files.walk(from)) {
            for (Path p : walk.toList()) {
                Path t = to.resolve(from.relativize(p).toString());
                if (Files.isDirectory(p)) Files.createDirectories(t); else Files.copy(p, t);
            }
        }
        return to;
    }
}
