package io.github.markpollack.journal.tck.ocsf;

import io.github.markpollack.journal.tck.verify.Json;
import io.github.markpollack.journal.tck.verify.NeutralJson;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import static io.github.markpollack.journal.tck.ocsf.TerminalReading.*;

/** Independent terminal-to-native association checks; assembly semantic acceptance remains separate. */
final class TerminalNativeReading {
    private TerminalNativeReading() { }
    private static boolean selected(Json p, Json a) {
        return Set.of("client-terminal-claude-2.1.316/1", RetainedAcquisition.PROFILE).contains(string(p, "profile")) && "COMPLETED".equals(string(a, "acquisition"));
    }

    static void check(DeclaredFile file, Json envelope, Json decision, Json association) {
        if (!selected(envelope, association)) return;
        Json manifest = file.run().path("launch_settings", "acquired_execution");
        require(manifest != null, "complete native terminal has no acquisition declaration");
        structure(manifest);
        headers(file, manifest, decision, association);
        Map<String, Json> occurrences = new HashMap<>();
        for (Json occurrence : array(association, "sources")) {
            String path = string(occurrence, "relativePath");
            require(occurrences.put(path, occurrence) == null, "native occurrence path repeated");
            require(number(occurrence, "start") == 0 && number(occurrence, "end") == number(occurrence, "size"), "partial native occurrence");
            require(occurrence.member("nativeTurnId").isNull() && occurrence.member("nativeConnectionId").isNull(), "unsupported native identity assertion");
        }
        Set<String> required = new HashSet<>(); required.add("acquisition.json");
        Set<String> roles = new HashSet<>();
        for (Json source : array(manifest, "sources")) {
            String path = string(source, "path"); require(required.add(path), "native source path repeated");
            Json occurrence = occurrences.get(path); require(occurrence != null, "native source lacks terminal occurrence");
            compare(source, occurrence, manifest, decision);
            roles.add(string(source, "role"));
        }
        require(roles.containsAll(Set.of("stdin", "stream", "stderr", "transcript")), "native source roles missing");
        require(required.equals(occurrences.keySet()), "native terminal inventory does not close");
        Json manifestOccurrence = occurrences.get("acquisition.json");
        expect(manifestOccurrence, "nativeSessionId", string(manifest.member("run"), "session_uid"));
        expect(manifestOccurrence, "clientConnectionId", string(decision, "connectionId"));
        require(manifestOccurrence.member("stream").isNull(), "native manifest incorrectly declares stream");
    }

    private static void structure(Json m) {
        require(m.member("run") instanceof Json.JObject && m.member("process") instanceof Json.JObject, "native run/process declaration absent");
        Json run = m.member("run");
        require(run.member("cli") instanceof Json.JObject && run.member("launch_settings") instanceof Json.JObject, "native CLI/launch declaration absent");
        for (Json turn : array(m, "turns")) {
            require(turn.member("start_offsets") instanceof Json.JObject && turn.member("end_offsets") instanceof Json.JObject, "native turn offsets absent");
        }
    }

    private static void headers(DeclaredFile file, Json m, Json d, Json a) {
        expect(m, "kind", "agent-journal/acquired-execution"); expect(m, "version", "0.1");
        expect(m, "profile", "claude-code-2.1.316-sdk-stdio-session-file");
        expect(m, "profile_id", "claude-code/2.1.316/sdk-stdio+session-file");
        expect(m, "acquisition_uid", string(d, "executionId")); expect(m.member("run"), "run_uid", string(d, "executionId"));
        expect(file.run(), "run_uid", string(d, "executionId"));
        require(file.sessions().equals(List.of(string(m.member("run"), "session_uid"))), "native session declarations differ");
        require(file.assembly() != null, "native archive lacks final assembly");
        expect(m.path("run", "cli"), "version", "2.1.316"); expect(m.path("run", "launch_settings"), "route", "sdk_stdio");
        require(m.member("complete") instanceof Json.JBool b && b.value(), "declared native acquisition incomplete");
        require(array(m, "capture_errors").isEmpty() && array(a, "missing").isEmpty(), "native capture gaps");
        String connection = string(m.member("process"), "connection_uid"); expect(d, "connectionId", connection);
        require(array(a, "connectionIds").size() == 1 && connection.equals(array(a, "connectionIds").get(0).text()), "native connection not the accepted connection");
        require(array(m, "turns").size() == 1, "native profile cannot bind multiple turns");
        expect(array(m, "turns").get(0), "invocation_uid", string(d, "executionId"));
    }

