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
    static void validate(Path base, Jv m, Map<String, Jv> roles) throws IOException { validate(base, m, roles, false, false); }

    /** A retained turn's projection is one turn's rollout view; its stdout usage is compared with the declared
     * per-turn and cumulative native usage, never assumed equal to the thread total. {@code ordinary} applies the native-observed
     * shapes of the /2 profile (a thread_settings_applied record must carry thread_id); the frozen /1 profile admits what it admitted. */
    static void validate(Path base, Jv m, Map<String, Jv> roles, boolean retained, boolean ordinary) throws IOException {
        Jv declared = array(m, "turns").get(0);
        String session = text(m.member("run"), "session_uid"), turn = text(declared, "native_turn_uid");
        List<Jv> stream = rows(base, roles.get("stream")), nativeRows = rows(base, roles.get("transcript"));
        String prompt = utf8(bytes(base, roles.get("stdin")));
        require(!prompt.isBlank() && !prompt.startsWith("\uFEFF"), "Codex prompt must be nonblank UTF-8 without BOM");
        Jv usage = wire(stream, session, retained && !"stdout".equals(text(declared, "identity_source")));
        if (!retained) { nativeSource(nativeRows, session, turn, text(m.member("process"), "cwd"), prompt, usage); return; }
        usage(declared.member("wire_usage"), usage);
        require(scope(declared) != null && scope(declared).equals(text(m, "wire_usage_scope")), "Codex wire usage scope differs from declaration");
        nativeSource(nativeRows, session, turn, text(m.member("process"), "cwd"), prompt, declared.member("thread_token_usage"));
        if (ordinary) for (Jv row : nativeRows) if ("thread_settings_applied".equals(optional(row.member("payload"), "type"))) require(row.member("payload").member("thread_id") != null && !row.member("payload").member("thread_id").isNull(), "Codex ordinary profile requires thread_id on thread_settings_applied");
    }
    static String scope(Jv turn) {
        Jv wire = turn.member("wire_usage");
        if (same(wire, turn.member("turn_token_usage"))) return "per-turn";
        return same(wire, turn.member("thread_token_usage")) ? "cumulative" : null;
    }
    private static boolean same(Jv a, Jv b) { return a instanceof Jv.Obj && b instanceof Jv.Obj && COMPONENTS.stream().allMatch(k -> number(a, k) == number(b, k)); }
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
    private static Jv wire(List<Jv> rows, String session, boolean resumedWithoutEmittedIdentity) {
        require(rows.size() >= (resumedWithoutEmittedIdentity ? 3 : 4), "Codex stdout lifecycle incomplete");
        int first = 0;
        if (resumedWithoutEmittedIdentity) require(!"thread.started".equals(optional(rows.get(0), "type")), "Codex identity source contradicts emitted thread.started");
        else { equal(rows.get(0), "type", "thread.started"); equal(rows.get(0), "thread_id", session); first = 1; }
        equal(rows.get(first), "type", "turn.started"); equal(rows.get(rows.size() - 1), "type", "turn.completed");
        for (String type : List.of("turn.started", "turn.completed")) require(count(rows, type, false) == 1, "Codex stdout lifecycle repeated");
        require(count(rows, "thread.started", false) == (resumedWithoutEmittedIdentity ? 0 : 1), "Codex stdout lifecycle repeated");
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
