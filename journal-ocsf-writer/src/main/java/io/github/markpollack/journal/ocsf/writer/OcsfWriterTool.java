package io.github.markpollack.journal.ocsf.writer;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;

/**
 * Command-line replay of recorded native artifacts through the incremental writer, and the non-default live capture
 * entry point ({@link LiveCapture}, {@code capture ...}, the stream on standard input):
 *
 * <pre>
 *   replay &lt;spec.json&gt; &lt;out-dir&gt; [--chunk N] [--flush-every N] [--base DIR] [--stop-after ARTIFACT:RECORDS]
 * </pre>
 *
 * The spec lists the run facts and the sources in admission order ({@code artifact}, {@code kind}, {@code profile},
 * {@code route}, {@code surface}, {@code path}; a source without a route matches no compatibility entry and is retained unnormalized); each source is fed in byte chunks of {@code --chunk} bytes (default 4096) and
 * ended with EOF before the next source is admitted, which is the order a live capture sees them in (live output
 * first, the saved session file afterwards). {@code --stop-after} ends the named source after that many whole
 * records and interrupts the writer, for prefix evidence. The out directory must not exist.
 */
public final class OcsfWriterTool {

    private OcsfWriterTool() {
    }

    public static void main(String[] args) throws IOException {
        if (args.length >= 1 && "capture".equals(args[0])) {
            System.exit(LiveCapture.run(args, System.in, System.out, System.err));
        }
        if (args.length < 3 || !"replay".equals(args[0])) {
            System.err.println("usage: replay <spec.json> <out-dir> [--chunk N] [--flush-every N] [--base DIR] [--stop-after ARTIFACT:RECORDS]");
            System.err.println("       capture <spec.json> <out-dir> [--artifact NAME] [--profile PROFILE] [--surface TEXT] [--flush-every N] [--interrupt-flag FILE]  (stdin: the live stream)");
            System.exit(64);
        }
        int chunk = 4096;
        int flushEvery = 0;
        Path base = null;
        String stopArtifact = null;
        int stopRecords = -1;
        for (int i = 3; i < args.length; i++) {
            switch (args[i]) {
                case "--chunk" -> chunk = Integer.parseInt(args[++i]);
                case "--flush-every" -> flushEvery = Integer.parseInt(args[++i]);
                case "--base" -> base = Path.of(args[++i]);
                case "--stop-after" -> {
                    String[] parts = args[++i].split(":");
                    stopArtifact = parts[0];
                    stopRecords = Integer.parseInt(parts[1]);
                }
                default -> {
                    System.err.println("unknown option " + args[i]);
                    System.exit(64);
                }
            }
        }
        Jv spec = Jv.parse(Files.readString(Path.of(args[1]), StandardCharsets.UTF_8));
        Path out = Path.of(args[2]);
        if (Files.exists(out)) {
            System.err.println("out directory exists: " + out);
            System.exit(65);
        }
        RunDescriptor run = RunDescriptor.fromSpec(spec);
        WriterOptions options = WriterOptions.defaults().withAutoFlush(flushEvery);
        Clock clock = options.clock();
        OcsfRunWriter.State state;
        try (OcsfRunWriter w = OcsfRunWriter.open(out, run, SchemaPin.bundled(), options)) {
            boolean stopped = false;
            for (Jv s : spec.member("sources").elements()) {
                String artifact = s.member("artifact").text();
                Path file = Path.of(s.member("path").text());
                if (base != null && !file.isAbsolute()) {
                    file = base.resolve(file);
                }
                SourceDescriptor d = new SourceDescriptor(artifact, (int) ((Jv.Num) s.member("kind")).value().longValueExact(), s.member("profile").text(),
                        s.member("route") == null ? null : s.member("route").text(),
                        s.member("surface") == null ? "recorded artifact replayed in byte chunks" : s.member("surface").text(), s.member("path").text());
                SourceInput in = w.source(d);
                if (artifact.equals(stopArtifact)) {
                    byte[] prefix = Replay.prefixBytes(file, stopRecords);
                    Replay.feedBytes(in, prefix, chunk, clock, prefix.length, false);
                    w.flush();
                    stopped = true;
                    break;
                }
                Replay.feedBytes(in, file, chunk, clock);
                System.out.println("fed " + artifact + ": " + in.records() + " record(s) from " + file);
            }
            state = stopped ? w.interrupt("--stop-after " + stopArtifact + ":" + stopRecords) : w.complete();
        }
        System.out.println("wrote " + out + " state " + state);
        List<String> lines = Files.readAllLines(out.resolve("events.jsonl"), StandardCharsets.UTF_8);
        System.out.println(lines.size() + " event line(s) in events.jsonl");
    }
}
