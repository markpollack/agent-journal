package io.github.markpollack.journal.tck.ocsf;

import io.github.markpollack.journal.tck.verify.Json;
import io.github.markpollack.journal.tck.verify.NeutralJson;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import static io.github.markpollack.journal.tck.ocsf.TerminalReading.*;

/** Independent identity/whole-source association reading for the opt-in fresh Codex terminal profile. */
final class TerminalCodexReading {
    static final String PROFILE = "client-terminal-codex-0.166.0/1";
    private TerminalCodexReading() { }
    private static void require(boolean pass, String reason) { if (!pass) throw new IllegalArgumentException(reason); }
    private static boolean selected(Json p, Json a) { return (PROFILE.equals(string(p, "profile")) || retained(p)) && "COMPLETED".equals(string(a, "acquisition")); }
    private static boolean retained(Json p) { return CodexRetainedGeneration.PROFILES.contains(string(p, "profile")); }
    static void check(DeclaredFile file, Json p, Json d, Json a) {
        if (!selected(p, a)) return;
        Json m = file.run().path("launch_settings", "acquired_execution"); require(m != null, "Codex acquisition declaration absent");
        header(file, m, d, a);
        Map<String, Json> occurrences = occurrences(m, d, a);
        Set<String> paths = new HashSet<>(); paths.add("acquisition.json"); Set<String> roles = new HashSet<>();
        boolean retained = retained(p);
        if (retained) expect(m, "retained_profile", string(p, "profile")); else require(m.member("retained_generation") == null, "one-shot Codex profile carries a thread");
        for (Json s : array(m, "sources")) {
            String path = string(s, "path"), role = string(s, "role"); require(paths.add(path) && roles.add(role), "Codex duplicate source");
            require(Set.of("stdin", "stream", "stderr", "transcript").contains(role) || (retained && role.startsWith("generation-")), "unsupported Codex source role");
            Json occurrence = occurrences.get(path); require(occurrence != null, "Codex original not associated");
            expect(s, "state", "acquired"); expect(s, "end", "EOF"); expect(s, "acquisition", string(d, "executionId"));
            for (String key : List.of("sha256", "origin")) expect(occurrence, key, string(s, key));
            require(number(occurrence, "size") == number(s, "bytes"), "Codex source extent differs");
            stream(s, occurrence, array(m, "turns").get(0));
        }
        require(paths.equals(occurrences.keySet()) && roles.containsAll(Set.of("stdin", "stream", "stderr", "transcript")), "Codex original inventory differs");
    }
    private static Map<String, Json> occurrences(Json m, Json d, Json a) {
        Map<String, Json> occurrences = new HashMap<>();
        for (Json s : array(a, "sources")) {
            require(occurrences.put(string(s, "relativePath"), s) == null, "duplicate Codex source occurrence");
            require(number(s, "start") == 0 && number(s, "end") == number(s, "size"), "Codex whole-source extent required");
            require(s.member("nativeTurnId").isNull() && s.member("nativeConnectionId").isNull(), "Codex whole-source identity overstated");
            expect(s, "nativeSessionId", string(m.member("run"), "session_uid")); expect(s, "clientConnectionId", string(d, "connectionId"));
        }
        require(occurrences.containsKey("acquisition.json"), "Codex manifest occurrence absent");
        require(occurrences.get("acquisition.json").member("stream").isNull(), "Codex manifest labelled stream");
        return occurrences;
    }
    private static void header(DeclaredFile file, Json m, Json d, Json a) {
        expect(m, "profile", "codex-cli-0.166.0-exec-stdio-rollout"); expect(m, "profile_id", "codex-cli/0.166.0/exec-stdio+rollout");
        expect(m, "acquisition_uid", string(d, "executionId")); expect(m.member("run"), "run_uid", string(d, "executionId"));
        expect(file.run(), "run_uid", string(d, "executionId"));
        require(file.assembly() != null && file.sessions().equals(List.of(string(m.member("run"), "session_uid"))), "Codex assembly/session differs");
        expect(d.member("outcome"), "kind", "SUCCESS"); expect(d.member("provider"), "kind", "REPORTED_SUCCESS");
        expect(m.member("process"), "connection_uid", string(d, "connectionId")); expect(m.member("process"), "client_session_uid", string(d, "sessionId"));
        require(array(a, "connectionIds").size() == 1 && string(d, "connectionId").equals(array(a, "connectionIds").get(0).text()), "Codex accepted generation differs");
        require(m.member("complete") instanceof Json.JBool b && b.value() && array(m, "capture_errors").isEmpty() && array(a, "missing").isEmpty(), "Codex capture incomplete");
        require(array(m, "turns").size() == 1, "Codex fresh acquisition needs one turn");
        Json turn = array(m, "turns").get(0); expect(turn, "invocation_uid", string(d, "executionId")); expect(turn, "decision_uid", string(d, "decisionId"));
    }
    private static void stream(Json source, Json occurrence, Json turn) {
        String stream = Map.of("stdin", "STDIN", "stream", "STDOUT", "stderr", "STDERR").get(string(source, "role"));
        if (stream == null) { require(occurrence.member("stream").isNull(), "Codex non-stream labelled stream"); return; }
        expect(occurrence, "stream", stream);
        require(number(turn.member("start_offsets"), stream) == 0 && number(turn.member("end_offsets"), stream) == number(source, "bytes"), "Codex stream turn span differs");
    }
    static void sources(Path directory, DeclaredFile file, List<Diagnostic> diagnostics) {
        Json binding = file.run().member("terminal_binding"); if (binding == null) return;
        Json p = exact(string(binding, "terminalEnvelope")), a = exact(string(binding, "association")); if (!selected(p, a)) return;
        Path manifest = directory.resolve("sources/acquisition.json"); if (!Files.isRegularFile(manifest)) return;
        try {
            Json original = NeutralJson.readDocument(manifest);
            require(Json.sameValue(original, file.run().path("launch_settings", "acquired_execution")), "Codex declaration differs from manifest original");
            CodexAcquisitionReading.validate(manifest);
            if (retained(p)) CodexRetainedReading.validate(original, manifest.getParent());
        }
        catch (IOException | IllegalArgumentException e) { diagnostics.add(new Diagnostic(Diagnostic.Severity.DEFECT, "TERMINAL_CODEX_INVALID", e.getMessage())); }
    }
}
