package io.github.markpollack.journal.ocsf.writer;

import java.util.List;

import static io.github.markpollack.journal.ocsf.writer.MapperSupport.text;

/**
 * Claude Code {@code --output-format stream-json} live output (contract {@code claude-code-2.1.303-stream}). Each
 * record is mapped as it arrives; the only state is the artifact's time fallback. Assistant records are responses
 * (one block per record, Partial until a stop reason is reported), their tool_use blocks are requests, user records
 * with tool_result blocks are results owned by the block's own id, the system init record is the session's reported
 * settings, the result record completes the turn with run/model final usage, cost and run-scope denials, and every
 * other record is retained whole. A record whose {@code parent_tool_use_id} names a tool call is routed to that
 * child core.
 */
final class ClaudeStreamMapper implements SourceMapper {

    static final String CONTRACT = "claude-code-2.1.303-stream";

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
        String subtype = text(o, "subtype");
        String ts = text(o, "timestamp");
        String uid = text(o, "uuid");
        String sess = text(o, "session_id");
        String owner = text(o, "parent_tool_use_id");
        String family = subtype == null ? String.valueOf(type) : type + "/" + subtype;
        if ("assistant".equals(type) && o.member("message") instanceof Jv.Obj m) {
            ctx.family(family, r.raw().length, "normalized: model_response (+ tool_call Requested per tool_use block)");
            MapperSupport.Time t = ctx.timeFor(r, ts);
            boolean terminal = m.member("stop_reason") != null && !m.member("stop_reason").isNull();
            int act = terminal ? 2 : 1;
            Jv.Obj usage = MapperSupport.usage(2, terminal ? 2 : 1, "claude.stream.usage_status.v1: stop_reason null -> provisional, non-null -> final",
                    ctx.sourceRef(r, uid, ts, false), MapperSupport.modelRef(text(m, "model"), 3, null), m.member("usage"));
            MapperSupport.components(usage, m.member("usage"), "input_tokens", "input_tokens", "output_tokens", "output_tokens",
                    "cache_read_input_tokens", "cache_read_tokens", "cache_creation_input_tokens", "cache_write_tokens");
            Jv thinking = m.path("usage", "output_tokens_details", "thinking_tokens");
            if (MapperSupport.isIntegral(thinking)) {
                usage.put("reasoning_tokens", thinking);
            }
            Jv.Obj ev = ctx.event("model_response", act, r, t, CONTRACT, "claude.stream.assistant->model_response", ts, uid, true, null);
            ev.put("session_uid", sess).put("response_uid", text(m, "id")).put("model_ref", MapperSupport.modelRef(text(m, "model"), 3, null))
                    .put("content_blocks", MapperSupport.blocks(ctx.contracts, CONTRACT, m.member("content")))
                    .put("usage_observations", new Jv.Arr(List.of(usage)))
                    .put("basis", "claude.stream.activity.v1: stop_reason null -> Partial, non-null -> Completed");
            if (terminal) {
                ev.put("stop_reason", text(m, "stop_reason"));
            }
            ev.put("data", o);
            ctx.emit(owner, ev);
            for (Jv b : m.member("content") == null ? List.<Jv>of() : m.member("content").elements()) {
                if ("tool_use".equals(text(b, "type"))) {
                    int k = ctx.contracts.toolKind(CONTRACT, text(b, "name"));
                    Jv.Obj req = ctx.event("tool_call", 1, r, t, CONTRACT, "claude.stream.assistant.tool_use->tool_call.requested", ts, uid, false, null);
                    req.put("session_uid", sess).put("response_uid", text(m, "id")).put("tool_call_uid", text(b, "id")).put("tool_name", text(b, "name"))
                            .put("tool_kind_id", k).put("tool_kind", MapperSupport.TOOL_KIND.get(k)).put("tool_kind_basis", ctx.contracts.toolKindRule(CONTRACT))
                            .put("arguments", b.member("input") == null ? Jv.NULL : b.member("input")).put("data", b);
                    ctx.emit(owner, req);
                }
            }
            return;
        }
        if ("user".equals(type) && o.member("message") instanceof Jv.Obj m) {
            MapperSupport.Time t = ctx.timeFor(r, ts);
            Jv content = m.member("content");
            boolean hasResult = false;
            for (Jv b : content == null ? List.<Jv>of() : content.elements()) {
                if ("tool_result".equals(text(b, "type"))) {
                    hasResult = true;
                }
            }
            if (!hasResult) {
                // the stream echoes a prompt only in a child scope (the sub-agent's prompt as delivered by the spawning call); the main
                // thread's -p prompt is never echoed. The record is a vendor-reported prompt submission with its content blocks; the stream
                // names no turn id, so none is invented.
                ctx.family("user (prompt)", r.raw().length, "normalized: prompt_turn Prompt submitted (user record without tool_result; observed in child scopes only)");
                Jv.Obj ev = ctx.event("prompt_turn", 1, r, t, CONTRACT, "claude.stream.user.prompt->prompt_turn.submitted", ts, uid, true, null);
                ev.put("session_uid", sess).put("content_blocks", MapperSupport.blocks(ctx.contracts, CONTRACT, content)).put("data", o);
                ctx.emit(owner, ev);
                return;
            }
            ctx.family("user", r.raw().length, "normalized: tool_call Result observed per tool_result block (+ permission_decision from tool_result_meta)");
            boolean first = true;
            for (Jv b : content.elements()) {
                if (!"tool_result".equals(text(b, "type"))) {
                    continue;
                }
                String id = text(b, "tool_use_id");
                Jv.Obj res = new Jv.Obj().put("data", b.member("content") == null ? Jv.NULL : b.member("content"));
                Jv isError = b.member("is_error");
                MapperSupport.Status status;
                if (isError instanceof Jv.Bool e) {
                    res.put("error_reported", e);
                    status = new MapperSupport.Status(e.value() ? 2 : 1, 1, "reported: tool_result.is_error");
                }
                else {
                    status = new MapperSupport.Status(0, 3, "is_error not reported on this tool_result block");
                }
                if (o.member("tool_use_result") != null) {
                    res.put("structured_result", o.member("tool_use_result"));
                }
                Jv.Obj ev = ctx.event("tool_call", 2, r, t, CONTRACT, "claude.stream.user.tool_result->tool_call.result", ts, uid, first, status);
                ev.put("session_uid", sess).put("tool_call_uid", id).put("tool_result", res);
                for (Jv meta : o.member("tool_result_meta") == null ? List.<Jv>of() : o.member("tool_result_meta").elements()) {
                    Jv pd = meta.member("permission_decision");
                    if (id != null && id.equals(text(meta, "id")) && pd instanceof Jv.Obj) {
                        String reported = text(pd, "decision");
                        int d = MapperSupport.decisionId(reported);
                        ev.put("permission_decision", new Jv.Obj().put("decision_id", d).put("decision", MapperSupport.DECISION.get(d)).put("reported_decision", reported)
                                .put("decision_source", text(pd, "source")).put("reason_type", text(pd, "reason_type")).put("scope_id", 1).put("scope", "Tool call")
                                .put("attribution", MapperSupport.attribution(1, 1, List.of(id), "tool_result_meta[].id == tool_result.tool_use_id", null))
                                .put("source_ref", ctx.sourceRef(r, uid, ts, false)).put("data", meta));
                    }
                }
                ev.put("data", first ? o : b);
                ctx.emit(owner, ev);
                first = false;
            }
            return;
        }
        if ("result".equals(type)) {
            ctx.family(family, r.raw().length, "normalized: prompt_turn Turn completed with run/model final usage, cost and run-scope denials");
            MapperSupport.Time t = ctx.timeFor(r, ts);
            Jv.Arr obs = new Jv.Arr();
            Jv u = o.member("usage");
            Jv.Obj runUsage = MapperSupport.usage(4, 2, "claude.stream.result_usage.v1: the result line reports run totals once", ctx.sourceRef(r, uid, ts, false), null, u);
            MapperSupport.components(runUsage, u, "input_tokens", "input_tokens", "output_tokens", "output_tokens",
                    "cache_read_input_tokens", "cache_read_tokens", "cache_creation_input_tokens", "cache_write_tokens");
            Jv thinking = u == null ? null : u.path("output_tokens_details", "thinking_tokens");
            if (MapperSupport.isIntegral(thinking)) {
                runUsage.put("reasoning_tokens", thinking);
            }
            obs.add(runUsage);
            String costBasis = null;
            Jv mu = o.member("modelUsage");
            if (mu instanceof Jv.Obj models) {
                for (var m : models.members().entrySet()) {
                    Jv.Obj mo = MapperSupport.usage(6, 2, "claude.stream.result_usage.v1: modelUsage per model on the result line", ctx.sourceRef(r, uid, ts, false),
                            MapperSupport.modelRef(m.getKey(), 1, text(m.getValue(), "provider")), m.getValue());
                    MapperSupport.components(mo, m.getValue(), "inputTokens", "input_tokens", "outputTokens", "output_tokens", "cacheReadInputTokens", "cache_read_tokens",
                            "cacheCreationInputTokens", "cache_write_tokens", "thinkingTokens", "reasoning_tokens");
                    obs.add(mo);
                    costBasis = models.members().size() == 1 ? text(m.getValue(), "costBasis") : null;
                }
            }
            boolean success = "success".equals(subtype) && o.member("is_error") instanceof Jv.Bool b && !b.value();
            Jv.Obj ev = ctx.event("prompt_turn", 2, r, t, CONTRACT, "claude.stream.result->prompt_turn.completed", ts, uid, true,
                    new MapperSupport.Status(success ? 1 : 2, 1, "reported: result.subtype and result.is_error"));
            ev.put("session_uid", sess).put("stop_reason", text(o, "stop_reason")).put("usage_observations", obs);
            if (o.member("total_cost_usd") instanceof Jv.Num cost) {
                Jv.Obj co = new Jv.Obj().put("total_cost", cost).put("currency", "USD").put("scope_id", 4).put("scope", "Run");
                if (costBasis != null) {
                    co.put("cost_basis", costBasis);
                }
                co.put("source_ref", ctx.sourceRef(r, uid, ts, false)).put("data", new Jv.Obj().put("total_cost_usd", cost));
                ev.put("cost_observation", co);
            }
            Jv.Arr pds = new Jv.Arr();
            for (Jv d : o.member("permission_denials") == null ? List.<Jv>of() : o.member("permission_denials").elements()) {
                String deniedCall = text(d, "tool_use_id");
                Jv.Obj attribution = deniedCall != null
                        ? MapperSupport.attribution(1, 1, List.of(deniedCall), "permission_denials[].tool_use_id names the denied tool call (reported identifier)", null)
                        : MapperSupport.attribution(3, 2, List.of(), "permission_denials entry names no tool call; not linked by position or text", null);
                pds.add(new Jv.Obj().put("decision_id", 2).put("decision", "Denied").put("reported_decision", "denied").put("scope_id", 4).put("scope", "Run")
                        .put("attribution", attribution).put("source_ref", ctx.sourceRef(r, uid, ts, false)).put("data", d));
            }
            if (!pds.elements().isEmpty()) {
                ev.put("permission_decisions", pds);
            }
            ev.put("data", o);
            ctx.emit(owner, ev);
            return;
        }
        if ("system".equals(type) && "init".equals(subtype)) {
            ctx.family(family, r.raw().length, "normalized: session_context Settings reported (cwd, model, permission mode, version, tools)");
            MapperSupport.Time t = ctx.timeFor(r, ts);
            Jv.Obj settings = new Jv.Obj();
            settings.put("working_directory", text(o, "cwd")).put("model", text(o, "model")).put("permission_mode", text(o, "permissionMode"))
                    .put("version", text(o, "claude_code_version"));
            if (o.member("tools") instanceof Jv.Arr tools) {
                Jv.Arr names = new Jv.Arr();
                for (Jv n : tools.elements()) {
                    if (n.text() != null) {
                        names.add(n);
                    }
                }
                settings.put("tool_names", names);
            }
            Jv.Obj ev = ctx.event("session_context", 1, r, t, CONTRACT, "claude.stream.system.init->session_context.settings", ts, uid, true, null);
            ev.put("session_uid", sess).put("context_kind", "system/init").put("settings", settings)
                    .put("basis", "claude.stream.init.v1: the init record reports the session's launch settings once").put("data", o);
            ctx.emit(owner, ev);
            return;
        }
        ctx.family(family, r.raw().length, "system/thinking_tokens".equals(family) ? "retained-only: vendor estimate of thinking tokens so far (not reported usage)"
                : "rate_limit_event".equals(family) ? "retained-only: vendor rate-limit status" : "retained-only: no normalization rule");
        ctx.emit(owner, ctx.fallback(r, CONTRACT, "claude.stream.fallback->source_record", family, ts, uid, sess, null));
    }
}
