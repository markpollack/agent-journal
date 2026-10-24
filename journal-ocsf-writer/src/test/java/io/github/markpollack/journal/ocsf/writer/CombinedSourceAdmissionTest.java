package io.github.markpollack.journal.ocsf.writer;

import io.github.markpollack.journal.tck.ocsf.Event;
import io.github.markpollack.journal.tck.ocsf.OcsfPackage;
import io.github.markpollack.journal.tck.ocsf.OracleReport;
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
import static io.github.markpollack.journal.ocsf.writer.WriterTestSupport.claudeStream;
import static io.github.markpollack.journal.ocsf.writer.WriterTestSupport.claudeTranscript;
import static io.github.markpollack.journal.ocsf.writer.WriterTestSupport.nativeOf;
import static io.github.markpollack.journal.ocsf.writer.WriterTestSupport.options;
import static io.github.markpollack.journal.ocsf.writer.WriterTestSupport.run;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * CHARACTERIZATION of the writer's experimental multi-source path (UC1-AC4 status: a saved session file admitted after
 * the live stream ended), recorded as evidence for a combined-source proposal. It pins what the path does today, not
 * target behavior: a session file is admitted without checking that it belongs to the run's session (and the oracle,
 * given a frozen manifest naming those bytes, reports no defect), an identical second import is recorded again under
 * another artifact name with no declaration marking it, and the same tool call observed by both sources becomes events
 * from each source with no declaration relating them. The derived event file does read on its own with every source
 * declared.
 * When a combined-source policy is adopted, the first two tests are expected to change.
 */
class CombinedSourceAdmissionTest {

    static final String RUN_SESSION = "00000000-0000-4000-8000-000000000000";
    static final String OTHER_SESSION = "11111111-1111-4111-8111-111111111111";
    static final String SHARED_CALL = "toolu_0183ddwvMmvPzqAU5Jeu5hwZ";

    @Test
    void aSessionFileOfAnotherSessionIsAdmittedWithoutAnIdentityCheck(@TempDir Path work) throws IOException {
        Path nativeDir = Files.createDirectories(work.resolve("native"));
        Files.copy(nativeOf(PAIR, "stream.jsonl"), nativeDir.resolve("stream.jsonl"));
        // the same session file bytes, except that every record's sessionId names another session
        String foreign = Files.readString(nativeOf(PAIR, "transcript.jsonl"), StandardCharsets.UTF_8).replace(RUN_SESSION, OTHER_SESSION);
        assertThat(foreign).contains(OTHER_SESSION).doesNotContain(RUN_SESSION);
        Files.writeString(nativeDir.resolve("transcript.jsonl"), foreign, StandardCharsets.UTF_8);
        Path core = work.resolve("core");
        try (OcsfRunWriter w = OcsfRunWriter.open(core, run(PAIR), SchemaPin.bundled(), options())) {
            Replay.feedBytes(w.source(claudeStream()), nativeDir.resolve("stream.jsonl"), 4096, options().clock());
            Replay.feedBytes(w.source(claudeTranscript()), nativeDir.resolve("transcript.jsonl"), 4096, options().clock());
            assertThat(w.complete()).as("admitted and completed").isEqualTo(OcsfRunWriter.State.COMPLETED);
        }
        OcsfPackage pkg = WriterTestSupport.open(core);
        Map<String, List<String>> sessions = sessionsByArtifact(pkg);
        assertThat(sessions.get("stream.jsonl")).containsOnly(RUN_SESSION);
        assertThat(sessions.get("transcript.jsonl")).as("another session's facts inside this run's event file").contains(OTHER_SESSION);
        // the fixture's own frozen manifest (with its child core), naming the substituted bytes: same length, another hash
        String frozen = Files.readString(nativeOf(PAIR, "expected-sources.json"), StandardCharsets.UTF_8)
                .replace(SchemaPin.sha256(Files.readAllBytes(nativeOf(PAIR, "transcript.jsonl"))), SchemaPin.sha256(foreign.getBytes(StandardCharsets.UTF_8)));
        Files.writeString(nativeDir.resolve("expected-sources.json"), frozen, StandardCharsets.UTF_8);
        OracleReport report = WriterTestSupport.verify(core, nativeDir);
        assertThat(report.defects()).as("today no check relates a declared session file to the run's session").isEmpty();
    }

