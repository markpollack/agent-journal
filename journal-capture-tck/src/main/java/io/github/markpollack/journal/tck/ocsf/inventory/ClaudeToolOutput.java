package io.github.markpollack.journal.tck.ocsf.inventory;

import io.github.markpollack.journal.tck.ocsf.NativeArtifact;
import io.github.markpollack.journal.tck.verify.Json;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Exact native persisted-output links; never path proximity, copied API metadata or inferred child execution. */
final class ClaudeToolOutput {
    private ClaudeToolOutput() { }

    static Map<String, Json> bindings(Json manifest, Path base) {
        Map<String, Json> out = new LinkedHashMap<>();
        List<Json> transcripts = sources(manifest, "transcript");
        List<Json> streams = sources(manifest, "stream");
        if (transcripts.size() != 1 || streams.size() != 1) { return out; }
        Json transcript = transcripts.get(0);
        List<NativeArtifact.Record> nativeRows = read(base, transcript);
        List<NativeArtifact.Record> streamRows = read(base, streams.get(0));
        for (Json sidecar : sources(manifest, "sidecar")) {
            Json binding = bind(manifest, sidecar, transcript, nativeRows, streamRows, base);
            if (binding != null) { out.put(text(sidecar, "artifact"), binding); }
        }
        return out;
    }

    static List<String> references(Json manifest, Path base) {
        return sources(manifest, "transcript").stream().flatMap(s -> read(base, s).stream())
                .filter(r -> r.parsed() && "user".equals(text(r.json(), "type")))
                .map(r -> text(r.json().member("toolUseResult"), "persistedOutputPath")).filter(Objects::nonNull).distinct().toList();
    }

    private static Json bind(Json manifest, Json sidecar, Json transcript, List<NativeArtifact.Record> rows,
            List<NativeArtifact.Record> stream, Path base) {
        String origin = text(sidecar, "origin");
        List<NativeArtifact.Record> matches = rows.stream().filter(r -> r.parsed() && "user".equals(text(r.json(), "type"))
                && Objects.equals(origin, text(r.json().member("toolUseResult"), "persistedOutputPath"))).toList();
        if (matches.size() != 1) { return null; }
        NativeArtifact.Record result = matches.get(0);
        Json row = result.json();
        Json block = singleBlock(row, "tool_result");
        if (block == null) { return null; }
        String id = text(block, "tool_use_id");
        NativeArtifact.Record request = request(rows, row, id);
        if (request == null || request.index() >= result.index()) { return null; }
        if (!sameSession(manifest, sidecar, row) || !wireResult(stream, block, id)) { return null; }
        if (!content(base, sidecar, row.member("toolUseResult"), block)) { return null; }
        Map<String, Json> fields = new LinkedHashMap<>();
        fields.put("kind", new Json.JString("agent-journal/persisted-tool-output"));
        fields.put("version", new Json.JString("0.1"));
        fields.put("run_uid", manifest.path("run", "run_uid"));
        fields.put("session_uid", manifest.path("run", "session_uid"));
        fields.put("tool_call_uid", block.member("tool_use_id"));
        fields.put("request_ref", ref(transcript, request));
        fields.put("result_ref", ref(transcript, result));
        return new Json.JObject(fields);
    }

    private static boolean sameSession(Json manifest, Json sidecar, Json row) {
        Json session = manifest.path("run", "session_uid");
        return Json.sameValue(session, sidecar.member("owner_session_uid")) && Json.sameValue(session, row.member("sessionId"))
                && Json.sameValue(manifest.member("acquisition_uid"), sidecar.member("acquisition"));
    }

    private static NativeArtifact.Record request(List<NativeArtifact.Record> rows, Json result, String id) {
        String uuid = text(result, "sourceToolAssistantUUID");
        if (uuid == null || id == null) { return null; }
        List<NativeArtifact.Record> matches = rows.stream().filter(r -> r.parsed()
                && "assistant".equals(text(r.json(), "type")) && uuid.equals(text(r.json(), "uuid"))
                && blocks(r.json(), "tool_use").stream().filter(b -> id.equals(text(b, "id"))).count() == 1).toList();
        return matches.size() == 1 ? matches.get(0) : null;
    }

    private static boolean wireResult(List<NativeArtifact.Record> rows, Json block, String id) {
        List<Json> results = rows.stream().filter(r -> r.parsed() && "user".equals(text(r.json(), "type")))
                .flatMap(r -> blocks(r.json(), "tool_result").stream()).filter(b -> Objects.equals(id, text(b, "tool_use_id"))).toList();
        return results.size() == 1 && Json.sameValue(block, results.get(0));
    }

    private static boolean content(Path base, Json sidecar, Json structured, Json result) {
        NativeArtifact file = NativeArtifact.document(base.resolve(text(sidecar, "path")));
        if (file.records().size() != 1) { return false; }
        NativeArtifact.Record record = file.records().get(0);
        String full = record.text();
        String prefix = text(structured, "stdout");
        String preview = text(result, "content");
        // Only the exercised plain-text persisted result format; JSON/binary/other outputs remain unqualified.
        return !record.parsed() && full != null && prefix != null && full.startsWith(prefix)
                && preview != null && preview.startsWith("<persisted-output>\n") && preview.contains("Full output saved to: " + text(sidecar, "origin") + "\n")
                && structured.member("persistedOutputSize") instanceof Json.JNumber n && n.value().longValueExact() == file.bytes().length;
    }

    private static Json ref(Json source, NativeArtifact.Record record) {
        return new Json.JObject(Map.of("artifact", source.member("artifact"), "record_index", new Json.JNumber(Integer.toString(record.index())),
                "native_uid", record.json().member("uuid")));
    }

    private static List<NativeArtifact.Record> read(Path base, Json source) {
        return NativeArtifact.read(base.resolve(text(source, "path"))).records();
    }
    private static List<Json> sources(Json manifest, String role) {
        return manifest.member("sources").elements().stream().filter(s -> role.equals(text(s, "role")) && "acquired".equals(text(s, "state"))).toList();
    }
    private static Json singleBlock(Json row, String type) {
        List<Json> blocks = blocks(row, type);
        return blocks.size() == 1 ? blocks.get(0) : null;
    }
    private static List<Json> blocks(Json row, String type) {
        Json content = row.path("message", "content");
        return content == null ? List.of() : content.elements().stream().filter(b -> type.equals(text(b, "type"))).toList();
    }
    private static String text(Json value, String key) { return value == null ? null : RawInventory.text(value, key); }
}
