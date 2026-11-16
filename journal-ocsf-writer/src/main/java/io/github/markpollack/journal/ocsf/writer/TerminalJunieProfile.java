package io.github.markpollack.journal.ocsf.writer;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import static io.github.markpollack.journal.ocsf.writer.TerminalValues.*;

/**
 * Admission of a qualified Junie ACP one-shot delivered under {@value JunieAcpFamily#TERMINAL_PROFILE}: the qualified
 * manifest is validated again here, independently of the TCK qualifier, against the accepted decision and the
 * association's whole-file occurrences, and the native records by {@link TerminalJunieNative}. Assembly then runs
 * through {@link AcquiredExecution} like every other acquisition.
 */
final class TerminalJunieProfile {
    static final String PROFILE = JunieAcpFamily.TERMINAL_PROFILE;
    private static final Map<String, String> PROFILES = Map.of("summary", "junie_summary", "session", "junie_session_events");
    private static final Map<String, String> FILES = Map.of("summary", "summary.json", "session", "events.jsonl");

    private TerminalJunieProfile() { }

    static boolean complete(TerminalContract.Input input, Jv a) {
        return PROFILE.equals(text(Jv.parse(input.envelope()), "profile")) && "COMPLETED".equals(text(a, "acquisition"));
    }

    static void validate(Path manifest, TerminalContract.Input input, Jv a) throws IOException {
        Jv d = input.decision();
        Jv m = Jv.parse(Files.readString(manifest));
        header(m, d, a);
        String session = identity(m);
        Map<String, Jv> occurrences = new HashMap<>();
        for (Jv s : array(a, "sources")) {
            require(occurrences.put(text(s, "relativePath"), s) == null, "duplicate Junie occurrence");
            occurrence(s, session, text(d, "connectionId"));
        }
        require(occurrences.containsKey("acquisition.json"), "Junie acquisition not associated");
        Map<String, Jv> roles = new HashMap<>();
        for (Jv s : array(m, "sources")) {
            String role = text(s, "role");
            require(PROFILES.containsKey(role) && roles.put(role, s) == null, "unsupported or duplicate Junie source role");
            Jv occurrence = "acquisition.json".equals(text(s, "path")) ? null : occurrences.remove(text(s, "path"));
            if (occurrence == null) throw new IllegalArgumentException("Junie source not associated");
            source(s, occurrence, m, role);
        }
        require(roles.size() == 2 && occurrences.keySet().equals(java.util.Set.of("acquisition.json")), "Junie original inventory differs");
        TerminalJunieNative.validate(manifest.getParent(), m, roles);
    }

    private static void header(Jv m, Jv d, Jv a) {
        equal(m, "kind", "agent-journal/acquired-execution"); equal(m, "version", "0.1");
        String version = text(m.path("run", "cli"), "version");
        equal(m, "profile", JunieAcpFamily.profile(version)); equal(m, "profile_id", JunieAcpFamily.profileId(version));
        flag(m, "complete", true); require(array(m, "capture_errors").isEmpty() && array(a, "missing").isEmpty(), "Junie capture incomplete");
        equal(d.member("outcome"), "kind", "SUCCESS"); equal(d.member("provider"), "kind", "REPORTED_SUCCESS");
        equal(m, "acquisition_uid", text(d, "executionId")); equal(m.member("run"), "run_uid", text(d, "executionId"));
        equal(m.path("run", "launch_settings"), "route", "acp_stdio");
        Jv p = m.member("process");
        equal(p, "connection_uid", text(d, "connectionId")); equal(p, "client_session_uid", text(d, "sessionId"));
        require(array(a, "connectionIds").size() == 1 && text(p, "connection_uid").equals(array(a, "connectionIds").get(0).text()), "Junie connection differs");
        for (String fact : List.of("fresh_session", "sealed", "observed_resources_terminated")) flag(p, fact, true);
        equal(p, "acp_stop_reason", "end_turn");
        require(array(m, "turns").size() == 1, "Junie profile requires one turn");
        Jv turn = array(m, "turns").get(0);
        equal(turn, "invocation_uid", text(d, "executionId")); equal(turn, "decision_uid", text(d, "decisionId")); text(turn, "native_task_uid");
    }

    /** The ACP session, the run's session and the one new native session directory are one id; returns it. */
    private static String identity(Jv m) {
        Jv p = m.member("process");
        String session = text(p, "acp_session_id");
        equal(m.member("run"), "session_uid", session);
        List<Jv> fresh = array(p, "native_sessions_new");
        require(fresh.size() == 1 && session.equals(fresh.get(0).text()), "Junie native session candidates differ from the ACP session");
        equal(m.path("run", "launch_settings"), "working_directory", text(p, "cwd"));
        return session;
    }

    private static void occurrence(Jv s, String session, String connection) {
        require(number(s, "start") == 0 && number(s, "end") == number(s, "size"), "whole Junie source required");
        require(s.member("stream").isNull() && s.member("nativeTurnId").isNull() && s.member("nativeConnectionId").isNull(), "Junie occurrence overstates its identity");
        equal(s, "nativeSessionId", session); equal(s, "clientConnectionId", connection);
    }

    private static void source(Jv s, Jv occurrence, Jv m, String role) {
        equal(s, "state", "acquired"); equal(s, "end", "EOF"); equal(s, "acquisition", text(m, "acquisition_uid"));
        flag(s, "required", true); flag(s, "archive_only", false);
        equal(s, "profile", PROFILES.get(role)); equal(s, "route", "native_session_file");
        String session = text(m.member("process"), "acp_session_id");
        equal(s, "owner_session_uid", session);
        Path origin = Path.of(text(s, "origin"));
        require(origin.normalize().equals(origin) && Path.of(text(m.member("process"), "native_sessions_directory"), session, FILES.get(role)).equals(origin), "Junie origin outside the session directory");
        equal(occurrence, "sha256", text(s, "sha256")); equal(occurrence, "origin", text(s, "origin"));
        long bytes = number(s, "bytes");
        require(bytes == number(occurrence, "size") && bytes == number(s, "observed") && number(s, "dropped") == 0, "Junie extent/drop differs");
    }

    static void flag(Jv value, String key, boolean expected) { require(value.member(key) instanceof Jv.Bool b && b.value() == expected, "invalid Junie " + key); }
    static void require(boolean condition, String reason) { if (!condition) throw new IllegalArgumentException(reason); }
}
