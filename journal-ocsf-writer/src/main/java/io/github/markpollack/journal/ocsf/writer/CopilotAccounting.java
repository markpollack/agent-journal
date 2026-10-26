package io.github.markpollack.journal.ocsf.writer;

import java.util.List;
import java.util.Map;

import static io.github.markpollack.journal.ocsf.writer.MapperSupport.isIntegral;
import static io.github.markpollack.journal.ocsf.writer.MapperSupport.text;

/**
 * Usage and cost of GitHub Copilot CLI 1.0.100 as each source reports it, kept as distinct quantities: a model call's
 * token usage, its model cost multiplier (quantity kind 3) and its nano-AIU (4); the session's cumulative token usage
 * (overall and per model), premium requests (2) and nano-AIU; the CLI's outcome with the run's premium requests. A
 * multiplier is never a currency, premium requests are never derived from multipliers, and quantities of different kinds
 * are never compared or added. An absent count is absent, never 0.
 */
final class CopilotAccounting {

    static final Map<Integer, String> QUANTITY_KIND = Map.of(1, "Monetary amount", 2, "Premium requests", 3, "Model cost multiplier", 4, "Nano-AIU", 5, "Credits",
            99, "Other");
    static final List<String> SESSION_USAGE = List.of("inputTokens", "cacheReadTokens", "cacheWriteTokens", "outputTokens", "reasoningTokens");
    static final String TOKEN_DETAILS = "copilot.usage.token_details.v1: accountingSnapshot.tokenDetails reports input apart from cache reads and writes "
            + "(input 11397 + cache_read 50176 = modelMetrics inputTokens 61573 on the 1.0.100 run): input.tokenCount is the uncached tranche as reported";

    private CopilotAccounting() {
    }

    /** A quantity kind with the unit the source counts it in. */
    record Quantity(int kind, String unit) {
    }

    static final Quantity MULTIPLIER = new Quantity(3, "multiplier");
    static final Quantity MULTIPLIER_SUM = new Quantity(3, "multiplier sum");
    static final Quantity PREMIUM_REQUESTS = new Quantity(2, "premium requests");
    static final Quantity NANO_AIU = new Quantity(4, "nano-AIU");

    /**
     * One cost observation of a declared quantity; {@code member} names the native member it copies (its value is kept
     * as the observation's data). No pricing basis is reported, so none is written.
     */
    static Jv.Obj cost(Quantity q, Jv total, int scope, int aggregation, String member, Jv sourceRef) {
        int kind = q.kind();
        String unit = q.unit();
        return new Jv.Obj().put("total_cost", total).put("quantity_kind_id", kind).put("quantity_kind", QUANTITY_KIND.get(kind)).put("quantity_unit", unit)
                .put("aggregation_id", aggregation).put("aggregation", UsageTranches.AGGREGATION.get(aggregation)).put("scope_id", scope).put("scope", MapperSupport.SCOPE.get(scope))
                .put("source_ref", sourceRef).put("data", new Jv.Obj().put(member, total));
    }

