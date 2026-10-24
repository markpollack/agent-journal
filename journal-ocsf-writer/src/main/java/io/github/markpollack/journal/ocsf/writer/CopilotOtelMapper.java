package io.github.markpollack.journal.ocsf.writer;

import java.util.List;

import static io.github.markpollack.journal.ocsf.writer.MapperSupport.IDENTITY_BASIS;
import static io.github.markpollack.journal.ocsf.writer.MapperSupport.TOOL_KIND;
import static io.github.markpollack.journal.ocsf.writer.MapperSupport.text;

/**
 * GitHub Copilot CLI 1.0.100's native OTel file exporter ({@code COPILOT_OTEL_FILE_EXPORTER_PATH}, contract
 * {@code github-copilot-cli-1.0.100-otel}, EXPERIMENTAL): one JSON object per line, a finished span or a metric. A
 * {@code chat} span is one model call (its response id is the provider call id, its usage and cost the call's own); an
 * {@code execute_tool} span is one tool execution with its model-facing call id, arguments, result text and status; the
 * {@code invoke_agent} span is the whole agent invocation with its totals, including {@code github.copilot.cost}, the sum
 * of the run's per-call model cost multipliers (never premium requests), and the tool definitions and MCP servers it had.
 * Span times are the exporter's {@code [seconds, nanoseconds]} pairs; the event time is the span end at millisecond
 * precision (the pair stays in the record). Metrics are run aggregates and are retained whole. Content capture settings
 * decide whether message, system and tool content attributes are present: an absent attribute is absent, never empty.
 * The session is {@code gen_ai.conversation.id}; a metric reports none.
 */
final class CopilotOtelMapper implements SourceMapper {

