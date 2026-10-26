package io.github.markpollack.journal.ocsf.writer;

import java.util.List;
import java.util.Map;

/**
 * Exact native execution quantities, without rounding, inferred windows or arithmetic across
 * observations.
 */
final class ExecutionMetrics {
    private ExecutionMetrics() {}

    static Jv.Obj add(Jv.Obj event, NativeRecord record, SourceContext ctx) {
        if (!record.parsed()
                || !(event.path("source_ref", "is_whole_record") instanceof Jv.Bool whole)
                || !whole.value()) {
            return event;
        }
        Jv.Arr metrics = new Jv.Arr();
        Jv.Obj ref =
                new Jv.Obj(((Jv.Obj) event.member("source_ref")).members())
                        .put("is_whole_record", false);
        Reading reading = new Reading(metrics, ref);
        switch (ctx.source.profile()) {
            case "claude_stream" -> claude(reading, record.json());
            case "agy_stream" -> antigravity(reading, record.json());
            case "claude_transcript" -> limits(reading, record.json());
            default -> {}
        }
        if (!metrics.elements().isEmpty()) {
            event.put("execution_metrics", metrics);
        }
        return event;
    }

    private static void claude(Reading r, Jv n) {
        if ("result".equals(MapperSupport.text(n, "type"))) {
            for (String field :
                    List.of(
                            "duration_api_ms",
                            "ttft_ms",
                            "ttft_stream_ms",
                            "time_to_request_ms",
                            "first_content_frame_ms")) {
                r.number(n.member(field), "/" + field, "ms", 4, null);
            }
            r.number(n.member("num_turns"), "/num_turns", "count", 4, null);
            r.numbers(n.path("usage", "server_tool_use"), "/usage/server_tool_use", 4, null);
            r.numbers(n.member("subagent_stats"), "/subagent_stats", 4, null);
            if (n.member("modelUsage") instanceof Jv.Obj models) {
                models.members()
                        .forEach(
                                (name, value) ->
                                        r.number(
                                                value.member("webSearchRequests"),
                                                "/modelUsage/"
                                                        + escape(name)
                                                        + "/webSearchRequests",
                                                "count",
                                                6,
                                                name));
            }
        }
        if (List.of("task_progress", "task_notification")
                .contains(String.valueOf(MapperSupport.text(n, "subtype")))) {
            r.number(n.path("usage", "tool_uses"), "/usage/tool_uses", "count", 7, null);
        }
    }

    static Jv.Obj child(Jv.Obj event, Jv result) {
        Jv.Arr metrics = new Jv.Arr();
        Reading reader = new Reading(metrics, (Jv.Obj) event.member("source_ref"));
        reader.number(
                result.member("totalToolUseCount"),
                "/tool_use_result/totalToolUseCount",
                "count",
                7,
                null);
        reader.numbers(result.member("toolStats"), "/tool_use_result/toolStats", 7, null);
        reader.numbers(
                result.path("usage", "server_tool_use"),
                "/tool_use_result/usage/server_tool_use",
                7,
                null);
        if (!metrics.elements().isEmpty()) {
            event.put("execution_metrics", metrics);
        }
        return event;
    }

    private static void limits(Reading reader, Jv record) {
        if (!"cost-state".equals(MapperSupport.text(record, "type"))
                || !(record.member("modelUsage") instanceof Jv.Obj models)) {
            return;
        }
        models.members()
                .forEach(
                        (model, usage) -> {
                            for (String field : List.of("contextWindow", "maxOutputTokens")) {
                                reader.number(
                                        usage.member(field),
                                        "/modelUsage/" + escape(model) + "/" + field,
                                        "token",
                                        6,
                                        model);
                            }
                        });
    }

    private static void antigravity(Reading r, Jv n) {
        if (n.member("step_update") instanceof Jv.Obj step) {
            r.number(
                    step.member("duration_seconds"),
                    "/step_update/duration_seconds",
                    "s",
                    99,
                    null);
        }
        if (n.member("result") instanceof Jv.Obj result) {
            r.number(result.member("duration_seconds"), "/result/duration_seconds", "s", 5, null);
            r.number(result.member("num_turns"), "/result/num_turns", "count", 5, null);
        }
    }

    record Reading(Jv.Arr out, Jv.Obj source) {
        void number(Jv value, String pointer, String unit, int scope, String model) {
            if (!(value instanceof Jv.Num)) {
                return;
            }
            Jv.Obj metric =
                    new Jv.Obj()
                            .put("metric_name", pointer.substring(1))
                            .put("reported_value", value)
                            .put("metric_unit", unit)
                            .put("scope_id", scope)
                            .put(
                                    "scope",
                                    scope == 99
                                            ? "Source observation"
                                            : MapperSupport.SCOPE.get(scope))
                            .put("measurement_status_id", 0)
                            .put("source_ref", source)
                            .put("native_pointers", new Jv.Arr(List.of(new Jv.Str(pointer))));
            if (model != null) {
                metric.put("model_ref", MapperSupport.modelRef(model, 3, null));
            }
            out.add(metric);
        }

        void numbers(Jv object, String pointer, int scope, String model) {
            if (!(object instanceof Jv.Obj o)) {
                return;
            }
            for (Map.Entry<String, Jv> field : o.members().entrySet()) {
                String path = pointer + "/" + escape(field.getKey());
                if (field.getValue() instanceof Jv.Obj) {
                    numbers(field.getValue(), path, scope, model);
                } else {
                    number(
                            field.getValue(),
                            path,
                            field.getKey().contains("depth") ? "level" : "count",
                            scope,
                            model);
                }
            }
        }
    }

    static Jv.Obj copilot(Jv.Obj event, Jv snapshot, String pointer) {
        Jv.Arr metrics = new Jv.Arr();
        Reading reading =
                new Reading(
                        metrics,
                        new Jv.Obj(((Jv.Obj) event.member("source_ref")).members())
                                .put("is_whole_record", false));
        reading.number(
                snapshot.member("totalApiDurationMs"),
                pointer + "/totalApiDurationMs",
                "ms",
                5,
                null);
        if (snapshot.member("modelMetrics") instanceof Jv.Obj models) {
            models.members()
                    .forEach(
                            (model, value) ->
                                    reading.number(
                                            value.path("requests", "count"),
                                            pointer
                                                    + "/modelMetrics/"
                                                    + escape(model)
                                                    + "/requests/count",
                                            "count",
                                            6,
                                            model));
        }
        if (!metrics.elements().isEmpty()) event.put("execution_metrics", metrics);
        return event;
    }

    static Jv.Obj junieCalls(Jv.Obj event, Jv entries, String pointer, int scope) {
        Jv.Arr metrics = new Jv.Arr();
        Reading reading =
                new Reading(
                        metrics,
                        new Jv.Obj(((Jv.Obj) event.member("source_ref")).members())
                                .put("is_whole_record", false));
        List<Jv> models = entries == null ? List.of() : entries.elements();
        for (int i = 0; i < models.size(); i++) {
            Jv model = models.get(i);
            reading.number(
                    model.member("calls"),
                    pointer + "/" + i + "/calls",
                    "count",
                    scope,
                    MapperSupport.text(model, "model"));
        }
        if (!metrics.elements().isEmpty()) event.put("execution_metrics", metrics);
        return event;
    }

    private static String escape(String s) {
        return s.replace("~", "~0").replace("/", "~1");
    }
}
