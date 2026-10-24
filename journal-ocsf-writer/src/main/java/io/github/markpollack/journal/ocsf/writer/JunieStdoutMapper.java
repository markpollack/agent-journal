package io.github.markpollack.journal.ocsf.writer;

import static io.github.markpollack.journal.ocsf.writer.MapperSupport.text;

/**
 * Junie 3813.7's {@code --output-format json-stream} standard output (contract {@code junie-3813.7-stdout},
 * EXPERIMENTAL): a session record naming the session, step summaries without ids, and a result with the final answer
 * and, under a member named {@code errorCode}, the per-model usage (calls, unnamed-unit cost, token counts). Steps are
 * retained whole: they carry no id, so they are never joined with the session file's steps and never counted as
 * operations again.
 */
final class JunieStdoutMapper implements SourceMapper {

    static final String CONTRACT = "junie-3813.7-stdout";

    private String session;

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
        String type = text(o, "type");
        String ts = o.member("timestamp") instanceof Jv.Num n ? MapperSupport.msToRfc3339(n.value().longValueExact()) : null;
        if ("session".equals(type)) {
            session = text(o, "sessionId");
            ctx.reportSession(session);
            ctx.family(type, r.raw().length, "normalized: session_context Settings reported (the session the run reports)");
            Jv.Obj ev = ctx.event("session_context", 1, r, ctx.timeFor(r, ts), CONTRACT, "junie.stdout.session->session_context.settings", ts, null, true, null);
            ctx.emit(ev.put("session_uid", ctx.sessionFor(session)).put("basis", "junie.stdout.session.v1: the run's session id").put("data", o));
            return;
        }
        if ("result".equals(type)) {
            result(r, ctx, o, ts);
            return;
        }
        ctx.family(type, r.raw().length, "step".equals(type) ? "retained whole: a step summary without an id (never joined, never counted as an operation)" : "retained-only: no normalization rule");
        ctx.emit(ctx.fallback(r, CONTRACT, "junie.stdout.fallback->source_record", type, ts, null, ctx.sessionFor(session),
                "step".equals(type) ? "junie.stdout.step.v1: a step summary (name, details or output) the stream reports without an id; retained whole" : null));
    }

    /** The result: the final answer, and the per-model usage its errorCode member carries. */
    private void result(NativeRecord r, SourceContext ctx, Jv o, String ts) {
        ctx.family("result", r.raw().length, "normalized: model_response Item completed (the final answer) and session_context Accounting reported (per-model usage and calls under errorCode)");
        Jv.Arr blocks = new Jv.Arr();
        blocks.add(new Jv.Obj().put("block_index", 0).put("block_type", "result").put("block_kind_id", 1).put("block_kind", "Text").put("text", text(o, "result"))
                .put("basis", "result is the task's final answer").put("data", o.member("result")));
        Jv.Obj answer = ctx.event("model_response", 3, r, ctx.timeFor(r, ts), CONTRACT, "junie.stdout.result->model_response.final_answer", ts, null, true, null);
        ctx.emit(answer.put("session_uid", ctx.sessionFor(session)).put("content_blocks", blocks)
                .put("basis", "junie.stdout.result.v1: the result record's final answer; it reports no step id").put("data", o));
        Jv ref = ctx.sourceRef(r, null, ts, false);
        Jv.Arr usage = new Jv.Arr();
        Jv.Arr costs = new Jv.Arr();
        JunieUsage.perModel(o.member("errorCode") instanceof Jv.Arr a ? a : null, 2,
                "junie.stdout.error_code_usage.v1: despite its name, the result's errorCode member is a list of per-model usage entries (model, calls, cost, token counts) "
                        + "for the task; no error is reported there", ref, usage, costs);
        Jv.Obj acct = ctx.event("session_context", 3, r, ctx.timeFor(r, ts), CONTRACT, "junie.stdout.result->session_context.accounting", ts, null, false, null);
        ctx.emit(acct.put("session_uid", ctx.sessionFor(session)).put("usage_observations", usage).put("cost_observations", costs)
                .put("basis", "junie.stdout.error_code_usage.v1: the task's per-model usage and unnamed-unit costs as the result's errorCode member reports them"));
    }
}
