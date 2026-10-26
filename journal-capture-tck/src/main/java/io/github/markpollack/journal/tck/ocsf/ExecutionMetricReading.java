package io.github.markpollack.journal.tck.ocsf;

import io.github.markpollack.journal.tck.verify.Json;

import java.util.List;
import java.util.Map;

/**
 * Independent expectations for reported execution quantities. No measurement or summability is
 * inferred.
 */
final class ExecutionMetricReading {
    private ExecutionMetricReading() {}

    static void read(ExpectedFacts facts, NativeArtifact artifact, String profile) {
        for (NativeArtifact.Record record : artifact.records()) {
            if (!record.parsed() || !facts.owned.contains(record.index())) {
                continue;
            }
            if ("claude_stream".equals(profile)) {
                claude(facts, record);
            }
            if ("agy_stream".equals(profile)) {
                antigravity(facts, record);
            }
            if ("claude_transcript".equals(profile)) {
                limits(facts, record);
            }
        }
    }

    private static void claude(ExpectedFacts facts, NativeArtifact.Record record) {
        Json n = record.json();
        child(facts, record);
        if ("result".equals(SourceProfile.text(n, "type"))) {
            Reading reader =
                    new Reading(new SourceFacts.Target(facts, record.index(), "prompt_turn", 2));
            for (String field :
                    List.of(
                            "duration_api_ms",
                            "ttft_ms",
                            "ttft_stream_ms",
                            "time_to_request_ms",
                            "first_content_frame_ms")) {
                reader.quantity(n.member(field), "/" + field, "ms", 4, null);
            }
            reader.quantity(n.member("num_turns"), "/num_turns", "count", 4, null);
            reader.counters(n.path("usage", "server_tool_use"), "/usage/server_tool_use");
            reader.counters(n.member("subagent_stats"), "/subagent_stats");
            if (n.member("modelUsage") instanceof Json.JObject models) {
                models.members()
                        .forEach(
                                (model, usage) ->
                                        reader.quantity(
                                                usage.member("webSearchRequests"),
                                                "/modelUsage/"
                                                        + FactComparison.escape(model)
                                                        + "/webSearchRequests",
                                                "count",
                                                6,
                                                model));
            }
        }
        if (List.of("task_progress", "task_notification")
                .contains(String.valueOf(SourceProfile.text(n, "subtype")))) {
            new Reading(new SourceFacts.Target(facts, record.index(), "session_context", 3))
                    .quantity(n.path("usage", "tool_uses"), "/usage/tool_uses", "count", 7, null);
        }
    }

    private static void child(ExpectedFacts facts, NativeArtifact.Record record) {
        Json result = record.json().member("tool_use_result");
        if (result == null
                || facts.contexts.stream()
                        .noneMatch(
                                c ->
                                        c.recordIndex() == record.index()
                                                && "user/tool_result".equals(c.contextKind()))) {
            return;
        }
        SourceFacts.Target target =
                new SourceFacts.Target(facts, record.index(), "session_context", 3);
        Map<String, String> fields =
                Map.of(
                        "agentId",
                        "agent_uid",
                        "agentType",
                        "agent_type",
                        "prompt",
                        "task_description",
                        "resolvedModel",
                        "model_ref/name");
        fields.forEach(
                (nativeName, canonicalName) ->
                        facts.facts.add(
                                new ExpectedFacts.Fact(
                                        record.index(),
                                        "session_context",
                                        3,
                                        Map.of(),
                                        null,
                                        Map.of(),
                                        SourceFacts.fields(
                                                "/" + canonicalName, result.member(nativeName)),
                                        List.of("/tool_use_result/" + nativeName),
                                        "source.child_metadata/v1")));
        Reading reader = new Reading(target);
        reader.quantity(
                result.member("totalToolUseCount"),
                "/tool_use_result/totalToolUseCount",
                "count",
                7,
                null);
        childCounters(reader, result.member("toolStats"), "/tool_use_result/toolStats");
        childCounters(
                reader,
                result.path("usage", "server_tool_use"),
                "/tool_use_result/usage/server_tool_use");
    }

