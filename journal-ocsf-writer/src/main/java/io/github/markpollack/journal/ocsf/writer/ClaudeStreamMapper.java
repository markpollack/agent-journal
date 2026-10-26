package io.github.markpollack.journal.ocsf.writer;

import java.util.ArrayList;
import java.util.List;

import static io.github.markpollack.journal.ocsf.writer.MapperSupport.text;

/**
 * Claude Code {@code --output-format stream-json} live output (contract {@code claude-code-2.1.303-stream}). Each
 * record is mapped as it arrives; the only state is the artifact's time fallback. Assistant records are responses
 * (one block per record, Partial until a stop reason is reported), their tool_use blocks are requests, user records
 * with tool_result blocks are results owned by the block's own id, the system init record is the session's reported
 * settings, the result record completes the turn with run/model final usage, cost and run-scope denials, and every
 * other record is retained whole, except that child-run usage the parent's records report (an Agent result, task
 * progress and notification) is also an accounting event ({@link ClaudeChildUsage}). A record whose
 * {@code parent_tool_use_id} names a tool call is routed to that child core.
 */
final class ClaudeStreamMapper implements SourceMapper {

    static final MapperSupport.ClaudeRoute ROUTE = new MapperSupport.ClaudeRoute("claude-code-2.1.303-stream", "claude.stream", "claude.stream.usage_status.v1: stop_reason null -> provisional, non-null -> final", "claude.stream.activity.v1: stop_reason null -> Partial, non-null -> Completed", "tool_use_result");
    static final String CONTRACT = "claude-code-2.1.303-stream";
    /** Claude Code's ModelUsage accumulates over the session: consumers difference its costUSD per turn, and it is partial for a resumed session begun on an older version. */
    static final String CUMULATIVE_MODEL_USAGE = "claude.model_usage.cumulative.v1: ModelUsage is Claude Code's running total per model (consumers difference it per turn; a resumed session's earlier turns are included)";

    /** MCP facts: the registered servers and tools of the init record, and the calls established as MCP calls. */
    private final ClaudeMcp mcp = new ClaudeMcp();
    /** The Agent requests whose results report child-run usage. */
    private final ClaudeChildUsage childUsage = new ClaudeChildUsage();

    @Override
    public String contractName() {
        return CONTRACT;
    }

