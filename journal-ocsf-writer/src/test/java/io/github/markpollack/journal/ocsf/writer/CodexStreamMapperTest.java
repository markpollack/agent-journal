package io.github.markpollack.journal.ocsf.writer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static io.github.markpollack.journal.ocsf.writer.WriterTestSupport.CODEX_STREAM;
import static io.github.markpollack.journal.ocsf.writer.WriterTestSupport.codexStream;
import static io.github.markpollack.journal.ocsf.writer.WriterTestSupport.nativeOf;
import static io.github.markpollack.journal.ocsf.writer.WriterTestSupport.options;
import static io.github.markpollack.journal.ocsf.writer.WriterTestSupport.run;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The Codex exec-stream mapper on the labelled synthetic {@code syn-codex-exec-stream} fixture, event by event with
 * exact expectations read from the native records: agent messages as stream-scoped responses, command executions as
 * unattributed operations with their start/completion history, the turn's usage once with exact values, unknown item
 * and event types retained whole, the capture-start time fallback for a stream without timestamps, and the family
 * inventory the writer declares.
 */
class CodexStreamMapperTest {

    @Test
    void everyRecordOfTheExecStreamMapsToItsPromisedEvent(@TempDir Path work) {
        Path core = work.resolve("core");
        try (OcsfRunWriter w = OcsfRunWriter.open(core, run(CODEX_STREAM), SchemaPin.bundled(), options())) {
            Replay.feedRecords(w.source(codexStream()), nativeOf(CODEX_STREAM, "stream.jsonl"), options().clock());
            assertThat(w.complete()).isEqualTo(OcsfRunWriter.State.COMPLETED);
        }
        List<Jv> events = new ArrayList<>();
        for (String line : WriterTestSupport.eventLines(core.resolve("events.jsonl"))) {
            events.add(Jv.parse(line));
        }
        assertThat(events).hasSize(12);
        for (int i = 0; i < 12; i++) {
            Jv e = events.get(i);
            assertThat(((Jv.Num) e.path("source_ref", "record_index")).value().intValue()).isEqualTo(i + 1);
            assertThat(((Jv.Num) e.path("journal", "time_basis_id")).value().intValue()).as("no timestamps in an exec stream").isEqualTo(2);
            assertThat(e.path("metadata", "original_time")).isNull();
            assertThat(e.path("source_ref", "is_whole_record")).isEqualTo(new Jv.Bool(true));
            assertThat(e.member("session_uid").text()).isEqualTo(run(CODEX_STREAM).sessionUid());
        }
        // 1 thread.started, 2 turn.started: retained whole with their meaning stated
        assertThat(events.get(0).member("class_name").text()).isEqualTo("Source Record");
        assertThat(events.get(0).member("record_type").text()).isEqualTo("thread.started");
        assertThat(events.get(1).member("record_type").text()).isEqualTo("turn.started");
        // 3 agent_message: a completed content item (activity 3) under its stream-local item id, one text block, its response unidentified
        Jv first = events.get(2);
        assertThat(first.member("class_name").text()).isEqualTo("Model Response");
        assertThat(((Jv.Num) first.member("activity_id")).value().intValue()).isEqualTo(3);
        assertThat(first.member("item_uid").text()).isEqualTo("item_0");
        assertThat(first.member("response_uid")).isNull();
        assertThat(first.member("content_blocks").elements()).hasSize(1);
        assertThat(first.member("content_blocks").elements().get(0).member("text").text()).isEqualTo("SYNTHETIC: I will run three commands.\n");
        assertThat(first.member("content_blocks").elements().get(0).member("block_type").text()).isEqualTo("agent_message.text");
        assertThat(first.path("journal", "rule_uid").text()).isEqualTo("codex.stream.agent_message->model_response");
        // 4..8 command executions: operations, unattributed, with the reported state and exit code of each observation
        operation(events.get(3), "item_1", "in_progress", null, 0);
        operation(events.get(4), "item_1", "completed", "0", 1);
        operation(events.get(5), "item_2", "in_progress", null, 0);
        operation(events.get(6), "item_2", "failed", "1", 2);
        operation(events.get(7), "item_3", "in_progress", null, 0);
        assertThat(events.get(4).path("tool_operation", "stdout").text()).isEqualTo("alpha");
        assertThat(events.get(6).path("tool_operation", "stdout").text()).isEqualTo("cat: missing.txt: No such file or directory\n");
        assertThat(events.get(3).path("tool_operation", "stdout").text()).as("an empty aggregated_output is still a reported value").isEqualTo("");
        // 9 unknown item type, 10 unknown event type: fallback, whole record retained
        assertThat(events.get(8).member("class_name").text()).isEqualTo("Source Record");
        assertThat(events.get(8).member("record_type").text()).isEqualTo("item.completed/synthetic_unknown_item");
        assertThat(events.get(8).path("data", "item", "note").text()).contains("SYNTHETIC");
        assertThat(events.get(9).member("record_type").text()).isEqualTo("synthetic.unknown_event");
        assertThat(events.get(9).path("journal", "rule_uid").text()).isEqualTo("codex.stream.fallback->source_record");
        // 11 second agent message, 12 turn.completed with the exact usage spelled as the source spelled it
        assertThat(events.get(10).member("item_uid").text()).isEqualTo("item_5");
        Jv turn = events.get(11);
        assertThat(turn.member("class_name").text()).isEqualTo("Prompt Turn");
        assertThat(((Jv.Num) turn.member("activity_id")).value().intValue()).isEqualTo(2);
        assertThat(turn.member("turn_uid")).as("the stream names no turn id").isNull();
        Jv usage = turn.member("usage_observations").elements().get(0);
        // 2^53+1 inclusive input less 24576 cached (cache writes 0) is kept exactly as a conditional value; the reported value stays in data
        assertThat(usage.member("uncached_input_tokens")).as("only a conditional value: the cache-write 0 may be the deserializer default").isNull();
        assertThat(Jv.write(usage.path("components").elements().get(0).member("conditional_value"))).isEqualTo("9007199254716417");
        assertThat(Jv.write(usage.path("data", "input_tokens"))).isEqualTo("9007199254740993");
        assertThat(usage.member("input_tokens")).isNull();
        assertThat(Jv.write(usage.member("cache_read_tokens"))).isEqualTo("24576");
        assertThat(Jv.write(usage.member("cache_write_tokens"))).isEqualTo("0");
        assertThat(Jv.write(usage.member("output_tokens"))).isEqualTo("143");
        assertThat(Jv.write(usage.member("reasoning_tokens"))).isEqualTo("7");
        assertThat(usage.member("reasoning_relation").text()).isEqualTo("Part of output");
        assertThat(usage.member("aggregation").text()).isEqualTo("Cumulative snapshot");
        assertThat(usage.member("reported_total_tokens")).as("the stream reports no total; none is derived").isNull();
        // the thread's cumulative usage at turn completion, session scope, final for the run (SEM-1 candidate, pending Owner approval)
        assertThat(((Jv.Num) usage.member("scope_id")).value().intValue()).isEqualTo(5);
        assertThat(((Jv.Num) usage.member("usage_status_id")).value().intValue()).isEqualTo(2);
        assertThat(((Jv.Num) usage.member("measurement_status_id")).value().intValue()).as("a non-zero component: an update arrived into a running total, at least these values").isEqualTo(4);
        assertThat(((Jv.Num) turn.member("status_id")).value().intValue()).isEqualTo(0);
        assertThat(((Jv.Num) turn.member("status_basis_id")).value().intValue()).isEqualTo(3);
        // the family inventory the writer declares
        Jv families = WriterTestSupport.manifest(core).member("sources").elements().get(0).member("family_inventory");
        assertThat(((Jv.Num) families.path("item.started/command_execution", "records")).value().intValue()).isEqualTo(3);
        assertThat(((Jv.Num) families.path("item.completed/command_execution", "records")).value().intValue()).isEqualTo(2);
        assertThat(((Jv.Num) families.path("item.completed/agent_message", "records")).value().intValue()).isEqualTo(2);
        assertThat(families.path("synthetic.unknown_event", "disposition").text()).isEqualTo("retained-only: no normalization rule");
        assertThat(families.path("thread.started", "disposition").text()).contains("session identity");
        assertThat(families.path("turn.started", "disposition").text()).contains("turn start without a turn id");
        assertThat(WriterTestSupport.manifest(core).path("recording", "acquisition", "status").text()).isEqualTo("complete");
    }

