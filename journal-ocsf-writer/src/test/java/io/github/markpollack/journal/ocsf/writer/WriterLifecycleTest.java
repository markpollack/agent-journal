package io.github.markpollack.journal.ocsf.writer;

import io.github.markpollack.journal.tck.ocsf.AnalysisViews;
import io.github.markpollack.journal.tck.ocsf.Diagnostic;
import io.github.markpollack.journal.tck.ocsf.OcsfPackage;
import io.github.markpollack.journal.tck.ocsf.OracleReport;
import io.github.markpollack.journal.tck.verify.Check;
import io.github.markpollack.journal.tck.verify.Json;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static io.github.markpollack.journal.ocsf.writer.WriterTestSupport.CHILD;
import static io.github.markpollack.journal.ocsf.writer.WriterTestSupport.PAIR;
import static io.github.markpollack.journal.ocsf.writer.WriterTestSupport.SPAWN;
import static io.github.markpollack.journal.ocsf.writer.WriterTestSupport.claudeStream;
import static io.github.markpollack.journal.ocsf.writer.WriterTestSupport.claudeTranscript;
import static io.github.markpollack.journal.ocsf.writer.WriterTestSupport.manifest;
import static io.github.markpollack.journal.ocsf.writer.WriterTestSupport.nativeDir;
import static io.github.markpollack.journal.ocsf.writer.WriterTestSupport.nativeOf;
import static io.github.markpollack.journal.ocsf.writer.WriterTestSupport.notVerified;
import static io.github.markpollack.journal.ocsf.writer.WriterTestSupport.open;
import static io.github.markpollack.journal.ocsf.writer.WriterTestSupport.options;
import static io.github.markpollack.journal.ocsf.writer.WriterTestSupport.run;
import static io.github.markpollack.journal.ocsf.writer.WriterTestSupport.verify;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The incremental writer's lifecycle, judged from the files it leaves on disk by the independent reader and oracle:
 * persisted prefixes readable before EOF, honest incomplete/interrupted/failed states, idempotent finalization,
 * later session evidence appended without changing earlier answers, child routing, and the capture-facing record
 * form producing the same bytes as chunked replay.
 */
class WriterLifecycleTest {

    private static final int STREAM_RECORDS = 27;

