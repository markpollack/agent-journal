package io.github.markpollack.journal.tck.ocsf;

import io.github.markpollack.journal.tck.verify.Json;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Reads the frozen Copilot 1.0.100 event sources independently, including stdout-only and
 * session-only records.
 */
final class CopilotProfile implements SourceProfile {

    private static final Set<String> SETTINGS =
            Set.of(
                    "session.start",
                    "session.model_change",
                    "session.auto_mode_resolved",
                    "session.tools_updated",
                    "session.mcp_servers_loaded",
                    "session.mcp_server_status_changed",
                    "session.mcp_server_tools_loaded");
    private final String profile;

    CopilotProfile(String profile) {
        this.profile = profile;
    }

    @Override
    public ExpectedFacts expected(NativeArtifact artifact, Json source, Json scope) {
        ExpectedFacts f = new ExpectedFacts(SourceProfile.text(source, "artifact"));
        f.directFacts = true;
        String session = null;
        Map<String, Json> starts = new LinkedHashMap<>();
        for (NativeArtifact.Record record : artifact.records()) {
            f.owned.add(record.index());
            if (!record.parsed()) {
                continue;
            }
            if ("copilot_usage_report".equals(profile)) {
                CopilotUsageReading.snapshot(f, record.index(), record.json(), "", 2);
            } else if ("copilot_otel".equals(profile)) {
                CopilotTelemetryReading.read(f, record.index(), record.json());
            } else {
                session = eventRecord(f, record.index(), record.json(), session, starts);
            }
        }
        return f;
    }

    private static String eventRecord(
            ExpectedFacts f, int index, Json n, String session, Map<String, Json> starts) {
        Json d = n.member("data") == null ? new Json.JObject(Map.of()) : n.member("data");
        String type = SourceProfile.text(n, "type");
        if ("session.start".equals(type)) {
            session = SourceProfile.text(d, "sessionId");
        }
        String ownSession = SourceProfile.text(n, "sessionId");
        if (ownSession != null || session != null) {
            f.sessionOf.put(index, ownSession != null ? ownSession : session);
        }
        SourceFacts.time(f, index, n.member("timestamp"));
        if (SourceProfile.text(n, "id") != null) {
            f.nativeUid.put(index, SourceProfile.text(n, "id"));
        }
        read(f, index, type, n, d, starts);
        return session;
    }

    private static void read(
            ExpectedFacts f, int index, String type, Json n, Json d, Map<String, Json> starts) {
        if (SETTINGS.contains(type)) {
            Json model = first(d, "chosenModel", "newModel", "model");
            event(
                    new SourceFacts.Target(f, index, "session_context", 1),
                    "/data",
                    "settings",
                    Map.of(),
                    SourceFacts.fields(
                            "/settings",
                            settings(d.path("context", "cwd"), d.member("copilotVersion"), model)));
            return;
        }
        if (Set.of(
                        "tool.execution_start",
                        "tool.execution_complete",
                        "tool.execution_partial_result")
                .contains(type)) {
            toolEvent(f, index, type, d, starts);
            return;
        }
        if (Set.of("session.usage_record", "session.usage_checkpoint", "session.shutdown")
                .contains(type)) {
            accounting(f, index, type, d);
            return;
        }
        switch (String.valueOf(type)) {
            case "user.message", "system.message" -> message(f, index, type, d);
            case "assistant.message" -> assistant(f, index, d);
            case "result" -> {
                event(
                        new SourceFacts.Target(f, index, "session_context", 4),
                        "",
                        "outcome",
                        Map.of(),
                        SourceFacts.fields(
                                "/exit_code", n.member("exitCode"), "/reported_state", "result"));
                new SourceFacts.Target(f, index, "session_context", 4)
                        .quantity(
                                "/usage/premiumRequests",
                                "copilot.premium_requests/v1",
                                new SourceFacts.Quantity(
                                        n.path("usage", "premiumRequests"),
                                        2,
                                        "premium requests",
                                        null,
                                        4,
                                        "Run",
                                        1,
                                        null));
            }
            default -> {}
        }
    }

    private static void accounting(ExpectedFacts f, int index, String type, Json d) {
        switch (type) {
            case "session.usage_record" -> CopilotUsageReading.call(f, index, d.member("usage"));
            case "session.usage_checkpoint", "session.shutdown" -> {
                Json snapshot =
                        d.member("accountingSnapshot") == null ? d : d.member("accountingSnapshot");
                CopilotUsageReading.snapshot(
                        f,
                        index,
                        snapshot,
                        "/data/accountingSnapshot",
                        "session.shutdown".equals(type) ? 2 : 1);
                if ("session.usage_checkpoint".equals(type)) {
                    CopilotUsageReading.lastCall(f, index, d, snapshot);
                }
            }
            default -> {}
        }
    }

