package io.github.markpollack.journal.tck.ocsf;

import io.github.markpollack.journal.tck.verify.Json;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Independent expectations from pinned FileChangeItem/FileChange/emit_patch_end definitions. */
final class CodexFileChangeReading {
    private CodexFileChangeReading() { }
    static void read(ExpectedFacts facts, NativeArtifact.Record record) {
        Json payload = record.json().member("payload"), item = payload.member("item");
        String id = SourceProfile.text(item, "id"), state = SourceProfile.text(item, "status");
        int status = "completed".equals(state) ? 1 : "failed".equals(state) ? 2 : 0;
        facts.normalized.add(record.index());
        facts.operations.add(new ExpectedFacts.Operation(record.index(), id, null, null, state, status, 1, 1, List.of(id)));
        List<Json> files = new ArrayList<>();
        if (item.member("changes") instanceof Json.JObject changes) {
            changes.members().forEach((path, value) -> files.add(new Json.JObject(SourceFacts.fields(
                    "file_path", path, "change_kind", value.member("type")))));
        }
        new SourceFacts.Target(facts, record.index(), "tool_call", 3).event("/payload/item",
                "codex.native.file_change/v1", SourceFacts.fields("/tool_operation/uid", id), SourceFacts.fields(
                        "/file_changes", new Json.JArray(files), "/tool_kind_id", 2,
                        "/session_uid", payload.member("thread_id"), "/turn_uid", payload.member("turn_id"),
                        "/tool_operation/data", item));
    }
}