    private static void childCounters(Reading reader, Json object, String pointer) {
        if (object instanceof Json.JObject values) {
            values.members()
                    .forEach(
                            (name, value) ->
                                    reader.quantity(
                                            value,
                                            pointer + "/" + FactComparison.escape(name),
                                            "count",
                                            7,
                                            null));
        }
    }

    private static void limits(ExpectedFacts facts, NativeArtifact.Record record) {
        if (!"cost-state".equals(SourceProfile.text(record.json(), "type"))
                || !(record.json().member("modelUsage") instanceof Json.JObject models)) {
            return;
        }
        Reading reader =
                new Reading(new SourceFacts.Target(facts, record.index(), "session_context", 3));
        models.members()
                .forEach(
                        (model, usage) -> {
                            for (String field : List.of("contextWindow", "maxOutputTokens")) {
                                reader.quantity(
                                        usage.member(field),
                                        "/modelUsage/" + FactComparison.escape(model) + "/" + field,
                                        "token",
                                        6,
                                        model);
                            }
                        });
    }

    private static void antigravity(ExpectedFacts facts, NativeArtifact.Record record) {
        Json n = record.json();
        if (n.member("result") instanceof Json.JObject result) {
            Reading reader =
                    new Reading(new SourceFacts.Target(facts, record.index(), "prompt_turn", 2));
            reader.quantity(
                    result.member("duration_seconds"), "/result/duration_seconds", "s", 5, null);
            reader.quantity(result.member("num_turns"), "/result/num_turns", "count", 5, null);
        }
        if (n.member("step_update") instanceof Json.JObject step) {
            new Reading(stepTarget(facts, record.index(), step))
                    .quantity(
                            step.member("duration_seconds"),
                            "/step_update/duration_seconds",
                            "s",
                            99,
                            null);
        }
    }

    private static SourceFacts.Target stepTarget(ExpectedFacts facts, int record, Json step) {
        String type = SourceProfile.text(step, "step_type");
        boolean active = "ACTIVE".equals(SourceProfile.text(step, "state"));
        return switch (String.valueOf(type)) {
            case "user_input" -> new SourceFacts.Target(facts, record, "prompt_turn", 1);
            case "agent_response" ->
                    new SourceFacts.Target(facts, record, "model_response", active ? 1 : 3);
            case "tool", "subagent" ->
                    new SourceFacts.Target(facts, record, "tool_call", active ? 4 : 2);
            default -> new SourceFacts.Target(facts, record, "source_record", 1);
        };
    }

    record Reading(SourceFacts.Target target) {
        void quantity(Json value, String pointer, String unit, int scope, String model) {
            if (!(value instanceof Json.JNumber)) {
                return;
            }
            Map<String, Json> values =
                    SourceFacts.fields(
                            "/reported_value",
                            value,
                            "/metric_unit",
                            unit,
                            "/scope_id",
                            scope,
                            "/measurement_status_id",
                            0,
                            "/native_pointers",
                            List.of(pointer),
                            "/model_ref/name",
                            model);
            if (scope == 99) {
                values.put("/scope", FactComparison.value("Source observation"));
            }
            target.observation(
                    "/execution_metrics",
                    pointer,
                    "source.execution_quantity/v1",
                    SourceFacts.fields("/metric_name", pointer.substring(1)),
                    values);
        }

        void counters(Json value, String pointer) {
            if (!(value instanceof Json.JObject object)) {
                return;
            }
            object.members()
                    .forEach(
                            (name, member) -> {
                                String at = pointer + "/" + FactComparison.escape(name);
                                if (member instanceof Json.JObject) {
                                    counters(member, at);
                                } else {
                                    quantity(
                                            member,
                                            at,
                                            name.contains("depth") ? "level" : "count",
                                            4,
                                            null);
                                }
                            });
        }
    }
}
