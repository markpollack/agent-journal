package io.github.markpollack.journal.ocsf.writer;

import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;

/**
 * NON-DEFAULT live capture entry point: feeds the raw bytes of a CLI's selected stream, as they arrive on this
 * process's standard input, into the one incremental writer, flushing after every record so the package is readable
 * before the CLI exits. Nothing between the CLI and the writer parses, filters or re-frames the bytes: the caller
 * pipes the CLI's standard output here (and, independently, to a copy for the checker), which is acquisition before
 * any SDK stage.
 *
 * <pre>
 *   capture &lt;spec.json&gt; &lt;out-dir&gt; [--artifact NAME] [--profile PROFILE] [--surface TEXT] [--flush-every N] [--interrupt-flag FILE]
 * </pre>
 *
 * {@code spec.json} is the run descriptor ({@code run_uid}, {@code invocation_index}, {@code capture_start},
 * {@code cli} whose {@code version} may be absent when the stream is expected to report it, {@code launch_settings});
 * the stream is declared as {@code --artifact} (default {@code stream.jsonl}) under {@code --profile} (default
 * {@code claude_stream}). At end of input the source ends by EOF and the writer completes, unless the file named by
 * {@code --interrupt-flag} exists, in which case the driver stopped the CLI and the writer is interrupted with the
 * flag file's content as the reason. The outcome, the run uid, the session uid and the reported CLI version are
 * printed on standard output for the driver.
 *
 * <p>This is bounded, non-production integration evidence: it changes no capture default and uses no SDK.
 */
public final class LiveCapture {

    private LiveCapture() {
    }

    static int run(String[] args, InputStream stdin, PrintStream out, PrintStream err) throws IOException {
        if (args.length < 3) {
            err.println("usage: capture <spec.json> <out-dir> [--artifact NAME] [--profile PROFILE] [--surface TEXT] [--flush-every N] [--interrupt-flag FILE]");
            return 64;
        }
        String artifact = "stream.jsonl";
        String profile = "claude_stream";
        String surface = "live CLI standard output piped to this process (raw bytes, no SDK stage); an independent copy is taken by the driver";
        int flushEvery = 1;
        Path interruptFlag = null;
        for (int i = 3; i < args.length; i++) {
            switch (args[i]) {
                case "--artifact" -> artifact = args[++i];
                case "--profile" -> profile = args[++i];
                case "--surface" -> surface = args[++i];
                case "--flush-every" -> flushEvery = Integer.parseInt(args[++i]);
                case "--interrupt-flag" -> interruptFlag = Path.of(args[++i]);
                default -> {
                    err.println("unknown option " + args[i]);
                    return 64;
                }
            }
        }
        Jv spec = Jv.parse(Files.readString(Path.of(args[1]), StandardCharsets.UTF_8));
        Path dir = Path.of(args[2]);
        if (Files.exists(dir)) {
            err.println("out directory exists: " + dir);
            return 65;
        }
        RunDescriptor run = RunDescriptor.fromSpec(spec);
        WriterOptions options = WriterOptions.defaults().withAutoFlush(flushEvery);
        Clock clock = options.clock();
        Instant started = clock.instant();
        OcsfRunWriter.State state;
        long bytes = 0;
        int records;
        try (OcsfRunWriter w = OcsfRunWriter.open(dir, run, SchemaPin.bundled(), options)) {
            SourceInput in = w.source(new SourceDescriptor(artifact, 1, profile, surface, "stdin of the capture process"));
            w.flush();
            out.println("capture open " + dir + " run " + run.runUid() + " at " + started + " (flush every " + flushEvery + " record(s))");
            out.flush();
            byte[] buf = new byte[8192];
            int n;
            while ((n = stdin.read(buf)) > 0) {
                in.bytes(buf, 0, n, clock.instant());
                bytes += n;
            }
            records = in.records();
            Instant ended = clock.instant();
            String reason = interruptFlag != null && Files.isRegularFile(interruptFlag) ? Files.readString(interruptFlag, StandardCharsets.UTF_8).strip() : null;
            if (reason != null) {
                state = w.interrupt("driver stopped the CLI: " + reason);
            }
            else {
                in.end(SourceInput.End.EOF, ended);
                state = w.complete();
            }
        }
        Jv manifest = Jv.parse(Files.readString(dir.resolve("run.json"), StandardCharsets.UTF_8));
        out.println("capture " + state + " run " + run.runUid() + " session " + text(manifest.path("run", "session_uid"))
                + " cli_version " + text(manifest.path("run", "cli", "version")) + " (" + text(manifest.path("run", "cli", "version_basis")) + ")"
                + " records " + records + " bytes " + bytes + " events " + text(manifest.path("package", "events_count")));
        return state == OcsfRunWriter.State.COMPLETED || state == OcsfRunWriter.State.INTERRUPTED ? 0 : 1;
    }

    private static String text(Jv v) {
        return v == null || v.isNull() ? "null" : v instanceof Jv.Str s ? s.value() : Jv.write(v);
    }
}
