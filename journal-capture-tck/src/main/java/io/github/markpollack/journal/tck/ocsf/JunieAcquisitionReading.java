package io.github.markpollack.journal.tck.ocsf;

import io.github.markpollack.journal.tck.verify.Json;
import io.github.markpollack.journal.tck.verify.NeutralJson;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Independent original-byte validation of one sealed Junie ACP one-shot acquisition ({@link JunieAcpFamily},
 * docs/junie-acquisition.md): the manifest's identifiers and process facts, the binding of the ACP session to exactly
 * one native session directory, and the two native files' bytes. The native records are read by
 * {@link JunieSessionReading}. No writer, Client or CLI class is used; each refusal is an
 * {@link IllegalArgumentException} whose message names its reason.
 */
public final class JunieAcquisitionReading {
    /** The two source roles, in the order they are read. */
    public static final List<String> ROLES = List.of("summary", "session");
    private static final Map<String, String> PROFILES = Map.of("summary", JunieAcpFamily.SUMMARY_PROFILE, "session", JunieAcpFamily.SESSION_PROFILE);
    private static final Map<String, String> FRAMING = Map.of("summary", "document", "session", "lines");
    private static final Map<String, String> FILES = Map.of("summary", "summary.json", "session", "events.jsonl");
    private static final Set<String> RESERVED = Set.of("acquisition.json", "client-acquisition.json", "assembly-expectation.json");

    private JunieAcquisitionReading() { }

    /** One validated original: its declaration, its path relative to the sealed directory and a snapshot of its bytes. */
    @SuppressWarnings("ArrayRecordComponent") // byte snapshots are copied at the public boundary, never compared by record equality
    public record Source(Json declaration, Path relative, byte[] bytes) {
        public Source { bytes = bytes.clone(); }
        @Override public byte[] bytes() { return bytes.clone(); }
    }

    /** The identities a sealed manifest binds the native session to. */
    record Identity(String acquisition, String session, String workingDirectory, String task, Path sessionDirectory) { }

    /** Read-only validation of a sealed or qualified manifest and the originals beside it. */
    public static void validate(Path manifest) throws IOException {
        read(NeutralJson.readDocument(manifest), manifest.toAbsolutePath().getParent());
    }

    /** Validates the manifest and the originals in {@code directory}; returns the two sources by role. */
    public static Map<String, Source> read(Json m, Path directory) throws IOException {
        header(m);
        complete(m);
        Identity identity = identity(m);
        Map<String, Source> sources = sources(m, directory.toRealPath(), identity);
        JunieSessionReading.summary(sources.get("summary").bytes(), identity);
        JunieSessionReading.events(sources.get("session"), identity);
        return sources;
    }

    /** The CLI version the manifest declares, once its kind, format version and both profile names agree with it. */
    public static String version(Json m) {
        return text(m.path("run", "cli"), "version");
    }

    private static void header(Json m) {
        require("agent-journal/acquired-execution".equals(text(m, "kind")) && "0.1".equals(text(m, "version")), "unsupported acquisition kind or version");
        String version = version(m);
        require(JunieAcpFamily.wellFormed(version), "malformed Junie version " + version);
        require(JunieAcpFamily.profile(version).equals(text(m, "profile")), "profile " + text(m, "profile") + " is not " + JunieAcpFamily.profile(version));
        require(JunieAcpFamily.profileId(version).equals(text(m, "profile_id")), "profile_id " + text(m, "profile_id") + " is not " + JunieAcpFamily.profileId(version));
        text(m.path("run", "cli"), "name");
        text(m.path("run", "cli"), "vendor_name");
        text(m.member("run"), "capture_start");
    }

    private static void complete(Json m) {
        require(m.member("complete") instanceof Json.JBool b && b.value(), "incomplete events: the acquisition is not complete");
        require(array(m, "capture_errors").isEmpty(), "incomplete events: capture errors present");
        Json process = m.member("process");
        for (String fact : List.of("fresh_session", "sealed", "observed_resources_terminated")) {
            require(process != null && process.member(fact) instanceof Json.JBool b && b.value(), "incomplete events: process." + fact + " is not true");
        }
        require("end_turn".equals(text(process, "acp_stop_reason")), "incomplete events: acp_stop_reason is " + text(process, "acp_stop_reason") + ", not end_turn");
    }

    private static Identity identity(Json m) {
        Json run = m.member("run"), process = m.member("process");
        String acquisition = text(m, "acquisition_uid"), session = text(process, "acp_session_id");
        List<String> fresh = array(process, "native_sessions_new").stream().map(Json::text).toList();
        require(fresh.equals(List.of(session)), "more than one candidate native session: native_sessions_new is " + fresh + ", not [" + session + "]");
        require(acquisition.equals(text(run, "run_uid")), "identity mismatch: run.run_uid differs from acquisition_uid");
        require(session.equals(text(run, "session_uid")), "identity mismatch: run.session_uid differs from acp_session_id");
        String cwd = text(process, "cwd");
        require(cwd.equals(text(run.member("launch_settings"), "working_directory")), "identity mismatch: launch_settings.working_directory differs from process.cwd");
        require("acp_stdio".equals(text(run.member("launch_settings"), "route")), "unsupported route " + text(run.member("launch_settings"), "route"));
        text(process, "client_session_uid");
        text(process, "connection_uid");
        require(!array(process, "argv").isEmpty() && array(process, "argv").stream().allMatch(a -> a.text() != null && !a.text().isEmpty()), "observed argv required");
        Path sessions = Path.of(text(process, "native_sessions_directory"));
        require(sessions.isAbsolute() && sessions.normalize().equals(sessions), "native_sessions_directory is not an absolute normal path");
        List<Json> turns = array(m, "turns");
        require(turns.size() == 1, "exactly one turn required");
        require(acquisition.equals(text(turns.get(0), "invocation_uid")), "identity mismatch: turns[0].invocation_uid differs from acquisition_uid");
        text(turns.get(0), "decision_uid");
        return new Identity(acquisition, session, cwd, text(turns.get(0), "native_task_uid"), sessions.resolve(session));
    }

