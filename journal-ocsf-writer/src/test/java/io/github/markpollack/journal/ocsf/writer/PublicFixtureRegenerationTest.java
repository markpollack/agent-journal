package io.github.markpollack.journal.ocsf.writer;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

import static io.github.markpollack.journal.ocsf.writer.WriterTestSupport.CHILD;
import static io.github.markpollack.journal.ocsf.writer.WriterTestSupport.CODEX;
import static io.github.markpollack.journal.ocsf.writer.WriterTestSupport.CODEX_STREAM;
import static io.github.markpollack.journal.ocsf.writer.WriterTestSupport.FIXTURES;
import static io.github.markpollack.journal.ocsf.writer.WriterTestSupport.PAIR;
import static io.github.markpollack.journal.ocsf.writer.WriterTestSupport.claudeStream;
import static io.github.markpollack.journal.ocsf.writer.WriterTestSupport.claudeTranscript;
import static io.github.markpollack.journal.ocsf.writer.WriterTestSupport.codexRollout;
import static io.github.markpollack.journal.ocsf.writer.WriterTestSupport.codexStream;
import static io.github.markpollack.journal.ocsf.writer.WriterTestSupport.nativeOf;
import static io.github.markpollack.journal.ocsf.writer.WriterTestSupport.run;

/**
 * Regenerates the committed public fixture packages under {@code journal-capture-tck/src/test/resources/ocsf/fixtures/<name>/package}
 * through the incremental writer (byte replay, 1 KiB chunks, a fixed clock so the output is reproducible). Gated by
 * {@code -Docsf.regenerate=true}; the build never regenerates fixtures silently.
 */
class PublicFixtureRegenerationTest {

    @Test
    void regenerate() throws IOException {
        Assumptions.assumeTrue("true".equals(System.getProperty("ocsf.regenerate")), "no -Docsf.regenerate=true");
        write(CHILD, List.of(claudeStream()), List.of("stream.jsonl"));
        write(CODEX, List.of(codexRollout()), List.of("rollout.jsonl"));
        write(PAIR, List.of(claudeStream(), claudeTranscript()), List.of("stream.jsonl", "transcript.jsonl"));
        write(CODEX_STREAM, List.of(codexStream()), List.of("stream.jsonl"));
    }

    private static void write(String fixture, List<SourceDescriptor> sources, List<String> artifacts) throws IOException {
        Path out = FIXTURES.resolve(fixture).resolve("package");
        if (Files.exists(out)) {
            try (Stream<Path> s = Files.walk(out)) {
                s.sorted(Comparator.reverseOrder()).forEach(p -> {
                    try {
                        Files.delete(p);
                    }
                    catch (IOException e) {
                        throw new java.io.UncheckedIOException(e);
                    }
                });
            }
        }
        Clock fixed = Clock.fixed(Instant.parse("2026-10-21T00:00:00Z"), ZoneOffset.UTC);
        try (OcsfRunWriter w = OcsfRunWriter.open(out, run(fixture), SchemaPin.bundled(), WriterOptions.defaults().withClock(fixed))) {
            for (int i = 0; i < sources.size(); i++) {
                Replay.feedBytes(w.source(sources.get(i)), nativeOf(fixture, artifacts.get(i)), 1024, fixed);
            }
            w.complete();
        }
        System.out.println("regenerated " + out);
    }
}
