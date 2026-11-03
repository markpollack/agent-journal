package io.github.markpollack.journal.tck.ocsf;

import io.github.markpollack.journal.tck.ocsf.NativeArtifact;
import io.github.markpollack.journal.tck.verify.Json;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;
import static io.github.markpollack.journal.tck.ocsf.CodexAcquisitionReading.*;

/** Reads original vendor witnesses, not the writer's candidate facts. */
final class CodexNativeCapture {
    private static final List<String> USAGE = List.of("input_tokens", "cached_input_tokens", "cache_write_input_tokens", "output_tokens", "reasoning_output_tokens");
    private CodexNativeCapture() { }

    static void validate(Json m, Map<String, CodexAcquisitionReading.Source> sources) throws CharacterCodingException {
        String session = text(m.member("run"), "session_uid");
        String turn = text(array(m, "turns").get(0), "native_turn_uid");
        List<Json> stream = records(sources.get("stream")); List<Json> nativeRows = records(sources.get("transcript"));
        Json usage = stream(stream, session);
        nativeRows(m, nativeRows, session, turn, prompt(sources.get("stdin").bytes()), usage);
    }

    private static List<Json> records(CodexAcquisitionReading.Source source) {
        NativeArtifact artifact = NativeArtifact.read(source.path());
        require(artifact.sha256().equals(NativeArtifact.sha256(source.bytes())), "source changed during native reading");
        require(artifact.records().stream().allMatch(NativeArtifact.Record::parsed), "unparsed native record");
        return artifact.records().stream().map(NativeArtifact.Record::json).toList();
    }

    private static String prompt(byte[] bytes) throws CharacterCodingException {
        String prompt = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
        require(!prompt.isBlank() && !prompt.startsWith("\uFEFF"), "nonblank UTF-8 prompt without BOM required"); return prompt;
    }

    private static Json stream(List<Json> rows, String session) {
        require(rows.size() >= 4, "incomplete stdout lifecycle");
        equal(rows.get(0), "type", "thread.started"); equal(rows.get(0), "thread_id", session);
        equal(rows.get(1), "type", "turn.started");
        equal(rows.get(rows.size() - 1), "type", "turn.completed");
        for (String kind : List.of("thread.started", "turn.started", "turn.completed")) require(count(rows, kind, false) == 1, "duplicate stdout lifecycle");
        require(rows.stream().noneMatch(r -> Set.of("turn.failed", "error").contains(text(r, "type"))), "failed stdout lifecycle");
        for (Json row : rows) if (row.member("thread_id") != null) equal(row, "thread_id", session);
        Json usage = rows.get(rows.size() - 1).member("usage"); validUsage(usage);
        require(rows.stream().anyMatch(r -> "item.completed".equals(optional(r, "type")) && "agent_message".equals(optional(r.member("item"), "type"))), "stdout text response missing");
        return usage;
    }

    private static void nativeRows(Json m, List<Json> rows, String session, String turn, String prompt, Json usage) {
        require(!rows.isEmpty(), "native source empty"); equal(rows.get(0), "type", "session_meta");
        Json meta = rows.get(0).member("payload"); equal(meta, "id", session); equal(meta, "cli_version", "0.166.0");
        equal(meta, "cwd", text(m.member("process"), "cwd")); equal(meta, "source", "exec");
        require(meta.member("forked_from_id") == null || meta.member("forked_from_id").isNull(), "forked native history");
        for (String kind : List.of("session_meta", "turn_context")) require(count(rows, kind, false) == 1, "native context population differs");
        for (String kind : List.of("task_started", "task_complete")) require(count(rows, kind, true) == 1, "native turn population differs");
        equal(rows.get(rows.size() - 1).member("payload"), "type", "task_complete");
        Scan scan = new Scan(session, turn, text(m.member("process"), "cwd"), prompt);
        rows.forEach(scan::read);
        require(scan.matches == 1, "exact original prompt membership missing or ambiguous");
        sameUsage(usage, scan.lastUsage); if (scan.lastRecord != null) sameUsage(usage, scan.lastRecord);
    }

    private static final class Scan {
        private final String session, turn, cwd, prompt;
        private Json lastUsage, lastRecord;
        private int matches;
        private boolean started;
        Scan(String session, String turn, String cwd, String prompt) { this.session = session; this.turn = turn; this.cwd = cwd; this.prompt = prompt; }
        void read(Json row) {
            Json p = row.member("payload"); require(p instanceof Json.JObject, "native payload missing"); identities(p, session, turn);
            switch (text(row, "type")) {
                case "turn_context" -> { require(started, "context before native turn"); equal(p, "turn_id", turn); equal(p, "cwd", cwd); }
                case "token_usage_record" -> { equal(p, "thread_id", session); equal(p, "turn_id", turn); lastRecord = p.member("thread_token_usage"); }
                case "response_item" -> user(p);
                case "event_msg" -> event(p);
                default -> { }
            }
        }
        private void user(Json p) {
            if (!"message".equals(optional(p, "type")) || !"user".equals(optional(p, "role"))) return;
            if (matches(p, prompt)) { require(started, "prompt before native turn"); matches++; }
            else require(context(p), "unassigned native user history");
        }
        private void event(Json p) {
            switch (text(p, "type")) {
                case "task_started" -> { started = true; equal(p, "turn_id", turn); }
                case "task_complete" -> { require(started, "native terminal before start"); equal(p, "turn_id", turn); require(p.member("error") == null || p.member("error").isNull(), "native completion error"); }
                case "token_count" -> lastUsage = p.path("info", "total_token_usage");
                case "turn_aborted", "task_failed", "error" -> throw new IllegalArgumentException("failed native turn");
                default -> { }
            }
        }
    }
    private static boolean context(Json p) {
        return array(p, "content").stream().allMatch(b -> "input_text".equals(optional(b, "type"))
                && (text(b, "text").startsWith("<environment_context>") || text(b, "text").startsWith("# AGENTS.md instructions for ")));
    }

    private static void identities(Json p, String session, String turn) {
        for (String key : List.of("session_id", "thread_id")) if (p.member(key) != null && !p.member(key).isNull()) equal(p, key, session);
        for (String key : List.of("turn_id", "root_turn_id")) if (p.member(key) != null && !p.member(key).isNull()) equal(p, key, turn);
        nestedIdentities(p, turn);
    }
    private static void nestedIdentities(Json p, String turn) {
        Json metadata = p.member("internal_chat_message_metadata_passthrough");
        if (metadata != null && metadata.member("turn_id") != null) equal(metadata, "turn_id", turn);
        Json parent = p.member("turn_attribution");
        if (parent != null) {
            equal(parent, "turn_id", turn); equal(parent, "root_turn_id", turn);
            require(parent.member("parent_turn_id") == null || parent.member("parent_turn_id").isNull(), "native parent turn present");
        }
    }
    private static boolean matches(Json p, String prompt) {
        List<Json> content = array(p, "content");
        return content.size() == 1 && "input_text".equals(optional(content.get(0), "type")) && prompt.equals(optional(content.get(0), "text"));
    }
    private static long count(List<Json> rows, String type, boolean payload) { return rows.stream().filter(r -> type.equals(optional(payload ? r.member("payload") : r, "type"))).count(); }
    private static void validUsage(Json usage) { for (String key : USAGE) require(number(usage, key) >= 0, "negative native usage"); }
    private static void sameUsage(Json expected, Json actual) {
        validUsage(actual); for (String key : USAGE) require(number(expected, key) == number(actual, key), "native cumulative usage mismatch");
    }
    private static String optional(Json value, String key) { return value == null || value.member(key) == null ? null : value.member(key).text(); }
}
