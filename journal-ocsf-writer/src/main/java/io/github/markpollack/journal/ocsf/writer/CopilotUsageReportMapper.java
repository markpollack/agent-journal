package io.github.markpollack.journal.ocsf.writer;

/**
 * GitHub Copilot CLI 1.0.100's usage report ({@code --usage-output-file}, contract
 * {@code github-copilot-cli-1.0.100-usage-report}, EXPERIMENTAL): one JSON document the CLI writes at exit with the
 * session's accounting: premium requests, nano-AIU, token details overall and per model (and per agent), code changes
 * and timings. It names no session; it belongs to the execution whose launch named the file (its acquisition).
 */
final class CopilotUsageReportMapper implements SourceMapper {

    static final String CONTRACT = "github-copilot-cli-1.0.100-usage-report";

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
        Jv report = r.json();
        ctx.reportSession(null);
        ctx.family("usage-report", r.raw().length, "normalized: session_context Accounting reported (the session's final accounting at exit: premium requests, nano-AIU, "
                + "token details overall and per model)");
        Jv ref = ctx.sourceRef(r, null, null, false);
        Jv.Obj ev = ctx.event("session_context", 3, r, ctx.timeFor(r, null), CONTRACT, "copilot.usage_report->session_context.accounting", null, null, true, null);
        ev.put("session_uid", ctx.sessionFor(null)).put("usage_observations", CopilotAccounting.sessionUsage(report, 2, ref)).put("cost_observations", CopilotAccounting.sessionCosts(report, ref))
                .put("basis", "copilot.usage_report.v1: the usage report the CLI wrote at exit for the launch that named it; a final cumulative snapshot of the session")
                .put("data", report);
        ctx.emit(ev);
    }
}
