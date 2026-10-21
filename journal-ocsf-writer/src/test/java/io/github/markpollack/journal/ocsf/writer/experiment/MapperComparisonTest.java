package io.github.markpollack.journal.ocsf.writer;

import com.dashjoin.jsonata.Functions;
import com.dashjoin.jsonata.Jsonata;
import com.dashjoin.jsonata.json.Json;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.schibsted.spt.data.jslt.Expression;
import com.schibsted.spt.data.jslt.Parser;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.io.StringReader;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Supplier;
import java.util.stream.Stream;

import static io.github.markpollack.journal.ocsf.writer.ExperimentSupport.Emitted;
import static io.github.markpollack.journal.ocsf.writer.ExperimentSupport.Stats;
import static io.github.markpollack.journal.ocsf.writer.ExperimentSupport.canonical;
import static io.github.markpollack.journal.ocsf.writer.ExperimentSupport.exact;
import static io.github.markpollack.journal.ocsf.writer.ExperimentSupport.records;
import static io.github.markpollack.journal.ocsf.writer.ExperimentSupport.replay;
import static io.github.markpollack.journal.ocsf.writer.WriterTestSupport.CHILD;
import static io.github.markpollack.journal.ocsf.writer.WriterTestSupport.CODEX;
import static io.github.markpollack.journal.ocsf.writer.WriterTestSupport.claudeStream;
import static io.github.markpollack.journal.ocsf.writer.WriterTestSupport.codexRollout;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * MAP-1: bounded in-process comparison of the direct-Java baseline mappers with JSLT and Dashjoin JSONata on the
 * representative cases (Claude assistant -> model_response + tool_call Requested; Claude user tool_result ->
 * tool_call Result; Codex CommandExecution -> tool_call Operation with derived attribution) plus precision and
 * filtering probes and measurements. The engine mappers plug into the real {@link SourceContext}; records outside
 * the cases go to the baseline, so the equality checks are meaningful only for case records (counted and asserted).
 *
 * <p>Compilation and the probes always run (fast, assertion-free on engine behaviour). Equivalence runs only with
 * {@code -Dmap1=true} so the normal build stays fast and is never broken by a candidate. The latency, end-to-end, heap
 * and concurrency measurements are {@code @Disabled}: the owner stopped them on 2026-10-21 because performance is not
 * the deciding criterion; they are kept for a later session. Results are written under {@code target/map1/}.
 */
class MapperComparisonTest {

    static final Path OUT = Path.of("target", "map1");
    static final int WARMUP = 1000;
    static final int SAMPLES = 5000;
    static final int E2E_WARMUP = 5;
    static final int E2E_SAMPLES = 15;

    record Candidate(String name, String fixture, String artifact, SourceDescriptor descriptor, Supplier<SourceMapper> baseline,
            Supplier<EngineMapper> engine, int expectedCaseRecords) {
        @Override
        public String toString() {
            return name;
        }
    }

    static Stream<Candidate> candidates() {
        return Stream.of(
                new Candidate("claude-stream/jslt", CHILD, "stream.jsonl", claudeStream(), ClaudeStreamMapper::new,
                        () -> new JsltEngineMapper(EngineMapper.Profile.CLAUDE_STREAM, false), 11),
                new Candidate("claude-stream/jsonata", CHILD, "stream.jsonl", claudeStream(), ClaudeStreamMapper::new,
                        () -> new JsonataEngineMapper(EngineMapper.Profile.CLAUDE_STREAM), 11),
                new Candidate("codex-rollout/jslt", CODEX, "rollout.jsonl", codexRollout(), CodexRolloutMapper::new,
                        () -> new JsltEngineMapper(EngineMapper.Profile.CODEX_ROLLOUT, false), 4),
                new Candidate("codex-rollout/jsonata", CODEX, "rollout.jsonl", codexRollout(), CodexRolloutMapper::new,
                        () -> new JsonataEngineMapper(EngineMapper.Profile.CODEX_ROLLOUT), 4));
    }

    // ------------------------------------------------------------------------------------------ always on

