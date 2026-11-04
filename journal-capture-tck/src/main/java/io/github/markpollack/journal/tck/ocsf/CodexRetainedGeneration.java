package io.github.markpollack.journal.tck.ocsf;

import io.github.markpollack.journal.tck.verify.Json;
import io.github.markpollack.journal.tck.verify.NeutralJson;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import static io.github.markpollack.journal.tck.ocsf.TerminalReading.*;

/** Independent validation of a Client thread-level Codex retained generation
 * ({@value #KIND}): one rollout, exactly two sealed exec turns, each turn its own process
 * (a replacement process resumes the same native thread; PID continuity is never the identity).
 * Every rollout byte is partitioned into the shared {@code session_meta} line and per-turn
 * regions by whole-line locators; per-turn originals, prefix continuity, emitted identity,
 * region membership and cumulative usage arithmetic are checked from original bytes.
 * {@link Verdict#REFUSED} is a contradiction; {@link Verdict#INCOMPLETE} is unestablished evidence.
 */
public final class CodexRetainedGeneration {
    public static final String KIND = "journal-codex-retained-generation/1";
    public static final String PROFILE = "client-terminal-codex-retained-0.166.0/1";
    static final List<String> USAGE = List.of("input_tokens", "cached_input_tokens", "cache_write_input_tokens", "output_tokens", "reasoning_output_tokens");
    private static final Set<String> FORBIDDEN = Set.of("fork", "--ephemeral", "--last");
    private CodexRetainedGeneration() { }

    public enum Verdict { REFUSED, INCOMPLETE }

    /** The reason a thread-level generation does not qualify. */
    public static final class Rejection extends IllegalArgumentException {
        private static final long serialVersionUID = 1L;
        private final Verdict verdict;
        Rejection(Verdict verdict, String reason, Throwable cause) { super(verdict + ": " + reason, cause); this.verdict = verdict; }
        public Verdict verdict() { return verdict; }
    }

    /** One validated turn: its declaration, per-turn manifest and frozen per-process originals. */
    public record Turn(int index, Json declaration, Json perTurnManifest, Path perTurnManifestPath, Map<String, byte[]> originals, long regionStart, long regionEnd) {
        public String executionId() { return string(declaration, "execution_id"); }
        public String nativeTurnId() { return string(declaration, "native_turn_uid"); }
    }

