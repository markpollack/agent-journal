package io.github.markpollack.journal.ocsf.writer;

import java.util.List;

import static io.github.markpollack.journal.ocsf.writer.MapperSupport.IDENTITY_BASIS;
import static io.github.markpollack.journal.ocsf.writer.MapperSupport.MEASUREMENT_STATUS;
import static io.github.markpollack.journal.ocsf.writer.MapperSupport.REASONING_FORM;
import static io.github.markpollack.journal.ocsf.writer.MapperSupport.TOOL_KIND;
import static io.github.markpollack.journal.ocsf.writer.MapperSupport.text;

/**
 * Codex CLI {@code exec --json} live output (contract {@code codex-cli-0.160.1-exec-stream}, 0.6.0-prototype, whose
 * revised meanings await the managing reviewer's binding). The exec processor assigns its own item ids ({@code item_N}):
 * they are stream-local, unique only within this artifact of this Journal invocation, never a model-facing call id or a
 * provider's response id, which the stream omits. Agent messages and reasoning items are completed content items of
 * some model response (activity 3, {@code item_uid}); which response, and whether two items share one, is not reported,
 * so no {@code response_uid} is written. A reasoning item's one block is the emitter's joined reasoning summary. A command
 * execution is a standalone operation (no wrapping call id is reported) with its command string; a declined command
 * keeps status 99 "Declined", the item's reported status, not an observed permission decision. MCP, web search,
 * collaboration and file-change items are tool activities at the stage the stream reports: {@code item.started} is a
 * started invocation (activity 4, not the model's request), {@code item.completed} its observed result; the item type is
 * {@code tool_family}, a tool name is written only where the item reports one, and the kind comes from the family, or for
 * MCP from a reviewed namespace binding (none: Other), never from a bare name ({@link CodexItems} reads each item).
 * {@code turn.completed} carries the thread's cumulative usage ({@code ThreadTokenUsage.total}) as a cumulative snapshot
 * in canonical tranches: each value is at least its quantity (the total adds only the responses whose usage arrived),
 * a zero the deserializer also writes for an omitted member may be that default, and the uncached tranche is only a
 * conditional value. A web_search line carries the item's
 * {@code id} twice (the exec item id, then the search item's own id); only that declared form is interpreted, and its
 * record is retained verbatim. {@code thread.started} states the thread id, the session identity of every later event.
 * The stream carries no timestamps, so every event uses the capture-start fallback.
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
            unparsed(r, ctx);
            return;
        }
        Jv o = r.json();
        String type = text(o, "type");
        String itype = text(item(o), "type");
        if ("thread.started".equals(type) && threadId == null) {
            threadId = text(o, "thread_id");
        }
        String family = itype == null ? String.valueOf(type) : type + "/" + itype;
        switch (CodexItems.kind(type, itype)) {
            case RESPONSE -> response(r, ctx, o, itype, family);
            case OPERATION -> operation(r, ctx, o, family);
            case TOOL -> {
                ctx.family(family, r.raw().length, "normalized: tool_call " + ("item.started".equals(type) ? "Requested" : "Result observed") + " (stream-local item id)");
                toolCall(r, ctx, o, type, itype, true);
            }
            case TURN -> turn(r, ctx, o, family);
            case OTHER -> {
                ctx.family(family, r.raw().length, "thread.started".equals(type) ? "retained whole; thread_id is the session identity (session_uid of every later event, rule codex.stream.thread_started->session)"
                        : "turn.started".equals(type) ? "retained-only: turn start without a turn id (meaning known)" : "retained-only: no normalization rule");
                ctx.emit(ctx.fallback(r, CONTRACT, "codex.stream.fallback->source_record", family, null, null, session(ctx), null));
            }
        }
    }

    /** A line that is not one JSON object: retained verbatim, and read beside that only in the declared web_search form. */
    private void unparsed(NativeRecord r, SourceContext ctx) {
        Jv.WithDuplicates web = CodexItems.declaredWebSearch(r);
        if (web == null) {
            ctx.family("opaque", r.raw().length, "retained-only: not one JSON object (bytes kept verbatim)");
            ctx.emit(ctx.opaque(r, CONTRACT));
            return;
        }
        String type = text(web.value(), "type");
        ctx.family(type + "/web_search", r.raw().length, "normalized: tool_call " + ("item.started".equals(type) ? "Requested" : "Result observed")
                + " from the declared form with item.id twice (exec item id, then the search item's own id); the record is retained verbatim, both ids in raw_data");
        ctx.emit(ctx.opaque(r, CONTRACT));
        toolCall(r, ctx, web.value(), type, "web_search", false);
    }

    private String session(SourceContext ctx) {
        return threadId != null ? threadId : ctx.run.sessionUid();
    }

    private static Jv item(Jv o) {
        return o.member("item") instanceof Jv.Obj io ? io : new Jv.Obj();
    }

    /** An agent message, or a reasoning item whose text is the emitter's joined reasoning summary: a completed content item, its response unidentified. */
    private void response(NativeRecord r, SourceContext ctx, Jv o, String itype, String family) {
        boolean reasoning = "reasoning".equals(itype);
        Jv it = item(o);
        ctx.family(family, r.raw().length, reasoning ? "normalized: model_response Item completed with one reasoning-summary block (stream-local item id; response not identified)"
                : "normalized: model_response Item completed (stream-local item id; response not identified)");
        // item.text is the one content block; an item without text has no block (the source carries none)
        Jv.Arr blocks = new Jv.Arr();
        if (it.member("text") != null && !it.member("text").isNull()) {
            Jv.Obj block = new Jv.Obj().put("block_index", 0).put("block_type", itype + ".text");
            if (reasoning) {
                block.put("block_kind_id", 2).put("block_kind", "Thought").put("reasoning_form_id", 2).put("reasoning_form", REASONING_FORM.get(2))
                        .put("basis", "codex.stream.reasoning_summary.v1: the exec processor writes a reasoning item's summary parts joined by newlines; raw reasoning content is not projected");
            }
            else {
                block.put("block_kind_id", 1).put("block_kind", "Text").put("basis", "item.text is a string");
            }
            blocks.add(block.put("text", text(it, "text")).put("data", it.member("text")));
        }
        Jv.Obj ev = ctx.event("model_response", 3, r, ctx.timeFor(r, null), CONTRACT,
                reasoning ? "codex.stream.reasoning->model_response" : "codex.stream.agent_message->model_response", null, text(it, "id"), true, null);
        ev.put("session_uid", session(ctx)).put("item_uid", text(it, "id")).put("identity_basis_id", 2).put("identity_basis", IDENTITY_BASIS.get(2)).put("content_blocks", blocks)
                .put("basis", "codex.stream.content_item.v1: item.completed completes this content item; item_uid is the stream-local item id (the exec processor's item_N); "
                        + "the model response it belongs to is not reported, so no response_uid is written").put("data", o);
        ctx.emit(ev);
    }

    /** A command execution: a standalone operation observation with its reported command string, state and output. */
    private void operation(NativeRecord r, SourceContext ctx, Jv o, String family) {
        Jv it = item(o);
        ctx.family(family, r.raw().length, "normalized: tool_call Operation observed, standalone (the stream reports no wrapping call id)");
        Jv.Obj op = new Jv.Obj().put("uid", text(it, "id")).put("identity_basis_id", 2).put("identity_basis", IDENTITY_BASIS.get(2))
                .put("command_line", text(it, "command")).put("reported_state", text(it, "status"));
        if (MapperSupport.isIntegral(it.member("exit_code"))) {
            op.put("exit_code", it.member("exit_code"));
        }
        op.put("stdout", text(it, "aggregated_output"))
                .put("attribution", MapperSupport.attribution(3, 2, List.of(), "codex.stream_operation_attribution.v2: a standalone operation; the exec stream reports no wrapping call id and its item ids are stream-local", null))
                .put("data", it);
        String state = text(it, "status");
        MapperSupport.Status status = "declined".equals(state)
                ? new MapperSupport.Status(99, 1, "reported: item.status declined (the command was not run; the stream reports no permission request or decision)", "Declined")
                : new MapperSupport.Status("completed".equals(state) ? 1 : "failed".equals(state) ? 2 : 0, 1, "reported: item.status");
        Jv.Obj ev = ctx.event("tool_call", 3, r, ctx.timeFor(r, null), CONTRACT, "codex.stream.command_execution->tool_call.operation", null, text(it, "id"), true, status);
        ev.put("session_uid", session(ctx)).put("tool_operation", op).put("data", o);
        ctx.emit(ev);
    }

    /**
     * An MCP, web search, collaboration or file-change item as a tool activity at its reported stage: {@code item.started}
     * a started invocation (activity 4; the model's request is not observed), {@code item.completed} the observed result,
     * under the stream-local item id, with the item type as the family and a name only where the item reports one.
     *
     * @param whole whether this event retains the record whole ({@code false} for the web_search form, whose
     *        verbatim record is the opaque event beside it)
     */
    private void toolCall(NativeRecord r, SourceContext ctx, Jv o, String type, String itype, boolean whole) {
        Jv it = item(o);
        boolean started = "item.started".equals(type);
        String namespace = "mcp_tool_call".equals(itype) ? text(it, "server") : null;
        MappingContracts.Kind kind = ctx.contracts.familyKind(CONTRACT, itype, namespace, CodexItems.toolName(itype, it));
        Jv.Obj ev = ctx.event("tool_call", started ? 4 : 2, r, ctx.timeFor(r, null), CONTRACT, CodexItems.TOOL_RULES.get(itype).get(started ? 0 : 1), null, text(it, "id"), whole,
                started ? null : CodexItems.resultStatus(itype, it));
        ev.put("session_uid", session(ctx)).put("tool_call_uid", text(it, "id")).put("identity_basis_id", 2).put("identity_basis", IDENTITY_BASIS.get(2))
                .put("tool_name", CodexItems.toolName(itype, it)).put("tool_family", itype).put("tool_namespace", namespace)
                .put("tool_kind_id", kind.id()).put("tool_kind", TOOL_KIND.get(kind.id())).put("tool_kind_basis", kind.rule())
                .put("arguments", CodexItems.arguments(itype, it)).put("reported_state", text(it, "status"))
                .put("tool_result", started ? null : CodexItems.result(itype, it)).put("data", whole ? o : null);
        ctx.emit(ev);
    }

    /** turn.completed: the thread's cumulative usage as of this turn's completion, with its measurement status. */
    private void turn(NativeRecord r, SourceContext ctx, Jv o, String family) {
        ctx.family(family, r.raw().length, "normalized: prompt_turn Turn completed with the thread's cumulative usage snapshot (session scope, final for this run, canonical tranches)");
        Jv usage = o.member("usage");
        Jv.Obj ob = MapperSupport.usage(5, 2, "codex.stream.thread_usage.v2: turn.completed carries ThreadTokenUsage.total, the thread's cumulative usage as of this turn's completion,"
                + " including earlier turns of a resumed thread; not a per-turn measurement; final for this run", ctx.sourceRef(r, null, null, false), null, usage);
        UsageTranches.aggregation(ob, 2, "codex.stream.thread_usage_aggregation.v2: a running total of the thread up to this turn's completion that adds only the responses "
                + "whose completion carried usage; never summed with another snapshot");
        UsageTranches.codex(ob, usage, "", true);
        Integer measurement = CodexItems.measurement(usage);
        if (measurement != null) {
            ob.put("measurement_status_id", measurement).put("measurement_status", MEASUREMENT_STATUS.get(measurement));
        }
        UsageTranches.dataLast(ob);
        Jv.Obj ev = ctx.event("prompt_turn", 2, r, ctx.timeFor(r, null), CONTRACT, "codex.stream.turn_completed->prompt_turn.completed", null, null, true,
                new MapperSupport.Status(0, 3, "turn.completed carries no status"));
        ev.put("session_uid", session(ctx)).put("usage_observations", new Jv.Arr(List.of(ob))).put("data", o);
        ctx.emit(ev);
    }
}