    @Test
    void programsCompileAndColdInitialisationIsMeasured() {
        StringBuilder sb = new StringBuilder("# Cold initialisation (single JVM; first compile includes class loading)\n\n");
        for (String p : List.of("claude-stream", "codex-rollout")) {
            String jslt = ExperimentSupport.resource(p + ".jslt");
            String jsonata = ExperimentSupport.resource(p + ".jsonata");
            long t0 = System.nanoTime();
            Expression e1 = JsltEngineMapper.compile(p, jslt);
            long t1 = System.nanoTime();
            Expression e2 = JsltEngineMapper.compile(p, jslt);
            long t2 = System.nanoTime();
            Jsonata j1 = Jsonata.jsonata(jsonata);
            long t3 = System.nanoTime();
            Jsonata j2 = Jsonata.jsonata(jsonata);
            long t4 = System.nanoTime();
            assertThat(e1).isNotNull();
            assertThat(e2).isNotNull();
            assertThat(j1).isNotNull();
            assertThat(j2).isNotNull();
            sb.append(String.format("- %s.jslt (%d chars): first compile %.2f ms, second compile %.2f ms%n", p, jslt.length(), (t1 - t0) / 1e6, (t2 - t1) / 1e6));
            sb.append(String.format("- %s.jsonata (%d chars): first parse %.2f ms, second parse %.2f ms%n", p, jsonata.length(), (t3 - t2) / 1e6, (t4 - t3) / 1e6));
        }
        long t0 = System.nanoTime();
        new ObjectMapper();
        sb.append(String.format("- new ObjectMapper() (JSLT input reader): %.2f ms%n", (System.nanoTime() - t0) / 1e6));
        write("cold-init.md", sb.toString());
    }

    static final String PROBE = "{\"i1\":1,\"f1\":1.0,\"f2\":2.50,\"e1\":1e5,\"big\":9007199254740993,\"d1\":0.1,\"d2\":0.30000000000000004,"
            + "\"neg0\":-0.0,\"E2\":1E+2,\"nul\":null,\"eobj\":{},\"earr\":[],\"f\":false,\"zero\":0,\"estr\":\"\",\"z\":\"last\",\"a\":\"first\"}";
    static final String P2_JSLT = "{\"z\": .z, \"a\": .a, \"nul\": .nul, \"eobj\": .eobj, \"earr\": .earr, \"f\": .f, \"zero\": .zero, \"estr\": .estr, \"absent\": .absent, \"big\": .big}";
    static final String P2_JSONATA = "{\"z\": z, \"a\": a, \"nul\": nul, \"eobj\": eobj, \"earr\": earr, \"f\": f, \"zero\": zero, \"estr\": estr, \"absent\": absent, \"big\": big}";
    static final String P3_JSLT = "{\"copy\": .big, \"plus0\": .big + 0, \"f1copy\": .f1, \"f1plus0\": .f1 + 0, \"f1_is_integer\": is-integer(.f1), \"f1_eq_i1\": .f1 == .i1, "
            + "\"f1_round_eq\": round(.f1) == .f1, \"e1copy\": .e1, \"e1plus0\": .e1 + 0, \"d1plus\": .d1 + 0.2, \"f2copy\": .f2}";
    static final String P3_JSONATA = "{\"copy\": big, \"plus0\": big + 0, \"f1copy\": f1, \"f1plus0\": f1 + 0, \"f1_eq_i1\": f1 = i1, \"f1_floor_eq\": f1 = $floor(f1), "
            + "\"e1copy\": e1, \"e1plus0\": e1 + 0, \"d1plus\": d1 + 0.2, \"f2copy\": f2}";

