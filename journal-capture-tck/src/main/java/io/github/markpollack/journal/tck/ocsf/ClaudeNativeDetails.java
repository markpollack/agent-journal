package io.github.markpollack.journal.tck.ocsf;

import io.github.markpollack.journal.tck.verify.Json;

import java.util.List;
import java.util.Map;

/**
 * Exact Claude native payload and metadata expectations; child usage remains separately attributed.
 */
final class ClaudeNativeDetails {
    private ClaudeNativeDetails() {}

    static void read(ExpectedFacts f, int record, Json n, String profile) {
        tools(f, record, n);
        permissions(f, record, n);
        run(f, record, n);
        child(f, record, n);
        costs(f, record, n, profile);
    }

    private static void tools(ExpectedFacts f, int record, Json n) {
        Json content = n.path("message", "content");
        List<Json> blocks = content instanceof Json.JArray a ? a.elements() : List.of();
        for (int i = 0; i < blocks.size(); i++) {
            Json block = blocks.get(i);
            if ("tool_use".equals(SourceProfile.text(block, "type"))) {
                int kind = claudeKind(SourceProfile.text(block, "name"));
                Map<String, Json> fields =
                        SourceFacts.fields("/tool_kind_id", kind, "/data", block);
                if (kind == 99) {
                    fields.put("/tool_kind", FactComparison.value("other"));
                }
                new SourceFacts.Target(f, record, "tool_call", 1)
                        .event(
                                "/message/content/" + i,
                                "source.native_details/v1",
                                SourceFacts.fields("/tool_call_uid", block.member("id")),
                                fields);
            }
        }
        if ("user".equals(SourceProfile.text(n, "type"))
                && blocks.stream()
                        .noneMatch(b -> "tool_result".equals(SourceProfile.text(b, "type")))) {
            NativeContent.text(
                    new SourceFacts.Target(f, record, "prompt_turn", 1),
                    content,
                    "string",
                    "/message/content");
        }
    }

    private static void permissions(ExpectedFacts f, int record, Json n) {
        Json metadata = n.member("tool_result_meta");
        List<Json> decisions = metadata == null ? List.of() : metadata.elements();
        for (int i = 0; i < decisions.size(); i++) {
            Json decision = decisions.get(i);
            if (decision.member("permission_decision") != null) {
                new SourceFacts.Target(f, record, "tool_call", 2)
                        .event(
                                "/tool_result_meta/" + i,
                                "source.native_details/v1",
                                SourceFacts.fields("/tool_call_uid", decision.member("id")),
                                SourceFacts.fields("/permission_decision/data", decision));
            }
        }
        if (n.member("permissionDecision") != null
                && f.decisions.stream().anyMatch(d -> d.recordIndex() == record)) {
            new SourceFacts.Target(f, record, "tool_call", 2)
                    .event(
                            "/permissionDecision",
                            "source.native_details/v1",
                            Map.of(),
                            SourceFacts.fields(
                                    "/permission_decision/data", n.member("permissionDecision")));
        }
    }

    private static void run(ExpectedFacts f, int record, Json n) {
        if ("result".equals(SourceProfile.text(n, "type"))) {
            NativeContent.text(
                    new SourceFacts.Target(f, record, "prompt_turn", 2),
                    n.member("result"),
                    "string",
                    "/result");
            Json denied = n.member("permission_denials");
            List<Json> decisionsAtRun = denied == null ? List.of() : denied.elements();
            if (decisionsAtRun.isEmpty()) {
                new SourceFacts.Target(f, record, "prompt_turn", 2)
                        .event(
                                "/permission_denials",
                                "source.native_details/v1",
                                Map.of(),
                                SourceFacts.fields("/permission_decisions", null));
            }
            for (int i = 0; i < decisionsAtRun.size(); i++) {
                new SourceFacts.Target(f, record, "prompt_turn", 2)
                        .event(
                                "/permission_denials/" + i,
                                "source.native_details/v1",
                                Map.of(),
                                SourceFacts.fields(
                                        "/permission_decisions/" + i + "/data",
                                        decisionsAtRun.get(i)));
            }
            if (f.costs.stream().anyMatch(c -> c.recordIndex() == record && c.model() != null)) {
                new SourceFacts.Target(f, record, "session_context", 3)
                        .event(
                                "/modelUsage",
                                "source.native_details/v1",
                                Map.of(),
                                SourceFacts.fields("/data", n.member("modelUsage")));
            }
        }
    }

