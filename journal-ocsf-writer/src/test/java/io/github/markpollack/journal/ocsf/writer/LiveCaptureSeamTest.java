package io.github.markpollack.journal.ocsf.writer;

import io.github.markpollack.journal.tck.ocsf.Diagnostic;
import io.github.markpollack.journal.tck.ocsf.OcsfPackage;
import io.github.markpollack.journal.tck.ocsf.OracleReport;
import io.github.markpollack.journal.tck.verify.Check;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static io.github.markpollack.journal.ocsf.writer.WriterTestSupport.LIVE_FEATURES;
import static io.github.markpollack.journal.ocsf.writer.WriterTestSupport.nativeDir;
import static io.github.markpollack.journal.ocsf.writer.WriterTestSupport.nativeOf;
import static io.github.markpollack.journal.ocsf.writer.WriterTestSupport.notVerified;
import static io.github.markpollack.journal.ocsf.writer.WriterTestSupport.open;
import static io.github.markpollack.journal.ocsf.writer.WriterTestSupport.verify;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The live capture entry point exercised through a recorded seam: the sanitized Claude stream is piped to
 * {@code capture} on standard input as a live CLI's stdout would be (the sanitized real 2.1.304 stdout recording, the
 * one tested combination for that route). This is REPLAY evidence about the seam (framing
 * from stdin, flush per record, EOF versus driver interruption, the stream-reported CLI version declared when the
 * launch did not know it); it is not live capture and is never reported as such.
 */
class LiveCaptureSeamTest {

    @Test
    void aStreamPipedToTheCaptureSeamIsRecordedWithItsReportedVersionAndJudgedAgainstTheBytes(@TempDir Path work) throws Exception {
        Path spec = spec(work, "seam-complete");
        Path out = work.resolve("pkg");
        byte[] stream = WriterTestSupport.bytes(nativeOf(LIVE_FEATURES, "stream.jsonl"));
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        int exit = LiveCapture.run(new String[] {"capture", spec.toString(), out.toString(), "--flush-every", "1"},
                new ByteArrayInputStream(stream), new PrintStream(stdout, true, StandardCharsets.UTF_8), System.err);
        String log = stdout.toString(StandardCharsets.UTF_8);
        assertThat(exit).as(log).isZero();
        assertThat(log).contains("capture COMPLETED").contains("cli_version 2.1.304 (reported by the selected source");

        OcsfPackage p = open(out);
        assertThat(p.diagnostics(Diagnostic.Severity.DEFECT)).as(p.diagnostics.toString()).isEmpty();
        assertThat(p.manifest.cliVersion()).as("the launch declared no version; the stream reported one").isEqualTo("2.1.304");
        assertThat(p.manifest.compatibility("stream.jsonl").member("status").text()).isEqualTo("tested");
        assertThat(p.declarations.lineOf("cli_version:reported")).isNotNull();
        assertThat(p.declarations.lineOf("cli_version:reported")).as("declared after the init record's event, before anything that follows")
                .isGreaterThan(p.declarations.lineOf("source:stream.jsonl"));
        assertThat(p.events).as("53 plus the result record's per-model cost accounting event (ACCT-1)").hasSize(55);
        assertThat(p.children).hasSize(1);

        // the standalone file alone also knows the reported version
        Path alone = work.resolve("alone");
        Files.createDirectories(alone);
        Files.copy(out.resolve("events.jsonl"), alone.resolve("events.jsonl"));
        OcsfPackage a = io.github.markpollack.journal.tck.ocsf.PackageReader.open(alone.resolve("events.jsonl"));
        assertThat(a.manifest.cliVersion()).isEqualTo("2.1.304");
        assertThat(a.manifest.text("run", "cli", "version_basis")).contains("reported by the selected source");

        // judged against the bytes that were piped (the independent copy a driver would take with tee)
        OracleReport report = verify(out, nativeDir(work, LIVE_FEATURES, "stream.jsonl", -1));
        assertThat(report.defects()).as(notVerified(report).toString()).isEmpty();
        assertThat(report.outcome("acquisition.complete", "main")).isEqualTo(Check.Outcome.VERIFIED);
    }

    @Test
    void anInterruptFlagLeftByTheDriverEndsTheSourceAsInterrupted(@TempDir Path work) throws Exception {
        Path spec = spec(work, "seam-interrupted");
        Path out = work.resolve("pkg");
        Path flag = work.resolve("interrupt.flag");
        Files.writeString(flag, "SIGTERM sent to the CLI after 10 records\n");
        byte[] prefix = Replay.prefixBytes(nativeOf(LIVE_FEATURES, "stream.jsonl"), 10);
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        int exit = LiveCapture.run(new String[] {"capture", spec.toString(), out.toString(), "--interrupt-flag", flag.toString()},
                new ByteArrayInputStream(prefix), new PrintStream(stdout, true, StandardCharsets.UTF_8), System.err);
        assertThat(exit).isZero();
        assertThat(stdout.toString(StandardCharsets.UTF_8)).contains("capture INTERRUPTED");
        OcsfPackage p = open(out);
        assertThat(p.manifest.packageState()).isEqualTo("INTERRUPTED");
        assertThat(p.manifest.text("recording", "interruption_reason")).contains("SIGTERM sent to the CLI after 10 records");
        assertThat(p.manifest.source("stream.jsonl").path("ended", "how").text()).isEqualTo("INTERRUPTED");
        assertThat(p.manifest.source("stream.jsonl").member("records").scalarText()).isEqualTo("10");
    }

    private static Path spec(Path work, String runUid) throws Exception {
        Path spec = work.resolve("spec.json");
        Files.writeString(spec, "{\"run_uid\":\"" + runUid + "\",\"invocation_index\":0,\"capture_start\":\"2026-10-21T12:00:00Z\","
                + "\"cli\":{\"name\":\"Claude Code\",\"vendor_name\":\"Anthropic\",\"type_id\":1,\"type\":\"Native\"},"
                + "\"launch_settings\":{\"command\":\"REPLAY through the capture seam: the sanitized claude-live-2.1.304-features stream piped to stdin (not live)\"},"
                + "\"synthetic\":{\"label\":\"REPLAY SEAM TEST: recorded stream piped to the live capture entry point; not a live run\"}}", StandardCharsets.UTF_8);
        return spec;
    }
}