    @Test
    void precisionAndFilteringProbes() throws Exception {
        StringBuilder sb = new StringBuilder("# Precision and filtering probes (actual output text)\n\n");
        sb.append("Input: `").append(PROBE).append("`\n\n");
        sb.append("- baseline Jv (parse, write): `").append(Jv.write(Jv.parse(PROBE))).append("`\n");

        ObjectMapper plain = new ObjectMapper();
        ObjectMapper big = new ObjectMapper().enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
        for (Map.Entry<String, ObjectMapper> m : Map.of("jackson-default", plain, "jackson-USE_BIG_DECIMAL_FOR_FLOATS", big).entrySet()) {
            JsonNode in = m.getValue().readTree(PROBE);
            sb.append("\n## JSLT, input via ").append(m.getKey()).append("\n\n");
            sb.append("- P1 identity `.`: `").append(m.getValue().writeValueAsString(Parser.compileString(".").apply(in))).append("`\n");
            sb.append("- P3 arithmetic vs copy: `").append(m.getValue().writeValueAsString(Parser.compileString(P3_JSLT).apply(in))).append("`\n");
            sb.append("- P2 construct, default filter (drops null and empty containers): `")
                    .append(m.getValue().writeValueAsString(Parser.compileString(P2_JSLT).apply(in))).append("`\n");
            sb.append("- P2 construct, filter `. != null`: `")
                    .append(m.getValue().writeValueAsString(new Parser(new StringReader(P2_JSLT)).withObjectFilter(JsltEngineMapper.FILTER).compile().apply(in))).append("`\n");
            sb.append("- P2 construct, Java filter keep-all: `")
                    .append(m.getValue().writeValueAsString(new Parser(new StringReader(P2_JSLT)).withObjectFilter(v -> true).compile().apply(in))).append("`\n");
        }

        sb.append("\n## JSONata (Dashjoin), input via the library's Json.parseJson\n\n");
        Object in = Json.parseJson(PROBE);
        sb.append("- P1 identity `$`: `").append(Functions.string(Jsonata.jsonata("$").evaluate(in), false)).append("`\n");
        sb.append("- P3 arithmetic vs copy: `").append(Functions.string(Jsonata.jsonata(P3_JSONATA).evaluate(in), false)).append("`\n");
        sb.append("- P2 construct: `").append(Functions.string(Jsonata.jsonata(P2_JSONATA).evaluate(in), false)).append("`\n");
        sb.append("- Java types after parse: ").append(javaTypes(in)).append("\n");

        sb.append("\n## JSONata (Dashjoin), input converted from Jv (Integer/Long for integral values, Double otherwise)\n\n");
        Object jv = ExperimentSupport.toJava(Jv.parse(PROBE));
        sb.append("- P1 identity `$`: `").append(Functions.string(Jsonata.jsonata("$").evaluate(jv), false)).append("`\n");
        sb.append("- P3 arithmetic vs copy: `").append(Functions.string(Jsonata.jsonata(P3_JSONATA).evaluate(jv), false)).append("`\n");
        sb.append("- P2 construct: `").append(Functions.string(Jsonata.jsonata(P2_JSONATA).evaluate(jv), false)).append("`\n");

        sb.append("\n## JSONata (Dashjoin), input converted from Jv with every number as BigDecimal (exact value, not spelling)\n\n");
        Object bd = toJavaBigDecimal(Jv.parse(PROBE));
        try {
            sb.append("- P1 identity `$`: `").append(Functions.string(Jsonata.jsonata("$").evaluate(bd), false)).append("`\n");
            sb.append("- P3 arithmetic vs copy: `").append(Functions.string(Jsonata.jsonata(P3_JSONATA).evaluate(bd), false)).append("`\n");
            sb.append("- P2 construct: `").append(Functions.string(Jsonata.jsonata(P2_JSONATA).evaluate(bd), false)).append("`\n");
        }
        catch (RuntimeException e) {
            sb.append("- threw ").append(e).append("\n");
        }
        write("probes.md", sb.toString());
    }

    private static String javaTypes(Object in) {
        StringBuilder sb = new StringBuilder();
        ((Map<?, ?>) in).forEach((k, v) -> sb.append(k).append('=').append(v == null ? "null" : v.getClass().getSimpleName()).append(' '));
        return sb.toString().trim();
    }

    private static Object toJavaBigDecimal(Jv v) {
        if (v instanceof Jv.Obj o) {
            Map<String, Object> m = new LinkedHashMap<>();
            o.members().forEach((k, x) -> m.put(k, toJavaBigDecimal(x)));
            return m;
        }
        if (v instanceof Jv.Arr a) {
            List<Object> l = new ArrayList<>();
            a.elements().forEach(x -> l.add(toJavaBigDecimal(x)));
            return l;
        }
        if (v instanceof Jv.Num n) {
            return new BigDecimal(n.text());
        }
        return ExperimentSupport.toJava(v);
    }

    // ------------------------------------------------------------------------------------------ equivalence (-Dmap1=true)

    record Comparison(int records, int caseRecords, int baselineEvents, int engineEvents, List<String> canonicalDiffs, List<String> exactDiffs) {
    }

