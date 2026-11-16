package io.github.markpollack.journal.ocsf.writer;

import io.github.markpollack.journal.tck.ocsf.AnalysisViews;
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

import static io.github.markpollack.journal.ocsf.writer.WriterTestSupport.LIVE_CLAUDE_PAIR;
import static io.github.markpollack.journal.ocsf.writer.WriterTestSupport.LIVE_FEATURES;
import static io.github.markpollack.journal.ocsf.writer.WriterTestSupport.PAIR_LOG;
import static io.github.markpollack.journal.ocsf.writer.WriterTestSupport.claudeStreamStdout;
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

    static final String RUN_SESSION = "00000000-0000-4000-8000-0000000000a4";
    static final String OTHER_SESSION = "11111111-1111-4111-8111-111111111111";

    @Test
    void thePlainWriterRecordsOneSourceAndRefusesASecond(@TempDir Path work) {
        Path core = work.resolve("core");
        try (OcsfRunWriter w = OcsfRunWriter.open(core, run(LIVE_FEATURES), SchemaPin.bundled(), options())) {
            Replay.feedBytes(w.source(claudeStreamStdout()), nativeOf(LIVE_FEATURES, "stream.jsonl"), 4096, options().clock());
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
        Files.copy(nativeOf(LIVE_CLAUDE_PAIR, "stream.jsonl"), nativeDir.resolve("stream.jsonl"));
        // the same session file bytes, except that every record's sessionId names another session
        String foreign = Files.readString(nativeOf(LIVE_CLAUDE_PAIR, "transcript.jsonl"), StandardCharsets.UTF_8).replace(RUN_SESSION, OTHER_SESSION);
        assertThat(foreign).contains(OTHER_SESSION).doesNotContain(RUN_SESSION);
        Files.writeString(nativeDir.resolve("transcript.jsonl"), foreign, StandardCharsets.UTF_8);
        Path core = work.resolve("core");
        try (OcsfRunWriter w = OcsfRunWriter.openEvidenceLog(core, run(LIVE_CLAUDE_PAIR), SchemaPin.bundled(), options(), PAIR_LOG)) {
            Replay.feedBytes(w.source(claudeStreamStdout()), nativeDir.resolve("stream.jsonl"), 4096, options().clock());
            Replay.feedBytes(w.source(claudeTranscript()), nativeDir.resolve("transcript.jsonl"), 4096, options().clock());
            assertThat(w.complete()).as("an evidence log records what it was given").isEqualTo(OcsfRunWriter.State.COMPLETED);
        }
        OcsfPackage pkg = WriterTestSupport.open(core);
        assertThat(pkg.combination()).isEqualTo(OcsfPackage.Combination.UNVALIDATED_EVIDENCE_LOG);
        Map<String, List<String>> sessions = sessionsByArtifact(pkg);
        assertThat(sessions.get("stream.jsonl")).containsOnly(RUN_SESSION);
        assertThat(sessions.get("transcript.jsonl")).as("another session's facts, recorded as evidence").contains(OTHER_SESSION);
        // the fixture's own frozen manifest, naming the substituted bytes: same length, another hash
        String frozen = Files.readString(nativeOf(LIVE_CLAUDE_PAIR, "expected-sources.json"), StandardCharsets.UTF_8)
                .replace(SchemaPin.sha256(Files.readAllBytes(nativeOf(LIVE_CLAUDE_PAIR, "transcript.jsonl"))), SchemaPin.sha256(foreign.getBytes(StandardCharsets.UTF_8)));
        Files.writeString(nativeDir.resolve("expected-sources.json"), frozen, StandardCharsets.UTF_8);
        OracleReport report = WriterTestSupport.verify(core, nativeDir);
        assertThat(report.defects()).as("each source's own observations still verify against its bytes").isEmpty();
        assertThat(report.outcome("combination.policy", "main")).isEqualTo(Check.Outcome.UNRESOLVED);
        assertThat(OcsfReaderTool.acceptance(report).verdict()).as("never accepted as one execution").isEqualTo("INCOMPLETE");
    }

    @Test
    void anAssemblyDeclarationCopiedIntoAnEvidenceLogIsNeverAnAcceptedExecution(@TempDir Path work) throws IOException {
        // review finding CDX-R1D-01, reproduced: the foreign-session evidence log above, its standalone event file (no
        // run.json) with a final-assembly declaration copied from the committed assembled pair appended, was read as
        // ASSEMBLED and accepted (combination.policy VERIFIED, exit 0) although no assembly check ran
        aSessionFileOfAnotherSessionInAnEvidenceLogIsNeverAcceptedAsOneExecution(Files.createDirectories(work.resolve("log")));
        String declaration = Files.readAllLines(WriterTestSupport.FIXTURES.resolve(WriterTestSupport.LIVE_CLAUDE_PAIR).resolve("package/events.jsonl"), StandardCharsets.UTF_8)
                .stream().filter(l -> l.contains("\"kind\":\"assembly\"")).findFirst().orElseThrow();
        List<String> log = Files.readAllLines(work.resolve("log/core/events.jsonl"), StandardCharsets.UTF_8);
        Path both = copiedWith(work.resolve("log/core"), work.resolve("both"), log, declaration);
        OcsfPackage contradictory = WriterTestSupport.open(both);
        assertThat(contradictory.combination()).isEqualTo(OcsfPackage.Combination.CONTRADICTORY);
        OracleReport report = WriterTestSupport.verify(both, work.resolve("log/native"));
        assertThat(report.outcome("combination.policy", "main")).isEqualTo(Check.Outcome.DEFECTIVE);
        assertThat(OcsfReaderTool.acceptance(report).verdict()).isNotEqualTo("ACCEPTED");
        // with the evidence-log declaration removed as well, the file reads as an assembly, which this per-source
        // verification still never accepts: only the assembly oracle judges a combination
        List<String> stripped = log.stream().map(l -> l.replaceFirst(",\"combination\":\\{\"status\":\"unvalidated evidence log\",\"reason\":\"[^\"]*\",\"meaning\":\"[^\"]*\"\\}", "")).toList();
        Path assembled = copiedWith(work.resolve("log/core"), work.resolve("assembled"), stripped, declaration);
        assertThat(WriterTestSupport.open(assembled).combination()).isEqualTo(OcsfPackage.Combination.ASSEMBLED);
        OracleReport claimed = WriterTestSupport.verify(assembled, work.resolve("log/native"));
        assertThat(claimed.outcome("combination.policy", "main")).isEqualTo(Check.Outcome.UNRESOLVED);
        assertThat(OcsfReaderTool.acceptance(claimed).verdict()).isEqualTo("INCOMPLETE");
    }

    @Test
    void aDeclaredFinalAssemblyIsJudgedOnlyByTheAssemblyOracle() {
        // the committed assembled pair: each source's observations verify, but the per-source verification does not run
        // the assembly checks, so it does not accept the file as a combined execution (FinalAssemblyClaudeTest runs them)
        Path fixture = WriterTestSupport.FIXTURES.resolve(WriterTestSupport.LIVE_CLAUDE_PAIR);
        OracleReport report = WriterTestSupport.verify(fixture.resolve("package"), fixture.resolve("native"));
        assertThat(report.outcome("combination.policy", "main")).isEqualTo(Check.Outcome.UNRESOLVED);
        assertThat(report.check("combination.policy", "main").detail()).contains("assembly oracle");
        assertThat(OcsfReaderTool.acceptance(report).reasons()).anySatisfy(r -> assertThat(r).contains("combination.policy"));
    }

    /** A standalone copy of a core's event file (child files kept, run.json left out) whose lines are {@code lines} plus {@code appended}. */
    private static Path copiedWith(Path core, Path out, List<String> lines, String appended) throws IOException {
        try (var walk = Files.walk(core)) {
            for (Path p : walk.toList()) {
                Path dest = out.resolve(core.relativize(p).toString());
                if (Files.isDirectory(p)) {
                    Files.createDirectories(dest);
                }
                else if (!p.equals(core.resolve("run.json")) && !p.equals(core.resolve("events.jsonl"))) {
                    Files.copy(p, dest);
                }
            }
        }
        List<String> all = new java.util.ArrayList<>(lines);
        all.add(appended);
        Files.write(out.resolve("events.jsonl"), all, StandardCharsets.UTF_8);
        return out;
    }

    @Test
    void everyCoreOfAnEvidenceLogDeclaresItselfUnvalidatedAndReadsAlone(@TempDir Path work) throws IOException {
        // the scrubbed real sub-agent run (Claude Code 2.1.316): its original SDK transport stream, whose Agent call splits
        // a child core, and the session file of the same session
        Path run = RealRecording.nativeDir(RealRecording.PUBLIC.resolve("sub-agent"));
        String agentCall = "toolu_01KQMhaSZMrxp98fqQBkmHCC";
        RunDescriptor spec = new RunDescriptor("real-sub-agent-2.1.316", 0, "1e60ebc7-85e6-4b7c-bd39-897d99229066", java.time.Instant.parse("2026-11-08T00:00:00Z"),
                new RunDescriptor.Cli("Claude Code", "2.1.316", "Anthropic"), null, null);
        Path core = work.resolve("core");
        try (OcsfRunWriter w = OcsfRunWriter.openEvidenceLog(core, spec, SchemaPin.bundled(), options(), PAIR_LOG)) {
            Replay.feedBytes(w.source(new SourceDescriptor("stream.jsonl", 1, "claude_stream", "sdk_stdio", "original SDK transport bytes of the real run", null)),
                    run.resolve("stdout.bin"), 4096, options().clock());
            Replay.feedBytes(w.source(claudeTranscript()), run.resolve("native").resolve("transcript.jsonl"), 4096, options().clock());
            w.complete();
        }
        OcsfPackage pkg = WriterTestSupport.open(core);
        assertThat(pkg.all()).as("the main core and the Agent call's child core").hasSize(2).allSatisfy(c -> {
            assertThat(c.declarations.run().path("combination", "status").text()).isEqualTo("unvalidated evidence log");
            assertThat(c.declarations.run().path("combination", "reason").text()).isEqualTo(PAIR_LOG);
        });
        long both = pkg.events.stream().filter(e -> agentCall.equals(e.text("tool_call_uid"))).map(Event::artifact).distinct().count();
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
        try (OcsfRunWriter w = OcsfRunWriter.openEvidenceLog(core, run(LIVE_CLAUDE_PAIR), SchemaPin.bundled(), options(), PAIR_LOG)) {
            Replay.feedBytes(w.source(claudeStreamStdout()), nativeOf(LIVE_CLAUDE_PAIR, "stream.jsonl"), 4096, options().clock());
            Replay.feedBytes(w.source(claudeTranscript()), nativeOf(LIVE_CLAUDE_PAIR, "transcript.jsonl"), 4096, options().clock());
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

    /**
     * The live pair's stream and session file recorded together as an evidence log: response identities are run-scoped
     * reported ids, so the logical count is the identity count, and a tool result both sources report is one owner observed
     * twice, kept apart from identical content under distinct owners.
     */
    @Test
    void anEvidenceLogCountsRunScopedIdentitiesLogicallyAndSeparatesRepeatsFromIdenticalContent(@TempDir Path work) {
        Path core = work.resolve("core");
        try (OcsfRunWriter w = OcsfRunWriter.openEvidenceLog(core, run(LIVE_CLAUDE_PAIR), SchemaPin.bundled(), options(), PAIR_LOG)) {
            Replay.feedBytes(w.source(claudeStreamStdout()), nativeOf(LIVE_CLAUDE_PAIR, "stream.jsonl"), 4096, options().clock());
            Replay.feedBytes(w.source(claudeTranscript()), nativeOf(LIVE_CLAUDE_PAIR, "transcript.jsonl"), 4096, options().clock());
            w.complete();
        }
        Json views = AnalysisViews.all(WriterTestSupport.open(core));
        Json responses = views.member("responses");
        assertThat(responses.member("identity_count").scalarText()).isEqualTo(responses.member("logical_count").scalarText());
        assertThat(responses.member("logical_count_basis").text()).startsWith("every identity is a run-scoped reported id");
        assertThat(responses.member("observation_count").scalarText()).as("6 stream + 6 session-file occurrences").isEqualTo("12");
        Json exchanges = views.member("tool_exchanges");
        List<Json> repeated = exchanges.member("repeated_result_observations").elements();
        assertThat(repeated).as("each of the four MCP results is reported by both sources").hasSize(4);
        assertThat(repeated.get(0).member("tool_call_uid").text()).isEqualTo("toolu_01DsWdj46hAwvjAwujRMJScN");
        assertThat(repeated.get(0).member("occurrences").elements()).extracting(Json::text).containsExactly("stream.jsonl:6", "transcript.jsonl:25");
        assertThat(repeated.get(0).member("content_equal")).as("the session file stores the result content the stream reported").isEqualTo(new Json.JBool(true));
        assertThat(repeated.get(0).member("counted_as").text()).isEqualTo("one owner observed 2 times; not 2 results");
        assertThat(exchanges.member("identical_content_distinct_owners").elements()).isEmpty();
        assertThat(views.path("associations", "operations", "observation_count").scalarText()).isEqualTo("0");
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