    private static Map<String, Source> sources(Json m, Path base, Identity identity) throws IOException {
        Map<String, Json> declared = new HashMap<>();
        for (Json s : array(m, "sources")) {
            String role = text(s, "role");
            require(PROFILES.containsKey(role), "unsupported source role " + role);
            require(declared.put(role, s) == null, "duplicate source role " + role);
        }
        Map<String, Source> sources = new LinkedHashMap<>();
        Set<String> names = new HashSet<>();
        for (String role : ROLES) {
            Json s = declared.get(role);
            require(s != null, "missing " + role + ": the source is not declared");
            require("acquired".equals(text(s, "state")), "missing " + role + ": state is " + text(s, "state") + ", not acquired");
            declaration(s, role, identity);
            Source source = freeze(base, s, role);
            require(names.add(source.relative().toString()), "duplicate source path " + source.relative());
            require(names.add("artifact:" + text(s, "artifact")), "duplicate source artifact " + text(s, "artifact"));
            sources.put(role, source);
        }
        return sources;
    }

    private static void declaration(Json s, String role, Identity identity) {
        require(identity.acquisition().equals(text(s, "acquisition")), "identity mismatch: " + role + " acquisition differs from acquisition_uid");
        require(identity.session().equals(text(s, "owner_session_uid")), "identity mismatch: " + role + " owner_session_uid differs from acp_session_id");
        Path origin = Path.of(text(s, "origin"));
        require(origin.isAbsolute() && origin.normalize().equals(origin) && identity.sessionDirectory().equals(origin.getParent())
                && FILES.get(role).equals(String.valueOf(origin.getFileName())), "identity mismatch: " + role + " origin " + origin + " is not "
                + FILES.get(role) + " directly inside " + identity.sessionDirectory());
        require(s.member("required") instanceof Json.JBool r && r.value() && s.member("archive_only") instanceof Json.JBool a && !a.value(), role + " must be required and not archive-only");
        require(PROFILES.get(role).equals(text(s, "profile")) && "native_session_file".equals(text(s, "route")), role + " profile or route differs from its role");
        require("EOF".equals(text(s, "end")) && number(s, "dropped") == 0, "incomplete events: " + role + " was dropped or did not end at EOF");
        require(FRAMING.get(role).equals(text(s, "framing")), "incomplete events: " + role + " framing " + text(s, "framing") + " differs from its role (" + FRAMING.get(role) + ")");
        require(!"summary".equals(role) || number(s, "records") == 1, "incomplete events: summary records " + number(s, "records") + " differs from its role (one document)");
    }

    private static Source freeze(Path base, Json s, String role) throws IOException {
        Path relative = Path.of(text(s, "path")).normalize();
        require(!relative.isAbsolute() && !relative.startsWith(".."), "source path escapes the acquisition: " + relative);
        require(!RESERVED.contains(relative.getName(0).toString()), "reserved source path " + relative);
        Path file = base.resolve(relative);
        require(Files.isRegularFile(file), "missing " + role + ": " + relative + " is absent from the sealed acquisition");
        Path real = file.toRealPath();
        require(real.startsWith(base), "source symlink escapes the acquisition: " + relative);
        byte[] bytes = Files.readAllBytes(real);
        require(bytes.length == number(s, "bytes") && bytes.length == number(s, "observed"), "changed bytes: " + relative + " is " + bytes.length
                + " bytes, declared bytes " + number(s, "bytes") + " and observed " + number(s, "observed"));
        require(NativeArtifact.sha256(bytes).equals(text(s, "sha256")), "changed bytes: " + relative + " SHA-256 differs from its declaration");
        return new Source(s, relative, bytes);
    }

    static String text(Json value, String key) {
        Json v = value == null ? null : value.member(key);
        String s = v == null ? null : v.text();
        require(s != null && !s.isBlank(), "missing " + key);
        return s;
    }

    static List<Json> array(Json value, String key) {
        if (value == null || !(value.member(key) instanceof Json.JArray found)) {
            throw new IllegalArgumentException("missing array " + key);
        }
        return found.elements();
    }

    static long number(Json value, String key) {
        if (value == null || !(value.member(key) instanceof Json.JNumber found) || found.value().stripTrailingZeros().scale() > 0) {
            throw new IllegalArgumentException("missing integer " + key);
        }
        try {
            return found.value().longValueExact();
        }
        catch (ArithmeticException e) {
            throw new IllegalArgumentException("integer out of range: " + key, e);
        }
    }

    static void require(boolean pass, String reason) {
        if (!pass) {
            throw new IllegalArgumentException(reason);
        }
    }
}
