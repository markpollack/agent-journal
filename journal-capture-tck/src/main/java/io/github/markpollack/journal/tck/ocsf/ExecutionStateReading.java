package io.github.markpollack.journal.tck.ocsf;

import io.github.markpollack.journal.tck.verify.Json;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Native task/plan/failure and collaboration facts, independent of the writer's rule selection and
 * output.
 */
final class ExecutionStateReading {
    private ExecutionStateReading() {}

    static void read(ExpectedFacts facts, NativeArtifact artifact, String profile) {
        for (NativeArtifact.Record record : artifact.records()) {
            if (!record.parsed() || !facts.owned.contains(record.index())) {
                continue;
            }
            switch (profile) {
                case "claude_stream" -> claude(facts, record);
                case "claude_transcript" -> transcript(facts, record);
                case "codex_stream" -> codex(facts, record);
                case "agy_stream" -> antigravity(facts, record);
                default -> {}
            }
        }
    }

    private static void transcript(ExpectedFacts facts, NativeArtifact.Record record) {
        Json n = record.json();
        String type = SourceProfile.text(n, "type");
        boolean shape = "api-request-shape".equals(type);
        if (!shape && !"attachment".equals(type)) {
            return;
        }
        Json tools = shape ? n.path("shape", "tools") : n.path("attachment", "tools");
        if (!shape && !(tools instanceof Json.JArray)) {
            return;
        }
        transcriptTools(facts, record, n, type, tools, shape);
    }

    private static void transcriptTools(
            ExpectedFacts facts,
            NativeArtifact.Record record,
            Json n,
            String type,
            Json tools,
            boolean shape) {
        Json definitions =
                tools instanceof Json.JArray ? new Json.JArray(definitions(tools, shape)) : null;
        Map<String, Json> fields = SourceFacts.fields("/tool_definitions", definitions);
        if (shape) {
            context(target(facts, record, "session_context", 1), type, null, null);
            fields.putAll(SourceFacts.fields("/context_kind", type, "/data", n));
        }
        target(facts, record, "session_context", shape ? 1 : 2)
                .event(
                        shape ? "/shape/tools" : "/attachment/tools",
                        "source.tool_declarations/v1",
                        Map.of(),
                        fields);
    }

    private static List<Json> definitions(Json tools, boolean shape) {
        List<Json> definitions = new ArrayList<>();
        for (Json tool : tools.elements()) {
            Map<String, Json> fields =
                    new LinkedHashMap<>(
                            ((Json.JObject)
                                            object(
                                                    "name",
                                                    tool.member("name"),
                                                    "tool_description",
                                                    tool.member("description"),
                                                    "definition_format_id",
                                                    2,
                                                    "definition_format",
                                                    "Function declaration",
                                                    "schema_hash",
                                                    tool.member("hash"),
                                                    "data",
                                                    tool))
                                    .members());
            Json schema = tool.member(shape ? "input_schema" : "schema");
            if (schema instanceof Json.JObject) {
                fields.put("input_schema", object("schema_document", schema));
            }
            definitions.add(new Json.JObject(fields));
        }
        return definitions;
    }

