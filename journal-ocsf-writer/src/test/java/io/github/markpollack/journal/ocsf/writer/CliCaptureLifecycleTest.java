package io.github.markpollack.journal.ocsf.writer;

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
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static io.github.markpollack.journal.ocsf.writer.WriterTestSupport.LIVE_FEATURES;
import static io.github.markpollack.journal.ocsf.writer.WriterTestSupport.nativeOf;
import static io.github.markpollack.journal.ocsf.writer.WriterTestSupport.verify;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

/**
 * Bounded completion of the launching capture ({@link CliCapture}) independent of output arrival. TEST-ONLY stand-in
 * processes, labelled as such: a silent CLI, a CLI that stalls after some output, a CLI that ignores termination and
 * keeps its standard output open, a CLI whose descendant keeps standard output open after the CLI itself ended, and
 * a version probe that never ends. Each scenario runs the capture on its own thread under an outer bound and kills
 * the stand-in itself when the capture does not return, so a failing run leaves no process behind.
 *
 * <p>These were review hypotheses at {@code 6ae8d17} (the deadline checked only after bytes arrived; force-kill
 * reached only after standard output drained; the version probe read to EOF before its timed wait). The tests
 * establish or refute each one on the unchanged code and then pin the repaired behaviour: the deadline and the
 * termination grace are enforced by the capture's own supervision, acquired bytes and incomplete tails are kept,
 * the CLI outcome, acquisition extent and storage state stay distinct, and nothing is left running.
 */
@EnabledOnOs({OS.LINUX, OS.MAC})
class CliCaptureLifecycleTest {

    static final int OUTER_BOUND_SECONDS = 20;
    /** Short bounds so each scenario ends in seconds; the production defaults are 5 s and 30 s. */
    static final CliCapture.Limits LIMITS = new CliCapture.Limits(Duration.ofSeconds(1), Duration.ofSeconds(1));
    /**
     * Holds the script alive until SIGTERM, then exits 143: the background sleep ignores SIGTERM (inherited before the
     * trap is reset), so the capture's termination of descendants cannot end the script's wait first and race its own
     * signal; the script's trap kills the sleep and exits with the conventional 128+15.
     */
    static final String HOLD_THEN_EXIT_143_ON_TERM = "trap '' TERM\nsleep 60 > /dev/null 2>&1 &\nchild=$!\ntrap 'kill -9 $child 2>/dev/null; exit 143' TERM\nwait $child\nexit 0\n";

    /** A silent CLI (no byte ever) is cancelled at the configured deadline, not when a byte happens to arrive. */
    @Test
    void aSilentCliIsCancelledAtTheDeadline(@TempDir Path work) throws Exception {
        StandIn standIn = standIn(work, "silent", HOLD_THEN_EXIT_143_ON_TERM);
        CliCapture capture = new CliCapture(launch(standIn, work, null, 400L), options());
        long t0 = System.nanoTime();
        CliCapture.Result r = runBounded(capture, standIn);
        long elapsedMs = (System.nanoTime() - t0) / 1_000_000;

        assertThat(r.state()).isEqualTo(OcsfRunWriter.State.INTERRUPTED);
        assertThat(r.how()).isEqualTo("cancelled");
        assertThat(r.exitCode()).as("SIGTERM honoured by the stand-in: 128+15").isEqualTo(143);
        assertThat(r.process().get("cancel_reason").text()).isEqualTo("deliberate cancellation after 400 ms");
        assertThat(r.records()).isZero();
        assertThat(r.bytes()).isZero();
        assertThat(Files.size(r.independentCopy())).isZero();
        assertThat(elapsedMs).as("cancelled at the deadline, well inside the outer bound").isLessThan(OUTER_BOUND_SECONDS * 1000L / 2);
        assertThat(Files.readString(r.expectedSources())).contains("\"complete\":false").contains("\"records\":0");
        OcsfPackage p = PackageReader.open(r.packageDir().resolve("events.jsonl"));
        assertThat(p.manifest.packageState()).isEqualTo("INTERRUPTED");
        assertThat(p.manifest.text("recording", "interruption_reason")).isEqualTo("deliberate cancellation after 400 ms");
        assertThat(p.manifest.source("stream.jsonl").path("ended", "how").text()).isEqualTo("INTERRUPTED");
        assertThat(p.diagnosticsWithCode("CLI_CANCELLED")).hasSize(1);
        standIn.assertNothingLeftRunning();
    }

