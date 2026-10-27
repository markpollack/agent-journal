package io.github.markpollack.journal.ocsf.writer;

/** Finite bindings for already selected native quantities; source names remain provenance. */
final class QuantityMeaning {
    private static final java.util.Map<String, String> CONCEPTS = java.util.Map.ofEntries(
            java.util.Map.entry("contextWindow", "model.context_capacity"),
            java.util.Map.entry("maxOutputTokens", "model.output_limit"),
            java.util.Map.entry("size", "context.token_capacity"),
            java.util.Map.entry("context_token_limit", "context.token_capacity"),
            java.util.Map.entry("maxPromptTokens", "context.token_capacity"),
            java.util.Map.entry("used", "context.tokens_used"),
            java.util.Map.entry("context_current_tokens", "context.tokens_used"),
            java.util.Map.entry("currentTokens", "context.tokens_used"),
            java.util.Map.entry("percentage", "context.percent_used"),
            java.util.Map.entry("duration_api_ms", "duration.api"),
            java.util.Map.entry("totalApiDurationMs", "duration.api"),
            java.util.Map.entry("duration_seconds", "duration.elapsed"),
            java.util.Map.entry("sessionDurationMs", "duration.elapsed"),
            java.util.Map.entry("ttft_ms", "latency.first_token"),
            java.util.Map.entry("timeToFirstTokenMs", "latency.first_token"),
            java.util.Map.entry("ttft_stream_ms", "latency.first_stream_frame"),
            java.util.Map.entry("outputTtftMs", "latency.first_output_token"),
            java.util.Map.entry("interTokenLatencyMs", "latency.inter_token"),
            java.util.Map.entry("time_to_request_ms", "latency.request_dispatch"),
            java.util.Map.entry("first_content_frame_ms", "latency.first_content_frame"),
            java.util.Map.entry("dispatchDurationMs", "duration.model_dispatch"),
            java.util.Map.entry("calls", "model.request_count"),
            java.util.Map.entry("num_turns", "execution.turn_count"),
            java.util.Map.entry("totalUserRequests", "user.request_count"),
            java.util.Map.entry("numToolCalls", "tool.invocation_count"),
            java.util.Map.entry("tool_uses", "tool.invocation_count"),
            java.util.Map.entry("totalToolUseCount", "tool.invocation_count"),
            java.util.Map.entry("webSearchRequests", "server.web_search_count"),
            java.util.Map.entry("web_search_requests", "server.web_search_count"),
            java.util.Map.entry("web_fetch_requests", "server.web_fetch_count"),
            java.util.Map.entry("linesAdded", "change.lines_added"),
            java.util.Map.entry("linesRemoved", "change.lines_removed"),
            java.util.Map.entry("filesChanged", "change.files_changed"),
            java.util.Map.entry("availableToolCount", "tool.available_count"),
            java.util.Map.entry("requestBodyBytes", "request.body_size"),
            java.util.Map.entry("requestBodyWireBytes", "request.wire_size"),
            java.util.Map.entry("cacheTtlSeconds", "cache.retention"),
            java.util.Map.entry("lastCallInputTokens", "usage.reported_input"),
            java.util.Map.entry("lastCallOutputTokens", "usage.output"));

    private QuantityMeaning() {}

    static void bind(Jv.Obj metric, String pointer, int scope) {
        if (pointer.startsWith("/dataPoints/")) return; // exporter histograms retain their own declared windows and units
        String name = pointer.substring(pointer.lastIndexOf('/') + 1);
        String concept = name.equals("count")
                ? (pointer.endsWith("/requests/count") ? "model.request_count" : null)
                : CONCEPTS.get(name);
        if (concept == null) return;
        metric.put("concept", concept).put("measurement_window", window(pointer, concept, scope));
        if (name.equals("linesAdded") || name.equals("linesRemoved")) metric.put("metric_unit", "line");
    }

    private static String window(String pointer, String concept, int scope) {
        if (concept.startsWith("model.context") || concept.equals("model.output_limit")) return "configuration_snapshot";
        if (concept.startsWith("context.")) return "context_snapshot";
        if (pointer.contains("/stats/")) return "patch";
        if (pointer.contains("lastCall")) return "most_recent_request";
        if (pointer.startsWith("/step_update/")) return "conversation_step";
        return subjectWindow(pointer, concept, scope);
    }

    private static String subjectWindow(String pointer, String concept, int scope) {
        return switch (scope) {
            case 6 -> "model_cumulative";
            case 7 -> pointer.startsWith("/usage/") ? "task_tracker_snapshot" : "child_result";
            case 2 -> "model_request";
            case 5 -> "session_cumulative";
            default -> concept.startsWith("latency.") ? "run_initial_response" : "run_cumulative";
        };
    }
}
