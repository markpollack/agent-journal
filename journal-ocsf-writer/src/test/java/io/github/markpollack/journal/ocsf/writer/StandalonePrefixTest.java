package io.github.markpollack.journal.ocsf.writer;

import io.github.markpollack.journal.tck.ocsf.Diagnostic;
import io.github.markpollack.journal.tck.ocsf.OcsfPackage;
import io.github.markpollack.journal.tck.ocsf.PackageReader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;

import static io.github.markpollack.journal.ocsf.writer.WriterTestSupport.CHILD;
import static io.github.markpollack.journal.ocsf.writer.WriterTestSupport.PAIR;
import static io.github.markpollack.journal.ocsf.writer.WriterTestSupport.SPAWN;
import static io.github.markpollack.journal.ocsf.writer.WriterTestSupport.claudeStream;
import static io.github.markpollack.journal.ocsf.writer.WriterTestSupport.nativeOf;
import static io.github.markpollack.journal.ocsf.writer.WriterTestSupport.options;
import static io.github.markpollack.journal.ocsf.writer.WriterTestSupport.run;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The standalone event file through the writer's lifecycle, each time read from a copy of {@code events.jsonl}
 * alone (no {@code run.json}): a flushed prefix before the writer closes is an interpretable open prefix; the
 * completed file carries its finalization; an interrupted file says so; a file cut mid-line keeps every whole line
 * readable and reports the cut; a failed writer leaves a file without finalization (only the ancillary manifest
 * can say the writer failed); a child file written during the run is interpretable alone.
 */
class StandalonePrefixTest {

    @Test
    void aFlushedPrefixReadAloneIsAnOpenInterpretablePrefixAndTheCompletedFileCarriesItsFinalization(@TempDir Path work) throws IOException {
        Path core = work.resolve("core");
        Path stream = nativeOf(PAIR, "stream.jsonl");
        byte[] all = WriterTestSupport.bytes(stream);
        try (OcsfRunWriter w = OcsfRunWriter.open(core, run(PAIR), SchemaPin.bundled(), options())) {
            SourceInput in = w.source(claudeStream());
            byte[] prefix = Replay.prefixBytes(stream, 6);
            Replay.feedBytes(in, prefix, 7, options().clock(), prefix.length, false);
            w.flush();

            OcsfPackage open = alone(core, work.resolve("prefix-alone"));
            assertThat(open.standalone()).isTrue();
            assertThat(open.refused()).as(open.diagnostics.toString()).isFalse();
            assertThat(open.diagnostics(Diagnostic.Severity.DEFECT)).isEmpty();
            assertThat(open.diagnosticsWithCode("ANCILLARY_ABSENT").get(0).detail()).contains("open prefix");
            assertThat(open.manifest.packageState()).isEqualTo("RECORDING");
            assertThat(open.manifest.text("recording", "acquisition", "status")).isEqualTo("pending");
            assertThat(open.manifest.source("stream.jsonl").member("final").scalarText()).isEqualTo("false");
            assertThat(open.events).isNotEmpty();
            assertThat(open.events("tool_call", 1)).as("record 5 requested a tool; its request is readable from the prefix").hasSize(1);
            assertThat(open.catalog.classes()).containsKey("agentic_cli/tool_call");

            in.bytes(all, prefix.length, all.length - prefix.length, options().clock().instant());
            in.end(SourceInput.End.EOF, options().clock().instant());
            w.complete();
        }
        OcsfPackage done = alone(core, work.resolve("done-alone"));
        assertThat(done.diagnostics(Diagnostic.Severity.DEFECT)).isEmpty();
        assertThat(done.manifest.packageState()).isEqualTo("COMPLETED");
        assertThat(done.manifest.text("recording", "acquisition", "status")).isEqualTo("complete");
        assertThat(done.manifest.source("stream.jsonl").member("sha256").text()).isEqualTo(SchemaPin.sha256(all));
        assertThat(done.manifest.source("stream.jsonl").member("records").scalarText()).isEqualTo("27");
        assertThat(done.events).hasSize(PackageReader.open(core.resolve("events.jsonl")).events.size());
    }

    @Test
    void anInterruptedFileReadAloneSaysSoAndACutFileKeepsEveryWholeLine(@TempDir Path work) throws IOException {
        Path core = work.resolve("core");
        Path stream = nativeOf(PAIR, "stream.jsonl");
        try (OcsfRunWriter w = OcsfRunWriter.open(core, run(PAIR), SchemaPin.bundled(), options())) {
            SourceInput in = w.source(claudeStream());
            byte[] prefix = Replay.prefixBytes(stream, 10);
            Replay.feedBytes(in, prefix, 1000, options().clock(), prefix.length, false);
            w.interrupt("operator cancelled");
        }
        OcsfPackage interrupted = alone(core, work.resolve("interrupted-alone"));
        assertThat(interrupted.diagnostics(Diagnostic.Severity.DEFECT)).isEmpty();
        assertThat(interrupted.manifest.packageState()).isEqualTo("INTERRUPTED");
        assertThat(interrupted.manifest.text("recording", "interruption_reason")).isEqualTo("operator cancelled");
        assertThat(interrupted.manifest.text("recording", "acquisition", "status")).isEqualTo("incomplete");
        assertThat(interrupted.manifest.source("stream.jsonl").path("ended", "how").text()).isEqualTo("INTERRUPTED");

        // the same file cut inside its last line: the whole lines before the cut are read, the cut line is reported, no finalization is claimed
        byte[] bytes = WriterTestSupport.bytes(core.resolve("events.jsonl"));
        int cut = bytes.length - 40;
        Path cutDir = work.resolve("cut-alone");
        Files.createDirectories(cutDir);
        Files.write(cutDir.resolve("events.jsonl"), Arrays.copyOf(bytes, cut));
        OcsfPackage truncated = PackageReader.open(cutDir.resolve("events.jsonl"));
        assertThat(truncated.refused()).isFalse();
        assertThat(truncated.diagnosticsWithCode("EVENT_UNPARSEABLE")).hasSize(1);
        assertThat(truncated.manifest.packageState()).as("the recording declaration was the cut line").isEqualTo("RECORDING");
        assertThat(truncated.events).hasSize(interrupted.events.size());
    }

