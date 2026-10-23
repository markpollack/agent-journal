package io.github.markpollack.journal.ocsf.writer;

import java.util.List;

import static io.github.markpollack.journal.ocsf.writer.MapperSupport.TOOL_KIND;
import static io.github.markpollack.journal.ocsf.writer.MapperSupport.text;

/**
 * Antigravity CLI headless {@code --output-format stream-json} live output (contract {@code antigravity-cli-1.4.6-stream},
 * 0.1.0-prototype, a candidate for the managing reviewer), read from the documented interface (one {@code init} event,
 * {@code step_update} events, one {@code result} event per turn) and the 1.4.6 recordings. Every record reports its
 * conversation id, the session identity. A step is identified by its {@code step_index}, the conversation's own step
 * numbering (identity basis 99 Other: neither a model-facing call id nor a stream-local item id). The init event is the
 * session's reported settings; a DONE user-input step is a prompt submission whose text the stream never carries; an
 * agent-response step is a content item of an unidentified model response, Partial while ACTIVE with its
 * {@code text_delta} fragment and Item completed when DONE with the step's own usage; a tool step is a started invocation
 * when ACTIVE and a result when DONE or ERROR, failed only where {@code tool_info.error} is reported (DONE is a lifecycle
 * state: a soft-denied command reports DONE without output or error); a subagent step completes the invoke_subagent call
 * of the same step with the reported subagent_info as its result content (no child run is linked: the child's records are
 * not in this stream); the result event completes the turn with its response text, the session's cumulative usage and
 * its denied actions, which name a permission category and never a step. Checkpoint, system_message and unknown steps
 * are retained whole. The stream carries no timestamps.
 */
final class AntigravityStreamMapper implements SourceMapper {

    static final String CONTRACT = "antigravity-cli-1.4.6-stream";

