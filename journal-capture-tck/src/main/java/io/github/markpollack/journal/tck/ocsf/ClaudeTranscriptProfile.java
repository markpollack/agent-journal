package io.github.markpollack.journal.tck.ocsf;

import io.github.markpollack.journal.tck.verify.Json;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Claude Code saved session file, under the bounded admission the package declares ({@code admit_response_uid}):
 * assistant records of that message id, the first user prompt record (string content) and the user tool-result
 * records for that message's tool uses. Every other record is excluded with its family name and is expected to be
 * declared, not preserved. Transcript usage is final when {@code stop_reason} is reported.
 */
final class ClaudeTranscriptProfile implements SourceProfile {

    @Override
    public ExpectedFacts expected(NativeArtifact artifact, Json source, Json scope) {
        ExpectedFacts f = new ExpectedFacts(SourceProfile.text(source, "artifact"));
        String admit = SourceProfile.text(source, "admit_response_uid");
        Set<String> toolIds = new LinkedHashSet<>();
        boolean promptSeen = false;
        for (NativeArtifact.Record r : artifact.records()) {
            if (!r.parsed()) {
                f.owned.add(r.index());
                continue;
            }
            Json o = r.json();
            String type = SourceProfile.text(o, "type");
            Json message = o.member("message") instanceof Json.JObject m ? m : null;
            String ts = SourceProfile.text(o, "timestamp");
            if ("assistant".equals(type) && message != null && admit != null && admit.equals(SourceProfile.text(message, "id"))) {
                f.owned.add(r.index());
                f.normalized.add(r.index());
                if (ts != null) {
                    f.nativeTime.put(r.index(), ts);
                }
                String stop = SourceProfile.text(message, "stop_reason");
                List<Json> blocks = message.member("content") == null ? null : message.member("content").elements();
                f.responses.add(new ExpectedFacts.Response(r.index(), admit, blocks, stop, stop != null, SourceProfile.text(message, "model")));
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
                        toolIds.add(SourceProfile.text(b, "id"));
                        f.requests.add(new ExpectedFacts.Request(r.index(), SourceProfile.text(b, "id"), SourceProfile.text(b, "name"),
                                b.member("input"), admit, null));
                    }
                }
            }
            else if ("user".equals(type) && message != null && message.member("content") instanceof Json.JString s && !promptSeen) {
                promptSeen = true;
                f.owned.add(r.index());
                f.normalized.add(r.index());
                if (ts != null) {
                    f.nativeTime.put(r.index(), ts);
                }
                f.prompts.add(new ExpectedFacts.Prompt(r.index(), SourceProfile.text(o, "promptId"), List.of(s.value())));
            }
            else if ("user".equals(type) && message != null && message.member("content") instanceof Json.JArray && admittedResult(message, toolIds)) {
                f.owned.add(r.index());
                f.normalized.add(r.index());
                if (ts != null) {
                    f.nativeTime.put(r.index(), ts);
                }
                int resultBlocks = 0;
                for (Json b : message.member("content").elements()) {
                    if ("tool_result".equals(SourceProfile.text(b, "type"))) {
                        resultBlocks++;
                    }
                }
                for (Json b : message.member("content").elements()) {
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
                    f.results.add(new ExpectedFacts.Result(r.index(), id, b.member("content"), b.member("is_error"),
                            o.member("toolUseResult"), decision, null));
                }
            }
            else {
                f.excluded.put(r.index(), type == null ? "unknown" : type);
            }
        }
        return f;
    }

    private static boolean admittedResult(Json message, Set<String> toolIds) {
        for (Json b : message.member("content").elements()) {
            if ("tool_result".equals(SourceProfile.text(b, "type")) && toolIds.contains(SourceProfile.text(b, "tool_use_id"))) {
                return true;
            }
        }
        return false;
    }
}
