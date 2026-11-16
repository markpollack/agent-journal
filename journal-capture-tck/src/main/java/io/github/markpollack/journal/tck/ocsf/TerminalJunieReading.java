package io.github.markpollack.journal.tck.ocsf;

import io.github.markpollack.journal.tck.verify.Json;
import io.github.markpollack.journal.tck.verify.NeutralJson;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import static io.github.markpollack.journal.tck.ocsf.TerminalReading.*;

/**
 * Independent reading of a Junie ACP one-shot delivered under {@value JunieAcpFamily#TERMINAL_PROFILE}: the accepted
 * decision, the association's whole-file occurrences and the declared qualified manifest name one execution, one
 * connection and one native session, and the archived originals pass {@link JunieAcquisitionReading} again. Semantic
 * acceptance of the assembled file stays with the assembly oracle.
 */
final class TerminalJunieReading {
    private TerminalJunieReading() { }

    private static boolean selected(Json p, Json a) {
        return JunieAcpFamily.TERMINAL_PROFILE.equals(string(p, "profile")) && "COMPLETED".equals(string(a, "acquisition"));
    }

    static void check(DeclaredFile file, Json p, Json d, Json a) {
        if (!selected(p, a)) return;
        Json m = file.run().path("launch_settings", "acquired_execution");
        require(m != null, "Junie acquisition declaration absent");
        String session = header(file, m, d, a);
        Map<String, Json> whole = new HashMap<>();
        for (Json occurrence : array(a, "sources")) {
            require(whole.put(string(occurrence, "relativePath"), occurrence) == null, "Junie occurrence repeated");
            occurrence(occurrence, session, string(d, "connectionId"));
        }
        require(whole.containsKey("acquisition.json"), "Junie manifest occurrence absent");
        Set<String> paths = new java.util.HashSet<>(Set.of("acquisition.json"));
        Set<String> roles = new java.util.HashSet<>();
        for (Json s : array(m, "sources")) {
            require(paths.add(string(s, "path")) && roles.add(string(s, "role")), "Junie duplicate source");
            Json occurrence = whole.get(string(s, "path"));
            require(occurrence != null, "Junie original not associated");
            expect(s, "acquisition", string(d, "executionId"));
            expect(occurrence, "origin", string(s, "origin"));
            expect(occurrence, "sha256", string(s, "sha256"));
            require(number(occurrence, "size") == number(s, "bytes"), "Junie source extent differs");
        }
        require(paths.equals(whole.keySet()) && roles.equals(Set.copyOf(JunieAcquisitionReading.ROLES)), "Junie original inventory differs");
    }

    /** One whole native file of the accepted connection's session, never a stream and never a turn or connection claim. */
    private static void occurrence(Json o, String session, String connection) {
        require(number(o, "start") == 0 && number(o, "end") == number(o, "size"), "Junie whole-file extent required");
        require(o.member("stream").isNull() && o.member("nativeTurnId").isNull() && o.member("nativeConnectionId").isNull(), "Junie occurrence overstates its identity");
        expect(o, "nativeSessionId", session);
        expect(o, "clientConnectionId", connection);
    }

    /** The declared manifest names the accepted execution, decision, Client session and connection; returns its native session. */
    private static String header(DeclaredFile file, Json m, Json d, Json a) {
        String version = string(m.path("run", "cli"), "version");
        expect(m, "profile", JunieAcpFamily.profile(version));
        expect(m, "profile_id", JunieAcpFamily.profileId(version));
        String execution = string(d, "executionId"), session = string(m.member("run"), "session_uid");
        expect(m, "acquisition_uid", execution);
        expect(file.run(), "run_uid", execution);
        require(file.assembly() != null && file.sessions().equals(List.of(session)), "Junie assembly/session differs");
        expect(d.member("outcome"), "kind", "SUCCESS");
        expect(d.member("provider"), "kind", "REPORTED_SUCCESS");
        expect(m.member("process"), "connection_uid", string(d, "connectionId"));
        expect(m.member("process"), "client_session_uid", string(d, "sessionId"));
        require(array(a, "connectionIds").stream().map(Json::text).toList().equals(List.of(string(d, "connectionId"))), "Junie accepted connection differs");
        require(array(a, "missing").isEmpty(), "Junie capture incomplete");
        Json turn = array(m, "turns").get(0);
        expect(turn, "invocation_uid", execution);
        expect(turn, "decision_uid", string(d, "decisionId"));
        return session;
    }

    /** The archived originals beside the declared manifest, read again from their bytes. */
    static void sources(Path directory, DeclaredFile file, List<Diagnostic> diagnostics) {
        Json binding = file.run().member("terminal_binding");
        if (binding == null) return;
        if (!selected(exact(string(binding, "terminalEnvelope")), exact(string(binding, "association")))) return;
        Path manifest = directory.resolve("sources/acquisition.json");
        if (!Files.isRegularFile(manifest)) return; // TerminalSourceReading reports detached bytes as unavailable.
        try {
            require(Json.sameValue(NeutralJson.readDocument(manifest), file.run().path("launch_settings", "acquired_execution")), "Junie declaration differs from manifest original");
            JunieAcquisitionReading.validate(manifest);
        }
        catch (IOException | IllegalArgumentException e) {
            diagnostics.add(new Diagnostic(Diagnostic.Severity.DEFECT, "TERMINAL_JUNIE_INVALID", e.getMessage()));
        }
    }

    private static void require(boolean pass, String reason) {
        if (!pass) throw new IllegalArgumentException(reason);
    }
}