    @Override
    public void map(NativeRecord r, SourceContext ctx) {
        if (!r.parsed()) {
            ctx.family("opaque", r.raw().length, "retained-only: not one JSON object (bytes kept verbatim)");
            ctx.emit(ExecutionMetrics.add(ctx.opaque(r, CONTRACT), r, ctx));
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
            MapperSupport.ClaudeMessage msg = new MapperSupport.ClaudeMessage(ctx, r, t, ts, uid, sess);
            Jv.Obj ev = MapperSupport.claudeModelResponse(ROUTE, msg, m, o);
            ctx.emit(owner, ExecutionMetrics.add(ev, r, ctx));
            for (Jv b : m.member("content") == null ? List.<Jv>of() : m.member("content").elements()) {
                if ("tool_use".equals(text(b, "type"))) {
                    Jv.Obj req = MapperSupport.claudeToolRequest(ROUTE, msg, text(m, "id"), b);
                    req.put("mcp", mcp.request(b));
                    childUsage.request(b);
                    ctx.emit(owner, ExecutionMetrics.add(req, r, ctx));
                }
            }
            return;
        }
        if ("user".equals(type) && o.member("message") instanceof Jv.Obj m) {
            MapperSupport.Time t = ctx.timeFor(r, ts);
            MapperSupport.ClaudeMessage msg = new MapperSupport.ClaudeMessage(ctx, r, t, ts, uid, sess);
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
                ctx.emit(owner, ExecutionMetrics.add(ev, r, ctx));
                return;
            }
            ctx.family("user", r.raw().length, "normalized: tool_call Result observed per tool_result block (+ permission_decision from tool_result_meta; "
                    + "+ session_context Accounting reported for an Agent result's child-run usage)");
            boolean first = true;
            List<String> resultIds = new ArrayList<>();
            for (Jv b : content.elements()) {
                if (!"tool_result".equals(text(b, "type"))) {
                    continue;
                }
                String id = text(b, "tool_use_id");
                resultIds.add(id);
                Jv.Obj ev = MapperSupport.claudeToolResult(ROUTE, msg, first, o, b);
                ev.put("mcp", mcp.result(id, o, ROUTE.structuredMember()));
                ((Jv.Obj) ev.get("tool_result")).put("errors", mcp.errors(id, b));
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
                ctx.emit(owner, ExecutionMetrics.add(ev, r, ctx));
                first = false;
            }
            Jv.Obj accounting = childUsage.agentResult(msg, o, resultIds);
            if (accounting != null) {
                ctx.emit(owner, ExecutionMetrics.add(accounting, r, ctx));
            }
            return;
        }
        if ("result".equals(type)) {
            ctx.family(family, r.raw().length, "normalized: prompt_turn Turn completed with run/model final usage, cost and run-scope denials");
            MapperSupport.Time t = ctx.timeFor(r, ts);
            Jv.Arr obs = new Jv.Arr();
            Jv u = o.member("usage");
            Jv.Obj runUsage = MapperSupport.usage(4, 2, "claude.stream.result_usage.v1: the result line reports run totals once", ctx.sourceRef(r, uid, ts, false), null, u);
            // the result's usage is an Anthropic usage object; whether it covers the run's sub-agents is not established, so no aggregation is stated
            UsageTranches.anthropic(runUsage, u);
            obs.add(runUsage);
            String costBasis = null;
            Jv mu = o.member("modelUsage");
            if (mu instanceof Jv.Obj models) {
                for (var m : models.members().entrySet()) {
                    Jv.Obj mo = MapperSupport.usage(6, 2, "claude.stream.result_usage.v1: modelUsage per model on the result line", ctx.sourceRef(r, uid, ts, false),
                            MapperSupport.modelRef(m.getKey(), 1, text(m.getValue(), "provider")), m.getValue());
                    UsageTranches.aggregation(mo, 2, CUMULATIVE_MODEL_USAGE);
                    UsageTranches.claudeModelUsage(mo, m.getValue());
                    obs.add(mo);
                    costBasis = models.members().size() == 1 ? text(m.getValue(), "costBasis") : null;
                }
            }
            UsageTranches.claudeLastResponse(obs, u, ctx.sourceRef(r, uid, ts, false));
            boolean success = "success".equals(subtype) && o.member("is_error") instanceof Jv.Bool b && !b.value();
            Jv.Obj ev = ctx.event("prompt_turn", 2, r, t, CONTRACT, "claude.stream.result->prompt_turn.completed", ts, uid, true,
                    new MapperSupport.Status(success ? 1 : 2, 1, "reported: result.subtype and result.is_error"));
            ev.put("session_uid", sess).put("stop_reason", text(o, "stop_reason")).put("usage_observations", obs);
            if (o.member("result") instanceof Jv.Str) {
                ev.put("content_blocks", MapperSupport.blocks(ctx.contracts, CONTRACT, o.member("result")));
            }
            if (o.member("duration_ms") instanceof Jv.Num duration && duration.value().stripTrailingZeros().scale() <= 0) {
                // claude.stream.result.duration->prompt_turn.duration: the run's reported wall-clock milliseconds, copied
                ev.put("duration", duration);
            }
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
            ctx.emit(owner, ExecutionMetrics.add(ev, r, ctx));
            Jv.Obj modelCosts = modelCosts(ctx, r, o, sess);
            if (modelCosts != null) {
                ctx.emit(owner, ExecutionMetrics.add(modelCosts, r, ctx));
            }
            return;
        }
        if ("system".equals(type) && "init".equals(subtype)) {
            mcp.init(o);
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
            ((Jv.Obj) ev.member("settings")).put("server_connections", ExecutionState.connections(o.member("mcp_servers")));
            ctx.emit(owner, ExecutionMetrics.add(ev, r, ctx));
            return;
        }
        Jv.Obj state = "system".equals(type) ? ExecutionState.claudeTask(r, ctx) : null;
        if (state != null) {
            ctx.family(family, r.raw().length, "normalized: task state and reported prompt");
            ctx.emit(owner, state);
            return;
        }
        Jv.Obj task = "system/task_progress".equals(family) || "system/task_notification".equals(family) ? ClaudeChildUsage.task(ctx, r, family, o) : null;
        if (task != null) {
            ctx.family(family, r.raw().length, "normalized: session_context Accounting reported (the spawned task's child-run usage, scope 7)");
            ctx.emit(owner, ExecutionMetrics.add(task, r, ctx));
            return;
        }
        ctx.family(family, r.raw().length, "system/thinking_tokens".equals(family) ? "retained-only: vendor estimate of thinking tokens so far (not reported usage)"
                : "rate_limit_event".equals(family) ? "retained-only: vendor rate-limit status" : "retained-only: no normalization rule");
        ctx.emit(owner, ExecutionMetrics.add(ctx.fallback(r, CONTRACT, "claude.stream.fallback->source_record", family, ts, uid, sess, null), r, ctx));
    }

    static final String MODEL_COST_RULE = "claude.stream.result.model_cost->session_context.accounting";
    static final String MODEL_COST_BASIS = "claude.stream.model_cost.v1: ModelUsage.costUSD is Claude Code's running USD cost per model (scope 6 Model, aggregation 2 "
            + "Cumulative snapshot, the same running total the per-model usage figures are; a resumed session's earlier turns are included); with one model it equals "
            + "total_cost_usd, which stays the turn's run-scope cost observation; per-model costs are never summed with it or with each other";

    /**
     * {@link #MODEL_COST_RULE}: the result record's per-model running cost as one accounting event ({@code result/modelUsage})
     * carrying one monetary cost observation per model member that reports a numeric costUSD, or {@code null} when none
     * does. The event's data is the modelUsage object; each observation's data is the member it copies.
     */
    static Jv.Obj modelCosts(SourceContext ctx, NativeRecord r, Jv record, String sess) {
        if (!(record.member("modelUsage") instanceof Jv.Obj models)) {
            return null;
        }
        String ts = text(record, "timestamp");
        String uid = text(record, "uuid");
        MapperSupport.Time t = ctx.timeFor(r, ts);
        Jv.Arr costs = new Jv.Arr();
        for (var m : models.members().entrySet()) {
            if (m.getValue().member("costUSD") instanceof Jv.Num cost) {
                costs.add(new Jv.Obj().put("total_cost", cost).put("currency", "USD").put("quantity_kind_id", 1).put("quantity_kind", CopilotAccounting.QUANTITY_KIND.get(1))
                        .put("scope_id", 6).put("scope", MapperSupport.SCOPE.get(6)).put("aggregation_id", 2).put("aggregation", UsageTranches.AGGREGATION.get(2))
                        .put("model_ref", MapperSupport.modelRef(m.getKey(), 1, text(m.getValue(), "provider")))
                        .put("source_ref", ctx.sourceRef(r, uid, ts, false)).put("data", new Jv.Obj().put("costUSD", cost)));
            }
        }
        if (costs.elements().isEmpty()) {
            return null;
        }
        Jv.Obj ev = ctx.event("session_context", 3, r, t, CONTRACT, MODEL_COST_RULE, ts, uid, false, null);
        return ev.put("session_uid", sess).put("context_kind", "result/modelUsage").put("cost_observations", costs).put("basis", MODEL_COST_BASIS).put("data", models);
    }
}
