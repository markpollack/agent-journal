package io.github.markpollack.journal.tck.ocsf;

import io.github.markpollack.journal.tck.verify.Json;
import io.github.markpollack.journal.tck.verify.NeutralJson;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Native OTel file reading for the exact Copilot 1.0.100 route. Unset span status is absence, not
 * success.
 */
final class CopilotTelemetryReading {

    private CopilotTelemetryReading() {}

    static void read(ExpectedFacts f, int record, Json n) {
        if (!"span".equals(SourceProfile.text(n, "type"))) {
            return;
        }
        Json a = n.member("attributes");
        String session = SourceProfile.text(a, "gen_ai.conversation.id");
        if (session != null) {
            f.sessionOf.put(record, session);
        }
        if (SourceProfile.text(n, "spanId") != null) {
            f.nativeUid.put(record, SourceProfile.text(n, "spanId"));
        }
        List<Json> end = n.member("endTime") == null ? List.of() : n.member("endTime").elements();
        if (end.size() == 2) {
            long ms =
                    SourceProfile.decimal(end.get(0)).longValueExact() * 1000
                            + SourceProfile.decimal(end.get(1)).longValueExact() / 1_000_000;
            f.nativeTime.put(record, Instant.ofEpochMilli(ms).toString());
        }
        switch (String.valueOf(SourceProfile.text(a, "gen_ai.operation.name"))) {
            case "chat" -> {
                CopilotProfile.event(
                        new SourceFacts.Target(f, record, "model_response", 2),
                        "/attributes",
                        "otel.chat",
                        Map.of(),
                        SourceFacts.fields(
                                "/response_uid",
                                a.member("gen_ai.response.id"),
                                "/identity_basis_id",
                                99,
                                "/identity_basis",
                                "Provider call identifier (apiCallId)",
                                "/turn_uid",
                                a.member("github.copilot.interaction_id"),
                                "/model_ref/name",
                                a.member("gen_ai.response.model"),
                                "/model_ref/provider_kind",
                                null));
                accounting(
                        new SourceFacts.Target(f, record, "model_response", 2),
                        a,
                        2,
                        SourceProfile.text(a, "gen_ai.response.model"));
            }
            case "execute_tool" -> tool(f, record, n, a);
            case "invoke_agent" -> {
                definitions(f, record, a);
                CopilotProfile.event(
                        new SourceFacts.Target(f, record, "session_context", 3),
                        "/attributes",
                        "otel.invocation",
                        Map.of(),
                        Map.of());
                accounting(new SourceFacts.Target(f, record, "session_context", 3), a, 4, null);
            }
            default -> {}
        }
    }

    private static void accounting(SourceFacts.Target target, Json a, int scope, String model) {
        Map<String, Json> usageData = new LinkedHashMap<>();
        ((Json.JObject) a)
                .members()
                .forEach(
                        (key, value) -> {
                            if (key.startsWith("gen_ai.usage.")) {
                                usageData.put(key, value);
                            }
                        });
        ExpectedFacts.Tranches tranches =
                SourceFacts.tranches(
                        a,
                        new SourceFacts.TrancheNames(
                                "gen_ai.usage.input_tokens",
                                "gen_ai.usage.cache_read.input_tokens",
                                "gen_ai.usage.cache_write.input_tokens",
                                "gen_ai.usage.output_tokens",
                                "gen_ai.usage.reasoning.output_tokens",
                                true));
        target.usage(
                "/attributes",
                "copilot.otel.usage/v1",
                new SourceFacts.Usage(
                        new Json.JObject(usageData), tranches, scope, 2, 1, model, null));
        String scopeName = scope == 2 ? "Model response" : "Run";
        target.quantity(
                "/attributes/github.copilot.cost",
                "copilot.otel.multiplier/v1",
                new SourceFacts.Quantity(
                        a.member("github.copilot.cost"),
                        3,
                        scope == 2 ? "multiplier" : "multiplier sum",
                        null,
                        scope,
                        scopeName,
                        1,
                        null));
        target.quantity(
                "/attributes/github.copilot.nano_aiu",
                "copilot.otel.nano_aiu/v1",
                new SourceFacts.Quantity(
                        a.member("github.copilot.nano_aiu"),
                        4,
                        "nano-AIU",
                        null,
                        scope,
                        scopeName,
                        1,
                        null));
    }

