package io.github.markpollack.journal.ocsf.writer;

import java.util.Locale;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * OPT-IN capture integration: launches a CLI's streaming invocation itself, reads its raw standard output into the
 * one incremental writer (an independent byte-for-byte copy is written before the writer sees each chunk), carries
 * the process outcome, cancellation, input failure and persistence failure into the recording lifecycle, archives the
 * CLI's native session file unchanged beside the package, and freezes the expected-source manifest from the
 * independent copy after acquisition. Defaults of the production recorders are untouched; this is the intended
 * stream-first path for Claude Code and Codex behind an explicit launch.
 *
 * <p>Three facts stay distinct in the result and in the package: storage finalization ({@code COMPLETED},
 * {@code INTERRUPTED}, {@code FAILED}), acquisition extent (every byte to EOF, or an interrupted prefix, with the
 * incomplete tail retained) and the CLI outcome ({@code exited} with its exit code, {@code cancelled} by this capture,
 * {@code input_failure}, {@code writer_failure}). Reaching EOF never manufactures CLI success: a CLI that exits
 * non-zero after a complete stream is a completely acquired failed run.
 *
 * <p>Version policy (RULE-7, RULE-8): Claude Code's stream reports its version, so none is given at launch and no
 * version command runs; Codex's {@code exec --json} stream reports none, so the documented discovery path
 * {@code codex --version} runs exactly once per run before launch and its basis is declared. The native session
 * file is located by the session id the stream reported and copied by bytes with its hash; it is never opened,
 * parsed or used for enrichment or version discovery (RULE-4, RULE-5).
 */
public final class CliCapture {

    public enum Cli {
        CLAUDE("Claude Code", "Anthropic", "claude_stream"),
        CODEX("Codex CLI", "OpenAI", "codex_stream");

        final String product;
        final String vendor;
        final String profile;

        Cli(String product, String vendor, String profile) {
            this.product = product;
            this.vendor = vendor;
            this.profile = profile;
        }
    }

    /**
     * What to launch and how to record it.
     *
     * @param cli the CLI
     * @param command the full command line (executable first); see {@link #claudeCommand} and {@link #codexCommand}
     * @param workDir the CLI's working directory (an isolated task directory)
     * @param prompt the caller-supplied prompt, recorded with caller provenance (never as a vendor-reported fact), or {@code null}
     * @param runUid the Journal run id
     * @param out the package directory to create (must not exist)
     * @param cancelAfterRecords cancel the CLI once this many records arrived (a deliberate interruption), or {@code null}
     * @param cancelAfterMillis cancel the CLI once this long has passed since launch, or {@code null}
     * @param home the home directory whose native session files are archived (normally the user's; a test's own)
     * @param versionCommand for Codex, the once-per-run discovery command (default {@code [<executable>, --version]}); ignored for Claude
     * @param launchNotes free launch settings the caller records (model, allowed tools, route label), or {@code null}
     * @param limits the capture's own waiting bounds, or {@code null} for {@link Limits#DEFAULTS}
     */
    public record Launch(Cli cli, List<String> command, Path workDir, String prompt, String runUid, Path out, Integer cancelAfterRecords, Long cancelAfterMillis,
            Path home, List<String> versionCommand, Jv.Obj launchNotes, Limits limits) {
    }

    /**
     * How long the capture itself waits, on its own monotonic clock and independently of output arrival: the grace
     * after a termination request before the CLI is killed (also the wait for standard output to close after the CLI
     * ended before its surviving descendants are killed, and the wait for the CLI to exit after its output ended),
     * and the bound on the once-per-run version probe.
     */
    public record Limits(Duration terminationGrace, Duration versionProbeTimeout) {
        public static final Limits DEFAULTS = new Limits(Duration.ofSeconds(5), Duration.ofSeconds(30));
    }

    /** What happened: the three distinct facts plus where everything is. */
    public record Result(OcsfRunWriter.State state, Jv.Obj process, Jv.Obj archive, Path packageDir, Path independentCopy, Path expectedSources, int records, long bytes,
            String sessionUid, String cliVersion, Path stderr) {
        public Integer exitCode() {
            return process.get("exit_code") instanceof Jv.Num n ? (int) n.value().longValueExact() : null;
        }

        public String how() {
            return process.get("how").text();
        }
    }

