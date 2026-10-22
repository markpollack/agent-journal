package io.github.markpollack.journal.tck.ocsf;

import io.github.markpollack.journal.tck.verify.Json;
import io.github.markpollack.journal.tck.verify.NeutralJson;

import java.util.List;
import java.util.Set;

/**
 * Codex CLI {@code exec --json} live output, read from the pinned interface (codex-rs/exec/src/exec_events.rs and
 * event_processor_with_jsonl_output.rs at rust-v0.160.1 d27764b), never from the writer's mapper. The SEM-1 candidate
 * readings, proposed for the Project Owner's semantic approval:
 *
 * <ul>
 * <li>Every {@code item.id} is the processor's own stream-local id (identity basis 2), not a model-facing call id.</li>
 * <li>Agent messages are responses; a reasoning item is a response whose one block is a reasoning summary (form 2).</li>
 * <li>A command execution is a standalone operation (unattributed) with its command string; completed, failed and
 * declined (status 99) are its reported states.</li>
 * <li>MCP, web search, collaboration and file-change items are tool calls: {@code item.started} is the request and
 * {@code item.completed} the result. The tool name is {@code item.tool} where the item reports one (MCP, collaboration),
 * otherwise the item type. A completed or failed status is the reported outcome.</li>
 * <li>{@code turn.completed} reports the thread's cumulative usage (scope 5, final for the run); a non-zero component
 * shows it was measured, all-zero components may be the emitter's default.</li>
 * <li>A web_search line repeats {@code item.id} (the exec item id, then the search item's own id). It is not one JSON
 * object under strict parsing, so it is retained verbatim, not normalized; its request or result is read from the
 * first id only in exactly that form.</li>
 * </ul>
 * The stream carries no timestamps.
 */
final class CodexStreamProfile implements SourceProfile {

    private static final int STREAM_LOCAL = 2;
    private static final Set<String> TOOL_ITEMS = Set.of("mcp_tool_call", "web_search", "collab_tool_call", "file_change");
    private static final List<String> USAGE_FIELDS = List.of("input_tokens", "cached_input_tokens", "cache_write_input_tokens", "output_tokens", "reasoning_output_tokens");

    @Override
    public ExpectedFacts expected(NativeArtifact artifact, Json source, Json scope) {
        ExpectedFacts f = new ExpectedFacts(SourceProfile.text(source, "artifact"));
        String threadId = null;
        for (NativeArtifact.Record r : artifact.records()) {
            f.owned.add(r.index());
            Json o = r.parsed() ? r.json() : webSearchLine(r);
            if (o == null) {
                continue;
            }
            if ("thread.started".equals(SourceProfile.text(o, "type")) && SourceProfile.text(o, "thread_id") != null) {
                threadId = SourceProfile.text(o, "thread_id");
            }
            if (threadId != null) {
                // the stream states its session identity once, in thread.started; every later record belongs to that thread
                f.sessionOf.put(r.index(), threadId);
            }
            if (record(f, r.index(), o) && r.parsed()) {
                f.normalized.add(r.index()); // the web_search form is retained verbatim, not as a parsed record
            }
        }
        return f;
    }

    /** The facts one record states; {@code true} when a normalization rule covers it. */
    private static boolean record(ExpectedFacts f, int index, Json o) {
        String type = SourceProfile.text(o, "type");
        Json item = o.member("item");
        String itemType = SourceProfile.text(item, "type");
        boolean started = "item.started".equals(type);
        if (!started && !"item.completed".equals(type)) {
            return "turn.completed".equals(type) && turn(f, index, o);
        }
        if ("command_execution".equals(itemType)) {
            f.operations.add(new ExpectedFacts.Operation(index, SourceProfile.text(item, "id"), null, item.member("exit_code"),
                    SourceProfile.text(item, "status"), operationStatus(SourceProfile.text(item, "status")), 3, 2, List.of(), SourceProfile.text(item, "command"), STREAM_LOCAL));
            return true;
        }
        if (TOOL_ITEMS.contains(itemType)) {
            if (started) {
                f.requests.add(request(index, itemType, item));
            }
            else {
                f.results.add(result(index, itemType, item));
            }
            return true;
        }
        if (!started && ("agent_message".equals(itemType) || "reasoning".equals(itemType))) {
            f.responses.add(new ExpectedFacts.Response(index, SourceProfile.text(item, "id"),
                    item.member("text") == null ? List.of() : List.of(item.member("text")), null, true, null, "reasoning".equals(itemType) ? 2 : null));
            return true;
        }
        return false;
    }

