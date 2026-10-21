package io.github.markpollack.journal.tck.ocsf;

import io.github.markpollack.journal.tck.verify.Json;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Claude Code saved session file, read whole by the oracle. Every record is owned. When the package declares a
 * bounded admission ({@code admit_response_uid}, the 0.1.0 prototype), records outside it are expected to be
 * declared excluded, not preserved; otherwise (complete admission) every record is expected: assistant records are
 * responses (terminal when they report a stop reason; the transcript repeats the message's usage on every block
 * record, so each record yields a usage observation) with their tool_use requests; user records without a
 * tool_result block are prompt submissions (turn id {@code promptId}); user records with tool_result blocks are
 * results, with the record-level permission decision when exactly one result is carried; attachments are injected
 * context ({@code attachment.type}); cost-state is session-scope accounting (per-model usage and a cost total);
 * every other family (queue-operation, atis-latch, api-request-shape, api-request-blob, api-request, last-prompt)
 * is expected as a whole-record fallback.
 */
final class ClaudeTranscriptProfile implements SourceProfile {

    @Override
    public ExpectedFacts expected(NativeArtifact artifact, Json source, Json scope) {
        ExpectedFacts f = new ExpectedFacts(SourceProfile.text(source, "artifact"));
        String admit = SourceProfile.text(source, "admit_response_uid");
        java.util.Set<String> admittedTools = new java.util.LinkedHashSet<>();
        boolean promptSeen = false;
        for (NativeArtifact.Record r : artifact.records()) {
            f.owned.add(r.index());
            if (!r.parsed()) {
                continue;
            }
            Json o = r.json();
            String type = SourceProfile.text(o, "type");
            Json message = o.member("message") instanceof Json.JObject m ? m : null;
            String ts = SourceProfile.text(o, "timestamp");
            if ("assistant".equals(type) && message != null) {
                if (admit != null && !admit.equals(SourceProfile.text(message, "id"))) {
                    f.owned.remove(r.index());
                    f.excluded.put(r.index(), type);
                    continue;
                }
                f.normalized.add(r.index());
                time(f, r, ts);
                String stop = SourceProfile.text(message, "stop_reason");
                List<Json> blocks = message.member("content") == null ? null : message.member("content").elements();
                f.responses.add(new ExpectedFacts.Response(r.index(), SourceProfile.text(message, "id"), blocks, stop, stop != null, SourceProfile.text(message, "model")));
                Json usage = message.member("usage");
                if (usage != null) {
                    Map<String, Json> c = SourceProfile.components(usage, "input_tokens", "input_tokens", "output_tokens", "output_tokens",
                            "cache_read_input_tokens", "cache_read_tokens", "cache_creation_input_tokens", "cache_write_tokens");
                    Json thinking = usage.path("output_tokens_details", "thinking_tokens");
                    if (SourceProfile.isIntegral(thinking)) {
                        c.put("reasoning_tokens", thinking);
                    }
                    f.usages.add(new ExpectedFacts.Usage(r.index(), r.index(), 2, stop == null ? 1 : 2, c, SourceProfile.text(message, "model")));
                }
                for (Json b : blocks == null ? List.<Json>of() : blocks) {
                    if ("tool_use".equals(SourceProfile.text(b, "type"))) {
                        admittedTools.add(SourceProfile.text(b, "id"));
                        f.requests.add(new ExpectedFacts.Request(r.index(), SourceProfile.text(b, "id"), SourceProfile.text(b, "name"),
                                b.member("input"), SourceProfile.text(message, "id"), null));
                    }
                }
                continue;
            }
            if ("user".equals(type) && message != null) {
                Json content = message.member("content");
                int resultBlocks = 0;
                for (Json b : content == null ? List.<Json>of() : content.elements()) {
                    if ("tool_result".equals(SourceProfile.text(b, "type"))) {
                        resultBlocks++;
                    }
                }
                if (resultBlocks == 0) {
                    if (admit != null && (promptSeen || !(content instanceof Json.JString))) {
                        f.owned.remove(r.index());
                        f.excluded.put(r.index(), type);
                        continue;
                    }
                    promptSeen = true;
                    f.normalized.add(r.index());
                    time(f, r, ts);
                    List<String> texts = content instanceof Json.JString s ? List.of(s.value()) : CodexRolloutProfile.texts(content == null ? List.of() : content.elements());
                    f.prompts.add(new ExpectedFacts.Prompt(r.index(), SourceProfile.text(o, "promptId"), texts));
                    continue;
                }
                if (admit != null && !admittedResult(content, admittedTools)) {
                    f.owned.remove(r.index());
                    f.excluded.put(r.index(), type);
                    continue;
                }
                f.normalized.add(r.index());
                time(f, r, ts);
                for (Json b : content.elements()) {
                    if (!"tool_result".equals(SourceProfile.text(b, "type"))) {
                        continue;
                    }
                    String id = SourceProfile.text(b, "tool_use_id");
                    ExpectedFacts.Decision decision = null;
                    Json pd = o.member("permissionDecision");
                    if (pd instanceof Json.JObject && resultBlocks == 1) {
                        String reported = SourceProfile.text(pd, "decision");
                        decision = new ExpectedFacts.Decision(r.index(), reported, SourceProfile.decisionId(reported),
                                SourceProfile.text(pd, "source"), SourceProfile.text(pd, "reasonType"), 1, 1, 1, List.of(id), id);
                        f.decisions.add(decision);
                    }
                    f.results.add(new ExpectedFacts.Result(r.index(), id, b.member("content"), b.member("is_error"), o.member("toolUseResult"), decision, null));
                }
                continue;
            }
            if (admit != null) {
                f.owned.remove(r.index());
                f.excluded.put(r.index(), type == null ? "unknown" : type);
                continue;
            }
            if ("attachment".equals(type) && o.member("attachment") instanceof Json.JObject att) {
                f.normalized.add(r.index());
                time(f, r, ts);
                Json rendered = o.member("rendered");
                f.contexts.add(new ExpectedFacts.Context(r.index(), 2, "attachment/" + SourceProfile.text(att, "type"), null, Map.of(),
                        SourceProfile.text(o, "renderedRole"), rendered instanceof Json.JArray a ? a.elements().size() : null));
                continue;
            }
            if ("cost-state".equals(type)) {
                f.normalized.add(r.index());
                time(f, r, ts);
                f.contexts.add(new ExpectedFacts.Context(r.index(), 3, "cost-state", null, Map.of(), null, null));
                if (o.member("modelUsage") instanceof Json.JObject mu) {
                    for (Map.Entry<String, Json> m : mu.members().entrySet()) {
                        f.usages.add(new ExpectedFacts.Usage(r.index(), r.index(), 5, 2, SourceProfile.components(m.getValue(),
                                "inputTokens", "input_tokens", "outputTokens", "output_tokens", "cacheReadInputTokens", "cache_read_tokens",
                                "cacheCreationInputTokens", "cache_write_tokens", "thinkingTokens", "reasoning_tokens"), m.getKey()));
                    }
                }
                if (o.member("totalCostUSD") instanceof Json.JNumber) {
                    f.costs.add(new ExpectedFacts.Cost(r.index(), o.member("totalCostUSD"), "USD", 5));
                }
                continue;
            }
            // every other family: whole-record fallback expected (owned, not normalized); time basis still judged
            time(f, r, ts);
        }
        return f;
    }

    private static void time(ExpectedFacts f, NativeArtifact.Record r, String ts) {
        if (ts != null) {
            f.nativeTime.put(r.index(), ts);
        }
    }

    private static boolean admittedResult(Json content, java.util.Set<String> toolIds) {
        for (Json b : content.elements()) {
            if ("tool_result".equals(SourceProfile.text(b, "type")) && toolIds.contains(SourceProfile.text(b, "tool_use_id"))) {
                return true;
            }
        }
        return false;
    }

    static Map<String, Json> settings(Json o, String... pairs) {
        Map<String, Json> out = new LinkedHashMap<>();
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            Json v = o.path(pairs[i].split("\\."));
            if (v != null && !v.isNull()) {
                out.put(pairs[i + 1], v);
            }
        }
        return out;
    }
}
