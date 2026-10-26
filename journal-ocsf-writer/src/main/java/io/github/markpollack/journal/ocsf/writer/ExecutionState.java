package io.github.markpollack.journal.ocsf.writer;

import static io.github.markpollack.journal.ocsf.writer.MapperSupport.text;

import java.util.List;
import java.util.Map;

/**
 * Source-reported plans, task state, failures and tool details with no inferred lifecycle or
 * effects.
 */
final class ExecutionState {
    private ExecutionState() {}

    static boolean codex(NativeRecord record, SourceContext ctx, String session) {
        Jv n = record.json();
        String type = text(n, "type");
        Jv item = n.member("item");
        boolean plan = "todo_list".equals(text(item, "type"));
        boolean failure =
                "turn.failed".equals(type)
                        || "error".equals(type)
                        || "error".equals(text(item, "type"));
        if (!plan && !failure) {
            return false;
        }
        Jv.Obj event = codexEvent(record, ctx, session, plan, failure);
        ctx.family(
                plan ? type + "/todo_list" : type,
                record.raw().length,
                "normalized: reported execution state");
        ctx.emit(event);
        return true;
    }

    private static Jv.Obj codexEvent(
            NativeRecord record, SourceContext ctx, String session, boolean plan, boolean failure) {
        Jv n = record.json();
        String type = text(n, "type");
        Jv item = n.member("item");
        String rule =
                plan
                        ? "codex.stream.todo_list->session_context.state"
                        : "codex.stream.failure->session_context.outcome";
        Jv.Obj event =
                ctx.event(
                        "session_context",
                        plan ? 5 : 4,
                        record,
                        ctx.timeFor(record, null),
                        CodexStreamMapper.CONTRACT,
                        rule,
                        null,
                        text(item, "id"),
                        true,
                        failure ? new MapperSupport.Status(2, 1, "CLI reports failure") : null);
        event.put("session_uid", session)
                .put("context_kind", plan ? "todo_list" : type)
                .put("reported_state", type)
                .put("data", n);
        if (plan) {
            event.put("plan_items", plans(item.member("items")));
        } else {
            event.put(
                    "errors",
                    errors(
                            "turn.failed".equals(type)
                                    ? n.member("error")
                                    : item == null ? n : item));
        }
        return event;
    }

    static Jv.Obj claudeTask(NativeRecord record, SourceContext ctx) {
        Jv n = record.json();
        String subtype = text(n, "subtype");
        if (!List.of("task_started", "task_updated").contains(String.valueOf(subtype))) {
            return null;
        }
        String timestamp = text(n, "timestamp");
        Jv.Obj event =
                ctx.event(
                        "session_context",
                        5,
                        record,
                        ctx.timeFor(record, timestamp),
                        ClaudeStreamMapper.CONTRACT,
                        "claude.stream.system.task_state->session_context.state",
                        timestamp,
                        text(n, "uuid"),
                        true,
                        null);
        event.put("session_uid", text(n, "session_id"))
                .put("task_uid", text(n, "task_id"))
                .put("tool_call_uid", text(n, "tool_use_id"))
                .put("task_description", n.member("description"))
                .put("agent_type", n.member("subagent_type"))
                .put("context_kind", subtype)
                .put("reported_state", text(n, "patch", "status"))
                .put("end_time", n.path("patch", "end_time"))
                .put("content_blocks", prompt(n.member("prompt")))
                .put("data", n);
        return event;
    }

    static Jv.Obj tool(Jv.Obj event, Jv item) {
        String type = text(item, "type");
        if ("file_change".equals(type)) {
            event.put("file_changes", files(item.member("changes")));
        }
        if ("collab_tool_call".equals(type)) {
            event.put("sender_session_uid", item.member("sender_thread_id"))
                    .put("recipient_session_uids", item.member("receiver_thread_ids"))
                    .put("content_blocks", prompt(item.member("prompt")))
                    .put("agent_states", agents(item.member("agents_states")));
        }
        return event;
    }

    static Jv.Arr connections(Jv value) {
        if (!(value instanceof Jv.Arr array)) {
            return null;
        }
        Jv.Arr result = new Jv.Arr();
        for (Jv entry : array.elements()) {
            result.add(
                    new Jv.Obj()
                            .put("element_index", result.elements().size())
                            .put("name", entry.member("name"))
                            .put("reported_state", entry.member("status"))
                            .put("configuration_source", entry.member("source")));
        }
        return result;
    }

    static Jv.Arr errors(Jv value) {
        if (value == null || value.isNull()) {
            return null;
        }
        String message = value instanceof Jv.Str s ? s.value() : text(value, "message");
        return McpObservations.errors(
                McpObservations.error(
                        4, null, message, value, "Source reports an execution failure"));
    }

    private static Jv.Arr plans(Jv value) {
        Jv.Arr result = new Jv.Arr();
        for (Jv entry : value == null ? List.<Jv>of() : value.elements()) {
            result.add(
                    new Jv.Obj()
                            .put("element_index", result.elements().size())
                            .put("text", entry.member("text"))
                            .put("reported_completed", entry.member("completed")));
        }
        return result;
    }

    private static Jv.Arr files(Jv value) {
        Jv.Arr result = new Jv.Arr();
        for (Jv entry : value == null ? List.<Jv>of() : value.elements()) {
            result.add(
                    new Jv.Obj()
                            .put("element_index", result.elements().size())
                            .put("file_path", entry.member("path"))
                            .put("change_kind", entry.member("kind")));
        }
        return result;
    }

    private static Jv.Arr agents(Jv value) {
        if (!(value instanceof Jv.Obj states)) {
            return null;
        }
        Jv.Arr result = new Jv.Arr();
        for (Map.Entry<String, Jv> entry : states.members().entrySet()) {
            result.add(
                    new Jv.Obj()
                            .put("session_uid", entry.getKey())
                            .put("reported_state", entry.getValue().member("status"))
                            .put("text", entry.getValue().member("message")));
        }
        return result;
    }

    private static Jv.Arr prompt(Jv value) {
        if (!(value instanceof Jv.Str)) {
            return null;
        }
        return new Jv.Arr(
                List.of(
                        new Jv.Obj()
                                .put("block_index", 0)
                                .put("block_type", "string")
                                .put("block_kind_id", 1)
                                .put("block_kind", "Text")
                                .put("text", value)
                                .put("data", value)));
    }
}
