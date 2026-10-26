package io.github.markpollack.journal.tck.ocsf;

import io.github.markpollack.journal.tck.verify.Json;

import java.util.List;
import java.util.Map;

/**
 * Independent reading of the frozen Junie 3813.7 summary, events and stdout source specifications.
 */
final class JunieProfile implements SourceProfile {

    private final String profile;

    JunieProfile(String profile) {
        this.profile = profile;
    }

    @Override
    public ExpectedFacts expected(NativeArtifact artifact, Json source, Json scope) {
        ExpectedFacts f = new ExpectedFacts(SourceProfile.text(source, "artifact"));
        f.directFacts = true;
        String session = null;
        for (NativeArtifact.Record record : artifact.records()) {
            f.owned.add(record.index());
            if (!record.parsed()) {
                continue;
            }
            Json nativeRecord = record.json();
            if (SourceProfile.text(nativeRecord, "sessionId") != null) {
                session = SourceProfile.text(nativeRecord, "sessionId");
            }
            if (session != null) {
                f.sessionOf.put(record.index(), session);
            }
            switch (profile) {
                case "junie_summary" -> summary(f, record.index(), nativeRecord);
                case "junie_session_events" -> sessionEvent(f, record.index(), nativeRecord);
                case "junie_stdout" -> stdout(f, record.index(), nativeRecord);
                default -> throw new IllegalArgumentException("unknown Junie profile " + profile);
            }
        }
        return f;
    }

    private static void summary(ExpectedFacts f, int record, Json n) {
        SourceFacts.time(f, record, n.member("updatedAt"));
        if (SourceProfile.text(n, "sessionId") != null) {
            f.nativeUid.put(record, SourceProfile.text(n, "sessionId"));
        }
        event(
                new SourceFacts.Target(f, record, "session_context", 1),
                "",
                "summary.settings",
                SourceFacts.fields("/settings/working_directory", n.member("projectDir")));
        event(
                new SourceFacts.Target(f, record, "session_context", 4),
                "",
                "summary.outcome",
                SourceFacts.fields("/reported_state", n.member("lifecycle")));
        event(
                new SourceFacts.Target(f, record, "session_context", 3),
                "/mainAgent/modelUsage",
                "summary.accounting",
                Map.of());
        usage(
                new SourceFacts.Target(f, record, "session_context", 3),
                n.path("mainAgent", "modelUsage"),
                "/mainAgent/modelUsage",
                6,
                2);
    }

    private static void stdout(ExpectedFacts f, int record, Json n) {
        SourceFacts.time(f, record, n.member("timestamp"));
        switch (String.valueOf(SourceProfile.text(n, "type"))) {
            case "session" ->
                    event(
                            new SourceFacts.Target(f, record, "session_context", 1),
                            "/sessionId",
                            "stdout.session",
                            Map.of());
            case "result" -> {
                Map<String, Json> fields = SourceFacts.textBlock(n.member("result"), "result");
                fields.put("/response_uid", null);
                fields.put("/item_uid", null);
                event(
                        new SourceFacts.Target(f, record, "model_response", 3),
                        "/result",
                        "stdout.answer",
                        fields);
                event(
                        new SourceFacts.Target(f, record, "session_context", 3),
                        "/errorCode",
                        "stdout.accounting",
                        Map.of());
                usage(
                        new SourceFacts.Target(f, record, "session_context", 3),
                        n.member("errorCode"),
                        "/errorCode",
                        6,
                        2);
            }
            default -> {}
        }
    }

    private static void sessionEvent(ExpectedFacts f, int record, Json n) {
        SourceFacts.time(f, record, n.member("timestampMs"));
        Json a = n.path("event", "agentEvent");
        String kind = a == null ? SourceProfile.text(n, "kind") : SourceProfile.text(a, "kind");
        if (List.of("TaskStartedEvent", "AgentCurrentStatusUpdatedEvent")
                .contains(String.valueOf(kind))) {
            event(
                    new SourceFacts.Target(f, record, "session_context", 5),
                    "",
                    "session.state",
                    SourceFacts.fields(
                            "/context_kind",
                            kind,
                            "/task_uid",
                            n.member("taskId"),
                            "/agent_uid",
                            a == null ? null : a.path("agent", "id"),
                            "/reported_state",
                            a == null ? null : a.member("status"),
                            "/data",
                            n));
        }
        if (a != null) {
            agentEvent(f, record, a);
        }
        if (n.path("completion", "taskCostUsd") instanceof Json.JNumber cost) {
            event(
                    new SourceFacts.Target(f, record, "session_context", 3),
                    "/completion",
                    "session.completion",
                    Map.of());
            new SourceFacts.Target(f, record, "session_context", 3)
                    .quantity(
                            "/completion/taskCostUsd",
                            "junie.completion.taskCostUsd/v1",
                            new SourceFacts.Quantity(cost, 1, null, "USD", 99, "Task", 1, null));
        }
    }

