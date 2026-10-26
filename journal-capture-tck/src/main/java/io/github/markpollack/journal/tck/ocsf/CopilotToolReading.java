package io.github.markpollack.journal.tck.ocsf;

import io.github.markpollack.journal.tck.verify.Json;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Independent Copilot tool lifecycle, result and explicitly reported MCP projection. */
final class CopilotToolReading {
    private CopilotToolReading() {}

    static void read(ExpectedFacts f, int record, Json d, Json start, int activity) {
        Map<String, Json> fields = fields(d, activity);
        mcp(f, record, d, start, activity, fields);
        if (activity == 2 && d.member("error") instanceof Json.JObject error) {
            f.toolErrors.add(
                    new ExpectedFacts.ToolError(
                            record,
                            SourceProfile.text(d, "toolCallId"),
                            3,
                            error.get("code"),
                            SourceProfile.text(error, "message")));
        }
        CopilotProfile.event(
                new SourceFacts.Target(f, record, "tool_call", activity),
                "/data",
                "tool.stage",
                SourceFacts.fields("/tool_call_uid", d.member("toolCallId")),
                fields);
    }

    private static Map<String, Json> fields(Json d, int activity) {
        Map<String, Json> fields = SourceFacts.fields("/identity_basis_id", 1);
        if (activity == 4) {
            fields.putAll(
                    SourceFacts.fields(
                            "/tool_name",
                            d.member("toolName"),
                            "/arguments",
                            d.member("arguments")));
        } else {
            fields.putAll(
                    SourceFacts.fields(
                            "/turn_uid",
                            d.member("interactionId"),
                            "/tool_result/data",
                            d.member("result") == null ? d.member("error") : d.member("result"),
                            "/tool_result/structured_result",
                            d.path("result", "structuredContent")));
            if (d.member("success") instanceof Json.JBool success) {
                fields.putAll(
                        SourceFacts.fields(
                                "/status_id",
                                success.value() ? 1 : 2,
                                "/status_basis_id",
                                1,
                                "/tool_result/error_reported",
                                !success.value()));
            }
        }
        return fields;
    }

    private static void mcp(
            ExpectedFacts f,
            int record,
            Json d,
            Json start,
            int activity,
            Map<String, Json> fields) {
        if (start == null || SourceProfile.text(start, "mcpServerName") == null) {
            return;
        }

        fields.putAll(
                SourceFacts.fields(
                        "/mcp/target_server_name",
                        start.member("mcpServerName"),
                        "/mcp/target_server_basis_id",
                        1,
                        "/mcp/target_tool_name",
                        start.member("mcpToolName"),
                        "/mcp/target_tool_basis_id",
                        1));

        List<ExpectedFacts.McpProjection> projections = new java.util.ArrayList<>();
        Map<String, Json> projection = new LinkedHashMap<>();
        projection.putAll(projection(d, activity));
        projections.addAll(projections(activity, projection));
        f.mcp.add(
                new ExpectedFacts.Mcp(
                        record,
                        SourceProfile.text(d, "toolCallId"),
                        activity,
                        SourceProfile.text(start, "mcpServerName"),
                        1,
                        SourceProfile.text(start, "mcpToolName"),
                        1,
                        activity == 4 && d.member("arguments") != null ? "" : null,
                        projections));
    }

    private static List<ExpectedFacts.McpProjection> projections(
            int activity, Map<String, Json> projection) {
        List<ExpectedFacts.McpProjection> projections = new java.util.ArrayList<>();
        if (activity == 4 || !projection.isEmpty()) {
            String required = activity == 4 ? "name" : "content";
            projections.add(
                    new ExpectedFacts.McpProjection(
                            activity == 4 ? "CallToolRequestParams" : "CallToolResult",
                            new Json.JObject(projection),
                            projection.containsKey(required) ? List.of() : List.of(required)));
        }
        return projections;
    }

    private static Map<String, Json> projection(Json d, int activity) {
        Map<String, Json> projection = new LinkedHashMap<>();
        if (activity == 4) {
            if (d.member("mcpToolName") != null) {
                projection.put("name", d.member("mcpToolName"));
            }
            if (d.member("arguments") != null) {
                projection.put("arguments", d.member("arguments"));
            }
        } else if (d.member("result") instanceof Json.JObject result) {
            if (result.get("contents") != null) {
                projection.put("content", result.get("contents"));
            }
            if (result.get("structuredContent") != null) {
                projection.put("structuredContent", result.get("structuredContent"));
            }
        }
        return projection;
    }
}
