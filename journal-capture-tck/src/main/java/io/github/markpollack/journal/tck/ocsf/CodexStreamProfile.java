package io.github.markpollack.journal.tck.ocsf;

import io.github.markpollack.journal.tck.verify.Json;

import java.util.List;

/**
 * Codex CLI {@code exec --json} live output. Agent messages are responses with stream-scoped item ids; command
 * executions are operations without any call id (unattributed); {@code turn.completed} reports the turn's usage
 * once. The stream carries no timestamps.
 */
final class CodexStreamProfile implements SourceProfile {

    @Override
    public ExpectedFacts expected(NativeArtifact artifact, Json source, Json scope) {
        ExpectedFacts f = new ExpectedFacts(SourceProfile.text(source, "artifact"));
        String threadId = null;
        for (NativeArtifact.Record r : artifact.records()) {
            f.owned.add(r.index());
            if (!r.parsed()) {
                continue;
            }
            Json o = r.json();
            String type = SourceProfile.text(o, "type");
            if ("thread.started".equals(type) && SourceProfile.text(o, "thread_id") != null) {
                threadId = SourceProfile.text(o, "thread_id");
            }
            if (threadId != null) {
                // the stream states its session identity once, in thread.started; every later record belongs to that thread
                f.sessionOf.put(r.index(), threadId);
            }
            Json item = o.member("item");
            String itemType = SourceProfile.text(item, "type");
            if ("item.completed".equals(type) && "agent_message".equals(itemType)) {
                f.normalized.add(r.index());
                f.responses.add(new ExpectedFacts.Response(r.index(), SourceProfile.text(item, "id"),
                        item.member("text") == null ? List.of() : List.of(item.member("text")), null, true, null));
            }
            else if (("item.started".equals(type) || "item.completed".equals(type)) && "command_execution".equals(itemType)) {
                f.normalized.add(r.index());
                String state = SourceProfile.text(item, "status");
                int status = "completed".equals(state) ? 1 : "failed".equals(state) ? 2 : 0;
                f.operations.add(new ExpectedFacts.Operation(r.index(), SourceProfile.text(item, "id"), null, item.member("exit_code"),
                        state, status, 3, 2, List.of()));
            }
            else if ("turn.completed".equals(type)) {
                f.normalized.add(r.index());
                f.usages.add(new ExpectedFacts.Usage(r.index(), r.index(), 3, 2, SourceProfile.components(o.member("usage"),
                        "input_tokens", "input_tokens", "output_tokens", "output_tokens", "cached_input_tokens", "cache_read_tokens",
                        "cache_write_input_tokens", "cache_write_tokens", "reasoning_output_tokens", "reasoning_tokens"), null));
                f.completed.add(new ExpectedFacts.TurnCompleted(r.index(), 0, null));
            }
        }
        return f;
    }
}
