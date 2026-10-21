package io.github.markpollack.journal.ocsf.writer;

import java.util.List;

import static io.github.markpollack.journal.ocsf.writer.MapperSupport.text;

/**
 * Claude Code saved session file (contract {@code claude-code-2.1.303-transcript}), complete admission: every record
 * is mapped or retained whole; no response filter. Assistant records are responses (Completed when the record
 * reports a stop reason; the transcript writes the message's final usage on every block record), tool_use blocks
 * are requests, user records with a bare-string content are prompt submissions, user records with tool_result
 * blocks are results (with the record-level permission decision when the record carries exactly one result),
 * attachments are injected context, cost-state is session accounting, and the remaining families (queue-operation,
 * atis-latch, api-request-shape, api-request-blob, api-request, last-prompt) are retained whole with their meaning
 * stated in the family inventory.
 */
final class ClaudeTranscriptMapper implements SourceMapper {

    static final MapperSupport.ClaudeRoute ROUTE = new MapperSupport.ClaudeRoute("claude-code-2.1.303-transcript", "claude.transcript", "claude.transcript.usage_status.v1: stop_reason non-null -> final (the transcript repeats the message's usage on each block record)", "claude.transcript.activity.v1: stop_reason non-null -> Completed", "toolUseResult");
    static final String CONTRACT = "claude-code-2.1.303-transcript";

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
        String ts = text(o, "timestamp");
        String uid = text(o, "uuid");
        String sess = text(o, "sessionId");
        Jv.Obj m = o.member("message") instanceof Jv.Obj mm ? mm : null;
        if ("assistant".equals(type) && m != null) {
            ctx.family(type, r.raw().length, "normalized: model_response (+ tool_call Requested per tool_use block); every message id admitted");
            MapperSupport.Time t = ctx.timeFor(r, ts);
            MapperSupport.ClaudeMessage msg = new MapperSupport.ClaudeMessage(ctx, r, t, ts, uid, sess);
            Jv.Obj ev = MapperSupport.claudeModelResponse(ROUTE, msg, m, o);
            ctx.emit(ev);
            for (Jv b : m.member("content") == null ? List.<Jv>of() : m.member("content").elements()) {
                if ("tool_use".equals(text(b, "type"))) {
                    Jv.Obj req = MapperSupport.claudeToolRequest(ROUTE, msg, text(m, "id"), b);
                    ctx.emit(req);
                }
            }
            return;
        }
        if ("user".equals(type) && m != null) {
            MapperSupport.Time t = ctx.timeFor(r, ts);
            MapperSupport.ClaudeMessage msg = new MapperSupport.ClaudeMessage(ctx, r, t, ts, uid, sess);
            Jv content = m.member("content");
            int resultBlocks = 0;
            for (Jv b : content == null ? List.<Jv>of() : content.elements()) {
                if ("tool_result".equals(text(b, "type"))) {
                    resultBlocks++;
                }
            }
            if (resultBlocks == 0) {
                ctx.family(type, r.raw().length, "normalized: prompt_turn Prompt submitted (user record without tool_result)");
                Jv.Obj ev = ctx.event("prompt_turn", 1, r, t, CONTRACT, "claude.transcript.user.prompt->prompt_turn.submitted", ts, uid, true, null);
                ev.put("session_uid", sess).put("turn_uid", text(o, "promptId")).put("content_blocks", MapperSupport.blocks(ctx.contracts, CONTRACT, content)).put("data", o);
                ctx.emit(ev);
                return;
            }
            ctx.family(type, r.raw().length, "normalized: tool_call Result observed per tool_result block (+ record-level permissionDecision when exactly one result)");
            boolean first = true;
            for (Jv b : content.elements()) {
                if (!"tool_result".equals(text(b, "type"))) {
                    continue;
                }
                String id = text(b, "tool_use_id");
                Jv.Obj ev = MapperSupport.claudeToolResult(ROUTE, msg, first, o, b);
                Jv pd = o.member("permissionDecision");
                if (pd instanceof Jv.Obj && resultBlocks == 1) {
                    String reported = text(pd, "decision");
                    int d = MapperSupport.decisionId(reported);
                    ev.put("permission_decision", new Jv.Obj().put("decision_id", d).put("decision", MapperSupport.DECISION.get(d)).put("reported_decision", reported)
                            .put("decision_source", text(pd, "source")).put("reason_type", text(pd, "reasonType")).put("scope_id", 1).put("scope", "Tool call")
                            .put("attribution", MapperSupport.attribution(1, 1, List.of(id), "record-level permissionDecision on a record with exactly one tool_result block", null))
                            .put("source_ref", ctx.sourceRef(r, uid, ts, false)).put("data", pd));
                }
                ev.put("data", first ? o : b);
                ctx.emit(ev);
                first = false;
            }
            return;
        }
        if ("attachment".equals(type) && o.member("attachment") instanceof Jv.Obj att) {
            String kind = text(att, "type");
            ctx.family("attachment/" + kind, r.raw().length, "normalized: session_context Context injected (attachment." + kind + ")");
            MapperSupport.Time t = ctx.timeFor(r, ts);
            Jv.Obj ev = ctx.event("session_context", 2, r, t, CONTRACT, "claude.transcript.attachment->session_context.context", ts, uid, true, null);
            ev.put("session_uid", sess).put("context_kind", "attachment/" + kind);
            if (text(o, "renderedRole") != null) {
                ev.put("rendered_role", text(o, "renderedRole"));
            }
            if (o.member("rendered") instanceof Jv.Arr rendered) {
                ev.put("content_blocks", MapperSupport.renderedBlocks(rendered));
            }
            ev.put("basis", "claude.transcript.attachment.v1: an attachment record is context the CLI injected into the model input; rendered[] is the text as rendered, when present").put("data", o);
            ctx.emit(ev);
            return;
        }
        if ("cost-state".equals(type)) {
            ctx.family(type, r.raw().length, "normalized: session_context Accounting reported (session-scope per-model usage and cost total)");
            MapperSupport.Time t = ctx.timeFor(r, ts);
            Jv.Obj ev = ctx.event("session_context", 3, r, t, CONTRACT, "claude.transcript.cost_state->session_context.accounting", ts, uid, true, null);
            Jv.Arr obs = new Jv.Arr();
            if (o.member("modelUsage") instanceof Jv.Obj models) {
                for (var e : models.members().entrySet()) {
                    Jv.Obj mo = MapperSupport.usage(5, 2, "claude.transcript.cost_state.v1: cost-state is the session's cumulative per-model usage, written after the turn",
                            ctx.sourceRef(r, uid, ts, false), MapperSupport.modelRef(e.getKey(), 3, null), e.getValue());
                    MapperSupport.components(mo, e.getValue(), "inputTokens", "input_tokens", "outputTokens", "output_tokens", "cacheReadInputTokens", "cache_read_tokens",
                            "cacheCreationInputTokens", "cache_write_tokens", "thinkingTokens", "reasoning_tokens");
                    obs.add(mo);
                }
            }
            ev.put("session_uid", sess).put("context_kind", "cost-state").put("usage_observations", obs);
            if (o.member("totalCostUSD") instanceof Jv.Num cost) {
                ev.put("cost_observation", new Jv.Obj().put("total_cost", cost).put("currency", "USD").put("scope_id", 5).put("scope", "Session")
                        .put("source_ref", ctx.sourceRef(r, uid, ts, false)).put("data", new Jv.Obj().put("totalCostUSD", cost)));
            }
            ev.put("basis", "claude.transcript.cost_state.v1").put("data", o);
            ctx.emit(ev);
            return;
        }
        String disposition = switch (type == null ? "" : type) {
            case "queue-operation" -> "retained-only: prompt queue enqueue/dequeue (meaning known; the prompt text is a repeat representation of the user record)";
            case "atis-latch" -> "retained-only: vendor internal latch (meaning unresolved)";
            case "api-request-shape" -> "retained-only: the API request shape (system prompt and tool definitions) sent to the model (meaning known; not a submission)";
            case "api-request-blob" -> "retained-only: the API request message blobs (what the model saw), repeat representations of records already normalized (meaning known; not counted as submissions or responses)";
            case "api-request" -> "retained-only: API request bookkeeping (model, betas, cache control, digests) (meaning known)";
            case "last-prompt" -> "retained-only: last prompt pointer (leafUuid) (meaning known; a repeat representation of the user record)";
            default -> "retained-only: no normalization rule (meaning unresolved)";
        };
        ctx.family(type == null ? "(untyped)" : type, r.raw().length, disposition);
        ctx.emit(ctx.fallback(r, CONTRACT, "claude.transcript.fallback->source_record", type == null ? "(untyped)" : type, ts, uid, sess, disposition));
    }
}
