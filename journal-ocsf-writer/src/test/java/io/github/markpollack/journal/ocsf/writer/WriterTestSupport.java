package io.github.markpollack.journal.ocsf.writer;

import io.github.markpollack.journal.tck.ocsf.OcsfOracle;
import io.github.markpollack.journal.tck.ocsf.OcsfPackage;
import io.github.markpollack.journal.tck.ocsf.OracleReport;
import io.github.markpollack.journal.tck.ocsf.PackageReader;
import io.github.markpollack.journal.tck.verify.Check;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

/**
 * Test support: the public fixtures of the TCK (native artifacts only; the packages are what this writer produces),
 * a deterministic ticking clock, and the independent reader/oracle from {@code journal-capture-tck} (test scope) to
 * judge what the writer persisted. The oracle never sees the writer; it reads files.
 */
final class WriterTestSupport {

    static final Path FIXTURES = Path.of("..", "journal-capture-tck", "src", "test", "resources", "ocsf", "fixtures").toAbsolutePath().normalize();
    static final String CHILD = "claude-child-2.1.299";
    static final String CODEX = "syn-codex-counterexamples";
    static final String PAIR = "syn-claude-pair-transcript";
    static final String CODEX_STREAM = "syn-codex-exec-stream";
    static final String INTERRUPTED = "claude-child-2.1.299-interrupted";
    static final String UNTESTED = "syn-claude-untested-version";
    /** Sanitized real live recordings of ACCEPT-1 (2026-10-21), replayed through the writer to produce their committed packages. */
    static final String LIVE_FEATURES = "claude-live-2.1.304-features";
    static final String LIVE_DENIAL = "claude-live-2.1.304-denial";
    static final String LIVE_CANCELLED = "claude-live-2.1.304-cancelled";
    static final String LIVE_CODEX = "codex-live-0.160.1-commands";
    static final String LIVE_CODEX_CANCELLED = "codex-live-0.160.1-cancelled";
    static final String SPAWN = "toolu_01BnEuCUmZ2Vu1LpADzksmrJ";

    private WriterTestSupport() {
    }

    static Path nativeOf(String fixture, String artifact) {
        Path p = FIXTURES.resolve(fixture).resolve("native").resolve(artifact);
        if (!Files.isRegularFile(p)) {
            throw new IllegalStateException("missing fixture native " + p);
        }
        return p;
    }