    /** A validated thread: manifest, final transcript bytes, shared locator and both turns. */
    @SuppressWarnings("ArrayRecordComponent") // the transcript is copied at the public boundary, never compared by record equality
    public record RetainedThread(Json manifest, Path base, byte[] transcript, Json shared, List<Turn> turns) {
        public RetainedThread { transcript = transcript.clone(); }
        @Override public byte[] transcript() { return transcript.clone(); }
        public String session() { return string(manifest, "native_session_id"); }
        public Turn turn(String executionId) {
            List<Turn> matches = turns.stream().filter(t -> executionId.equals(t.executionId())).toList();
            require(matches.size() == 1, Verdict.REFUSED, "retained execution absent/ambiguous"); return matches.get(0);
        }
        /** Shared session_meta line followed by exactly this turn's contiguous region: a single-turn rollout view. */
        public byte[] projection(Turn turn) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            out.writeBytes(Arrays.copyOfRange(transcript, (int) number(shared, "start"), (int) number(shared, "end")));
            out.writeBytes(Arrays.copyOfRange(transcript, (int) turn.regionStart(), (int) turn.regionEnd()));
            return out.toByteArray();
        }
    }

    public static RetainedThread validate(Json m, Path base) throws IOException {
        try { return read(m, base); }
        catch (Rejection r) { throw r; }
        catch (IllegalArgumentException e) { throw new Rejection(Verdict.REFUSED, e.getMessage(), e); }
    }

    private static RetainedThread read(Json m, Path base) throws IOException {
        expect(m, "kind", KIND); expect(m, "cli_version", "0.166.0");
        String session = string(m, "native_session_id"), cwd = string(m, "cwd");
        require(m.member("sealed") instanceof Json.JBool b && b.value(), Verdict.INCOMPLETE, "thread not sealed");
        require(m.member("overflow") instanceof Json.JBool b && !b.value(), Verdict.INCOMPLETE, "thread overflow");
        require(string(m, "native_path").endsWith("-" + session + ".jsonl"), Verdict.REFUSED, "native path does not name the thread");
        byte[] transcript = transcript(m, base);
        RetainedSource source = new RetainedSource(declaration(transcript, string(m.member("transcript"), "path")), base);
        List<Json> turns = array(m, "turns"); require(turns.size() == 2, Verdict.REFUSED, "bounded retained profile requires exactly two turns");
        Json shared = null; long covered = 0; List<Turn> result = new ArrayList<>();
        Set<String> executions = new HashSet<>(), connections = new HashSet<>(), nativeTurns = new HashSet<>();
        Map<String, Json> previous = null;
        for (int i = 0; i < turns.size(); i++) {
            Json t = turns.get(i);
            require(!"UNSEALED".equals(optional(t, "state")), Verdict.INCOMPLETE, "turn " + i + " unsealed: " + optional(t, "detail"));
            require(number(t, "invocation_index") == i, Verdict.REFUSED, "turn order differs from invocation index");
            require(executions.add(string(t, "execution_id")) && connections.add(string(t, "connection_id")) && nativeTurns.add(string(t, "native_turn_uid")), Verdict.REFUSED, "turn identity reused");
            closure(t, i); launch(t, i, session, cwd);
            Json sharedHere = sharedLocator(t, source, session, cwd);
            if (shared == null) { shared = sharedHere; covered = number(shared, "end"); }
            else require(Json.sameValue(shared, sharedHere), Verdict.REFUSED, "shared session_meta locator differs between turns");
            long start = covered;
            for (Json locator : array(t.member("native_records"), "records")) {
                require(number(locator, "start") == covered, Verdict.REFUSED, "rollout partition gap/overlap at " + covered);
                source.extent(locator); covered = number(locator, "end");
            }
            require(covered > start, Verdict.REFUSED, "turn region empty");
            prefix(t, transcript, covered);
            Map<String, byte[]> originals = originals(t, base);
            Json perTurn = perTurn(t, base, i, session, originals, previous);
            Path perTurnPath = resolve(base, string(t, "per_turn_manifest"));
            region(t, transcript, start, covered, originals, session, cwd);
            usage(t, previous == null ? null : previous.get("thread"));
            previous = Map.of("thread", t.member("thread_token_usage"), "after", t.member("native_prefix_after"));
            result.add(new Turn(i, t, perTurn, perTurnPath, originals, start, covered));
        }
        require(covered == transcript.length, Verdict.REFUSED, "rollout partition omits original bytes");
        return new RetainedThread(m, base, transcript, shared, List.copyOf(result));
    }

    private static byte[] transcript(Json m, Path base) throws IOException {
        Json t = m.member("transcript");
        require(t instanceof Json.JObject && !"ABSENT".equals(optional(t, "state")), Verdict.INCOMPLETE, "final transcript not observed");
        expect(t, "role", "transcript"); require(string(t, "origin").equals(string(m, "native_path")), Verdict.REFUSED, "transcript origin differs from native path");
        byte[] bytes = Files.readAllBytes(resolve(base, string(t, "path")));
        require(number(t, "bytes") == bytes.length && string(t, "sha256").equals(NativeArtifact.sha256(bytes)), Verdict.REFUSED, "final transcript changed");
        require(t.member("final_equals_last_sealed_prefix") instanceof Json.JBool b && b.value(), Verdict.INCOMPLETE, "final transcript differs from last sealed prefix");
        Json last = t.member("last_sealed_prefix");
        require(number(last, "bytes") == bytes.length && string(last, "sha256").equals(string(t, "sha256")), Verdict.REFUSED, "last sealed prefix contradicts final transcript");
        require(bytes.length > 0 && bytes[bytes.length - 1] == '\n', Verdict.REFUSED, "transcript tail incomplete");
        return bytes;
    }

    private static Json declaration(byte[] bytes, String path) {
        Map<String, Json> d = new LinkedHashMap<>();
        d.put("source", new Json.JString("TRANSCRIPT")); d.put("path", new Json.JString(path)); d.put("origin", new Json.JString("thread-transcript"));
        d.put("sha256", new Json.JString(NativeArtifact.sha256(bytes))); d.put("size", num(bytes.length)); d.put("observed", num(bytes.length)); d.put("dropped", num(0));
        return new Json.JObject(d);
    }

    private static void closure(Json t, int i) {
        require(t.member("complete") instanceof Json.JBool b && b.value(), Verdict.INCOMPLETE, "turn " + i + " not complete");
        for (String key : List.of("stdin_closed", "stdout_natural_eof", "stderr_natural_eof", "observed_resources_terminated"))
            require(t.member(key) instanceof Json.JBool b && b.value(), Verdict.INCOMPLETE, "turn " + i + " closure not observed: " + key);
        require(number(t, "exit_code") == 0, Verdict.REFUSED, "turn " + i + " process failed");
        array(t, "pids");
    }

    private static void launch(Json t, int i, String session, String cwd) {
        List<String> argv = array(t, "argv").stream().map(Json::text).toList();
        require(argv.size() > 4 && argv.stream().noneMatch(FORBIDDEN::contains), Verdict.REFUSED, "forbidden history option");
        require(argv.contains("exec") && argv.contains("--json") && argv.contains("--ignore-user-config") && "-".equals(argv.get(argv.size() - 1)), Verdict.REFUSED, "unsupported exec route");
        require(cwd.equals(value(argv, "-C")) || cwd.equals(value(argv, "--cd")), Verdict.REFUSED, "argv cwd differs from thread cwd");
        int exec = argv.indexOf("exec"); boolean resume = exec + 1 < argv.size() && "resume".equals(argv.get(exec + 1));
        if (i == 0) {
            require(!resume && !argv.contains("resume"), Verdict.REFUSED, "first turn must be a fresh exec");
            require(t.member("resume_thread_id").isNull(), Verdict.REFUSED, "first turn declares a resumed thread");
            require("stdout".equals(string(t, "identity_source")), Verdict.INCOMPLETE, "first turn identity not CLI-emitted");
        }
        else {
            require(resume && argv.subList(exec + 2, argv.size()).contains(session), Verdict.REFUSED, "follow-up turn does not resume the emitted thread");
            require(session.equals(string(t, "resume_thread_id")), Verdict.REFUSED, "resumed thread differs from native session");
            require(Set.of("stdout", "resume-argv").contains(string(t, "identity_source")), Verdict.REFUSED, "unknown identity source");
        }
    }

    private static Json sharedLocator(Json t, RetainedSource source, String session, String cwd) {
        List<Json> shared = array(t.member("native_records"), "shared");
        require(shared.size() == 1 && number(shared.get(0), "start") == 0, Verdict.REFUSED, "exactly one shared session_meta locator at offset 0 required");
        Json meta = source.record(shared.get(0)); expect(meta, "type", "session_meta"); Json payload = meta.member("payload");
        expect(payload, "id", session); expect(payload, "cli_version", "0.166.0"); expect(payload, "cwd", cwd); expect(payload, "source", "exec");
        require(payload.member("forked_from_id") == null || payload.member("forked_from_id").isNull(), Verdict.REFUSED, "forked native history");
        return shared.get(0);
    }

    private static void prefix(Json t, byte[] transcript, long end) {
        Json after = t.member("native_prefix_after");
        require(number(after, "bytes") == end && string(after, "sha256").equals(NativeArtifact.sha256(Arrays.copyOf(transcript, (int) end))), Verdict.REFUSED, "native prefix after turn differs from its region end");
    }

    private static Map<String, byte[]> originals(Json t, Path base) throws IOException {
        Map<String, byte[]> originals = new LinkedHashMap<>();
        for (Json s : array(t, "sources")) {
            String role = string(s, "role"); require(Set.of("STDIN", "STDOUT", "STDERR").contains(role), Verdict.REFUSED, "unsupported per-turn source role");
            require(s.member("natural_eof") instanceof Json.JBool b && b.value(), Verdict.INCOMPLETE, "per-turn source without natural EOF");
            byte[] bytes = Files.readAllBytes(resolve(base, string(s, "path")));
            require(number(s, "bytes") == bytes.length && number(s, "observed") == bytes.length && string(s, "sha256").equals(NativeArtifact.sha256(bytes)), Verdict.REFUSED, "per-turn original changed");
            require(originals.put(role, bytes) == null, Verdict.REFUSED, "per-turn source role repeated");
        }
        require(originals.keySet().equals(Set.of("STDIN", "STDOUT", "STDERR")), Verdict.INCOMPLETE, "per-turn originals missing");
        return originals;
    }

    private static Json perTurn(Json t, Path base, int i, String session, Map<String, byte[]> originals, Map<String, Json> previous) throws IOException {
        require(t.member("per_turn_manifest") instanceof Json.JString, Verdict.INCOMPLETE, "per-turn manifest missing");
        Path path = resolve(base, string(t, "per_turn_manifest")); Json m = NeutralJson.readDocument(path);
        expect(m, "kind", "agent-journal/acquired-execution"); expect(m, "acquisition_uid", string(t, "execution_id"));
        expect(m.member("run"), "session_uid", session); require(number(m.member("run"), "invocation_index") == i, Verdict.REFUSED, "per-turn invocation index differs");
        Json process = m.member("process"); expect(process, "connection_uid", string(t, "connection_id"));
        require(Json.sameValue(process.member("argv"), t.member("argv")), Verdict.REFUSED, "per-turn argv differs");
        require(process.member("fresh_session") instanceof Json.JBool f && f.value() == (i == 0), Verdict.REFUSED, "per-turn fresh_session contradicts turn index");
        require(process.member("native_path_preexisting") instanceof Json.JBool p && p.value() == (i > 0), Verdict.REFUSED, "per-turn native_path_preexisting contradicts turn index");
        List<Json> turns = array(m, "turns"); require(turns.size() == 1, Verdict.REFUSED, "per-turn manifest must hold one turn");
        Json turn = turns.get(0); expect(turn, "native_turn_uid", string(t, "native_turn_uid")); expect(turn, "invocation_uid", string(t, "execution_id"));
        require(Json.sameValue(turn.member("wire_usage"), t.member("wire_usage")) && Json.sameValue(turn.member("native_prefix_after"), t.member("native_prefix_after")), Verdict.REFUSED, "per-turn usage/prefix differs from thread");
        Json before = turn.member("native_prefix_before");
        if (i == 0) { fields(before, "state observation"); expect(before, "state", "ABSENT"); expect(before, "observation", "before-first-dispatch"); }
        else require(Json.sameValue(before, previous.get("after")), Verdict.REFUSED, "turn before-prefix differs from previous after-prefix");
        Path dir = path.getParent();
        for (Json s : array(m, "sources")) {
            String role = string(s, "role"); byte[] bytes = Files.readAllBytes(dir.resolve(string(s, "path")));
            require(string(s, "sha256").equals(NativeArtifact.sha256(bytes)), Verdict.REFUSED, "per-turn manifest original changed");
            String stream = Map.of("stdin", "STDIN", "stream", "STDOUT", "stderr", "STDERR").get(role);
            if (stream != null) require(Arrays.equals(originals.get(stream), bytes), Verdict.REFUSED, "per-turn original differs from thread source");
            else require(number(t.member("native_prefix_after"), "bytes") == bytes.length && string(t.member("native_prefix_after"), "sha256").equals(string(s, "sha256")), Verdict.REFUSED, "prefix snapshot differs from declared after-prefix");
        }
        return m;
    }

    private static void region(Json t, byte[] transcript, long start, long end, Map<String, byte[]> originals, String session, String cwd) {
        String turn = string(t, "native_turn_uid"), prompt = new String(originals.get("STDIN"), StandardCharsets.UTF_8);
        require(!prompt.isBlank(), Verdict.REFUSED, "blank prompt");
        List<Json> rows = new String(Arrays.copyOfRange(transcript, (int) start, (int) end), StandardCharsets.UTF_8).lines().map(NeutralJson::parse).toList();
        int started = 0, completed = 0, contexts = 0, prompts = 0; Json usageRecord = null, lastTotal = null;
        for (Json row : rows) {
            Json p = row.member("payload"); require(p instanceof Json.JObject, Verdict.REFUSED, "native payload missing");
            for (String key : List.of("session_id", "thread_id")) if (p.member(key) != null && !p.member(key).isNull()) require(session.equals(string(p, key)), Verdict.REFUSED, "foreign thread in region");
            for (String key : List.of("turn_id", "root_turn_id")) if (p.member(key) != null && !p.member(key).isNull()) require(turn.equals(string(p, key)), Verdict.REFUSED, "foreign turn in region");
            switch (string(row, "type")) {
                case "session_meta" -> throw new Rejection(Verdict.REFUSED, "session_meta inside a turn region", null);
                case "turn_context" -> { contexts++; expect(p, "turn_id", turn); expect(p, "cwd", cwd); }
                case "token_usage_record" -> { expect(p, "turn_id", turn); usageRecord = p; }
                case "response_item" -> { if ("message".equals(optional(p, "type")) && "user".equals(optional(p, "role"))) prompts += prompt(p, prompt) ? 1 : 0; }
                case "event_msg" -> {
                    switch (string(p, "type")) {
                        case "task_started" -> { started++; expect(p, "turn_id", turn); }
                        case "task_complete" -> { completed++; expect(p, "turn_id", turn); require(p.member("error") == null || p.member("error").isNull(), Verdict.REFUSED, "native completion error"); }
                        case "token_count" -> lastTotal = p.path("info", "total_token_usage");
                        case "turn_aborted", "task_failed", "error" -> throw new Rejection(Verdict.REFUSED, "failed native turn", null);
                        default -> { }
                    }
                }
                default -> { }
            }
        }
        require(started == 1 && completed == 1 && contexts == 1, Verdict.REFUSED, "turn lifecycle population differs");
        require(prompts == 1, Verdict.REFUSED, "exact original prompt membership missing or ambiguous");
        if (usageRecord == null || lastTotal == null) throw new Rejection(Verdict.INCOMPLETE, "native usage witnesses missing", null);
        same(t.member("turn_token_usage"), usageRecord.member("turn_token_usage"), "turn usage differs from native record");
        same(t.member("thread_token_usage"), usageRecord.member("thread_token_usage"), "thread usage differs from native record");
        same(t.member("total_token_usage"), lastTotal, "total usage differs from native token_count");
        wire(t, originals.get("STDOUT"), session);
    }

    private static boolean prompt(Json p, String prompt) {
        List<Json> content = array(p, "content");
        boolean match = content.size() == 1 && "input_text".equals(optional(content.get(0), "type")) && prompt.equals(rawString(content.get(0), "text"));
        boolean context = content.stream().allMatch(b -> "input_text".equals(optional(b, "type")) && (rawString(b, "text").startsWith("<environment_context>") || rawString(b, "text").startsWith("# AGENTS.md instructions for ")));
        require(match || context, Verdict.REFUSED, "unassigned native user history"); return match;
    }

    private static void wire(Json t, byte[] stdout, String session) {
        List<Json> rows = new String(stdout, StandardCharsets.UTF_8).lines().map(NeutralJson::parse).toList();
        require(rows.size() >= 3, Verdict.INCOMPLETE, "stdout lifecycle incomplete");
        int index = 0;
        if ("thread.started".equals(optional(rows.get(0), "type"))) { expect(rows.get(0), "thread_id", session); index = 1; }
        else require(!"stdout".equals(string(t, "identity_source")), Verdict.REFUSED, "identity source claims stdout without thread.started");
        expect(rows.get(index), "type", "turn.started"); expect(rows.get(rows.size() - 1), "type", "turn.completed");
        for (Json row : rows) {
            require(!Set.of("error", "turn.failed").contains(optional(row, "type")), Verdict.REFUSED, "failed stdout turn");
            if (row.member("thread_id") != null) expect(row, "thread_id", session);
        }
        require(rows.stream().anyMatch(r -> "item.completed".equals(optional(r, "type")) && "agent_message".equals(optional(r.member("item"), "type"))), Verdict.REFUSED, "stdout text response absent");
        same(t.member("wire_usage"), rows.get(rows.size() - 1).member("usage"), "declared wire usage differs from stdout");
    }

    private static void usage(Json t, Json previousThread) {
        Json turn = t.member("turn_token_usage"), thread = t.member("thread_token_usage");
        same(t.member("total_token_usage"), thread, "total usage differs from cumulative thread usage");
        for (String key : USAGE) {
            long before = previousThread == null ? 0 : number(previousThread, key);
            require(number(thread, key) - before == number(turn, key), Verdict.REFUSED, "cumulative usage arithmetic fails for " + key);
        }
        require(scope(t) != null, Verdict.REFUSED, "wire usage is neither per-turn nor cumulative");
    }

    /** "per-turn" when stdout usage equals the native turn usage, "cumulative" when it equals the thread usage; an observation, not a claim. */
    public static String scope(Json t) {
        Json wire = t.member("wire_usage");
        if (equal(wire, t.member("turn_token_usage"))) return "per-turn";
        return equal(wire, t.member("thread_token_usage")) ? "cumulative" : null;
    }

    private static boolean equal(Json a, Json b) { return USAGE.stream().allMatch(k -> number(a, k) == number(b, k)); }
    private static void same(Json expected, Json actual, String reason) {
        require(expected instanceof Json.JObject && actual instanceof Json.JObject, Verdict.INCOMPLETE, reason + ": usage absent");
        for (String key : USAGE) require(number(expected, key) >= 0 && number(expected, key) == number(actual, key), Verdict.REFUSED, reason);
    }

    /** Relative paths stay under the base; absolute paths are admitted only when they resolve inside the base. */
    static Path resolve(Path base, String text) throws IOException {
        Path real = base.toRealPath(), path = Path.of(text);
        Path resolved = path.isAbsolute() ? path : real.resolve(path);
        require(!path.normalize().startsWith("..") && Files.isRegularFile(resolved), Verdict.INCOMPLETE, "referenced file missing: " + text);
        require(resolved.toRealPath().startsWith(real), Verdict.REFUSED, "referenced file escapes the generation: " + text);
        return resolved.toRealPath();
    }

    private static String value(List<String> argv, String flag) { int i = argv.indexOf(flag); return i >= 0 && i + 1 < argv.size() ? argv.get(i + 1) : null; }
    private static String optional(Json v, String key) { return v == null || v.member(key) == null || v.member(key).isNull() ? null : v.member(key).text(); }
    private static Json.JNumber num(long n) { return new Json.JNumber(Long.toString(n)); }
    private static void require(boolean pass, Verdict verdict, String reason) { if (!pass) throw new Rejection(verdict, reason, null); }
}
