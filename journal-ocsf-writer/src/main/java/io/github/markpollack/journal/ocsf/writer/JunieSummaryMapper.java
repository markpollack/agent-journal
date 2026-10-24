package io.github.markpollack.journal.ocsf.writer;

import static io.github.markpollack.journal.ocsf.writer.MapperSupport.text;

/**
 * Junie 3813.7's session summary ({@code <session dir>/summary.json}, contract {@code junie-3813.7-summary}, EXPERIMENTAL):
 * one JSON document naming the session ({@code sessionId}), its project directory, task name and lifecycle, and the main
 * agent's usage per model. It identifies the session directory: the session events beside it carry no session id. The
 * document's lifecycle is the session's reported state; its per-model costs are a quantity whose unit it does not name.
 */
final class JunieSummaryMapper implements SourceMapper {

    static final String CONTRACT = "junie-3813.7-summary";

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
        Jv s = r.json();
        String session = text(s, "sessionId");
        ctx.reportSession(session);
        String updated = s.member("updatedAt") instanceof Jv.Num n ? MapperSupport.msToRfc3339(n.value().longValueExact()) : null;
        ctx.family("summary", r.raw().length, "normalized: session_context Settings reported (project directory), Outcome reported (lifecycle) and Accounting reported (usage and "
                + "unnamed-unit cost per model)");
        Jv.Obj settings = ctx.event("session_context", 1, r, ctx.timeFor(r, updated), CONTRACT, "junie.summary->session_context.settings", updated, session, true, null);
        settings.put("session_uid", ctx.sessionFor(session)).put("settings", McpObservations.members("working_directory", s.member("projectDir")))
                .put("basis", "junie.summary.settings.v1: the session's project directory; the task name and times stay in the record").put("data", s);
        ctx.emit(settings);
        Jv.Obj outcome = ctx.event("session_context", 4, r, ctx.timeFor(r, updated), CONTRACT, "junie.summary->session_context.outcome", updated, session, false, null);
        outcome.put("session_uid", ctx.sessionFor(session)).put("reported_state", text(s, "lifecycle"))
                .put("basis", "junie.summary.outcome.v1: lifecycle is the session's reported state (no exit code is reported)");
        ctx.emit(outcome);
        Jv ref = ctx.sourceRef(r, session, updated, false);
        Jv.Arr usage = new Jv.Arr();
        Jv.Arr costs = new Jv.Arr();
        JunieUsage.perModel(s.path("mainAgent", "modelUsage"), 2, "junie.summary.model_usage.v1: mainAgent.modelUsage is the main agent's usage per model for the session", ref,
                usage, costs);
        Jv.Obj accounting = ctx.event("session_context", 3, r, ctx.timeFor(r, updated), CONTRACT, "junie.summary->session_context.accounting", updated, session, false, null);
        accounting.put("session_uid", ctx.sessionFor(session)).put("usage_observations", usage).put("cost_observations", costs)
                .put("basis", "junie.summary.accounting.v1: the session's per-model usage and unnamed-unit costs (junie.usage.unnamed_cost.v1)");
        ctx.emit(accounting);
    }
}
