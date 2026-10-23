package io.github.markpollack.journal.ocsf.writer;

import java.util.List;
import java.util.Map;

import static io.github.markpollack.journal.ocsf.writer.MapperSupport.text;

/**
 * How the Codex exec-stream contract (0.4.0-prototype) reads one item: which rule applies, the tool name and
 * arguments an item reports, its result content and outcome, the usage measurement status and the one repeated-member
 * form it interprets. Read from the pinned interface (codex-rs/exec at rust-v0.160.1); {@link CodexStreamMapper}
 * emits the events.
 */
final class CodexItems {

    /** What a parsed record becomes. */
    enum Kind { RESPONSE, OPERATION, TOOL, TURN, OTHER }

    /** Request and result rule of each item family normalized as a tool call. */
    static final Map<String, List<String>> TOOL_RULES = Map.of(
            "mcp_tool_call", List.of("codex.stream.mcp_tool_call->tool_call.requested", "codex.stream.mcp_tool_call->tool_call.result"),
            "web_search", List.of("codex.stream.web_search->tool_call.requested", "codex.stream.web_search->tool_call.result"),
            "collab_tool_call", List.of("codex.stream.collab_tool_call->tool_call.requested", "codex.stream.collab_tool_call->tool_call.result"),
            "file_change", List.of("codex.stream.file_change->tool_call.requested", "codex.stream.file_change->tool_call.result"));

    /** The five components of the emitter's Usage; all zero is also what it writes when no token-usage update arrived. */
    private static final List<String> USAGE_FIELDS = List.of("input_tokens", "cached_input_tokens", "cache_write_input_tokens", "output_tokens", "reasoning_output_tokens");

    private CodexItems() {
    }

    static Kind kind(String type, String itype) {
        boolean item = "item.started".equals(type) || "item.completed".equals(type);
        if ("item.completed".equals(type) && ("agent_message".equals(itype) || "reasoning".equals(itype))) {
            return Kind.RESPONSE;
        }
        if (item && "command_execution".equals(itype)) {
            return Kind.OPERATION;
        }
        if (item && TOOL_RULES.containsKey(itype)) {
            return Kind.TOOL;
        }
        return "turn.completed".equals(type) ? Kind.TURN : Kind.OTHER;
    }

    /** The tool name the item reports (MCP and collaboration {@code item.tool}), else {@code null}: a family such as file_change is not a name. */
    static String toolName(String itype, Jv it) {
        return "mcp_tool_call".equals(itype) || "collab_tool_call".equals(itype) ? text(it, "tool") : null;
    }

    /** The arguments an item reports: an MCP call's arguments, a web search's action; the other families report none. */
    static Jv arguments(String itype, Jv it) {
        return switch (itype) {
            case "mcp_tool_call" -> it.member("arguments");
            case "web_search" -> it.member("action");
            default -> null;
        };
    }

    /** The reported status of a completed item: completed and failed are reported outcomes; no status is not derivable. */
    static MapperSupport.Status resultStatus(String itype, Jv it) {
        String state = text(it, "status");
        String basis = "file_change".equals(itype) ? "reported: item.status (the exec processor reports a declined patch as failed)" : "reported: item.status";
        if ("completed".equals(state) || "failed".equals(state)) {
            return new MapperSupport.Status("completed".equals(state) ? 1 : 2, 1, basis);
        }
        return new MapperSupport.Status(0, 3, "item.status not reported on this item");
    }

    /**
     * The result content a completed item reports, or {@code null} when it reports none: an MCP call's result content
     * with its structured content and the success or failure its status reports, a web search's results. Collaboration
     * and file-change items report no result content; their members stay in the retained record.
     */
    static Jv.Obj result(String itype, Jv it) {
        if ("web_search".equals(itype)) {
            return it.member("results") == null ? null : new Jv.Obj().put("data", it.member("results"));
        }
        if (!"mcp_tool_call".equals(itype) || !(it.member("result") instanceof Jv.Obj res)) {
            return null;
        }
        Jv.Obj out = new Jv.Obj().put("data", res.member("content") == null ? Jv.Null.INSTANCE : res.member("content"));
        String state = text(it, "status");
        if ("completed".equals(state) || "failed".equals(state)) {
            out.put("error_reported", "failed".equals(state));
        }
        Jv structured = res.member("structured_content");
        return structured == null || structured.isNull() ? out : out.put("structured_result", structured);
    }

    /**
     * codex.stream.usage_measurement.v2: any non-zero component proves a token-usage update arrived (the emitter's
     * default is all zeros), but the thread running total adds only the responses whose usage arrived, so its values are
     * each at least their quantity (4 Lower bound), never established as complete; all reported components zero may be
     * that default (2); no integral component, no statement.
     */
    static Integer measurement(Jv usage) {
        boolean any = false;
        boolean nonZero = false;
        for (String f : USAGE_FIELDS) {
            if (usage != null && usage.member(f) instanceof Jv.Num n && n.integral()) {
                any = true;
                nonZero |= n.value().signum() != 0;
            }
        }
        if (!any) {
            return null;
        }
        return nonZero ? 4 : 2;
    }

    /** The container of the one declared repeat: the root's first (and only) {@code item} member, RFC 6901 {@code /item}. */
    private static final Jv.Location ITEM = Jv.Location.ROOT.member("item", 1);

    /**
     * The one duplicate form the contract interprets: an {@code item.started} or {@code item.completed} web_search line
     * whose only repeated member is {@code id}, exactly twice and both strings, inside the root's {@code item} object
     * ({@code /item/id}), as the emitter's flattened ThreadItem writes it. The location is exact: a root member literally
     * named {@code item.id} ({@code /item.id}), a repeat in another object, a third value or any further repeat is not
     * this form and stays an opaque record without interpretation.
     */
    static Jv.WithDuplicates declaredWebSearch(NativeRecord r) {
        if (r.status() != NativeRecord.Status.MALFORMED || r.text() == null) {
            return null;
        }
        Jv.WithDuplicates w;
        try {
            w = Jv.parseRecordingDuplicates(r.text());
        }
        catch (IllegalArgumentException e) {
            return null;
        }
        if (!(w.value() instanceof Jv.Obj o) || w.repeated().size() != 1) {
            return null;
        }
        Jv.Repeated d = w.repeated().get(0);
        boolean itemId = ITEM.equals(d.container()) && "id".equals(d.member()) && d.values().size() == 2
                && d.values().get(0) instanceof Jv.Str && d.values().get(1) instanceof Jv.Str;
        String type = text(o, "type");
        boolean event = "item.started".equals(type) || "item.completed".equals(type);
        return itemId && event && "web_search".equals(text(o, "item", "type")) ? w : null;
    }
}
