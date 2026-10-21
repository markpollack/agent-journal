package io.github.markpollack.journal.ocsf.writer;

import java.util.List;

import static io.github.markpollack.journal.ocsf.writer.MapperSupport.text;

/**
 * Codex CLI {@code exec --json} live output (contract {@code codex-cli-0.160.1-exec-stream}). Agent messages are
 * responses with stream-scoped item ids; command executions are operations without any call id (unattributed; the
 * stream's item ids do not occur in the rollout, R13-08); {@code turn.completed} reports the turn's usage once;
 * {@code thread.started} states the thread id, which is the session identity ({@code session_uid}) of every later
 * event of the stream (the record itself is retained whole). The stream carries no timestamps, so every event uses
 * the capture-start fallback.
 */
final class CodexStreamMapper implements SourceMapper {

    static final String CONTRACT = "codex-cli-0.160.1-exec-stream";

    /** The thread id the stream stated in thread.started: the native session identity of every later record. */
    private String threadId;

    @Override
    public String contractName() {
        return CONTRACT;
    }

    @Override
    public void map(NativeRecord r, SourceContext ctx) {
        if (!r.parsed()) {
            ctx.family("opaque", r.raw().length, "retained-only: not one JSON object (bytes kept verbatim)");
            ctx.emit(ctx.opaque(r, CONTRACT));
            return;
        }
        Jv o = r.json();
        String type = text(o, "type");
        Jv it = o.member("item") instanceof Jv.Obj io ? io : new Jv.Obj();
        String itype = text(it, "type");
        if ("thread.started".equals(type) && text(o, "thread_id") != null && threadId == null) {
            threadId = text(o, "thread_id");
        }
        String session = threadId != null ? threadId : ctx.run.sessionUid();
        MapperSupport.Time t = ctx.timeFor(r, null);
        String family = itype == null ? String.valueOf(type) : type + "/" + itype;
        if ("item.completed".equals(type) && "agent_message".equals(itype)) {
            ctx.family(family, r.raw().length, "normalized: model_response Completed (stream-scoped item id)");
            // item.text is the one content block; an agent_message without text has no block (the source carries none)
            Jv.Arr blocks = new Jv.Arr();
            if (it.member("text") != null && !it.member("text").isNull()) {
                Jv.Obj block = new Jv.Obj().put("block_index", 0).put("block_type", "agent_message.text").put("block_kind_id", 1).put("block_kind", "Text")
                        .put("basis", "item.text is a string");
                if (text(it, "text") != null) {
                    block.put("text", text(it, "text"));
                }
                blocks.add(block.put("data", it.member("text")));
            }
            Jv.Obj ev = ctx.event("model_response", 2, r, t, CONTRACT, "codex.stream.agent_message->model_response", null, text(it, "id"), true, null);
            ev.put("session_uid", session).put("response_uid", text(it, "id")).put("content_blocks", blocks)
                    .put("basis", "item.completed is terminal; response_uid is the stream-scoped item id").put("data", o);
            ctx.emit(ev);
            return;
        }
        if (("item.started".equals(type) || "item.completed".equals(type)) && "command_execution".equals(itype)) {
            ctx.family(family, r.raw().length, "normalized: tool_call Operation observed (unattributed: the stream reports no call id)");
            Jv.Obj op = new Jv.Obj().put("uid", text(it, "id"));
            if (text(it, "status") != null) {
                op.put("reported_state", text(it, "status"));
            }
            if (MapperSupport.isIntegral(it.member("exit_code"))) {
                op.put("exit_code", it.member("exit_code"));
            }
            if (text(it, "aggregated_output") != null) {
                op.put("stdout", text(it, "aggregated_output"));
            }
            op.put("attribution", MapperSupport.attribution(3, 2, List.of(), "codex.stream_operation_attribution.v1: the exec stream reports no call id; item ids do not occur in the rollout (R13-08)", null))
                    .put("data", it);
            String state = text(it, "status");
            int status = "completed".equals(state) ? 1 : "failed".equals(state) ? 2 : 0;
            Jv.Obj ev = ctx.event("tool_call", 3, r, t, CONTRACT, "codex.stream.command_execution->tool_call.operation", null, text(it, "id"), true,
                    new MapperSupport.Status(status, 1, "reported: item.status"));
            ev.put("session_uid", session).put("tool_operation", op).put("data", o);
            ctx.emit(ev);
            return;
        }
        if ("turn.completed".equals(type)) {
            ctx.family(family, r.raw().length, "normalized: prompt_turn Turn completed with the turn's usage (final)");
            Jv.Obj ob = MapperSupport.usage(3, 2, "codex.stream.turn_usage.v1: turn.completed reports the turn's usage once", ctx.sourceRef(r, null, null, false), null, o.member("usage"));
            MapperSupport.components(ob, o.member("usage"), "input_tokens", "input_tokens", "output_tokens", "output_tokens", "cached_input_tokens", "cache_read_tokens",
                    "cache_write_input_tokens", "cache_write_tokens", "reasoning_output_tokens", "reasoning_tokens");
            Jv.Obj ev = ctx.event("prompt_turn", 2, r, t, CONTRACT, "codex.stream.turn_completed->prompt_turn.completed", null, null, true,
                    new MapperSupport.Status(0, 3, "turn.completed carries no status"));
            ev.put("session_uid", session).put("usage_observations", new Jv.Arr(List.of(ob))).put("data", o);
            ctx.emit(ev);
            return;
        }
        ctx.family(family, r.raw().length, "thread.started".equals(type) ? "retained whole; thread_id is the session identity (session_uid of every later event, rule codex.stream.thread_started->session)"
                : "turn.started".equals(type) ? "retained-only: turn start without a turn id (meaning known)" : "retained-only: no normalization rule");
        ctx.emit(ctx.fallback(r, CONTRACT, "codex.stream.fallback->source_record", family, null, null, session, null));
    }
}
