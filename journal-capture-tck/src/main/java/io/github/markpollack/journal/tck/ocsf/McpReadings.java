package io.github.markpollack.journal.tck.ocsf;

import io.github.markpollack.journal.tck.verify.Json;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The oracle's own reading of the MCP facts a native record reports (RULE-53), per route, written from the native
 * fields and the pinned MCP definitions' required members (CallToolRequestParams requires {@code name},
 * CallToolResult {@code content}); it shares nothing with the writer's mappers. A projection holds only members the
 * record reports; a component of the target only where the record or a declared derivation establishes it.
 */
final class McpReadings {

    static final int REPORTED_MEMBERS = 1;
    static final int DISPATCHER_ARGUMENTS = 2;
    static final int ENCODED_NAME = 3;
    static final int CLI_FAILURE = 3;

    private McpReadings() {
    }

    // ------------------------------------------------------------------------------------------ Codex exec stream

    /** An mcp_tool_call item: server and tool are its own members; started projects the request, completed the result. */
    static void codex(ExpectedFacts f, int index, Json item, boolean started) {
        if (!"mcp_tool_call".equals(SourceProfile.text(item, "type"))) {
            return;
        }
        String id = SourceProfile.text(item, "id");
        f.mcp.add(new ExpectedFacts.Mcp(index, id, started ? 4 : 2, SourceProfile.text(item, "server"), basis(item, "server", REPORTED_MEMBERS),
                SourceProfile.text(item, "tool"), basis(item, "tool", REPORTED_MEMBERS), arguments(item) == null ? null : "", codexProjections(item, started)));
        boolean failed = "failed".equals(SourceProfile.text(item, "status")) || item.member("error") instanceof Json.JObject;
        if (!started && failed && (item.member("result") instanceof Json.JObject || item.member("error") instanceof Json.JObject)) {
            f.toolErrors.add(new ExpectedFacts.ToolError(index, id, CLI_FAILURE, null, SourceProfile.text(item, "error", "message")));
        }
    }

    private static List<ExpectedFacts.McpProjection> codexProjections(Json item, boolean started) {
        if (started) {
            return List.of(projection("CallToolRequestParams", members("name", item.member("tool"), "arguments", arguments(item)), "name"));
        }
        if (!(item.member("result") instanceof Json.JObject res)) {
            return List.of();
        }
        Json structured = res.member("structured_content");
        return List.of(projection("CallToolResult", members("content", res.member("content"), "structuredContent", structured == null || structured.isNull() ? null : structured), "content"));
    }

    /** The item's arguments, {@code null} when it reports none or reports JSON null (the emitter's absent arguments). */
    private static Json arguments(Json item) {
        Json a = item.member("arguments");
        return a == null || a.isNull() ? null : a;
    }

    // ------------------------------------------------------------------------------------------ Antigravity stream

    /** A call_mcp_tool step: the target and the request projection from its reported parameters when ACTIVE; its reported error when finished. */
    static void antigravity(ExpectedFacts f, int index, String id, Json step) {
        if (!"call_mcp_tool".equals(SourceProfile.text(step, "tool_name"))) {
            return;
        }
        Json info = step.member("tool_info");
        String state = SourceProfile.text(step, "state");
        if ("ACTIVE".equals(state) && info != null && info.member("parameters") instanceof Json.JObject p) {
            dispatcher(f, index, id, p);
        }
        Json error = info == null ? null : info.member("error");
        if (error != null && ("DONE".equals(state) || "ERROR".equals(state))) {
            f.toolErrors.add(new ExpectedFacts.ToolError(index, id, CLI_FAILURE, error.member("type"), SourceProfile.text(error, "message")));
        }
    }