    private static void claude(ExpectedFacts facts, NativeArtifact.Record record) {
        Json n = record.json();
        String subtype = SourceProfile.text(n, "subtype");
        if ("init".equals(subtype) && n.member("mcp_servers") instanceof Json.JArray servers) {
            List<Json> connections = new ArrayList<>();
            for (Json server : servers.elements()) {
                connections.add(
                        object(
                                "element_index",
                                connections.size(),
                                "name",
                                server.member("name"),
                                "reported_state",
                                server.member("status"),
                                "configuration_source",
                                server.member("source")));
            }
            Json expectedConnections = new Json.JArray(connections);
            facts.contexts.replaceAll(
                    context -> {
                        if (context.recordIndex() != record.index() || context.activityId() != 1) {
                            return context;
                        }
                        Map<String, Json> settings = new LinkedHashMap<>(context.settings());
                        settings.put("server_connections", expectedConnections);
                        return new ExpectedFacts.Context(
                                context.recordIndex(),
                                context.activityId(),
                                context.contextKind(),
                                context.turnUid(),
                                settings,
                                context.renderedRole(),
                                context.blocks());
                    });
            target(facts, record, "session_context", 1)
                    .event(
                            "/mcp_servers",
                            "source.server_connections/v1",
                            Map.of(),
                            SourceFacts.fields(
                                    "/settings/server_connections", expectedConnections));
        }
        if (List.of("task_progress", "task_notification").contains(String.valueOf(subtype))) {
            target(facts, record, "session_context", 3)
                    .event(
                            "",
                            "source.task_metadata/v1",
                            Map.of(),
                            SourceFacts.fields(
                                    "/task_description",
                                    n.member("description"),
                                    "/agent_type",
                                    n.member("subagent_type"),
                                    "/task_uid",
                                    n.member("task_id")));
        }
        if (!List.of("task_started", "task_updated").contains(String.valueOf(subtype))) {
            return;
        }
        Map<String, Json> fields =
                SourceFacts.fields(
                        "/context_kind",
                        subtype,
                        "/task_uid",
                        n.member("task_id"),
                        "/tool_call_uid",
                        n.member("tool_use_id"),
                        "/reported_state",
                        n.path("patch", "status"),
                        "/end_time",
                        n.path("patch", "end_time"),
                        "/data",
                        n);
        fields.putAll(
                SourceFacts.fields(
                        "/task_description",
                        n.member("description"),
                        "/agent_type",
                        n.member("subagent_type")));
        fields.put("/content_blocks", prompt(n.member("prompt")));
        context(
                target(facts, record, "session_context", 5),
                subtype,
                n.member("prompt"),
                n.member("tool_use_id"));
        target(facts, record, "session_context", 5)
                .event("", "source.task_state/v1", Map.of(), fields);
    }

    private static void codex(ExpectedFacts facts, NativeArtifact.Record record) {
        Json n = record.json();
        Json item = n.member("item");
        String type = SourceProfile.text(n, "type");
        String itemType = SourceProfile.text(item, "type");
        if ("todo_list".equals(itemType) || "error".equals(itemType)) {
            facts.nativeUid.put(record.index(), SourceProfile.text(item, "id"));
        }
        if ("todo_list".equals(itemType)) {
            context(target(facts, record, "session_context", 5), "todo_list", null, null);
            target(facts, record, "session_context", 5)
                    .event(
                            "/item",
                            "source.plan/v1",
                            Map.of(),
                            SourceFacts.fields(
                                    "/context_kind",
                                    "todo_list",
                                    "/reported_state",
                                    type,
                                    "/plan_items",
                                    ordered(
                                            item.member("items"),
                                            "text",
                                            "text",
                                            "reported_completed",
                                            "completed"),
                                    "/data",
                                    n));
        }
        if ("turn.failed".equals(type) || "error".equals(type) || "error".equals(itemType)) {
            context(target(facts, record, "session_context", 4), type, null, null);
            Json error = "turn.failed".equals(type) ? n.member("error") : item == null ? n : item;
            Map<String, Json> fields =
                    SourceFacts.fields(
                            "/context_kind",
                            type,
                            "/reported_state",
                            type,
                            "/status_id",
                            2,
                            "/status_basis_id",
                            1,
                            "/data",
                            n);
            fields.putAll(errors(error));
            target(facts, record, "session_context", 4)
                    .event("", "source.execution_failure/v1", Map.of(), fields);
        }
        tool(facts, record, item, type, itemType);
    }

