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
    /** The version-neutral Codex one-shot profile: any Codex CLI version on the exec route. */
    static final String PROFILE = CodexExecFamily.TERMINAL_PROFILE;
    /** The versioned profile earlier 0.166.0 one-shot recordings carry; still received, for 0.166.0 only. */
    static final String LEGACY_PROFILE = CodexExecFamily.LEGACY_TERMINAL_PROFILE;
    private TerminalCodexProfile() { }
    static boolean complete(TerminalContract.Input input, Jv a) {
        String profile = text(Jv.parse(input.envelope()), "profile");
        return (LEGACY_PROFILE.equals(CodexExecFamily.contract(profile)) || TerminalContract.CODEX_RETAINED_PROFILES.contains(CodexExecFamily.contract(profile))) && "COMPLETED".equals(text(a, "acquisition"));
    }
    /** The retained profile admits one turn of a sealed thread; the one-shot profile refuses any thread declaration. */
    static void validate(Path manifest, TerminalContract.Input input, Jv a) throws IOException {
        String profile = text(Jv.parse(input.envelope()), "profile"); boolean retained = TerminalContract.CODEX_RETAINED_PROFILES.contains(CodexExecFamily.contract(profile));
        Jv d = input.decision();
        Jv m = Jv.parse(Files.readString(manifest)); header(m, d, a, retained, profile);
        Map<String, Jv> occurrences = new HashMap<>();
        for (Jv s : array(a, "sources")) {
            require(occurrences.put(text(s, "relativePath"), s) == null, "duplicate Codex occurrence");
            require(number(s, "start") == 0 && number(s, "end") == number(s, "size"), "whole Codex source required");
            require(s.member("nativeTurnId").isNull() && s.member("nativeConnectionId").isNull(), "whole-source native turn/connection unsupported");
            equal(s, "nativeSessionId", text(m.member("run"), "session_uid")); equal(s, "clientConnectionId", text(d, "connectionId"));
        }
        require(occurrences.containsKey("acquisition.json"), "Codex acquisition not associated");
        require(occurrences.get("acquisition.json").member("stream").isNull(), "manifest is not a stream");
        Map<String, Jv> roles = sources(m, d, occurrences, retained);
        TerminalCodexNative.validate(manifest.getParent(), m, roles, retained, TerminalContract.CODEX_RETAINED_ORDINARY_PROFILE.equals(CodexExecFamily.contract(profile)));
        if (retained) TerminalCodexRetained.validate(manifest.getParent(), m, profile); else require(m.member("retained_generation") == null, "one-shot Codex profile carries no thread");
    }

    private static Map<String, Jv> sources(Jv m, Jv d, Map<String, Jv> occurrences, boolean retained) {
        Set<String> paths = new HashSet<>(); paths.add("acquisition.json");
        Map<String, Jv> roles = new HashMap<>();
        for (Jv s : array(m, "sources")) {
            String path = text(s, "path"), role = text(s, "role");
            require(paths.add(path) && roles.put(role, s) == null, "duplicate Codex source");
            boolean generation = retained && role.startsWith("generation-");
            require(generation || Set.of("stdin", "stream", "stderr", "transcript").contains(role), "unsupported Codex source role");
            Jv occurrence = occurrences.get(path); require(occurrence != null, "Codex source not associated");
            source(s, occurrence, m, d, generation);
        }
        require(roles.keySet().containsAll(Set.of("stdin", "stream", "stderr", "transcript")) && paths.equals(occurrences.keySet()), "Codex original inventory differs");
        return roles;
    }

    private static void header(Jv m, Jv d, Jv a, boolean retained, String terminalProfile) {
        equal(m, "kind", "agent-journal/acquired-execution"); equal(m, "version", "0.1");
        String version = text(m.path("run", "cli"), "version");
        require(CodexExecFamily.admittedUnder(terminalProfile, version), "Codex CLI version " + version + " is not admitted under terminal profile " + terminalProfile);
        equal(m, "profile", CodexExecFamily.profile(version)); equal(m, "profile_id", CodexExecFamily.profileId(version));
        flag(m, "complete", true); require(array(m, "capture_errors").isEmpty() && array(a, "missing").isEmpty(), "Codex capture incomplete");
        equal(d.member("outcome"), "kind", "SUCCESS"); equal(d.member("provider"), "kind", "REPORTED_SUCCESS");
        equal(m, "acquisition_uid", text(d, "executionId")); equal(m.member("run"), "run_uid", text(d, "executionId"));
        equal(m.path("run", "cli"), "name", "Codex CLI");
        equal(m.path("run", "launch_settings"), "route", "exec_stdio");
        process(m, d, a, retained);
    }
    private static void process(Jv m, Jv d, Jv a, boolean retained) {
        Jv p = m.member("process"); equal(p, "connection_uid", text(d, "connectionId")); equal(p, "client_session_uid", text(d, "sessionId"));
        require(array(a, "connectionIds").size() == 1 && array(a, "connectionIds").get(0).text().equals(text(p, "connection_uid")), "Codex generation differs");
        equal(m.path("run", "launch_settings"), "working_directory", text(p, "cwd"));
        Jv turn = array(m, "turns").get(0); equal(turn, "invocation_uid", text(d, "executionId")); equal(turn, "decision_uid", text(d, "decisionId")); text(turn, "native_turn_uid");
        closure(m, p, retained);
    }
    /** A follow-up turn of a retained thread is the only admitted non-fresh process; it must resume the emitted thread. */
    static void closure(Jv m, Jv p, boolean retained) {
        boolean followUp = retained && number(m.member("run"), "invocation_index") > 0;
        for (String key : List.of("sealed", "observed_resources_terminated", "stdin_closed", "stdout_natural_eof", "stderr_natural_eof")) flag(p, key, true);
        flag(p, "fresh_session", !followUp); flag(p, "native_path_preexisting", followUp); flag(p, "overflow", false);
        require(number(p, "exit_code") == 0, "Codex process did not exit successfully");
        require(array(m, "turns").size() == 1, "Codex profile requires one turn");
        String session = text(m.member("run"), "session_uid"); Jv turn = array(m, "turns").get(0);
        launch(p, followUp ? session : null);
        if (followUp) equal(turn, "resume_thread_id", session);
        else require(turn.member("resume_thread_id") == null || turn.member("resume_thread_id").isNull(), "fresh Codex turn declares a resumed thread");
    }
    static void launch(Jv p, String resumed) {
        List<String> argv = array(p, "argv").stream().map(Jv::text).toList();
        require(argv.containsAll(List.of("--no-daemon", "exec", "--ignore-user-config", "--json")) && "-".equals(argv.get(argv.size() - 1)), "wrong Codex original stdio route");
        require(argv.stream().noneMatch(Set.of("fork", "--ephemeral", "--last")::contains), "Codex history reuse forbidden");
        int exec = argv.indexOf("exec"); boolean resume = exec + 1 < argv.size() && "resume".equals(argv.get(exec + 1));
        if (resumed == null) require(!argv.contains("resume"), "Codex history reuse forbidden for a fresh turn");
        else require(resume && argv.subList(exec + 2, argv.size()).contains(resumed), "Codex follow-up must resume the emitted thread");
        require(option(argv, "-C", text(p, "cwd")) || option(argv, "--cd", text(p, "cwd")), "Codex argv cwd differs");
        for (List<String> flags : List.of(List.of("--sandbox", "-s"), List.of("--ask-for-approval", "-a"), List.of("--model", "-m"))) require(flags.stream().anyMatch(argv::contains), "Codex explicit launch option missing");
        // Reasoning effort is reported by the native settings records (null when unset) and is not required on the command line.
    }
    private static boolean option(List<String> argv, String flag, String value) { int i = argv.indexOf(flag); return i >= 0 && i + 1 < argv.size() && value.equals(argv.get(i + 1)); }

    private static void source(Jv s, Jv occurrence, Jv m, Jv d, boolean generation) {
        equal(s, "state", "acquired"); equal(s, "end", "EOF"); equal(s, "acquisition", text(d, "executionId")); flag(s, "required", true);
        equal(occurrence, "sha256", text(s, "sha256")); equal(occurrence, "origin", text(s, "origin"));
        long bytes = number(s, "bytes"); require(bytes == number(occurrence, "size") && bytes == number(s, "observed") && number(s, "dropped") == 0, "Codex extent/drop differs");
        String role = text(s, "role"), stream = Map.of("stdin", "STDIN", "stream", "STDOUT", "stderr", "STDERR").get(role);
        flag(s, "archive_only", generation || Set.of("stdin", "stderr").contains(role));
        if (generation) { require(occurrence.member("stream").isNull(), "generation original is not a stream"); return; }
        if (stream == null) { require(occurrence.member("stream").isNull(), "native file is not a stream"); equal(s, "owner_session_uid", text(m.member("run"), "session_uid")); }
        else {
            equal(occurrence, "stream", stream); Jv turn = array(m, "turns").get(0);
            require(number(turn.member("start_offsets"), stream) == 0 && number(turn.member("end_offsets"), stream) == bytes, "Codex stream span differs");
        }
    }
    static void flag(Jv value, String key, boolean expected) { require(value.member(key) instanceof Jv.Bool b && b.value() == expected, "invalid Codex " + key); }
    static void require(boolean condition, String reason) { if (!condition) throw new IllegalArgumentException(reason); }
}
