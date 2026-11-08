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

/** Admission for one closed Claude SDK execution. Storage completeness does not assert process cessation. */
final class TerminalNativeProfile {
    private TerminalNativeProfile() { }

    static boolean complete(TerminalContract.Input input, Jv association) {
        Jv envelope = Jv.parse(input.envelope());
        String contract = ClaudeSdkFamily.contract(text(envelope, "profile"));
        return (Set.of(TerminalContract.LEGACY_CLAUDE_PROFILE, TerminalContract.RETAINED_PROFILE).contains(contract) || TerminalContract.FRESH_RETAINED_PROFILES.contains(contract))
                && "COMPLETED".equals(text(association, "acquisition"));
    }

    static void validate(Path manifest, TerminalContract.Input input, Jv association) throws IOException {
        Jv decision = input.decision();
        Jv m = Jv.parse(Files.readString(manifest));
        structure(m);
        header(m, decision, association, text(Jv.parse(input.envelope()), "profile"));
        Map<String, Jv> declared = inventory(association);
        require(declared.containsKey("acquisition.json"), "native manifest not associated");
        Jv receipt = declared.get("acquisition.json");
        equal(receipt, "nativeSessionId", text(m.member("run"), "session_uid"));
        equal(receipt, "clientConnectionId", text(decision, "connectionId"));
        require(receipt.member("stream").isNull(), "native manifest is not a stream");
        Set<String> paths = new HashSet<>(); paths.add("acquisition.json");
        Map<String, Jv> roles = new HashMap<>();
        for (Jv source : array(m, "sources")) {
            equal(source, "state", "acquired"); equal(source, "end", "EOF");
            equal(source, "acquisition", text(decision, "executionId"));
            String path = text(source, "path"); require(paths.add(path), "duplicate native source path");
            Jv occurrence = declared.get(path); require(occurrence != null, "native source not associated");
            source(source, occurrence, m);
            String role = text(source, "role");
            if (Set.of("stdin", "stream", "stderr", "transcript").contains(role)) require(roles.put(role, source) == null, "duplicate native role");
        }
        require(declared.keySet().equals(paths), "native association inventory differs");
        require(roles.keySet().containsAll(Set.of("stdin", "stream", "stderr", "transcript")), "required native role absent");
        nativeRecords(manifest.getParent(), roles, text(m.member("run"), "session_uid"), text(m.path("run", "cli"), "version"));
    }

    private static void structure(Jv m) {
        for (String key : List.of("run", "process")) require(m.member(key) instanceof Jv.Obj, "native " + key + " object absent");
        for (String key : List.of("cli", "launch_settings")) require(m.member("run").member(key) instanceof Jv.Obj, "native run " + key + " absent");
        for (Jv turn : array(m, "turns")) {
            for (String key : List.of("start_offsets", "end_offsets")) require(turn.member(key) instanceof Jv.Obj, "native turn " + key + " absent");
        }
    }

    private static void header(Jv m, Jv d, Jv a, String terminalProfile) {
        equal(m, "kind", "agent-journal/acquired-execution"); equal(m, "version", "0.1");
        String version = text(m.path("run", "cli"), "version");
        require(ClaudeSdkFamily.admittedUnder(terminalProfile, version), "native CLI version " + version + " is not admitted under terminal profile " + terminalProfile);
        equal(m, "profile", ClaudeSdkFamily.profile(version));
        equal(m, "profile_id", ClaudeSdkFamily.profileId(version));
        require(m.member("complete") instanceof Jv.Bool b && b.value(), "native acquisition incomplete");
        require(array(m, "capture_errors").isEmpty() && array(a, "missing").isEmpty(), "native capture has gaps");
        String execution = text(d, "executionId"); equal(m, "acquisition_uid", execution); equal(m.member("run"), "run_uid", execution);
        equal(m.path("run", "launch_settings"), "route", "sdk_stdio");
        String connection = text(m.member("process"), "connection_uid"); equal(d, "connectionId", connection);
        require(array(a, "connectionIds").size() == 1 && connection.equals(array(a, "connectionIds").get(0).text()), "native connection differs");
        List<Jv> turns = array(m, "turns"); require(turns.size() == 1, "native profile requires one turn");
        equal(turns.get(0), "invocation_uid", execution);
    }

