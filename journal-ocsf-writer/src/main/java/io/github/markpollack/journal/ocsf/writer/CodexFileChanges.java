package io.github.markpollack.journal.ocsf.writer;

import java.util.List;
import static io.github.markpollack.journal.ocsf.writer.MapperSupport.text;

/** Codex 0.166.0 FileChange observations; no command parsing or inferred filesystem effects. */
final class CodexFileChanges {
    private CodexFileChanges() { }
    static void map(NativeRecord record, SourceContext ctx) {
        Jv payload = record.json().member("payload");
        Jv item = payload.member("item");
        String id = text(item, "id"), turn = text(payload, "turn_id"), state = text(item, "status");
        int status = "completed".equals(state) ? 1 : "failed".equals(state) ? 2 : 0;
        Jv.Obj operation = new Jv.Obj().put("uid", id).put("reported_state", state)
                .put("stdout", item.member("stdout")).put("stderr", item.member("stderr"))
                .put("attribution", MapperSupport.attribution(1, 1, List.of(id),
                        "codex.file_change.owner.v1: emit_patch_end assigns item.id from ToolEventCtx.call_id", turn)).put("data", item);
        Jv.Arr changes = new Jv.Arr();
        if (item.member("changes") instanceof Jv.Obj files) {
            files.members().forEach((path, value) -> changes.add(new Jv.Obj()
                    .put("file_path", path).put("change_kind", value.member("type"))));
        }
        String timestamp = text(record.json(), "timestamp");
        ctx.family("event_msg/item_completed/FileChange", record.raw().length,
                "normalized: tool_call Operation observed; patch metadata retained in tool_operation.data");
        Jv.Obj event = ctx.event("tool_call", 3, record, ctx.timeFor(record, timestamp), CodexRolloutMapper.CONTRACT,
                "codex.rollout.item_completed.FileChange->tool_call.operation", timestamp, id, true,
                new MapperSupport.Status(status, 1, "reported: item.status; completion is not independent filesystem verification"));
        if ("declined".equals(state)) event.put("status", "Declined");
        event.put("session_uid", text(payload, "thread_id")).put("turn_uid", turn).put("tool_call_uid", id)
                .put("tool_kind_id", 2).put("tool_kind", MapperSupport.TOOL_KIND.get(2))
                .put("tool_kind_basis", "reported FileChange family").put("tool_operation", operation)
                .put("file_changes", changes).put("data", record.json());
        ctx.emit(event);
    }
}
