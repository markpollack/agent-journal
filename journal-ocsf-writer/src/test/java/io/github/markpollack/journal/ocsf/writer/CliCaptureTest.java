package io.github.markpollack.journal.ocsf.writer;

import io.github.markpollack.journal.tck.ocsf.Diagnostic;
import io.github.markpollack.journal.tck.ocsf.NativeArtifact;
import io.github.markpollack.journal.tck.ocsf.inventory.AssemblyOracle;
import io.github.markpollack.journal.tck.verify.Json;
import io.github.markpollack.journal.tck.verify.NeutralJson;
import io.github.markpollack.journal.tck.ocsf.OcsfPackage;
import io.github.markpollack.journal.tck.ocsf.OracleReport;
import io.github.markpollack.journal.tck.ocsf.PackageReader;
import io.github.markpollack.journal.tck.verify.Check;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static io.github.markpollack.journal.ocsf.writer.WriterTestSupport.CHILD;
import static io.github.markpollack.journal.ocsf.writer.WriterTestSupport.CODEX_STREAM;
import static io.github.markpollack.journal.ocsf.writer.WriterTestSupport.LIVE_AGY_FEATURES;
import static io.github.markpollack.journal.ocsf.writer.WriterTestSupport.LIVE_CLAUDE_PAIR;
import static io.github.markpollack.journal.ocsf.writer.WriterTestSupport.LIVE_FEATURES;
import static io.github.markpollack.journal.ocsf.writer.WriterTestSupport.nativeOf;
import static io.github.markpollack.journal.ocsf.writer.WriterTestSupport.verify;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The opt-in launching capture ({@link CliCapture}) with stand-in CLIs: small shell scripts that replay a committed
 * stream (the sanitized real 2.1.304 stdout recording, the one tested combination for the stdout route) line by line
 * with delays and end the way each scenario needs. TEST-ONLY FAULTS, labelled as such: nothing
 * here is a live CLI; the live Claude and Codex runs are separate steward evidence and are never reported as this.
 *
 * <p>What is established deterministically: the package is readable before the process exits; EOF with exit 0 is a
 * COMPLETED, completely acquired run; EOF with a non-zero exit is COMPLETED and completely acquired with a failed CLI
 * outcome (EOF manufactures no success); a deliberate cancellation terminates the process and interrupts the
 * recording with the reason; an abnormal exit inside a record leaves an incomplete tail, honest and not complete; a
 * persistence failure stops the CLI and leaves a FAILED package while the independent copy keeps every byte; the
 * native session file is archived by bytes and hash from the session the stream reported, never parsed, and its
 * contents cannot change the normalized output; Codex's version is discovered once by the documented command and
 * its thread id becomes the session.
 */
@EnabledOnOs({OS.LINUX, OS.MAC})
class CliCaptureTest {

    static final String CLAUDE_SESSION = "00000000-0000-4000-8000-0000000000a1";
    static final String LIVE_SPAWN = "toolu_01WBvs5eGudfYPm8YvrTYTQK";
    static final String CODEX_THREAD = "00000000-0000-4000-8000-0000000c0de5";
    /** The conversation stand-in of the sanitized Antigravity feature recording. */
    static final String AGY_CONVERSATION = "00000000-0000-4000-8000-000000000200";