    static final Pattern CODEX_VERSION = Pattern.compile("codex(?:-cli)?\\s+v?(\\d+(?:\\.\\d+)+)");
    /** How often supervision looks at the clock and the process while standard output is open. */
    static final long POLL_MILLIS = 20;
    /** How often the CLI's descendants are noted while it is alive, so they can be cleaned up once it is gone. */
    static final long DESCENDANT_SCAN_MILLIS = 250;

    private final Launch launch;
    private final Limits limits;
    private final WriterOptions options;
    private final AtomicReference<String> cancelRequested = new AtomicReference<>();
    /** Every descendant seen while the CLI was alive: a descendant that outlives the CLI is no longer reachable through it. */
    private final Set<ProcessHandle> seenDescendants = ConcurrentHashMap.newKeySet();
    private volatile Process process;

    public CliCapture(Launch launch, WriterOptions options) {
        this.launch = launch;
        this.limits = launch.limits() != null ? launch.limits() : Limits.DEFAULTS;
        this.options = options;
    }

    /** Requests cancellation from any thread: the CLI is terminated (then killed after the grace period) and the recording is interrupted with this reason. */
    public void cancel(String reason) {
        if (cancelRequested.compareAndSet(null, reason)) {
            Process p = process;
            if (p != null) {
                terminate(p);
            }
        }
    }

    /** Terminates through the process handle: {@code Process.destroy()} would also close the stdout pipe this capture is still draining. */
    private void terminate(Process p) {
        p.descendants().forEach(d -> {
            seenDescendants.add(d);
            d.destroy();
        });
        p.toHandle().destroy();
    }

    /** Kills the CLI and every descendant seen, including those the CLI's exit already orphaned. */
    private void kill(Process p) {
        p.descendants().forEach(seenDescendants::add);
        seenDescendants.forEach(ProcessHandle::destroyForcibly);
        p.toHandle().destroyForcibly();
    }

    /** Kills the descendants that outlived the CLI; returns how many were still alive. */
    private int killSurvivingDescendants() {
        int n = 0;
        for (ProcessHandle h : seenDescendants) {
            if (h.isAlive()) {
                h.destroyForcibly();
                n++;
            }
        }
        return n;
    }

    /** The Claude Code streaming invocation: no version argument, no version command (the stream reports it). */
    public static List<String> claudeCommand(String executable, String prompt, String model, List<String> allowedTools, List<String> extra) {
        List<String> c = new ArrayList<>(List.of(executable, "-p", prompt, "--output-format", "stream-json", "--verbose"));
        if (model != null) {
            c.add("--model");
            c.add(model);
        }
        if (allowedTools != null && !allowedTools.isEmpty()) {
            c.add("--allowedTools");
            c.addAll(allowedTools);
        }
        c.addAll(extra == null ? List.of() : extra);
        return c;
    }

    /** The Codex streaming invocation. */
    public static List<String> codexCommand(String executable, String prompt, String model, String sandbox, Path workDir, List<String> extra) {
        List<String> c = new ArrayList<>(List.of(executable, "exec", "--json", "--skip-git-repo-check"));
        if (sandbox != null) {
            c.add("-s");
            c.add(sandbox);
        }
        if (model != null) {
            c.add("-m");
            c.add(model);
        }
        if (workDir != null) {
            c.add("-C");
            c.add(workDir.toString());
        }
        c.addAll(extra == null ? List.of() : extra);
        c.add(prompt);
        return c;
    }