    /** session.usage_record: the per-call usage, multiplier and nano-AIU of the model response of data.usage.apiCallId. */
    static void usageRecord(CopilotEventsMapper.Event e) {
        Jv usage = e.data().member("usage") == null ? new Jv.Obj() : e.data().member("usage");
        e.ctx().family(e.type(), e.r().raw().length, "normalized: model_response Completed with its per-call usage, model cost multiplier and nano-AIU (response_uid apiCallId)");
        Jv ref = e.ctx().sourceRef(e.r(), e.id(), e.ts(), false);
        Jv.Obj model = MapperSupport.modelRef(text(usage, "model"), 1, text(usage, "provider", "kind"));
        Jv.Obj ob = MapperSupport.usage(2, 2, "copilot.events.usage_record.v1: data.usage is the usage of one model call, final once recorded", ref, model, usage);
        UsageTranches.aggregation(ob, 1, "the call's own total");
        UsageTranches.copilot(ob, usage, SESSION_USAGE);
        Jv.Arr costs = new Jv.Arr();
        if (usage.member("cost") instanceof Jv.Num m) {
            costs.add(cost(MULTIPLIER, m, 2, 1, "data.usage.cost", ref));
        }
        if (usage.path("copilotUsage", "totalNanoAiu") instanceof Jv.Num n) {
            costs.add(cost(NANO_AIU, n, 2, 1, "data.usage.copilotUsage.totalNanoAiu", ref));
        }
        Jv.Obj ev = e.event("model_response", 2, "copilot.events.usage_record->model_response", true, null);
        ev.put("session_uid", e.session()).put("response_uid", text(usage, "apiCallId")).put("identity_basis_id", 99).put("identity_basis", "Provider call identifier (apiCallId)")
                .put("model_ref", model).put("usage_observations", new Jv.Arr(List.of(ob))).put("cost_observations", costs)
                .put("basis", "copilot.events.usage_record.v1: the per-call usage record of the model response data.usage.apiCallId").put("data", e.o());
        e.ctx().emit(ReportedQuantities.add(ev, e.r(), "copilot_events"));
    }

    /**
     * A session usage checkpoint (provisional: a later report may supersede it) or the shutdown's accounting snapshot
     * (final): the session's cumulative token usage overall and per model, premium requests and nano-AIU.
     */
    static void snapshot(CopilotEventsMapper.Event e, Jv snap, String rule) {
        boolean shutdown = "session.shutdown".equals(e.type());
        Jv acct = snap == null ? new Jv.Obj() : snap.member("accountingSnapshot") instanceof Jv.Obj a ? a : snap;
        e.ctx().family(e.type(), e.r().raw().length, "normalized: session_context Accounting reported (cumulative session usage, premium requests, nano-AIU; "
                + (shutdown ? "final" : "provisional") + ")");
        Jv ref = e.ctx().sourceRef(e.r(), e.id(), e.ts(), false);
        Jv.Obj ev = e.event("session_context", 3, rule, true, null);
        ev.put("session_uid", e.session()).put("usage_observations", sessionUsage(acct, shutdown ? 2 : 1, ref)).put("cost_observations", sessionCosts(acct, ref))
                .put("basis", "copilot.events.accounting_snapshot.v1: the session's cumulative accounting as of this event; a snapshot, never summed with another")
                .put("data", e.o());
        ExecutionMetrics.copilot(ev, acct, e.data().member("accountingSnapshot") instanceof Jv.Obj ? "/data/accountingSnapshot" : "/data");
        e.ctx().emit(ReportedQuantities.add(ev, e.r(), "copilot_events"));
        if (!shutdown) {
            lastCall(e, acct);
        }
    }

    /**
     * A checkpoint's last-call usage (accountingSnapshot.lastCallInputTokens and lastCallOutputTokens) as the usage of
     * the model response the same record names as the last call (promptCacheBreakState[].models[lastActiveModel]
     * .model_call_id), with that call's cache reads and writes from the same entry: the last call's full usage, which
     * standard output reports for no other call.
     */
    static void lastCall(CopilotEventsMapper.Event e, Jv acct) {
        Jv in = acct.member("lastCallInputTokens");
        Jv out = acct.member("lastCallOutputTokens");
        Jv state = e.data().member("promptCacheBreakState") instanceof Jv.Arr a && !a.elements().isEmpty() ? a.elements().get(0) : null;
        String model = text(state, "lastActiveModel");
        Jv call = model == null ? null : state.path("models", model);
        if (!isIntegral(in) || !isIntegral(out) || text(call, "model_call_id") == null) {
            return;
        }
        Jv.Obj ob = MapperSupport.usage(2, 2, "copilot.events.last_call_usage.v1: accountingSnapshot.lastCallInputTokens and lastCallOutputTokens are the usage of the "
                + "last model call, which promptCacheBreakState[0].models[lastActiveModel].model_call_id names; its cache reads and writes are that entry's; pointers are into the record",
                e.ctx().sourceRef(e.r(), e.id(), e.ts(), false), MapperSupport.modelRef(model, 1, null), null);
        UsageTranches.aggregation(ob, 1, "the call's own total");
        lastCallTranches(ob, (Jv.Num) in, (Jv.Num) out, call, "/data/promptCacheBreakState/0/models/" + model.replace("~", "~0").replace("/", "~1"));
        ob.put("data", new Jv.Obj().put("lastCallInputTokens", in).put("lastCallOutputTokens", out).put("model_call", call));
        Jv.Obj ev = e.event("model_response", 2, "copilot.events.last_call_usage->model_response", false, null);
        ev.put("session_uid", e.session()).put("response_uid", text(call, "model_call_id")).put("identity_basis_id", 99).put("identity_basis", "Provider call identifier (apiCallId)")
                .put("model_ref", MapperSupport.modelRef(model, 1, null)).put("usage_observations", new Jv.Arr(List.of(ob)))
                .put("basis", "copilot.events.last_call_usage.v1: the checkpoint's last-call usage, attributed to the call its promptCacheBreakState names");
        e.ctx().emit(ReportedQuantities.add(ev, e.r(), "copilot_events"));
    }

