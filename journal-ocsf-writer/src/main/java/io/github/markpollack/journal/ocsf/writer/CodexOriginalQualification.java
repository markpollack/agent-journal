package io.github.markpollack.journal.ocsf.writer;

import java.io.IOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import static io.github.markpollack.journal.ocsf.writer.TerminalValues.*;
import static io.github.markpollack.journal.ocsf.writer.TerminalCodexProfile.*;

/** Original acquisition prerequisites for the fresh singleton assembly, also without a terminal envelope. */
final class CodexOriginalQualification {
    private CodexOriginalQualification() { }

    static void validate(Jv m, Path base) throws IOException {
        boolean retained = m.member("retained_generation") != null;
        String version = text(m.path("run", "cli"), "version");
        require(CodexExecFamily.wellFormed(version), "malformed Codex CLI version");
        equal(m, "profile_id", CodexExecFamily.profileId(version)); equal(m, "profile", CodexExecFamily.profile(version));
        flag(m, "complete", true); require(array(m, "capture_errors").isEmpty(), "Codex capture errors");
        Jv run = m.member("run"), process = m.member("process");
        equal(run, "run_uid", text(m, "acquisition_uid")); text(run, "session_uid");
        equal(run.member("cli"), "name", "Codex CLI");
        process(m, process, run, retained);
        Jv turn = array(m, "turns").get(0); equal(turn, "invocation_uid", text(m, "acquisition_uid")); text(turn, "decision_uid"); text(turn, "native_turn_uid");
        Map<String, Jv> roles = sources(m, retained);
        equal(roles.get("transcript"), "owner_session_uid", text(run, "session_uid"));
        for (var span : Map.of("stdin", "STDIN", "stream", "STDOUT", "stderr", "STDERR").entrySet()) {
            require(number(turn.member("start_offsets"), span.getValue()) == 0
                    && number(turn.member("end_offsets"), span.getValue()) == number(roles.get(span.getKey()), "bytes"), "Codex whole stream scope required");
        }
        String profile = retained ? CodexExecFamily.contract(text(m, "retained_profile")) : null;
        if (retained) require(TerminalContract.CODEX_RETAINED_PROFILES.contains(profile), "unsupported Codex retained profile");
        TerminalCodexNative.validate(base, m, roles, retained, TerminalContract.CODEX_RETAINED_ORDINARY_PROFILE.equals(profile));
        if (retained) TerminalCodexRetained.validate(base, m, text(m, "retained_profile"));
    }
    private static Map<String, Jv> sources(Jv m, boolean retained) {
        Map<String, Jv> roles = new HashMap<>();
        for (Jv source : array(m, "sources")) {
            String role = text(source, "role"); require(roles.put(role, source) == null, "duplicate Codex source role");
            equal(source, "state", "acquired"); equal(source, "end", "EOF"); equal(source, "acquisition", text(m, "acquisition_uid")); flag(source, "required", true);
            require(number(source, "bytes") == number(source, "observed") && number(source, "dropped") == 0, "Codex complete original extent required");
            boolean generation = retained && role.startsWith("generation-");
            flag(source, "archive_only", generation || Set.of("stdin", "stderr").contains(role));
        }
        require(roles.keySet().containsAll(Set.of("stdin", "stream", "stderr", "transcript")) && (retained || roles.size() == 4), "four Codex original sources required");
        return roles;
    }
    private static void process(Jv m, Jv process, Jv run, boolean retained) {
        equal(run.member("launch_settings"), "route", "exec_stdio");
        equal(run.member("launch_settings"), "working_directory", text(process, "cwd"));
        TerminalCodexProfile.closure(m, process, retained); text(process, "connection_uid"); text(process, "client_session_uid");
    }

}
