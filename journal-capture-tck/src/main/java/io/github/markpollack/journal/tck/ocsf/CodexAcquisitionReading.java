package io.github.markpollack.journal.tck.ocsf;

import io.github.markpollack.journal.tck.ocsf.NativeArtifact;
import io.github.markpollack.journal.tck.verify.Json;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Independent original-byte acquisition validation for the bounded fresh exec route. */
public final class CodexAcquisitionReading {
    private CodexAcquisitionReading() { }
    /** Read-only validation of original capture evidence; no writer or Client dependency. */
    public static void validate(Path manifest) throws IOException {
        read(io.github.markpollack.journal.tck.verify.NeutralJson.readDocument(manifest), manifest.toAbsolutePath().getParent());
    }
    @SuppressWarnings("ArrayRecordComponent") // byte snapshots are copied at the public boundary, never compared by record equality
    public record Source(Json declaration, Path relative, byte[] bytes, Path path) {
        public Source { bytes = bytes.clone(); }
        @Override public byte[] bytes() { return bytes.clone(); }
    }

    public static Map<String, Source> read(Json m, Path directory) throws IOException {
        boolean retained = m.member("retained_generation") != null;
        header(m, retained);
        Path base = directory.toRealPath();
        Map<String, Source> sources = new HashMap<>();
        Set<Path> paths = new HashSet<>(); Set<String> artifacts = new HashSet<>();
        for (Json s : array(m, "sources")) {
            String role = text(s, "role");
            boolean generation = retained && role.startsWith("generation-");
            require(generation || Set.of("stdin", "stream", "stderr", "transcript").contains(role), "unsupported source role");
            require(artifacts.add(text(s, "artifact")), "duplicate artifact");
            equal(s, "acquisition", text(m, "acquisition_uid")); equal(s, "state", "acquired"); equal(s, "end", "EOF");
            truth(s, "required", true); text(s, "origin");
            Source frozen = freeze(base, s, role);
            require(paths.add(frozen.path()), "duplicate physical source path");
            require(sources.put(role, frozen) == null, "duplicate source role");
        }
        require(sources.keySet().containsAll(Set.of("stdin", "stream", "stderr", "transcript")) && (retained || sources.size() == 4), "four original sources required");
        spans(m, sources);
        CodexNativeCapture.validate(m, sources, retained);
        return sources;
    }

    private static Source freeze(Path base, Json s, String role) throws IOException {
        Path relative = Path.of(text(s, "path")).normalize();
        require(!relative.isAbsolute() && !relative.startsWith(".."), "source escapes acquisition");
        require(!Set.of("acquisition.json", "client-acquisition.json", "assembly-expectation.json").contains(relative.getName(0).toString()), "reserved source path");
        Path path = base.resolve(relative).toRealPath(); require(path.startsWith(base), "source symlink escapes acquisition");
        byte[] bytes = Files.readAllBytes(path);
        require(NativeArtifact.sha256(bytes).equals(text(s, "sha256")), "source hash mismatch");
        require(number(s, "bytes") == bytes.length && number(s, "observed") == bytes.length && number(s, "dropped") == 0, "source extent/drop mismatch");
        selected(s, role, bytes);
        return new Source(s, relative, bytes, path);
    }

    private static void header(Json m, boolean retained) {
        equal(m, "kind", "agent-journal/acquired-execution"); equal(m, "version", "0.1");
        String version = text(m.path("run", "cli"), "version");
        require(CodexExecFamily.wellFormed(version), "malformed Codex CLI version");
        equal(m, "profile", CodexExecFamily.profile(version)); equal(m, "profile_id", CodexExecFamily.profileId(version));
        truth(m, "complete", true); require(array(m, "capture_errors").isEmpty(), "capture errors present");
        Json run = m.member("run"), process = m.member("process");
        equal(run, "run_uid", text(m, "acquisition_uid")); text(run, "session_uid"); text(run, "capture_start");
        equal(run.member("cli"), "name", "Codex CLI");
        equal(run.member("launch_settings"), "route", "exec_stdio");
        equal(run.member("launch_settings"), "working_directory", text(process, "cwd"));
        text(process, "connection_uid"); text(process, "client_session_uid");
        process(m, run, process, retained && number(run, "invocation_index") > 0);
    }

