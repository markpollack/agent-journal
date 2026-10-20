package io.github.markpollack.journal.tck.ocsf;

import io.github.markpollack.journal.tck.verify.Json;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Codex CLI rollout (saved session file). User-role messages and {@code UserMessage} items are prompt records (the
 * view, not this profile, decides which are human submissions); assistant messages and {@code AgentMessage} items
 * are responses sharing one message id; {@code custom_tool_call} is a request owned by its {@code call_id};
 * {@code CommandExecution} items are operations whose wrapping call is recounted here from the native join keys
 * (same turn, an {@code exec} call whose input contains the command text) and is therefore derived, never reported;
 * {@code token_count} records before {@code task_complete} supply prompt-turn usage, the last one final. The rollout
 * reports no cost.
 */
final class CodexRolloutProfile implements SourceProfile {

    private record Call(String turn, String callId, String name, String input) {
    }

    @Override
    public ExpectedFacts expected(NativeArtifact artifact, Json source, Json scope) {
        ExpectedFacts f = new ExpectedFacts(SourceProfile.text(source, "artifact"));
        List<Call> calls = new ArrayList<>();
        List<NativeArtifact.Record> tokenCounts = new ArrayList<>();
        String currentTurn = null;
        for (NativeArtifact.Record r : artifact.records()) {
            f.owned.add(r.index());
            if (!r.parsed()) {
                continue;
            }
            Json o = r.json();
            String ts = SourceProfile.text(o, "timestamp");
            if (ts != null) {
                f.nativeTime.put(r.index(), ts);
            }
            String type = SourceProfile.text(o, "type");
            Json p = o.member("payload");
            String pt = SourceProfile.text(p, "type");
            if ("turn_context".equals(type)) {
                currentTurn = SourceProfile.text(p, "turn_id");
                continue;
            }
            if ("response_item".equals(type) && "message".equals(pt)) {
                String role = SourceProfile.text(p, "role");
                List<Json> blocks = p.member("content") == null ? List.of() : p.member("content").elements();
                if ("user".equals(role)) {
                    f.normalized.add(r.index());
                    f.prompts.add(new ExpectedFacts.Prompt(r.index(), currentTurn, texts(blocks)));
                }
                else if ("assistant".equals(role)) {
                    f.normalized.add(r.index());
                    f.responses.add(new ExpectedFacts.Response(r.index(), SourceProfile.text(p, "id"), blocks, null, true, null));
                }
                continue;
            }
            if ("response_item".equals(type) && "custom_tool_call".equals(pt)) {
                f.normalized.add(r.index());
                String turn = SourceProfile.text(p, "internal_chat_message_metadata_passthrough", "turn_id");
                String input = SourceProfile.text(p, "input");
                calls.add(new Call(turn, SourceProfile.text(p, "call_id"), SourceProfile.text(p, "name"), input));
                f.requests.add(new ExpectedFacts.Request(r.index(), SourceProfile.text(p, "call_id"), SourceProfile.text(p, "name"),
                        p.member("input"), null, SourceProfile.text(p, "status")));
                continue;
            }
            if ("response_item".equals(type) && "custom_tool_call_output".equals(pt)) {
                f.normalized.add(r.index());
                f.results.add(new ExpectedFacts.Result(r.index(), SourceProfile.text(p, "call_id"), p.member("output"), null, null, null, null));
                continue;
            }
            if ("event_msg".equals(type) && "item_completed".equals(pt)) {
                Json item = p.member("item");
                String itemType = SourceProfile.text(item, "type");
                String turn = SourceProfile.text(p, "turn_id");
                if ("UserMessage".equals(itemType)) {
                    f.normalized.add(r.index());
                    f.prompts.add(new ExpectedFacts.Prompt(r.index(), turn, texts(item.member("content") == null ? List.of() : item.member("content").elements())));
                    continue;
                }
                if ("AgentMessage".equals(itemType)) {
                    f.normalized.add(r.index());
                    f.responses.add(new ExpectedFacts.Response(r.index(), SourceProfile.text(item, "id"),
                            item.member("content") == null ? List.of() : item.member("content").elements(), null, true, null));
                    continue;
                }
                if ("CommandExecution".equals(itemType)) {
                    f.normalized.add(r.index());
                    List<Json> argv = item.member("command") == null ? List.of() : item.member("command").elements();
                    String last = argv.isEmpty() ? "" : argv.get(argv.size() - 1).text();
                    List<String> candidates = new ArrayList<>();
                    for (Call c : calls) {
                        if (turn != null && turn.equals(c.turn()) && "exec".equals(c.name()) && c.input() != null && last != null && c.input().contains(last)) {
                            candidates.add(c.callId());
                        }
                    }
                    int outcome = candidates.size() == 1 ? 1 : candidates.size() > 1 ? 2 : 3;
                    String state = SourceProfile.text(item, "status");
                    int status = "completed".equals(state) ? 1 : "failed".equals(state) ? 2 : 0;
                    f.operations.add(new ExpectedFacts.Operation(r.index(), SourceProfile.text(item, "id"), item.member("command"),
                            item.member("exit_code"), state, status, outcome, 2, candidates));
                    continue;
                }
                continue; // other items: whole-record fallback
            }
            if ("event_msg".equals(type) && "token_count".equals(pt)) {
                tokenCounts.add(r); // retained whole; its usage rides on the turn completion
                continue;
            }
            if ("event_msg".equals(type) && "task_complete".equals(pt)) {
                f.normalized.add(r.index());
                for (int i = 0; i < tokenCounts.size(); i++) {
                    NativeArtifact.Record tc = tokenCounts.get(i);
                    Json total = tc.json().path("payload", "info", "total_token_usage");
                    Map<String, Json> c = SourceProfile.components(total, "input_tokens", "input_tokens", "output_tokens", "output_tokens",
                            "cached_input_tokens", "cache_read_tokens", "cache_write_input_tokens", "cache_write_tokens",
                            "reasoning_output_tokens", "reasoning_tokens", "total_tokens", "reported_total_tokens");
                    f.usages.add(new ExpectedFacts.Usage(r.index(), tc.index(), 3, i == tokenCounts.size() - 1 ? 2 : 1, c, null));
                }
                tokenCounts.clear();
                f.completed.add(new ExpectedFacts.TurnCompleted(r.index(), 0, null));
            }
        }
        return f;
    }

    static List<String> texts(List<Json> blocks) {
        List<String> out = new ArrayList<>();
        for (Json b : blocks) {
            String t = SourceProfile.text(b, "text");
            if (t != null) {
                out.add(t);
            }
        }
        return out;
    }
}
