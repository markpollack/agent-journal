package io.github.markpollack.journal.tck.ocsf;

import io.github.markpollack.journal.tck.verify.Json;
import java.util.ArrayList;
import java.util.List;

/**
 * The owner scope a Claude Code stream record names, read independently of the writer. A record's
 * {@code parent_tool_use_id} names the tool call whose sub-agent wrote it, except on a {@code tool_progress}
 * record: there it names the tool call in progress (a 30 s heartbeat, Bash progress or an Agent API retry), so
 * the record belongs to the scope of a record without one ({@code claude.stream.progress_owner.v1}).
 */
public final class ClaudeOwnerScope {
    /** The owner member Claude Code split cores are selected by. */
    public static final String OWNER_PATH = "parent_tool_use_id";

    private ClaudeOwnerScope() { }

    /** The spawning tool call that owns {@code record}, or null for the main thread. */
    public static String owner(Json record) {
        return record == null || "tool_progress".equals(SourceProfile.text(record, "type")) ? null : SourceProfile.text(record, OWNER_PATH);
    }

    /** The distinct owners of a stream's parsed records, in first-seen order: the split cores the stream requires. */
    public static List<String> splitCores(NativeArtifact stream) {
        List<String> owners = new ArrayList<>();
        for (NativeArtifact.Record r : stream.records()) {
            String owner = r.parsed() ? owner(r.json()) : null;
            if (owner != null && !owners.contains(owner)) owners.add(owner);
        }
        return owners;
    }
}
