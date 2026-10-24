package io.github.markpollack.journal.tck.ocsf;

import io.github.markpollack.journal.tck.ocsf.inventory.NativeInventoryTool;
import io.github.markpollack.journal.tck.ocsf.inventory.SourceJoin;
import io.github.markpollack.journal.tck.verify.Json;
import io.github.markpollack.journal.tck.verify.NeutralJson;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The experimental source join: two native sources of one execution are joined only on identities they report.
 * Synthetic records in the shape of a stream and a session file that share event ids (the Copilot CLI form) and of a
 * stream whose steps carry no id (the Junie form); the controls are the ones a combined-source capture needs before any
 * merge: a wrong or missing session, a duplicated import, repeated same-name calls, a source without identities, a
 * partial snapshot, a repeated key, conflicting and absent values.
 */
class SourceJoinTest {

    static final String SPEC_TEXT = """
            {"kind": "agent-journal/source-join-specification", "version": "0.1", "name": "synthetic stream and session file",
             "sources": [
              {"name": "stdout", "session": [{"select": {"/type": "result"}, "pointer": "/sessionId"}]},
              {"name": "session", "session": [{"select": {"/type": "session.start"}, "pointer": "/data/sessionId"}]}],
             "keys": [
              {"name": "event id", "unit": "record", "a": {"pointer": "/id"}, "b": {"pointer": "/id"}},
              {"name": "tool call id", "unit": "fact",
               "a": {"select": {"/type": "tool.execution_start"}, "pointer": "/data/toolCallId"},
               "b": {"select": {"/type": "tool.execution_start"}, "pointer": "/data/toolCallId"},
               "compare": [{"a": "/data/arguments/n", "b": "/data/arguments/n"}, {"a": "/data/tokens", "b": "/data/tokens"}]}]}
            """;

    static final Json SPEC = NeutralJson.parse(SPEC_TEXT);

    static final String STDOUT = """
            {"type":"tool.execution_start","id":"e1","data":{"toolCallId":"call_a","toolName":"echo","arguments":{"n":1}}}
            {"type":"tool.execution_start","id":"e2","data":{"toolCallId":"call_b","toolName":"echo","arguments":{"n":2}}}
            {"type":"tool.execution_complete","id":"e3","data":{"toolCallId":"call_a","result":{"content":"x","detailedContent":"x"}}}
            {"type":"tool.execution_complete","id":"e4","data":{"toolCallId":"call_b","metrics":{"tokens":14552}}}
            {"type":"assistant.message_delta","id":"e5","ephemeral":true,"data":{"deltaContent":"SENTINEL-DELTA"}}
            {"type":"result","sessionId":"s-1","exitCode":0}
            """;

    static final String SESSION = """
            {"type":"session.start","id":"e0","data":{"sessionId":"s-1","copilotVersion":"9.9.9"}}
            {"type":"tool.execution_start","id":"e1","data":{"toolCallId":"call_a","toolName":"echo","arguments":{"n":1}}}
            {"type":"tool.execution_start","id":"e2","data":{"toolCallId":"call_b","toolName":"echo","arguments":{"n":2}}}
            {"type":"tool.execution_complete","id":"e3","data":{"toolCallId":"call_a","result":{"content":"x"}}}
            {"type":"tool.execution_complete","id":"e4","data":{"toolCallId":"call_b","metrics":{"tokens":14552.0}}}
            {"type":"session.usage_record","id":"e6","data":{"usage":{"inputTokens":13128}}}
            """;

    static NativeArtifact artifact(String name, String text) {
        return NativeArtifact.ofBytes(Path.of(name), text.getBytes(StandardCharsets.UTF_8));
    }

    static Json join(String stdout, String session) {
        return SourceJoin.join(SPEC, List.of(artifact("stdout.jsonl", stdout)), List.of(artifact("events.jsonl", session)));
    }

    static Json key(Json report, String name) {
        return report.member("keys").elements().stream().filter(k -> name.equals(k.member("name").text())).findFirst().orElseThrow();
    }

    static List<String> texts(Json array) {
        return array.elements().stream().map(Json::text).toList();
    }