    private static void tool(ExpectedFacts f, int record, Json n, Json a) {
        boolean error = Integer.valueOf(2).equals(Event.integer(n.path("status", "code")));
        Json result = a.member("gen_ai.tool.call.result");
        Map<String, Json> fields =
                SourceFacts.fields(
                        "/tool_name",
                        a.member("gen_ai.tool.name"),
                        "/tool_family",
                        a.member("gen_ai.tool.type"),
                        "/identity_basis_id",
                        1,
                        "/arguments",
                        parsed(a.member("gen_ai.tool.call.arguments")),
                        "/tool_result/data",
                        result == null ? Json.JNull.INSTANCE : result,
                        "/status_id",
                        error ? Integer.valueOf(2) : null,
                        "/status_basis_id",
                        error ? 1 : null,
                        "/tool_result/error_reported",
                        error ? Boolean.TRUE : null);
        int kind =
                SourceProfile.text(a, "github.copilot.tool.parameters.mcp_server_name") != null
                        ? 99
                        : NativeDetails.toolKind(SourceProfile.text(a, "gen_ai.tool.name"));
        fields.put("/tool_kind_id", FactComparison.value(kind));
        if (kind == 99) {
            fields.put("/tool_kind", FactComparison.value("other"));
        }
        if (error) {
            new SourceFacts.Target(f, record, "tool_call", 2)
                    .observation(
                            "/tool_result/errors",
                            "/status",
                            "copilot.span_error/v1",
                            SourceFacts.fields("/error_layer_id", 3),
                            SourceFacts.fields(
                                    "/error_code",
                                    null,
                                    "/message",
                                    n.path("status", "message"),
                                    "/data",
                                    n.member("status")));
        }
        if (SourceProfile.text(a, "github.copilot.tool.parameters.mcp_server_name") != null) {
            fields.putAll(
                    SourceFacts.fields(
                            "/mcp/target_server_name",
                            a.member("github.copilot.tool.parameters.mcp_server_name"),
                            "/mcp/target_tool_name",
                            a.member("github.copilot.tool.parameters.mcp_tool_name"),
                            "/mcp/target_server_basis_id",
                            1,
                            "/mcp/target_tool_basis_id",
                            1,
                            "/mcp/protocol_objects",
                            null));
        }
        CopilotProfile.event(
                new SourceFacts.Target(f, record, "tool_call", 2),
                "/attributes",
                "otel.tool",
                SourceFacts.fields("/tool_call_uid", a.member("gen_ai.tool.call.id")),
                fields);
    }

    private static void definitions(ExpectedFacts f, int record, Json a) {
        Json definitions = parsed(a.member("gen_ai.tool.definitions"));
        Map<String, Json> fields = new LinkedHashMap<>();
        List<Json> names = new ArrayList<>();
        if (definitions instanceof Json.JArray list) {
            for (int i = 0; i < list.elements().size(); i++) {
                Json definition = list.elements().get(i);
                names.add(definition.member("name"));
                String p = "/tool_definitions/" + i;
                fields.putAll(
                        SourceFacts.fields(
                                p + "/name",
                                definition.member("name"),
                                p + "/tool_description",
                                definition.member("description"),
                                p + "/definition_format_id",
                                2,
                                p + "/input_schema/schema_document",
                                definition.member("parameters"),
                                p + "/data",
                                definition));
            }
            fields.put("/settings/tool_names", new Json.JArray(names));
        } else {
            fields.put("/tool_definitions", null);
        }
        CopilotProfile.event(
                new SourceFacts.Target(f, record, "session_context", 1),
                "/attributes/gen_ai.tool.definitions",
                "otel.definitions",
                Map.of(),
                fields);
    }

    private static Json parsed(Json value) {
        if (!(value instanceof Json.JString string)) {
            return value;
        }
        try {
            return NeutralJson.parse(string.value());
        } catch (IllegalArgumentException e) {
            return value;
        }
    }
}