    private static final String IDENTITY = "agy.stream.step_identity.v1";
    /** The caption of identity_basis_id 99 Other for a step index: per OCSF, an Other value names the specific basis in its sibling. */
    static final String STEP_INDEX_BASIS = "Conversation step index";

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
        String event = text(o, "event");
        if ("init".equals(event) && o.member("init") instanceof Jv.Obj init) {
            init(r, ctx, o, init);
        }
        else if ("result".equals(event) && o.member("result") instanceof Jv.Obj res) {
            result(r, ctx, o, res);
        }
        else if ("step_update".equals(event) && o.member("step_update") instanceof Jv.Obj s) {
            step(r, ctx, o, s);
        }
        else {
            ctx.family(String.valueOf(event), r.raw().length, "retained-only: no normalization rule");
            ctx.emit(ctx.fallback(r, CONTRACT, "agy.stream.fallback->source_record", String.valueOf(event), null, null, text(o, "conversation_id"), null));
        }
    }

    /** One step_update record, by step type and state. */
    private void step(NativeRecord r, SourceContext ctx, Jv o, Jv.Obj s) {
        String type = text(s, "step_type");
        String state = text(s, "state");
        String family = "step_update/" + type + "/" + state;
        boolean live = "ACTIVE".equals(state) || "DONE".equals(state);
        if ("user_input".equals(type) && "DONE".equals(state)) {
            ctx.family(family, r.raw().length, "normalized: prompt_turn Prompt submitted (the stream carries no prompt text)");
            Jv.Obj ev = ctx.event("prompt_turn", 1, r, ctx.timeFor(r, null), CONTRACT, "agy.stream.user_input->prompt_turn.submitted", null, null, true, null);
            ctx.emit(ev.put("session_uid", text(s, "conversation_id")).put("data", o));
        }
        else if ("agent_response".equals(type) && live) {
            response(r, ctx, o, s, family, "DONE".equals(state));
        }
        else if (toolStep(type, s) && (live || "ERROR".equals(state))) {
            tool(r, ctx, o, s, family, "ACTIVE".equals(state));
        }
        else {
            ctx.family(family, r.raw().length, retainedOnly(type));
            ctx.emit(ctx.fallback(r, CONTRACT, "agy.stream.fallback->source_record", family, null, null, text(s, "conversation_id"), null));
        }
    }

    /** A tool step, or a subagent step that completes the invoke_subagent call of the same step (it reports the tool name). */
    private static boolean toolStep(String type, Jv.Obj s) {
        return "tool".equals(type) || ("subagent".equals(type) && text(s, "tool_name") != null);
    }

    private static String retainedOnly(String type) {
        if ("checkpoint".equals(type)) {
            return "retained-only: checkpoint step (meaning not documented; its usage is included in the result's session usage)";
        }
        return "system_message".equals(type) ? "retained-only: system_message step (undocumented step type; observed without content)" : "retained-only: no normalization rule";
    }

    /** The init event: the session's launch settings as reported once. */
    private void init(NativeRecord r, SourceContext ctx, Jv o, Jv.Obj init) {
        ctx.family("init", r.raw().length, "normalized: session_context Settings reported (cwd, permission mode, model and agent when set, tools)");
        Jv.Obj settings = new Jv.Obj().put("working_directory", text(init, "cwd")).put("permission_mode", text(init, "permission_mode"))
                .put("model", text(init, "model")).put("agent", text(init, "agent"));
        if (init.member("tools") instanceof Jv.Arr tools) {
            settings.put("tool_names", tools);
        }
        Jv.Obj ev = ctx.event("session_context", 1, r, ctx.timeFor(r, null), CONTRACT, "agy.stream.init->session_context.settings", null, null, true, null);
        ev.put("session_uid", text(o, "conversation_id")).put("context_kind", "init").put("settings", settings)
                .put("basis", "agy.stream.init.v1: the init event reports the run configuration once, at stream start").put("data", o);
        ctx.emit(ev);
    }

    /** An agent-response step: a Partial view while ACTIVE (its text fragment), Item completed when DONE with the step's usage. */
    private void response(NativeRecord r, SourceContext ctx, Jv o, Jv.Obj s, String family, boolean done) {
        ctx.family(family, r.raw().length, done ? "normalized: model_response Item completed (step identity; response not identified) with the step's usage"
                : "normalized: model_response Partial (step identity) with its text_delta fragment");
        Jv.Obj ev = ctx.event("model_response", done ? 3 : 1, r, ctx.timeFor(r, null), CONTRACT,
                done ? "agy.stream.agent_response.done->model_response.item" : "agy.stream.agent_response.active->model_response.partial", null, null, true, null);
        ev.put("session_uid", text(s, "conversation_id")).put("item_uid", step(s)).put("identity_basis_id", 99).put("identity_basis", STEP_INDEX_BASIS);
        if (s.member("text_delta") instanceof Jv.Str delta) {
            Jv.Obj block = new Jv.Obj().put("block_index", 0).put("block_type", "text_delta").put("block_kind_id", 1).put("block_kind", "Text")
                    .put("basis", "agy.stream.text_delta.v1: an increment of the step's response text; the step's text is its fragments joined in record order")
                    .put("text", delta.value()).put("data", delta);
            ev.put("content_blocks", new Jv.Arr(List.of(block)));
        }
        if (s.member("usage") instanceof Jv.Obj usage) {
            Jv.Obj ob = MapperSupport.usage(2, done ? 2 : 1, "agy.stream.step_usage.v1: step_update.usage is documented as the step's own token usage; "
                    + (done ? "reported on the step's DONE record" : "reported on a record of a step still ACTIVE"), ctx.sourceRef(r, null, null, false), null, usage);
            UsageTranches.aggregation(ob, 1, "agy.stream.step_usage_aggregation.v1: the total of this one step");
            UsageTranches.antigravity(ob, usage);
            ev.put("usage_observations", new Jv.Arr(List.of(ob)));
        }
        ev.put("basis", "agy.stream.agent_response.v1: an agent_response step is a content item of a model response the stream does not identify; " + IDENTITY
                + ": item_uid is the conversation's step_index").put("data", o);
        ctx.emit(ev);
    }

    /**
     * A tool step: a started invocation while ACTIVE (tool name, kind and arguments), its result when DONE or ERROR. The
     * result's own copy of the name and parameters stays in its retained record: the call is joined by tool_call_uid.
     */
    private void tool(NativeRecord r, SourceContext ctx, Jv o, Jv.Obj s, String family, boolean active) {
        boolean subagent = "subagent".equals(text(s, "step_type"));
        ctx.family(family, r.raw().length, toolDisposition(active, subagent));
        Jv info = s.member("tool_info");
        Jv error = info == null ? null : info.member("error");
        Jv.Obj ev = ctx.event("tool_call", active ? 4 : 2, r, ctx.timeFor(r, null), CONTRACT, toolRule(active, subagent), null, null, true, active ? null : outcome(error, text(s, "state")));
        ev.put("session_uid", text(s, "conversation_id")).put("tool_call_uid", step(s)).put("identity_basis_id", 99).put("identity_basis", STEP_INDEX_BASIS);
        if (active) {
            String name = text(s, "tool_name");
            int kind = ctx.contracts.toolKind(CONTRACT, name);
            ev.put("tool_name", name).put("tool_kind_id", kind).put("tool_kind", TOOL_KIND.get(kind)).put("tool_kind_basis", ctx.contracts.toolKindRule(CONTRACT))
                    .put("arguments", info == null ? null : info.member("parameters"));
        }
        ev.put("reported_state", text(s, "state")).put("tool_result", active ? null : toolResult(s, info, error, subagent)).put("data", o);
        ctx.emit(ev);
    }

    private static String toolDisposition(boolean active, boolean subagent) {
        if (active) {
            return "normalized: tool_call Started (step identity)";
        }
        return subagent ? "normalized: tool_call Result observed (step identity) with the reported subagent_info as result content; no child run is linked"
                : "normalized: tool_call Result observed (step identity)";
    }

    private static String toolRule(boolean active, boolean subagent) {
        if (active) {
            return "agy.stream.tool.active->tool_call.started";
        }
        return subagent ? "agy.stream.subagent.done->tool_call.result" : "agy.stream.tool.done->tool_call.result";
    }

    /**
     * The result content a finished step reports: the output, else the error, else (subagent step) the subagent_info;
     * {@code null} when it reports none.
     */
    private static Jv.Obj toolResult(Jv.Obj s, Jv info, Jv error, boolean subagent) {
        Jv output = info == null ? null : info.member("output");
        Jv content = output != null ? output : error;
        if (content == null && subagent) {
            content = s.member("subagent_info");
        }
        if (content == null) {
            return null;
        }
        Jv.Obj result = new Jv.Obj().put("data", content);
        return error != null ? result.put("error_reported", true) : result;
    }

    /** A finished step failed only where it reports tool_info.error; otherwise its outcome is not derivable. */
    private static MapperSupport.Status outcome(Jv error, String state) {
        return error != null ? new MapperSupport.Status(2, 1, "reported: tool_info.error (documented as present when the tool fails)")
                : new MapperSupport.Status(0, 3, "agy.stream.tool_outcome.v1: " + state + " is a lifecycle state and no error is reported, which does not establish success "
                        + "(a soft-denied command reports DONE without output or error)");
    }

    /** The result event: the turn's completion with its response text, the session's cumulative usage and its denied actions. */
    private void result(NativeRecord r, SourceContext ctx, Jv o, Jv.Obj res) {
        ctx.family("result", r.raw().length, "normalized: prompt_turn Turn completed with the turn's response text, the session's cumulative usage and run-scope denied actions");
        Jv.Obj ev = ctx.event("prompt_turn", 2, r, ctx.timeFor(r, null), CONTRACT, "agy.stream.result->prompt_turn.completed", null, null, true, turnStatus(text(res, "status")));
        ev.put("session_uid", text(res, "conversation_id"));
        if (res.member("response") instanceof Jv.Str response) {
            ev.put("content_blocks", new Jv.Arr(List.of(new Jv.Obj().put("block_index", 0).put("block_type", "response").put("block_kind_id", 1).put("block_kind", "Text")
                    .put("basis", "agy.stream.result_response.v1: result.response is documented as the turn's own response text").put("text", response.value()).put("data", response))));
        }
        if (res.member("usage") instanceof Jv.Obj usage) {
            Jv.Obj ob = MapperSupport.usage(5, 2, "agy.stream.session_usage.v1: result.usage is documented as cumulative over the session; final for this run",
                    ctx.sourceRef(r, null, null, false), null, usage);
            UsageTranches.aggregation(ob, 2, "agy.stream.session_usage_aggregation.v1: a running total of the conversation as of this turn's end; never summed with another result's");
            UsageTranches.antigravity(ob, usage);
            ev.put("usage_observations", new Jv.Arr(List.of(ob)));
        }
        Jv.Arr decisions = denials(r, ctx, res);
        if (!decisions.elements().isEmpty()) {
            ev.put("permission_decisions", decisions);
        }
        ctx.emit(ev.put("data", o));
    }

    /** result.status as reported: SUCCESS 1, ERROR 2, any other word 99 with that word as its label, absent not derivable. */
    private static MapperSupport.Status turnStatus(String reported) {
        if (reported == null) {
            return new MapperSupport.Status(0, 3, "result.status not reported");
        }
        return switch (reported) {
            case "SUCCESS" -> new MapperSupport.Status(1, 1, "reported: result.status SUCCESS");
            case "ERROR" -> new MapperSupport.Status(2, 1, "reported: result.status ERROR");
            default -> new MapperSupport.Status(99, 1, "reported: result.status " + reported, reported);
        };
    }

    /** Each denied_actions entry: a run-scope denial naming a permission category, never a step, so unattributed. */
    private static Jv.Arr denials(NativeRecord r, SourceContext ctx, Jv.Obj res) {
        Jv.Arr decisions = new Jv.Arr();
        for (Jv d : res.member("denied_actions") == null ? List.<Jv>of() : res.member("denied_actions").elements()) {
            decisions.add(new Jv.Obj().put("decision_id", 2).put("decision", "Denied").put("reported_decision", "denied").put("scope_id", 4).put("scope", "Run")
                    .put("attribution", MapperSupport.attribution(3, 2, List.of(), "agy.stream.denied_action_attribution.v1: a denied_actions entry names a permission action category and a display name, "
                            + "never a step; not linked to a tool step by name, position or text", null))
                    .put("source_ref", ctx.sourceRef(r, null, null, false)).put("data", d));
        }
        return decisions;
    }

    /** The step's identity: its step_index as decimal text, or {@code null} when the record reports none. */
    private static String step(Jv.Obj s) {
        Jv idx = s.member("step_index");
        return MapperSupport.isIntegral(idx) ? ((Jv.Num) idx).value().toBigIntegerExact().toString() : null;
    }
}