    /** Runs the capture to its end and returns the three distinct facts. */
    public Result run() throws IOException {
        Clock clock = options.clock();
        Path out = launch.out();
        if (Files.exists(out)) {
            throw new IOException("out directory exists: " + out);
        }
        Files.createDirectories(out.resolve("native"));
        Files.createDirectories(out.resolve("archive"));
        Path copyFile = out.resolve("native").resolve("stream.jsonl");
        Path stderrFile = out.resolve("native").resolve("stderr.txt");

        // version: Claude's stream reports it (none at launch); Codex's does not: the documented discovery path runs once
        String version = null;
        String versionBasis = null;
        if (launch.cli() == Cli.CODEX) {
            List<String> vc = launch.versionCommand() != null ? launch.versionCommand() : List.of(launch.command().get(0), "--version");
            version = discoverVersion(vc, limits.versionProbeTimeout());
            versionBasis = "discovered once per run by the documented adapter path `" + String.join(" ", vc)
                    + "` before launch, because the exec --json stream reports no version; the native session file was not consulted";
        }
        Instant captureStart = clock.instant();
        Jv.Obj settings = new Jv.Obj().put("command", Jv.arr(launch.command().toArray(String[]::new))).put("cwd", launch.workDir().toString())
                .put("route", "stdout").put("surface", "the CLI's raw standard output read by this capture process (no SDK stage); an independent byte copy is written before the writer sees each chunk");
        if (launch.prompt() != null) {
            settings.put("caller_input", new Jv.Obj().put("prompt", launch.prompt())
                    .put("provenance", "caller-supplied: the prompt text this capture passed on the command line; recorded by Journal, not reported by the CLI, and not part of the selected stream's record denominator"));
        }
        if (launch.launchNotes() != null) {
            for (Map.Entry<String, Jv> e : launch.launchNotes().members().entrySet()) {
                settings.put(e.getKey(), e.getValue());
            }
        }
        RunDescriptor run = new RunDescriptor(launch.runUid(), 0, null, captureStart,
                new RunDescriptor.Cli(launch.cli().product, version, launch.cli().vendor, 1, "Native", versionBasis), settings, null);

        OcsfRunWriter.State state;
        Jv.Obj processOutcome;
        Jv.Obj archive = null;
        int records = 0;
        long bytes = 0;
        String sessionUid = null;
        String cliVersion = version;
        Jv.Obj compatibilityInForce = null;
        try (OcsfRunWriter w = OcsfRunWriter.open(out.resolve("package"), run, SchemaPin.bundled(), options)) {
            SourceInput in = w.source(new SourceDescriptor("stream.jsonl", 1, launch.cli().profile, "stdout",
                    "live CLI standard output read by the launching capture (raw bytes, no SDK stage); independent copy native/stream.jsonl", "stdout of the launched process"));
            w.flush();
            ProcessBuilder pb = new ProcessBuilder(launch.command()).directory(launch.workDir().toFile())
                    .redirectError(stderrFile.toFile()).redirectInput(ProcessBuilder.Redirect.from(devNull()));
            Instant started = clock.instant();
            Process p = pb.start();
            process = p;
            if (cancelRequested.get() != null) {
                terminate(p);
            }
            Pump pump;
            Supervision supervision;
            try (OutputStream copy = Files.newOutputStream(copyFile)) {
                // standard output is read on its own thread; this thread supervises on its own clock, never waiting on bytes
                pump = new Pump(p.getInputStream(), copy, in, clock);
                Thread reader = new Thread(pump, "cli-capture-stdout-" + p.pid());
                reader.setDaemon(true);
                reader.start();
                supervision = supervise(p, pump);
            }
            Integer exit = waitFor(p, supervision);
            Instant ended = clock.instant();
            String cancelled = cancelRequested.get();
            bytes = pump.bytes();
            records = pump.records();
            RuntimeException writerFailure = pump.writerFailure();
            IOException inputFailure = pump.inputFailure();
            if (inputFailure != null) {
                cancel("input failure: " + inputFailure);
                cancelled = cancelRequested.get();
            }
            processOutcome = new Jv.Obj().put("pid", p.pid()).put("started", started.toString()).put("ended", ended.toString());
            if (exit == null) {
                processOutcome.put("how", "unknown").put("exit_code", Jv.Null.INSTANCE).put("detail", "the process did not end within the grace period after it was killed");
            }
            else if (writerFailure != null) {
                processOutcome.put("how", "writer_failure").put("exit_code", exit).put("exit_meaning", exitMeaning(exit)).put("cancel_reason", cancelled)
                        .put("detail", "the writer failed while recording; the CLI was stopped and its remaining output went to the independent copy only");
            }
            else if (inputFailure != null) {
                processOutcome.put("how", "input_failure").put("exit_code", exit).put("exit_meaning", exitMeaning(exit)).put("detail", "reading the CLI's standard output failed: " + inputFailure);
            }
            else if (cancelled != null) {
                processOutcome.put("how", "cancelled").put("exit_code", exit).put("exit_meaning", exitMeaning(exit)).put("cancel_reason", cancelled);
            }
            else {
                processOutcome.put("how", "exited").put("exit_code", exit).put("exit_meaning", exitMeaning(exit));
            }
            if (supervision.killed) {
                processOutcome.put("killed", "SIGKILL after the " + limits.terminationGrace().toMillis() + " ms grace period: the CLI had not ended after termination was requested");
            }
            if (supervision.descendantsKilled > 0) {
                processOutcome.put("descendants_killed", supervision.descendantsKilled)
                        .put("descendants_detail", supervision.descendantsKilled + " descendant process(es) still held standard output open " + limits.terminationGrace().toMillis()
                                + " ms after the CLI ended and were killed; their output up to that point is in the independent copy");
            }
            processOutcome.put("stdout", supervision.stdoutEnd)
                    .put("stderr_bytes", Files.exists(stderrFile) ? Files.size(stderrFile) : 0L)
                    .put("meaning", "the CLI process outcome; distinct from storage finalization (recording.state) and acquisition extent (acquisition.status): EOF never implies success");
            sessionUid = w.sessionUid();
            cliVersion = w.knownCliVersion();
            List<Jv.Obj> declared = w.compatibilityDeclarations("stream.jsonl");
            compatibilityInForce = declared.isEmpty() ? null : declared.get(declared.size() - 1);
            if (w.state() == OcsfRunWriter.State.RECORDING) {
                archive = archiveNativeSessionFile(out.resolve("archive"), sessionUid);
                w.archive(archive);
                w.processOutcome(processOutcome);
                if (cancelled != null || inputFailure != null) {
                    state = w.interrupt(cancelled != null ? cancelled : "input failure");
                }
                else if (!pump.eof()) {
                    state = w.interrupt("standard output did not end: " + supervision.stdoutEnd);
                }
                else {
                    in.end(SourceInput.End.EOF, ended);
                    state = w.complete();
                }
            }
            else {
                state = w.state();
                archive = archiveNativeSessionFile(out.resolve("archive"), sessionUid);
            }
        }
        finally {
            process = null;
        }
        Files.writeString(out.resolve("archive").resolve("archive.json"), Jv.write(archive == null ? new Jv.Obj().put("status", "not attempted") : archive) + "\n", StandardCharsets.UTF_8);
        boolean completeRun = state == OcsfRunWriter.State.COMPLETED && "exited".equals(processOutcome.get("how").text());
        Path expected = freezeExpectedSources(out.resolve("native"), copyFile, launch.runUid(), launch.cli().profile, completeRun, processOutcome, compatibilityInForce);
        return new Result(state, processOutcome, archive, out.resolve("package"), copyFile, expected, records, bytes, sessionUid, cliVersion, stderrFile);
    }

