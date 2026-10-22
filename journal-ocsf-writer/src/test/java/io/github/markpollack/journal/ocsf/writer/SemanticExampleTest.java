package io.github.markpollack.journal.ocsf.writer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static io.github.markpollack.journal.ocsf.writer.WriterTestSupport.FIXTURES;
import static io.github.markpollack.journal.ocsf.writer.WriterTestSupport.options;
import static io.github.markpollack.journal.ocsf.writer.WriterTestSupport.run;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The reviewed semantic examples of the mapping catalog ({@code ocsf/mapping-catalog/examples/*.json}) against what
 * the writer actually produces from each example's native input. An example is authored from pinned vendor sources and
 * native observations, never from the mapper or the oracle profile, so it can reject a meaning both of them share. Each
 * expected value is compared exactly at its event pointer; each counterexample must differ from the expected example
 * and must not describe the writer's output. Every comparison is written to
 * {@code target/semantic-examples/example-results.json} with the native artifact's hash, expected and actual values.
 */
class SemanticExampleTest {

    static final Path EXAMPLES = FIXTURES.getParent().resolve("mapping-catalog").resolve("examples");

    /** One compared value: where, what the reviewed example expects, what the writer produced. */
    record Compared(String example, int record, String pointer, String expected, String actual, String outcome) {
    }

    @Test
    void everyReviewedExampleDescribesTheWritersOutputAndNoCounterexampleDoes(@TempDir Path work) throws IOException {
        List<Compared> results = new ArrayList<>();
        List<String> problems = new ArrayList<>();
        List<Map<String, Object>> examples = new ArrayList<>();
        for (Path file : examples()) {
            Jv ex = Jv.parse(Files.readString(file, StandardCharsets.UTF_8));
            String id = ex.member("id").text();
            Jv nat = ex.member("native");
            byte[] input = nativeBytes(nat, work.resolve(id));
            String sha = SchemaPin.sha256(input);
            if (nat.member("sha256") != null && !nat.member("sha256").text().equals(sha)) {
                problems.add(id + ": native input sha256 " + sha + " differs from the example's " + nat.member("sha256").text());
            }
            List<Jv> events = write(nat, input, work.resolve(id));
            for (Jv exp : ex.member("expected").elements()) {
                Jv event = find(events, exp);
                int record = integer(exp.member("record"));
                if (event == null) {
                    problems.add(id + ": no " + exp.member("class").text() + " activity " + exp.member("activity_id").text() + " event for record " + record);
                    continue;
                }
                for (Map.Entry<String, Jv> v : ((Jv.Obj) exp.member("values")).members().entrySet()) {
                    Jv actual = at(event, v.getKey());
                    boolean same = same(v.getValue(), actual);
                    results.add(new Compared(id, record, v.getKey(), Jv.write(v.getValue()), actual == null ? null : Jv.write(actual), same ? "MATCHES" : "DIFFERS"));
                    if (!same) {
                        problems.add(id + " record " + record + " " + v.getKey() + ": expected " + Jv.write(v.getValue()) + ", writer produced " + (actual == null ? "nothing" : Jv.write(actual)));
                    }
                }
                for (Jv absent : exp.member("absent") == null ? List.<Jv>of() : exp.member("absent").elements()) {
                    Jv actual = at(event, absent.text());
                    results.add(new Compared(id, record, absent.text(), null, actual == null ? null : Jv.write(actual), actual == null ? "ABSENT" : "PRESENT"));
                    if (actual != null) {
                        problems.add(id + " record " + record + " " + absent.text() + ": expected absent, writer produced " + Jv.write(actual));
                    }
                }
            }
            for (Jv counter : ex.member("counterexamples") == null ? List.<Jv>of() : ex.member("counterexamples").elements()) {
                String cid = id + "#" + counter.member("id").text();
                Jv exp = expectedFor(ex, counter);
                if (exp == null || !rejects(exp, (Jv.Obj) counter.member("values"))) {
                    problems.add(cid + ": the counterexample is not rejected by the expected example (no differing pointer)");
                }
                Jv event = find(events, counter);
                boolean describes = event != null && !differs(counter.member("values"), event);
                results.add(new Compared(cid, integer(counter.member("record")), String.join(",", ((Jv.Obj) counter.member("values")).members().keySet()),
                        Jv.write(counter.member("values")), null, describes ? "COUNTEREXAMPLE_HOLDS" : "COUNTEREXAMPLE_REJECTED"));
                if (describes) {
                    problems.add(cid + ": the writer's output satisfies the counterexample (" + counter.member("why").text() + ")");
                }
            }
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", id);
            m.put("native_sha256", sha);
            m.put("evidence_kind", nat.member("evidence_kind").text());
            examples.add(m);
        }
        writeResults(examples, results);
        assertThat(examples).as("reviewed examples").isNotEmpty();
        assertThat(problems).isEmpty();
    }

    // ---- inputs -----------------------------------------------------------------------------------------------------

    static List<Path> examples() throws IOException {
        try (Stream<Path> s = Files.list(EXAMPLES)) {
            return s.filter(p -> p.toString().endsWith(".json")).sorted().toList();
        }
    }

    /** The native bytes: a committed fixture's native artifact, or the example's own labelled synthetic lines. */
    static byte[] nativeBytes(Jv nat, Path dir) throws IOException {
        if (nat.member("fixture") != null) {
            return Files.readAllBytes(FIXTURES.resolve(nat.member("fixture").text()).resolve("native").resolve(nat.member("artifact").text()));
        }
        StringBuilder sb = new StringBuilder();
        for (Jv line : nat.member("lines").elements()) {
            sb.append(line.text()).append('\n');
        }
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    /** Replays the native input through the writer and returns its execution events in order. */
    static List<Jv> write(Jv nat, byte[] input, Path dir) throws IOException {
        Files.createDirectories(dir);
        Path in = dir.resolve(nat.member("artifact").text());
        Files.write(in, input);
        String spec = nat.member("fixture") != null ? nat.member("fixture").text() : nat.member("run_spec_fixture").text();
        Path core = dir.resolve("core");
        try (OcsfRunWriter w = OcsfRunWriter.open(core, run(spec), SchemaPin.bundled(), options())) {
            Replay.feedRecords(w.source(source(nat)), in, options().clock());
            w.complete();
        }
        List<Jv> out = new ArrayList<>();
        for (String line : WriterTestSupport.eventLines(core.resolve("events.jsonl"))) {
            out.add(Jv.parse(line));
        }
        return out;
    }

    static SourceDescriptor source(Jv nat) {
        String profile = nat.member("profile").text();
        return switch (profile) {
            case "codex_stream" -> WriterTestSupport.codexStream();
            case "claude_stream" -> WriterTestSupport.claudeStreamStdout();
            default -> throw new IllegalArgumentException("no example source for profile " + profile);
        };
    }

    // ---- comparison -------------------------------------------------------------------------------------------------

    static Jv find(List<Jv> events, Jv want) {
        int classUid = SchemaPin.bundled().classNamed(want.member("class").text()).classUid();
        for (Jv e : events) {
            if (integer(e.member("class_uid")) == classUid && integer(e.member("activity_id")) == integer(want.member("activity_id"))
                    && integer(e.path("source_ref", "record_index")) == integer(want.member("record"))) {
                return e;
            }
        }
        return null;
    }

    static Jv expectedFor(Jv example, Jv counter) {
        for (Jv exp : example.member("expected").elements()) {
            if (integer(exp.member("record")) == integer(counter.member("record")) && exp.member("class").text().equals(counter.member("class").text())) {
                return exp;
            }
        }
        return null;
    }

    /**
     * Whether the expected example takes a different value at some pointer the counterexample names, or states that
     * pointer (or an enclosing one) absent.
     */
    static boolean rejects(Jv expected, Jv.Obj counter) {
        Jv.Obj values = (Jv.Obj) expected.member("values");
        List<String> absent = new ArrayList<>();
        for (Jv a : expected.member("absent") == null ? List.<Jv>of() : expected.member("absent").elements()) {
            absent.add(a.text());
        }
        for (Map.Entry<String, Jv> v : counter.members().entrySet()) {
            if (values.members().containsKey(v.getKey()) && !same(v.getValue(), values.members().get(v.getKey()))) {
                return true;
            }
            if (absent.stream().anyMatch(a -> v.getKey().equals(a) || v.getKey().startsWith(a + "/"))) {
                return true;
            }
        }
        return false;
    }

    /** Whether some pointer of {@code values} holds a different value in the event. */
    static boolean differs(Jv values, Jv event) {
        for (Map.Entry<String, Jv> v : ((Jv.Obj) values).members().entrySet()) {
            if (!same(v.getValue(), at(event, v.getKey()))) {
                return true;
            }
        }
        return false;
    }

    /** The value at a JSON Pointer ({@code /a/0/b}), or {@code null}. */
    static Jv at(Jv root, String pointer) {
        Jv cur = root;
        for (String raw : pointer.substring(1).split("/", -1)) {
            String token = raw.replace("~1", "/").replace("~0", "~");
            if (cur instanceof Jv.Obj o) {
                cur = o.get(token);
            }
            else if (cur instanceof Jv.Arr a && token.chars().allMatch(Character::isDigit) && Integer.parseInt(token) < a.elements().size()) {
                cur = a.elements().get(Integer.parseInt(token));
            }
            else {
                return null;
            }
            if (cur == null) {
                return null;
            }
        }
        return cur;
    }

    /** Exact equality: numbers as spelled, objects by member regardless of order, arrays in order. */
    static boolean same(Jv a, Jv b) {
        if (a == null || b == null) {
            return a == b;
        }
        if (a instanceof Jv.Obj x && b instanceof Jv.Obj y) {
            if (!x.members().keySet().equals(y.members().keySet())) {
                return false;
            }
            for (Map.Entry<String, Jv> m : x.members().entrySet()) {
                if (!same(m.getValue(), y.members().get(m.getKey()))) {
                    return false;
                }
            }
            return true;
        }
        if (a instanceof Jv.Arr x && b instanceof Jv.Arr y) {
            if (x.elements().size() != y.elements().size()) {
                return false;
            }
            for (int i = 0; i < x.elements().size(); i++) {
                if (!same(x.elements().get(i), y.elements().get(i))) {
                    return false;
                }
            }
            return true;
        }
        return a.getClass() == b.getClass() && Jv.write(a).equals(Jv.write(b));
    }

    static int integer(Jv v) {
        return v instanceof Jv.Num n ? n.value().intValueExact() : -1;
    }

    static void writeResults(List<Map<String, Object>> examples, List<Compared> results) {
        StringBuilder sb = new StringBuilder("{\"kind\":\"agent-journal/semantic-example-results\",\"format_version\":\"0.1\",\"produced_by\":\"SemanticExampleTest (journal-ocsf-writer, this build)\",\"examples\":[");
        for (int i = 0; i < examples.size(); i++) {
            Map<String, Object> m = examples.get(i);
            sb.append(i > 0 ? "," : "").append("{\"id\":").append(Jv.write(new Jv.Str(String.valueOf(m.get("id"))))).append(",\"native_sha256\":\"").append(m.get("native_sha256"))
                    .append("\",\"evidence_kind\":\"").append(m.get("evidence_kind")).append("\"}");
        }
        sb.append("],\"comparisons\":[");
        for (int i = 0; i < results.size(); i++) {
            Compared c = results.get(i);
            sb.append(i > 0 ? ",\n" : "\n").append("{\"example\":").append(Jv.write(new Jv.Str(c.example()))).append(",\"record\":").append(c.record())
                    .append(",\"pointer\":").append(Jv.write(new Jv.Str(c.pointer()))).append(",\"expected\":").append(c.expected() == null ? "null" : Jv.write(new Jv.Str(c.expected())))
                    .append(",\"actual\":").append(c.actual() == null ? "null" : Jv.write(new Jv.Str(c.actual()))).append(",\"outcome\":\"").append(c.outcome()).append("\"}");
        }
        sb.append("]}\n");
        try {
            Path out = Path.of(System.getProperty("basedir", ".")).resolve("target").resolve("semantic-examples");
            Files.createDirectories(out);
            Files.writeString(out.resolve("example-results.json"), sb.toString(), StandardCharsets.UTF_8);
        }
        catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