    static RunDescriptor run(String fixture) {
        Path spec = FIXTURES.resolve(fixture).resolve("spec.json");
        try {
            return RunDescriptor.fromSpec(Jv.parse(Files.readString(spec, StandardCharsets.UTF_8)));
        }
        catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static SourceDescriptor claudeStream() {
        return new SourceDescriptor("stream.jsonl", 1, "claude_stream", "sdk", "fixture stream replayed (SDK-delivered lines of the original capture)", null);
    }

    /** The same profile on the raw-stdout route (the route of the live captures). */
    static SourceDescriptor claudeStreamStdout() {
        return new SourceDescriptor("stream.jsonl", 1, "claude_stream", "stdout", "fixture stream replayed as raw standard output", null);
    }

    static SourceDescriptor claudeTranscript() {
        return new SourceDescriptor("transcript.jsonl", 2, "claude_transcript", "native_session_file", "fixture transcript replayed after the stream", null);
    }

    static SourceDescriptor codexRollout() {
        return new SourceDescriptor("rollout.jsonl", 2, "codex_rollout", "native_session_file", "fixture rollout replayed", null);
    }

    static SourceDescriptor codexStream() {
        return new SourceDescriptor("stream.jsonl", 1, "codex_stream", "stdout", "fixture exec --json stream replayed (labelled synthetic)", null);
    }

    /** Writes a frozen expected-source manifest for the artifacts in {@code nativeDir} (the test's own labelled inputs). */
    static void freeze(Path nativeDir, String runUid, boolean complete, String... artifactProfilePairs) {
        StringBuilder sb = new StringBuilder("{\"kind\":\"agent-journal/expected-sources\",\"version\":\"0.1\",\"run_uid\":\"" + runUid + "\",\"complete\":" + complete
                + ",\"frozen\":{\"basis\":\"test-frozen from the native bytes the writer was fed\"},\"sources\":[");
        for (int i = 0; i + 1 < artifactProfilePairs.length; i += 2) {
            byte[] b = bytes(nativeDir.resolve(artifactProfilePairs[i]));
            int records = 0;
            for (byte x : b) {
                if (x == '\n') {
                    records++;
                }
            }
            if (b.length > 0 && b[b.length - 1] != '\n') {
                records++;
            }
            sb.append(i > 0 ? "," : "").append("{\"artifact\":\"").append(artifactProfilePairs[i]).append("\",\"profile\":\"").append(artifactProfilePairs[i + 1])
                    .append("\",\"sha256\":\"").append(SchemaPin.sha256(b)).append("\",\"bytes\":").append(b.length).append(",\"records\":").append(records).append(",\"required\":true}");
        }
        sb.append("],\"children\":[]}\n");
        try {
            Files.writeString(nativeDir.resolve("expected-sources.json"), sb.toString(), StandardCharsets.UTF_8);
        }
        catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** A clock that advances one millisecond per reading, from a fixed start. */
    static Clock ticking() {
        return new Clock() {
            private long ms = 1_792_000_000_000L;

            @Override
            public ZoneOffset getZone() {
                return ZoneOffset.UTC;
            }

            @Override
            public Clock withZone(java.time.ZoneId zone) {
                return this;
            }

            @Override
            public Instant instant() {
                return Instant.ofEpochMilli(ms++);
            }
        };
    }

    static WriterOptions options() {
        return WriterOptions.defaults().withClock(ticking());
    }

    static OcsfPackage open(Path core) {
        return PackageReader.open(core.resolve("events.jsonl"));
    }

    static OracleReport verify(Path core, Path nativeDir) {
        return OcsfOracle.verify(open(core), nativeDir);
    }

    /** Checks that are neither VERIFIED nor NOT_APPLICABLE, for assertion messages. */
    static List<Check> notVerified(OracleReport report) {
        List<Check> out = new ArrayList<>();
        for (Check c : report.checks()) {
            if (c.outcome() != Check.Outcome.VERIFIED && c.outcome() != Check.Outcome.NOT_APPLICABLE) {
                out.add(c);
            }
        }
        return out;
    }

    static List<Check> defects(OracleReport report) {
        return report.defects();
    }

    /** Copies a native artifact (or its first {@code records} records) into a native dir for the oracle. */
    static Path nativeDir(Path work, String fixture, String artifact, int records) {
        try {
            Path dir = work.resolve("native");
            Files.createDirectories(dir);
            Path src = nativeOf(fixture, artifact);
            if (records < 0) {
                Files.copy(src, dir.resolve(artifact), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
            else {
                Files.write(dir.resolve(artifact), Replay.prefixBytes(src, records));
            }
            return dir;
        }
        catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static Jv manifest(Path core) {
        try {
            return Jv.parse(Files.readString(core.resolve("run.json"), StandardCharsets.UTF_8));
        }
        catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static byte[] bytes(Path p) {
        try {
            return Files.readAllBytes(p);
        }
        catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static List<String> lines(Path p) {
        try {
            return Files.readAllLines(p, StandardCharsets.UTF_8);
        }
        catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** The execution event lines of a standalone event file (every line without a top-level {@code declaration} member). */
    static List<String> eventLines(Path p) {
        List<String> out = new ArrayList<>();
        for (String line : lines(p)) {
            if (!line.isBlank() && !(Jv.parse(line).member("declaration") instanceof Jv.Obj)) {
                out.add(line);
            }
        }
        return out;
    }

    /** The declaration lines of a standalone event file, parsed. */
    static List<Jv> declarations(Path p) {
        List<Jv> out = new ArrayList<>();
        for (String line : lines(p)) {
            if (!line.isBlank() && Jv.parse(line).member("declaration") instanceof Jv.Obj) {
                out.add(Jv.parse(line));
            }
        }
        return out;
    }

    /** The declaration kinds of a file in order, for comparing two files whose declarations differ only in times. */
    static List<String> declarationKinds(Path p) {
        List<String> out = new ArrayList<>();
        for (Jv d : declarations(p)) {
            out.add(d.path("declaration", "kind").text() + " " + d.path("declaration", "uid").text());
        }
        return out;
    }
}