    private static void operation(Jv e, String uid, String state, String exitCode, int status) {
        assertThat(e.member("class_name").text()).isEqualTo("Tool Call");
        assertThat(((Jv.Num) e.member("activity_id")).value().intValue()).isEqualTo(3);
        Jv op = e.member("tool_operation");
        assertThat(op.member("uid").text()).isEqualTo(uid);
        assertThat(((Jv.Num) op.member("identity_basis_id")).value().intValue()).as("a stream-local item id").isEqualTo(2);
        assertThat(op.member("command_line")).as("the reported command string, unparsed").isEqualTo(e.path("data", "item", "command"));
        assertThat(op.member("reported_state").text()).isEqualTo(state);
        if (exitCode == null) {
            assertThat(op.member("exit_code")).as("exit_code null at source is absent, never 0").isNull();
        }
        else {
            assertThat(Jv.write(op.member("exit_code"))).isEqualTo(exitCode);
        }
        assertThat(((Jv.Num) e.member("status_id")).value().intValue()).isEqualTo(status);
        assertThat(((Jv.Num) e.member("status_basis_id")).value().intValue()).isEqualTo(1);
        assertThat(((Jv.Num) op.path("attribution", "outcome_id")).value().intValue()).isEqualTo(3);
        assertThat(((Jv.Num) op.path("attribution", "method_id")).value().intValue()).isEqualTo(2);
        assertThat(op.path("attribution", "candidates").elements()).isEmpty();
        assertThat(e.member("tool_call_uid")).as("no call id exists in the stream").isNull();
        assertThat(e.path("journal", "rule_uid").text()).isEqualTo("codex.stream.command_execution->tool_call.operation");
    }

