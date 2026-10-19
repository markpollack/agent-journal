package io.github.markpollack.journal.claude;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import io.github.markpollack.claude.agent.sdk.parsing.MessageParser;
import io.github.markpollack.claude.agent.sdk.parsing.ParsedMessage;
import io.github.markpollack.journal.Journal;
import io.github.markpollack.journal.Run;
import io.github.markpollack.journal.RunStatus;
import io.github.markpollack.journal.storage.JsonFileStorage;
import io.github.markpollack.journal.tck.venn.Jsonl;
import io.github.markpollack.journal.tck.venn.Landing;
import io.github.markpollack.journal.tck.venn.Leaf;
import io.github.markpollack.journal.tck.venn.PathResult;
import io.github.markpollack.journal.tck.venn.Venn;
import io.github.markpollack.journal.tck.venn.VennReport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Diagnostic driver, not a gate: runs Venn v2 over a directory of captured PAIRS. Each pair directory holds
 * {@code stream.jsonl} (the CLI's live output) and {@code transcript.jsonl} (the session file Claude Code wrote), and
 * optionally {@code subagents/} with the sidechain files. For each pair it records the run through
 * {@link RunRecorder} into a temporary run directory and writes {@code venn.json} into the pair directory with three
 * reports: stream against the events, transcript against the events, and transcript against the stream.
 *
 * <p>Skipped unless {@code -Dvenn.pairs=<dir>} is given. The reports hold counts and key paths, never values.
 */
class VennOnPairsTest {

    private static final ObjectMapper JSON = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    @AfterEach
    void resetJournal() {
        Journal.reset();
    }

    @Test
    void writeVennReportsForEveryPair(@TempDir Path work) throws Exception {
        String root = System.getProperty("venn.pairs");
        Assumptions.assumeTrue(root != null && !root.isBlank(), "no -Dvenn.pairs");
        List<Path> pairs;
        try (Stream<Path> s = Files.list(Path.of(root))) {
            pairs = s.filter(p -> Files.isRegularFile(p.resolve("stream.jsonl"))).sorted().toList();
        }
        assertThat(pairs).as("pair directories under " + root).isNotEmpty();

        for (Path pair : pairs) {
            Journal.reset();
            Path stream = pair.resolve("stream.jsonl");
            Path transcript = pair.resolve("transcript.jsonl");
            // the run package is kept beside the pair so readback queries can run on it with no Level 0 present
            Path runDir = record(stream, pair.resolve("journal"));

            List<Leaf> events = new ArrayList<>(Jsonl.read(runDir.resolve("events.jsonl"), "@type", "vendor_event"));
            if (Files.exists(runDir.resolve("analysis.jsonl"))) {
                events.addAll(Jsonl.read(runDir.resolve("analysis.jsonl"), "@type", null));
            }
            // child runs of sub-agents, if the recorder wrote any
            try (Stream<Path> s = Files.walk(runDir.getParent())) {
                for (Path child : s.filter(p -> p.getFileName().toString().equals("events.jsonl"))
                        .filter(p -> !p.getParent().equals(runDir)).toList()) {
                    events.addAll(Jsonl.read(child, "@type", "vendor_event"));
                }
            }
            List<Leaf> streamLeaves = Jsonl.read(stream, "type", null);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("pair", pair.getFileName().toString());
            out.put("events", events.size());
            out.put("stream_vs_events", summary(Venn.compare(streamLeaves, events)));
            if (Files.isRegularFile(transcript)) {
                List<Leaf> transcriptLeaves = new ArrayList<>(Jsonl.read(transcript, "type", null));
                Path subagents = pair.resolve("subagents");
                if (Files.isDirectory(subagents)) {
                    try (Stream<Path> s = Files.walk(subagents)) {
                        for (Path f : s.filter(p -> p.toString().endsWith(".jsonl")).sorted().toList()) {
                            transcriptLeaves.addAll(Jsonl.read(f, "type", null));
                        }
                    }
                }
                out.put("transcript_vs_events", summary(Venn.compare(transcriptLeaves, events)));
                out.put("transcript_vs_stream", summary(Venn.compare(transcriptLeaves, streamLeaves)));
                out.put("stream_vs_transcript", summary(Venn.compare(streamLeaves, transcriptLeaves)));
            }
            JSON.writeValue(pair.resolve("venn.json").toFile(), out);
            System.out.println("VENN " + pair.getFileName() + " " + out.get("stream_vs_events"));
        }
    }

    private static Path record(Path stream, Path dir) throws Exception {
        MessageParser parser = new MessageParser();
        List<ParsedMessage> messages = new ArrayList<>();
        for (String line : Files.readAllLines(stream)) {
            if (line.isBlank()) {
                continue;
            }
            try {
                messages.add(ParsedMessage.RegularMessage.of(parser.parseMessage(line), line));
            }
            catch (RuntimeException unknownType) {
                // the SDK skips message types it does not know; so does the stream reader
            }
        }
        PhaseCapture capture = SessionLogParser.parse(messages.iterator(), "run", "prompt");
        Files.createDirectories(dir);
        Journal.configure(new JsonFileStorage(dir));
        Run run = Journal.run("exp").start();
        new RunRecorder(run).recordPhase(capture);
        run.finish(capture.isError() ? RunStatus.FAILED : RunStatus.FINISHED);
        try (Stream<Path> walk = Files.walk(dir)) {
            return walk.filter(p -> p.getFileName().toString().equals("events.jsonl"))
                    .filter(p -> p.getParent().getFileName().toString().equals(run.id()))
                    .findFirst().orElseThrow().getParent();
        }
    }

    private static Map<String, Object> summary(VennReport r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("landed", r.count(Landing.LANDED));
        m.put("kept_whole", r.count(Landing.KEPT_WHOLE));
        m.put("low_information", r.count(Landing.LOW_INFORMATION));
        m.put("neither", r.count(Landing.NEITHER));
        List<Map<String, Object>> gaps = new ArrayList<>();
        for (PathResult p : r.gaps()) {
            Map<String, Object> g = new LinkedHashMap<>();
            g.put("kind", p.kind());
            g.put("path", p.path());
            g.put("neither", p.neither());
            g.put("of", p.total());
            gaps.add(g);
        }
        m.put("gaps", gaps);
        m.put("unexplained", r.unexplained());
        return m;
    }
}