    @Test
    void persistedPrefixIsReadableAndQueryableBeforeEof(@TempDir Path work) {
        Path core = work.resolve("core");
        Path stream = nativeOf(PAIR, "stream.jsonl");
        try (OcsfRunWriter w = OcsfRunWriter.open(core, run(PAIR), SchemaPin.bundled(), options())) {
            SourceInput in = w.source(claudeStream());
            byte[] prefix = Replay.prefixBytes(stream, 6);
            Replay.feedBytes(in, prefix, 7, options().clock(), prefix.length, false);
            w.flush();
            assertThat(w.state()).isEqualTo(OcsfRunWriter.State.RECORDING);

            // another reader, from the files alone, while the writer is still open
            OcsfPackage p = open(core);
            assertThat(p.refused()).isFalse();
            assertThat(p.diagnostics(Diagnostic.Severity.DEFECT)).isEmpty();
            assertThat(p.diagnosticsWithCode("PACKAGE_OPEN")).hasSize(1);
            assertThat(p.manifest.packageState()).isEqualTo("RECORDING");
            assertThat(p.manifest.eventsSha256()).isNull();
            assertThat(p.events).isNotEmpty();
            Json views = AnalysisViews.all(p);
            // record 5 requested ToolSearch, record 6 returned its result: one exchange, request and result present
            Json calls = views.path("tool_exchanges", "calls");
            assertThat(calls.elements()).hasSize(1);
            assertThat(calls.elements().get(0).member("tool_call_uid").text()).isEqualTo("toolu_0183ddwvMmvPzqAU5Jeu5hwZ");
            assertThat(calls.elements().get(0).member("requests").elements()).hasSize(1);
            assertThat(calls.elements().get(0).member("results").elements()).hasSize(1);
            assertThat(views.path("settings_context", "settings_rows").scalarText()).isEqualTo("1");

            // the oracle judges the open package against exactly the prefix the writer has seen
            OracleReport report = verify(core, nativeDir(work, PAIR, "stream.jsonl", 6));
            assertThat(report.defects()).as(notVerified(report).toString()).isEmpty();
            assertThat(report.outcome("package.state", "main")).isEqualTo(Check.Outcome.UNRESOLVED);
            assertThat(report.outcome("source.inventory", "main/stream.jsonl")).isEqualTo(Check.Outcome.VERIFIED);
            assertThat(report.outcome("population.requests", "main/stream.jsonl")).isEqualTo(Check.Outcome.VERIFIED);

            // then the rest arrives; the earlier bytes are untouched
            byte[] before = WriterTestSupport.bytes(core.resolve("events.jsonl"));
            byte[] all = WriterTestSupport.bytes(stream);
            in.bytes(all, prefix.length, all.length - prefix.length, options().clock().instant());
            in.end(SourceInput.End.EOF, options().clock().instant());
            assertThat(w.complete()).isEqualTo(OcsfRunWriter.State.COMPLETED);
            byte[] after = WriterTestSupport.bytes(core.resolve("events.jsonl"));
            assertThat(Arrays.copyOf(after, before.length)).isEqualTo(before);
        }
        OracleReport finalReport = verify(core, nativeDir(work, PAIR, "stream.jsonl", -1));
        assertThat(finalReport.defects()).as(notVerified(finalReport).toString()).isEmpty();
        assertThat(finalReport.outcome("package.state", "main")).isEqualTo(Check.Outcome.VERIFIED);
    }

    @Test
    void eofInsideARecordRetainsTheIncompleteTailHonestly(@TempDir Path work) {
        Path core = work.resolve("core");
        Path stream = nativeOf(PAIR, "stream.jsonl");
        byte[] all = WriterTestSupport.bytes(stream);
        int cut = Replay.prefixBytes(stream, 6).length + 40; // 40 bytes into record 7
        try (OcsfRunWriter w = OcsfRunWriter.open(core, run(PAIR), SchemaPin.bundled(), options())) {
            SourceInput in = w.source(claudeStream());
            Replay.feedBytes(in, all, 11, options().clock(), cut, true);
            assertThat(in.records()).isEqualTo(7);
            assertThat(w.complete()).isEqualTo(OcsfRunWriter.State.COMPLETED);
        }
        Jv m = manifest(core);
        Jv source = m.member("sources").elements().get(0);
        assertThat(((Jv.Num) source.member("incomplete_tail_record")).value().intValue()).isEqualTo(7);
        assertThat(source.member("state").text()).isEqualTo("ENDED");
        assertThat(m.path("recording", "acquisition", "status").text()).isEqualTo("incomplete");
        assertThat(Jv.write(m.path("recording", "acquisition", "detail"))).contains("record 7 is incomplete at source end");
        // the cut bytes are retained verbatim as an Opaque source_record with parse status 5
        List<String> lines = WriterTestSupport.lines(core.resolve("events.jsonl"));
        Jv last = Jv.parse(lines.get(lines.size() - 1));
        assertThat(last.member("class_name").text()).isEqualTo("Source Record");
        assertThat(((Jv.Num) last.member("activity_id")).value().intValue()).isEqualTo(2);
        assertThat(((Jv.Num) last.member("parse_status_id")).value().intValue()).isEqualTo(5);
        assertThat(last.path("source_ref", "line_ending").text()).isEqualTo("none");
        byte[] expectedTail = Arrays.copyOfRange(all, cut - 40, cut);
        assertThat(last.member("raw_data").text()).isEqualTo(new String(expectedTail, StandardCharsets.UTF_8));
        // the independent oracle, given the same cut bytes, finds the incomplete record retained byte-exact
        Path nat = work.resolve("native");
        try {
            Files.createDirectories(nat);
            Files.write(nat.resolve("stream.jsonl"), Arrays.copyOf(all, cut));
        }
        catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        OracleReport report = verify(core, nat);
        assertThat(report.defects()).as(notVerified(report).toString()).isEmpty();
        assertThat(report.outcome("whole_record.bytes", "main/stream.jsonl")).isEqualTo(Check.Outcome.VERIFIED);
    }