    static Comparison compare(Candidate c) {
        List<Emitted> base = replay(c.baseline().get(), c.fixture(), c.artifact(), c.descriptor());
        EngineMapper engine = c.engine().get();
        List<Emitted> eng = replay(engine, c.fixture(), c.artifact(), c.descriptor());
        List<String> canonicalDiffs = new ArrayList<>();
        List<String> exactDiffs = new ArrayList<>();
        int n = Math.min(base.size(), eng.size());
        for (int i = 0; i < n; i++) {
            Emitted b = base.get(i);
            Emitted e = eng.get(i);
            String where = "event " + i + " (record " + b.recordIndex() + ", class " + b.event().get("class_name").text() + " activity " + b.event().get("activity_id") + ")";
            if (b.recordIndex() != e.recordIndex() || !java.util.Objects.equals(b.owner(), e.owner())) {
                canonicalDiffs.add(where + ": routing differs: baseline record " + b.recordIndex() + " owner " + b.owner() + ", engine record " + e.recordIndex() + " owner " + e.owner());
                continue;
            }
            String cb = canonical(b.event());
            String ce = canonical(e.event());
            if (!cb.equals(ce)) {
                canonicalDiffs.add(where + ": " + ExperimentSupport.firstDifference(cb, ce));
            }
            String xb = exact(b.event());
            String xe = exact(e.event());
            if (!xb.equals(xe)) {
                exactDiffs.add(where + ": " + ExperimentSupport.firstDifference(xb, xe));
            }
        }
        if (base.size() != eng.size()) {
            canonicalDiffs.add("event count differs: baseline " + base.size() + ", engine " + eng.size());
        }
        return new Comparison(records(c.fixture(), c.artifact()).size(), engine.engineRecords, base.size(), eng.size(), canonicalDiffs, exactDiffs);
    }

    @ParameterizedTest
    @MethodSource("candidates")
    @EnabledIfSystemProperty(named = "map1", matches = "true")
    void engineOutputEqualsBaselineValues(Candidate c) {
        Comparison cmp = compare(c);
        write("equivalence-" + c.name().replace('/', '-') + ".md", "# " + c.name() + "\n\n- records: " + cmp.records() + "\n- case records through the engine: " + cmp.caseRecords()
                + "\n- events: baseline " + cmp.baselineEvents() + ", engine " + cmp.engineEvents() + "\n- value (canonical) differences: " + cmp.canonicalDiffs().size()
                + "\n- exact-text (order-sensitive) differences: " + cmp.exactDiffs().size() + "\n\n## canonical\n" + String.join("\n", cmp.canonicalDiffs())
                + "\n\n## exact\n" + String.join("\n", cmp.exactDiffs()) + "\n");
        assertThat(cmp.caseRecords()).as("case records routed to the engine").isEqualTo(c.expectedCaseRecords());
        assertThat(cmp.engineEvents()).isEqualTo(cmp.baselineEvents());
        assertThat(cmp.canonicalDiffs()).as(String.join("\n", cmp.canonicalDiffs())).isEmpty();
    }

    @ParameterizedTest
    @MethodSource("candidates")
    @EnabledIfSystemProperty(named = "map1", matches = "true")
    void engineOutputEqualsBaselineTextExactly(Candidate c) {
        Comparison cmp = compare(c);
        assertThat(cmp.exactDiffs()).as(String.join("\n", cmp.exactDiffs())).isEmpty();
    }

    // ------------------------------------------------------------------------------------------ measurements (-Dmap1=true)