    private static void child(ExpectedFacts f, int record, Json n) {
        if (n.path("tool_use_result", "usage") != null
                && f.contexts.stream().anyMatch(c -> c.recordIndex() == record)) {
            new SourceFacts.Target(f, record, "session_context", 3)
                    .event(
                            "/tool_use_result",
                            "source.native_details/v1",
                            Map.of(),
                            SourceFacts.fields("/data", n.member("tool_use_result")));
        }
        if (n.member("tool_use_result") instanceof Json.JObject child
                && f.contexts.stream().anyMatch(c -> c.recordIndex() == record)) {
            if (child.member("status") != null) {
                new SourceFacts.Target(f, record, "session_context", 3)
                        .event(
                                "/tool_use_result/status",
                                "source.native_details/v1",
                                Map.of(),
                                SourceFacts.fields("/reported_state", child.member("status")));
            }
            if (child.member("totalDurationMs") != null) {
                new SourceFacts.Target(f, record, "session_context", 3)
                        .event(
                                "/tool_use_result/totalDurationMs",
                                "source.native_details/v1",
                                Map.of(),
                                SourceFacts.fields("/duration", child.member("totalDurationMs")));
            }
        }
    }

    private static void costs(ExpectedFacts f, int record, Json n, String profile) {
        f.costs.stream()
                .filter(c -> c.recordIndex() == record)
                .forEach(cost -> cost(f, record, n, profile, cost));
    }

    private static String costKey(ExpectedFacts.Cost cost, String profile) {
        if (cost.model() != null) {
            return "costUSD";
        }
        return "claude_transcript".equals(profile) ? "totalCostUSD" : "total_cost_usd";
    }

    private static void cost(
            ExpectedFacts f, int record, Json n, String profile, ExpectedFacts.Cost cost) {
        String key = costKey(cost, profile);
        String support =
                cost.model() == null
                        ? "/" + key
                        : "/modelUsage/" + FactComparison.escape(cost.model()) + "/costUSD";
        String cls =
                cost.model() == null
                                && f.completed.stream().anyMatch(t -> t.recordIndex() == record)
                        ? "prompt_turn"
                        : "session_context";
        Map<String, Json> fields =
                SourceFacts.fields(
                        "/data",
                        new Json.JObject(Map.of(key, cost.amount())),
                        "/total_cost",
                        cost.amount());
        if (cost.model() != null) {
            fields.put("/model_ref/provider_kind", n.path("modelUsage", cost.model(), "provider"));
        }
        new SourceFacts.Target(f, record, cls, "prompt_turn".equals(cls) ? 2 : 3)
                .observation(
                        cost.model() == null ? "/cost_observation" : "/cost_observations",
                        support,
                        "source.cost_payload/v1",
                        cost.model() == null
                                ? Map.of()
                                : SourceFacts.fields("/model_ref/name", cost.model()),
                        fields);
    }

    private static final Map<String, Integer> CLAUDEKIND =
            Map.ofEntries(
                    Map.entry("Read", 1),
                    Map.entry("NotebookRead", 1),
                    Map.entry("Edit", 2),
                    Map.entry("MultiEdit", 2),
                    Map.entry("Write", 2),
                    Map.entry("NotebookEdit", 2),
                    Map.entry("Glob", 5),
                    Map.entry("Grep", 5),
                    Map.entry("Bash", 6),
                    Map.entry("WebFetch", 8),
                    Map.entry("WebSearch", 8));

    static int claudeKind(String name) {
        return CLAUDEKIND.getOrDefault(String.valueOf(name), 99);
    }
}