    @Test
    void interruptionEndsOpenSourcesAndFinalizesWhatWasAcquired(@TempDir Path work) {
        Path core = work.resolve("core");
        Path stream = nativeOf(PAIR, "stream.jsonl");
        OcsfRunWriter w = OcsfRunWriter.open(core, run(PAIR), SchemaPin.bundled(), options());
        SourceInput in = w.source(claudeStream());
        byte[] prefix = Replay.prefixBytes(stream, 10);
        Replay.feedBytes(in, prefix, 1000, options().clock(), prefix.length, false);
        assertThat(w.interrupt("operator cancelled")).isEqualTo(OcsfRunWriter.State.INTERRUPTED);
        assertThat(w.interrupt("again")).isEqualTo(OcsfRunWriter.State.INTERRUPTED);
        assertThat(w.complete()).as("complete after interrupt is a no-op").isEqualTo(OcsfRunWriter.State.INTERRUPTED);
        Jv m = manifest(core);
        assertThat(m.path("package", "state").text()).isEqualTo("INTERRUPTED");
        assertThat(m.path("recording", "interruption_reason").text()).isEqualTo("operator cancelled");
        Jv source = m.member("sources").elements().get(0);
        assertThat(source.member("state").text()).isEqualTo("INTERRUPTED");
        assertThat(source.path("ended", "how").text()).isEqualTo("INTERRUPTED");
        assertThat(source.member("sha256").text()).isEqualTo(SchemaPin.sha256(prefix));
        assertThat(m.path("package", "events_jsonl_sha256").text()).isEqualTo(SchemaPin.sha256(WriterTestSupport.bytes(core.resolve("events.jsonl"))));
        OcsfPackage p = open(core);
        assertThat(p.diagnosticsWithCode("PACKAGE_INTERRUPTED")).hasSize(1);
        assertThat(p.diagnostics(Diagnostic.Severity.DEFECT)).isEmpty();
        OracleReport report = verify(core, nativeDir(work, PAIR, "stream.jsonl", 10));
        assertThat(report.defects()).as(notVerified(report).toString()).isEmpty();
        assertThat(report.outcome("package.state", "main")).isEqualTo(Check.Outcome.UNSUPPORTED);
    }

