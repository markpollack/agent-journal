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
        McpReadings.Claude mcp = new McpReadings.Claude();
        for (NativeArtifact.Record r : artifact.records()) {
            Json o = r.json();
            if (!r.parsed()) {
                if (scope == null || SourceProfile.owned(null, scope)) {
                    f.owned.add(r.index()); // opaque lines belong to the main thread
                }
                continue;
            }
            if ("system".equals(SourceProfile.text(o, "type")) && "init".equals(SourceProfile.text(o, "subtype"))) {
                mcp.init(o); // registration is a fact of the session, whichever core owns the record
            }
            if (!SourceProfile.owned(o, scope)) {
                continue;
            }
            f.owned.add(r.index());
            if (SourceProfile.text(o, "session_id") != null) {
                f.sessionOf.put(r.index(), SourceProfile.text(o, "session_id"));
            }
            if (SourceProfile.text(o, "uuid") != null) {
                f.nativeUid.put(r.index(), SourceProfile.text(o, "uuid"));
            }
            String ts = SourceProfile.text(o, "timestamp");
            if (ts != null) {
                f.nativeTime.put(r.index(), ts);
            }
            String type = SourceProfile.text(o, "type");
            Json message = o.member("message");
            if ("assistant".equals(type) && message != null) {
                f.normalized.add(r.index());
                String id = SourceProfile.text(message, "id");
                for (Json b : SourceProfile.anthropicMessage(f, r.index(), message)) {
                    if ("tool_use".equals(SourceProfile.text(b, "type"))) {
                        f.requests.add(new ExpectedFacts.Request(r.index(), SourceProfile.text(b, "id"), SourceProfile.text(b, "name"),
                                b.member("input"), id, null));
                        mcp.request(f, r.index(), b);
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
                    mcp.result(f, r.index(), o, b);
                    if (decision != null) {
                        f.decisions.add(decision);
                    }
                }
            }
            else if ("user".equals(type) && message != null && message.member("content") != null) {
                // a user record without a tool_result block: the prompt the stream reports for this scope (observed in child scopes)
                f.normalized.add(r.index());
                Json content = message.member("content");
                List<String> texts = content instanceof Json.JString s ? List.of(s.value()) : CodexRolloutProfile.texts(content.elements());
                f.prompts.add(new ExpectedFacts.Prompt(r.index(), null, texts));
            }
            else if ("system".equals(type) && "init".equals(SourceProfile.text(o, "subtype"))) {
                f.normalized.add(r.index());
                Map<String, Json> settings = ClaudeTranscriptProfile.settings(o, "cwd", "working_directory", "model", "model",
                        "permissionMode", "permission_mode", "claude_code_version", "version", "tools", "tool_names");
                f.contexts.add(new ExpectedFacts.Context(r.index(), 1, "system/init", null, settings, null, null));
            }
            else if ("result".equals(type)) {
                f.normalized.add(r.index());
                boolean success = "success".equals(SourceProfile.text(o, "subtype")) && o.member("is_error") instanceof Json.JBool b && !b.value();
                f.completed.add(new ExpectedFacts.TurnCompleted(r.index(), success ? 1 : 2, SourceProfile.text(o, "stop_reason")));
                Json usage = o.member("usage");
                // whether the result's usage covers the run's sub-agents is not established: no aggregation is expected
                f.usages.add(new ExpectedFacts.Usage(r.index(), r.index(), 4, 2, TrancheReading.anthropic(usage), null, null, null, null));
                Json modelUsage = o.member("modelUsage");
                if (modelUsage instanceof Json.JObject mu) {
                    for (Map.Entry<String, Json> m : mu.members().entrySet()) {
                        // ModelUsage is Claude Code's running total per model: a cumulative snapshot
                        f.usages.add(new ExpectedFacts.Usage(r.index(), r.index(), 6, 2, TrancheReading.claudeModelUsage(m.getValue()), m.getKey(),
                                SourceProfile.text(m.getValue(), "provider"), null, 2));
                    }
                }
                if (o.member("total_cost_usd") instanceof Json.JNumber) {
                    String basis = null;
                    if (modelUsage instanceof Json.JObject mu && mu.members().size() == 1) {
                        basis = SourceProfile.text(mu.members().values().iterator().next(), "costBasis");
                    }
                    f.costs.add(new ExpectedFacts.Cost(r.index(), o.member("total_cost_usd"), "USD", 4, basis));
                }
                for (Json d : o.member("permission_denials") == null ? List.<Json>of() : o.member("permission_denials").elements()) {
                    String deniedCall = SourceProfile.text(d, "tool_use_id");
                    // the entry names the denied call (2.1.304): a reported identifier, unique; otherwise unattributed, never linked by position
                    f.decisions.add(deniedCall != null
                            ? new ExpectedFacts.Decision(r.index(), "denied", 2, null, null, 4, 1, 1, new ArrayList<>(List.of(deniedCall)), null)
                            : new ExpectedFacts.Decision(r.index(), "denied", 2, null, null, 4, 3, 2, new ArrayList<>(), null));
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
