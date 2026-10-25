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
import static io.github.markpollack.journal.ocsf.writer.WriterTestSupport.INTERRUPTED;
import static io.github.markpollack.journal.ocsf.writer.WriterTestSupport.LIVE_CLAUDE_PAIR;
import static io.github.markpollack.journal.ocsf.writer.WriterTestSupport.LIVE_COPILOT;
import static io.github.markpollack.journal.ocsf.writer.WriterTestSupport.LIVE_JUNIE_TERMINAL;
import static io.github.markpollack.journal.ocsf.writer.WriterTestSupport.LIVE_JUNIE_VIEW;
import static io.github.markpollack.journal.ocsf.writer.WriterTestSupport.PAIR;
import static io.github.markpollack.journal.ocsf.writer.WriterTestSupport.LIVE_CANCELLED;
import static io.github.markpollack.journal.ocsf.writer.WriterTestSupport.LIVE_CODEX;
import static io.github.markpollack.journal.ocsf.writer.WriterTestSupport.CODEX_FAMILIES;
import static io.github.markpollack.journal.ocsf.writer.WriterTestSupport.CODEX_TURN_FAILED;
import static io.github.markpollack.journal.ocsf.writer.WriterTestSupport.LIVE_CODEX_FILE_CHANGE;
import static io.github.markpollack.journal.ocsf.writer.WriterTestSupport.LIVE_CODEX_MCP;
import static io.github.markpollack.journal.ocsf.writer.WriterTestSupport.LIVE_CODEX_REASONING;
import static io.github.markpollack.journal.ocsf.writer.WriterTestSupport.LIVE_CODEX_PLAN;
import static io.github.markpollack.journal.ocsf.writer.WriterTestSupport.LIVE_CODEX_WEB_SEARCH;
import static io.github.markpollack.journal.ocsf.writer.WriterTestSupport.LIVE_CODEX_COLLAB_WAIT;
import static io.github.markpollack.journal.ocsf.writer.WriterTestSupport.LIVE_CODEX_INTERRUPT_OPEN;
import static io.github.markpollack.journal.ocsf.writer.WriterTestSupport.LIVE_CODEX_SANDBOX_REFUSAL;
import static io.github.markpollack.journal.ocsf.writer.WriterTestSupport.LIVE_CODEX_CANCELLED;
import static io.github.markpollack.journal.ocsf.writer.WriterTestSupport.LIVE_DENIAL;
import static io.github.markpollack.journal.ocsf.writer.WriterTestSupport.LIVE_MCP;
import static io.github.markpollack.journal.ocsf.writer.WriterTestSupport.LIVE_FEATURES;
import static io.github.markpollack.journal.ocsf.writer.WriterTestSupport.UNTESTED;
import static io.github.markpollack.journal.ocsf.writer.WriterTestSupport.LIVE_AGY_DENIAL;
import static io.github.markpollack.journal.ocsf.writer.WriterTestSupport.LIVE_AGY_FEATURES;
import static io.github.markpollack.journal.ocsf.writer.WriterTestSupport.LIVE_AGY_INTERRUPT_OPEN;
import static io.github.markpollack.journal.ocsf.writer.WriterTestSupport.LIVE_AGY_MCP;
import static io.github.markpollack.journal.ocsf.writer.WriterTestSupport.LIVE_AGY_MCP_DENIED;
import static io.github.markpollack.journal.ocsf.writer.WriterTestSupport.LIVE_AGY_SUBAGENT;
import static io.github.markpollack.journal.ocsf.writer.WriterTestSupport.agyStream;
import static io.github.markpollack.journal.ocsf.writer.WriterTestSupport.claudeStream;
import static io.github.markpollack.journal.ocsf.writer.WriterTestSupport.claudeStreamStdout;
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
        writeEvidenceLog(PAIR, List.of(claudeStream(), claudeTranscript()), List.of("stream.jsonl", "transcript.jsonl"),
                "the synthetic stream and session file of one sdk-route run, recorded together without assembly to exercise both mappers and the readers of a multi-source file");
        write(CODEX_STREAM, List.of(codexStream()), List.of("stream.jsonl"));
        writeInterrupted(INTERRUPTED, CHILD, "stream.jsonl", 10);
        write(UNTESTED, List.of(claudeStreamStdout()), List.of("stream.jsonl"));
        write(LIVE_FEATURES, List.of(claudeStreamStdout()), List.of("stream.jsonl"));
        write(WriterTestSupport.SYN_CHILD_RUNS, List.of(claudeStreamStdout()), List.of("stream.jsonl"));
        write(WriterTestSupport.SYN_AGENT_DEFAULTS, List.of(claudeStreamStdout()), List.of("stream.jsonl"));
        write(LIVE_DENIAL, List.of(claudeStreamStdout()), List.of("stream.jsonl"));
        write(LIVE_MCP, List.of(claudeStreamStdout()), List.of("stream.jsonl"));
        write(LIVE_CODEX, List.of(codexStream()), List.of("stream.jsonl"));
        writeCancelled(LIVE_CANCELLED, claudeStreamStdout());
        writeCancelled(LIVE_CODEX_CANCELLED, codexStream());
        write(CODEX_FAMILIES, List.of(codexStream()), List.of("stream.jsonl"));
        write(CODEX_TURN_FAILED, List.of(codexStream()), List.of("stream.jsonl"));
        write(LIVE_CODEX_FILE_CHANGE, List.of(codexStream()), List.of("stream.jsonl"));
        write(LIVE_CODEX_MCP, List.of(codexStream()), List.of("stream.jsonl"));
        write(LIVE_CODEX_REASONING, List.of(codexStream()), List.of("stream.jsonl"));
        write(LIVE_CODEX_PLAN, List.of(codexStream()), List.of("stream.jsonl"));
        write(LIVE_CODEX_WEB_SEARCH, List.of(codexStream()), List.of("stream.jsonl"));
        write(LIVE_CODEX_COLLAB_WAIT, List.of(codexStream()), List.of("stream.jsonl"));
        writeCancelled(LIVE_CODEX_INTERRUPT_OPEN, codexStream());
        write(LIVE_CODEX_SANDBOX_REFUSAL, List.of(codexStream()), List.of("stream.jsonl"));
        write(LIVE_AGY_FEATURES, List.of(agyStream()), List.of("stream.jsonl"));
        write(LIVE_AGY_DENIAL, List.of(agyStream()), List.of("stream.jsonl"));
        writeCancelled(LIVE_AGY_INTERRUPT_OPEN, agyStream());
        write(LIVE_AGY_SUBAGENT, List.of(agyStream()), List.of("stream.jsonl"));
        write(LIVE_AGY_MCP_DENIED, List.of(agyStream()), List.of("stream.jsonl"));
        write(LIVE_AGY_MCP, List.of(agyStream()), List.of("stream.jsonl"));
        writeAssembled(LIVE_CLAUDE_PAIR);
        writeAssembled(LIVE_COPILOT);
        writeAssembled(LIVE_JUNIE_VIEW);
        writeAssembled(LIVE_JUNIE_TERMINAL);
    }

    /**
     * A finally assembled fixture: the inputs and profile its spec.json names ({@code assembly}), assembled by
     * {@link FinalAssembly} with a fixed clock.
     */
    private static void writeAssembled(String fixture) throws IOException {
        Path out = FIXTURES.resolve(fixture).resolve("package");
        delete(out);
        Clock fixed = Clock.fixed(Instant.parse("2026-10-21T00:00:00Z"), ZoneOffset.UTC);
        Jv assembly = Jv.parse(Files.readString(FIXTURES.resolve(fixture).resolve("spec.json"))).member("assembly");
        List<FinalAssembly.Input> inputs = new java.util.ArrayList<>();
        for (Jv i : assembly.member("inputs").elements()) {
            String artifact = i.member("artifact").text();
            inputs.add(new FinalAssembly.Input(artifact, i.member("role").text(), FIXTURES.resolve(fixture).resolve("native").resolve(artifact), i.member("origin").text(),
                    i.member("acquisition") == null ? null : i.member("acquisition").text()));
        }
        FinalAssembly.assemble(out, run(fixture), AssemblyProfile.bundled(assembly.member("profile").text()), inputs, new Jv.Arr(), WriterOptions.defaults().withClock(fixed));
        System.out.println("regenerated " + out);
    }

    /**
     * The replay-interrupted fixture: the first {@code records} whole records of another fixture's stream are its
     * native artifact (written here, so the prefix is exactly what the writer was fed), its frozen expected sources
     * declare the run incomplete, and the writer is interrupted by the driver after feeding them.
     */
    private static void writeInterrupted(String fixture, String from, String artifact, int records) throws IOException {
        Path dir = FIXTURES.resolve(fixture);
        Path nativeDir = dir.resolve("native");
        Files.createDirectories(nativeDir);
        byte[] prefix = Replay.prefixBytes(nativeOf(from, artifact), records);
        Files.write(nativeDir.resolve(artifact), prefix);
        WriterTestSupport.freeze(nativeDir, run(fixture).runUid(), false, artifact, "claude_stream");
        Path out = dir.resolve("package");
        delete(out);
        Clock fixed = Clock.fixed(Instant.parse("2026-10-21T00:00:00Z"), ZoneOffset.UTC);
        try (OcsfRunWriter w = OcsfRunWriter.open(out, run(fixture), SchemaPin.bundled(), WriterOptions.defaults().withClock(fixed))) {
            SourceInput in = w.source(claudeStream());
            Replay.feedBytes(in, prefix, 1024, fixed, prefix.length, false);
            w.flush();
            w.interrupt("fixture driver stopped the replay after " + records + " whole records (labelled replay interruption)");
        }
        System.out.println("regenerated " + out);
    }

    /**
     * A sanitized live recording whose capture cancelled the CLI: every acquired byte is replayed and the writer is
     * interrupted with the reason the live capture recorded (the fixture's frozen manifest declares the run incomplete).
     */
    private static void writeCancelled(String fixture, SourceDescriptor source) throws IOException {
        Path dir = FIXTURES.resolve(fixture);
        Path out = dir.resolve("package");
        delete(out);
        byte[] data = Files.readAllBytes(dir.resolve("native").resolve("stream.jsonl"));
        Jv expected = Jv.parse(Files.readString(dir.resolve("native").resolve("expected-sources.json")));
        String reason = expected.path("frozen", "live_process", "cancel_reason").text();
        Clock fixed = Clock.fixed(Instant.parse("2026-10-21T00:00:00Z"), ZoneOffset.UTC);
        try (OcsfRunWriter w = OcsfRunWriter.open(out, run(fixture), SchemaPin.bundled(), WriterOptions.defaults().withClock(fixed))) {
            SourceInput in = w.source(source);
            Replay.feedBytes(in, data, 1024, fixed, data.length, false);
            w.flush();
            w.interrupt("replay of the sanitized live recording whose capture cancelled the CLI: " + reason);
        }
        System.out.println("regenerated " + out);
    }

    private static void delete(Path out) throws IOException {
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
    }

    private static void write(String fixture, List<SourceDescriptor> sources, List<String> artifacts) throws IOException {
        writeEvidenceLog(fixture, sources, artifacts, null);
    }

    /** One source through the plain writer, or several as a declared unvalidated evidence log ({@code reason}). */
    private static void writeEvidenceLog(String fixture, List<SourceDescriptor> sources, List<String> artifacts, String reason) throws IOException {
        Path out = FIXTURES.resolve(fixture).resolve("package");
        delete(out);
        Clock fixed = Clock.fixed(Instant.parse("2026-10-21T00:00:00Z"), ZoneOffset.UTC);
        WriterOptions options = WriterOptions.defaults().withClock(fixed);
        try (OcsfRunWriter w = reason == null ? OcsfRunWriter.open(out, run(fixture), SchemaPin.bundled(), options)
                : OcsfRunWriter.openEvidenceLog(out, run(fixture), SchemaPin.bundled(), options, reason)) {
            for (int i = 0; i < sources.size(); i++) {
                Replay.feedBytes(w.source(sources.get(i)), nativeOf(fixture, artifacts.get(i)), 1024, fixed);
            }
            w.complete();
        }
        System.out.println("regenerated " + out);
    }
}
