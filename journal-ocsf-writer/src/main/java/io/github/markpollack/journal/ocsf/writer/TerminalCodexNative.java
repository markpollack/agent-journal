package io.github.markpollack.journal.ocsf.writer;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import static io.github.markpollack.journal.ocsf.writer.TerminalValues.*;
import static io.github.markpollack.journal.ocsf.writer.TerminalCodexProfile.require;

/** Original vendor witness checks before terminal assembly; no dependency on the TCK. */
final class TerminalCodexNative {
    private static final List<String> COMPONENTS = List.of("input_tokens", "cached_input_tokens", "cache_write_input_tokens", "output_tokens", "reasoning_output_tokens");
    private TerminalCodexNative() { }
    static void validate(Path base, Jv m, Map<String, Jv> roles) throws IOException {
        String session = text(m.member("run"), "session_uid"), turn = text(array(m, "turns").get(0), "native_turn_uid");
        List<Jv> stream = rows(base, roles.get("stream")), nativeRows = rows(base, roles.get("transcript"));
        String prompt = utf8(bytes(base, roles.get("stdin")));
        require(!prompt.isBlank() && !prompt.startsWith("\uFEFF"), "Codex prompt must be nonblank UTF-8 without BOM");
        Jv usage = wire(stream, session);
        nativeSource(nativeRows, session, turn, text(m.member("process"), "cwd"), prompt, usage);
    }
    private static byte[] bytes(Path base, Jv source) throws IOException {
        byte[] bytes = Files.readAllBytes(base.resolve(relative(text(source, "path"))));
        require(bytes.length == number(source, "bytes") && SchemaPin.sha256(bytes).equals(text(source, "sha256")), "Codex original changed"); return bytes;
    }
    private static String utf8(byte[] bytes) throws java.nio.charset.CharacterCodingException {
        return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
    }
    private static List<Jv> rows(Path base, Jv source) throws IOException {
        byte[] bytes = bytes(base, source); require(bytes.length > 0 && bytes[bytes.length - 1] == '\n', "Codex original line incomplete");
        List<Jv> rows = new ArrayList<>(); for (String line : utf8(bytes).split("\n")) rows.add(Jv.parse(line));
        require(rows.size() == number(source, "records"), "Codex record extent differs"); return rows;
    }
    private static Jv wire(List<Jv> rows, String session) {
        require(rows.size() >= 4, "Codex stdout lifecycle incomplete"); equal(rows.get(0), "type", "thread.started"); equal(rows.get(0), "thread_id", session);
        equal(rows.get(1), "type", "turn.started"); equal(rows.get(rows.size() - 1), "type", "turn.completed");
        for (String type : List.of("thread.started", "turn.started", "turn.completed")) require(count(rows, type, false) == 1, "Codex stdout lifecycle repeated");
        for (Jv row : rows) {
            require(!Set.of("error", "turn.failed").contains(text(row, "type")), "Codex failed stdout");
            if (row.member("thread_id") != null) equal(row, "thread_id", session);
        }
        require(rows.stream().anyMatch(r -> "item.completed".equals(optional(r, "type")) && "agent_message".equals(optional(r.member("item"), "type"))), "Codex text response absent");
        Jv usage = rows.get(rows.size() - 1).member("usage"); usage(usage, usage); return usage;
    }
    private static void nativeSource(List<Jv> rows, String session, String turn, String cwd, String prompt, Jv usage) {
        require(!rows.isEmpty(), "Codex native source missing"); equal(rows.get(0), "type", "session_meta"); Jv meta = rows.get(0).member("payload");
        equal(meta, "id", session); equal(meta, "cli_version", "0.166.0"); equal(meta, "cwd", cwd); equal(meta, "source", "exec");
        require(meta.member("forked_from_id") == null || meta.member("forked_from_id").isNull(), "Codex native fork history");
        for (String type : List.of("session_meta", "turn_context")) require(count(rows, type, false) == 1, "Codex native context repeated");
        for (String type : List.of("task_started", "task_complete")) require(count(rows, type, true) == 1, "Codex native lifecycle repeated");
        equal(rows.get(rows.size() - 1).member("payload"), "type", "task_complete");
        Scan scan = new Scan(session, turn, cwd, prompt); rows.forEach(scan::read);
        require(scan.prompts == 1, "Codex original prompt membership differs"); usage(usage, scan.total); if (scan.recorded != null) usage(usage, scan.recorded);
    }
    private static final class Scan {
        private final String session, turn, cwd, prompt;
        private Jv total, recorded;
        private int prompts;
        private boolean started;
        Scan(String session, String turn, String cwd, String prompt) { this.session = session; this.turn = turn; this.cwd = cwd; this.prompt = prompt; }
        void read(Jv row) {
            Jv p = row.member("payload"); require(p instanceof Jv.Obj, "Codex native payload missing"); identities(p, session, turn);
            switch (text(row, "type")) {
                case "turn_context" -> { require(started, "Codex context precedes start"); equal(p, "turn_id", turn); equal(p, "cwd", cwd); }
                case "token_usage_record" -> { equal(p, "thread_id", session); equal(p, "turn_id", turn); recorded = p.member("thread_token_usage"); }
                case "response_item" -> user(row);
                case "event_msg" -> event(p);
                default -> { }
            }
        }
        private void user(Jv row) {
            Jv p = row.member("payload");
            if (!"message".equals(optional(p, "type")) || !"user".equals(optional(p, "role"))) return;
            if (matches(row, prompt)) { require(started, "Codex prompt precedes start"); prompts++; }
            else require(context(p), "Codex unassigned native user history");
        }
        private void event(Jv p) {
            switch (text(p, "type")) {
                case "task_started" -> { started = true; equal(p, "turn_id", turn); }
                case "task_complete" -> { require(started, "Codex completion precedes start"); equal(p, "turn_id", turn); require(p.member("error") == null || p.member("error").isNull(), "Codex native completion error"); }
                case "token_count" -> total = p.path("info", "total_token_usage");
                case "error", "turn_aborted", "task_failed" -> throw new IllegalArgumentException("Codex failed native turn");
                default -> { }
            }
        }
    }
    private static boolean context(Jv p) {
        return array(p, "content").stream().allMatch(b -> "input_text".equals(optional(b, "type"))
                && (text(b, "text").startsWith("<environment_context>") || text(b, "text").startsWith("# AGENTS.md instructions for ")));
    }