    private static void tool(
            ExpectedFacts facts,
            NativeArtifact.Record record,
            Json item,
            String type,
            String itemType) {
        if (!List.of("item.started", "item.completed").contains(String.valueOf(type))) {
            return;
        }
        SourceFacts.Target tool =
                target(facts, record, "tool_call", "item.started".equals(type) ? 4 : 2);
        if ("file_change".equals(itemType)) {
            tool.event(
                    "/item/changes",
                    "source.file_changes/v1",
                    SourceFacts.fields("/tool_call_uid", item.member("id")),
                    SourceFacts.fields(
                            "/file_changes",
                            ordered(
                                    item.member("changes"),
                                    "file_path",
                                    "path",
                                    "change_kind",
                                    "kind")));
        }
        if ("collab_tool_call".equals(itemType)) {
            collaboration(tool, item);
        }
    }

    private static void collaboration(SourceFacts.Target target, Json item) {
        List<Json> states = new ArrayList<>();
        if (item.member("agents_states") instanceof Json.JObject object) {
            object.members()
                    .forEach(
                            (session, state) ->
                                    states.add(
                                            object(
                                                    "session_uid",
                                                    session,
                                                    "reported_state",
                                                    state.member("status"),
                                                    "text",
                                                    state.member("message"))));
        }
        target.event(
                "/item",
                "source.collaboration/v1",
                SourceFacts.fields("/tool_call_uid", item.member("id")),
                SourceFacts.fields(
                        "/sender_session_uid",
                        item.member("sender_thread_id"),
                        "/recipient_session_uids",
                        item.member("receiver_thread_ids"),
                        "/content_blocks",
                        prompt(item.member("prompt")),
                        "/agent_states",
                        item.member("agents_states") instanceof Json.JObject
                                ? new Json.JArray(states)
                                : null));
    }

    private static void antigravity(ExpectedFacts facts, NativeArtifact.Record record) {
        Json error = record.json().path("result", "error");
        if (error != null && !(error instanceof Json.JNull)) {
            target(facts, record, "prompt_turn", 2)
                    .event("/result/error", "source.execution_failure/v1", Map.of(), errors(error));
        }
    }

    private static Map<String, Json> errors(Json value) {
        return SourceFacts.fields(
                "/errors/0/error_layer_id",
                4,
                "/errors/0/message",
                value instanceof Json.JString ? value : value.member("message"),
                "/errors/0/data",
                value);
    }

    private static Json ordered(
            Json values, String field1, String native1, String field2, String native2) {
        List<Json> out = new ArrayList<>();
        for (Json value : values == null ? List.<Json>of() : values.elements()) {
            out.add(
                    object(
                            "element_index",
                            out.size(),
                            field1,
                            value.member(native1),
                            field2,
                            value.member(native2)));
        }
        return new Json.JArray(out);
    }

    private static Json prompt(Json value) {
        if (!(value instanceof Json.JString)) {
            return null;
        }
        return new Json.JArray(
                List.of(
                        object(
                                "block_index",
                                0,
                                "block_type",
                                "string",
                                "block_kind_id",
                                1,
                                "block_kind",
                                "Text",
                                "text",
                                value,
                                "data",
                                value)));
    }

    private static Json object(Object... pairs) {
        Map<String, Json> out = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            Json value = FactComparison.value(pairs[i + 1]);
            if (value != null) {
                out.put((String) pairs[i], value);
            }
        }
        return new Json.JObject(out);
    }

    private static void context(SourceFacts.Target target, String kind, Json text, Json toolId) {
        List<ExpectedFacts.Block> blocks =
                text instanceof Json.JString
                        ? List.of(new ExpectedFacts.Block("string", text.text(), text))
                        : null;
        target.f()
                .contexts
                .add(
                        new ExpectedFacts.Context(
                                target.record(),
                                target.activity(),
                                kind,
                                null,
                                Map.of(),
                                null,
                                blocks));
        if (toolId != null) {
            target.f().contextToolCalls.put(target.record() + ":" + kind, toolId.text());
        }
    }

    private static SourceFacts.Target target(
            ExpectedFacts facts, NativeArtifact.Record record, String cls, int activity) {
        return new SourceFacts.Target(facts, record.index(), cls, activity);
    }
}
