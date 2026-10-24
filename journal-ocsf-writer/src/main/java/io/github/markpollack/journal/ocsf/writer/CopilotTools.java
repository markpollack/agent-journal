package io.github.markpollack.journal.ocsf.writer;

import static io.github.markpollack.journal.ocsf.writer.MapperSupport.text;

/**
 * Tool execution stages of GitHub Copilot CLI 1.0.100 events: the reported success flag, the result as reported (the
 * text content, the detailed content standard output adds, the MCP content list and structured content where the CLI
 * reports them), the CLI-reported failure, and the MCP target and partial protocol projection of an MCP tool
 * (copilot.events.mcp.v1). Nothing the CLI does not report is synthesized: no isError, request id or protocol revision.
 */
final class CopilotTools {

    private CopilotTools() {
    }

    static MapperSupport.Status status(Jv data) {
        Jv success = data.member("success");
        if (success instanceof Jv.Bool b) {
            return new MapperSupport.Status(b.value() ? 1 : 2, 1, "reported: data.success " + b.value());
        }
        return new MapperSupport.Status(0, 3, "the completion reports no success flag");
    }

    /** The result as reported, with the CLI's failure as a CLI-reported tool failure (layer 3); {@code null} when neither is reported. */
    static Jv.Obj result(Jv data) {
        Jv res = data.member("result");
        Jv error = data.member("error");
        if (res == null && error == null) {
            return null;
        }
        // the result as reported; a failure that reports none has its reported error as the data
        Jv.Obj out = new Jv.Obj().put("data", res == null ? error : res);
        if (data.member("success") instanceof Jv.Bool b) {
            out.put("error_reported", !b.value());
        }
        if (error instanceof Jv.Obj e) {
            out.put("errors", McpObservations.errors(McpObservations.error(3, e.member("code"), text(e, "message"), e,
                    "copilot.events.tool_error.v1: data.error is the CLI's report of the tool's failure; whether an MCP server returned isError or a JSON-RPC error is not reported")));
        }
        Jv structured = res == null ? null : res.member("structuredContent");
        return structured == null || structured.isNull() ? out : out.put("structured_result", structured);
    }

    /**
     * copilot.events.mcp.v1: an MCP tool's server and tool are the start event's own reported members (mcpServerName,
     * mcpToolName); a completion, which does not repeat them, takes them from the start of the same call in the same
     * source ({@code started} names the start's members; {@code null} when no start named an MCP tool). The start
     * projects CallToolRequestParams {name, arguments}, the completion CallToolResult {content (result.contents, reported
     * on standard output only), structuredContent}; {@code null} for a tool that is not MCP.
     */
    static Jv.Obj mcp(Jv data, boolean start, String[] started) {
        String server = start ? text(data, "mcpServerName") : started == null ? null : started[0];
        if (server == null) {
            return null;
        }
        String tool = start ? text(data, "mcpToolName") : started[1];
        Jv arguments = data.member("arguments");
        Jv.Obj mcp = McpObservations.target(server, McpObservations.REPORTED_MEMBERS, tool, McpObservations.REPORTED_MEMBERS, start && arguments != null ? "" : null,
                start ? "copilot.events.mcp.v1: data.mcpServerName and data.mcpToolName are the call's own members (data.toolName is the CLI's encoded name); "
                        + "the negotiated protocol revision is not reported in the event"
                        : "copilot.events.mcp.v1: the start of the same call (data.toolCallId) in this source names its server and tool; the completion does not repeat them");
        return start ? McpObservations.project(mcp, McpObservations.PROJECTION_BINDING, "CallToolRequestParams",
                McpObservations.members("name", data.member("mcpToolName"), "arguments", arguments), "data.mcpToolName as name and data.arguments as arguments")
                : result(mcp, data.member("result"));
    }

    /** The completion's CallToolResult projection {content (standard output only), structuredContent}, when either is reported. */
    private static Jv.Obj result(Jv.Obj mcp, Jv res) {
        if (!(res instanceof Jv.Obj r) || (r.member("contents") == null && r.member("structuredContent") == null)) {
            return mcp;
        }
        return McpObservations.project(mcp, McpObservations.PROJECTION_BINDING, "CallToolResult",
                McpObservations.members("content", r.member("contents"), "structuredContent", r.member("structuredContent")),
                "data.result.contents as content (standard output only) and data.result.structuredContent as structuredContent; isError is not reported");
    }
}
