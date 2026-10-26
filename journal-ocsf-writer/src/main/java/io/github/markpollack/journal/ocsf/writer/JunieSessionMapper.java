package io.github.markpollack.journal.ocsf.writer;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static io.github.markpollack.journal.ocsf.writer.MapperSupport.IDENTITY_BASIS;
import static io.github.markpollack.journal.ocsf.writer.MapperSupport.text;

/**
 * Junie 3813.7's session events ({@code <session dir>/events.jsonl}, contract {@code junie-3813.7-session-events},
 * EXPERIMENTAL): structured agent events with millisecond times. They carry no session id: the summary.json of the same
 * session directory identifies them. A tool step's updates (view-files, tool and terminal blocks) are operations of
 * the step id the agent assigns (stream-local, identity basis 2) with their reported status and, for a terminal, the
 * command, output and exit code; an LLM response metadata event is one model call's usage, unidentified (no call id);
 * the result block is the task's final answer, and its completion block the task's end time and named USD total
 * ({@code taskCostUsd}, money). Status, directory, environment, tip, task-name, context-window and patch events are
 * retained whole with their meaning.
 */
final class JunieSessionMapper implements SourceMapper {

    static final String CONTRACT = "junie-3813.7-session-events";

    static final Set<String> STEP_BLOCKS = Set.of("ToolBlockUpdatedEvent", "ViewFilesBlockUpdatedEvent", "TerminalBlockUpdatedEvent");
    static final Map<String, String> RETAINED = Map.of(
            "AgentCurrentStatusUpdatedEvent", "the agent's current status changed",
            "CurrentDirectoryUpdatedEvent", "the agent's current directory",
            "EnvironmentVariablesUpdatedEvent", "the agent's environment variables",
            "TipSuggestionCreatedEvent", "a user-interface tip",
            "AgentTaskNameUpdatedEvent", "the task's generated name",
            "ContextWindowReportEvent", "the context window's use (used, size, percentage)",
            "AgentPatchCreatedEvent", "the task's patch and its statistics");

    @Override
    public String contractName() {
        return CONTRACT;
    }

    @Override
    public void map(NativeRecord r, SourceContext ctx) {
        ctx.reportSession(null);
        if (!r.parsed()) {
            ctx.family("opaque", r.raw().length, "retained-only: not one JSON object (bytes kept verbatim)");
            ctx.emit(ctx.opaque(r, CONTRACT));
            return;
        }
        Jv o = r.json();
        Jv ae = o.path("event", "agentEvent") instanceof Jv.Obj a ? a : new Jv.Obj();
        String kind = text(ae, "kind") != null ? text(ae, "kind") : text(o, "kind");
        String ts = o.member("timestampMs") instanceof Jv.Num n ? MapperSupport.msToRfc3339(n.value().longValueExact()) : null;
        Rec rec = new Rec(r, ctx, o, ae, kind, ts);
        if (Set.of("TaskStartedEvent", "AgentCurrentStatusUpdatedEvent").contains(String.valueOf(kind))) {
            reportedState(rec);
        }
        else if (STEP_BLOCKS.contains(kind)) {
            step(rec);
        }
        else if ("LlmResponseMetadataEvent".equals(kind)) {
            llmCall(rec);
        }
        else if ("ResultBlockUpdatedEvent".equals(kind)) {
            result(rec);
        }
        else {
            retained(rec);
        }
    }

    private static void reportedState(Rec e) {
        e.ctx().family(e.kind(), e.r().raw().length, "normalized: native task or agent progress state; no terminal success is inferred");
        Jv.Obj event = e.event("session_context", 5, "junie.session.state->session_context.state", true, null);
        event.put("session_uid", e.ctx().sessionFor(null)).put("context_kind", e.kind())
                .put("task_uid", e.o().member("taskId")).put("agent_uid", e.ae().path("agent", "id"))
                .put("reported_state", e.ae().member("status")).put("data", e.o());
        e.ctx().emit(ReportedQuantities.add(event, e.r(), "junie_session_events"));
    }

    private static void retained(Rec e) {
        String meaning = "TaskStartedEvent".equals(e.kind()) ? "the task started (taskId)" : RETAINED.get(e.kind());
        e.ctx().family(e.kind(), e.r().raw().length, meaning == null ? "retained-only: no normalization rule" : "retained whole: " + meaning);
        e.ctx().emit(ReportedQuantities.add(e.ctx().fallback(e.r(), CONTRACT, "junie.session.fallback->source_record", e.kind(), e.ts(), null, e.ctx().sessionFor(null),
                meaning == null ? "no normalization rule for this event; retained whole" : "junie.session.retained.v1: " + meaning + "; retained whole"), e.r(), "junie_session_events"));
    }

    /** One session event being mapped. */
    record Rec(NativeRecord r, SourceContext ctx, Jv o, Jv ae, String kind, String ts) {

        Jv.Obj event(String className, int activity, String rule, boolean whole, MapperSupport.Status status) {
            return ctx.event(className, activity, r, ctx.timeFor(r, ts), CONTRACT, rule, ts, null, whole, status);
        }
    }

