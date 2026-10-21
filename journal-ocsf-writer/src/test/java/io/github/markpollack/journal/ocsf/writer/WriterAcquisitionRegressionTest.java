package io.github.markpollack.journal.ocsf.writer;

import io.github.markpollack.journal.tck.ocsf.OcsfReaderTool;
import io.github.markpollack.journal.tck.ocsf.OracleReport;
import io.github.markpollack.journal.tck.verify.Check;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

import static io.github.markpollack.journal.ocsf.writer.WriterTestSupport.PAIR;
import static io.github.markpollack.journal.ocsf.writer.WriterTestSupport.claudeStream;
import static io.github.markpollack.journal.ocsf.writer.WriterTestSupport.claudeTranscript;
import static io.github.markpollack.journal.ocsf.writer.WriterTestSupport.freeze;
import static io.github.markpollack.journal.ocsf.writer.WriterTestSupport.manifest;
import static io.github.markpollack.journal.ocsf.writer.WriterTestSupport.nativeOf;
import static io.github.markpollack.journal.ocsf.writer.WriterTestSupport.notVerified;
import static io.github.markpollack.journal.ocsf.writer.WriterTestSupport.options;
import static io.github.markpollack.journal.ocsf.writer.WriterTestSupport.run;
import static io.github.markpollack.journal.ocsf.writer.WriterTestSupport.verify;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Storage finalization and acquisition completeness are two properties (FR-02). The writer may finalize a package
 * ({@code COMPLETED}) whose acquisition is honestly incomplete: EOF inside a record, a source ended as interrupted
 * before {@code complete()}, or no source at all. The independent oracle and the real CLI acceptance path preserve
 * every acquired byte and still refuse to accept such a package as a complete capture (exit 4), beside a complete
 * control (exit 0) and an interrupted-but-preserved control that fails no preservation property.
 */
class WriterAcquisitionRegressionTest {

    @Test
    void eofInsideARecordIsFinalizedHonestlyAndNotAcceptedAsComplete(@TempDir Path work) throws Exception {
        Path core = work.resolve("core");
        Path stream = nativeOf(PAIR, "stream.jsonl");
        byte[] all = WriterTestSupport.bytes(stream);
        int cut = Replay.prefixBytes(stream, 6).length + 40;
        try (OcsfRunWriter w = OcsfRunWriter.open(core, run(PAIR), SchemaPin.bundled(), options())) {
            Replay.feedBytes(w.source(claudeStream()), all, 11, options().clock(), cut, true);
            assertThat(w.complete()).as("storage is finalized").isEqualTo(OcsfRunWriter.State.COMPLETED);
        }
        Jv m = manifest(core);
        assertThat(m.path("package", "state").text()).isEqualTo("COMPLETED");
        assertThat(m.path("recording", "acquisition", "status").text()).isEqualTo("incomplete");
        Path nat = nativeDir(work, Arrays.copyOf(all, cut));
        freeze(nat, run(PAIR).runUid(), true, "stream.jsonl", "claude_stream");
        OracleReport report = verify(core, nat);
        assertThat(report.defects()).as(notVerified(report).toString()).isEmpty();
        assertThat(report.outcome("package.state", "main")).as("storage finalization holds").isEqualTo(Check.Outcome.VERIFIED);
        assertThat(report.outcome("acquisition.complete", "main")).as("acquisition completeness does not").isEqualTo(Check.Outcome.UNSUPPORTED);
        assertThat(report.check("acquisition.complete", "main").detail()).contains("record 7 incomplete at source end");
        assertThat(report.outcome("source.state", "main/stream.jsonl")).as("the declared tail is the artifact's cut last record, retained as such").isEqualTo(Check.Outcome.VERIFIED);
        assertThat(report.outcome("whole_record.bytes", "main/stream.jsonl")).as("the cut bytes are preserved").isEqualTo(Check.Outcome.VERIFIED);
        assertThat(OcsfReaderTool.acceptance(report).exit()).isEqualTo(OcsfReaderTool.EXIT_INCOMPLETE);
        assertThat(cli("verify", core.toString(), nat.toString())).isEqualTo(OcsfReaderTool.EXIT_INCOMPLETE);
    }

