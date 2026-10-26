package io.github.markpollack.journal.tck.ocsf;

import io.github.markpollack.journal.tck.verify.Json;

import java.util.Map;
import java.util.function.BiFunction;

/**
 * Permitted Journal rule identifiers, selected from native discriminators and the independently
 * compared event type.
 */
final class NativeRule {
    private static final Map<String, BiFunction<Json, Event, String>> READERS =
            Map.ofEntries(
                    Map.entry("claude_stream", (n, e) -> claude("claude.stream.", n, e)),
                    Map.entry("claude_transcript", (n, e) -> claude("claude.transcript.", n, e)),
                    Map.entry("codex_stream", NativeRule::codex),
                    Map.entry("codex_rollout", NativeRule::rollout),
                    Map.entry("agy_stream", NativeRule::antigravity),
                    Map.entry("copilot_events", NativeRule::copilot),
                    Map.entry("copilot_otel", NativeRule::telemetry),
                    Map.entry(
                            "copilot_usage_report",
                            (n, e) -> "copilot.usage_report->session_context.accounting"),
                    Map.entry(
                            "junie_summary",
                            (n, e) ->
                                    "junie.summary->session_context." + contextStage(e.activityId)),
                    Map.entry("junie_stdout", NativeRule::junieStdout),
                    Map.entry("junie_session_events", NativeRule::junieSession),
                    Map.entry("opaque_text", (n, e) -> "common.opaque->source_record"));
    private static final Map<String, String> COPILOT =
            Map.ofEntries(
                    Map.entry("session.start", "session_start->session_context.settings"),
                    Map.entry("user.message", "user_message->prompt_turn.submitted"),
                    Map.entry("system.message", "system_message->session_context.context"),
                    Map.entry("tool.execution_start", "tool_execution_start->tool_call.started"),
                    Map.entry(
                            "tool.execution_complete", "tool_execution_complete->tool_call.result"),
                    Map.entry(
                            "tool.execution_partial_result",
                            "tool_execution_partial_result->tool_call.result"),
                    Map.entry("session.usage_record", "usage_record->model_response"),
                    Map.entry("session.shutdown", "shutdown->session_context.accounting"),
                    Map.entry("result", "result->session_context.outcome"));

    private NativeRule() {}

    static String expected(String profile, NativeArtifact.Record record, Event event) {
        if (!record.parsed()) {
            return "codex_stream".equals(profile) && "tool_call".equals(event.className)
                    ? "codex.stream.web_search->tool_call."
                            + (event.activityId == 2 ? "result" : "requested")
                    : "common.opaque->source_record";
        }
        BiFunction<Json, Event, String> reader = READERS.get(profile);
        return reader == null ? null : reader.apply(record.json(), event);
    }

    private static String codex(Json n, Event event) {
        return switch (event.className) {
            case "source_record" -> "codex.stream.fallback->source_record";
            case "session_context" ->
                    event.activityId == 5
                            ? "codex.stream.todo_list->session_context.state"
                            : "codex.stream.failure->session_context.outcome";
            case "prompt_turn" -> "codex.stream.turn_completed->prompt_turn.completed";
            default ->
                    "codex.stream."
                            + SourceProfile.text(n, "item", "type")
                            + "->"
                            + event.className
                            + ("tool_call".equals(event.className)
                                    ? "." + toolStage(event.activityId)
                                    : "");
        };
    }

    private static String claude(String prefix, Json n, Event event) {
        return prefix
                + switch (event.className) {
                    case "source_record" -> "fallback->source_record";
                    case "model_response" -> "assistant->model_response";
                    case "tool_call" ->
                            event.activityId == 1
                                    ? "assistant.tool_use->tool_call.requested"
                                    : "user.tool_result->tool_call.result";
                    case "prompt_turn" ->
                            event.activityId == 1
                                    ? "user.prompt->prompt_turn.submitted"
                                    : "result->prompt_turn.completed";
                    default -> claudeContext(n, event);
                };
    }

    private static String claudeContext(Json n, Event event) {
        if (event.activityId == 5) {
            return "system.task_state->session_context.state";
        }
        return switch (String.valueOf(SourceProfile.text(n, "type"))) {
            case "api-request-shape" -> "api_shape->session_context.settings";
            case "attachment" -> "attachment->session_context.context";
            case "cost-state" -> "cost_state->session_context.accounting";
            case "result" -> "result.model_cost->session_context.accounting";
            case "user" -> "user.tool_result.agent_usage->session_context.accounting";
            case "system" ->
                    "init".equals(SourceProfile.text(n, "subtype"))
                            ? "system.init->session_context.settings"
                            : "system.task_usage->session_context.accounting";
            default -> null;
        };
    }

