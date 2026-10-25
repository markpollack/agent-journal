package io.github.markpollack.journal.ocsf.writer;

import io.github.markpollack.journal.tck.ocsf.Event;
import io.github.markpollack.journal.tck.ocsf.OcsfPackage;
import io.github.markpollack.journal.tck.ocsf.OcsfReaderTool;
import io.github.markpollack.journal.tck.ocsf.OracleReport;
import io.github.markpollack.journal.tck.verify.Check;
import io.github.markpollack.journal.tck.verify.Json;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static io.github.markpollack.journal.ocsf.writer.WriterTestSupport.PAIR;
import static io.github.markpollack.journal.ocsf.writer.WriterTestSupport.PAIR_LOG;
import static io.github.markpollack.journal.ocsf.writer.WriterTestSupport.claudeStream;
import static io.github.markpollack.journal.ocsf.writer.WriterTestSupport.claudeTranscript;
import static io.github.markpollack.journal.ocsf.writer.WriterTestSupport.nativeOf;
import static io.github.markpollack.journal.ocsf.writer.WriterTestSupport.options;
import static io.github.markpollack.journal.ocsf.writer.WriterTestSupport.run;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The writer's combination policy (UC1-AC4; review finding CDX-R1-04). Before it, the plain writer admitted a second
 * source with no identity check: a session file of another session entered the run's file, an identical second import
 * was recorded again, and the oracle and the reader CLI accepted the result as one execution. Now the plain writer
 * records one source; several sources are combined only by the final assembly ({@code FinalAssemblyClaudeTest} refuses
 * the same foreign session file), or recorded as an explicitly unvalidated evidence log that the reader classifies and
 * the oracle refuses to accept as a final combined execution. A multi-source file written before the policy reads as
 * before and is classified the same way.
 */
class CombinedSourceAdmissionTest {

    static final String RUN_SESSION = "00000000-0000-4000-8000-000000000000";
    static final String OTHER_SESSION = "11111111-1111-4111-8111-111111111111";
    static final String SHARED_CALL = "toolu_0183ddwvMmvPzqAU5Jeu5hwZ";

    @Test
    void thePlainWriterRecordsOneSourceAndRefusesASecond(@TempDir Path work) {
        Path core = work.resolve("core");
        try (OcsfRunWriter w = OcsfRunWriter.open(core, run(PAIR), SchemaPin.bundled(), options())) {
            Replay.feedBytes(w.source(claudeStream()), nativeOf(PAIR, "stream.jsonl"), 4096, options().clock());
            assertThatThrownBy(() -> w.source(claudeTranscript())).isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("final assembly").hasMessageContaining("unvalidated evidence log");
            assertThat(w.complete()).isEqualTo(OcsfRunWriter.State.COMPLETED);
        }
        OcsfPackage pkg = WriterTestSupport.open(core);
        assertThat(pkg.combination()).isEqualTo(OcsfPackage.Combination.SINGLE_SOURCE);
        assertThat(pkg.manifest.sources()).hasSize(1);
    }

    @Test
    void aSessionFileOfAnotherSessionInAnEvidenceLogIsNeverAcceptedAsOneExecution(@TempDir Path work) throws IOException {
        Path nativeDir = Files.createDirectories(work.resolve("native"));
        Files.copy(nativeOf(PAIR, "stream.jsonl"), nativeDir.resolve("stream.jsonl"));
        // the same session file bytes, except that every record's sessionId names another session
        String foreign = Files.readString(nativeOf(PAIR, "transcript.jsonl"), StandardCharsets.UTF_8).replace(RUN_SESSION, OTHER_SESSION);
        assertThat(foreign).contains(OTHER_SESSION).doesNotContain(RUN_SESSION);
        Files.writeString(nativeDir.resolve("transcript.jsonl"), foreign, StandardCharsets.UTF_8);
        Path core = work.resolve("core");
        try (OcsfRunWriter w = OcsfRunWriter.openEvidenceLog(core, run(PAIR), SchemaPin.bundled(), options(), PAIR_LOG)) {
            Replay.feedBytes(w.source(claudeStream()), nativeDir.resolve("stream.jsonl"), 4096, options().clock());
            Replay.feedBytes(w.source(claudeTranscript()), nativeDir.resolve("transcript.jsonl"), 4096, options().clock());
            assertThat(w.complete()).as("an evidence log records what it was given").isEqualTo(OcsfRunWriter.State.COMPLETED);
        }
        OcsfPackage pkg = WriterTestSupport.open(core);
        assertThat(pkg.combination()).isEqualTo(OcsfPackage.Combination.UNVALIDATED_EVIDENCE_LOG);
        Map<String, List<String>> sessions = sessionsByArtifact(pkg);
        assertThat(sessions.get("stream.jsonl")).containsOnly(RUN_SESSION);
        assertThat(sessions.get("transcript.jsonl")).as("another session's facts, recorded as evidence").contains(OTHER_SESSION);
        // the fixture's own frozen manifest (with its child core), naming the substituted bytes: same length, another hash
        String frozen = Files.readString(nativeOf(PAIR, "expected-sources.json"), StandardCharsets.UTF_8)
                .replace(SchemaPin.sha256(Files.readAllBytes(nativeOf(PAIR, "transcript.jsonl"))), SchemaPin.sha256(foreign.getBytes(StandardCharsets.UTF_8)));
        Files.writeString(nativeDir.resolve("expected-sources.json"), frozen, StandardCharsets.UTF_8);
        OracleReport report = WriterTestSupport.verify(core, nativeDir);
        assertThat(report.defects()).as("each source's own observations still verify against its bytes").isEmpty();
        assertThat(report.outcome("combination.policy", "main")).isEqualTo(Check.Outcome.UNRESOLVED);
        assertThat(OcsfReaderTool.acceptance(report).verdict()).as("never accepted as one execution").isEqualTo("INCOMPLETE");
    }