    /** Output, then a stall: the deadline still fires, the acquired prefix is kept and judged incomplete. */
    @Test
    void outputThenAStallIsCancelledAtTheDeadlineWithThePrefixKept(@TempDir Path work) throws Exception {
        StandIn standIn = standIn(work, "stall", "head -n 3 '" + stream() + "'\n" + HOLD_THEN_EXIT_143_ON_TERM);
        CliCapture capture = new CliCapture(launch(standIn, work, null, 600L), options());
        CliCapture.Result r = runBounded(capture, standIn);

        assertThat(r.state()).isEqualTo(OcsfRunWriter.State.INTERRUPTED);
        assertThat(r.how()).isEqualTo("cancelled");
        assertThat(r.exitCode()).isEqualTo(143);
        assertThat(r.process().get("cancel_reason").text()).isEqualTo("deliberate cancellation after 600 ms");
        assertThat(r.records()).isEqualTo(3);
        byte[] firstThree = firstLines(3);
        assertThat(r.bytes()).isEqualTo(firstThree.length);
        assertThat(Files.readAllBytes(r.independentCopy())).as("every acquired byte, nothing invented").isEqualTo(firstThree);
        OcsfPackage p = PackageReader.open(r.packageDir().resolve("events.jsonl"));
        assertThat(p.manifest.packageState()).isEqualTo("INTERRUPTED");
        assertThat(p.manifest.cliVersion()).as("the stream reported its version before the stall").isEqualTo("2.1.304");
        OracleReport report = verify(r.packageDir(), work.resolve("out/native"));
        assertThat(report.defects()).as(report.toString()).isEmpty();
        assertThat(report.outcome("acquisition.complete", "main")).isEqualTo(Check.Outcome.UNSUPPORTED);
        assertThat(report.outcome("source.inventory", "main/stream.jsonl")).as("the prefix is accounted against the frozen copy").isEqualTo(Check.Outcome.VERIFIED);
        standIn.assertNothingLeftRunning();
    }

    /** A CLI that ignores SIGTERM and keeps standard output open is killed after the grace period; the prefix is kept. */
    @Test
    void aCliThatIgnoresTerminationAndKeepsStdoutOpenIsKilledAfterTheGrace(@TempDir Path work) throws Exception {
        StandIn standIn = standIn(work, "ignores-term", "trap '' TERM\nhead -n 3 '" + stream() + "'\nexec sleep 60\n");
        CliCapture capture = new CliCapture(launch(standIn, work, 3, null), options());
        long t0 = System.nanoTime();
        CliCapture.Result r = runBounded(capture, standIn);
        long elapsedMs = (System.nanoTime() - t0) / 1_000_000;

        assertThat(r.state()).isEqualTo(OcsfRunWriter.State.INTERRUPTED);
        assertThat(r.how()).isEqualTo("cancelled");
        assertThat(r.exitCode()).as("SIGKILL after the grace: 128+9").isEqualTo(137);
        assertThat(r.process().get("cancel_reason").text()).contains("deliberate cancellation after 3 record(s)");
        assertThat(r.process().get("exit_meaning").text()).contains("terminated by signal 9");
        assertThat(r.process().get("killed").text()).contains("SIGKILL after the 1000 ms grace period");
        assertThat(r.process().get("stdout").text()).isEqualTo("closed: EOF reached");
        assertThat(r.records()).isEqualTo(3);
        assertThat(Files.readAllBytes(r.independentCopy())).isEqualTo(firstLines(3));
        assertThat(elapsedMs).as("ended once the grace period passed").isGreaterThanOrEqualTo(LIMITS.terminationGrace().toMillis()).isLessThan(OUTER_BOUND_SECONDS * 1000L / 2);
        OcsfPackage p = PackageReader.open(r.packageDir().resolve("events.jsonl"));
        assertThat(p.manifest.packageState()).isEqualTo("INTERRUPTED");
        assertThat(p.diagnosticsWithCode("CLI_CANCELLED")).hasSize(1);
        OracleReport report = verify(r.packageDir(), work.resolve("out/native"));
        assertThat(report.defects()).as(report.toString()).isEmpty();
        assertThat(report.outcome("acquisition.complete", "main")).isEqualTo(Check.Outcome.UNSUPPORTED);
        standIn.assertNothingLeftRunning();
    }

