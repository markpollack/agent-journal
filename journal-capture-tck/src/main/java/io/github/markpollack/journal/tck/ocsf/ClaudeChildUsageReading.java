package io.github.markpollack.journal.tck.ocsf;

import io.github.markpollack.journal.tck.verify.Json;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The oracle's own reading of child-run usage that Claude Code reports in the parent's records, from the native
 * fields alone: the Agent tool's result, whose {@code tool_use_result.usage} has the shape of one Messages API
 * response's usage (Agent SDK 0.3.304 {@code AgentOutput.usage}, with that response's {@code iterations[]}) and so
 * measures one response of the child run its call spawned (aggregation 3 Single response), and the Agent SDK 0.3.304
 * task messages, whose {@code usage.total_tokens} is the spawned task's own token figure (task_progress so far,
 * task_notification at the end; no aggregation, it is neither a total nor a running total of the child's usage). Each
 * is a session_context accounting fact (activity 3) at scope 7 Child run naming the spawning call.
 */
final class ClaudeChildUsageReading {

    static final String AGENT_TOOL = "Agent";

    /** Tool-use id to requested tool name, over every assistant record of the artifact. */
    private final Map<String, String> requested = new LinkedHashMap<>();

    ClaudeChildUsageReading(NativeArtifact artifact) {
        for (NativeArtifact.Record r : artifact.records()) {
            Json content = r.parsed() && "assistant".equals(SourceProfile.text(r.json(), "type")) ? r.json().path("message", "content") : null;
            for (Json b : content == null ? List.<Json>of() : content.elements()) {
                if ("tool_use".equals(SourceProfile.text(b, "type"))) {
                    requested.put(SourceProfile.text(b, "id"), SourceProfile.text(b, "name"));
                }
            }
        }
    }

    /** A user record with tool_result blocks: the Agent result's child-run accounting, when exactly one block answers an Agent request. */
    void agentResult(ExpectedFacts f, int index, Json record, List<String> resultIds) {
        Json result = record.member("tool_use_result");
        Json usage = result == null ? null : result.member("usage");
        if (resultIds.size() != 1 || !AGENT_TOOL.equals(requested.get(resultIds.get(0))) || !(usage instanceof Json.JObject)) {
            return;
        }
        account(f, index, "user/tool_result", resultIds.get(0));
        f.usages.add(new ExpectedFacts.Usage(index, index, 7, 2, TrancheReading.anthropic(usage), null, null, emitterDefaults(usage) ? 2 : null, 3));
        // the envelope's own members (Agent SDK 0.3.304 AgentOutput): the child run's reported terminal status and its
        // total duration in milliseconds are the accounting event's reported_state and duration; the child's status is
        // never the outer tool call's status (that stays the result block's is_error)
        if (result.member("status") instanceof Json.JString) {
            f.attributes.add(new ExpectedFacts.Attribute(index, "session_context", 3, "reported_state", result.member("status")));
        }
        if (SourceProfile.isIntegral(result.member("totalDurationMs"))) {
            f.attributes.add(new ExpectedFacts.Attribute(index, "session_context", 3, "duration", result.member("totalDurationMs")));
        }
    }

    /**
     * Whether the Agent result's usage is indistinguishable from the emitter's zero defaults (2 Possibly default): input,
     * cache creation, cache read and output all reported as 0, and no {@code iterations} entry. The retained 2.1.304
     * emitter lays the last metered response's usage over exactly these defaults and keeps them when there is none, so
     * such zeros establish neither a response nor a measured zero.
     */
    static boolean emitterDefaults(Json usage) {
        for (String member : List.of("input_tokens", "cache_creation_input_tokens", "cache_read_input_tokens", "output_tokens")) {
            if (!(usage.member(member) instanceof Json.JNumber n) || n.value().signum() != 0) {
                return false;
            }
        }
        return !(usage.member("iterations") instanceof Json.JArray entries) || entries.elements().isEmpty();
    }

    /** A task_progress or task_notification record that reports a usage object; returns whether it does. */
    boolean task(ExpectedFacts f, int index, Json record, String family) {
        if (!(record.member("usage") instanceof Json.JObject usage)) {
            return false;
        }
        f.normalized.add(index);
        account(f, index, family, SourceProfile.text(record, "tool_use_id"));
        f.usages.add(new ExpectedFacts.Usage(index, index, 7, "system/task_notification".equals(family) ? 2 : 1, TrancheReading.claudeTaskUsage(usage), null, null, null, null));
        return true;
    }

    private static void account(ExpectedFacts f, int index, String kind, String spawn) {
        f.contexts.add(new ExpectedFacts.Context(index, 3, kind, null, Map.of(), null, null));
        if (spawn != null) {
            f.contextToolCalls.put(index + ":" + kind, spawn);
        }
    }
}
