package io.github.markpollack.journal.ocsf.writer;

import io.github.markpollack.journal.tck.ocsf.OcsfPackage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static io.github.markpollack.journal.ocsf.writer.WriterTestSupport.PAIR;
import static io.github.markpollack.journal.ocsf.writer.WriterTestSupport.claudeStream;
import static io.github.markpollack.journal.ocsf.writer.WriterTestSupport.manifest;
import static io.github.markpollack.journal.ocsf.writer.WriterTestSupport.nativeOf;
import static io.github.markpollack.journal.ocsf.writer.WriterTestSupport.open;
import static io.github.markpollack.journal.ocsf.writer.WriterTestSupport.options;
import static io.github.markpollack.journal.ocsf.writer.WriterTestSupport.run;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The persistence accounting and failure boundaries of the writer, each pinned to the file on disk: persisted byte
 * and event counts after every flush, the flush counter, the per-type and appended totals, provisional versus final
 * manifest fields, an interruption that arrives inside a record, failures injected at the manifest write and at the
 * final force, and the refusal of input after a failure. These are the PIT survivors of the delivery packet that
 * concern acquisition and lifecycle predicates, not wording.
 */
class WriterPersistenceTest {

    @Test
    void persistedBytesEventsAndFlushCountFollowTheFileAtEveryFlush(@TempDir Path work) {
        Path core = work.resolve("core");
        Path stream = nativeOf(PAIR, "stream.jsonl");
        List<LineFramer.Framed> lines = Replay.lines(WriterTestSupport.bytes(stream));
        try (OcsfRunWriter w = OcsfRunWriter.open(core, run(PAIR), SchemaPin.bundled(), options())) {
            SourceInput in = w.source(claudeStream());
            int flushes = 1; // the opening manifest
            for (int k = 0; k < lines.size(); k++) {
                in.record(lines.get(k).raw(), lines.get(k).ending(), options().clock().instant());
                if (k % 5 == 4) {
                    w.flush();
                    flushes++;
                    Jv m = manifest(core);
                    long onDisk = WriterTestSupport.bytes(core.resolve("events.jsonl")).length;
                    assertThat(((Jv.Num) m.path("package", "events_bytes")).value().longValue()).as("events_bytes after flush " + flushes + " at record " + (k + 1) + "; events_count " + m.path("package", "events_count").text() + "; lines " + WriterTestSupport.lines(core.resolve("events.jsonl")).size()).isEqualTo(onDisk);
                    assertThat(((Jv.Num) m.path("package", "events_count")).value().intValue()).isEqualTo(WriterTestSupport.eventLines(core.resolve("events.jsonl")).size());
                    assertThat(((Jv.Num) m.path("package", "declarations_count")).value().intValue()).isEqualTo(WriterTestSupport.declarations(core.resolve("events.jsonl")).size());
                    assertThat(m.path("package", "persisted_prefix", "sha256").text()).isEqualTo(SchemaPin.sha256(WriterTestSupport.bytes(core.resolve("events.jsonl"))));
                    assertThat(((Jv.Num) m.path("recording", "flush_count")).value().intValue()).isEqualTo(flushes);
                    assertThat(m.path("package", "state").text()).isEqualTo("RECORDING");
                    assertThat(m.path("package", "provisional")).as("a provisional note while recording").isNotNull();
                    assertThat(m.path("package", "events_jsonl_sha256").isNull()).isTrue();
                    assertThat(m.path("recording", "ended")).isNull();
                }
            }
            // an empty flush still counts and changes nothing on disk
            w.flush(); // the records appended since the last in-loop flush
            flushes++;
            long before = WriterTestSupport.bytes(core.resolve("events.jsonl")).length;
            w.flush();
            flushes++;
            assertThat(WriterTestSupport.bytes(core.resolve("events.jsonl")).length).isEqualTo(before);
            assertThat(((Jv.Num) manifest(core).path("recording", "flush_count")).value().intValue()).isEqualTo(flushes);
            in.end(SourceInput.End.EOF, options().clock().instant());
            w.complete();
            Jv m = manifest(core);
            assertThat(m.path("package", "provisional")).as("no provisional note once finalized").isNull();
            assertThat(m.path("recording", "ended")).isNotNull();
            assertThat(((Jv.Num) m.path("package", "events_bytes")).value().longValue()).isEqualTo(WriterTestSupport.bytes(core.resolve("events.jsonl")).length);
            int count = ((Jv.Num) m.path("package", "events_count")).value().intValue();
            assertThat(((Jv.Num) m.path("events", "appended")).value().intValue()).isEqualTo(count);
            assertThat(((Jv.Num) m.path("events", "count")).value().intValue()).isEqualTo(count);
            int byType = 0;
            for (var e : ((Jv.Obj) m.path("events", "by_type")).members().entrySet()) {
                byType += ((Jv.Num) e.getValue()).value().intValue();
            }
            assertThat(byType).as("events.by_type sums to the event count").isEqualTo(count);
            assertThat(m.path("synthetic", "label").text()).as("the run's synthetic label is carried").startsWith("LABELLED SYNTHETIC PAIR");
            assertThat(m.path("run", "launch_settings", "model").text()).isEqualTo("claude-haiku-4-5-20251001");
            assertThat(m.path("shared", "cli_agent", "instance_uid").text()).isEqualTo(run(PAIR).sessionUid());
            assertThat(m.path("ocsf", "compiled_excerpt_sha256").text()).hasSize(64);
            assertThat(m.path("recording", "interruption_reason")).isNull();
        }
    }