    /** The CLI honours SIGTERM but a descendant that ignores it keeps standard output open: the descendant is killed, the capture ends. */
    @Test
    void aDescendantKeepingStdoutOpenAfterTheCliEndedIsKilledAndTheCaptureEnds(@TempDir Path work) throws Exception {
        Path orphanPid = work.resolve("orphan.pid");
        StandIn standIn = standIn(work, "orphan", "trap '' TERM\nsleep 60 &\necho $! > '" + orphanPid + "'\ntrap 'exit 143' TERM\nhead -n 3 '" + stream() + "'\nwait\nexit 0\n");
        standIn.alsoWatch(orphanPid);
        CliCapture capture = new CliCapture(launch(standIn, work, 3, null), options());
        CliCapture.Result r = runBounded(capture, standIn);

        assertThat(r.state()).isEqualTo(OcsfRunWriter.State.INTERRUPTED);
        assertThat(r.how()).isEqualTo("cancelled");
        assertThat(r.exitCode()).as("the CLI itself honoured SIGTERM").isEqualTo(143);
        assertThat(r.process().get("killed")).as("the CLI itself was not killed").isNull();
        assertThat(r.process().get("descendants_killed").text()).isEqualTo("1");
        assertThat(r.process().get("descendants_detail").text()).contains("still held standard output open");
        assertThat(r.process().get("stdout").text()).as("the pipe closed once the descendant was killed").isEqualTo("closed: EOF reached");
        assertThat(r.records()).isEqualTo(3);
        assertThat(Files.readAllBytes(r.independentCopy())).isEqualTo(firstLines(3));
        OcsfPackage p = PackageReader.open(r.packageDir().resolve("events.jsonl"));
        assertThat(p.manifest.packageState()).isEqualTo("INTERRUPTED");
        standIn.assertNothingLeftRunning();
    }

    /** A version probe that never ends is bounded: the capture fails explicitly and the probe is not left running. */
    @Test
    void aHangingVersionProbeIsBoundedAndReported(@TempDir Path work) throws Exception {
        StandIn standIn = standIn(work, "hanging-probe", "if [ \"$1\" = \"--version\" ]; then exec sleep 60; fi\nexit 0\n");
        Path task = Files.createDirectories(work.resolve("task"));
        List<String> command = CliCapture.codexCommand(standIn.script.toString(), "TEST PROMPT (stand-in; no model)", null, null, task, List.of());
        CliCapture.Launch launch = new CliCapture.Launch(CliCapture.Cli.CODEX, command, task, "TEST PROMPT (stand-in; no model)", "probe-hang", work.resolve("out"), null, null,
                work.resolve("home"), null, new Jv.Obj().put("stand_in", "TEST-ONLY: a shell script whose --version never ends; not a live CLI"), LIMITS);
        CliCapture capture = new CliCapture(launch, options());
        CompletableFuture<Throwable> failure = CompletableFuture.supplyAsync(() -> {
            try {
                capture.run();
                return null;
            }
            catch (Throwable t) {
                return t;
            }
        });
        Throwable t;
        try {
            t = failure.get(OUTER_BOUND_SECONDS, TimeUnit.SECONDS);
        }
        catch (TimeoutException e) {
            standIn.killEverything();
            throw new AssertionError("the version probe hung for " + OUTER_BOUND_SECONDS + " s and the capture did not bound it (stand-in killed by the test)");
        }
        assertThat(t).as("an explicit failure, not a silent wait").isInstanceOf(IOException.class);
        assertThat(t.getMessage()).contains("--version").contains("did not end within");
        standIn.assertNothingLeftRunning();
    }