    @Test
    void injectedWriteFailureLeavesAnHonestFailedPackage(@TempDir Path work) {
        Path core = work.resolve("core");
        Path stream = nativeOf(PAIR, "stream.jsonl");
        AtomicInteger appends = new AtomicInteger();
        WriterOptions opts = options().withFaults((operation, detail) -> {
            if ("append".equals(operation) && appends.incrementAndGet() == 2) {
                throw new IOException("injected: disk full during " + detail);
            }
        });
        OcsfRunWriter w = OcsfRunWriter.open(core, run(PAIR), SchemaPin.bundled(), opts);
        SourceInput in = w.source(claudeStream());
        byte[] all = WriterTestSupport.bytes(stream);
        byte[] first = Replay.prefixBytes(stream, 5);
        Replay.feedBytes(in, first, 1000, opts.clock(), first.length, false);
        w.flush(); // first append succeeds
        int confirmed = open(core).events.size();
        assertThat(confirmed).isPositive();
        in.bytes(all, first.length, all.length - first.length, opts.clock().instant());
        assertThatThrownBy(w::flush).isInstanceOf(UncheckedIOException.class).hasMessageContaining("injected: disk full");
        assertThat(w.state()).isEqualTo(OcsfRunWriter.State.FAILED);
        assertThatThrownBy(() -> in.bytes(new byte[] {'{'}, opts.clock().instant())).isInstanceOf(CoreWriter.WriterFailedException.class);
        assertThat(w.complete()).isEqualTo(OcsfRunWriter.State.FAILED);
        w.close();
        Jv m = manifest(core);
        assertThat(m.path("package", "state").text()).isEqualTo("FAILED");
        assertThat(m.path("package", "events_jsonl_sha256").isNull()).isTrue();
        assertThat(((Jv.Num) m.path("recording", "failure", "events_confirmed_persisted")).value().intValue()).isEqualTo(confirmed);
        assertThat(((Jv.Num) m.path("recording", "failure", "events_not_confirmed")).value().intValue()).isPositive();
        assertThat(m.path("recording", "failure", "detail").text()).contains("injected: disk full");
        assertThat(m.path("recording", "acquisition", "status").text()).isEqualTo("incomplete");
        OcsfPackage p = open(core);
        assertThat(p.diagnosticsWithCode("PACKAGE_FAILED")).hasSize(1);
        assertThat(p.events.size()).isEqualTo(confirmed);
        OracleReport report = verify(core, nativeDir(work, PAIR, "stream.jsonl", 5));
        assertThat(report.outcome("package.state", "main")).isEqualTo(Check.Outcome.UNRESOLVED);
    }

    @Test
    void finalizationIsIdempotentAndCloseAfterCompletionChangesNothing(@TempDir Path work) {
        Path core = work.resolve("core");
        OcsfRunWriter w = OcsfRunWriter.open(core, run(PAIR), SchemaPin.bundled(), options());
        Replay.feedBytes(w.source(claudeStream()), nativeOf(PAIR, "stream.jsonl"), 4096, options().clock());
        assertThat(w.complete()).isEqualTo(OcsfRunWriter.State.COMPLETED);
        byte[] manifest1 = WriterTestSupport.bytes(core.resolve("run.json"));
        byte[] events1 = WriterTestSupport.bytes(core.resolve("events.jsonl"));
        assertThat(w.complete()).isEqualTo(OcsfRunWriter.State.COMPLETED);
        assertThat(w.interrupt("late")).isEqualTo(OcsfRunWriter.State.COMPLETED);
        w.close();
        w.flush();
        assertThat(WriterTestSupport.bytes(core.resolve("run.json"))).isEqualTo(manifest1);
        assertThat(WriterTestSupport.bytes(core.resolve("events.jsonl"))).isEqualTo(events1);
        assertThatThrownBy(() -> w.source(claudeTranscript())).isInstanceOf(IllegalStateException.class);
        Jv m = manifest(core);
        assertThat(m.path("package", "events_jsonl_sha256").text()).isEqualTo(SchemaPin.sha256(events1));
        assertThat(((Jv.Num) m.path("package", "events_count")).value().intValue()).isEqualTo(WriterTestSupport.lines(core.resolve("events.jsonl")).size());
    }

    @Test
    void completeRequiresEveryDeclaredSourceToHaveEndedAndInputsCannotBeMixed(@TempDir Path work) {
        Path core = work.resolve("core");
        try (OcsfRunWriter w = OcsfRunWriter.open(core, run(PAIR), SchemaPin.bundled(), options())) {
            SourceInput in = w.source(claudeStream());
            in.record("{\"type\":\"system\",\"subtype\":\"init\"}".getBytes(StandardCharsets.UTF_8), "LF", options().clock().instant());
            assertThatThrownBy(() -> in.bytes(new byte[] {'{', '}', '\n'}, options().clock().instant())).isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(w::complete).isInstanceOf(IllegalStateException.class).hasMessageContaining("stream.jsonl has not ended");
            assertThatThrownBy(() -> w.source(claudeStream())).isInstanceOf(IllegalArgumentException.class);
        }
        // try-with-resources closed it without complete(): that is an interruption, recorded as such
        assertThat(manifest(core).path("package", "state").text()).isEqualTo("INTERRUPTED");
        assertThat(manifest(core).path("recording", "interruption_reason").text()).contains("closed without complete()");
    }