    @Test
    void interruptionInsideARecordRetainsTheIncompleteTailAndEndsTheSourceAsInterrupted(@TempDir Path work) {
        Path core = work.resolve("core");
        Path stream = nativeOf(PAIR, "stream.jsonl");
        byte[] all = WriterTestSupport.bytes(stream);
        int cut = Replay.prefixBytes(stream, 6).length + 40;
        OcsfRunWriter w = OcsfRunWriter.open(core, run(PAIR), SchemaPin.bundled(), options());
        SourceInput in = w.source(claudeStream());
        Replay.feedBytes(in, all, 13, options().clock(), cut, false);
        assertThat(in.records()).as("the partial seventh record is still in the framer").isEqualTo(6);
        assertThat(w.interrupt("process lost mid-record")).isEqualTo(OcsfRunWriter.State.INTERRUPTED);
        Jv source = manifest(core).member("sources").elements().get(0);
        assertThat(((Jv.Num) source.member("records")).value().intValue()).as("the tail became record 7").isEqualTo(7);
        assertThat(((Jv.Num) source.member("incomplete_tail_record")).value().intValue()).isEqualTo(7);
        assertThat(source.member("state").text()).isEqualTo("INTERRUPTED");
        assertThat(source.path("ended", "how").text()).isEqualTo("INTERRUPTED");
        assertThat(((Jv.Num) source.member("bytes")).value().intValue()).isEqualTo(cut);
        assertThat(Jv.write(manifest(core).path("recording", "acquisition", "detail"))).contains("interrupted after 7 record(s)").contains("record 7 is incomplete at source end");
        List<String> lines = WriterTestSupport.eventLines(core.resolve("events.jsonl"));
        Jv last = Jv.parse(lines.get(lines.size() - 1));
        assertThat(((Jv.Num) last.member("parse_status_id")).value().intValue()).isEqualTo(5);
        assertThat(last.member("raw_data").text()).isEqualTo(new String(all, cut - 40, 40, StandardCharsets.UTF_8));
    }

    @Test
    void mixedInputIsRecordedInTheAcquisitionDetailOfAnInterruptedPackage(@TempDir Path work) {
        Path core = work.resolve("core");
        try (OcsfRunWriter w = OcsfRunWriter.open(core, run(PAIR), SchemaPin.bundled(), options())) {
            SourceInput in = w.source(claudeStream());
            in.bytes("{\"type\":\"system\",\"subtype\":\"init\"}\n".getBytes(StandardCharsets.UTF_8), options().clock().instant());
            assertThatThrownBy(() -> in.record(new byte[] {'{', '}'}, "LF", options().clock().instant())).isInstanceOf(IllegalStateException.class);
            w.interrupt("mixed input");
        }
        assertThat(Jv.write(manifest(core).path("recording", "acquisition", "detail"))).contains("mixed byte and record input was rejected");
        assertThat(manifest(core).path("recording", "acquisition", "status").text()).isEqualTo("incomplete");
    }