    @Test
    void aSourceEndedAsInterruptedThenCompletedIsPreservedButNotAcceptedAsComplete(@TempDir Path work) throws Exception {
        Path core = work.resolve("core");
        Path stream = nativeOf(PAIR, "stream.jsonl");
        byte[] prefix = Replay.prefixBytes(stream, 10);
        try (OcsfRunWriter w = OcsfRunWriter.open(core, run(PAIR), SchemaPin.bundled(), options())) {
            SourceInput in = w.source(claudeStream());
            Replay.feedBytes(in, prefix, 1000, options().clock(), prefix.length, false);
            in.end(SourceInput.End.INTERRUPTED, options().clock().instant());
            assertThat(w.complete()).as("every source has ended, so storage finalizes as COMPLETED").isEqualTo(OcsfRunWriter.State.COMPLETED);
        }
        Jv m = manifest(core);
        Jv source = m.member("sources").elements().get(0);
        assertThat(source.member("state").text()).isEqualTo("INTERRUPTED");
        assertThat(source.path("ended", "how").text()).isEqualTo("INTERRUPTED");
        assertThat(m.path("recording", "acquisition", "status").text()).isEqualTo("incomplete");
        assertThat(Jv.write(m.path("recording", "acquisition", "detail"))).contains("interrupted after 10 record(s)");
        // against the interrupted prefix itself: preserved in full, honest, not complete
        Path nat = nativeDir(work, prefix);
        freeze(nat, run(PAIR).runUid(), true, "stream.jsonl", "claude_stream");
        OracleReport report = verify(core, nat);
        assertThat(report.defects()).as(notVerified(report).toString()).isEmpty();
        assertThat(report.outcome("whole_record.coverage", "main/stream.jsonl")).isEqualTo(Check.Outcome.VERIFIED);
        assertThat(report.outcome("reconstruction.coverage", "main/stream.jsonl")).isEqualTo(Check.Outcome.VERIFIED);
        assertThat(report.outcome("acquisition.complete", "main")).isEqualTo(Check.Outcome.UNSUPPORTED);
        assertThat(report.check("acquisition.complete", "main").detail()).contains("interrupted after 10 record(s)");
        assertThat(OcsfReaderTool.acceptance(report).exit()).isEqualTo(OcsfReaderTool.EXIT_INCOMPLETE);
        // against the frozen whole run: the extent falls short and that is reported, not hidden behind COMPLETED
        Path whole = work.resolve("whole");
        Files.createDirectories(whole);
        Files.copy(stream, whole.resolve("stream.jsonl"));
        freeze(whole, run(PAIR).runUid(), true, "stream.jsonl", "claude_stream");
        OracleReport wholeReport = verify(core, whole);
        assertThat(wholeReport.outcome("source.extent", "main/stream.jsonl")).isEqualTo(Check.Outcome.UNSUPPORTED);
        assertThat(wholeReport.check("source.extent", "main/stream.jsonl").detail()).contains("10 of 27 record(s)");
        assertThat(cli("verify", core.toString(), whole.toString())).isEqualTo(OcsfReaderTool.EXIT_INCOMPLETE);
    }

    @Test
    void aCompletedPackageWithNoSourceIsNotACompleteCapture(@TempDir Path work) throws Exception {
        Path core = work.resolve("core");
        try (OcsfRunWriter w = OcsfRunWriter.open(core, run(PAIR), SchemaPin.bundled(), options())) {
            assertThat(w.complete()).isEqualTo(OcsfRunWriter.State.COMPLETED);
        }
        assertThat(manifest(core).path("recording", "acquisition", "status").text()).isEqualTo("no sources declared");
        Path nat = work.resolve("native");
        Files.createDirectories(nat);
        OracleReport report = verify(core, nat);
        assertThat(report.outcome("acquisition.complete", "main")).isEqualTo(Check.Outcome.UNSUPPORTED);
        assertThat(report.check("acquisition.complete", "main").detail()).contains("no source declared");
        assertThat(OcsfReaderTool.acceptance(report).exit()).isEqualTo(OcsfReaderTool.EXIT_INCOMPLETE);
        // and against a frozen manifest that expects the stream: an omitted source is a defect
        Files.copy(nativeOf(PAIR, "stream.jsonl"), nat.resolve("stream.jsonl"));
        freeze(nat, run(PAIR).runUid(), true, "stream.jsonl", "claude_stream");
        assertThat(cli("verify", core.toString(), nat.toString())).isEqualTo(OcsfReaderTool.EXIT_DEFECTS);
    }

    @Test
    void aCompleteTwoSourceRunIsAcceptedByTheCli(@TempDir Path work) throws Exception {
        Path core = work.resolve("core");
        try (OcsfRunWriter w = OcsfRunWriter.open(core, run(PAIR), SchemaPin.bundled(), options())) {
            Replay.feedBytes(w.source(claudeStream()), nativeOf(PAIR, "stream.jsonl"), 4096, options().clock());
            Replay.feedBytes(w.source(claudeTranscript()), nativeOf(PAIR, "transcript.jsonl"), 4096, options().clock());
            assertThat(w.complete()).isEqualTo(OcsfRunWriter.State.COMPLETED);
        }
        Path nat = work.resolve("native");
        Files.createDirectories(nat);
        Files.copy(nativeOf(PAIR, "stream.jsonl"), nat.resolve("stream.jsonl"));
        Files.copy(nativeOf(PAIR, "transcript.jsonl"), nat.resolve("transcript.jsonl"));
        // the committed frozen manifest of the fixture (with its expected split core) is the denominator
        Files.copy(WriterTestSupport.FIXTURES.resolve(PAIR).resolve("native").resolve("expected-sources.json"), nat.resolve("expected-sources.json"));
        OracleReport report = verify(core, nat);
        assertThat(report.defects()).as(notVerified(report).toString()).isEmpty();
        assertThat(report.outcome("acquisition.complete", "main")).isEqualTo(Check.Outcome.VERIFIED);
        assertThat(report.outcome("source.extent", "main/transcript.jsonl")).isEqualTo(Check.Outcome.VERIFIED);
        assertThat(report.outcome("package.children", "main")).isEqualTo(Check.Outcome.VERIFIED);
        assertThat(OcsfReaderTool.acceptance(report).exit()).isEqualTo(OcsfReaderTool.EXIT_OK);
        assertThat(cli("verify", core.toString(), nat.toString(), work.resolve("report.json").toString())).isEqualTo(OcsfReaderTool.EXIT_OK);
        assertThat(Files.readString(work.resolve("report.json"))).contains("\"missingRequired\" : [ ]");
    }

    static Path nativeDir(Path work, byte[] streamBytes) {
        try {
            Path nat = work.resolve("native");
            Files.createDirectories(nat);
            Files.write(nat.resolve("stream.jsonl"), streamBytes);
            return nat;
        }
        catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static int cli(String... args) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        return OcsfReaderTool.run(args, new PrintStream(out, true, StandardCharsets.UTF_8), new PrintStream(out, true, StandardCharsets.UTF_8));
    }
}