    /** The last call's tranches: uncached derived when cache writes are 0, cache reads and writes from its entry, output reported. */
    private static void lastCallTranches(Jv.Obj ob, Jv.Num in, Jv.Num out, Jv call, String base) {
        Jv.Arr components = new Jv.Arr();
        boolean reads = isIntegral(call.member("cache_read"));
        boolean noWrites = isIntegral(call.member("cache_write")) && ((Jv.Num) call.member("cache_write")).value().signum() == 0;
        if (reads && noWrites) {
            ob.put("uncached_input_tokens", new Jv.Num(in.value().subtract(((Jv.Num) call.member("cache_read")).value()).toBigIntegerExact().toString()));
            components.add(component("uncached_input_tokens", 2, Jv.arr("/data/accountingSnapshot/lastCallInputTokens", base + "/cache_read"), UsageTranches.COPILOT_UNCACHED));
        }
        for (String[] t : new String[][] {{"cache_read", "cache_read_tokens"}, {"cache_write", "cache_write_tokens"}}) {
            if (isIntegral(call.member(t[0]))) {
                ob.put(t[1], call.member(t[0]));
                components.add(component(t[1], 1, Jv.arr(base + "/" + t[0]), null));
            }
        }
        ob.put("output_tokens", out);
        components.add(component("output_tokens", 1, Jv.arr("/data/accountingSnapshot/lastCallOutputTokens"), null));
        ob.put("components", components);
    }

    private static Jv.Obj component(String name, int basis, Jv pointers, String derivation) {
        return new Jv.Obj().put("name", name).put("component_basis_id", basis).put("component_basis", UsageTranches.COMPONENT_BASIS.get(basis)).put("native_pointers", pointers)
                .put("derivation", derivation);
    }

    /** The session's cumulative usage: tokenDetails overall, then each model's usage under modelMetrics. */
    static Jv.Arr sessionUsage(Jv acct, int status, Jv ref) {
        Jv.Arr out = new Jv.Arr();
        Jv details = acct.member("tokenDetails");
        if (details instanceof Jv.Obj) {
            Jv.Obj ob = MapperSupport.usage(5, status, TOKEN_DETAILS, ref, null, details);
            UsageTranches.aggregation(ob, 2, "a cumulative snapshot of the session");
            tokenDetails(ob, details);
            out.add(ob);
        }
        if (acct.member("modelMetrics") instanceof Jv.Obj models) {
            for (Map.Entry<String, Jv> m : models.members().entrySet()) {
                Jv usage = m.getValue().member("usage");
                Jv.Obj ob = MapperSupport.usage(6, status, "copilot.usage.model_metrics.v1: modelMetrics is keyed by model; its usage is that model's cumulative usage in the session",
                        ref, MapperSupport.modelRef(m.getKey(), 1, providerKind(acct, m.getKey())), usage);
                UsageTranches.aggregation(ob, 2, "a cumulative snapshot of the model's usage in the session");
                UsageTranches.copilot(ob, usage, SESSION_USAGE);
                out.add(ob);
            }
        }
        return out;
    }