    /** A CLI that closes its standard output but never exits is terminated after the grace; the complete stream is kept, nothing is presumed. */
    @Test
    void aCliThatClosesStdoutButDoesNotExitIsTerminatedAfterTheGrace(@TempDir Path work) throws Exception {
        StandIn standIn = standIn(work, "lingers", "cat '" + stream() + "'\nexec 1>&-\n" + HOLD_THEN_EXIT_143_ON_TERM);
        CliCapture.Result r = runBounded(new CliCapture(launch(standIn, work, null, null), options()), standIn);
        assertThat(r.state()).as("the stream ended but the CLI did not: not presented as a completed run").isEqualTo(OcsfRunWriter.State.INTERRUPTED);
        assertThat(r.how()).isEqualTo("cancelled");
        assertThat(r.exitCode()).isEqualTo(143);
        assertThat(r.process().get("cancel_reason").text()).isEqualTo("the CLI did not exit within 1000 ms after its standard output ended");
        assertThat(r.records()).isEqualTo(50);
        assertThat(Files.readAllBytes(r.independentCopy())).isEqualTo(Files.readAllBytes(stream()));
        standIn.assertNothingLeftRunning();
    }

    /** Control: a deadline that is not reached changes nothing about a normal completion. */
    @Test
    void aDeadlineThatIsNotReachedLeavesANormalCompletionUntouched(@TempDir Path work) throws Exception {
        StandIn standIn = standIn(work, "control", "cat '" + stream() + "'\nexit 0\n");
        CliCapture.Result r = runBounded(new CliCapture(launch(standIn, work, null, 60_000L), options()), standIn);
        assertThat(r.state()).isEqualTo(OcsfRunWriter.State.COMPLETED);
        assertThat(r.how()).isEqualTo("exited");
        assertThat(r.exitCode()).isZero();
        assertThat(r.records()).isEqualTo(50);
        assertThat(r.process().get("cancel_reason")).isNull();
        assertThat(Files.readAllBytes(r.independentCopy())).isEqualTo(Files.readAllBytes(stream()));
        OracleReport report = verify(r.packageDir(), work.resolve("out/native"));
        assertThat(report.defects()).as(report.toString()).isEmpty();
        assertThat(report.outcome("acquisition.complete", "main")).isEqualTo(Check.Outcome.VERIFIED);
        standIn.assertNothingLeftRunning();
    }

    // ---- stand-ins ----------------------------------------------------------------------------------------------------

    static Path stream() {
        return nativeOf(LIVE_FEATURES, "stream.jsonl");
    }