    private static String rollout(Json n, Event event) {
        if ("source_record".equals(event.className)) {
            return "codex.rollout.fallback->source_record";
        }
        String suffix =
                switch (event.className) {
                    case "tool_call" -> "." + toolStage(event.activityId);
                    case "prompt_turn" -> "." + turnStage(event.activityId);
                    case "session_context" -> "." + contextStage(event.activityId);
                    default -> "";
                };
        return "codex.rollout." + rolloutFamily(n) + "->" + event.className + suffix;
    }

    private static String rolloutFamily(Json n) {
        String type = SourceProfile.text(n, "type");
        if (!"response_item".equals(type) && !"event_msg".equals(type)) {
            return type;
        }
        String item = SourceProfile.text(n, "payload", "type");
        if ("message".equals(item)) {
            return SourceProfile.text(n, "payload", "role") + "_message";
        }
        if (!"item_completed".equals(item)) {
            return item;
        }
        String completed = SourceProfile.text(n, "payload", "item", "type");
        return "CommandExecution".equals(completed) ? completed : "item_completed." + completed;
    }

    private static String toolStage(int activity) {
        return activity == 3 ? "operation" : activity == 2 ? "result" : "requested";
    }

    private static String turnStage(int activity) {
        return activity == 1 ? "submitted" : activity == 2 ? "completed" : "started";
    }

    private static String contextStage(int activity) {
        return switch (activity) {
            case 1 -> "settings";
            case 2 -> "context";
            case 4 -> "outcome";
            default -> "accounting";
        };
    }

    private static String antigravity(Json n, Event event) {
        String rule =
                switch (event.className) {
                    case "source_record" -> "fallback->source_record";
                    case "session_context" -> "init->session_context.settings";
                    case "prompt_turn" ->
                            event.activityId == 1
                                    ? "user_input->prompt_turn.submitted"
                                    : "result->prompt_turn.completed";
                    case "model_response" ->
                            event.activityId == 1
                                    ? "agent_response.active->model_response.partial"
                                    : "agent_response.done->model_response.item";
                    default ->
                            event.activityId == 4
                                    ? "tool.active->tool_call.started"
                                    : SourceProfile.text(n, "step_update", "step_type")
                                            + ".done->tool_call.result";
                };
        return "agy.stream." + rule;
    }

    private static String copilot(Json n, Event event) {
        if ("source_record".equals(event.className)) {
            return "copilot.events.fallback->source_record";
        }
        String type = String.valueOf(SourceProfile.text(n, "type"));
        String family = COPILOT.getOrDefault(type, "session_settings->session_context.settings");
        if ("assistant.message".equals(type)) {
            family =
                    "tool_call".equals(event.className)
                            ? "tool_request->tool_call.requested"
                            : "assistant_message->model_response";
        }
        if ("session.usage_checkpoint".equals(type)) {
            family =
                    "model_response".equals(event.className)
                            ? "last_call_usage->model_response"
                            : "usage_checkpoint->session_context.accounting";
        }
        return "copilot.events." + family;
    }

    private static String telemetry(Json n, Event event) {
        if ("source_record".equals(event.className)) {
            return "copilot.otel.fallback->source_record";
        }
        String family =
                switch (event.className) {
                    case "model_response" -> "chat->model_response";
                    case "tool_call" -> "execute_tool->tool_call.result";
                    default ->
                            "invoke_agent->session_context."
                                    + (event.activityId == 1 ? "settings" : "accounting");
                };
        return "copilot.otel." + family;
    }

    private static String junieStdout(Json n, Event event) {
        if ("source_record".equals(event.className)) {
            return "junie.stdout.fallback->source_record";
        }
        String stage =
                "model_response".equals(event.className)
                        ? "final_answer"
                        : contextStage(event.activityId);
        return "junie.stdout."
                + SourceProfile.text(n, "type")
                + "->"
                + event.className
                + "."
                + stage;
    }

    private static String junieSession(Json n, Event event) {
        return "junie.session."
                + switch (event.className) {
                    case "source_record" -> "fallback->source_record";
                    case "tool_call" -> "step_block->tool_call.operation";
                    case "session_context" ->
                            event.activityId == 5
                                    ? "state->session_context.state"
                                    : "completion->session_context.accounting";
                    default ->
                            event.activityId == 3
                                    ? "result_block->model_response.final_answer"
                                    : "llm_response_metadata->model_response";
                };
    }
}
