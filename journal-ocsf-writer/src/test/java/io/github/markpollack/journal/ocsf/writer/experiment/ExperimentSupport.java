package io.github.markpollack.journal.ocsf.writer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.BooleanNode;
import com.fasterxml.jackson.databind.node.DecimalNode;
import com.fasterxml.jackson.databind.node.LongNode;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * MAP-1 experiment support (NON-PRODUCTION, test scope). Lives in directory {@code experiment/} but in the writer's
 * package because the seam it exercises ({@link SourceMapper}, {@link SourceContext}, {@link NativeRecord#classify})
 * is package-private by design; nothing here is referenced by main code.
 *
 * <p>Holds the Java glue every engine candidate needs regardless of expression language: the capturing sink, the
 * fixture replay, the per-record environment the engine is fed (time decision, source identity, class table, contract
 * tables, captions, cross-record state), the value conversions between the writer's {@link Jv} tree and each engine's
 * object model, the emission of engine output through the real {@link SourceContext}, and canonical/exact comparison.
 */
final class ExperimentSupport {

    private ExperimentSupport() {
    }

    // ------------------------------------------------------------------------------------------ sinks and replay

    record Emitted(int recordIndex, String owner, Jv.Obj event) {
    }

    static final class CaptureSink implements SourceContext.Sink {
        final List<Emitted> emitted = new ArrayList<>();
        int current;

        @Override
        public void emit(String owner, Jv.Obj event) {
            emitted.add(new Emitted(current, owner, event));
        }
    }

    static final SourceContext.Sink DISCARD = (owner, event) -> { };

    private static final SchemaPin PIN = SchemaPin.bundled();
    private static final Map<String, RunDescriptor> RUNS = new java.util.concurrent.ConcurrentHashMap<>();
    private static final Map<String, List<NativeRecord>> RECORDS = new java.util.concurrent.ConcurrentHashMap<>();

    static SourceContext context(String fixture, SourceDescriptor descriptor, SourceContext.Sink sink) {
        return new SourceContext(descriptor, RUNS.computeIfAbsent(fixture, WriterTestSupport::run), new MappingContracts(), PIN, sink);
    }

    /** The fixture's records classified exactly as the writer classifies live input (record form, one per line); cached, immutable. */
    static List<NativeRecord> records(String fixture, String artifact) {
        return RECORDS.computeIfAbsent(fixture + "/" + artifact, k -> {
            List<LineFramer.Framed> lines = Replay.lines(WriterTestSupport.bytes(WriterTestSupport.nativeOf(fixture, artifact)));
            List<NativeRecord> out = new ArrayList<>();
            Instant arrival = Instant.parse("2026-10-21T00:00:00Z");
            int i = 0;
            for (LineFramer.Framed f : lines) {
                out.add(NativeRecord.classify(++i, f.raw(), f.ending(), arrival));
            }
            return List.copyOf(out);
        });
    }

    static List<Emitted> replay(SourceMapper mapper, String fixture, String artifact, SourceDescriptor descriptor) {
        CaptureSink sink = new CaptureSink();
        SourceContext ctx = context(fixture, descriptor, sink);
        for (NativeRecord r : records(fixture, artifact)) {
            sink.current = r.index();
            mapper.map(r, ctx);
        }
        return sink.emitted;
    }

    static String resource(String name) {
        try (InputStream in = ExperimentSupport.class.getResourceAsStream("/experiment/" + name)) {
            if (in == null) {
                throw new IllegalStateException("missing resource /experiment/" + name);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    // ------------------------------------------------------------------------------------------ case routing (Java glue)

    /** Case a or b: an assistant record with a message object, or a user record whose message has a tool_result block. */
    static boolean isClaudeCase(Jv o) {
        String type = MapperSupport.text(o, "type");
        if (!(o.member("message") instanceof Jv.Obj m)) {
            return false;
        }
        if ("assistant".equals(type)) {
            return true;
        }
        if ("user".equals(type)) {
            for (Jv b : m.member("content") == null ? List.<Jv>of() : m.member("content").elements()) {
                if ("tool_result".equals(MapperSupport.text(b, "type"))) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Case c: event_msg / item_completed / CommandExecution. */
    static boolean isCodexCase(Jv o) {
        return "event_msg".equals(MapperSupport.text(o, "type")) && "item_completed".equals(MapperSupport.text(o, "payload", "type"))
                && "CommandExecution".equals(MapperSupport.text(o, "payload", "item", "type"));
    }

    static boolean isCodexCustomToolCall(Jv o) {
        return "response_item".equals(MapperSupport.text(o, "type")) && "custom_tool_call".equals(MapperSupport.text(o, "payload", "type"));
    }

    /** The cross-record state the baseline keeps for case c, as the engine is fed it: one object per custom_tool_call seen. */
    static Jv.Obj callSeen(Jv o) {
        Jv p = o.member("payload");
        return new Jv.Obj().put("turn", MapperSupport.text(p, "internal_chat_message_metadata_passthrough", "turn_id"))
                .put("call_id", MapperSupport.text(p, "call_id")).put("name", MapperSupport.text(p, "name")).put("input", MapperSupport.text(p, "input"));
    }

    // ------------------------------------------------------------------------------------------ engine environment (Java glue)

    /** Run, schema, contract and caption facts that do not change per record; built once per mapper instance. */
    static Jv.Obj staticEnv(SourceContext ctx, String contract) {
        Jv.Obj run = new Jv.Obj().put("run_uid", ctx.run.runUid()).put("invocation_index", ctx.run.invocationIndex()).put("product", ctx.run.cli().product());
        if (ctx.run.sessionUid() != null) {
            run.put("session_uid", ctx.run.sessionUid());
        }
        Jv.Obj ocsf = new Jv.Obj().put("version", ctx.pin.ocsfVersion()).put("ext_name", SchemaPin.EXTENSION)
                .put("ext_uid", Integer.toString(ctx.pin.extensionUid())).put("ext_version", ctx.pin.extensionVersion());
        Jv.Obj classes = new Jv.Obj();
        for (String name : List.of("model_response", "tool_call")) {
            SchemaPin.ClassDef c = ctx.pin.classNamed(name);
            Jv.Obj acts = new Jv.Obj();
            c.activities().forEach(acts::put);
            classes.put(name, new Jv.Obj().put("class_uid", c.classUid()).put("caption", c.caption()).put("category_uid", c.categoryUid())
                    .put("category_name", c.categoryName()).put("activities", acts));
        }
        Jv doc = ctx.contracts.contract(contract);
        Jv.Obj captions = new Jv.Obj().put("tool_kind", captions(MapperSupport.TOOL_KIND)).put("block_kind", captions(MapperSupport.BLOCK_KIND))
                .put("status", captions(MapperSupport.STATUS)).put("status_basis", captions(MapperSupport.STATUS_BASIS))
                .put("decision", captions(MapperSupport.DECISION)).put("outcome", captions(MapperSupport.OUTCOME));
        Jv.Obj source = new Jv.Obj().put("source_kind_id", ctx.source.kind()).put("source_kind", ctx.source.kindCaption()).put("artifact", ctx.source.artifact());
        return new Jv.Obj().put("run", run).put("ocsf", ocsf).put("contract_uid", ctx.contracts.uid(contract)).put("classes", classes)
                .put("tool_kind", doc.member("tool_kind_table")).put("block_kind", doc.member("block_kind_table")).put("captions", captions).put("source", source);
    }

    private static Jv.Obj captions(Map<Integer, String> table) {
        Jv.Obj o = new Jv.Obj();
        new TreeMap<>(table).forEach((k, v) -> o.put(Integer.toString(k), v));
        return o;
    }

    /** The per-record environment: the time decision Java already made, the record's identity, and case c's state. */
    static Jv.Obj recordEnv(NativeRecord r, MapperSupport.Time t, List<Jv.Obj> calls) {
        Jv.Obj env = new Jv.Obj()
                .put("time", new Jv.Obj().put("ms", t.ms()).put("dt", MapperSupport.msToRfc3339(t.ms())).put("basis_id", t.basisId())
                        .put("basis", MapperSupport.TIME_BASIS.get(t.basisId())).put("detail", t.detail()))
                .put("record_index", r.index()).put("record_bytes", r.raw().length).put("line_ending", r.ending());
        if (calls != null) {
            env.put("calls", new Jv.Arr(calls));
        }
        return env;
    }

    /** Routes engine output through the real context: family inventory, declared-rule check and count, owner routing. */
    static void emit(SourceContext ctx, NativeRecord r, String contract, Jv out) {
        if (!(out instanceof Jv.Obj res)) {
            throw new IllegalStateException("engine returned no object for case record " + r.index() + ": " + out);
        }
        String owner = MapperSupport.text(res, "owner");
        ctx.family(MapperSupport.text(res, "family", "name"), r.raw().length, MapperSupport.text(res, "family", "disposition"));
        for (Jv ev : res.member("events").elements()) {
            Jv.Obj e = (Jv.Obj) ev;
            ctx.contracts.rule(contract, MapperSupport.text(e, "unmapped", "rule_id"));
            ctx.emit(owner, e);
        }
    }

    // ------------------------------------------------------------------------------------------ conversions

    /** Jv to Jackson tree (env only; records are parsed from their text by the engine's own reader). */
    static JsonNode toJackson(Jv v, ObjectMapper mapper) {
        if (v instanceof Jv.Obj o) {
            ObjectNode n = mapper.createObjectNode();
            o.members().forEach((k, x) -> n.set(k, toJackson(x, mapper)));
            return n;
        }
        if (v instanceof Jv.Arr a) {
            ArrayNode n = mapper.createArrayNode();
            a.elements().forEach(x -> n.add(toJackson(x, mapper)));
            return n;
        }
        if (v instanceof Jv.Str s) {
            return new TextNode(s.value());
        }
        if (v instanceof Jv.Num num) {
            return num.integral() && num.value().precision() <= 18 ? new LongNode(num.value().longValueExact()) : new DecimalNode(num.value());
        }
        if (v instanceof Jv.Bool b) {
            return BooleanNode.valueOf(b.value());
        }
        return NullNode.getInstance();
    }

    /** Jv to the plain Java model Dashjoin JSONata evaluates (Map/List/String/Integer/Long/Double/Boolean/null). */
    static Object toJava(Jv v) {
        if (v instanceof Jv.Obj o) {
            Map<String, Object> m = new LinkedHashMap<>();
            o.members().forEach((k, x) -> m.put(k, toJava(x)));
            return m;
        }
        if (v instanceof Jv.Arr a) {
            List<Object> l = new ArrayList<>();
            a.elements().forEach(x -> l.add(toJava(x)));
            return l;
        }
        if (v instanceof Jv.Str s) {
            return s.value();
        }
        if (v instanceof Jv.Num num) {
            if (num.integral() && num.value().precision() <= 18) {
                long l = num.value().longValueExact();
                return (int) l == l ? (Object) (int) l : (Object) l;
            }
            return num.value().doubleValue();
        }
        if (v instanceof Jv.Bool b) {
            return b.value();
        }
        return null;
    }

    // ------------------------------------------------------------------------------------------ comparison

    /** Compact text with members in the order produced, after removing journal.sequence. */
    static String exact(Jv.Obj event) {
        return Jv.write(withoutSequence(event));
    }

    /** Compact text with members sorted recursively, after removing journal.sequence: value equality regardless of order. */
    static String canonical(Jv.Obj event) {
        StringBuilder sb = new StringBuilder();
        canonical(withoutSequence(event), sb);
        return sb.toString();
    }

    private static Jv.Obj withoutSequence(Jv.Obj event) {
        Jv.Obj copy = (Jv.Obj) Jv.copy(event);
        if (copy.member("journal") instanceof Jv.Obj j) {
            j.members().remove("sequence");
        }
        return copy;
    }

    private static void canonical(Jv v, StringBuilder sb) {
        if (v instanceof Jv.Obj o) {
            sb.append('{');
            boolean first = true;
            for (Map.Entry<String, Jv> m : new TreeMap<>(o.members()).entrySet()) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                Jv.write(new Jv.Str(m.getKey()), sb);
                sb.append(':');
                canonical(m.getValue(), sb);
            }
            sb.append('}');
        }
        else if (v instanceof Jv.Arr a) {
            sb.append('[');
            for (int i = 0; i < a.elements().size(); i++) {
                if (i > 0) {
                    sb.append(',');
                }
                canonical(a.elements().get(i), sb);
            }
            sb.append(']');
        }
        else {
            Jv.write(v, sb);
        }
    }

    /** The first position at which two texts differ, with context, for findings. */
    static String firstDifference(String a, String b) {
        int i = 0;
        while (i < a.length() && i < b.length() && a.charAt(i) == b.charAt(i)) {
            i++;
        }
        int from = Math.max(0, i - 60);
        return "at char " + i + ":\n    baseline: ..." + a.substring(from, Math.min(a.length(), i + 100)) + "\n    engine:   ..." + b.substring(from, Math.min(b.length(), i + 100));
    }

    // ------------------------------------------------------------------------------------------ statistics

    record Stats(int n, double medianUs, double p95Us, double maxUs, double meanUs) {
        static Stats of(long[] nanos) {
            long[] s = nanos.clone();
            Arrays.sort(s);
            double sum = 0;
            for (long v : s) {
                sum += v;
            }
            return new Stats(s.length, s[s.length / 2] / 1000.0, s[(int) Math.min(s.length - 1, Math.floor(s.length * 0.95))] / 1000.0, s[s.length - 1] / 1000.0, sum / s.length / 1000.0);
        }

        @Override
        public String toString() {
            return String.format("n=%d median=%.2fus p95=%.2fus mean=%.2fus max=%.2fus", n, medianUs, p95Us, meanUs, maxUs);
        }
    }

    static long usedHeapAfterGc() {
        for (int i = 0; i < 4; i++) {
            System.gc();
            try {
                Thread.sleep(50);
            }
            catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        Runtime rt = Runtime.getRuntime();
        return rt.totalMemory() - rt.freeMemory();
    }
}
