package io.github.markpollack.journal.ocsf.writer;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import static io.github.markpollack.journal.ocsf.writer.TerminalValues.*;

/** Admission of the explicitly selected successful fresh Codex terminal acquisition. */
final class TerminalCodexProfile {
    static final String PROFILE = "client-terminal-codex-0.166.0/1";
    private TerminalCodexProfile() { }
    static boolean complete(TerminalContract.Input input, Jv a) {
        return PROFILE.equals(text(Jv.parse(input.envelope()), "profile")) && "COMPLETED".equals(text(a, "acquisition"));
    }
    static void validate(Path manifest, Jv d, Jv a) throws IOException {
        Jv m = Jv.parse(Files.readString(manifest)); header(m, d, a);
        Map<String, Jv> occurrences = new HashMap<>();
        for (Jv s : array(a, "sources")) {
            require(occurrences.put(text(s, "relativePath"), s) == null, "duplicate Codex occurrence");
            require(number(s, "start") == 0 && number(s, "end") == number(s, "size"), "whole Codex source required");
            require(s.member("nativeTurnId").isNull() && s.member("nativeConnectionId").isNull(), "whole-source native turn/connection unsupported");
            equal(s, "nativeSessionId", text(m.member("run"), "session_uid")); equal(s, "clientConnectionId", text(d, "connectionId"));
        }
        require(occurrences.containsKey("acquisition.json"), "Codex acquisition not associated");
        require(occurrences.get("acquisition.json").member("stream").isNull(), "manifest is not a stream");
        Set<String> paths = new HashSet<>(); paths.add("acquisition.json");
        Map<String, Jv> roles = new HashMap<>();
        for (Jv s : array(m, "sources")) {
            String path = text(s, "path"), role = text(s, "role");
            require(paths.add(path) && roles.put(role, s) == null, "duplicate Codex source");
            require(Set.of("stdin", "stream", "stderr", "transcript").contains(role), "unsupported Codex source role");
            Jv occurrence = occurrences.get(path); require(occurrence != null, "Codex source not associated");
            source(s, occurrence, m, d);
        }
        require(roles.keySet().equals(Set.of("stdin", "stream", "stderr", "transcript")) && paths.equals(occurrences.keySet()), "Codex original inventory differs");
        TerminalCodexNative.validate(manifest.getParent(), m, roles);
    }

    private static void header(Jv m, Jv d, Jv a) {
        equal(m, "kind", "agent-journal/acquired-execution"); equal(m, "version", "0.1");
        equal(m, "profile", "codex-cli-0.166.0-exec-stdio-rollout"); equal(m, "profile_id", "codex-cli/0.166.0/exec-stdio+rollout");
        flag(m, "complete", true); require(array(m, "capture_errors").isEmpty() && array(a, "missing").isEmpty(), "Codex capture incomplete");
        equal(d.member("outcome"), "kind", "SUCCESS"); equal(d.member("provider"), "kind", "REPORTED_SUCCESS");
        equal(m, "acquisition_uid", text(d, "executionId")); equal(m.member("run"), "run_uid", text(d, "executionId"));
        equal(m.path("run", "cli"), "name", "Codex CLI"); equal(m.path("run", "cli"), "version", "0.166.0");
        equal(m.path("run", "launch_settings"), "route", "exec_stdio");
        process(m, d, a);
    }
    private static void process(Jv m, Jv d, Jv a) {
        Jv p = m.member("process"); equal(p, "connection_uid", text(d, "connectionId")); equal(p, "client_session_uid", text(d, "sessionId"));
        require(array(a, "connectionIds").size() == 1 && array(a, "connectionIds").get(0).text().equals(text(p, "connection_uid")), "Codex generation differs");
        equal(m.path("run", "launch_settings"), "working_directory", text(p, "cwd"));
        for (String key : List.of("fresh_session", "sealed", "observed_resources_terminated", "stdin_closed", "stdout_natural_eof", "stderr_natural_eof")) flag(p, key, true);
        for (String key : List.of("native_path_preexisting", "overflow")) flag(p, key, false);
        require(number(p, "exit_code") == 0, "Codex process did not exit successfully");
        require(array(m, "turns").size() == 1, "Codex fresh profile requires one turn");
        Jv turn = array(m, "turns").get(0); equal(turn, "invocation_uid", text(d, "executionId")); equal(turn, "decision_uid", text(d, "decisionId")); text(turn, "native_turn_uid");
        launch(p);
    }
    static void launch(Jv p) {
        List<String> argv = array(p, "argv").stream().map(Jv::text).toList();
        require(argv.containsAll(List.of("--no-daemon", "exec", "--ignore-user-config", "--json")) && "-".equals(argv.get(argv.size() - 1)), "wrong Codex original stdio route");
        require(argv.stream().noneMatch(Set.of("resume", "fork", "--ephemeral", "--last")::contains), "Codex history reuse forbidden");
        require(option(argv, "-C", text(p, "cwd")) || option(argv, "--cd", text(p, "cwd")), "Codex argv cwd differs");
        for (List<String> flags : List.of(List.of("--sandbox", "-s"), List.of("--ask-for-approval", "-a"), List.of("--model", "-m"))) require(flags.stream().anyMatch(argv::contains), "Codex explicit launch option missing");
        require(argv.stream().anyMatch(s -> s != null && s.startsWith("model_reasoning_effort=")), "Codex effort missing");
    }
    private static boolean option(List<String> argv, String flag, String value) { int i = argv.indexOf(flag); return i >= 0 && i + 1 < argv.size() && value.equals(argv.get(i + 1)); }

    private static void source(Jv s, Jv occurrence, Jv m, Jv d) {
        equal(s, "state", "acquired"); equal(s, "end", "EOF"); equal(s, "acquisition", text(d, "executionId")); flag(s, "required", true);
        equal(occurrence, "sha256", text(s, "sha256")); equal(occurrence, "origin", text(s, "origin"));
        long bytes = number(s, "bytes"); require(bytes == number(occurrence, "size") && bytes == number(s, "observed") && number(s, "dropped") == 0, "Codex extent/drop differs");
        String role = text(s, "role"), stream = Map.of("stdin", "STDIN", "stream", "STDOUT", "stderr", "STDERR").get(role);
        flag(s, "archive_only", Set.of("stdin", "stderr").contains(role));
        if (stream == null) { require(occurrence.member("stream").isNull(), "native file is not a stream"); equal(s, "owner_session_uid", text(m.member("run"), "session_uid")); }
        else {
            equal(occurrence, "stream", stream); Jv turn = array(m, "turns").get(0);
            require(number(turn.member("start_offsets"), stream) == 0 && number(turn.member("end_offsets"), stream) == bytes, "Codex stream span differs");
        }
    }
    static void flag(Jv value, String key, boolean expected) { require(value.member(key) instanceof Jv.Bool b && b.value() == expected, "invalid Codex " + key); }
    static void require(boolean condition, String reason) { if (!condition) throw new IllegalArgumentException(reason); }
}