    private static void dispatcher(ExpectedFacts f, int index, String id, Json.JObject p) {
        String server = p.get("ServerName") instanceof Json.JString s ? s.value() : null;
        String tool = p.get("ToolName") instanceof Json.JString t ? t.value() : null;
        if (server == null && tool == null) {
            return;
        }
        f.mcp.add(new ExpectedFacts.Mcp(index, id, 4, server, server == null ? null : DISPATCHER_ARGUMENTS, tool, tool == null ? null : DISPATCHER_ARGUMENTS,
                p.get("Arguments") == null ? null : "/Arguments",
                List.of(projection("CallToolRequestParams", members("name", p.get("ToolName"), "arguments", p.get("Arguments")), "name"))));
    }

    // ------------------------------------------------------------------------------------------ Claude stream

    /**
     * The Claude stream's MCP reading: a tool_use is an MCP call when its full name is a tool the init record lists and
     * exactly one server the init record registers makes it {@code mcp__<server>__<tool>} with a non-empty tool (a
     * declared derivation, basis 3); the name is the target, the input the projected arguments. The init record is read
     * whatever core owns it: registration is a fact of the session.
     */
    static final class Claude {
        private final Set<String> servers = new LinkedHashSet<>();
        private final Set<String> tools = new LinkedHashSet<>();
        private final Map<String, String[]> calls = new LinkedHashMap<>();

        void init(Json record) {
            for (Json s : record.member("mcp_servers") == null ? List.<Json>of() : record.member("mcp_servers").elements()) {
                if (SourceProfile.text(s, "name") != null) {
                    servers.add(SourceProfile.text(s, "name"));
                }
            }
            for (Json t : record.member("tools") == null ? List.<Json>of() : record.member("tools").elements()) {
                if (t.text() != null) {
                    tools.add(t.text());
                }
            }
        }

        void request(ExpectedFacts f, int index, Json toolUse) {
            String[] target = target(SourceProfile.text(toolUse, "name"));
            if (target == null) {
                return;
            }
            String id = SourceProfile.text(toolUse, "id");
            calls.put(id, target);
            f.mcp.add(new ExpectedFacts.Mcp(index, id, 1, target[0], ENCODED_NAME, target[1], ENCODED_NAME, toolUse.member("input") == null ? null : "",
                    List.of(projection("CallToolRequestParams", members("arguments", toolUse.member("input")), "name"))));
        }

        void result(ExpectedFacts f, int index, Json record, Json toolResult) {
            String id = SourceProfile.text(toolResult, "tool_use_id");
            if (!calls.containsKey(id)) {
                return;
            }
            Json structured = record.member("tool_use_result") instanceof Json.JObject r ? r.get("structuredContent") : null;
            f.mcp.add(new ExpectedFacts.Mcp(index, id, 2, null, null, null, null, null,
                    structured == null ? List.of() : List.of(projection("CallToolResult", members("structuredContent", structured), "content"))));
            if (toolResult.member("is_error") instanceof Json.JBool b && b.value()) {
                f.toolErrors.add(new ExpectedFacts.ToolError(index, id, CLI_FAILURE, null, SourceProfile.text(toolResult, "content")));
            }
        }

        private String[] target(String name) {
            if (name == null || !tools.contains(name)) {
                return null;
            }
            String[] found = null;
            int matches = 0;
            for (String s : servers) {
                String prefix = "mcp__" + s + "__";
                if (name.startsWith(prefix) && name.length() > prefix.length()) {
                    found = new String[] {s, name.substring(prefix.length())};
                    matches++;
                }
            }
            return matches == 1 ? found : null;
        }
    }

    // ------------------------------------------------------------------------------------------ helpers

    private static Integer basis(Json record, String member, int basis) {
        return SourceProfile.text(record, member) == null ? null : basis;
    }

    /** A projection of the reported members, lacking the one protocol-required member when the record does not report it. */
    private static ExpectedFacts.McpProjection projection(String definition, Json.JObject value, String required) {
        return new ExpectedFacts.McpProjection(definition, value, value.get(required) == null ? List.of(required) : List.of());
    }

    private static Json.JObject members(Object... pairs) {
        Map<String, Json> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            if (pairs[i + 1] instanceof Json v) {
                m.put((String) pairs[i], v);
            }
        }
        return new Json.JObject(m);
    }
}