    @Test
    void anIdenticalSecondImportIsRecordedAgainUnderAnotherArtifactName(@TempDir Path work) {
        Path core = work.resolve("core");
        try (OcsfRunWriter w = OcsfRunWriter.open(core, run(PAIR), SchemaPin.bundled(), options())) {
            Replay.feedBytes(w.source(claudeStream()), nativeOf(PAIR, "stream.jsonl"), 4096, options().clock());
            Replay.feedBytes(w.source(claudeTranscript()), nativeOf(PAIR, "transcript.jsonl"), 4096, options().clock());
            SourceDescriptor again = new SourceDescriptor("transcript-again.jsonl", 2, "claude_transcript", "native_session_file", "the same bytes imported a second time", null);
            Replay.feedBytes(w.source(again), nativeOf(PAIR, "transcript.jsonl"), 4096, options().clock());
            w.complete();
        }
        OcsfPackage pkg = WriterTestSupport.open(core);
        Map<String, Integer> callEvents = new TreeMap<>();
        for (Event e : pkg.events) {
            if (SHARED_CALL.equals(e.text("tool_call_uid"))) {
                callEvents.merge(e.artifact(), 1, Integer::sum);
            }
        }
        assertThat(callEvents.keySet()).as("one tool call, observed by the stream and twice by the same session bytes")
                .containsExactly("stream.jsonl", "transcript-again.jsonl", "transcript.jsonl");
        assertThat(callEvents.get("transcript-again.jsonl")).isEqualTo(callEvents.get("transcript.jsonl"));
        assertThat(pkg.manifest.sources().stream().map(s -> s.member("sha256").text()).distinct().count())
                .as("three declared sources, two of them with one sha256").isEqualTo(2);
        assertThat(declarationKinds(core)).as("the second import adds only its own source, compatibility and source-end declarations; nothing marks a duplicate")
                .containsExactlyInAnyOrderElementsOf(withAgain(declarationKinds(singleImport(work.resolve("single")))));
        assertThat(declarationKinds(core)).noneMatch(k -> k.matches("(?i).*(duplicate|relation|join).*"));
    }

    @Test
    void theDerivedEventFileReadsAloneWithEverySourceDeclared(@TempDir Path work) throws IOException {
        Path core = work.resolve("core");
        try (OcsfRunWriter w = OcsfRunWriter.open(core, run(PAIR), SchemaPin.bundled(), options())) {
            Replay.feedBytes(w.source(claudeStream()), nativeOf(PAIR, "stream.jsonl"), 4096, options().clock());
            Replay.feedBytes(w.source(claudeTranscript()), nativeOf(PAIR, "transcript.jsonl"), 4096, options().clock());
            w.complete();
        }
        Path alone = Files.createDirectories(work.resolve("copied"));
        Files.copy(core.resolve("events.jsonl"), alone.resolve("events.jsonl"));
        OcsfPackage pkg = WriterTestSupport.open(alone);
        assertThat(pkg.standalone()).isTrue();
        assertThat(pkg.refused()).isFalse();
        assertThat(pkg.manifest.sources()).extracting(s -> s.member("artifact").text() + " kind " + s.member("kind").scalarText())
                .containsExactly("stream.jsonl kind 1", "transcript.jsonl kind 2");
        Map<String, List<String>> sessions = sessionsByArtifact(pkg);
        assertThat(sessions).containsOnlyKeys("stream.jsonl", "transcript.jsonl");
        long both = pkg.events.stream().filter(e -> SHARED_CALL.equals(e.text("tool_call_uid"))).map(Event::artifact).distinct().count();
        assertThat(both).as("the shared call is readable from each source's own events").isEqualTo(2);
        assertThat(declarationKinds(alone)).as("no declaration relates the two sources' observations").noneMatch(k -> k.matches("(?i).*(relation|join).*"));
    }

    /** Declaration kinds without the per-file identity part, so packages written into different folders compare. */
    private static List<String> declarationKinds(Path core) {
        return WriterTestSupport.declarationKinds(core.resolve("events.jsonl")).stream().map(k -> k.split(" ")[0] + " " + k.split(" ")[1].replaceAll("transcript-again", "transcript")).toList();
    }

    /** The single-import declaration kinds plus the second import's own source, compatibility selection and source end. */
    private static List<String> withAgain(List<String> single) {
        List<String> out = new java.util.ArrayList<>(single);
        out.add("source source:transcript.jsonl");
        out.add("compatibility compatibility:transcript.jsonl#1");
        out.add("source_end source_end:transcript.jsonl");
        return out;
    }

    private static Path singleImport(Path core) {
        try (OcsfRunWriter w = OcsfRunWriter.open(core, run(PAIR), SchemaPin.bundled(), options())) {
            Replay.feedBytes(w.source(claudeStream()), nativeOf(PAIR, "stream.jsonl"), 4096, options().clock());
            Replay.feedBytes(w.source(claudeTranscript()), nativeOf(PAIR, "transcript.jsonl"), 4096, options().clock());
            w.complete();
        }
        return core;
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
