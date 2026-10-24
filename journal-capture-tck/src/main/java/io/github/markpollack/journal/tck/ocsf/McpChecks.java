package io.github.markpollack.journal.tck.ocsf;

import io.github.markpollack.journal.tck.verify.Json;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * The oracle's checks of MCP observations and reported tool errors against what a source profile reads from the native
 * records: the target components and their bases, the arguments pointer, and each projection extracted through the
 * file's own declared binding ({@link McpProtocol}) and validated against the pinned definition with only the required
 * members it declares missing relaxed. An {@code mcp} or an error the profile does not expect is a population defect: a
 * target or a protocol object the source does not establish must not appear.
 */
final class McpChecks {

    private McpChecks() {
    }

    static List<String> expectedKeys(ExpectedFacts facts) {
        List<String> out = new ArrayList<>();
        facts.mcp.forEach(m -> out.add(m.recordIndex() + ":" + m.id() + ":" + m.activityId()));
        return out;
    }

    static List<String> actualKeys(List<Event> events) {
        List<String> out = new ArrayList<>();
        for (Event e : events) {
            if (e.get("mcp") != null) {
                out.add(e.recordIndex() + ":" + e.text("tool_call_uid") + ":" + e.activityId);
            }
        }
        return out;
    }

    static Event find(List<Event> events, int recordIndex, String id, int activity) {
        for (Event e : events) {
            if (e.is("tool_call", activity) && Objects.equals(e.recordIndex(), recordIndex) && Objects.equals(e.text("tool_call_uid"), id)) {
                return e;
            }
        }
        return null;
    }

    /** Problems of one expected MCP observation on its event. */
    static List<String> compare(ExpectedFacts.Mcp m, Event e, Map<String, McpProtocol.Binding> bindings) {
        String what = m.recordIndex() + ":" + m.id();
        Json mcp = e.get("mcp");
        List<String> problems = new ArrayList<>();
        stated(what + " target_server_name", m.server(), SchemaCatalog.text(mcp, "target_server_name"), problems);
        stated(what + " target_server_basis_id", m.serverBasis(), Event.integer(mcp.member("target_server_basis_id")), problems);
        stated(what + " target_tool_name", m.tool(), SchemaCatalog.text(mcp, "target_tool_name"), problems);
        stated(what + " target_tool_basis_id", m.toolBasis(), Event.integer(mcp.member("target_tool_basis_id")), problems);
        stated(what + " target_arguments_pointer", m.argumentsPointer(), SchemaCatalog.text(mcp, "target_arguments_pointer"), problems);
        List<Json> objects = mcp.member("protocol_objects") == null ? List.of() : mcp.member("protocol_objects").elements();
        if (objects.size() != m.projections().size()) {
            problems.add(what + " protocol_objects " + objects.size() + " != " + m.projections().size());
            return problems;
        }
        for (int i = 0; i < objects.size(); i++) {
            problems.addAll(projection(what + " protocol_objects[" + i + "]", m.projections().get(i), objects.get(i), bindings));
        }
        return problems;
    }

    private static List<String> projection(String what, ExpectedFacts.McpProjection expected, Json actual, Map<String, McpProtocol.Binding> bindings) {
        List<String> problems = new ArrayList<>();
        McpProtocol.Extracted x;
        try {
            x = McpProtocol.extractProtocolObject(bindings, actual);
        }
        catch (RuntimeException ex) {
            problems.add(what + " cannot be extracted through the declared binding: " + ex.getMessage());
            return problems;
        }
        stated(what + " protocol_definition", expected.definition(), x.definition(), problems);
        stated(what + " projection_id", 2, x.projection(), problems);
        stated(what + " missing_required", expected.missingRequired(), x.missingRequired(), problems);
        if (!McpProtocol.sameJson(expected.value(), x.value())) {
            problems.add(what + " projected " + x.value() + " != " + expected.value());
        }
        List<String> invalid = McpProtocol.validate(bindings.get(x.revision()), x.definition(), x.value(), Set.copyOf(x.missingRequired()));
        if (!invalid.isEmpty()) {
            problems.add(what + " does not validate as the declared projection of " + x.definition() + ": " + invalid);
        }
        return problems;
    }

    static List<String> expectedErrorKeys(ExpectedFacts facts) {
        List<String> out = new ArrayList<>();
        facts.toolErrors.forEach(t -> out.add(t.recordIndex() + ":" + t.id() + ":" + t.layer()));
        return out;
    }

    static List<String> actualErrorKeys(List<Event> events) {
        List<String> out = new ArrayList<>();
        for (Event e : events) {
            Json errors = e.get("tool_result") == null ? null : e.get("tool_result").member("errors");
            for (Json err : errors == null ? List.<Json>of() : errors.elements()) {
                out.add(e.recordIndex() + ":" + e.text("tool_call_uid") + ":" + Event.integer(err.member("error_layer_id")));
            }
        }
        return out;
    }

    /** Problems of one expected tool error on the result event that reports it. */
    static List<String> compareError(ExpectedFacts.ToolError t, Event e) {
        String what = t.recordIndex() + ":" + t.id();
        List<String> problems = new ArrayList<>();
        Json errors = e.get("tool_result") == null ? null : e.get("tool_result").member("errors");
        Json match = null;
        for (Json err : errors == null ? List.<Json>of() : errors.elements()) {
            if (Objects.equals(Event.integer(err.member("error_layer_id")), t.layer())) {
                match = err;
            }
        }
        if (match == null) {
            problems.add(what + " no error of layer " + t.layer());
            return problems;
        }
        stated(what + " message", t.message(), SchemaCatalog.text(match, "message"), problems);
        Json code = match.member("error_code");
        boolean same = t.code() == null ? code == null : code != null && McpProtocol.sameJson(t.code(), code);
        if (!same) {
            problems.add(what + " error_code " + code + " != " + t.code());
        }
        return problems;
    }

    private static void stated(String what, Object expected, Object actual, List<String> problems) {
        if (!Objects.equals(expected, actual)) {
            problems.add(what + " " + actual + " != " + (expected == null ? "absent (the source profile states none)" : expected));
        }
    }
}