    /** A tool step's update: an operation of the agent's step id with its reported status (and a terminal's command, output and exit code). */
    private void step(Rec e) {
        e.ctx().family(e.kind(), e.r().raw().length, "normalized: tool_call Operation observed (stepId; reported status; a terminal's command, output and exit code)");
        String status = text(e.ae(), "status");
        Jv.Obj op = new Jv.Obj().put("uid", text(e.ae(), "stepId")).put("identity_basis_id", 2).put("identity_basis", IDENTITY_BASIS.get(2))
                .put("command_line", text(e.ae(), "command")).put("reported_state", status);
        if (MapperSupport.isIntegral(e.ae().member("exitCode"))) {
            op.put("exit_code", e.ae().member("exitCode"));
        }
        op.put("stdout", text(e.ae(), "output"))
                .put("attribution", MapperSupport.attribution(3, 2, List.of(), "junie.session.step_attribution.v1: the step id is the operation's own; the session events report no "
                        + "model-facing tool call id that the step executes", null))
                .put("data", e.ae());
        MapperSupport.Status st = "COMPLETED".equals(status) ? new MapperSupport.Status(1, 1, "reported: status COMPLETED")
                : "FAILED".equals(status) ? new MapperSupport.Status(2, 1, "reported: status FAILED") : null;
        Jv.Obj ev = e.event("tool_call", 3, "junie.session.step_block->tool_call.operation", true, st);
        ev.put("session_uid", e.ctx().sessionFor(null)).put("tool_family", e.kind()).put("tool_operation", op).put("reported_state", status).put("data", e.o());
        e.ctx().emit(ReportedQuantities.add(ev, e.r(), "junie_session_events"));
    }

    /** An LLM response metadata event: one model call's usage and unnamed-unit cost, per model entry; the call has no id. */
    private void llmCall(Rec e) {
        e.ctx().family(e.kind(), e.r().raw().length, "normalized: model_response Completed, unidentified (no call id), with the call's usage and unnamed-unit cost");
        Jv ref = e.ctx().sourceRef(e.r(), null, e.ts(), false);
        Jv.Arr usage = new Jv.Arr();
        Jv.Arr costs = new Jv.Arr();
        for (Jv entry : e.ae().member("modelUsage") == null ? List.<Jv>of() : e.ae().member("modelUsage").elements()) {
            usage.add(JunieUsage.usage(entry, 2, 2, 1, "junie.session.llm_call.v1: an LLM response metadata event's modelUsage entry is one model call's usage", ref));
            Jv.Obj c = JunieUsage.cost(entry, 2, 1, ref);
            if (c != null) {
                costs.add(c);
            }
        }
        Jv.Obj ev = e.event("model_response", 2, "junie.session.llm_response_metadata->model_response", true, null);
        ev.put("session_uid", e.ctx().sessionFor(null)).put("usage_observations", usage).put("cost_observations", costs)
                .put("basis", "junie.session.llm_call.v1: one model call, unidentified: the event reports no call or response id").put("data", e.o());
        e.ctx().emit(ReportedQuantities.add(ev, e.r(), "junie_session_events"));
    }

    /** The result block: the task's final answer, and its completion block's end time and named USD total. */
    private void result(Rec e) {
        e.ctx().family(e.kind(), e.r().raw().length, "normalized: model_response Item completed (the task's final answer, stepId) and session_context Accounting reported "
                + "(completion.taskCostUsd, money)");
        Jv.Arr blocks = new Jv.Arr();
        blocks.add(new Jv.Obj().put("block_index", 0).put("block_type", "result").put("block_kind_id", 1).put("block_kind", "Text").put("text", text(e.ae(), "result"))
                .put("basis", "agentEvent.result is the task's final answer").put("data", e.ae().member("result")));
        Jv.Obj answer = e.event("model_response", 3, "junie.session.result_block->model_response.final_answer", true, null);
        answer.put("session_uid", e.ctx().sessionFor(null)).put("item_uid", text(e.ae(), "stepId")).put("identity_basis_id", 2).put("identity_basis", IDENTITY_BASIS.get(2))
                .put("content_blocks", blocks).put("reported_state", text(e.ae(), "errorCode"))
                .put("basis", "junie.session.result.v1: the result block is the task's final answer; its errorCode (\"Submit\") is the block's reported state").put("data", e.o());
        e.ctx().emit(ReportedQuantities.add(answer, e.r(), "junie_session_events"));
        Jv completion = e.o().member("completion");
        if (completion instanceof Jv.Obj c && c.member("taskCostUsd") instanceof Jv.Num usd) {
            Jv ref = e.ctx().sourceRef(e.r(), null, e.ts(), false);
            Jv.Obj cost = new Jv.Obj().put("total_cost", usd).put("currency", "USD").put("quantity_kind_id", 1).put("quantity_kind", CopilotAccounting.QUANTITY_KIND.get(1))
                    .put("aggregation_id", 1).put("aggregation", UsageTranches.AGGREGATION.get(1)).put("scope_id", 99).put("scope", "Task").put("source_ref", ref)
                    .put("data", new Jv.Obj().put("taskCostUsd", usd));
            Jv.Obj acct = e.event("session_context", 3, "junie.session.completion->session_context.accounting", false, null);
            acct.put("session_uid", e.ctx().sessionFor(null)).put("cost_observations", new Jv.Arr(List.of(cost)))
                    .put("basis", "junie.session.completion.v1: completion.taskCostUsd is the task's total in USD, named by the source; completion.quota is a balance, not a consumption");
            e.ctx().emit(ReportedQuantities.add(acct, e.r(), "junie_session_events"));
        }
    }
}
