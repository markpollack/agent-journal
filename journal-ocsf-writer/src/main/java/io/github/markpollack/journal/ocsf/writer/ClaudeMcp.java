package io.github.markpollack.journal.ocsf.writer;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static io.github.markpollack.journal.ocsf.writer.MapperSupport.text;

/**
 * claude.stream.mcp.v1: the MCP facts of a Claude Code stream. The stream names an MCP tool {@code mcp__<server>__<tool>}
 * (Claude Code's documented convention) and lists, in its init record, the registered servers ({@code mcp_servers[].name})
 * and the available tool names. A tool_use is an MCP call to server S and tool T when its full name is an available tool
 * and exactly one registered server S makes it {@code mcp__S__T} with a non-empty T: a declared derivation (basis 3),
 * never a report. An ambiguous or unregistered name stays an ordinary tool call with no target; the reported name and
 * input are never rewritten. The request projects CallToolRequestParams {arguments} (the tool_use input; the derived name
 * is the target, not a projected member); the result projects CallToolResult {structuredContent} from the record's
 * tool_use_result, whose content is a rendering, not MCP content blocks. A reported is_error is a CLI-reported failure:
 * the stream does not say whether the server returned isError or a JSON-RPC error.
 */
final class ClaudeMcp {

    private final Set<String> servers = new LinkedHashSet<>();
    private final Set<String> tools = new LinkedHashSet<>();
    private final Map<String, String[]> calls = new LinkedHashMap<>();

    /** Reads the registered servers and the available tools from a system init record. */
    void init(Jv o) {
        for (Jv s : o.member("mcp_servers") == null ? List.<Jv>of() : o.member("mcp_servers").elements()) {
            if (text(s, "name") != null) {
                servers.add(text(s, "name"));
            }
        }
        for (Jv t : o.member("tools") == null ? List.<Jv>of() : o.member("tools").elements()) {
            if (t.text() != null) {
                tools.add(t.text());
            }
        }
    }

    /** The {@code mcp} observation of a tool_use block, or {@code null} when its name is not an unambiguous registered MCP tool. */
    Jv.Obj request(Jv block) {
        String name = text(block, "name");
        String[] target = derive(name);
        if (target == null) {
            return null;
        }
        calls.put(text(block, "id"), target);
        Jv.Obj mcp = McpObservations.target(target[0], McpObservations.ENCODED_NAME, target[1], McpObservations.ENCODED_NAME, block.member("input") == null ? null : "",
                "claude.stream.mcp.v1: derived from the reported name " + name + " by the mcp__<server>__<tool> convention; " + target[0]
                        + " is the one registered server (init mcp_servers) that makes it a tool the init record lists; the negotiated protocol revision is not reported");
        return McpObservations.project(mcp, McpObservations.PROJECTION_BINDING, "CallToolRequestParams", McpObservations.members("arguments", block.member("input")),
                "tool_use.input as arguments; the tool's name on the server is the derived target, not a reported member");
    }

    /** The {@code mcp} observation of a tool_result block of an MCP call, or {@code null} for any other call. */
    Jv.Obj result(String toolUseId, Jv record, String structuredMember) {
        if (!calls.containsKey(toolUseId)) {
            return null;
        }
        Jv.Obj mcp = new Jv.Obj().put("basis", "claude.stream.mcp.v1: the result of the MCP call " + toolUseId + " (target on its request)");
        Jv structured = record.member(structuredMember) instanceof Jv.Obj r ? r.get("structuredContent") : null;
        if (structured == null) {
            return mcp;
        }
        return McpObservations.project(mcp, McpObservations.PROJECTION_BINDING, "CallToolResult", McpObservations.members("structuredContent", structured),
                structuredMember + ".structuredContent as structuredContent; the result content the stream reports is a rendering, not MCP content blocks");
    }

    /** A CLI-reported failure of an MCP call, or {@code null}. */
    Jv.Arr errors(String toolUseId, Jv block) {
        if (!calls.containsKey(toolUseId) || !(block.member("is_error") instanceof Jv.Bool b) || !b.value()) {
            return null;
        }
        return McpObservations.errors(McpObservations.error(3, null, text(block, "content"), null,
                "claude.stream.mcp.v1: tool_result.is_error true for an MCP call; the stream does not say whether the server returned isError or a JSON-RPC error"));
    }

    private String[] derive(String name) {
        if (name == null || !name.startsWith("mcp__") || !tools.contains(name)) {
            return null;
        }
        List<String[]> matches = new ArrayList<>();
        for (String s : servers) {
            String prefix = "mcp__" + s + "__";
            if (name.startsWith(prefix) && name.length() > prefix.length()) {
                matches.add(new String[] {s, name.substring(prefix.length())});
            }
        }
        return matches.size() == 1 ? matches.get(0) : null;
    }
}
