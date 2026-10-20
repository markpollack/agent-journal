package io.github.markpollack.journal.tck.ocsf;

import io.github.markpollack.journal.tck.verify.Json;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Claude Code {@code --output-format stream-json} live output. Assistant lines are model responses (one block per
 * line, {@code stop_reason} null until the last), their {@code tool_use} blocks are requests, user lines with
 * {@code tool_result} blocks are results whose owner is the block's own {@code tool_use_id} (a permission decision
 * is taken from the {@code tool_result_meta} entry with that id), and the {@code result} line completes the turn with
 * run and per-model final usage, a cost total and run-scope denials. Everything else is retained whole. An owner
 * scope ({@code parent_tool_use_id}) selects the main thread or one sub-agent.
 */
final class ClaudeStreamProfile implements SourceProfile {

    @Override
    public ExpectedFacts expected(NativeArtifact artifact, Json source, Json scope) {
        ExpectedFacts f = new ExpectedFacts(SourceProfile.text(source, "artifact"));
        for (NativeArtifact.Record r : artifact.records()) {
            Json o = r.json();
            if (!r.parsed()) {
                if (scope == null || SourceProfile.owned(null, scope)) {
                    f.owned.add(r.index()); // opaque lines belong to the main thread
                }
                continue;
            }
            if (!SourceProfile.owned(o, scope)) {
                continue;
            }
            f.owned.add(r.index());
            String ts = SourceProfile.text(o, "timestamp");
            if (ts != null) {
                f.nativeTime.put(r.index(), ts);
            }
            String type = SourceProfile.text(o, "type");
            Json message = o.member("message");
            if ("assistant".equals(type) && message != null) {
                f.normalized.add(r.index());
                String id = SourceProfile.text(message, "id");
                String stop = SourceProfile.text(message, "stop_reason");
                List<Json> blocks = message.member("content") == null ? null : message.member("content").elements();
                f.responses.add(new ExpectedFacts.Response(r.index(), id, blocks, stop, stop != null, SourceProfile.text(message, "model")));
                Json usage = message.member("usage");
                if (usage != null) {
                    f.usages.add(new ExpectedFacts.Usage(r.index(), r.index(), 2, stop == null ? 1 : 2, SourceProfile.components(usage,
                            "input_tokens", "input_tokens", "output_tokens", "output_tokens",
                            "cache_read_input_tokens", "cache_read_tokens", "cache_creation_input_tokens", "cache_write_tokens"),
                            SourceProfile.text(message, "model")));
                }
                for (Json b : blocks == null ? List.<Json>of() : blocks) {
                    if ("tool_use".equals(SourceProfile.text(b, "type"))) {
                        f.requests.add(new ExpectedFacts.Request(r.index(), SourceProfile.text(b, "id"), SourceProfile.text(b, "name"),
                                b.member("input"), id, null));
                    }
                }
            }
            else if ("user".equals(type) && message != null && hasToolResult(message)) {
                f.normalized.add(r.index());
                for (Json b : message.member("content").elements()) {
                    if (!"tool_result".equals(SourceProfile.text(b, "type"))) {
                        continue;
                    }
                    String id = SourceProfile.text(b, "tool_use_id");
                    ExpectedFacts.Decision decision = null;
                    for (Json meta : o.member("tool_result_meta") == null ? List.<Json>of() : o.member("tool_result_meta").elements()) {
                        Json pd = meta.member("permission_decision");
                        if (id != null && id.equals(SourceProfile.text(meta, "id")) && pd instanceof Json.JObject) {
                            String reported = SourceProfile.text(pd, "decision");
                            decision = new ExpectedFacts.Decision(r.index(), reported, SourceProfile.decisionId(reported),
                                    SourceProfile.text(pd, "source"), SourceProfile.text(pd, "reason_type"), 1, 1, 1, List.of(id), id);
                        }
                    }
                    f.results.add(new ExpectedFacts.Result(r.index(), id, b.member("content"), b.member("is_error"),
                            o.member("tool_use_result"), decision, null));
                    if (decision != null) {
                        f.decisions.add(decision);
                    }
                }
            }
            else if ("result".equals(type)) {
                f.normalized.add(r.index());
                boolean success = "success".equals(SourceProfile.text(o, "subtype")) && o.member("is_error") instanceof Json.JBool b && !b.value();
                f.completed.add(new ExpectedFacts.TurnCompleted(r.index(), success ? 1 : 2, SourceProfile.text(o, "stop_reason")));
                Json usage = o.member("usage");
                Map<String, Json> run = SourceProfile.components(usage, "input_tokens", "input_tokens", "output_tokens", "output_tokens",
                        "cache_read_input_tokens", "cache_read_tokens", "cache_creation_input_tokens", "cache_write_tokens");
                Json thinking = usage == null ? null : usage.path("output_tokens_details", "thinking_tokens");
                if (SourceProfile.isIntegral(thinking)) {
                    run.put("reasoning_tokens", thinking);
                }
                f.usages.add(new ExpectedFacts.Usage(r.index(), r.index(), 4, 2, run, null));
                Json modelUsage = o.member("modelUsage");
                if (modelUsage instanceof Json.JObject mu) {
                    for (Map.Entry<String, Json> m : mu.members().entrySet()) {
                        f.usages.add(new ExpectedFacts.Usage(r.index(), r.index(), 6, 2, SourceProfile.components(m.getValue(),
                                "inputTokens", "input_tokens", "outputTokens", "output_tokens", "cacheReadInputTokens", "cache_read_tokens",
                                "cacheCreationInputTokens", "cache_write_tokens", "thinkingTokens", "reasoning_tokens"), m.getKey()));
                    }
                }
                if (o.member("total_cost_usd") instanceof Json.JNumber) {
                    f.costs.add(new ExpectedFacts.Cost(r.index(), o.member("total_cost_usd"), "USD", 4));
                }
                for (Json d : o.member("permission_denials") == null ? List.<Json>of() : o.member("permission_denials").elements()) {
                    f.decisions.add(new ExpectedFacts.Decision(r.index(), "denied", 2, null, null, 4, 3, 2, new ArrayList<>(), null));
                }
            }
        }
        return f;
    }

    static boolean hasToolResult(Json message) {
        Json content = message.member("content");
        if (!(content instanceof Json.JArray)) {
            return false;
        }
        for (Json b : content.elements()) {
            if ("tool_result".equals(SourceProfile.text(b, "type"))) {
                return true;
            }
        }
        return false;
    }
}
