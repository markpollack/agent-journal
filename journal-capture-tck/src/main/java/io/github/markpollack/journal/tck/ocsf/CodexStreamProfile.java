package io.github.markpollack.journal.tck.ocsf;

import io.github.markpollack.journal.tck.verify.Json;
import io.github.markpollack.journal.tck.verify.NeutralJson;

import java.util.List;
import java.util.Set;

/**
 * Codex CLI {@code exec --json} live output, read from the pinned interface (codex-rs/exec/src/exec_events.rs and
 * event_processor_with_jsonl_output.rs at rust-v0.160.1 d27764b), never from the writer's mapper. The readings follow
 * the delegated SEM-1 decisions (2026-10-22); their revised claims await the managing reviewer's binding:
 *
 * <ul>
 * <li>Every {@code item.id} is the processor's own stream-local id (identity basis 2), unique within this artifact of
 * one invocation; not a model-facing call id or a provider's response id.</li>
 * <li>Agent messages and reasoning items are completed content items (activity 3, the item id as {@code item_uid});
 * the model response they belong to is not reported, so no response id is expected. A reasoning item's one block is
 * a reasoning summary (form 2).</li>
 * <li>A command execution is a standalone operation (unattributed) with its command string; completed, failed and
 * declined (status 99) are its reported states.</li>
 * <li>MCP, web search, collaboration and file-change items are tool activities: {@code item.started} reports a started
 * invocation (activity 4, not the model's request), {@code item.completed} its result. The item type is the family; a
 * tool name only where the item reports one ({@code item.tool} of MCP and collaboration items). The kind follows the
 * family (web search 5, file change 2), never a name: an MCP tool is 99 Other whatever it is called, as no reviewed
 * namespace binding exists, and a collaboration item is 99.</li>
 * <li>{@code turn.completed} reports the thread's cumulative usage (scope 5, final for the run, a cumulative snapshot)
 * in canonical tranches; a non-zero component shows it was measured, all-zero components may be the emitter's default.</li>
 * <li>A web_search line repeats the item's {@code id} (the exec item id, then the search item's own id). It is not one
 * JSON object under strict parsing, so it is retained verbatim, not normalized; its activity is read from the first id
 * only in exactly that form.</li>
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
            facts(f, r, o);
        }
        return f;
    }

    private static void facts(ExpectedFacts f, NativeArtifact.Record r, Json o) {
            boolean known = record(f, r.index(), o);
            if (known && SourceProfile.text(o, "item", "id") != null) {
                f.nativeUid.put(r.index(), SourceProfile.text(o, "item", "id"));
            }
            if (known && r.parsed()) {
                f.normalized.add(r.index()); // the web_search form is retained verbatim, not as a parsed record
            }
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
        if (TOOL_ITEMS.contains(itemType)) { return tool(f, index, itemType, item, started); }
        if (!started && ("agent_message".equals(itemType) || "reasoning".equals(itemType))) {
            f.responses.add(new ExpectedFacts.Response(index, SourceProfile.text(item, "id"),
                    item.member("text") == null ? List.of() : List.of(item.member("text")), null, true, null, "reasoning".equals(itemType) ? 2 : null, STREAM_LOCAL));
            return true;
        }
        return false;
    }

    private static boolean tool(ExpectedFacts f, int index, String itemType, Json item, boolean started) {

            McpReadings.codex(f, index, item, started);
            if (started) {
                f.requests.add(request(index, itemType, item));
            }
            else {
                f.results.add(result(index, itemType, item));
            }
            return true;
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
        f.addUsage(new ExpectedFacts.Usage(index, index, 5, 2, TrancheReading.codex(o.member("usage"), "", true), null, null, measurement(o.member("usage")), 2), "/usage", o.member("usage"));
        f.completed.add(new ExpectedFacts.TurnCompleted(index, 0, null));
        return true;
    }

    /**
     * The started invocation a tool item reports: the tool name only where the item reports one, an MCP call's arguments,
     * a search's action, the item type as the family and the kind by family (an MCP or collaboration tool is 99).
     */
    private static ExpectedFacts.Request request(int index, String itemType, Json item) {
        boolean mcp = "mcp_tool_call".equals(itemType);
        String name = mcp || "collab_tool_call".equals(itemType) ? SourceProfile.text(item, "tool") : null;
        Json arguments = mcp ? item.member("arguments") : "web_search".equals(itemType) ? item.member("action") : null;
        int kind = switch (itemType) {
            case "web_search" -> 5;
            case "file_change" -> 2;
            default -> 99;
        };
        return new ExpectedFacts.Request(index, SourceProfile.text(item, "id"), name, arguments, null, SourceProfile.text(item, "status"),
                mcp ? SourceProfile.text(item, "server") : null, STREAM_LOCAL, 4, itemType, kind);
    }

    /** The result a completed tool item reports: MCP result content and structured content, search results; the outcome its status reports. */
    private static Json reportedError(String state) {
        if ("completed".equals(state)) { return new Json.JBool(false); }
        return "failed".equals(state) ? new Json.JBool(true) : null;
    }

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
        else if ("mcp_tool_call".equals(itemType) && item.member("error") instanceof Json.JObject) {
            content = item.member("error");
        }
        String state = SourceProfile.text(item, "status");
        Json isError = reportedError(state);
        return new ExpectedFacts.Result(index, SourceProfile.text(item, "id"), content, isError, structured, null, state, STREAM_LOCAL, itemType);
    }

    /**
     * All-zero components may be the emitter's Usage::default() (2); a non-zero one shows an update arrived, but the thread
     * running total adds only the responses whose usage arrived, so its values are each only a lower bound (4).
     */
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
        return !any ? null : nonZero ? 4 : 2;
    }

    /** Where the emitter's repeat is: the member {@code id} of the root's one {@code item} object (RFC 6901 {@code /item/id}). */
    private static final List<NeutralJson.Step> ITEM = List.of(NeutralJson.Step.member("item", 1));

    /**
     * A web_search line in the one repeated-member form the emitter writes, else {@code null}: the only repeat in the
     * line is {@code id} inside the root's {@code item}, exactly two string values, on item.started or item.completed. A
     * root member literally named {@code item.id} is another location and never this form.
     */
    static Json webSearchLine(NativeArtifact.Record r) {
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
