package io.github.markpollack.journal.ocsf.writer;

import java.io.IOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import static io.github.markpollack.journal.ocsf.writer.TerminalValues.*;
import static io.github.markpollack.journal.ocsf.writer.TerminalCodexProfile.*;

/** Original acquisition prerequisites for the fresh singleton assembly, also without a terminal envelope. */
final class CodexOriginalQualification {
    static final String PROFILE = "codex-cli/0.166.0/exec-stdio+rollout";
    private CodexOriginalQualification() { }

    static void validate(Jv m, Path base) throws IOException {
        equal(m, "profile_id", PROFILE); equal(m, "profile", "codex-cli-0.166.0-exec-stdio-rollout");
        flag(m, "complete", true); require(array(m, "capture_errors").isEmpty(), "Codex capture errors");
        Jv run = m.member("run"), process = m.member("process");
        equal(run, "run_uid", text(m, "acquisition_uid")); text(run, "session_uid");
        equal(run.member("cli"), "name", "Codex CLI"); equal(run.member("cli"), "version", "0.166.0");
        process(process, run);
        require(array(m, "turns").size() == 1, "Codex singleton turn required");
        Jv turn = array(m, "turns").get(0); equal(turn, "invocation_uid", text(m, "acquisition_uid")); text(turn, "decision_uid"); text(turn, "native_turn_uid");
        Map<String, Jv> roles = new HashMap<>();
        for (Jv source : array(m, "sources")) {
            String role = text(source, "role"); require(roles.put(role, source) == null, "duplicate Codex source role");
            equal(source, "state", "acquired"); equal(source, "end", "EOF"); equal(source, "acquisition", text(m, "acquisition_uid")); flag(source, "required", true);
            require(number(source, "bytes") == number(source, "observed") && number(source, "dropped") == 0, "Codex complete original extent required");
            flag(source, "archive_only", Set.of("stdin", "stderr").contains(role));
        }
        require(roles.keySet().equals(Set.of("stdin", "stream", "stderr", "transcript")), "four Codex original sources required");
        equal(roles.get("transcript"), "owner_session_uid", text(run, "session_uid"));
        for (var span : Map.of("stdin", "STDIN", "stream", "STDOUT", "stderr", "STDERR").entrySet()) {
            require(number(turn.member("start_offsets"), span.getValue()) == 0
                    && number(turn.member("end_offsets"), span.getValue()) == number(roles.get(span.getKey()), "bytes"), "Codex whole stream scope required");
        }
        TerminalCodexNative.validate(base, m, roles);
    }
    private static void process(Jv process, Jv run) {
        equal(run.member("launch_settings"), "route", "exec_stdio");
        equal(run.member("launch_settings"), "working_directory", text(process, "cwd"));
        for (String key : List.of("fresh_session", "sealed", "observed_resources_terminated", "stdin_closed", "stdout_natural_eof", "stderr_natural_eof")) flag(process, key, true);
        for (String key : List.of("native_path_preexisting", "overflow")) flag(process, key, false);
        require(number(process, "exit_code") == 0, "Codex successful original required");
        TerminalCodexProfile.launch(process); text(process, "connection_uid"); text(process, "client_session_uid");
    }

}