    private static void agentEvent(ExpectedFacts f, int record, Json a) {
        switch (String.valueOf(SourceProfile.text(a, "kind"))) {
            case "ToolBlockUpdatedEvent",
                    "ViewFilesBlockUpdatedEvent",
                    "TerminalBlockUpdatedEvent" ->
                    operation(f, record, a);
            case "LlmResponseMetadataEvent" -> {
                uncertainTime(f, record, a);
                event(
                        new SourceFacts.Target(f, record, "model_response", 2),
                        "/event/agentEvent",
                        "session.response",
                        SourceFacts.fields("/response_uid", null, "/item_uid", null));
                usage(
                        new SourceFacts.Target(f, record, "model_response", 2),
                        a.member("modelUsage"),
                        "/event/agentEvent/modelUsage",
                        2,
                        1);
            }
            case "ResultBlockUpdatedEvent" -> {
                Map<String, Json> fields = SourceFacts.textBlock(a.member("result"), "result");
                fields.putAll(
                        SourceFacts.fields(
                                "/item_uid",
                                a.member("stepId"),
                                "/identity_basis_id",
                                2,
                                "/reported_state",
                                a.member("errorCode"),
                                "/response_uid",
                                null));
                event(
                        new SourceFacts.Target(f, record, "model_response", 3),
                        "/event/agentEvent",
                        "session.answer",
                        fields);
            }
            default -> {}
        }
    }

    private static void uncertainTime(ExpectedFacts f, int record, Json a) {
        if (a.member("modelUsage") != null
                && a.member("modelUsage").elements().stream()
                        .anyMatch(m -> m.member("time") != null)) {
            f.unresolvedMeanings.put(
                    record,
                    "/event/agentEvent/modelUsage/*/time: the frozen empirical"
                            + " specification records zero but establishes neither unit nor"
                            + " whether zero is measured or a default; no duration is"
                            + " inferred");
        }
    }

    private static void operation(ExpectedFacts f, int record, Json a) {
        String kind = SourceProfile.text(a, "kind");
        String state = SourceProfile.text(a, "status");
        Map<String, Json> fields =
                SourceFacts.fields(
                        "/tool_operation/uid",
                        a.member("stepId"),
                        "/tool_operation/identity_basis_id",
                        2,
                        "/reported_state",
                        a.member("status"),
                        "/tool_family",
                        kind,
                        "/tool_operation/reported_state",
                        a.member("status"),
                        "/tool_operation/data",
                        a,
                        "/tool_operation/attribution/outcome_id",
                        3,
                        "/tool_operation/attribution/method_id",
                        2,
                        "/tool_operation/attribution/candidates",
                        List.of(),
                        "/status_basis_id",
                        "COMPLETED".equals(state) || "FAILED".equals(state) ? 1 : null,
                        "/status_id",
                        "COMPLETED".equals(state)
                                ? Integer.valueOf(1)
                                : "FAILED".equals(state) ? Integer.valueOf(2) : null);
        if ("TerminalBlockUpdatedEvent".equals(kind)) {
            fields.putAll(
                    SourceFacts.fields(
                            "/tool_operation/command_line",
                            a.member("command"),
                            "/tool_operation/exit_code",
                            a.member("exitCode"),
                            "/tool_operation/stdout",
                            a.member("output")));
        }
        event(
                new SourceFacts.Target(f, record, "tool_call", 3),
                "/event/agentEvent",
                "session.operation",
                fields);
    }

    private static void usage(
            SourceFacts.Target target, Json entries, String pointer, int scope, int aggregation) {
        List<Json> models = entries == null ? List.of() : entries.elements();
        for (int i = 0; i < models.size(); i++) {
            Json model = models.get(i);
            String name = SourceProfile.text(model, "model");
            new ExecutionMetricReading.Reading(target)
                    .quantity(
                            model.member("calls"),
                            pointer + "/" + i + "/calls",
                            "count",
                            scope,
                            name);
            target.usage(
                    pointer + "/" + i,
                    "junie.usage.3813.7/v1",
                    new SourceFacts.Usage(
                            model,
                            SourceFacts.tranches(
                                    model,
                                    new SourceFacts.TrancheNames(
                                            "inputTokens",
                                            "cacheInputTokens",
                                            "cacheCreateTokens",
                                            "outputTokens",
                                            null,
                                            false)),
                            scope,
                            2,
                            aggregation,
                            name,
                            null));
            target.quantity(
                    pointer + "/" + i + "/cost",
                    "junie.usage.unnamed_cost/v1",
                    new SourceFacts.Quantity(
                            model.member("cost"),
                            99,
                            "cost (unit not named by the source)",
                            null,
                            scope,
                            scope == 6 ? "Model" : "Model response",
                            aggregation,
                            name));
        }
    }

    private static void event(
            SourceFacts.Target target, String pointer, String meaning, Map<String, Json> fields) {
        target.event(pointer, "junie.3813.7." + meaning + "/v1", Map.of(), fields);
    }
}