    @Test
    void everyCoreOfAnEvidenceLogDeclaresItselfUnvalidatedAndReadsAlone(@TempDir Path work) throws IOException {
        Path core = work.resolve("core");
        try (OcsfRunWriter w = OcsfRunWriter.openEvidenceLog(core, run(PAIR), SchemaPin.bundled(), options(), PAIR_LOG)) {
            Replay.feedBytes(w.source(claudeStream()), nativeOf(PAIR, "stream.jsonl"), 4096, options().clock());
            Replay.feedBytes(w.source(claudeTranscript()), nativeOf(PAIR, "transcript.jsonl"), 4096, options().clock());
            w.complete();
        }
        OcsfPackage pkg = WriterTestSupport.open(core);
        assertThat(pkg.all()).hasSizeGreaterThan(1).allSatisfy(c -> {
            assertThat(c.declarations.run().path("combination", "status").text()).isEqualTo("unvalidated evidence log");
            assertThat(c.declarations.run().path("combination", "reason").text()).isEqualTo(PAIR_LOG);
        });
        long both = pkg.events.stream().filter(e -> SHARED_CALL.equals(e.text("tool_call_uid"))).map(Event::artifact).distinct().count();
        assertThat(both).as("an entity both sources report appears once per source: nothing consolidated it").isEqualTo(2);
        Path alone = Files.createDirectories(work.resolve("copied"));
        Files.copy(core.resolve("events.jsonl"), alone.resolve("events.jsonl"));
        OcsfPackage copied = WriterTestSupport.open(alone);
        assertThat(copied.standalone()).isTrue();
        assertThat(copied.refused()).isFalse();
        assertThat(copied.combination()).isEqualTo(OcsfPackage.Combination.UNVALIDATED_EVIDENCE_LOG);
        assertThat(copied.manifest.sources()).extracting(s -> s.member("artifact").text() + " kind " + s.member("kind").scalarText())
                .containsExactly("stream.jsonl kind 1", "transcript.jsonl kind 2");
    }

    @Test
    void aMultiSourceFileWrittenBeforeThePolicyReadsAsBeforeAndIsClassifiedUnvalidated(@TempDir Path work) throws IOException {
        Path core = work.resolve("core");
        try (OcsfRunWriter w = OcsfRunWriter.openEvidenceLog(core, run(PAIR), SchemaPin.bundled(), options(), PAIR_LOG)) {
            Replay.feedBytes(w.source(claudeStream()), nativeOf(PAIR, "stream.jsonl"), 4096, options().clock());
            Replay.feedBytes(w.source(claudeTranscript()), nativeOf(PAIR, "transcript.jsonl"), 4096, options().clock());
            w.complete();
        }
        // the plain writer's output before the policy: the same file without the combination member
        Path historical = Files.createDirectories(work.resolve("historical"));
        List<String> lines = Files.readAllLines(core.resolve("events.jsonl"), StandardCharsets.UTF_8).stream()
                .map(l -> l.replaceFirst(",\"combination\":\\{\"status\":\"unvalidated evidence log\",\"reason\":\"[^\"]*\",\"meaning\":\"[^\"]*\"\\}", "")).toList();
        assertThat(String.join("\n", lines)).doesNotContain("\"combination\"");
        Files.write(historical.resolve("events.jsonl"), lines, StandardCharsets.UTF_8);
        OcsfPackage pkg = WriterTestSupport.open(historical);
        assertThat(pkg.standalone()).isTrue();
        assertThat(pkg.refused()).isFalse();
        assertThat(pkg.combination()).isEqualTo(OcsfPackage.Combination.UNVALIDATED_UNDECLARED);
        assertThat(sessionsByArtifact(pkg)).containsOnlyKeys("stream.jsonl", "transcript.jsonl");
    }

    private static Map<String, List<String>> sessionsByArtifact(OcsfPackage pkg) {
        Map<String, List<String>> out = new TreeMap<>();
        for (Event e : pkg.events) {
            Json s = e.get("session_uid");
            if (s != null && !s.isNull()) {
                out.computeIfAbsent(e.artifact(), k -> new java.util.ArrayList<>()).add(s.text());
            }
        }
        return out;
    }
}