    private static java.io.File devNull() {
        return new java.io.File(System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win") ? "NUL" : "/dev/null");
    }

    /** What supervision established while standard output was open. */
    private static final class Supervision {
        boolean killed;
        int descendantsKilled;
        String stdoutEnd = "closed: EOF reached";
    }

    /**
     * Waits for standard output to end while enforcing, on this capture's monotonic clock and independently of
     * output arrival: the deadline ({@code cancelAfterMillis}); the termination grace, after which a CLI that ignored
     * termination is killed; and, once the CLI itself has ended, the grace for standard output to close before the
     * descendants that outlived the CLI are killed and, failing that, before the open pipe is abandoned.
     */
    private Supervision supervise(Process p, Pump pump) {
        Supervision s = new Supervision();
        long grace = limits.terminationGrace().toNanos();
        long launched = System.nanoTime();
        Long deadline = launch.cancelAfterMillis() == null ? null : launched + TimeUnit.MILLISECONDS.toNanos(launch.cancelAfterMillis());
        long terminatedAt = -1, exitedAt = -1, descendantsKilledAt = -1, scannedAt = -1;
        try {
            while (!pump.awaitEnd(POLL_MILLIS)) {
                long now = System.nanoTime();
                boolean alive = p.isAlive();
                if (alive && (scannedAt < 0 || now - scannedAt >= TimeUnit.MILLISECONDS.toNanos(DESCENDANT_SCAN_MILLIS))) {
                    p.descendants().forEach(seenDescendants::add);
                    scannedAt = now;
                }
                if (cancelRequested.get() == null && deadline != null && now >= deadline) {
                    cancel("deliberate cancellation after " + launch.cancelAfterMillis() + " ms");
                }
                if (cancelRequested.get() != null && terminatedAt < 0) {
                    terminatedAt = now;
                }
                if (!alive && exitedAt < 0) {
                    exitedAt = now;
                }
                if (alive && terminatedAt >= 0 && !s.killed && now - terminatedAt >= grace) {
                    kill(p);
                    s.killed = true;
                }
                if (!alive && now - exitedAt >= grace) {
                    if (descendantsKilledAt < 0) {
                        s.descendantsKilled = killSurvivingDescendants();
                        descendantsKilledAt = now;
                    }
                    else if (now - descendantsKilledAt >= grace) {
                        pump.abandon();
                        s.stdoutEnd = "left open: standard output had not closed " + 2 * limits.terminationGrace().toMillis()
                                + " ms after the CLI ended, even after its surviving descendants were killed; acquisition stopped here and the open pipe was abandoned";
                        break;
                    }
                }
            }
        }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            kill(p);
            s.killed = true;
            pump.abandon();
            s.stdoutEnd = "left open: the capture thread was interrupted; the CLI was killed and acquisition stopped here";
        }
        return s;
    }

