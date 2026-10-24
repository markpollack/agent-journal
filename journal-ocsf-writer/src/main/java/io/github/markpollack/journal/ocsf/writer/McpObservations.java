package io.github.markpollack.journal.ocsf.writer;

import java.util.List;
import java.util.Map;

/**
 * The {@code mcp} observation a mapper attaches to a tool call it can establish as an MCP call: the addressed server and
 * tool, each with its own basis and kept apart from the reported tool name and arguments, and the protocol objects the
 * CLI projected, typed by the pinned binding as partial projections. Nothing is synthesized to complete a projection:
 * no request id, protocol revision, capability, structured result, isError or success the source did not report.
 */
final class McpObservations {

    /**
     * The binding a projection is typed by when the route does not establish the negotiated protocol revision: the
     * earliest pinned revision, whose definitions of the projected members (CallToolRequestParams name and arguments,
     * CallToolResult content and structuredContent, the content blocks) the later pinned revision keeps unchanged.
     */
    static final String PROJECTION_BINDING = "2025-11-25";
    static final Map<Integer, String> TARGET_BASIS = Map.of(0, "Unknown", 1, "Reported members of the call", 2, "Reported dispatcher arguments",
            3, "Declared derivation from an encoded tool name", 99, "Other");
    static final Map<Integer, String> REVISION_BASIS = Map.of(0, "Unknown", 1, "Reported in the protocol exchange", 2, "Reported by a CLI artifact",
            3, "Declared by the capture configuration", 99, "Other");
    static final Map<Integer, String> ERROR_LAYER = Map.of(0, "Unknown", 1, "JSON-RPC protocol error", 2, "Tool result error", 3, "CLI-reported tool failure",
            4, "CLI or process failure", 5, "Capture failure", 99, "Other");

    static final int REPORTED_MEMBERS = 1;
    static final int DISPATCHER_ARGUMENTS = 2;
    static final int ENCODED_NAME = 3;

    private McpObservations() {
    }

    /** The addressed server and tool; the arguments pointer only when the call reports the target's arguments. */
    static Jv.Obj target(String server, int serverBasis, String tool, int toolBasis, String argumentsPointer, String basis) {
        Jv.Obj mcp = new Jv.Obj();
        if (server != null) {
            mcp.put("target_server_name", server).put("target_server_basis_id", serverBasis).put("target_server_basis", TARGET_BASIS.get(serverBasis));
        }
        if (tool != null) {
            mcp.put("target_tool_name", tool).put("target_tool_basis_id", toolBasis).put("target_tool_basis", TARGET_BASIS.get(toolBasis));
        }
        return mcp.put("target_arguments_pointer", argumentsPointer).put("basis", basis);
    }

    /** The established protocol revision of the session, with how it was established. */
    static Jv.Obj revision(Jv.Obj mcp, String revision, int basisId, String basis) {
        return mcp.put("protocol_revision", revision).put("protocol_revision_basis_id", basisId).put("protocol_revision_basis", basis == null ? REVISION_BASIS.get(basisId) : basis);
    }

    /** Adds a partial projection of {@code definition} under {@code revision}'s binding. */
    static Jv.Obj project(Jv.Obj mcp, String revision, String definition, Jv projected, String basis) {
        Jv.Obj po = McpBinding.bundled().protocolObject(revision, definition, projected, McpBinding.PARTIAL, basis);
        Jv existing = mcp.get("protocol_objects");
        Jv.Arr list = existing instanceof Jv.Arr a ? a : new Jv.Arr();
        list.add(po);
        return mcp.put("protocol_objects", list);
    }

    /** One reported error with the layer that reported it. */
    static Jv.Obj error(int layer, Jv code, String message, Jv data, String basis) {
        return new Jv.Obj().put("error_layer_id", layer).put("error_layer", ERROR_LAYER.get(layer)).put("error_code", code).put("message", message)
                .put("data", data).put("basis", basis);
    }

    /** {@code tool_result.errors} with one entry. */
    static Jv.Arr errors(Jv.Obj error) {
        return new Jv.Arr(List.of(error));
    }

    /** A JSON object with the given members, absent members (null values) left out. */
    static Jv.Obj members(Object... pairs) {
        Jv.Obj o = new Jv.Obj();
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            if (pairs[i + 1] instanceof Jv v) {
                o.put((String) pairs[i], v);
            }
        }
        return o;
    }
}