    static final String CONTRACT = "github-copilot-cli-1.0.100-otel";
    static final List<String> SPAN_USAGE = List.of("gen_ai.usage.input_tokens", "gen_ai.usage.cache_read.input_tokens", "gen_ai.usage.cache_write.input_tokens",
            "gen_ai.usage.output_tokens", "gen_ai.usage.reasoning.output_tokens");

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
        Jv attrs = o.member("attributes") == null ? new Jv.Obj() : o.member("attributes");
        ctx.reportSession(text(attrs, "gen_ai.conversation.id"));
        String op = "span".equals(text(o, "type")) ? text(attrs, "gen_ai.operation.name") : null;
        Span s = new Span(r, ctx, o, attrs, end(o));
        java.util.function.Consumer<Span> handler = op == null ? null : SPANS.get(op);
        if (handler == null) {
            retained(s, op);
        }
        else {
            handler.accept(s);
        }
    }

    /** The handler of each normalized span operation. */
    private static final java.util.Map<String, java.util.function.Consumer<Span>> SPANS = java.util.Map.of("chat", CopilotOtelMapper::chat, "execute_tool", CopilotOtelMapper::tool,
            "invoke_agent", CopilotOtelMapper::agent);

    /** A metric (a run aggregate) or a span of another operation: retained whole. */
    private static void retained(Span s, String op) {
        boolean span = "span".equals(text(s.o(), "type"));
        String family = span ? "span/" + op : "metric/" + text(s.o(), "name");
        s.ctx().family(family, s.r().raw().length, span ? "retained-only: no normalization rule for this span operation"
                : "retained whole: a run aggregate metric (data points by attribute set); not a per-call fact");
        s.ctx().emit(s.ctx().fallback(s.r(), CONTRACT, "copilot.otel.fallback->source_record", family, s.end(), null, s.session(),
                "copilot.otel.retained.v1: " + (span ? "no rule for this span operation; retained whole" : "an exporter metric (a run aggregate); retained whole")));
    }

    /** One span being mapped, with its end time as RFC 3339 (millisecond precision), or {@code null}. */
    record Span(NativeRecord r, SourceContext ctx, Jv o, Jv attrs, String end) {

        Jv.Obj event(String className, int activity, String rule, String nativeUid, MapperSupport.Status status) {
            return event(className, activity, rule, nativeUid, status, true);
        }

        Jv.Obj event(String className, int activity, String rule, String nativeUid, MapperSupport.Status status, boolean whole) {
            return ctx.event(className, activity, r, ctx.timeFor(r, end), CONTRACT, rule, end, nativeUid, whole, status);
        }

        String session() {
            return ctx.sessionFor(text(attrs, "gen_ai.conversation.id"));
        }

        Jv attr(String name) {
            return attrs.member(name);
        }
    }

    private static String end(Jv o) {
        Jv end = o.member("endTime");
        if (end instanceof Jv.Arr a && a.elements().size() == 2 && a.elements().get(0) instanceof Jv.Num sec && a.elements().get(1) instanceof Jv.Num nanos) {
            return MapperSupport.msToRfc3339(sec.value().longValueExact() * 1000 + nanos.value().longValueExact() / 1_000_000);
        }
        return null;
    }

    /** A chat span: the model call's response, usage, model cost multiplier and nano-AIU. */
    private static void chat(Span s) {
        s.ctx().family("span/chat", s.r().raw().length, "normalized: model_response Completed with the call's usage, model cost multiplier and nano-AIU (response_uid gen_ai.response.id)");
        Jv ref = s.ctx().sourceRef(s.r(), text(s.o(), "spanId"), s.end(), false);
        // gen_ai.provider.name ("github") names the GenAI system, a different namespace from the session's provider kind: kept in data
        Jv.Obj model = MapperSupport.modelRef(text(s.attrs(), "gen_ai.response.model"), 1, null);
        Jv.Obj ob = MapperSupport.usage(2, 2, "copilot.otel.chat_usage.v1: a chat span's gen_ai.usage attributes are the call's own usage", ref, model, s.attrs());
        UsageTranches.aggregation(ob, 1, "the call's own total");
        UsageTranches.copilot(ob, s.attrs(), SPAN_USAGE);
        ob.put("data", usageAttributes(s.attrs()));
        Jv.Arr costs = new Jv.Arr();
        if (s.attr("github.copilot.cost") instanceof Jv.Num m) {
            costs.add(CopilotAccounting.cost(CopilotAccounting.MULTIPLIER, m, 2, 1, "github.copilot.cost", ref));
        }
        if (s.attr("github.copilot.nano_aiu") instanceof Jv.Num n) {
            costs.add(CopilotAccounting.cost(CopilotAccounting.NANO_AIU, n, 2, 1, "github.copilot.nano_aiu", ref));
        }
        Jv.Obj ev = s.event("model_response", 2, "copilot.otel.chat->model_response", text(s.o(), "spanId"), null);
        ev.put("session_uid", s.session()).put("turn_uid", text(s.attrs(), "github.copilot.interaction_id")).put("response_uid", text(s.attrs(), "gen_ai.response.id"))
                .put("identity_basis_id", 99).put("identity_basis", "Provider call identifier (apiCallId)").put("model_ref", model)
                .put("usage_observations", new Jv.Arr(List.of(ob))).put("cost_observations", costs)
                .put("basis", "copilot.otel.chat.v1: a chat span is one model call; gen_ai.response.id is its provider call id").put("data", s.o());
        s.ctx().emit(ev);
    }

    /** An execute_tool span: the tool execution's call id, arguments (a JSON string, parsed), result text and status. */
    private static void tool(Span s) {
        s.ctx().family("span/execute_tool", s.r().raw().length, "normalized: tool_call Result observed (gen_ai.tool.call.id; arguments parsed from their JSON string)");
        String name = text(s.attrs(), "gen_ai.tool.name");
        String server = text(s.attrs(), "github.copilot.tool.parameters.mcp_server_name");
        MappingContracts.Kind kind = s.ctx().contracts.familyKind(CopilotEventsMapper.CONTRACT, server != null ? "mcp" : name, server,
                text(s.attrs(), "github.copilot.tool.parameters.mcp_tool_name"));
        int code = s.o().path("status", "code") instanceof Jv.Num c ? c.value().intValue() : 0;
        // an UNSET status (0) reports nothing about success: no status is stated (absent, not Unknown)
        MapperSupport.Status status = code == 2 ? new MapperSupport.Status(2, 1, "reported: span status code 2 (ERROR)") : null;
        Jv.Obj result = new Jv.Obj().put("data", s.attr("gen_ai.tool.call.result") == null ? Jv.Null.INSTANCE : s.attr("gen_ai.tool.call.result"));
        if (code == 2) {
            result.put("error_reported", true).put("errors", McpObservations.errors(McpObservations.error(3, null, text(s.o(), "status", "message"), s.o().member("status"),
                    "copilot.otel.tool_error.v1: span status ERROR with its message as the CLI reports the tool failure; error.type (an exception class) stays in data")));
        }
        Jv.Obj ev = s.event("tool_call", 2, "copilot.otel.execute_tool->tool_call.result", text(s.o(), "spanId"), status);
        ev.put("session_uid", s.session()).put("tool_call_uid", text(s.attrs(), "gen_ai.tool.call.id")).put("identity_basis_id", 1).put("identity_basis", IDENTITY_BASIS.get(1))
                .put("tool_name", name).put("tool_family", text(s.attrs(), "gen_ai.tool.type")).put("tool_kind_id", kind.id()).put("tool_kind", TOOL_KIND.get(kind.id()))
                .put("tool_kind_basis", kind.rule() + "; copilot.otel.execute_tool.v1: one tool execution of the model-facing call gen_ai.tool.call.id; "
                        + (code == 2 ? "status ERROR is its failure" : "an UNSET status reports no success or failure"))
                .put("arguments", parsed(s.attr("gen_ai.tool.call.arguments"))).put("tool_result", result)
                .put("mcp", server == null ? null : McpObservations.target(server, McpObservations.REPORTED_MEMBERS, text(s.attrs(), "github.copilot.tool.parameters.mcp_tool_name"),
                        McpObservations.REPORTED_MEMBERS, null, "copilot.otel.mcp.v1: the span's github.copilot.tool.parameters.mcp_server_name and mcp_tool_name; the span reports the result as text only, so no protocol object is projected"))
                .put("data", s.o());
        s.ctx().emit(ev);
    }

    /** The span's gen_ai.usage attributes, as the observation's native data. */
    private static Jv.Obj usageAttributes(Jv attrs) {
        Jv.Obj out = new Jv.Obj();
        ((Jv.Obj) attrs).members().forEach((k, v) -> {
            if (k.startsWith("gen_ai.usage.")) {
                out.put(k, v);
            }
        });
        return out;
    }

    /** A JSON-string attribute parsed; the string itself when it is not JSON. */
    static Jv parsed(Jv v) {
        if (!(v instanceof Jv.Str s)) {
            return v;
        }
        try {
            return Jv.parse(s.value());
        }
        catch (IllegalArgumentException e) {
            return v;
        }
    }

    /**
     * The invoke_agent span: the invocation's settings (tool definitions, MCP servers) and its totals: token usage, the sum
     * of its per-call model cost multipliers and its nano-AIU, at run scope.
     */
    private static void agent(Span s) {
        s.ctx().family("span/invoke_agent", s.r().raw().length, "normalized: session_context Settings reported (tool definitions, MCP servers) and Accounting reported (run totals; multiplier sum)");
        Jv.Obj settings = s.event("session_context", 1, "copilot.otel.invoke_agent->session_context.settings", text(s.o(), "spanId"), null);
        Jv.Arr definitions = CopilotDefinitions.of(s.attr("gen_ai.tool.definitions"));
        Jv.Arr names = new Jv.Arr();
        if (definitions != null) {
            definitions.elements().forEach(d -> names.add(d.member("name")));
        }
        settings.put("session_uid", s.session()).put("settings", definitions == null ? null : new Jv.Obj().put("tool_names", names)).put("tool_definitions", definitions)
                .put("basis", "copilot.otel.invoke_agent_settings.v1: the agent invocation's tool definitions (gen_ai.tool.definitions, present under content capture) and MCP servers")
                .put("data", s.o());
        s.ctx().emit(settings);
        Jv ref = s.ctx().sourceRef(s.r(), text(s.o(), "spanId"), s.end(), false);
        Jv.Obj ob = MapperSupport.usage(4, 2, "copilot.otel.invoke_agent_usage.v1: the invocation's total usage; input includes cache reads, which the span does not report, so "
                + "no uncached tranche is derived", ref, null, s.attrs());
        UsageTranches.aggregation(ob, 1, "the invocation's total");
        UsageTranches.copilot(ob, s.attrs(), SPAN_USAGE);
        ob.put("data", usageAttributes(s.attrs()));
        Jv.Arr costs = new Jv.Arr();
        if (s.attr("github.copilot.cost") instanceof Jv.Num m) {
            costs.add(CopilotAccounting.cost(CopilotAccounting.MULTIPLIER_SUM, m, 4, 1, "github.copilot.cost", ref));
        }
        if (s.attr("github.copilot.nano_aiu") instanceof Jv.Num n) {
            costs.add(CopilotAccounting.cost(CopilotAccounting.NANO_AIU, n, 4, 1, "github.copilot.nano_aiu", ref));
        }
        Jv.Obj acct = s.event("session_context", 3, "copilot.otel.invoke_agent->session_context.accounting", text(s.o(), "spanId"), null, false);
        acct.put("session_uid", s.session()).put("usage_observations", new Jv.Arr(List.of(ob))).put("cost_observations", costs)
                .put("basis", "copilot.otel.invoke_agent_accounting.v1: the agent invocation's totals (the whole span is retained on its settings event)");
        s.ctx().emit(acct);
    }
}