    private static int operationStatus(String state) {
        return switch (state == null ? "" : state) {
            case "completed" -> 1;
            case "failed" -> 2;
            case "declined" -> 99;
            default -> 0;
        };
    }

    private static boolean turn(ExpectedFacts f, int index, Json o) {
        f.usages.add(new ExpectedFacts.Usage(index, index, 5, 2, SourceProfile.components(o.member("usage"),
                "input_tokens", "input_tokens", "output_tokens", "output_tokens", "cached_input_tokens", "cache_read_tokens",
                "cache_write_input_tokens", "cache_write_tokens", "reasoning_output_tokens", "reasoning_tokens"), null, null, measurement(o.member("usage"))));
        f.completed.add(new ExpectedFacts.TurnCompleted(index, 0, null));
        return true;
    }

    /** The request a started tool item reports: the reported tool name or the item type, an MCP call's arguments, a search's action. */
    private static ExpectedFacts.Request request(int index, String itemType, Json item) {
        boolean mcp = "mcp_tool_call".equals(itemType);
        String name = mcp || "collab_tool_call".equals(itemType) ? SourceProfile.text(item, "tool") : itemType;
        Json arguments = mcp ? item.member("arguments") : "web_search".equals(itemType) ? item.member("action") : null;
        return new ExpectedFacts.Request(index, SourceProfile.text(item, "id"), name, arguments, null, SourceProfile.text(item, "status"),
                mcp ? SourceProfile.text(item, "server") : null, STREAM_LOCAL);
    }

    /** The result a completed tool item reports: MCP result content and structured content, search results; the outcome its status reports. */
    private static ExpectedFacts.Result result(int index, String itemType, Json item) {
        Json content = null;
        Json structured = null;
        if ("mcp_tool_call".equals(itemType) && item.member("result") instanceof Json.JObject res) {
            content = res.member("content") == null ? Json.JNull.INSTANCE : res.member("content");
            structured = res.member("structured_content") == null || res.member("structured_content").isNull() ? null : res.member("structured_content");
        }
        else if ("web_search".equals(itemType)) {
            content = item.member("results");
        }
        String state = SourceProfile.text(item, "status");
        Json isError = "completed".equals(state) ? new Json.JBool(false) : "failed".equals(state) ? new Json.JBool(true) : null;
        return new ExpectedFacts.Result(index, SourceProfile.text(item, "id"), content, isError, structured, null, state, STREAM_LOCAL);
    }

    /** All-zero components may be the emitter's Usage::default(); a non-zero one shows an update was measured. */
    private static Integer measurement(Json usage) {
        boolean any = false;
        boolean nonZero = false;
        for (String field : USAGE_FIELDS) {
            Json v = usage == null ? null : usage.member(field);
            if (SourceProfile.isIntegral(v)) {
                any = true;
                nonZero |= ((Json.JNumber) v).value().signum() != 0;
            }
        }
        return !any ? null : nonZero ? 1 : 2;
    }

    /** Where the emitter's repeat is: the member {@code id} of the root's one {@code item} object (RFC 6901 {@code /item/id}). */
    private static final List<NeutralJson.Step> ITEM = List.of(NeutralJson.Step.member("item", 1));

    /**
     * A web_search line in the one repeated-member form the emitter writes, else {@code null}: the only repeat in the
     * line is {@code id} inside the root's {@code item}, exactly two string values, on item.started or item.completed. A
     * root member literally named {@code item.id} is another location and never this form.
     */
    private static Json webSearchLine(NativeArtifact.Record r) {
        if (r.text() == null) {
            return null;
        }
        NeutralJson.WithRepeats w;
        try {
            w = NeutralJson.parseReportingRepeats(r.text());
        }
        catch (IllegalArgumentException e) {
            return null;
        }
        if (w.repeated().size() != 1) {
            return null;
        }
        NeutralJson.Repeated rep = w.repeated().get(0);
        boolean id = ITEM.equals(rep.container()) && "id".equals(rep.member()) && rep.values().size() == 2
                && rep.values().stream().allMatch(v -> v instanceof Json.JString);
        Json o = w.value();
        String type = SourceProfile.text(o, "type");
        boolean form = id && ("item.started".equals(type) || "item.completed".equals(type)) && "web_search".equals(SourceProfile.text(o, "item", "type"));
        return form ? o : null;
    }
}
