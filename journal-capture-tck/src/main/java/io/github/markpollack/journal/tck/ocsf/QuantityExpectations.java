package io.github.markpollack.journal.tck.ocsf;

import io.github.markpollack.journal.tck.verify.Json;
import java.util.Map;

/** Native-reader definitions, independent of the producer's quantity selection and binding code. */
final class QuantityExpectations {
    private QuantityExpectations() {}

    private static final Map<String, String> CONCEPTS = Map.ofEntries(
            Map.entry("contextWindow", "model.context_capacity"), Map.entry("maxOutputTokens", "model.output_limit"),
            Map.entry("size", "context.token_capacity"), Map.entry("context_token_limit", "context.token_capacity"), Map.entry("maxPromptTokens", "context.token_capacity"),
            Map.entry("used", "context.tokens_used"), Map.entry("context_current_tokens", "context.tokens_used"), Map.entry("currentTokens", "context.tokens_used"),
            Map.entry("percentage", "context.percent_used"), Map.entry("duration_api_ms", "duration.api"), Map.entry("totalApiDurationMs", "duration.api"),
            Map.entry("duration_seconds", "duration.elapsed"), Map.entry("sessionDurationMs", "duration.elapsed"),
            Map.entry("ttft_ms", "latency.first_token"), Map.entry("timeToFirstTokenMs", "latency.first_token"), Map.entry("ttft_stream_ms", "latency.first_stream_frame"),
            Map.entry("outputTtftMs", "latency.first_output_token"), Map.entry("interTokenLatencyMs", "latency.inter_token"),
            Map.entry("time_to_request_ms", "latency.request_dispatch"), Map.entry("first_content_frame_ms", "latency.first_content_frame"), Map.entry("dispatchDurationMs", "duration.model_dispatch"),
            Map.entry("calls", "model.request_count"), Map.entry("count", "model.request_count"), Map.entry("num_turns", "execution.turn_count"),
            Map.entry("totalUserRequests", "user.request_count"), Map.entry("numToolCalls", "tool.invocation_count"), Map.entry("tool_uses", "tool.invocation_count"), Map.entry("totalToolUseCount", "tool.invocation_count"),
            Map.entry("webSearchRequests", "server.web_search_count"), Map.entry("web_search_requests", "server.web_search_count"), Map.entry("web_fetch_requests", "server.web_fetch_count"),
            Map.entry("linesAdded", "change.lines_added"), Map.entry("linesRemoved", "change.lines_removed"), Map.entry("filesChanged", "change.files_changed"),
            Map.entry("availableToolCount", "tool.available_count"), Map.entry("requestBodyBytes", "request.body_size"), Map.entry("requestBodyWireBytes", "request.wire_size"),
            Map.entry("cacheTtlSeconds", "cache.retention"), Map.entry("lastCallInputTokens", "usage.reported_input"), Map.entry("lastCallOutputTokens", "usage.output"));

    static void bind(Map<String, Json> fields, String pointer, int scope) {
        String leaf = pointer.substring(pointer.lastIndexOf('/') + 1);
        String concept = CONCEPTS.get(leaf);
        if (leaf.equals("count") && !pointer.endsWith("/requests/count")) concept = null;
        fields.put("/concept", FactComparison.value(concept));
        if (concept == null) { fields.put("/measurement_window", null); return; }
        String window = switch (scope) {
            case 2 -> "model_request";
            case 5 -> "session_cumulative";
            case 6 -> "model_cumulative";
            case 7 -> pointer.startsWith("/usage/") ? "task_tracker_snapshot" : "child_result";
            default -> concept.startsWith("latency.") ? "run_initial_response" : "run_cumulative";
        };
        if (pointer.startsWith("/step_update/")) window = "conversation_step";
        if (pointer.contains("lastCall")) window = "most_recent_request";
        if (pointer.contains("/stats/")) window = "patch";
        if (concept.startsWith("context.")) window = "context_snapshot";
        if (leaf.equals("contextWindow") || leaf.equals("maxOutputTokens")) window = "configuration_snapshot";
        fields.put("/measurement_window", FactComparison.value(window));
        if (leaf.equals("linesAdded") || leaf.equals("linesRemoved")) fields.put("/metric_unit", FactComparison.value("line"));
    }
}