    @Test
    void recordsSharingAnEventIdAreJoinedAndEachPairIsClassifiedByValue() {
        Json report = join(STDOUT, SESSION);
        assertThat(report.path("session", "status").text()).isEqualTo("ESTABLISHED");
        Json events = key(report, "event id");
        assertThat(events.member("status").text()).isEqualTo("JOINED");
        assertThat(events.member("matched").scalarText()).isEqualTo("4");
        assertThat(texts(events.member("a_only"))).as("stream-only ephemeral record").containsExactly("e5");
        assertThat(texts(events.member("b_only"))).as("session-only records").containsExactly("e0", "e6");
        Json pairs = events.member("pairs");
        assertThat(pairs.member("identical").scalarText()).isEqualTo("2");
        assertThat(pairs.member("a_has_more").scalarText()).as("the stream keeps detailedContent the session omits").isEqualTo("1");
        assertThat(pairs.member("representation_only").scalarText()).as("14552 and 14552.0 are one value").isEqualTo("1");
        assertThat(pairs.member("conflicting").scalarText()).isEqualTo("0");
        Json richer = events.member("findings").elements().stream().filter(f -> "a_has_more".equals(f.member("class").text())).findFirst().orElseThrow();
        assertThat(richer.member("key").text()).isEqualTo("e3");
        assertThat(texts(richer.member("a"))).containsExactly("stdout.jsonl:3");
        assertThat(richer.member("differences").elements()).singleElement()
                .satisfies(d -> assertThat(d.member("path").text()).isEqualTo("$.data.result.detailedContent"))
                .satisfies(d -> assertThat(d.member("kind").text()).isEqualTo("MISSING"));
        assertThat(NeutralJson.write(report)).as("identities, pointers and kinds only, never content").doesNotContain("SENTINEL-DELTA").doesNotContain("9.9.9");
    }

    @Test
    void aDifferentSessionRejectsTheJoinAndAMissingOneLeavesItUnestablished() {
        Json wrong = join(STDOUT, SESSION.replace("\"sessionId\":\"s-1\"", "\"sessionId\":\"s-2\""));
        assertThat(wrong.path("session", "status").text()).isEqualTo("REJECTED");
        assertThat(texts(wrong.path("session", "b"))).containsExactly("s-2");
        for (Json k : wrong.member("keys").elements()) {
            assertThat(k.member("status").text()).isEqualTo("NOT_JOINED_SESSION");
            assertThat(k.member("matched")).as("equal event ids of another session are not joined").isNull();
        }
        // a partial snapshot that lost its session.start line: the session is not demonstrated, nothing is joined
        Json partial = join(STDOUT, SESSION.lines().skip(1).reduce("", (x, y) -> x + y + "\n"));
        assertThat(partial.path("session", "status").text()).isEqualTo("UNESTABLISHED");
        assertThat(key(partial, "event id").member("status").text()).isEqualTo("NOT_JOINED_SESSION");
    }

    @Test
    void aDuplicatedImportIsIdempotentNothingIsCountedTwice() {
        Json report = SourceJoin.join(SPEC, List.of(artifact("events.jsonl", SESSION)), List.of(artifact("events.jsonl", SESSION)));
        // the same session file on both sides: its own session locator is the session side's, so state it as a joinable pair
        Json selfSpec = NeutralJson.parse(SPEC_TEXT.replace("{\"select\": {\"/type\": \"result\"}, \"pointer\": \"/sessionId\"}",
                "{\"select\": {\"/type\": \"session.start\"}, \"pointer\": \"/data/sessionId\"}"));
        Json self = SourceJoin.join(selfSpec, List.of(artifact("events.jsonl", SESSION)), List.of(artifact("events.jsonl", SESSION)));
        assertThat(report.path("session", "status").text()).as("the stream locator finds no result record in a session file").isEqualTo("UNESTABLISHED");
        Json events = key(self, "event id");
        assertThat(events.member("matched").scalarText()).isEqualTo("6");
        assertThat(events.member("a_only").elements()).isEmpty();
        assertThat(events.member("b_only").elements()).isEmpty();
        assertThat(events.path("pairs", "identical").scalarText()).isEqualTo("6");
        Json calls = key(self, "tool call id");
        assertThat(calls.member("matched").scalarText()).as("two calls, not four").isEqualTo("2");
    }

    @Test
    void repeatedSameNameCallsStayDistinctAndAnUnidentifiedCallIsNeverPairedByName() {
        String stdout = STDOUT.replace("\"toolCallId\":\"call_b\",\"toolName\":\"echo\"", "\"toolName\":\"echo\"");
        Json calls = key(join(stdout, SESSION), "tool call id");
        assertThat(calls.member("matched").scalarText()).isEqualTo("1");
        assertThat(texts(calls.member("b_only"))).as("the same tool name does not stand in for the missing id").containsExactly("call_b");
        Json both = key(join(STDOUT, SESSION), "tool call id");
        assertThat(both.member("matched").scalarText()).as("two echo calls, two keys").isEqualTo("2");
        assertThat(both.member("a_occurrences").scalarText()).isEqualTo("2");
    }