    @Test
    void anAgentMessageWithoutTextHasNoContentBlockAndAnOpaqueLineIsRetained(@TempDir Path work) {
        Path core = work.resolve("core");
        try (OcsfRunWriter w = OcsfRunWriter.open(core, run(CODEX_STREAM), SchemaPin.bundled(), options())) {
            SourceInput in = w.source(codexStream());
            // SYNTHETIC unit inputs in the verified key sets, not a vendor claim about an agent_message without text
            in.record("{\"type\":\"item.completed\",\"item\":{\"id\":\"item_x\",\"type\":\"agent_message\"}}".getBytes(StandardCharsets.UTF_8), "LF", options().clock().instant());
            in.record("not json at all".getBytes(StandardCharsets.UTF_8), "LF", options().clock().instant());
            in.record("{\"type\":\"item.started\",\"item\":{\"id\":\"item_y\",\"type\":\"command_execution\",\"command\":\"true\",\"aggregated_output\":\"\",\"exit_code\":null,\"status\":\"in_progress\"}}".getBytes(StandardCharsets.UTF_8), "CRLF", options().clock().instant());
            in.end(SourceInput.End.EOF, options().clock().instant());
            w.complete();
        }
        List<String> lines = WriterTestSupport.eventLines(core.resolve("events.jsonl"));
        assertThat(lines).hasSize(3);
        Jv noText = Jv.parse(lines.get(0));
        assertThat(noText.member("item_uid").text()).isEqualTo("item_x");
        assertThat(noText.member("content_blocks").elements()).as("no text at source: no block, not a null block").isEmpty();
        Jv opaque = Jv.parse(lines.get(1));
        assertThat(opaque.member("class_name").text()).isEqualTo("Source Record");
        assertThat(((Jv.Num) opaque.member("activity_id")).value().intValue()).isEqualTo(2);
        assertThat(opaque.member("raw_data").text()).isEqualTo("not json at all");
        Jv crlf = Jv.parse(lines.get(2));
        assertThat(crlf.path("source_ref", "line_ending").text()).isEqualTo("CRLF");
        assertThat(crlf.path("tool_operation", "uid").text()).isEqualTo("item_y");
        Jv source = WriterTestSupport.manifest(core).member("sources").elements().get(0);
        assertThat(((Jv.Num) source.member("opaque_records")).value().intValue()).isEqualTo(1);
        assertThat(((Jv.Num) source.member("records")).value().intValue()).isEqualTo(3);
    }

