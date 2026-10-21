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
        return new SourceDescriptor("stream.jsonl", 1, "claude_stream", "fixture stream replayed", null);
    }

    static SourceDescriptor claudeTranscript() {
        return new SourceDescriptor("transcript.jsonl", 2, "claude_transcript", "fixture transcript replayed after the stream", null);
    }

    static SourceDescriptor codexRollout() {
        return new SourceDescriptor("rollout.jsonl", 2, "codex_rollout", "fixture rollout replayed", null);
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
}