    private static Map<String, Jv> inventory(Jv a) {
        Map<String, Jv> result = new HashMap<>();
        for (Jv s : array(a, "sources")) {
            require(result.put(text(s, "relativePath"), s) == null, "duplicate native occurrence path");
            require(number(s, "start") == 0 && number(s, "end") == number(s, "size"), "native profile requires whole source extents");
            require(s.member("nativeTurnId").isNull() && s.member("nativeConnectionId").isNull(), "unestablished native turn/connection identity");
        }
        return result;
    }

    private static void source(Jv source, Jv occurrence, Jv m) {
        equal(occurrence, "sha256", text(source, "sha256")); equal(occurrence, "origin", text(source, "origin"));
        require(number(occurrence, "size") == number(source, "bytes"), "native extent differs");
        equal(occurrence, "nativeSessionId", text(m.member("run"), "session_uid"));
        equal(occurrence, "clientConnectionId", text(m.member("process"), "connection_uid"));
        String stream = switch (text(source, "role")) { case "stdin" -> "STDIN"; case "stream" -> "STDOUT"; case "stderr" -> "STDERR"; default -> null; };
        if (stream == null) require(occurrence.member("stream").isNull(), "native non-stream mislabeled");
        else {
            equal(occurrence, "stream", stream); Jv turn = array(m, "turns").get(0);
            require(number(turn.member("start_offsets"), stream) == 0 && number(turn.member("end_offsets"), stream) == number(source, "bytes"), "native turn span differs");
        }
    }

    private static List<Jv> records(Path base, Jv source) throws IOException {
        Path path = base.resolve(relative(text(source, "path")));
        return Files.readAllLines(path).stream().filter(line -> !line.isBlank()).map(Jv::parse).toList();
    }

    private static void nativeRecords(Path base, Map<String, Jv> roles, String session, String version) throws IOException {
        List<Jv> stdin = records(base, roles.get("stdin"));
        List<Jv> prompts = stdin.stream().filter(r -> "user".equals(optional(r, "type"))).toList();
        require(prompts.size() == 1, "native profile needs one stdin prompt");
        List<Jv> stream = records(base, roles.get("stream"));
        List<Jv> transcript = records(base, roles.get("transcript"));
        identity(stream, session, version, true); identity(transcript, session, version, false);
        require(stream.stream().filter(r -> "result".equals(optional(r, "type"))).count() == 1, "native profile needs one result");
        List<Jv> nativePrompts = transcript.stream().filter(r -> "user".equals(optional(r, "type")) && r.path("message", "content") instanceof Jv.Str).toList();
        require(nativePrompts.size() == 1 && Jv.write(nativePrompts.get(0).member("message")).equals(Jv.write(prompts.get(0).member("message"))), "native prompt membership differs");
    }

    /** Every version witness states the manifest's own version; a listed version is admitted, never a neighbour. */
    private static void identity(List<Jv> rows, String session, String version, boolean stream) {
        int identities = 0, versions = 0;
        for (Jv row : rows) {
            String key = stream ? "session_id" : "sessionId";
            if (row.member(key) != null) { equal(row, key, session); identities++; }
            if (stream && "system".equals(optional(row, "type")) && "init".equals(optional(row, "subtype"))) { equal(row, "claude_code_version", version); versions++; }
            if (!stream && row.member("version") != null) { equal(row, "version", version); versions++; }
        }
        require(identities > 0 && versions > 0, "native version/session witness absent");
    }

    private static String optional(Jv value, String key) { Jv v = value.member(key); return v == null ? null : v.text(); }
    private static void require(boolean condition, String reason) { if (!condition) throw new IllegalArgumentException(reason); }
}