    @Test
    void aSourceWhoseStepsCarryNoIdIsNotJoinedByOrderOrText() {
        Json spec = NeutralJson.parse("""
                {"kind": "agent-journal/source-join-specification", "version": "0.1",
                 "sources": [
                  {"name": "stdout", "session": [{"select": {"/type": "session"}, "pointer": "/sessionId"}]},
                  {"name": "session", "session": [{"pointer": "/sessionId"}]}],
                 "keys": [{"name": "step id", "unit": "fact",
                   "a": {"select": {"/type": "step"}, "pointer": "/stepId"},
                   "b": {"select": {"/kind": "TerminalBlockUpdatedEvent"}, "pointer": "/stepId"}}]}
                """);
        NativeArtifact stdout = artifact("stdout.jsonl", """
                {"type":"session","sessionId":"session-1"}
                {"type":"step","name":"ls -1; cat missing.txt","output":"data.csv"}
                """);
        NativeArtifact summary = artifact("summary.json", "{\"sessionId\":\"session-1\",\"lifecycle\":\"COMPLETED\"}\n");
        NativeArtifact events = artifact("events.jsonl", """
                {"kind":"TerminalBlockUpdatedEvent","stepId":"st-1","status":"FAILED","command":"ls -1; cat missing.txt","exitCode":1}
                """);
        Json report = SourceJoin.join(spec, List.of(stdout), List.of(summary, events));
        assertThat(report.path("session", "status").text()).as("the session file's identity is in its summary, not its events").isEqualTo("ESTABLISHED");
        Json step = report.member("keys").elements().get(0);
        assertThat(step.member("status").text()).isEqualTo("NO_IDENTITY_IN_A");
        assertThat(step.member("a_selected_records").scalarText()).isEqualTo("1");
        assertThat(step.member("a_keyed_records").scalarText()).isEqualTo("0");
        assertThat(step.member("matched")).as("equal command text is not an identity").isNull();
    }

    @Test
    void aTruncatedSnapshotLeavesFactsUnmatchedWithoutConflictsAndARepeatedKeyIsAmbiguous() {
        String truncated = SESSION.substring(0, SESSION.indexOf("{\"type\":\"tool.execution_complete\",\"id\":\"e4\"")) + "{\"type\":\"tool.execution_complete\",\"id\":\"e4\",\"da";
        Json events = key(join(STDOUT, truncated), "event id");
        assertThat(events.member("matched").scalarText()).isEqualTo("3");
        assertThat(texts(events.member("a_only"))).as("the cut-off record is not parsed and not joined").containsExactly("e4", "e5");
        assertThat(events.path("pairs", "conflicting").scalarText()).isEqualTo("0");

        String repeated = SESSION + "{\"type\":\"tool.execution_complete\",\"id\":\"e3\",\"data\":{\"toolCallId\":\"call_a\"}}\n";
        Json ambiguous = key(join(STDOUT, repeated), "event id");
        assertThat(ambiguous.path("pairs", "ambiguous").scalarText()).as("two session records claim e3: not compared").isEqualTo("1");
    }

    @Test
    void conflictingValuesAreReportedWithTheirPathAndAnAbsentValueIsNotZero() {
        String conflicting = SESSION.replace("{\"toolCallId\":\"call_b\",\"toolName\":\"echo\",\"arguments\":{\"n\":2}}", "{\"toolCallId\":\"call_b\",\"toolName\":\"echo\",\"arguments\":{\"n\":3}}");
        Json report = join(STDOUT, conflicting);
        Json events = key(report, "event id");
        assertThat(events.path("pairs", "conflicting").scalarText()).isEqualTo("1");
        Json conflict = events.member("findings").elements().stream().filter(f -> "conflicting".equals(f.member("class").text())).findFirst().orElseThrow();
        assertThat(conflict.member("key").text()).isEqualTo("e2");
        assertThat(conflict.member("differences").elements()).singleElement()
                .satisfies(d -> assertThat(d.member("path").text()).isEqualTo("$.data.arguments.n"))
                .satisfies(d -> assertThat(d.member("kind").text()).isEqualTo("VALUE"));
        Json values = key(report, "tool call id").member("values");
        Json n = values.elements().get(0);
        assertThat(n.member("equal").scalarText()).isEqualTo("1");
        assertThat(texts(n.member("differ"))).containsExactly("call_b");
        Json tokens = values.elements().get(1);
        assertThat(tokens.member("equal").scalarText()).isEqualTo("0");
        assertThat(texts(tokens.member("absent_in_a"))).as("no start record reports tokens: absent, not 0").containsExactly("call_a", "call_b");
    }

    @Test
    void theCommandLineToolWritesTheReport(@TempDir Path work) throws Exception {
        Path spec = work.resolve("spec.json");
        Files.writeString(spec, NeutralJson.write(SPEC));
        Path a = work.resolve("stdout.jsonl");
        Files.writeString(a, STDOUT);
        Path b = work.resolve("events.jsonl");
        Files.writeString(b, SESSION);
        Path report = work.resolve("report.json");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int rc = NativeInventoryTool.run(new String[] {"overlap", spec.toString(), a.toString(), b.toString(), report.toString()},
                new PrintStream(out, true, StandardCharsets.UTF_8), new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8));
        assertThat(rc).isEqualTo(NativeInventoryTool.EXIT_OK);
        assertThat(out.toString(StandardCharsets.UTF_8)).contains("overlap: session ESTABLISHED; event id JOINED matched 4; tool call id JOINED matched 2");
        assertThat(NeutralJson.readDocument(report).member("kind").text()).isEqualTo(SourceJoin.KIND);
    }
}