    private static String providerKind(Jv snapshot, String model) {
        if (!(snapshot.member("providerModelMetrics") instanceof Jv.Arr providers)) return null;
        List<Jv> matching = providers.elements().stream().filter(p -> model.equals(text(p, "modelId"))).toList();
        return matching.size() == 1 ? text(matching.get(0), "provider", "kind") : null;
    }

    private static void tokenDetails(Jv.Obj ob, Jv details) {
        Jv.Arr components = new Jv.Arr();
        for (String[] t : new String[][] {{"input", "uncached_input_tokens"}, {"cache_read", "cache_read_tokens"}, {"cache_write", "cache_write_tokens"}, {"output", "output_tokens"}}) {
            Jv v = details.path(t[0], "tokenCount");
            if (isIntegral(v)) {
                ob.put(t[1], v);
                components.add(component(t[1], 1, Jv.arr("/" + t[0] + "/tokenCount"), null));
            }
        }
        if (!components.elements().isEmpty()) {
            ob.put("components", components);
        }
        UsageTranches.dataLast(ob);
    }

    /** The session's premium requests and nano-AIU as the snapshot reports them. */
    static Jv.Arr sessionCosts(Jv acct, Jv ref) {
        Jv.Arr out = new Jv.Arr();
        if (acct.member("totalPremiumRequestCost") instanceof Jv.Num p) {
            out.add(cost(PREMIUM_REQUESTS, p, 5, 2, "accountingSnapshot.totalPremiumRequestCost", ref));
        }
        if (acct.member("totalNanoAiu") instanceof Jv.Num n) {
            out.add(cost(NANO_AIU, n, 5, 2, "accountingSnapshot.totalNanoAiu", ref));
        }
        if (acct.member("modelMetrics") instanceof Jv.Obj models) {
            models.members().forEach((model, metrics) -> {
                if (metrics.path("requests", "cost") instanceof Jv.Num amount) {
                    out.add(cost(new Quantity(99, "request cost (unit not named by the source)"), amount, 6, 2,
                            "requests.cost", ref).put("model_ref", MapperSupport.modelRef(model, 1, null)));
                }
                if (metrics.member("totalNanoAiu") instanceof Jv.Num amount) {
                    out.add(cost(NANO_AIU, amount, 6, 2, "accountingSnapshot.totalNanoAiu", ref)
                            .put("model_ref", MapperSupport.modelRef(model, 1, null)));
                }
            });
        }
        return out;
    }

    /** The result record: the CLI's outcome and exit code, the session it reports, and the run's premium requests. */
    static void result(CopilotEventsMapper.Event e) {
        e.ctx().family(e.type(), e.r().raw().length, "normalized: session_context Outcome reported (exitCode; the run's premium requests)");
        Jv ref = e.ctx().sourceRef(e.r(), null, e.ts(), false);
        Jv.Arr costs = new Jv.Arr();
        if (e.o().path("usage", "premiumRequests") instanceof Jv.Num p) {
            costs.add(cost(PREMIUM_REQUESTS, p, 4, 1, "result.usage.premiumRequests", ref));
        }
        Jv.Obj ev = e.ctx().event("session_context", 4, e.r(), e.time(), CopilotEventsMapper.CONTRACT, "copilot.events.result->session_context.outcome", e.ts(), null, true, null);
        // (the result reports its own session: Event.session is it)
        ev.put("session_uid", e.session()).put("exit_code", e.o().member("exitCode")).put("reported_state", "result").put("cost_observations", costs)
                .put("basis", "copilot.events.result.v1: the CLI's final result record: its exit code, the session it reports and the run's premium requests")
                .put("data", e.o());
        e.ctx().emit(ReportedQuantities.add(ev, e.r(), "copilot_events"));
    }
}