    private static void compare(Json s, Json occurrence, Json m, Json d) {
        expect(s, "state", "acquired"); expect(s, "end", "EOF"); expect(s, "acquisition", string(d, "executionId"));
        for (String name : List.of("origin", "sha256")) expect(occurrence, name, string(s, name));
        require(number(s, "bytes") == number(occurrence, "size"), "native source size differs");
        expect(occurrence, "nativeSessionId", string(m.member("run"), "session_uid"));
        expect(occurrence, "clientConnectionId", string(d, "connectionId"));
        String stream = Map.of("stdin", "STDIN", "stream", "STDOUT", "stderr", "STDERR").get(string(s, "role"));
        if (stream == null) require(occurrence.member("stream").isNull(), "non-stream native source declared as stream");
        else {
            expect(occurrence, "stream", stream); Json turn = array(m, "turns").get(0);
            require(number(turn.member("start_offsets"), stream) == 0 && number(turn.member("end_offsets"), stream) == number(s, "bytes"), "native stream outside turn extent");
        }
    }

    static void sources(Path directory, DeclaredFile file, List<Diagnostic> diagnostics) {
        Json binding = file.run().member("terminal_binding"); if (binding == null) return;
        Json p = exact(string(binding, "terminalEnvelope")), a = exact(string(binding, "association"));
        if (!selected(p, a)) return;
        Path base = directory.resolve("sources"); Path manifest = base.resolve("acquisition.json");
        if (!Files.isRegularFile(manifest)) return; // TerminalSourceReading reports detached bytes as unavailable.
        try {
            Json declared = file.run().path("launch_settings", "acquired_execution");
            Json original = NeutralJson.readDocument(manifest);
            require(Json.sameValue(original, declared), "native manifest bytes disagree with canonical declaration");
            if (original.member("retained_generation") != null) RetainedAcquisition.validate(original, base);
            nativePrompts(base, original);
        }
        catch (IOException | UncheckedIOException | IllegalArgumentException e) {
            diagnostics.add(new Diagnostic(Diagnostic.Severity.DEFECT, "TERMINAL_NATIVE_INVALID", e.getMessage()));
        }
    }

    private record NativeSource(List<Json> prompts, int results) { }

    private static void nativePrompts(Path base, Json manifest) throws IOException {
        Map<String, NativeSource> sources = new HashMap<>();
        String session = string(manifest.member("run"), "session_uid");
        for (Json source : array(manifest, "sources")) {
            String role = string(source, "role");
            if (Set.of("stdin", "stream", "transcript").contains(role)) {
                NativeSource read = scan(base.resolve(string(source, "path")), role, session);
                require(sources.put(role, read) == null, "repeated native stream role");
            }
        }
        require(sources.keySet().containsAll(Set.of("stdin", "stream", "transcript")), "native membership source absent");
        List<Json> input = sources.get("stdin").prompts(), nativeInput = sources.get("transcript").prompts();
        require(input.size() == 1 && nativeInput.size() == 1 && sources.get("stream").results() == 1, "native one-turn population differs");
        require(Json.sameValue(input.get(0), nativeInput.get(0)), "native one-turn message differs");
    }

    private static NativeSource scan(Path path, String role, String session) throws IOException {
        List<Json> prompts = new java.util.ArrayList<>();
        int results = 0, identities = 0, versions = 0;
        for (NativeArtifact.Record record : NativeArtifact.read(path).records()) {
            require(record.parsed(), "native membership source has unparsed record");
            Json row = record.json();
            if (isPrompt(role, row)) prompts.add(row.member("message"));
            if ("result".equals(text(row, "type"))) results++;
            int witnessed = witness(row, session, role);
            identities += witnessed & 1; versions += (witnessed & 2) / 2;
        }
        if (!role.equals("stdin")) require(identities > 0 && versions > 0, "native version/session witness absent");
        return new NativeSource(prompts, results);
    }

    private static boolean isPrompt(String role, Json row) {
        if (!"user".equals(text(row, "type"))) return false;
        return role.equals("stdin") || (role.equals("transcript") && row.path("message", "content") instanceof Json.JString);
    }

    private static int witness(Json row, String session, String role) {
        if (role.equals("stdin")) return 0;
        String identity = role.equals("stream") ? "session_id" : "sessionId";
        String version = role.equals("stream") ? "claude_code_version" : "version";
        int observed = 0;
        if (row.member(identity) != null) { expect(row, identity, session); observed |= 1; }
        if (row.member(version) != null) { expect(row, version, "2.1.316"); observed |= 2; }
        return observed;
    }
    private static String text(Json value, String name) { Json v = value.member(name); return v == null ? null : v.text(); }
    private static void require(boolean value, String message) { if (!value) throw new IllegalArgumentException(message); }
}