    @Test
    void aFailureAtTheManifestWriteLeavesAFailedPackageWithTheConfirmedBoundary(@TempDir Path work) {
        Path core = work.resolve("core");
        int[] manifests = {0};
        WriterOptions opts = options().withFaults((operation, detail) -> {
            if ("manifest".equals(operation) && ++manifests[0] == 3) {
                throw new IOException("injected: manifest write refused");
            }
        });
        OcsfRunWriter w = OcsfRunWriter.open(core, run(PAIR), SchemaPin.bundled(), opts);
        SourceInput in = w.source(claudeStream());
        byte[] first = Replay.prefixBytes(nativeOf(PAIR, "stream.jsonl"), 5);
        Replay.feedBytes(in, first, 1000, opts.clock(), first.length, false);
        w.flush(); // manifest 2
        int confirmed = open(core).events.size();
        in.bytes(Replay.prefixBytes(nativeOf(PAIR, "stream.jsonl"), 8), first.length, Replay.prefixBytes(nativeOf(PAIR, "stream.jsonl"), 8).length - first.length, opts.clock().instant());
        assertThatThrownBy(w::flush).isInstanceOf(UncheckedIOException.class).hasMessageContaining("manifest write refused");
        assertThat(w.state()).isEqualTo(OcsfRunWriter.State.FAILED);
        assertThat(w.failure().get("operation").text()).isEqualTo("flush");
        assertThatThrownBy(w::flush).as("a failed writer refuses to flush").isInstanceOf(CoreWriter.WriterFailedException.class);
        assertThatThrownBy(() -> w.source(new SourceDescriptor("late.jsonl", 2, "claude_transcript", "late", null))).isInstanceOf(CoreWriter.WriterFailedException.class);
        assertThat(w.interrupt("too late")).as("finalization after failure keeps FAILED").isEqualTo(OcsfRunWriter.State.FAILED);
        Jv m = manifest(core);
        assertThat(m.path("package", "state").text()).isEqualTo("FAILED");
        assertThat(m.path("package", "provisional", "note").text()).contains("the writer failed");
        assertThat(((Jv.Num) m.path("recording", "failure", "events_confirmed_persisted")).value().intValue())
                .as("the events appended before the failing flush were written before the manifest failed").isGreaterThanOrEqualTo(confirmed);
        assertThat(m.path("recording", "failure", "operation").text()).isEqualTo("flush");
        assertThat(m.path("recording", "failure", "detail").text()).contains("manifest write refused");
        assertThat(((Jv.Num) m.path("recording", "failure", "bytes_confirmed_persisted")).value().longValue()).isLessThanOrEqualTo(WriterTestSupport.bytes(core.resolve("events.jsonl")).length);
        assertThat(m.member("sources").elements().get(0).member("state").text()).isEqualTo("INTERRUPTED");
        OcsfPackage p = open(core);
        assertThat(p.diagnosticsWithCode("PACKAGE_FAILED")).hasSize(1);
    }

    @Test
    void aFailureAtTheFinalForceIsReportedAsAFinishFailureNotACompletion(@TempDir Path work) {
        Path core = work.resolve("core");
        WriterOptions opts = options().withFaults((operation, detail) -> {
            if ("force".equals(operation)) {
                throw new IOException("injected: fsync failed");
            }
        });
        OcsfRunWriter w = OcsfRunWriter.open(core, run(PAIR), SchemaPin.bundled(), opts);
        Replay.feedBytes(w.source(claudeStream()), nativeOf(PAIR, "stream.jsonl"), 4096, opts.clock());
        assertThatThrownBy(w::complete).isInstanceOf(UncheckedIOException.class).hasMessageContaining("fsync failed");
        assertThat(w.state()).isEqualTo(OcsfRunWriter.State.FAILED);
        assertThat(w.complete()).isEqualTo(OcsfRunWriter.State.FAILED);
        Jv m = manifest(core);
        assertThat(m.path("package", "state").text()).isEqualTo("FAILED");
        assertThat(m.path("recording", "failure", "operation").text()).isEqualTo("finish");
        assertThat(m.path("package", "events_jsonl_sha256").isNull()).as("no final hash was ever written").isTrue();
        assertThat(((Jv.Num) m.path("recording", "failure", "events_not_confirmed")).value().intValue()).isEqualTo(0);
        assertThat(((Jv.Num) m.path("recording", "failure", "events_confirmed_persisted")).value().intValue()).isEqualTo(WriterTestSupport.eventLines(core.resolve("events.jsonl")).size());
        w.close();
        assertThat(manifest(core).path("package", "state").text()).isEqualTo("FAILED");
    }

    @Test
    void aFailureWhileWritingTheFailureManifestIsRecordedInMemory(@TempDir Path work) {
        Path core = work.resolve("core");
        int[] manifests = {0};
        WriterOptions opts = options().withFaults((operation, detail) -> {
            if ("manifest".equals(operation) && ++manifests[0] >= 2) {
                throw new IOException("injected: every manifest write refused");
            }
        });
        OcsfRunWriter w = OcsfRunWriter.open(core, run(PAIR), SchemaPin.bundled(), opts);
        SourceInput in = w.source(claudeStream());
        byte[] first = Replay.prefixBytes(nativeOf(PAIR, "stream.jsonl"), 3);
        Replay.feedBytes(in, first, 1000, opts.clock(), first.length, false);
        assertThatThrownBy(w::flush).isInstanceOf(UncheckedIOException.class);
        assertThat(w.state()).isEqualTo(OcsfRunWriter.State.FAILED);
        assertThat(w.failure().get("manifest_write_failed").text()).contains("every manifest write refused");
        assertThat(manifest(core).path("package", "state").text()).as("the opening manifest is what remains on disk").isEqualTo("RECORDING");
    }
}