    /**
     * Waits for the CLI to exit once its standard output has ended: within the grace normally; a CLI that stays
     * alive after its output ended is terminated, then killed; {@code null} if it still would not end.
     */
    private Integer waitFor(Process p, Supervision s) {
        long graceMs = limits.terminationGrace().toMillis();
        try {
            if (p.waitFor(graceMs, TimeUnit.MILLISECONDS)) {
                return p.exitValue();
            }
            cancel("the CLI did not exit within " + graceMs + " ms after its standard output ended");
            if (p.waitFor(graceMs, TimeUnit.MILLISECONDS)) {
                return p.exitValue();
            }
            kill(p);
            s.killed = true;
            return p.waitFor(graceMs, TimeUnit.MILLISECONDS) ? p.exitValue() : null;
        }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            kill(p);
            s.killed = true;
            return null;
        }
    }

    /**
     * Reads the CLI's standard output on its own thread: every chunk goes to the independent copy first, then to the
     * writer; record-count cancellation and writer failure are noticed here, where the counts are known. Once
     * abandoned, a chunk in flight finishes and anything read later is dropped, so the capture can finalize while
     * an orphaned writer still holds the pipe open.
     */
    private final class Pump implements Runnable {
        private final InputStream stdout;
        private final OutputStream copy;
        private final SourceInput in;
        private final Clock clock;
        private final CountDownLatch ended = new CountDownLatch(1);
        private final Object lock = new Object();
        private boolean abandoned;
        private boolean eof;
        private long bytes;
        private int records;
        private RuntimeException writerFailure;
        private IOException inputFailure;

        Pump(InputStream stdout, OutputStream copy, SourceInput in, Clock clock) {
            this.stdout = stdout;
            this.copy = copy;
            this.in = in;
            this.clock = clock;
        }

        @Override
        public void run() {
            byte[] buf = new byte[8192];
            try {
                int n;
                while ((n = stdout.read(buf)) > 0) {
                    synchronized (lock) {
                        if (abandoned) {
                            return;
                        }
                        accept(buf, n);
                    }
                }
                synchronized (lock) {
                    eof = !abandoned;
                }
            }
            catch (IOException e) {
                synchronized (lock) {
                    if (!abandoned) {
                        inputFailure = e;
                    }
                }
            }
            finally {
                ended.countDown();
            }
        }

        private void accept(byte[] buf, int n) throws IOException {
            copy.write(buf, 0, n);
            copy.flush();
            bytes += n;
            Instant arrival = clock.instant();
            if (writerFailure == null) {
                try {
                    in.bytes(buf, 0, n, arrival);
                    records = in.records();
                }
                catch (RuntimeException e) {
                    // the writer failed (persistence): keep draining into the independent copy, stop the CLI
                    writerFailure = e;
                    cancel("writer failed: " + e.getMessage());
                }
            }
            if (cancelRequested.get() == null && launch.cancelAfterRecords() != null && records >= launch.cancelAfterRecords()) {
                cancel("deliberate cancellation after " + records + " record(s) (requested after " + launch.cancelAfterRecords() + ")");
            }
        }

        boolean awaitEnd(long millis) throws InterruptedException {
            return ended.await(millis, TimeUnit.MILLISECONDS);
        }

        void abandon() {
            synchronized (lock) {
                abandoned = true;
            }
        }

        boolean eof() {
            synchronized (lock) {
                return eof;
            }
        }

        long bytes() {
            synchronized (lock) {
                return bytes;
            }
        }

        int records() {
            synchronized (lock) {
                return records;
            }
        }

        RuntimeException writerFailure() {
            synchronized (lock) {
                return writerFailure;
            }
        }

        IOException inputFailure() {
            synchronized (lock) {
                return inputFailure;
            }
        }
    }

    static String exitMeaning(int exit) {
        if (exit == 0) {
            return "0: the CLI reported success";
        }
        if (exit > 128 && exit < 160) {
            return exit + " (128+" + (exit - 128) + "): terminated by signal " + (exit - 128) + " under the usual shell convention (Java reports the code, not the signal)";
        }
        return exit + ": the CLI reported failure; whether its stream was completely acquired is judged separately";
    }

    /**
     * Runs the discovery command once, bounded, and parses its version; the command's own output is the basis. A
     * probe that has not ended within the bound is killed and reported as a failure, never waited for.
     */
    static String discoverVersion(List<String> command, Duration timeout) throws IOException {
        ProcessBuilder pb = new ProcessBuilder(command).redirectErrorStream(true).redirectInput(ProcessBuilder.Redirect.from(devNull()));
        Process p = pb.start();
        java.io.ByteArrayOutputStream collected = new java.io.ByteArrayOutputStream();
        Thread reader = new Thread(() -> {
            try (InputStream in = p.getInputStream()) {
                in.transferTo(collected);
            }
            catch (IOException ignored) {
                // the probe was killed or its pipe broke; whatever arrived is judged below
            }
        }, "cli-capture-version-probe-" + p.pid());
        reader.setDaemon(true);
        reader.start();
        String what = "version discovery `" + String.join(" ", command) + "`";
        try {
            if (!p.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                p.descendants().forEach(ProcessHandle::destroyForcibly);
                p.destroyForcibly();
                throw new IOException(what + " did not end within " + timeout.toMillis() + " ms; the probe was killed and no version was taken from it");
            }
            reader.join(timeout.toMillis());
            if (reader.isAlive()) {
                throw new IOException(what + " ended but its standard output did not close within " + timeout.toMillis() + " ms; no version was taken from it");
            }
        }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            p.destroyForcibly();
            throw new IOException(what + " was interrupted", e);
        }
        String text = collected.toString(StandardCharsets.UTF_8);
        Matcher m = CODEX_VERSION.matcher(text);
        if (!m.find()) {
            throw new IOException("version discovery `" + String.join(" ", command) + "` printed no recognizable version: " + text.strip());
        }
        return m.group(1);
    }

    /**
     * Copies the native session file located by the session id to the archive directory and hashes it; the file's
     * content is never read as JSON. Reports what was searched when nothing is found.
     */
    Jv.Obj archiveNativeSessionFile(Path archiveDir, String sessionUid) throws IOException {
        Jv.Obj a = new Jv.Obj().put("parsed", false).put("role", "Level 0 redundancy outside the selected input; never read for enrichment, reconciliation or version discovery");
        if (sessionUid == null) {
            return a.put("status", "not located").put("detail", "the stream reported no session id, so no native session file can be located");
        }
        List<Path> candidates = new ArrayList<>();
        List<String> searched = new ArrayList<>();
        Path home = launch.home() != null ? launch.home() : Path.of(System.getProperty("user.home"));
        if (launch.cli() == Cli.CLAUDE) {
            String absolute = launch.workDir().toAbsolutePath().normalize().toString();
            for (String enc : List.of(absolute.replace('/', '-'), absolute.replace('/', '-').replace('.', '-'))) {
                Path f = home.resolve(".claude").resolve("projects").resolve(enc).resolve(sessionUid + ".jsonl");
                searched.add(f.toString());
                if (Files.isRegularFile(f) && !candidates.contains(f)) {
                    candidates.add(f);
                }
            }
        }
        else {
            Path sessions = home.resolve(".codex").resolve("sessions");
            searched.add(sessions + "/**/rollout-*-" + sessionUid + ".jsonl");
            if (Files.isDirectory(sessions)) {
                try (Stream<Path> s = Files.walk(sessions)) {
                    s.filter(f -> Files.isRegularFile(f) && f.getFileName().toString().startsWith("rollout-") && f.getFileName().toString().endsWith("-" + sessionUid + ".jsonl"))
                            .forEach(candidates::add);
                }
            }
        }
        a.put("session_uid", sessionUid).put("searched", Jv.arr(searched.toArray(String[]::new)));
        if (candidates.isEmpty()) {
            return a.put("status", "not located").put("detail", "no native session file for this session id under " + home + "; reported separately from stream acquisition");
        }
        Path source = candidates.get(0);
        Path target = archiveDir.resolve(source.getFileName().toString());
        Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
        byte[] data = Files.readAllBytes(target);
        String sha = sha256(data);
        Files.writeString(archiveDir.resolve(source.getFileName() + ".sha256"), sha + "  " + source.getFileName() + "\n", StandardCharsets.UTF_8);
        return a.put("status", "archived").put("artifact", target.getFileName().toString()).put("located_at", source.toString()).put("bytes", data.length).put("sha256", sha)
                .put("located_by", launch.cli() == Cli.CLAUDE ? "the session id the stream reported, under <home>/.claude/projects/<encoded cwd>/" : "the thread id the stream reported, matched against <home>/.codex/sessions/**/rollout-*-<thread>.jsonl");
    }

    /**
     * The frozen expected-source manifest from the independent copy, written after acquisition and before any judging.
     * Split cores are expected only when the compatibility selection in force normalizes: a no_match source is retained
     * whole, so no child core is expected and the manifest states the expected status (as the untested-version fixture does).
     */
    static Path freezeExpectedSources(Path nativeDir, Path copy, String runUid, String profile, boolean complete, Jv.Obj process, Jv.Obj compatibilityInForce) throws IOException {
        byte[] b = Files.readAllBytes(copy);
        int records = 0;
        for (byte x : b) {
            if (x == '\n') {
                records++;
            }
        }
        if (b.length > 0 && b[b.length - 1] != '\n') {
            records++;
        }
        // expected split cores, read from the copy with the plain JSON parser (never the mapper): one per distinct non-null owner path
        Jv.Arr children = new Jv.Arr();
        String status = compatibilityInForce == null ? null : compatibilityInForce.get("status").text();
        boolean noMatch = "no_match".equals(status);
        if ("claude_stream".equals(profile) && !noMatch) {
            List<String> seen = new ArrayList<>();
            for (String line : new String(b, StandardCharsets.UTF_8).split("\n")) {
                if (line.isBlank()) {
                    continue;
                }
                try {
                    Jv o = Jv.parse(line);
                    String owner = o instanceof Jv.Obj ? MapperSupport.text(o, "parent_tool_use_id") : null;
                    if (owner != null && !seen.contains(owner)) {
                        seen.add(owner);
                        children.add(new Jv.Obj().put("spawn_tool_call_uid", owner).put("artifact", "stream.jsonl").put("owner_path", "parent_tool_use_id"));
                    }
                }
                catch (RuntimeException notJson) {
                    // an opaque or cut line has no owner
                }
            }
        }
        Jv.Obj source = new Jv.Obj().put("artifact", "stream.jsonl").put("profile", profile).put("sha256", sha256(b)).put("bytes", b.length).put("records", records).put("required", true);
        if (noMatch) {
            source.put("compatibility", "no_match").put("compatibility_basis", "the selection the writer declared for the stream-reported version matched no registry entry ("
                    + compatibilityInForce.get("detail").text() + "); the expected behavior is retention flagged untested, with no split core and no nearest-entry normalization");
        }
        Jv.Obj m = new Jv.Obj().put("kind", "agent-journal/expected-sources").put("version", "0.1").put("run_uid", runUid).put("complete", complete)
                .put("frozen", new Jv.Obj().put("at", "after acquisition, before judging").put("by", "sha256, bytes and line count of the independent byte copy of the CLI's standard output (written before the writer saw each chunk); split cores from the copy's distinct non-null parent_tool_use_id values")
                        .put("basis", "LIVE launch by CliCapture; the capture's own package output was not consulted").put("process", process))
                .put("sources", new Jv.Arr(List.of(source)))
                .put("children", children);
        Path f = nativeDir.resolve("expected-sources.json");
        Files.writeString(f, Jv.write(m) + "\n", StandardCharsets.UTF_8);
        return f;
    }

    static String sha256(byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        }
        catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    // ---- command line -----------------------------------------------------------------------------------------------

    /**
     * <pre>
     *   launch &lt;out-dir&gt; --cli claude|codex --prompt TEXT --cwd DIR [--run-uid X] [--model M] [--allowed-tools A,B] [--sandbox MODE]
     *          [--executable PATH] [--home DIR] [--cancel-after-records N] [--cancel-after-ms N] [--arg EXTRA]...
     * </pre>
     * Prints the three facts and exits 0 on COMPLETED or INTERRUPTED (the package is honest either way), 1 on FAILED.
     */
    static int main(String[] args, java.io.PrintStream out, java.io.PrintStream err) throws IOException {
        if (args.length < 2) {
            err.println("usage: launch <out-dir> --cli claude|codex --prompt TEXT --cwd DIR [--run-uid X] [--model M] [--allowed-tools A,B] [--sandbox MODE] [--executable PATH] [--home DIR] [--cancel-after-records N] [--cancel-after-ms N] [--arg EXTRA]...");
            return 64;
        }
        Path outDir = Path.of(args[1]);
        String cliName = null, prompt = null, cwd = null, runUid = null, model = null, sandbox = null, executable = null, home = null;
        List<String> allowed = new ArrayList<>();
        List<String> extra = new ArrayList<>();
        Integer cancelRecords = null;
        Long cancelMs = null;
        for (int i = 2; i < args.length; i++) {
            switch (args[i]) {
                case "--cli" -> cliName = args[++i];
                case "--prompt" -> prompt = args[++i];
                case "--cwd" -> cwd = args[++i];
                case "--run-uid" -> runUid = args[++i];
                case "--model" -> model = args[++i];
                case "--allowed-tools" -> allowed.addAll(List.of(args[++i].split(",")));
                case "--sandbox" -> sandbox = args[++i];
                case "--executable" -> executable = args[++i];
                case "--home" -> home = args[++i];
                case "--cancel-after-records" -> cancelRecords = Integer.parseInt(args[++i]);
                case "--cancel-after-ms" -> cancelMs = Long.parseLong(args[++i]);
                case "--arg" -> extra.add(args[++i]);
                default -> {
                    err.println("unknown option " + args[i]);
                    return 64;
                }
            }
        }
        if (cliName == null || prompt == null || cwd == null) {
            err.println("--cli, --prompt and --cwd are required");
            return 64;
        }
        Cli cli = "claude".equals(cliName) ? Cli.CLAUDE : "codex".equals(cliName) ? Cli.CODEX : null;
        if (cli == null) {
            err.println("--cli must be claude or codex");
            return 64;
        }
        Path workDir = Path.of(cwd).toAbsolutePath().normalize();
        String exe = executable != null ? executable : cli == Cli.CLAUDE ? "claude" : "codex";
        List<String> command = cli == Cli.CLAUDE ? claudeCommand(exe, prompt, model, allowed, extra) : codexCommand(exe, prompt, model, sandbox, workDir, extra);
        Jv.Obj notes = new Jv.Obj();
        if (model != null) {
            notes.put("model_requested", model);
        }
        if (!allowed.isEmpty()) {
            notes.put("allowed_tools", Jv.arr(allowed.toArray(String[]::new)));
        }
        if (sandbox != null) {
            notes.put("sandbox_requested", sandbox);
        }
        Launch launch = new Launch(cli, command, workDir, prompt, runUid != null ? runUid : "launch-" + Instant.now().toEpochMilli(), outDir, cancelRecords, cancelMs,
                home == null ? null : Path.of(home), null, notes, null);
        CliCapture capture = new CliCapture(launch, WriterOptions.defaults().withAutoFlush(1));
        Thread hook = new Thread(() -> capture.cancel("the capture process received a termination signal"));
        Runtime.getRuntime().addShutdownHook(hook);
        Result r;
        try {
            r = capture.run();
        }
        finally {
            try {
                Runtime.getRuntime().removeShutdownHook(hook);
            }
            catch (IllegalStateException ignored) {
                // shutting down
            }
        }
        out.println("launch " + r.state() + " process " + r.how() + " exit " + r.exitCode() + " session " + r.sessionUid() + " cli_version " + r.cliVersion()
                + " records " + r.records() + " bytes " + r.bytes() + " archive " + r.archive().get("status").text() + " package " + r.packageDir());
        return r.state() == OcsfRunWriter.State.FAILED ? 1 : 0;
    }

    static UncheckedIOException unchecked(IOException e) {
        return new UncheckedIOException(e);
    }
}