    /**
     * A web_search line repeats item.id (serde flattens WebSearchItem, which has its own id, into ThreadItem, whose id
     * comes first). That one declared form is read as a tool call under the first id beside its verbatim opaque record;
     * any other repeated member, a third id, or the same repeat on another item type stays an opaque record with no
     * interpretation, and strict parsing still refuses every duplicate.
     */
    @Test
    void onlyTheDeclaredWebSearchDuplicateIsInterpretedAndEveryOtherDuplicateStaysOpaque(@TempDir Path work) {
        // SYNTHETIC unit inputs in the vendor's key order; only the first is the form the pinned emitter writes
        List<String> inputs = List.of(
                "{\"type\":\"item.started\",\"item\":{\"id\":\"item_1\",\"type\":\"web_search\",\"id\":\"search-1\",\"query\":\"\",\"action\":{\"type\":\"other\"}}}",
                "{\"type\":\"item.started\",\"item\":{\"id\":\"item_2\",\"type\":\"web_search\",\"query\":\"a\",\"query\":\"b\",\"action\":{\"type\":\"other\"}}}",
                "{\"type\":\"item.started\",\"item\":{\"id\":\"item_3\",\"type\":\"web_search\",\"id\":\"search-3\",\"id\":\"search-3b\",\"query\":\"\",\"action\":{\"type\":\"other\"}}}",
                "{\"type\":\"item.completed\",\"item\":{\"id\":\"item_4\",\"type\":\"agent_message\",\"id\":\"msg-4\",\"text\":\"x\"}}",
                "{\"type\":\"item.started\",\"type\":\"item.completed\",\"item\":{\"id\":\"item_5\",\"type\":\"web_search\",\"query\":\"\",\"action\":{\"type\":\"other\"}}}");
        Path core = work.resolve("core");
        try (OcsfRunWriter w = OcsfRunWriter.open(core, run(CODEX_STREAM), SchemaPin.bundled(), options())) {
            SourceInput in = w.source(codexStream());
            for (String line : inputs) {
                in.record(line.getBytes(StandardCharsets.UTF_8), "LF", options().clock().instant());
            }
            in.end(SourceInput.End.EOF, options().clock().instant());
            w.complete();
        }
        List<Jv> events = new ArrayList<>();
        for (String line : WriterTestSupport.eventLines(core.resolve("events.jsonl"))) {
            events.add(Jv.parse(line));
        }
        // record 1: the verbatim opaque record (both ids in raw_data), then the tool call under the first id, not retaining the record
        assertThat(events).hasSize(6);
        assertThat(events.get(0).member("raw_data").text()).isEqualTo(inputs.get(0));
        assertThat(events.get(0).path("source_ref", "is_whole_record")).isEqualTo(new Jv.Bool(true));
        assertThat(events.get(1).member("class_name").text()).isEqualTo("Tool Call");
        assertThat(events.get(1).member("tool_call_uid").text()).isEqualTo("item_1");
        assertThat(events.get(1).path("source_ref", "is_whole_record")).isEqualTo(new Jv.Bool(false));
        assertThat(events.get(1).member("data")).isNull();
        // records 2 to 5: an opaque record each, nothing else
        for (int i = 2; i < 6; i++) {
            Jv e = events.get(i);
            assertThat(e.member("class_name").text()).isEqualTo("Source Record");
            assertThat(((Jv.Num) e.member("activity_id")).value().intValue()).isEqualTo(2);
            assertThat(e.member("raw_data").text()).isEqualTo(inputs.get(i - 1));
        }
        // strict parsing is unchanged; the duplicate-recording parse keeps both values without merging them
        for (String line : inputs) {
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> Jv.parse(line)).isInstanceOf(IllegalArgumentException.class);
        }
        Jv.WithDuplicates d = Jv.parseRecordingDuplicates(inputs.get(0));
        assertThat(d.value().path("item", "id").text()).isEqualTo("item_1");
        assertThat(d.repeated()).containsExactly(new Jv.Repeated(Jv.Location.ROOT.member("item", 1), "id", List.of(new Jv.Str("item_1"), new Jv.Str("search-1"))));
        assertThat(d.repeated().get(0).pointer()).isEqualTo("/item/id");
    }

    /** The thread id the stream states once in thread.started is the session identity of every later event, and is declared once in the file. */
    @Test
    void theThreadIdBecomesTheSessionOfEveryLaterEvent(@TempDir Path work) {
        Path core = work.resolve("core");
        RunDescriptor noSession = new RunDescriptor("codex-no-session-at-launch", 0, null, java.time.Instant.parse("2026-10-21T12:00:00Z"),
                new RunDescriptor.Cli("Codex CLI", "0.160.1", "OpenAI"), null, new Jv.Obj().put("label", "LABELLED SYNTHETIC: the session is not known at launch"));
        try (OcsfRunWriter w = OcsfRunWriter.open(core, noSession, SchemaPin.bundled(), options())) {
            Replay.feedRecords(w.source(codexStream()), nativeOf(CODEX_STREAM, "stream.jsonl"), options().clock());
            w.complete();
        }
        List<Jv> events = new ArrayList<>();
        for (String line : WriterTestSupport.eventLines(core.resolve("events.jsonl"))) {
            events.add(Jv.parse(line));
        }
        assertThat(events).hasSize(12);
        for (Jv e : events) {
            assertThat(e.member("session_uid").text()).as(e.member("record_type") == null ? e.member("class_name").text() : e.member("record_type").text())
                    .isEqualTo("00000000-0000-4000-8000-0000000c0de5");
        }
        io.github.markpollack.journal.tck.ocsf.OcsfPackage p = WriterTestSupport.open(core);
        assertThat(p.manifest.sessionUid()).isEqualTo("00000000-0000-4000-8000-0000000c0de5");
        assertThat(p.declarations.lineOf("session:00000000-0000-4000-8000-0000000c0de5")).isNotNull();
    }

    /**
     * The exec processor mints item_0, item_1, ... afresh in every stream: two invocations of one run that both report
     * item_0 report two different items. Each event states the id's stream-local basis, and the id is unique only with
     * its invocation and artifact (journal.run_uid, journal.invocation_index, source_ref.artifact); neither invocation's
     * item is written as a response identity.
     */
    @Test
    void equalItemIdsOfTwoInvocationsAreTwoItemsOfTheirOwnNamespaces(@TempDir Path work) {
        String line = "{\"type\":\"item.completed\",\"item\":{\"id\":\"item_0\",\"type\":\"agent_message\",\"text\":\"SYNTHETIC\"}}";
        List<String> namespaces = itemsOfTwoInvocations(work, line, e -> {
            assertThat(e.member("item_uid").text()).isEqualTo("item_0");
            assertThat(e.member("identity_basis").text()).isEqualTo("Stream-local item identifier");
            assertThat(e.member("response_uid")).as("a stream item is not a response identity").isNull();
            return e.member("item_uid").text();
        });
        assertThat(namespaces).doesNotHaveDuplicates().hasSize(2);
    }

    /**
     * The same for a tool item: item_0 is the tool_call_uid of an MCP invocation in each of two invocations of one run, a
     * call identity scoped to its stream with its stream-local basis, never a model-facing call identifier (basis 1) or a
     * response identity, and the two calls stay distinct by invocation and artifact.
     */
    @Test
    void equalToolItemIdsOfTwoInvocationsAreTwoStreamLocalCallsNeverModelFacingOrResponses(@TempDir Path work) {
        String line = "{\"type\":\"item.started\",\"item\":{\"id\":\"item_0\",\"type\":\"mcp_tool_call\",\"server\":\"local\",\"tool\":\"exec\",\"arguments\":{},\"result\":null,\"error\":null,\"status\":\"in_progress\"}}";
        List<String> namespaces = itemsOfTwoInvocations(work, line, e -> {
            assertThat(e.member("tool_call_uid").text()).isEqualTo("item_0");
            assertThat(((Jv.Num) e.member("identity_basis_id")).value().intValue()).as("stream-local, not a model-facing call id").isEqualTo(2);
            assertThat(e.member("response_uid")).as("a stream item is not a response identity").isNull();
            assertThat(((Jv.Num) e.member("tool_kind_id")).value().intValue()).as("no reviewed binding of local/exec: Other, not Execute").isEqualTo(99);
            return e.member("tool_call_uid").text();
        });
        assertThat(namespaces).doesNotHaveDuplicates().hasSize(2);
    }

    /** Writes {@code line} as the only record of two invocations of one run; each first event's scoped identity, after {@code check}. */
    static List<String> itemsOfTwoInvocations(Path work, String line, java.util.function.Function<Jv, String> check) {
        List<String> namespaces = new ArrayList<>();
        for (int invocation = 0; invocation < 2; invocation++) {
            RunDescriptor d = run(CODEX_STREAM);
            RunDescriptor r = new RunDescriptor(d.runUid(), invocation, d.sessionUid(), d.captureStart(), d.cli(), d.launchSettings(), d.synthetic());
            Path core = work.resolve("invocation-" + invocation);
            try (OcsfRunWriter w = OcsfRunWriter.open(core, r, SchemaPin.bundled(), options())) {
                SourceInput in = w.source(codexStream());
                in.record(line.getBytes(StandardCharsets.UTF_8), "LF", options().clock().instant());
                in.end(SourceInput.End.EOF, options().clock().instant());
                w.complete();
            }
            List<Jv> events = new ArrayList<>();
            for (String l : WriterTestSupport.eventLines(core.resolve("events.jsonl"))) {
                events.add(Jv.parse(l));
            }
            Jv e = events.get(0);
            String id = check.apply(e);
            namespaces.add(e.path("journal", "run_uid").text() + "/" + Jv.write(e.path("journal", "invocation_index")) + "/" + e.path("source_ref", "artifact").text() + "/" + id);
        }
        return namespaces;
    }
}
