package io.github.markpollack.journal.tck.ocsf;

import io.github.markpollack.journal.tck.verify.Json;

import java.util.ArrayList;
import java.util.List;

/**
 * Antigravity headless {@code stream-json} live output. The stream reports no text, no ids and no timestamps: a step
 * identity is {@code <conversation_id>#step<step_index>} (a Journal-generated id, declared as such); tool steps are
 * requests when ACTIVE and results when DONE (output only when present; DONE is a lifecycle state, not success);
 * agent-response steps carry usage the vendor does not mark provisional or final (Unknown); the {@code result}
 * line reports run usage once and run-scope denials without any step reference, whose candidates are derived from
 * the tool name the action maps to.
 */
final class AntigravityStreamProfile implements SourceProfile {

    private record Step(String uid, String name) {
    }

    @Override
    public ExpectedFacts expected(NativeArtifact artifact, Json source, Json scope) {
        ExpectedFacts f = new ExpectedFacts(SourceProfile.text(source, "artifact"));
        List<Step> toolSteps = new ArrayList<>();
        for (NativeArtifact.Record r : artifact.records()) {
            f.owned.add(r.index());
            if (!r.parsed()) {
                continue;
            }
            Json o = r.json();
            String event = SourceProfile.text(o, "event");
            if ("step_update".equals(event)) {
                Json s = o.member("step_update");
                String conv = SourceProfile.text(s, "conversation_id");
                Json idx = s.member("step_index");
                String uid = conv + "#step" + (idx == null ? null : idx.scalarText());
                String stepType = SourceProfile.text(s, "step_type");
                String state = SourceProfile.text(s, "state");
                if ("user_input".equals(stepType)) {
                    f.normalized.add(r.index());
                    f.prompts.add(new ExpectedFacts.Prompt(r.index(), null, null));
                }
                else if ("agent_response".equals(stepType)) {
                    f.normalized.add(r.index());
                    f.responses.add(new ExpectedFacts.Response(r.index(), uid, null, null, "DONE".equals(state), null));
                    f.usages.add(new ExpectedFacts.Usage(r.index(), r.index(), 2, 0, SourceProfile.components(s.member("usage"),
                            "input_tokens", "input_tokens", "output_tokens", "output_tokens", "cache_read_tokens", "cache_read_tokens",
                            "thinking_tokens", "reasoning_tokens", "total_tokens", "reported_total_tokens"), null));
                }
                else if ("tool".equals(stepType)) {
                    String name = SourceProfile.text(s, "tool_name");
                    Json info = s.member("tool_info");
                    if ("ACTIVE".equals(state)) {
                        f.normalized.add(r.index());
                        toolSteps.add(new Step(uid, name));
                        f.requests.add(new ExpectedFacts.Request(r.index(), uid, name, info == null ? null : info.member("parameters"), null, "ACTIVE"));
                    }
                    else if ("DONE".equals(state)) {
                        f.normalized.add(r.index());
                        if (toolSteps.stream().noneMatch(t -> t.uid().equals(uid))) {
                            toolSteps.add(new Step(uid, name));
                        }
                        f.results.add(new ExpectedFacts.Result(r.index(), uid, info == null ? null : info.member("output"), null, null, null, "DONE"));
                    }
                }
            }
            else if ("result".equals(event)) {
                f.normalized.add(r.index());
                Json res = o.member("result");
                f.completed.add(new ExpectedFacts.TurnCompleted(r.index(), "SUCCESS".equals(SourceProfile.text(res, "status")) ? 1 : 2, null));
                f.usages.add(new ExpectedFacts.Usage(r.index(), r.index(), 4, 2, SourceProfile.components(res.member("usage"),
                        "input_tokens", "input_tokens", "output_tokens", "output_tokens", "cache_read_tokens", "cache_read_tokens",
                        "thinking_tokens", "reasoning_tokens", "total_tokens", "reported_total_tokens"), null));
                for (Json d : res.member("denied_actions") == null ? List.<Json>of() : res.member("denied_actions").elements()) {
                    String target = "command".equals(SourceProfile.text(d, "action")) ? "run_command" : null;
                    List<String> candidates = new ArrayList<>();
                    for (Step t : toolSteps) {
                        if (target != null && target.equals(t.name())) {
                            candidates.add(t.uid());
                        }
                    }
                    f.decisions.add(new ExpectedFacts.Decision(r.index(), "denied (derived label: denied_actions entry)", 2, "cli", null, 4, 3, 2, candidates, null));
                }
            }
        }
        return f;
    }
}
