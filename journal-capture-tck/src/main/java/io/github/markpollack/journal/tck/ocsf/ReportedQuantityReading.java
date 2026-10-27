package io.github.markpollack.journal.tck.ocsf;

import io.github.markpollack.journal.tck.verify.Json;

import java.util.List;
import java.util.Map;

/** Independent, version-bound reading of reported counters, timings and exporter statistics. */
final class ReportedQuantityReading {
    private ReportedQuantityReading() {}

    static void read(ExpectedFacts facts, NativeArtifact artifact, String profile) {
        for (NativeArtifact.Record record : artifact.records()) {
            if (!record.parsed() || !facts.owned.contains(record.index())) continue;
            Json n = record.json();
            switch (profile) {
                case "copilot_events" -> copilot(facts, record.index(), n);
                case "copilot_usage_report" ->
                        snapshot(
                                new Reading(
                                        new SourceFacts.Target(
                                                facts, record.index(), "session_context", 3),
                                        n),
                                "");
                case "copilot_otel" -> telemetry(facts, record.index(), n);
                case "junie_session_events" -> junie(facts, record.index(), n);
                default -> {}
            }
        }
    }

    private static void copilot(ExpectedFacts f, int record, Json n) {
        String type = String.valueOf(SourceProfile.text(n, "type"));
        SourceFacts.Target target = copilotTarget(f, record, type);
        Reading r = new Reading(target, n);
        switch (type) {
            case "session.usage_record" -> {
                r.fields(
                        "/data/usage",
                        "ms",
                        2,
                        List.of("interTokenLatencyMs", "outputTtftMs", "timeToFirstTokenMs"));
                r.fields("/data/usage", "token", 2, List.of("toolTokenCount", "maxPromptTokens"));
                r.fields("/data/usage", "count", 2, List.of("numToolCalls", "availableToolCount"));
                r.fields(
                        "/data/usage",
                        "byte",
                        2,
                        List.of("requestBodyWireBytes", "requestBodyBytes"));
                r.fields("/data/usage", "s", 2, List.of("cacheTtlSeconds"));
                Json counts = n.path("data", "usage", "toolCounts");
                if (counts instanceof Json.JObject o)
                    o.members()
                            .keySet()
                            .forEach(
                                    k ->
                                            r.one(
                                                    "/data/usage/toolCounts/"
                                                            + FactComparison.escape(k),
                                                    "count",
                                                    2));
            }
            case "model.call_finished" -> r.one("/data/dispatchDurationMs", "ms", 2);
            case "result" ->
                    r.fields("/usage", "ms", 5, List.of("sessionDurationMs", "totalApiDurationMs"));
            case "session.usage_checkpoint", "session.shutdown" -> {
                snapshot(r, "/data/accountingSnapshot");
                r.fields(
                        "/data",
                        "token",
                        5,
                        List.of(
                                "currentTokens",
                                "conversationTokens",
                                "toolDefinitionsTokens",
                                "systemTokens"));
            }
            case "tool.execution_complete" -> {
                r.fields(
                        "/data/toolTelemetry/metrics",
                        "byte",
                        99,
                        List.of(
                                "mcp_text_token_bytes",
                                "mcp_structured_token_bytes",
                                "mcp_shared_token_bytes"));
                r.fields(
                        "/data/toolTelemetry/metrics",
                        "token",
                        99,
                        List.of(
                                "result_token_count",
                                "context_token_limit",
                                "context_current_tokens"));
            }
            default -> {}
        }
    }

    private static SourceFacts.Target copilotTarget(ExpectedFacts f, int record, String type) {
        return switch (type) {
            case "session.usage_record" -> new SourceFacts.Target(f, record, "model_response", 2);
            case "session.usage_checkpoint", "session.shutdown" ->
                    new SourceFacts.Target(f, record, "session_context", 3);
            case "result" -> new SourceFacts.Target(f, record, "session_context", 4);
            case "tool.execution_complete" -> new SourceFacts.Target(f, record, "tool_call", 2);
            default -> new SourceFacts.Target(f, record, "source_record", 1);
        };
    }

    private static void snapshot(Reading r, String root) {
        r.one(root + "/totalUserRequests", "count", 5);
        r.fields(root, "token", 2, List.of("lastCallOutputTokens", "lastCallInputTokens"));
    }

