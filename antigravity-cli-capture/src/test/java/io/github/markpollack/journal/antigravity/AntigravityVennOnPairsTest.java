package io.github.markpollack.journal.antigravity;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
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

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Diagnostic driver (Stage 13, package 2): runs Venn v2 over captured Antigravity PAIRS. Each pair directory holds
 * {@code stream.jsonl} (the {@code --print --output-format stream-json} stdout; the parser's input) and, optionally,
 * {@code native.jsonl} (the conversation's SQLite rows dumped as JSON lines, one per row, by the steward's dump
 * script). For each pair it records the stream through {@link AntigravityRunRecorder} and writes {@code venn.json}:
 * stream against the events and, when present, the native rows against the events and against the stream. Skipped unless {@code -Dvenn.antigravity.pairs=<dir>} is given.
 */
class AntigravityVennOnPairsTest {

    private static final ObjectMapper JSON = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    @AfterEach
    void resetJournal() {
        Journal.reset();
    }

    @Test
    void writeVennReportsForEveryPair() throws Exception {
        String root = System.getProperty("venn.antigravity.pairs");
        Assumptions.assumeTrue(root != null && !root.isBlank(), "no -Dvenn.antigravity.pairs");
        List<Path> pairs;
        try (Stream<Path> s = Files.list(Path.of(root))) {
            pairs = s.filter(p -> Files.isRegularFile(p.resolve("stream.jsonl"))).sorted().toList();
        }
        assertThat(pairs).as("pair directories under " + root).isNotEmpty();

        for (Path pair : pairs) {
            Journal.reset();
            Path streamFile = pair.resolve("stream.jsonl");
            Path dir = pair.resolve("journal");
            Files.createDirectories(dir);
            AntigravityPhaseCapture capture = AntigravitySessionParser.parse(streamFile, "run", "prompt");
            Journal.configure(new JsonFileStorage(dir));
            Run run = Journal.run("exp").start();
            new AntigravityRunRecorder(run).recordPhase(capture);
            run.finish(capture.isError() ? RunStatus.FAILED : RunStatus.FINISHED);
            Path runDir;
            try (Stream<Path> walk = Files.walk(dir)) {
                runDir = walk.filter(p -> p.getFileName().toString().equals("events.jsonl"))
                        .filter(p -> p.getParent().getFileName().toString().equals(run.id())).findFirst().orElseThrow()
                        .getParent();
            }
            List<Leaf> events = new ArrayList<>(Jsonl.read(runDir.resolve("events.jsonl"), "@type", "vendor_event"));
            if (Files.exists(runDir.resolve("analysis.jsonl"))) {
                events.addAll(Jsonl.read(runDir.resolve("analysis.jsonl"), "@type", null));
            }
            List<Leaf> streamLeaves = Jsonl.read(streamFile, "type", null);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("pair", pair.getFileName().toString());
            out.put("events", events.size());
            out.put("stream_vs_events", summary(Venn.compare(streamLeaves, events)));
            Path nativeRows = pair.resolve("native.jsonl");
            if (Files.isRegularFile(nativeRows) && Files.size(nativeRows) > 0) {
                List<Leaf> nativeLeaves = Jsonl.read(nativeRows, "table", null);
                out.put("native_vs_events", summary(Venn.compare(nativeLeaves, events)));
                out.put("native_vs_stream", summary(Venn.compare(nativeLeaves, streamLeaves)));
                out.put("stream_vs_native", summary(Venn.compare(streamLeaves, nativeLeaves)));
            }
            JSON.writeValue(pair.resolve("venn.json").toFile(), out);
            System.out.println("VENN " + pair.getFileName() + " " + out.get("stream_vs_events"));
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
