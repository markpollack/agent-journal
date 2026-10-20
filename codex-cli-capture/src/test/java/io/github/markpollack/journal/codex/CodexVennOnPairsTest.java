package io.github.markpollack.journal.codex;

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
 * Diagnostic driver (Stage 13, package 2): runs Venn v2 over captured Codex PAIRS. Each pair directory holds
 * {@code rollout-1.jsonl} (the session file Codex wrote; the parser's input) and, optionally, {@code stream.jsonl}
 * (the {@code codex exec --json} stdout of the same run). For each pair it records the rollout through
 * {@link CodexRunRecorder} and writes {@code venn.json}: rollout against the events, stream against the events, and
 * the two native channels against each other. Skipped unless {@code -Dvenn.codex.pairs=<dir>} is given.
 */
class CodexVennOnPairsTest {

    private static final ObjectMapper JSON = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    @AfterEach
    void resetJournal() {
        Journal.reset();
    }

    @Test
    void writeVennReportsForEveryPair() throws Exception {
        String root = System.getProperty("venn.codex.pairs");
        Assumptions.assumeTrue(root != null && !root.isBlank(), "no -Dvenn.codex.pairs");
        List<Path> pairs;
        try (Stream<Path> s = Files.list(Path.of(root))) {
            pairs = s.filter(p -> Files.isRegularFile(p.resolve("rollout-1.jsonl"))).sorted().toList();
        }
        assertThat(pairs).as("pair directories under " + root).isNotEmpty();

        for (Path pair : pairs) {
            Journal.reset();
            Path rollout = pair.resolve("rollout-1.jsonl");
            Path dir = pair.resolve("journal");
            Files.createDirectories(dir);
            CodexPhaseCapture capture = CodexSessionParser.parse(rollout, "run", "prompt");
            Journal.configure(new JsonFileStorage(dir));
            Run run = Journal.run("exp").start();
            new CodexRunRecorder(run).recordPhase(capture);
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
            List<Leaf> rolloutLeaves = Jsonl.read(rollout, "type", null);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("pair", pair.getFileName().toString());
            out.put("events", events.size());
            out.put("rollout_vs_events", summary(Venn.compare(rolloutLeaves, events)));
            Path stream = pair.resolve("stream.jsonl");
            if (Files.isRegularFile(stream) && Files.size(stream) > 0) {
                List<Leaf> streamLeaves = Jsonl.read(stream, "type", null);
                out.put("stream_vs_events", summary(Venn.compare(streamLeaves, events)));
                out.put("stream_vs_rollout", summary(Venn.compare(streamLeaves, rolloutLeaves)));
                out.put("rollout_vs_stream", summary(Venn.compare(rolloutLeaves, streamLeaves)));
            }
            JSON.writeValue(pair.resolve("venn.json").toFile(), out);
            System.out.println("VENN " + pair.getFileName() + " " + out.get("rollout_vs_events"));
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