    @Test
    @Disabled("not run: stopped by owner decision on 2026-10-21, performance is not the deciding criterion of MAP-1; kept for a later measurement session")
    @EnabledIfSystemProperty(named = "map1", matches = "true")
    void warmLatencyPerCaseRecord() {
        StringBuilder sb = new StringBuilder("# Warm per-record mapping latency\n\n");
        sb.append("Method: one mapper instance per row; the whole fixture is replayed once to establish state; then for each case kind the case records are mapped round-robin ")
                .append(WARMUP).append(" times (warm-up, discarded) and ").append(SAMPLES).append(" times with System.nanoTime around each map(record, ctx) call, into a discarding sink. ")
                .append("'map' is the full SourceMapper path (engine rows: record parse by the engine's reader, env build, evaluate, output re-read into Jv, emit through SourceContext); ")
                .append("'evaluate' is the engine's apply/evaluate call alone. Single thread. Not a production SLA.\n\n");
        sb.append("| fixture | case | records | mapper | map median us | map p95 us | map mean us | map max us | evaluate median us | evaluate p95 us |\n|---|---|---|---|---|---|---|---|---|---|\n");
        for (String fixture : List.of(CHILD, CODEX)) {
            boolean codex = fixture.equals(CODEX);
            String artifact = codex ? "rollout.jsonl" : "stream.jsonl";
            SourceDescriptor d = codex ? codexRollout() : claudeStream();
            List<NativeRecord> all = records(fixture, artifact);
            Map<String, List<NativeRecord>> kinds = new LinkedHashMap<>();
            for (NativeRecord r : all) {
                if (!r.parsed()) {
                    continue;
                }
                if (codex ? ExperimentSupport.isCodexCase(r.json()) : ExperimentSupport.isClaudeCase(r.json())) {
                    String kind = codex ? "c: CommandExecution -> tool_call Operation" : "assistant".equals(r.json().member("type").text())
                            ? "a: assistant -> model_response (+tool_call Requested)" : "b: user tool_result -> tool_call Result";
                    kinds.computeIfAbsent(kind, k -> new ArrayList<>()).add(r);
                }
            }
            Map<String, Supplier<SourceMapper>> mappers = new LinkedHashMap<>();
            mappers.put("baseline (direct Java)", codex ? CodexRolloutMapper::new : ClaudeStreamMapper::new);
            mappers.put("JSLT 0.1.14", () -> new JsltEngineMapper(codex ? EngineMapper.Profile.CODEX_ROLLOUT : EngineMapper.Profile.CLAUDE_STREAM, false));
            mappers.put("JSONata (Dashjoin 0.9.8)", () -> new JsonataEngineMapper(codex ? EngineMapper.Profile.CODEX_ROLLOUT : EngineMapper.Profile.CLAUDE_STREAM));
            for (Map.Entry<String, List<NativeRecord>> kind : kinds.entrySet()) {
                for (Map.Entry<String, Supplier<SourceMapper>> m : mappers.entrySet()) {
                    SourceMapper mapper = m.getValue().get();
                    SourceContext ctx = ExperimentSupport.context(fixture, d, ExperimentSupport.DISCARD);
                    for (NativeRecord r : all) {
                        mapper.map(r, ctx);
                    }
                    List<NativeRecord> rs = kind.getValue();
                    for (int i = 0; i < WARMUP; i++) {
                        mapper.map(rs.get(i % rs.size()), ctx);
                    }
                    long[] map = new long[SAMPLES];
                    long[] eval = new long[SAMPLES];
                    for (int i = 0; i < SAMPLES; i++) {
                        NativeRecord r = rs.get(i % rs.size());
                        long t0 = System.nanoTime();
                        mapper.map(r, ctx);
                        map[i] = System.nanoTime() - t0;
                        eval[i] = mapper instanceof EngineMapper em ? em.lastEvaluateNanos : 0;
                    }
                    Stats ms = Stats.of(map);
                    Stats es = Stats.of(eval);
                    sb.append(String.format("| %s | %s | %s | %s | %.2f | %.2f | %.2f | %.2f | %s | %s |%n", fixture, kind.getKey(), indices(rs), m.getKey(),
                            ms.medianUs(), ms.p95Us(), ms.meanUs(), ms.maxUs(), mapper instanceof EngineMapper ? String.format("%.2f", es.medianUs()) : "n/a",
                            mapper instanceof EngineMapper ? String.format("%.2f", es.p95Us()) : "n/a"));
                }
            }
        }
        write("warm-latency.md", sb.toString());
    }

    private static String indices(List<NativeRecord> rs) {
        StringBuilder sb = new StringBuilder();
        for (NativeRecord r : rs) {
            sb.append(sb.length() == 0 ? "" : ",").append(r.index());
        }
        return sb.toString();
    }