    @Test
    void childRecordsAreRoutedToTheirOwnCoreAsTheyArrive(@TempDir Path work) {
        Path core = work.resolve("core");
        try (OcsfRunWriter w = OcsfRunWriter.open(core, run(CHILD), SchemaPin.bundled(), options())) {
            Replay.feedBytes(w.source(claudeStream()), nativeOf(CHILD, "stream.jsonl"), 512, options().clock());
            w.complete();
        }
        Path child = core.resolve("children").resolve(SPAWN);
        assertThat(child.resolve("events.jsonl")).exists();
        Jv parent = manifest(core);
        assertThat(parent.path("run", "children").elements()).hasSize(1);
        assertThat(parent.path("run", "children").elements().get(0).member("spawn_tool_call_uid").text()).isEqualTo(SPAWN);
        assertThat(parent.path("run", "source_scope", "owner_value").isNull()).isTrue();
        Jv childManifest = manifest(child);
        assertThat(childManifest.path("run", "parent", "run_uid").text()).isEqualTo(run(CHILD).runUid());
        assertThat(childManifest.path("run", "source_scope", "owner_value").text()).isEqualTo(SPAWN);
        assertThat(childManifest.path("package", "state").text()).isEqualTo("COMPLETED");
        // the child owns 3 records (18 user text, 20 assistant tool_use, 21 user tool_result): 4 events (the tool_use yields response + request)
        assertThat(WriterTestSupport.lines(child.resolve("events.jsonl"))).hasSize(4);
        OracleReport report = verify(core, nativeDir(work, CHILD, "stream.jsonl", -1));
        assertThat(report.defects()).as(notVerified(report).toString()).isEmpty();
        assertThat(report.outcome("whole_record.coverage", "child:" + SPAWN + "/stream.jsonl")).isEqualTo(Check.Outcome.VERIFIED);
        assertThat(report.outcome("whole_record.coverage", "main/stream.jsonl")).isEqualTo(Check.Outcome.VERIFIED);
    }