    static byte[] firstLines(int n) throws IOException {
        List<String> lines = Files.readAllLines(stream(), StandardCharsets.UTF_8);
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < n; i++) {
            b.append(lines.get(i)).append('\n');
        }
        return b.toString().getBytes(StandardCharsets.UTF_8);
    }

    static WriterOptions options() {
        return WriterOptions.defaults().withClock(Clock.fixed(Instant.parse("2026-10-21T15:00:00Z"), ZoneOffset.UTC)).withAutoFlush(1);
    }

    static CliCapture.Launch launch(StandIn standIn, Path work, Integer cancelAfterRecords, Long cancelAfterMs) throws IOException {
        Path task = Files.createDirectories(work.resolve("task"));
        List<String> command = CliCapture.claudeCommand(standIn.script.toString(), "TEST PROMPT (stand-in; no model)", null, List.of("Read", "Write"), List.of());
        return new CliCapture.Launch(CliCapture.Cli.CLAUDE, command, task, "TEST PROMPT (stand-in; no model)", "lifecycle-" + standIn.name, work.resolve("out"), cancelAfterRecords, cancelAfterMs,
                work.resolve("home"), null, new Jv.Obj().put("stand_in", "TEST-ONLY: a shell script (" + standIn.name + "); not a live CLI"), LIMITS);
    }

    /** Runs the capture on its own thread under the outer bound; on timeout kills the stand-in and fails. */
    static CliCapture.Result runBounded(CliCapture capture, StandIn standIn) throws Exception {
        CompletableFuture<CliCapture.Result> running = CompletableFuture.supplyAsync(() -> {
            try {
                return capture.run();
            }
            catch (IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
        });
        try {
            return running.get(OUTER_BOUND_SECONDS, TimeUnit.SECONDS);
        }
        catch (TimeoutException e) {
            standIn.killEverything();
            return fail("the capture did not return within " + OUTER_BOUND_SECONDS + " s for stand-in '" + standIn.name
                    + "': the deadline or termination was not enforced independently of output arrival (stand-in killed by the test)");
        }
        catch (ExecutionException e) {
            throw e.getCause() instanceof Exception cause ? cause : e;
        }
    }

    /** A TEST-ONLY stand-in script that records its own pid first, so the test can always find and kill it. */
    static StandIn standIn(Path work, String name, String body) throws IOException {
        Path pid = work.resolve(name + ".pid");
        Path script = work.resolve("stand-in-" + name + ".sh");
        Files.writeString(script, "#!/bin/bash\n# TEST-ONLY stand-in CLI (" + name + "); ignores its arguments unless stated\necho $$ > '" + pid + "'\n" + body, StandardCharsets.UTF_8);
        Files.setPosixFilePermissions(script, PosixFilePermissions.fromString("rwxr-xr-x"));
        return new StandIn(name, script, pid);
    }

    static final class StandIn {
        final String name;
        final Path script;
        final List<Path> pidFiles = new ArrayList<>();

        StandIn(String name, Path script, Path pid) {
            this.name = name;
            this.script = script;
            this.pidFiles.add(pid);
        }

        void alsoWatch(Path pidFile) {
            pidFiles.add(pidFile);
        }

        List<ProcessHandle> alive() {
            List<ProcessHandle> alive = new ArrayList<>();
            for (Path f : pidFiles) {
                if (!Files.exists(f)) {
                    continue;
                }
                try {
                    String text = Files.readString(f).strip();
                    if (text.isEmpty()) {
                        continue;
                    }
                    Optional<ProcessHandle> h = ProcessHandle.of(Long.parseLong(text));
                    if (h.isPresent() && h.get().isAlive()) {
                        alive.add(h.get());
                    }
                }
                catch (IOException | NumberFormatException e) {
                    // the stand-in had not written its pid yet
                }
            }
            return alive;
        }

        void killEverything() {
            for (ProcessHandle h : alive()) {
                h.descendants().forEach(ProcessHandle::destroyForcibly);
                h.destroyForcibly();
            }
        }

        void assertNothingLeftRunning() throws InterruptedException {
            // the kernel reaps asynchronously; allow a moment for the handle to disappear
            for (int i = 0; i < 50 && !alive().isEmpty(); i++) {
                Thread.sleep(20);
            }
            List<ProcessHandle> alive = alive();
            if (!alive.isEmpty()) {
                killEverything();
                fail("stand-in '" + name + "' left processes running: " + alive.stream().map(ProcessHandle::pid).toList());
            }
        }
    }
}