    @Test
    @Disabled("not run: stopped by owner decision on 2026-10-21, performance is not the deciding criterion of MAP-1; kept for a later measurement session")
    @EnabledIfSystemProperty(named = "map1", matches = "true")
    void endToEndEmissionThroughTheWriterBaselineOnly(@TempDir Path work) {
        StringBuilder sb = new StringBuilder("# End-to-end emission through OcsfRunWriter (baseline mappers only)\n\n");
        sb.append("Method: open the writer in a fresh directory, declare the source, feed every record with SourceInput.record, end with EOF, complete(); wall time from open to complete ")
                .append("with the ticking test clock; ").append(E2E_WARMUP).append(" warm-up replays discarded, ").append(E2E_SAMPLES)
                .append(" measured. flush=0 persists at end only; flush=1 flushes (events appended, run.json rewritten atomically) after every record. ")
                .append("Engines are not pluggable into the writer without changing SourceMapper.forProfile (main code), which this experiment does not do.\n\n");
        sb.append("| fixture | records | flush every | median ms | p95 ms | max ms | median us/record | events.jsonl bytes |\n|---|---|---|---|---|---|---|---|\n");
        int run = 0;
        for (String fixture : List.of(CHILD, CODEX)) {
            boolean codex = fixture.equals(CODEX);
            String artifact = codex ? "rollout.jsonl" : "stream.jsonl";
            Path file = WriterTestSupport.nativeOf(fixture, artifact);
            int records = records(fixture, artifact).size();
            for (int flush : new int[] {0, 1}) {
                long[] samples = new long[E2E_SAMPLES];
                long bytes = 0;
                for (int i = 0; i < E2E_WARMUP + E2E_SAMPLES; i++) {
                    Path dir = work.resolve("run-" + (run++));
                    WriterOptions options = WriterTestSupport.options().withAutoFlush(flush);
                    long t0 = System.nanoTime();
                    try (OcsfRunWriter w = OcsfRunWriter.open(dir, WriterTestSupport.run(fixture), SchemaPin.bundled(), options)) {
                        SourceInput in = w.source(codex ? codexRollout() : claudeStream());
                        Replay.feedRecords(in, file, options.clock());
                        w.complete();
                    }
                    long dt = System.nanoTime() - t0;
                    if (i >= E2E_WARMUP) {
                        samples[i - E2E_WARMUP] = dt;
                    }
                    try {
                        bytes = Files.size(dir.resolve("events.jsonl"));
                    }
                    catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                }
                Stats s = Stats.of(samples);
                sb.append(String.format("| %s | %d | %d | %.2f | %.2f | %.2f | %.1f | %d |%n", fixture, records, flush, s.medianUs() / 1000, s.p95Us() / 1000, s.maxUs() / 1000,
                        s.medianUs() / records, bytes));
            }
        }
        write("end-to-end.md", sb.toString());
    }

    @Test
    @Disabled("not run: stopped by owner decision on 2026-10-21, performance is not the deciding criterion of MAP-1; kept for a later measurement session")
    @EnabledIfSystemProperty(named = "map1", matches = "true")
    void heapRetainedByMapperState() {
        StringBuilder sb = new StringBuilder("# Heap retained (approximate)\n\n");
        sb.append("Method: Runtime used heap after four System.gc() calls, measured before creating the mapper, after creating it (compiled program, readers), and after replaying the fixture once ")
                .append("with a discarding sink (the state a mapper retains at EOF). Single-shot deltas in KB; includes JIT/class-loading side effects of the first use of each library, ")
                .append("so treat values below ~200 KB as noise rather than a measurement.\n\n");
        sb.append("| fixture | mapper | create KB | after replay KB (delta from create) |\n|---|---|---|---|\n");
        for (String fixture : List.of(CHILD, CODEX)) {
            boolean codex = fixture.equals(CODEX);
            String artifact = codex ? "rollout.jsonl" : "stream.jsonl";
            SourceDescriptor d = codex ? codexRollout() : claudeStream();
            List<NativeRecord> all = records(fixture, artifact);
            Map<String, Supplier<SourceMapper>> mappers = new LinkedHashMap<>();
            mappers.put("baseline (direct Java)", codex ? CodexRolloutMapper::new : ClaudeStreamMapper::new);
            mappers.put("JSLT 0.1.14", () -> new JsltEngineMapper(codex ? EngineMapper.Profile.CODEX_ROLLOUT : EngineMapper.Profile.CLAUDE_STREAM, false));
            mappers.put("JSONata (Dashjoin 0.9.8)", () -> new JsonataEngineMapper(codex ? EngineMapper.Profile.CODEX_ROLLOUT : EngineMapper.Profile.CLAUDE_STREAM));
            for (Map.Entry<String, Supplier<SourceMapper>> m : mappers.entrySet()) {
                long before = ExperimentSupport.usedHeapAfterGc();
                SourceMapper mapper = m.getValue().get();
                long created = ExperimentSupport.usedHeapAfterGc();
                SourceContext ctx = ExperimentSupport.context(fixture, d, ExperimentSupport.DISCARD);
                for (NativeRecord r : all) {
                    mapper.map(r, ctx);
                }
                long replayed = ExperimentSupport.usedHeapAfterGc();
                sb.append(String.format("| %s | %s | %d | %d |%n", fixture, m.getKey(), (created - before) / 1024, (replayed - created) / 1024));
                assertThat(mapper).isNotNull();
                assertThat(ctx).isNotNull();
            }
        }
        write("heap.md", sb.toString());
    }