    private static void process(Json m, Json run, Json process, boolean followUp) {
        for (String field : List.of("sealed", "observed_resources_terminated", "stdin_closed", "stdout_natural_eof", "stderr_natural_eof")) truth(process, field, true);
        truth(process, "fresh_session", !followUp); truth(process, "native_path_preexisting", followUp); truth(process, "overflow", false);
        require(number(process, "exit_code") == 0, "successful exited process required");
        List<Json> turns = array(m, "turns"); require(turns.size() == 1, "exactly one fresh turn required");
        Json turn = turns.get(0); equal(turn, "invocation_uid", text(m, "acquisition_uid")); text(turn, "decision_uid"); text(turn, "native_turn_uid");
        launch(process, followUp ? text(run, "session_uid") : null);
        if (followUp) equal(turn, "resume_thread_id", text(run, "session_uid"));
        else require(turn.member("resume_thread_id") == null || turn.member("resume_thread_id").isNull(), "fresh turn declares a resumed thread");
    }

    /** {@code resumed} names the thread a follow-up turn must resume; null means a fresh exec that may not resume anything. */
    private static void launch(Json process, String resumed) {
        List<String> argv = array(process, "argv").stream().map(Json::text).toList();
        require(argv.size() > 4 && argv.stream().allMatch(s -> s != null && !s.isEmpty()), "observed argv required");
        require(argv.contains("--no-daemon") && argv.contains("exec") && argv.contains("--ignore-user-config") && argv.contains("--json"), "unsupported exec route");
        require("-".equals(argv.get(argv.size() - 1)), "raw stdin prompt '-' required");
        require(argv.stream().noneMatch(Set.of("fork", "--ephemeral", "--last")::contains), "ephemeral/forked history forbidden");
        int exec = argv.indexOf("exec"); boolean resume = exec + 1 < argv.size() && "resume".equals(argv.get(exec + 1));
        if (resumed == null) require(!argv.contains("resume"), "existing history forbidden for a fresh turn");
        else require(resume && argv.subList(exec + 2, argv.size()).contains(resumed), "follow-up turn must resume the emitted thread");
        require(option(argv, "--cd", text(process, "cwd")) || option(argv, "-C", text(process, "cwd")), "argv cwd mismatch");
        for (List<String> flags : List.of(List.of("--sandbox", "-s"), List.of("--ask-for-approval", "-a"), List.of("--model", "-m"))) {
            require(flags.stream().anyMatch(f -> value(argv, f) != null), "explicit launch option missing");
        }
        // Reasoning effort is not a launch requirement: the native thread_settings_applied/turn_context records report the effort in force (null when unset).
    }

    private static boolean option(List<String> argv, String flag, String expected) { return expected.equals(value(argv, flag)); }
    private static String value(List<String> argv, String flag) {
        int i = argv.indexOf(flag); return i >= 0 && i + 1 < argv.size() ? argv.get(i + 1) : null;
    }

    private static void selected(Json s, String role, byte[] bytes) {
        boolean stream = "stream".equals(role), nativeFile = "transcript".equals(role);
        truth(s, "archive_only", !stream && !nativeFile);
        if (role.startsWith("generation-")) return;
        if (!stream && !nativeFile) return;
        equal(s, "profile", stream ? "codex_stream" : "codex_rollout");
        equal(s, "route", stream ? "stdout" : "native_session_file"); equal(s, "framing", "lines");
        require(bytes.length > 0 && bytes[bytes.length - 1] == '\n', "selected source lacks complete final line");
        long lines = 0; for (byte b : bytes) if (b == '\n') lines++;
        require(number(s, "records") == lines, "source record extent mismatch");
    }

    private static void spans(Json m, Map<String, Source> sources) {
        Json turn = array(m, "turns").get(0);
        for (var entry : Map.of("stdin", "STDIN", "stream", "STDOUT", "stderr", "STDERR").entrySet()) {
            require(number(turn.member("start_offsets"), entry.getValue()) == 0
                    && number(turn.member("end_offsets"), entry.getValue()) == sources.get(entry.getKey()).bytes().length, "whole stream span required");
        }
        equal(sources.get("transcript").declaration(), "owner_session_uid", text(m.member("run"), "session_uid"));
    }

    static String text(Json value, String key) {
        Json v = value == null ? null : value.member(key); String s = v == null ? null : v.text();
        require(s != null && !s.isBlank(), "missing " + key); return s;
    }
    static List<Json> array(Json value, String key) {
        Json found = value == null ? null : value.member(key);
        if (!(found instanceof Json.JArray a)) throw new IllegalArgumentException("missing array " + key);
        return a.elements();
    }
    static long number(Json value, String key) {
        Json found = value == null ? null : value.member(key);
        if (!(found instanceof Json.JNumber n)) throw new IllegalArgumentException("missing number " + key);
        return n.value().longValueExact();
    }
    static void truth(Json value, String key, boolean expected) {
        require(value != null && value.member(key) instanceof Json.JBool b && b.value() == expected, "invalid " + key);
    }
    static void equal(Json value, String key, String expected) { require(expected.equals(text(value, key)), "wrong " + key); }
    static void require(boolean pass, String reason) { if (!pass) throw new IllegalArgumentException(reason); }
}