    @Test
    void aCompleteRunIsReadableBeforeExitCompletedAcquiredAndJudgedAgainstTheIndependentCopy(@TempDir Path work) throws Exception {
        Path task = Files.createDirectories(work.resolve("task"));
        Path home = Files.createDirectories(work.resolve("home"));
        Path sessionFile = claudeSessionFile(home, task, CLAUDE_SESSION);
        Files.writeString(sessionFile, "{\"this\":\"is the native session file\",\"n\":1}\n{broken json on purpose\n", StandardCharsets.UTF_8);
        Path fake = fakeClaude(work, nativeOf(LIVE_FEATURES, "stream.jsonl"), 60, 0, false);
        CliCapture capture = new CliCapture(launch(CliCapture.Cli.CLAUDE, fake, task, work.resolve("out"), home, null, null), options(work));
        CompletableFuture<CliCapture.Result> running = CompletableFuture.supplyAsync(() -> {
            try {
                return capture.run();
            }
            catch (IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
        });
        // readable before exit: open the persisted package while the stand-in is still emitting
        int seenWhileRecording = 0;
        String stateSeen = null;
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (System.nanoTime() < deadline && !running.isDone()) {
            Path events = work.resolve("out/package/events.jsonl");
            if (Files.exists(events)) {
                try {
                    OcsfPackage p = PackageReader.open(events);
                    if (p.manifest != null && p.events.size() >= 5 && "RECORDING".equals(p.manifest.packageState())) {
                        seenWhileRecording = p.events.size();
                        stateSeen = p.manifest.packageState();
                        break;
                    }
                }
                catch (RuntimeException racing) {
                    // a flush in progress; try again
                }
            }
            Thread.sleep(20);
        }
        CliCapture.Result r = running.get(60, TimeUnit.SECONDS);
        assertThat(seenWhileRecording).as("events read while the process was still running").isGreaterThanOrEqualTo(5);
        assertThat(stateSeen).isEqualTo("RECORDING");

        assertThat(r.state()).isEqualTo(OcsfRunWriter.State.COMPLETED);
        assertThat(r.how()).isEqualTo("exited");
        assertThat(r.exitCode()).isZero();
        assertThat(r.records()).isEqualTo(50);
        assertThat(r.sessionUid()).isEqualTo(CLAUDE_SESSION);
        assertThat(r.cliVersion()).as("reported by the stream; none at launch").isEqualTo("2.1.304");
        assertThat(Files.readAllBytes(r.independentCopy())).isEqualTo(Files.readAllBytes(nativeOf(LIVE_FEATURES, "stream.jsonl")));

        OcsfPackage p = PackageReader.open(r.packageDir().resolve("events.jsonl"));
        assertThat(p.diagnostics(Diagnostic.Severity.DEFECT)).as(p.diagnostics.toString()).isEmpty();
        assertThat(p.manifest.text("recording", "process", "how")).isEqualTo("exited");
        assertThat(p.manifest.integer("recording", "process", "exit_code")).isZero();
        assertThat(p.declarations.recording().member("process").member("how").text()).as("the file itself carries the process outcome").isEqualTo("exited");
        assertThat(p.manifest.text("run", "launch_settings", "caller_input", "provenance")).contains("caller-supplied").contains("not reported by the CLI");
        assertThat(p.manifest.text("run", "launch_settings", "route")).isEqualTo("stdout");
        assertThat(p.manifest.text("run", "cli", "version_basis")).contains("reported by the selected source");
        assertThat(p.manifest.compatibility("stream.jsonl").member("status").text()).as("2.1.304 on the stdout route: the exactly tested combination").isEqualTo("tested");
        assertThat(p.manifest.compatibility("stream.jsonl").member("entry_id").text()).isEqualTo("claude-code/stream-json/2.1.304-stdout");
        assertThat(p.diagnosticsWithCode("CLI_EXIT_NONZERO")).isEmpty();
        assertThat(p.children).hasSize(1);

        // archived by bytes and hash from the reported session; never parsed (the file is not even JSON)
        assertThat(r.archive().get("status").text()).isEqualTo("archived");
        Path archived = work.resolve("out/archive").resolve(CLAUDE_SESSION + ".jsonl");
        assertThat(Files.readAllBytes(archived)).isEqualTo(Files.readAllBytes(sessionFile));
        assertThat(r.archive().get("sha256").text()).isEqualTo(CliCapture.sha256(Files.readAllBytes(sessionFile)));
        assertThat(r.archive().get("located_by").text()).contains("session id the stream reported").contains("<home>/.claude/projects/");
        assertThat(r.archive().get("located_at").text()).isEqualTo(sessionFile.toString());
        assertThat(p.manifest.json().member("archive").member("parsed").scalarText()).isEqualTo("false");
        assertThat(p.manifest.sources()).as("the archive is not a declared source").hasSize(1);

        // the oracle against the frozen copy: accepted, complete
        OracleReport report = verify(r.packageDir(), work.resolve("out/native"));
        assertThat(report.defects()).as(report.toString()).isEmpty();
        assertThat(report.outcome("acquisition.complete", "main")).isEqualTo(Check.Outcome.VERIFIED);
        assertThat(report.outcome("source.inventory", "main/stream.jsonl")).isEqualTo(Check.Outcome.VERIFIED);
        assertThat(report.outcome("package.children", "main")).as("the split core frozen from the copy's owner paths is the one the candidate linked").isEqualTo(Check.Outcome.VERIFIED);
        assertThat(Files.readString(r.expectedSources())).contains("\"spawn_tool_call_uid\":\"" + LIVE_SPAWN + "\"");
    }

    /** A version tested on another route only (2.1.299, sdk) replayed on stdout: no match, retained whole and flagged; no child split, nothing normalized after the version report. */
    @Test
    void aVersionTestedOnAnotherRouteOnlyIsNoMatchRetainedAndFlagged(@TempDir Path work) throws Exception {
        Path task = Files.createDirectories(work.resolve("task"));
        Path fake = fakeClaude(work, nativeOf(CHILD, "stream.jsonl"), 0, 0, false);
        CliCapture.Result r = new CliCapture(launch(CliCapture.Cli.CLAUDE, fake, task, work.resolve("out"), work.resolve("home"), null, null), options(work)).run();
        assertThat(r.state()).isEqualTo(OcsfRunWriter.State.COMPLETED);
        assertThat(r.how()).isEqualTo("exited");
        assertThat(r.records()).isEqualTo(27);
        assertThat(r.cliVersion()).isEqualTo("2.1.299");
        OcsfPackage p = PackageReader.open(r.packageDir().resolve("events.jsonl"));
        assertThat(p.diagnostics(Diagnostic.Severity.DEFECT)).as(p.diagnostics.toString()).isEmpty();
        assertThat(p.manifest.compatibility("stream.jsonl").member("status").text()).as("2.1.299 is tested on the sdk route only; stdout matches no entry").isEqualTo("no_match");
        assertThat(p.manifest.compatibility("stream.jsonl").member("entry_id")).as("the nearest entry is not selected").isNull();
        assertThat(p.manifest.compatibility("stream.jsonl").member("normalizes").text()).isEqualTo("no");
        assertThat(p.manifest.compatibility("stream.jsonl").member("detail").text()).contains("2.1.299").contains("route stdout").contains("claude-code/stream-json/2.1.304-stdout [2.1.304, 2.1.304]");
        assertThat(p.diagnosticsWithCode("COMPATIBILITY_NO_MATCH")).hasSize(1);
        assertThat(p.children).as("owner routing is normalization; nothing after the selection was normalized").isEmpty();
        assertThat(p.events.stream().filter(e -> e.recordIndex() != null && e.recordIndex() > 1).map(e -> e.className).distinct().toList()).containsExactly("source_record");
        String frozen = Files.readString(r.expectedSources());
        assertThat(frozen).as("the frozen manifest expects retention, not a split core").contains("\"compatibility\":\"no_match\"").contains("\"children\":[]");
        OracleReport report = verify(r.packageDir(), work.resolve("out/native"));
        assertThat(report.defects()).as(report.toString()).isEmpty();
        assertThat(report.outcome("source.compatibility", "main/stream.jsonl")).isEqualTo(Check.Outcome.UNSUPPORTED);
        assertThat(report.outcome("whole_record.coverage", "main/stream.jsonl")).isEqualTo(Check.Outcome.VERIFIED);
        assertThat(report.outcome("acquisition.complete", "main")).isEqualTo(Check.Outcome.VERIFIED);
    }

    /** Changing the archived file's contents cannot change the normalized output: the integration never opens it. */
    @Test
    void theArchiveContentsCannotChangeTheNormalizedStreamOutput(@TempDir Path work) throws Exception {
        Path task = Files.createDirectories(work.resolve("task"));
        Path fake = fakeClaude(work, nativeOf(LIVE_FEATURES, "stream.jsonl"), 0, 0, false);
        byte[] first = null;
        for (String content : List.of("{\"variant\":\"A\",\"claude_code_version\":\"9.9.9\",\"usage\":{\"input_tokens\":123456}}\n", "garbage \u0000 bytes, not JSON at all")) {
            Path home = Files.createDirectories(work.resolve("home-" + content.length()));
            Files.writeString(claudeSessionFile(home, task, CLAUDE_SESSION), content, StandardCharsets.UTF_8);
            Path out = work.resolve("out-" + content.length());
            CliCapture.Launch base = launch(CliCapture.Cli.CLAUDE, fake, task, out, home, null, null);
            CliCapture.Result r = new CliCapture(new CliCapture.Launch(base.cli(), base.command(), base.workDir(), base.prompt(), "archive-independence", out, null, null, home, null, base.launchNotes(), null, null),
                    options(work)).run();
            assertThat(r.state()).isEqualTo(OcsfRunWriter.State.COMPLETED);
            assertThat(r.archive().get("status").text()).isEqualTo("archived");
            // every line except the recording declaration, which carries the process id of this run
            String text = Files.readString(out.resolve("package/events.jsonl"), StandardCharsets.UTF_8);
            String comparable = text.lines().filter(l -> !l.contains("\"declaration\":{\"kind\":\"recording\"")).reduce("", (a, b) -> a + b + "\n");
            byte[] events = comparable.getBytes(StandardCharsets.UTF_8);
            if (first == null) {
                first = events;
            }
            else {
                assertThat(events).as("identical declarations and events under a fixed clock whatever the archive holds").isEqualTo(first);
            }
            assertThat(text).doesNotContain("9.9.9").doesNotContain("123456");
            OcsfPackage p = PackageReader.open(out.resolve("package/events.jsonl"));
            assertThat(p.manifest.cliVersion()).isEqualTo("2.1.304");
        }
    }

    /**
     * A complete-capture configuration (an assembly profile at launch) assembles the final package automatically after
     * acquisition, from the standard output copy and the archived session file, without changing the original package.
     */
    @Test
    void aCompleteCaptureAssemblesTheFinalPackageAutomatically(@TempDir Path work) throws Exception {
        Path task = Files.createDirectories(work.resolve("task"));
        Path home = Files.createDirectories(work.resolve("home"));
        Files.copy(nativeOf(LIVE_CLAUDE_PAIR, "transcript.jsonl"), claudeSessionFile(home, task, FinalAssemblyClaudeTest.RUN_SESSION));
        Path fake = fakeClaude(work, nativeOf(LIVE_CLAUDE_PAIR, "stream.jsonl"), 0, 0, false);
        Path out = work.resolve("out");
        CliCapture.Launch base = launch(CliCapture.Cli.CLAUDE, fake, task, out, home, null, null);
        CliCapture.Result r = new CliCapture(new CliCapture.Launch(base.cli(), base.command(), base.workDir(), base.prompt(), "complete-capture", out, null, null, home, null,
                base.launchNotes(), null, FinalAssemblyClaudeTest.PROFILE), options(work)).run();
        assertThat(r.state()).isEqualTo(OcsfRunWriter.State.COMPLETED);
        assertThat(r.assembly().get("status").text()).as(Jv.write(r.assembly())).isEqualTo("assembled");
        assertThat(r.assembly().get("state").text()).isEqualTo("COMPLETED");
        String original = NativeArtifact.read(out.resolve("package/events.jsonl")).sha256();
        OcsfPackage fin = PackageReader.open(out.resolve("final/events.jsonl"));
        assertThat(fin.declarations.assembly().member("lineage").elements()).singleElement()
                .satisfies(l -> assertThat(l.member("events_jsonl_sha256").text()).as("the original package, unchanged").isEqualTo(original));
        assertThat(fin.events("tool_call", 1)).hasSize(4).allSatisfy(e -> assertThat(e.assemblyRole()).isEqualTo(3));
        Path archived = out.resolve("archive").resolve(r.archive().get("artifact").text());
        List<AssemblyOracle.Supplied> supplied = List.of(
                new AssemblyOracle.Supplied("stream.jsonl", "stream", "CliCapture independent copy of standard output", NativeArtifact.read(r.independentCopy())),
                new AssemblyOracle.Supplied("transcript.jsonl", "transcript", "native session file archived after the launch ended (" + r.archive().get("sha256").text() + ")",
                        NativeArtifact.read(archived)));
        Json spec = NeutralJson.parse(Files.readString(WriterTestSupport.FIXTURES.resolve(LIVE_CLAUDE_PAIR).resolve("native/assembly-expectation.json")));
        OracleReport report = AssemblyOracle.verify(fin, spec, supplied);
        assertThat(report.defects()).as(report.toString()).isEmpty();
        // without the configuration nothing is assembled
        CliCapture.Result plain = new CliCapture(launch(CliCapture.Cli.CLAUDE, fake, task, work.resolve("plain"), home, null, null), options(work)).run();
        assertThat(plain.assembly().get("status").text()).isEqualTo("not configured");
        assertThat(Files.exists(work.resolve("plain/final"))).isFalse();
    }

    @Test
    void aNonZeroExitAfterACompleteStreamIsACompletelyAcquiredFailedRun(@TempDir Path work) throws Exception {
        Path task = Files.createDirectories(work.resolve("task"));
        Path fake = fakeClaude(work, nativeOf(LIVE_FEATURES, "stream.jsonl"), 0, 2, false);
        CliCapture.Result r = new CliCapture(launch(CliCapture.Cli.CLAUDE, fake, task, work.resolve("out"), work.resolve("home"), null, null), options(work)).run();
        assertThat(r.state()).as("storage: finalized").isEqualTo(OcsfRunWriter.State.COMPLETED);
        assertThat(r.how()).isEqualTo("exited");
        assertThat(r.exitCode()).as("CLI outcome: failed").isEqualTo(2);
        OcsfPackage p = PackageReader.open(r.packageDir().resolve("events.jsonl"));
        assertThat(p.diagnosticsWithCode("CLI_EXIT_NONZERO")).hasSize(1);
        assertThat(p.diagnosticsWithCode("CLI_EXIT_NONZERO").get(0).detail()).contains("exited with 2").contains("judged separately");
        OracleReport report = verify(r.packageDir(), work.resolve("out/native"));
        assertThat(report.defects()).isEmpty();
        assertThat(report.outcome("acquisition.complete", "main")).as("acquisition: complete to EOF").isEqualTo(Check.Outcome.VERIFIED);
        assertThat(r.archive().get("status").text()).as("no session file under the test home: reported, not invented").isEqualTo("not located");
    }

    @Test
    void aDeliberateCancellationTerminatesTheProcessAndInterruptsTheRecordingWithTheReason(@TempDir Path work) throws Exception {
        Path task = Files.createDirectories(work.resolve("task"));
        Path fake = fakeClaude(work, nativeOf(LIVE_FEATURES, "stream.jsonl"), 150, 0, false);
        CliCapture.Result r = new CliCapture(launch(CliCapture.Cli.CLAUDE, fake, task, work.resolve("out"), work.resolve("home"), 6, null), options(work)).run();
        assertThat(r.state()).isEqualTo(OcsfRunWriter.State.INTERRUPTED);
        assertThat(r.how()).isEqualTo("cancelled");
        assertThat(r.exitCode()).as("SIGTERM to a shell loop: 128+15").isEqualTo(143);
        assertThat(r.process().get("cancel_reason").text()).contains("deliberate cancellation after").contains("requested after 6");
        assertThat(r.records()).isGreaterThanOrEqualTo(6).isLessThan(50);
        OcsfPackage p = PackageReader.open(r.packageDir().resolve("events.jsonl"));
        assertThat(p.manifest.packageState()).isEqualTo("INTERRUPTED");
        assertThat(p.manifest.text("recording", "interruption_reason")).contains("deliberate cancellation");
        assertThat(p.manifest.source("stream.jsonl").path("ended", "how").text()).isEqualTo("INTERRUPTED");
        assertThat(p.diagnosticsWithCode("CLI_CANCELLED")).hasSize(1);
        assertThat(p.declarations.recording().member("process").member("how").text()).isEqualTo("cancelled");
        // frozen as a partial capture; the oracle judges the prefix and does not accept it as complete
        assertThat(Files.readString(r.expectedSources())).contains("\"complete\":false");
        OracleReport report = verify(r.packageDir(), work.resolve("out/native"));
        assertThat(report.defects()).as(report.toString()).isEmpty();
        assertThat(report.outcome("acquisition.complete", "main")).isEqualTo(Check.Outcome.UNSUPPORTED);
        assertThat(report.outcome("package.state", "main")).isEqualTo(Check.Outcome.UNSUPPORTED);
    }

    @Test
    void anAbnormalExitInsideARecordLeavesAnHonestIncompleteTailAndAFailedProcess(@TempDir Path work) throws Exception {
        Path task = Files.createDirectories(work.resolve("task"));
        Path fake = fakeClaude(work, nativeOf(LIVE_FEATURES, "stream.jsonl"), 0, 1, true);
        CliCapture.Result r = new CliCapture(launch(CliCapture.Cli.CLAUDE, fake, task, work.resolve("out"), work.resolve("home"), null, null), options(work)).run();
        assertThat(r.state()).as("EOF reached: storage finalized").isEqualTo(OcsfRunWriter.State.COMPLETED);
        assertThat(r.how()).isEqualTo("exited");
        assertThat(r.exitCode()).isEqualTo(1);
        OcsfPackage p = PackageReader.open(r.packageDir().resolve("events.jsonl"));
        assertThat(p.manifest.source("stream.jsonl").member("incomplete_tail_record")).as("the cut record is retained as an incomplete tail").isNotNull();
        assertThat(p.manifest.text("recording", "acquisition", "status")).isEqualTo("incomplete");
        OracleReport report = verify(r.packageDir(), work.resolve("out/native"));
        assertThat(report.defects()).as(report.toString()).isEmpty();
        assertThat(report.outcome("acquisition.complete", "main")).as("not accepted as complete").isEqualTo(Check.Outcome.UNSUPPORTED);
        assertThat(report.outcome("whole_record.bytes", "main/stream.jsonl")).as("the incomplete tail is byte-retained").isEqualTo(Check.Outcome.VERIFIED);
    }

    @Test
    void aPersistenceFailureStopsTheProcessLeavesAFailedPackageAndKeepsTheIndependentCopyWhole(@TempDir Path work) throws Exception {
        Path task = Files.createDirectories(work.resolve("task"));
        Path fake = fakeClaude(work, nativeOf(LIVE_FEATURES, "stream.jsonl"), 40, 0, false);
        int[] writes = {0};
        WriterOptions faulty = options(work).withFaults((operation, detail) -> {
            if (operation.equals("append") && ++writes[0] == 4) {
                throw new IOException("TEST-ONLY FAULT: events write refused");
            }
        });
        CliCapture.Result r = new CliCapture(launch(CliCapture.Cli.CLAUDE, fake, task, work.resolve("out"), work.resolve("home"), null, null), faulty).run();
        assertThat(r.state()).isEqualTo(OcsfRunWriter.State.FAILED);
        assertThat(r.how()).isEqualTo("writer_failure");
        assertThat(r.process().get("cancel_reason").text()).contains("writer failed");
        assertThat(Files.readAllBytes(r.independentCopy())).as("the independent copy kept draining the CLI's output until it stopped").isNotEmpty();
        Jv manifest = Jv.parse(Files.readString(r.packageDir().resolve("run.json"), StandardCharsets.UTF_8));
        assertThat(manifest.path("package", "state").text()).isEqualTo("FAILED");
        assertThat(manifest.path("recording", "failure", "operation").text()).isEqualTo("flush");
        assertThat(Files.readString(r.expectedSources())).contains("\"complete\":false");
    }

    @Test
    void codexVersionIsDiscoveredOnceByTheDocumentedCommandAndTheThreadIdBecomesTheSession(@TempDir Path work) throws Exception {
        Path task = Files.createDirectories(work.resolve("task"));
        Path home = Files.createDirectories(work.resolve("home"));
        Path counter = work.resolve("version-calls");
        Path fake = fakeCodex(work, nativeOf(CODEX_STREAM, "stream.jsonl"), counter);
        Path rollout = home.resolve(".codex/sessions/2026/10/21").resolve("rollout-2026-10-21T12-00-00-" + CODEX_THREAD + ".jsonl");
        Files.createDirectories(rollout.getParent());
        Files.writeString(rollout, "{\"timestamp\":\"x\",\"type\":\"session_meta\",\"payload\":{\"id\":\"" + CODEX_THREAD + "\",\"cli_version\":\"0.160.1\"}}\n", StandardCharsets.UTF_8);
        List<String> command = CliCapture.codexCommand(fake.toString(), "say hello", null, "workspace-write", task, List.of());
        CliCapture.Launch launch = new CliCapture.Launch(CliCapture.Cli.CODEX, command, task, "say hello", "codex-fake-1", work.resolve("out"), null, null, home, null, null, null, null);
        CliCapture.Result r = new CliCapture(launch, options(work)).run();
        assertThat(r.state()).isEqualTo(OcsfRunWriter.State.COMPLETED);
        assertThat(r.exitCode()).isZero();
        assertThat(Files.readString(counter).lines().count()).as("the discovery command ran exactly once").isEqualTo(1);
        assertThat(r.cliVersion()).isEqualTo("0.160.1");
        assertThat(r.sessionUid()).isEqualTo(CODEX_THREAD);
        OcsfPackage p = PackageReader.open(r.packageDir().resolve("events.jsonl"));
        assertThat(p.diagnostics(Diagnostic.Severity.DEFECT)).as(p.diagnostics.toString()).isEmpty();
        assertThat(p.manifest.text("run", "cli", "version_basis")).contains("--version").contains("once per run").contains("native session file was not consulted");
        assertThat(p.manifest.compatibility("stream.jsonl").member("status").text()).isEqualTo("tested");
        assertThat(p.manifest.compatibility("stream.jsonl").member("entry_id").text()).isEqualTo("codex-cli/exec-json-stream/0.160.1");
        assertThat(r.archive().get("status").text()).isEqualTo("archived");
        assertThat(r.archive().get("located_by").text()).contains("thread id");
        assertThat(Files.readAllBytes(work.resolve("out/archive").resolve(rollout.getFileName()))).isEqualTo(Files.readAllBytes(rollout));
        OracleReport report = verify(r.packageDir(), work.resolve("out/native"));
        assertThat(report.defects()).as(report.toString()).isEmpty();
        assertThat(report.outcome("identity.values", "main/stream.jsonl")).isEqualTo(Check.Outcome.VERIFIED);
        assertThat(report.outcome("acquisition.complete", "main")).isEqualTo(Check.Outcome.VERIFIED);
    }

    @Test
    void antigravityVersionIsDiscoveredOnceAndTheConversationStoreIsArchivedByBytes(@TempDir Path work) throws Exception {
        Path task = Files.createDirectories(work.resolve("task"));
        Path home = Files.createDirectories(work.resolve("home"));
        Path counter = work.resolve("version-calls");
        Path fake = fakeAntigravity(work, nativeOf(LIVE_AGY_FEATURES, "stream.jsonl"), counter);
        Path store = home.resolve(".gemini/antigravity-cli/conversations").resolve(AGY_CONVERSATION + ".db");
        Files.createDirectories(store.getParent());
        Files.write(store, new byte[] {'S', 'Q', 'L', 'i', 't', 'e', 0, 1, 2, 3}); // opaque bytes: the store is never opened
        Files.writeString(store.resolveSibling(AGY_CONVERSATION + ".db-wal"), "wal", StandardCharsets.UTF_8);
        List<String> command = CliCapture.antigravityCommand(fake.toString(), "say hello", null, List.of("--dangerously-skip-permissions"));
        assertThat(command).containsExactly(fake.toString(), "--print", "say hello", "--output-format", "stream-json", "--dangerously-skip-permissions");
        CliCapture.Launch launch = new CliCapture.Launch(CliCapture.Cli.ANTIGRAVITY, command, task, "say hello", "agy-fake-1", work.resolve("out"), null, null, home, null, null, null, null);
        CliCapture.Result r = new CliCapture(launch, options(work)).run();
        assertThat(r.state()).isEqualTo(OcsfRunWriter.State.COMPLETED);
        assertThat(Files.readString(counter).lines().count()).as("the discovery command ran exactly once").isEqualTo(1);
        assertThat(r.cliVersion()).isEqualTo("1.4.6");
        assertThat(r.sessionUid()).isEqualTo(AGY_CONVERSATION);
        OcsfPackage p = PackageReader.open(r.packageDir().resolve("events.jsonl"));
        assertThat(p.diagnostics(Diagnostic.Severity.DEFECT)).as(p.diagnostics.toString()).isEmpty();
        assertThat(p.manifest.text("run", "cli", "version_basis")).contains("fake-agy.sh --version").contains("stream-json output reports no version");
        assertThat(p.manifest.compatibility("stream.jsonl").member("entry_id").text()).isEqualTo("antigravity-cli/stream-json/1.4.6-stdout");
        assertThat(r.archive().get("status").text()).isEqualTo("archived");
        assertThat(r.archive().get("located_by").text()).contains("conversation id");
        assertThat(Files.readAllBytes(work.resolve("out/archive").resolve(store.getFileName()))).isEqualTo(Files.readAllBytes(store));
        assertThat(r.archive().get("sidecars").elements()).extracting(x -> x.member("artifact").text()).containsExactly(AGY_CONVERSATION + ".db-wal");
        OracleReport report = verify(r.packageDir(), work.resolve("out/native"));
        assertThat(report.defects()).as(report.toString()).isEmpty();
        assertThat(report.outcome("request.values", "main/stream.jsonl")).isEqualTo(Check.Outcome.VERIFIED);
        assertThat(report.outcome("acquisition.complete", "main")).isEqualTo(Check.Outcome.VERIFIED);
    }

    @Test
    void theLaunchCommandLineRunsAStandInAndPrintsTheThreeFacts(@TempDir Path work) throws Exception {
        Path task = Files.createDirectories(work.resolve("task"));
        Path fake = fakeClaude(work, nativeOf(LIVE_FEATURES, "stream.jsonl"), 0, 0, false);
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        int exit = CliCapture.main(new String[] {"launch", work.resolve("out").toString(), "--cli", "claude", "--prompt", "hello", "--cwd", task.toString(),
                "--executable", fake.toString(), "--home", work.resolve("home").toString(), "--run-uid", "launch-test", "--model", "haiku", "--allowed-tools", "Read,Write"},
                new java.io.PrintStream(out, true, StandardCharsets.UTF_8), System.err);
        assertThat(exit).isZero();
        assertThat(out.toString(StandardCharsets.UTF_8)).contains("launch COMPLETED process exited exit 0").contains("session " + CLAUDE_SESSION).contains("cli_version 2.1.304").contains("records 50");
        OcsfPackage p = PackageReader.open(work.resolve("out/package/events.jsonl"));
        assertThat(p.manifest.text("run", "launch_settings", "model_requested")).isEqualTo("haiku");
        assertThat(p.manifest.json().member("run").member("launch_settings").member("command").elements().get(1).text()).isEqualTo("-p");
    }

    // ---- stand-ins ----------------------------------------------------------------------------------------------------

    static CliCapture.Launch launch(CliCapture.Cli cli, Path fake, Path task, Path out, Path home, Integer cancelAfterRecords, Long cancelAfterMs) {
        List<String> command = CliCapture.claudeCommand(fake.toString(), "TEST PROMPT (stand-in; no model)", null, List.of("Read", "Write"), List.of());
        return new CliCapture.Launch(cli, command, task, "TEST PROMPT (stand-in; no model)", "fake-" + out.getFileName(), out, cancelAfterRecords, cancelAfterMs, home, null,
                new Jv.Obj().put("stand_in", "TEST-ONLY: a shell script replaying a committed stream; not a live CLI"), null, null);
    }

    static WriterOptions options(Path work) {
        return WriterOptions.defaults().withClock(Clock.fixed(Instant.parse("2026-10-21T15:00:00Z"), ZoneOffset.UTC)).withAutoFlush(1);
    }

    /** A script that replays a stream file line by line ({@code delayMs} apart), optionally cutting the last line in half, then exits {@code exitCode}. */
    static Path fakeClaude(Path work, Path stream, int delayMs, int exitCode, boolean cutLast) throws IOException {
        String script = "#!/bin/bash\n"
                + "# TEST-ONLY stand-in CLI: replays a committed stream; ignores its arguments\n"
                + "trap 'exit 143' TERM\n"
                + "total=$(wc -l < '" + stream + "')\n"
                + "i=0\n"
                + "while IFS= read -r line || [ -n \"$line\" ]; do\n"
                + "  i=$((i+1))\n"
                + (cutLast ? "  if [ \"$i\" -eq \"$total\" ]; then printf '%s' \"${line:0:$(( ${#line} / 2 ))}\"; exit " + exitCode + "; fi\n" : "")
                + "  printf '%s\\n' \"$line\"\n"
                + (delayMs > 0 ? "  sleep " + (delayMs / 1000.0) + "\n" : "")
                + "done < '" + stream + "'\n"
                + "exit " + exitCode + "\n";
        return executable(work.resolve("fake-claude-" + delayMs + "-" + exitCode + "-" + cutLast + ".sh"), script);
    }

    /** A script that answers {@code --version} once per call (counted) and otherwise replays the Codex stream. */
    static Path fakeCodex(Path work, Path stream, Path counter) throws IOException {
        String script = "#!/bin/bash\n"
                + "# TEST-ONLY stand-in CLI\n"
                + "if [ \"$1\" = \"--version\" ]; then echo call >> '" + counter + "'; echo 'codex-cli 0.160.1'; exit 0; fi\n"
                + "while IFS= read -r line || [ -n \"$line\" ]; do printf '%s\\n' \"$line\"; done < '" + stream + "'\n"
                + "exit 0\n";
        return executable(work.resolve("fake-codex.sh"), script);
    }

    /** A script that answers {@code --version} with the bare number (counted) and otherwise replays an Antigravity stream. */
    static Path fakeAntigravity(Path work, Path stream, Path counter) throws IOException {
        String script = "#!/bin/bash\n"
                + "# TEST-ONLY stand-in CLI\n"
                + "if [ \"$1\" = \"--version\" ]; then echo call >> '" + counter + "'; echo '1.4.6'; exit 0; fi\n"
                + "while IFS= read -r line || [ -n \"$line\" ]; do printf '%s\\n' \"$line\"; done < '" + stream + "'\n"
                + "exit 0\n";
        return executable(work.resolve("fake-agy.sh"), script);
    }

    private static Path executable(Path file, String script) throws IOException {
        Files.writeString(file, script, StandardCharsets.UTF_8);
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rwxr-xr-x"));
        return file;
    }

    private static Path claudeSessionFile(Path home, Path task, String session) throws IOException {
        Path dir = home.resolve(".claude").resolve("projects").resolve(task.toAbsolutePath().normalize().toString().replace('/', '-'));
        Files.createDirectories(dir);
        return dir.resolve(session + ".jsonl");
    }
}
