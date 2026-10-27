package io.github.markpollack.journal.ocsf.writer;

import java.util.List;

/** Source-named measurements retain their native scope; no cross-observation sum is implied. */
final class ReportedQuantities {
    private ReportedQuantities() {}

    static Jv.Obj add(Jv.Obj event, NativeRecord record, String profile) {
        if (!record.parsed()
                || !(event.path("source_ref", "is_whole_record") instanceof Jv.Bool b)
                || !b.value()) return event;
        Jv.Arr metrics = event.member("execution_metrics") instanceof Jv.Arr a ? a : new Jv.Arr();
        Reading r =
                new Reading(
                        new ExecutionMetrics.Reading(
                                metrics,
                                new Jv.Obj(((Jv.Obj) event.member("source_ref")).members())
                                        .put("is_whole_record", false)),
                        record.json());
        read(r, event, profile);
        if (!metrics.elements().isEmpty()) event.put("execution_metrics", metrics);
        return event;
    }

    private static void read(Reading r, Jv.Obj event, String profile) {
        switch (profile) {
            case "copilot_events" -> copilot(r);
            case "copilot_usage_report" -> snapshot(r, "");
            case "copilot_otel" -> telemetry(r);
            case "junie_session_events" -> junie(r, event);
            default -> {}
        }
    }

    private static void copilot(Reading r) {
        switch (String.valueOf(MapperSupport.text(r.nativeRecord(), "type"))) {
            case "session.usage_record" -> {
                r.fields(
                        "/data/usage/",
                        "ms",
                        2,
                        "timeToFirstTokenMs",
                        "outputTtftMs",
                        "interTokenLatencyMs");
                r.fields("/data/usage/", "token", 2, "maxPromptTokens", "toolTokenCount");
                r.fields("/data/usage/", "byte", 2, "requestBodyBytes", "requestBodyWireBytes");
                r.fields("/data/usage/", "count", 2, "availableToolCount", "numToolCalls");
                r.members("/data/usage/toolCounts", "count", 2);
                r.fields("/data/usage/", "s", 2, "cacheTtlSeconds");
            }
            case "model.call_finished" -> r.fields("/data/", "ms", 2, "dispatchDurationMs");
            case "result" ->
                    r.fields("/usage/", "ms", 5, "sessionDurationMs", "totalApiDurationMs");
            case "session.usage_checkpoint", "session.shutdown" -> {
                snapshot(r, "/data/accountingSnapshot");
                r.fields(
                        "/data/",
                        "token",
                        5,
                        "conversationTokens",
                        "currentTokens",
                        "systemTokens",
                        "toolDefinitionsTokens");
            }
            case "tool.execution_complete" -> {
                r.fields(
                        "/data/toolTelemetry/metrics/",
                        "token",
                        99,
                        "context_current_tokens",
                        "context_token_limit",
                        "result_token_count");
                r.fields(
                        "/data/toolTelemetry/metrics/",
                        "byte",
                        99,
                        "mcp_shared_token_bytes",
                        "mcp_structured_token_bytes",
                        "mcp_text_token_bytes");
            }
            default -> {}
        }
    }

    private static void snapshot(Reading r, String p) {
        r.fields(p + "/", "count", 5, "totalUserRequests");
        r.fields(p + "/", "token", 2, "lastCallInputTokens", "lastCallOutputTokens");
    }

    private static void junie(Reading r, Jv.Obj event) {
        Jv a = r.nativeRecord().path("event", "agentEvent");
        if (a == null) return;
        event.put("reported_agent", new Jv.Obj().put("uid", a.path("agent", "id")).put("kind", a.path("agent", "kind")));
        switch (String.valueOf(MapperSupport.text(a, "kind"))) {
            case "ContextWindowReportEvent" -> {
                r.fields("/event/agentEvent/", "token", 8, "used", "size");
                r.fields("/event/agentEvent/", "percent", 8, "percentage");
            }
            case "AgentPatchCreatedEvent" -> {
                r.fields("/event/agentEvent/stats/", "line", 8, "linesAdded", "linesRemoved");
                r.fields("/event/agentEvent/stats/", "file", 8, "filesChanged");
            }
            default -> {}
        }
    }

    private static void telemetry(Reading r) {
        if (!"metric".equals(MapperSupport.text(r.nativeRecord(), "type"))) return;
        String unit = MapperSupport.text(r.nativeRecord(), "unit");
        List<Jv> points = r.nativeRecord().member("dataPoints").elements();
        for (int i = 0; i < points.size(); i++) {
            String p = "/dataPoints/" + i + "/value";
            Jv value = points.get(i).member("value");
            if (value instanceof Jv.Num) r.one(p, unit, 99);
            else {
                r.fields(p + "/", unit, 99, "sum", "min", "max");
                r.fields(p + "/", "count", 99, "count");
                r.array(p + "/buckets/boundaries", unit);
                r.array(p + "/buckets/counts", "count");
            }
        }
    }

    private record Reading(ExecutionMetrics.Reading out, Jv nativeRecord) {
        private Jv at(String pointer) {
            Jv value = nativeRecord;
            for (String part : pointer.substring(1).split("/")) {
                if (value == null) return null;
                value =
                        value instanceof Jv.Arr a
                                ? a.elements().get(Integer.parseInt(part))
                                : value.member(part.replace("~1", "/").replace("~0", "~"));
            }
            return value;
        }

        private void one(String pointer, String unit, int scope) {
            out.number(at(pointer), pointer, unit, scope, null);
            if (at(pointer) instanceof Jv.Num
                    && "metric".equals(MapperSupport.text(nativeRecord, "type"))) {
                Jv.Obj metric = (Jv.Obj) out.out().elements().get(out.out().elements().size() - 1);
                String point = pointer.substring(0, pointer.indexOf("/value"));
                Jv p = at(point);
                metric.put("metric_name", MapperSupport.text(nativeRecord, "name") + ":" + pointer)
                        .put(
                                "data",
                                new Jv.Obj()
                                        .put("metric_name", nativeRecord.member("name"))
                                        .put("unit", nativeRecord.member("unit"))
                                        .put("attributes", p.member("attributes"))
                                        .put("startTime", p.member("startTime"))
                                        .put("endTime", p.member("endTime")));
            }
        }

        private void fields(String p, String unit, int scope, String... names) {
            for (String name : names) one(p + name, unit, scope);
        }

        private void members(String p, String unit, int scope) {
            if (at(p) instanceof Jv.Obj object)
                object.members()
                        .keySet()
                        .forEach(
                                k ->
                                        one(
                                                p + "/" + k.replace("~", "~0").replace("/", "~1"),
                                                unit,
                                                scope));
        }

        private void array(String p, String unit) {
            if (at(p) instanceof Jv.Arr a)
                for (int i = 0; i < a.elements().size(); i++) one(p + "/" + i, unit, 99);
        }
    }
}