    private static boolean matches(Jv row, String prompt) {
        Jv p = row.member("payload");
        if (!"response_item".equals(optional(row, "type")) || !"message".equals(optional(p, "type")) || !"user".equals(optional(p, "role"))) return false;
        List<Jv> content = array(p, "content"); return content.size() == 1 && "input_text".equals(optional(content.get(0), "type")) && prompt.equals(optional(content.get(0), "text"));
    }
    private static void identities(Jv p, String session, String turn) {
        for (String key : List.of("session_id", "thread_id")) if (p.member(key) != null && !p.member(key).isNull()) equal(p, key, session);
        for (String key : List.of("turn_id", "root_turn_id")) if (p.member(key) != null && !p.member(key).isNull()) equal(p, key, turn);
        nestedIdentities(p, turn);
    }
    private static void nestedIdentities(Jv p, String turn) {
        Jv metadata = p.member("internal_chat_message_metadata_passthrough");
        if (metadata != null && metadata.member("turn_id") != null) equal(metadata, "turn_id", turn);
        Jv a = p.member("turn_attribution"); if (a == null) return;
        equal(a, "turn_id", turn); equal(a, "root_turn_id", turn); require(a.member("parent_turn_id") == null || a.member("parent_turn_id").isNull(), "Codex native parent present");
    }
    private static void usage(Jv wire, Jv nativeValue) {
        require(wire instanceof Jv.Obj && nativeValue instanceof Jv.Obj, "Codex usage absent");
        for (String key : COMPONENTS) require(number(wire, key) >= 0 && number(wire, key) == number(nativeValue, key), "Codex native cumulative usage differs");
    }
    private static long count(List<Jv> rows, String type, boolean payload) { return rows.stream().filter(r -> type.equals(optional(payload ? r.member("payload") : r, "type"))).count(); }
    private static String optional(Jv value, String key) { return value == null || value.member(key) == null ? null : value.member(key).text(); }
}