    @Test
    void laterSessionEvidenceIsAppendedWithoutChangingEarlierPrefixAnswers(@TempDir Path work) {
        Path core = work.resolve("core");
        String response = "msg_011Cg2aRQ1iZf8BxQVUnD2jR";
        try (OcsfRunWriter w = OcsfRunWriter.open(core, run(PAIR), SchemaPin.bundled(), options())) {
            Replay.feedBytes(w.source(claudeStream()), nativeOf(PAIR, "stream.jsonl"), 4096, options().clock());
            w.flush();
            byte[] streamOnly = WriterTestSupport.bytes(core.resolve("events.jsonl"));
            Json before = AnalysisViews.usage(open(core));
            Json streamGroup = group(before, "Model response", response, "stream.jsonl");
            assertThat(streamGroup).as("the live stream reports only provisional usage for " + response).isNotNull();
            assertThat(streamGroup.member("applicable_final").isNull()).isTrue();
            assertThat(group(before, "Model response", response, "transcript.jsonl")).as("no transcript fact exists in the stream-only prefix").isNull();
            assertThat(group(before, "Session", run(PAIR).sessionUid(), "transcript.jsonl")).isNull();

            // the saved session file is admitted later through the same writer
            Replay.feedBytes(w.source(claudeTranscript()), nativeOf(PAIR, "transcript.jsonl"), 4096, options().clock());
            w.complete();
            byte[] all = WriterTestSupport.bytes(core.resolve("events.jsonl"));
            assertThat(Arrays.copyOf(all, streamOnly.length)).as("the earlier persisted bytes are unchanged by the later admission").isEqualTo(streamOnly);
        }
        Json after = AnalysisViews.usage(open(core));
        Json streamGroupAfter = group(after, "Model response", response, "stream.jsonl");
        assertThat(streamGroupAfter.member("applicable_final").isNull()).as("the stream group still has no final").isTrue();
        Json transcriptGroup = group(after, "Model response", response, "transcript.jsonl");
        assertThat(transcriptGroup).isNotNull();
        assertThat(transcriptGroup.path("applicable_final", "output_tokens").scalarText()).isEqualTo("58");
        assertThat(transcriptGroup.member("final_observations").scalarText()).isEqualTo("2");
        assertThat(transcriptGroup.member("repeated_final_observations").scalarText()).isEqualTo("1");
        Json session = group(after, "Session", run(PAIR).sessionUid(), "transcript.jsonl");
        assertThat(session).as("cost-state session accounting from the transcript").isNotNull();
        assertThat(session.path("applicable_final", "output_tokens").scalarText()).isEqualTo("1362");
        Path nat = nativeDir(work, PAIR, "stream.jsonl", -1);
        nativeDir(work, PAIR, "transcript.jsonl", -1);
        OracleReport report = verify(core, nat);
        assertThat(report.defects()).as(notVerified(report).toString()).isEmpty();
        assertThat(report.outcome("population.contexts", "main/transcript.jsonl")).isEqualTo(Check.Outcome.VERIFIED);
        assertThat(report.outcome("cost.values", "main/transcript.jsonl")).isEqualTo(Check.Outcome.VERIFIED);
        assertThat(report.outcome("whole_record.coverage", "main/transcript.jsonl")).isEqualTo(Check.Outcome.VERIFIED);
        assertThat(report.outcome("reconstruction.coverage", "main/transcript.jsonl")).isEqualTo(Check.Outcome.VERIFIED);
    }

    @Test
    void captureFacingRecordInputProducesTheSameBytesAsChunkedReplay(@TempDir Path work) {
        Path a = work.resolve("bytes");
        Path b = work.resolve("records");
        Clock clockA = WriterTestSupport.ticking();
        Clock clockB = WriterTestSupport.ticking();
        try (OcsfRunWriter w = OcsfRunWriter.open(a, run(CHILD), SchemaPin.bundled(), options().withClock(clockA))) {
            Replay.feedBytes(w.source(claudeStream()), nativeOf(CHILD, "stream.jsonl"), 13, clockA);
            w.complete();
        }
        try (OcsfRunWriter w = OcsfRunWriter.open(b, run(CHILD), SchemaPin.bundled(), options().withClock(clockB))) {
            Replay.feedRecords(w.source(claudeStream()), nativeOf(CHILD, "stream.jsonl"), clockB);
            w.complete();
        }
        assertThat(WriterTestSupport.bytes(b.resolve("events.jsonl"))).isEqualTo(WriterTestSupport.bytes(a.resolve("events.jsonl")));
        assertThat(WriterTestSupport.bytes(b.resolve("children").resolve(SPAWN).resolve("events.jsonl")))
                .isEqualTo(WriterTestSupport.bytes(a.resolve("children").resolve(SPAWN).resolve("events.jsonl")));
        Jv ma = manifest(a);
        Jv mb = manifest(b);
        assertThat(mb.member("sources").elements().get(0).member("sha256").text()).isEqualTo(ma.member("sources").elements().get(0).member("sha256").text());
        assertThat(mb.path("package", "events_jsonl_sha256").text()).isEqualTo(ma.path("package", "events_jsonl_sha256").text());
    }

    static Json group(Json usageView, String scope, String owner, String source) {
        for (Json g : usageView.member("groups").elements()) {
            if (scope.equals(g.member("scope").text()) && owner.equals(g.member("owner").text()) && source.equals(g.member("source").text())) {
                return g;
            }
        }
        return null;
    }
}