    @Test
    @Disabled("not run: stopped by owner decision on 2026-10-21, performance is not the deciding criterion of MAP-1; kept for a later measurement session")
    @EnabledIfSystemProperty(named = "map1", matches = "true")
    void compiledProgramsAreSafeToShareAcrossThreads() throws Exception {
        StringBuilder sb = new StringBuilder("# Concurrency check\n\n");
        sb.append("Method: one compiled program per candidate shared by one mapper per thread (a mapper is per source and single-threaded by the writer's contract); ")
                .append("each thread replays the fixture repeatedly and compares every event's canonical text with a single-threaded replay. ")
                .append("Both programs of an engine (Claude and Codex) are also interleaved on the same threads, which exercises Dashjoin's static per-thread instance.\n\n");
        int threads = 4;
        int perThread = 150;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            for (String engineName : List.of("jslt", "jsonata")) {
                List<Candidate> cs = candidates().filter(c -> c.name().endsWith("/" + engineName)).toList();
                Expression jsltClaude = JsltEngineMapper.compile(EngineMapper.Profile.CLAUDE_STREAM);
                Expression jsltCodex = JsltEngineMapper.compile(EngineMapper.Profile.CODEX_ROLLOUT);
                Jsonata jsonataClaude = JsonataEngineMapper.compile(EngineMapper.Profile.CLAUDE_STREAM);
                Jsonata jsonataCodex = JsonataEngineMapper.compile(EngineMapper.Profile.CODEX_ROLLOUT);
                java.util.function.Function<Candidate, EngineMapper> shared = c -> {
                    boolean codex = c.fixture().equals(CODEX);
                    EngineMapper.Profile p = codex ? EngineMapper.Profile.CODEX_ROLLOUT : EngineMapper.Profile.CLAUDE_STREAM;
                    return engineName.equals("jslt") ? new JsltEngineMapper(p, false, codex ? jsltCodex : jsltClaude) : new JsonataEngineMapper(p, codex ? jsonataCodex : jsonataClaude);
                };
                Map<String, List<String>> expected = new LinkedHashMap<>();
                for (Candidate c : cs) {
                    expected.put(c.name(), replay(shared.apply(c), c.fixture(), c.artifact(), c.descriptor()).stream().map(e -> canonical(e.event())).toList());
                }
                List<Future<Integer>> futures = new ArrayList<>();
                for (int t = 0; t < threads; t++) {
                    futures.add(pool.submit(() -> {
                        int mismatches = 0;
                        for (int i = 0; i < perThread; i++) {
                            for (Candidate c : cs) {
                                List<String> got = replay(shared.apply(c), c.fixture(), c.artifact(), c.descriptor()).stream().map(e -> canonical(e.event())).toList();
                                if (!got.equals(expected.get(c.name()))) {
                                    mismatches++;
                                }
                            }
                        }
                        return mismatches;
                    }));
                }
                int mismatches = 0;
                for (Future<Integer> f : futures) {
                    mismatches += f.get();
                }
                sb.append("- ").append(engineName).append(": ").append(threads).append(" threads x ").append(perThread).append(" x ").append(cs.size())
                        .append(" fixture replays sharing the two compiled programs: ").append(mismatches).append(" mismatching replays\n");
                assertThat(mismatches).as(engineName).isZero();
            }
        }
        finally {
            pool.shutdownNow();
        }
        write("concurrency.md", sb.toString());
    }

    // ------------------------------------------------------------------------------------------ output

    static void write(String name, String text) {
        try {
            Files.createDirectories(OUT);
            Files.writeString(OUT.resolve(name), text, StandardCharsets.UTF_8);
            System.out.println(text);
        }
        catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
