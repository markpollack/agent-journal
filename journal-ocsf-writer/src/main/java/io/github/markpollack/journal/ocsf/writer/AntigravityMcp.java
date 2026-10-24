package io.github.markpollack.journal.ocsf.writer;

import static io.github.markpollack.journal.ocsf.writer.MapperSupport.text;

/**
 * agy.stream.mcp.v1: Antigravity reaches MCP tools through its {@code call_mcp_tool} dispatcher, whose reported
 * parameters name the server ({@code ServerName}), the tool ({@code ToolName}) and the tool's own arguments
 * ({@code Arguments}). The dispatcher stays the call's tool_name and its parameters stay the call's arguments; the target
 * is the reported dispatcher arguments (basis 2), each component only when it is a string, the arguments located at
 * {@code /Arguments} only when reported. The started step projects CallToolRequestParams {name, arguments}. The stream
 * reports a finished call's output as text, not MCP content, and no structuredContent or isError, so no result is
 * projected; a reported tool_info.error is a CLI-reported failure.
 */
final class AntigravityMcp {

    static final String DISPATCHER = "call_mcp_tool";

    private AntigravityMcp() {
    }

    /** The {@code mcp} observation of a started dispatcher step, or {@code null} for any other tool. */
    static Jv.Obj started(String toolName, Jv parameters) {
        if (!DISPATCHER.equals(toolName) || !(parameters instanceof Jv.Obj p)) {
            return null;
        }
        String server = p.get("ServerName") instanceof Jv.Str s ? s.value() : null;
        String tool = p.get("ToolName") instanceof Jv.Str t ? t.value() : null;
        if (server == null && tool == null) {
            return null;
        }
        Jv.Obj mcp = McpObservations.target(server, McpObservations.DISPATCHER_ARGUMENTS, tool, McpObservations.DISPATCHER_ARGUMENTS, p.has("Arguments") ? "/Arguments" : null,
                "agy.stream.mcp.v1: the call_mcp_tool dispatcher's reported ServerName and ToolName; the dispatcher's name and arguments are kept; the negotiated protocol revision is not reported");
        return McpObservations.project(mcp, McpObservations.PROJECTION_BINDING, "CallToolRequestParams", McpObservations.members("name", p.get("ToolName"), "arguments", p.get("Arguments")),
                "tool_info.parameters.ToolName as name and tool_info.parameters.Arguments as arguments, as the dispatcher reports them");
    }

    /** A CLI-reported failure of a finished dispatcher step, or {@code null}. */
    static Jv.Arr errors(String toolName, Jv error) {
        if (!DISPATCHER.equals(toolName) || error == null) {
            return null;
        }
        return McpObservations.errors(McpObservations.error(3, error.member("type"), text(error, "message"), error,
                "agy.stream.mcp.v1: tool_info.error on a call_mcp_tool step; the stream does not say whether the server returned isError or a JSON-RPC error"));
    }
}