    private static void toolEvent(
            ExpectedFacts f, int index, String type, Json d, Map<String, Json> starts) {
        switch (type) {
            case "tool.execution_start" -> {
                starts.put(SourceProfile.text(d, "toolCallId"), d);
                CopilotToolReading.read(f, index, d, d, 4);
            }
            case "tool.execution_complete" ->
                    CopilotToolReading.read(
                            f, index, d, starts.get(SourceProfile.text(d, "toolCallId")), 2);
            case "tool.execution_partial_result" ->
                    event(
                            new SourceFacts.Target(f, index, "tool_call", 2),
                            "/data",
                            "tool.partial_result",
                            SourceFacts.fields("/tool_call_uid", d.member("toolCallId")),
                            SourceFacts.fields(
                                    "/identity_basis_id",
                                    1,
                                    "/reported_state",
                                    type,
                                    "/status_id",
                                    0,
                                    "/status_basis_id",
                                    3,
                                    "/tool_result/data",
                                    d.member("partialOutput"),
                                    "/tool_result/error_reported",
                                    null));
            default -> {}
        }
    }

    private static Json settings(Json directory, Json version, Json model) {
        Map<String, Json> out = new LinkedHashMap<>();
        if (directory != null) {
            out.put("working_directory", directory);
        }
        if (version != null) {
            out.put("version", version);
        }
        if (model != null) {
            out.put("model", model);
        }
        return new Json.JObject(out);
    }

    private static Json first(Json d, String... names) {
        for (String name : names) {
            if (d.member(name) instanceof Json.JString) {
                return d.member(name);
            }
        }
        return null;
    }

    private static void message(ExpectedFacts f, int record, String type, Json d) {
        boolean user = "user.message".equals(type);
        Map<String, Json> fields = SourceFacts.textBlock(d.member("content"), type + ".content");
        fields.put("/turn_uid", d.member("interactionId"));
        if (!user) {
            fields.put("/context_kind", FactComparison.value("system message"));
            fields.put("/rendered_role", d.member("role"));
        }
        event(
                new SourceFacts.Target(
                        f, record, user ? "prompt_turn" : "session_context", user ? 1 : 2),
                "/data",
                type,
                Map.of(),
                fields);
    }

    private static void assistant(ExpectedFacts f, int record, Json d) {
        Map<String, Json> fields =
                SourceFacts.fields(
                        "/response_uid",
                        d.member("apiCallId"),
                        "/item_uid",
                        d.member("messageId"),
                        "/identity_basis_id",
                        99,
                        "/identity_basis",
                        "Provider call identifier (apiCallId)",
                        "/turn_uid",
                        d.member("interactionId"),
                        "/model_ref/name",
                        d.member("model"),
                        "/model_ref/provider_kind",
                        d.member("providerId"));
        if (SourceProfile.text(d, "content") != null
                && !SourceProfile.text(d, "content").isEmpty()) {
            fields.putAll(SourceFacts.textBlock(d.member("content"), "assistant.message.content"));
        } else {
            fields.put("/content_blocks", new Json.JArray(List.of()));
        }
        event(
                new SourceFacts.Target(f, record, "model_response", 2),
                "/data",
                "assistant.message",
                Map.of(),
                fields);
        List<Json> requests =
                d.member("toolRequests") == null ? List.of() : d.member("toolRequests").elements();
        for (int i = 0; i < requests.size(); i++) {
            Json request = requests.get(i);
            Map<String, Json> tool =
                    SourceFacts.fields(
                            "/tool_name",
                            request.member("name"),
                            "/arguments",
                            request.member("arguments"),
                            "/tool_family",
                            request.member("type"),
                            "/identity_basis_id",
                            1,
                            "/data",
                            request,
                            "/response_uid",
                            d.member("apiCallId"),
                            "/turn_uid",
                            d.member("interactionId"));
            event(
                    new SourceFacts.Target(f, record, "tool_call", 1),
                    "/data/toolRequests/" + i,
                    "tool.request",
                    SourceFacts.fields("/tool_call_uid", request.member("toolCallId")),
                    tool);
        }
    }

    static void event(
            SourceFacts.Target target,
            String pointer,
            String meaning,
            Map<String, Json> identity,
            Map<String, Json> fields) {
        target.event(pointer, "copilot.1.0.100." + meaning + "/v1", identity, fields);
    }
}