    private static void junie(ExpectedFacts f, int record, Json n) {
        Json a = n.path("event", "agentEvent");
        if (a == null) return;
        String kind = String.valueOf(SourceProfile.text(a, "kind"));
        SourceFacts.Target target = junieTarget(f, record, kind);
        f.facts.add(
                new ExpectedFacts.Fact(
                        record,
                        target.cls(),
                        target.activity(),
                        Map.of(),
                        null,
                        Map.of(),
                        SourceFacts.fields("/reported_agent/uid", a.path("agent", "id"), "/reported_agent/kind", a.path("agent", "kind"), "/agent_uid", null),
                        List.of("/event/agentEvent/agent/id"),
                        "source.reported_agent/v2"));
        Reading r = new Reading(target, n);
        if (kind.equals("ContextWindowReportEvent")) {
            r.fields("/event/agentEvent", "token", 8, List.of("size", "used"));
            r.one("/event/agentEvent/percentage", "percent", 8);
        }
        if (kind.equals("AgentPatchCreatedEvent")) {
            r.fields("/event/agentEvent/stats", "line", 8, List.of("linesRemoved", "linesAdded"));
            r.one("/event/agentEvent/stats/filesChanged", "file", 8);
        }
    }

    private static SourceFacts.Target junieTarget(ExpectedFacts f, int record, String kind) {
        return switch (kind) {
            case "AgentCurrentStatusUpdatedEvent" ->
                    new SourceFacts.Target(f, record, "session_context", 5);
            case "ToolBlockUpdatedEvent",
                    "TerminalBlockUpdatedEvent",
                    "ViewFilesBlockUpdatedEvent" ->
                    new SourceFacts.Target(f, record, "tool_call", 3);
            case "LlmResponseMetadataEvent" ->
                    new SourceFacts.Target(f, record, "model_response", 2);
            case "ResultBlockUpdatedEvent" ->
                    new SourceFacts.Target(f, record, "model_response", 3);
            default -> new SourceFacts.Target(f, record, "source_record", 1);
        };
    }

    private static void telemetry(ExpectedFacts f, int record, Json n) {
        if (!"metric".equals(SourceProfile.text(n, "type"))) return;
        Reading r = new Reading(new SourceFacts.Target(f, record, "source_record", 1), n);
        String unit = SourceProfile.text(n, "unit");
        List<Json> points = n.member("dataPoints").elements();
        for (int i = 0; i < points.size(); i++) {
            String p = "/dataPoints/" + i + "/value";
            if (points.get(i).member("value") instanceof Json.JNumber) r.one(p, unit, 99);
            else {
                r.one(p + "/count", "count", 99);
                r.fields(p, unit, 99, List.of("min", "max", "sum"));
                r.array(p + "/buckets/counts", "count");
                r.array(p + "/buckets/boundaries", unit);
            }
        }
    }

    private record Reading(SourceFacts.Target target, Json nativeRecord) {
        private void one(String p, String unit, int scope) {
            Json value = FactComparison.at(nativeRecord, p);
            if (!(value instanceof Json.JNumber)) return;
            if (!"metric".equals(SourceProfile.text(nativeRecord, "type"))) {
                new ExecutionMetricReading.Reading(target).quantity(value, p, unit, scope, null);
                return;
            }
            String point = p.substring(0, p.indexOf("/value"));
            Json dimensions = FactComparison.at(nativeRecord, point);
            Map<String, Json> data =
                    SourceFacts.fields(
                            "metric_name",
                            nativeRecord.member("name"),
                            "unit",
                            nativeRecord.member("unit"),
                            "startTime",
                            dimensions.member("startTime"),
                            "endTime",
                            dimensions.member("endTime"),
                            "attributes",
                            dimensions.member("attributes"));
            data.values().removeIf(java.util.Objects::isNull);
            target.f()
                    .facts
                    .add(
                            new ExpectedFacts.Fact(
                                    target.record(),
                                    target.cls(),
                                    target.activity(),
                                    Map.of(),
                                    "/execution_metrics",
                                    SourceFacts.fields(
                                            "/metric_name",
                                            SourceProfile.text(nativeRecord, "name") + ":" + p),
                                    SourceFacts.fields(
                                            "/metric_name",
                                            SourceProfile.text(nativeRecord, "name") + ":" + p,
                                            "/source_ref/artifact",
                                            target.f().artifact,
                                            "/source_ref/record_index",
                                            target.record(),
                                            "/reported_value",
                                            value,
                                            "/metric_unit",
                                            unit,
                                            "/scope_id",
                                            99,
                                            "/scope",
                                            "Source observation",
                                            "/measurement_status_id",
                                            0,
                                            "/native_pointers",
                                            List.of(p),
                                            "/model_ref/name",
                                            null,
                                            "/data",
                                            new Json.JObject(data)),
                                    List.of(
                                            p,
                                            "/name",
                                            "/unit",
                                            point + "/attributes",
                                            point + "/startTime",
                                            point + "/endTime"),
                                    "source.exporter_statistic/v1"));
        }

        private void fields(String p, String unit, int scope, List<String> fields) {
            fields.forEach(k -> one(p + "/" + k, unit, scope));
        }

        private void array(String p, String unit) {
            Json values = FactComparison.at(nativeRecord, p);
            if (values instanceof Json.JArray a)
                for (int i = 0; i < a.elements().size(); i++) one(p + "/" + i, unit, 99);
        }
    }
}