    @Test
    void aFailedWriterLeavesAFileWithoutFinalizationThatOnlyTheAncillaryManifestCallsFailed(@TempDir Path work) throws IOException {
        Path core = work.resolve("core");
        AtomicInteger appends = new AtomicInteger();
        WriterOptions opts = options().withFaults((operation, detail) -> {
            if ("append".equals(operation) && appends.incrementAndGet() == 3) {
                throw new IOException("injected: disk full");
            }
        });
        OcsfRunWriter w = OcsfRunWriter.open(core, run(PAIR), SchemaPin.bundled(), opts);
        SourceInput in = w.source(claudeStream());
        Path stream = nativeOf(PAIR, "stream.jsonl");
        byte[] first = Replay.prefixBytes(stream, 5);
        Replay.feedBytes(in, first, 1000, opts.clock(), first.length, false);
        w.flush();
        byte[] all = WriterTestSupport.bytes(stream);
        in.bytes(all, first.length, all.length - first.length, opts.clock().instant());
        try {
            w.flush();
        }
        catch (RuntimeException expected) {
            // the injected failure
        }
        assertThat(w.state()).isEqualTo(OcsfRunWriter.State.FAILED);
        OcsfPackage alone = alone(core, work.resolve("failed-alone"));
        assertThat(alone.manifest.packageState()).as("the file alone cannot know the writer failed; it is an unfinalized prefix").isEqualTo("RECORDING");
        OcsfPackage withManifest = PackageReader.open(core.resolve("events.jsonl"));
        assertThat(withManifest.manifest.packageState()).isEqualTo("FAILED");
        assertThat(withManifest.diagnosticsWithCode("PACKAGE_FAILED")).hasSize(1);
        assertThat(withManifest.events.size()).isEqualTo(alone.events.size());
    }

    @Test
    void aChildFileWrittenDuringTheRunIsInterpretableAloneBeforeTheRunEnds(@TempDir Path work) throws IOException {
        Path core = work.resolve("core");
        try (OcsfRunWriter w = OcsfRunWriter.open(core, run(CHILD), SchemaPin.bundled(), options())) {
            SourceInput in = w.source(claudeStream());
            Path stream = nativeOf(CHILD, "stream.jsonl");
            byte[] prefix = Replay.prefixBytes(stream, 22); // records 18, 20 and 21 belong to the child
            Replay.feedBytes(in, prefix, 512, options().clock(), prefix.length, false);
            w.flush();
            Path childCore = core.resolve("children").resolve(SPAWN);
            OcsfPackage child = alone(childCore, work.resolve("child-alone"));
            assertThat(child.refused()).as(child.diagnostics.toString()).isFalse();
            assertThat(child.diagnostics(Diagnostic.Severity.DEFECT)).isEmpty();
            assertThat(child.manifest.packageState()).isEqualTo("RECORDING");
            assertThat(child.manifest.parent().member("run_uid").text()).isEqualTo(run(CHILD).runUid());
            assertThat(child.manifest.sourceScope().member("owner_value").text()).isEqualTo(SPAWN);
            assertThat(child.events).hasSize(4);
            assertThat(child.manifest.source("stream.jsonl").member("final").scalarText()).as("the source is still open in the child too").isEqualTo("false");
            OcsfPackage parent = alone(core, work.resolve("parent-alone"));
            assertThat(parent.diagnosticsWithCode("CHILD_EXTERNAL")).hasSize(1);
            assertThat(parent.manifest.sourceScope().member("owner_value").isNull()).isTrue();
            in.bytes(WriterTestSupport.bytes(stream), prefix.length, WriterTestSupport.bytes(stream).length - prefix.length, options().clock().instant());
            in.end(SourceInput.End.EOF, options().clock().instant());
            w.complete();
            OcsfPackage childDone = alone(childCore, work.resolve("child-done"));
            assertThat(childDone.manifest.packageState()).isEqualTo("COMPLETED");
            assertThat(childDone.manifest.source("stream.jsonl").member("sha256").text()).hasSize(64);
        }
    }

    static OcsfPackage alone(Path core, Path target) throws IOException {
        Files.createDirectories(target);
        Files.copy(core.resolve("events.jsonl"), target.resolve("events.jsonl"));
        assertThat(Files.list(target).count()).isEqualTo(1);
        return PackageReader.open(target.resolve("events.jsonl"));
    }

    static String text(byte[] bytes) {
        return new String(bytes, StandardCharsets.UTF_8);
    }
}
