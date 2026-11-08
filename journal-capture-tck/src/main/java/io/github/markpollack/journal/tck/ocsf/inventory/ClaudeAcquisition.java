package io.github.markpollack.journal.tck.ocsf.inventory;

import io.github.markpollack.journal.tck.ocsf.ClaudeSdkFamily;

import io.github.markpollack.journal.tck.ocsf.NativeArtifact;
import io.github.markpollack.journal.tck.verify.Json;
import io.github.markpollack.journal.tck.verify.NeutralJson;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Qualifies a sealed original-byte Claude Code SDK acquisition ({@link ClaudeSdkFamily}) without changing the producer's files.
 * The manifest's CLI version must be one the family lists, and every native version witness must state that same
 * version. Native identity/version checks are independent of the writer. This does not claim semantic completeness:
 * assemble the returned manifest and run the independent verifier with the returned expectation.
 */
public final class ClaudeAcquisition {
    private static final String EXPECTATION = "/ocsf/qualification/claude-code-2.1.316.json";

    private ClaudeAcquisition() { }

    /**
     * The qualifier for one version's acquisition profile, delivered under {@link ClaudeSdkFamily#TERMINAL_PROFILE}; it runs
     * {@link #qualify(Path, Path)} and refuses a manifest declaring any other profile.
     */
    public static AcquisitionQualifier qualifier(String version) {
        return new ProfileQualifier(ClaudeSdkFamily.profile(version), ClaudeSdkFamily.TERMINAL_PROFILE, (manifest, directory) -> {
            String declared = RawInventory.text(NeutralJson.parse(Files.readString(manifest)), "profile");
            require(ClaudeSdkFamily.profile(version).equals(declared), "unqualified profile");
            return qualify(manifest, directory);
        });
    }

    /** Copies validated, frozen bytes into a directory that must not exist. No vendor parsing is needed by callers. */
    public static AcquisitionQualifier.Qualified qualify(Path sealedManifest, Path newDirectory) throws IOException {
        byte[] original = Files.readAllBytes(sealedManifest);
        Json manifest = NeutralJson.parse(new String(original, StandardCharsets.UTF_8));
        List<Frozen> sources = validated(manifest, sealedManifest.toAbsolutePath().getParent());
        String version = version(manifest);
        Json bindings = resource("/ocsf/source-meaning-bindings.json");
        for (Frozen source : sources) { bind(source.source(), bindings); }
        qualifyOutputs(manifest, sealedManifest.toAbsolutePath().getParent());
        qualifySubAgents(manifest, sealedManifest.toAbsolutePath().getParent(), bindings);
        Json.JObject qualification = new Json.JObject(java.util.Map.of(
                "original_manifest_sha256", new Json.JString(NativeArtifact.sha256(original)),
                "basis", new Json.JString("exact native version/session and original-byte extent checks; canonical verification is separate")));
        ((Json.JObject) manifest).members().put("qualification", qualification);
        Files.createDirectory(newDirectory);
        for (Frozen source : sources) { source.write(newDirectory); }
        write(newDirectory.resolve("client-acquisition.json"), original);
        Path output = newDirectory.resolve("acquisition.json");
        write(output, NeutralJson.write(manifest).getBytes(StandardCharsets.UTF_8));
        Path expectation = newDirectory.resolve("assembly-expectation.json");
        write(expectation, NeutralJson.write(expectation(version)).getBytes(StandardCharsets.UTF_8));
        return new AcquisitionQualifier.Qualified(output, expectation);
    }

    /** The same native checks are executed again by the independent verifier; no candidate supplies identity. */
    static void validate(Json manifest, Path directory) throws IOException {
        validated(manifest, directory);
    }

    private static List<Frozen> validated(Json manifest, Path directory) throws IOException {
        header(manifest);
        Path base = directory.toRealPath();
        List<Frozen> sources = new ArrayList<>();
        Set<String> artifacts = new HashSet<>();
        Set<Path> paths = new HashSet<>();
        Set<String> roles = new HashSet<>();
        for (Json source : manifest.member("sources").elements()) {
            require(artifacts.add(text(source, "artifact")), "duplicate artifact");
            require(source.member("required") instanceof Json.JBool, "explicit required flag needed");
            if ("missing".equals(text(source, "state"))) { text(source, "reason"); continue; }
            require(text(manifest, "acquisition_uid").equals(text(source, "acquisition")), "wrong acquisition identity");
            Frozen frozen = freeze(base, source);
            require(paths.add(frozen.relative()), "duplicate source path");
            selected(manifest, frozen, roles);
            sources.add(frozen);
        }
        require(roles.contains("stream"), "original stdout required");
        return sources;
    }

    private static void header(Json m) {
        require("agent-journal/acquired-execution".equals(text(m, "kind")) && "0.1".equals(text(m, "version")), "unsupported acquisition");
        String version = text(m.path("run", "cli"), "version");
        require(ClaudeSdkFamily.wellFormed(version), "malformed CLI version");
        require(ClaudeSdkFamily.profile(version).equals(text(m, "profile")) && ClaudeSdkFamily.profileId(version).equals(text(m, "profile_id")), "unqualified profile");
        require(m.member("complete") instanceof Json.JBool && m.member("process") instanceof Json.JObject, "explicit acquisition/process status required");
        require(m.member("sources") instanceof Json.JArray, "source inventory required");
        Json run = m.member("run");
        require(text(m, "acquisition_uid").equals(text(run, "run_uid")), "run/acquisition mismatch");
        text(run, "session_uid");
        require("Claude Code".equals(text(run.member("cli"), "name")), "unqualified CLI");
        require("sdk_stdio".equals(text(run.member("launch_settings"), "route")), "unqualified launch route");
        Json argv = m.path("process", "argv");
        require(argv instanceof Json.JArray, "observed process argv required");
        List<String> args = argv.elements().stream().map(Json::text).toList();
        require(option(args, "--input-format", "stream-json") && option(args, "--output-format", "stream-json"), "not original SDK stdio route");
    }

    private static boolean option(List<String> args, String key, String value) {
        int index = args.indexOf(key);
        return index >= 0 && index + 1 < args.size() && value.equals(args.get(index + 1));
    }

    private static Frozen freeze(Path base, Json source) throws IOException {
        require("acquired".equals(text(source, "state")), "unknown source state");
        require(source.member("required") instanceof Json.JBool, "explicit required flag needed");
        require(Set.of("EOF", "INTERRUPTED").contains(text(source, "end")), "unknown source end");
        text(source, "origin");
        Path relative = Path.of(text(source, "path")).normalize();
        require(!relative.isAbsolute() && !relative.startsWith(".."), "source escapes acquisition");
        require(!Set.of("acquisition.json", "client-acquisition.json", "assembly-expectation.json").contains(relative.toString()), "reserved source path");
        Path path = base.resolve(relative).toRealPath();
        require(path.startsWith(base), "source symlink escapes acquisition");
        byte[] bytes = Files.readAllBytes(path);
        require(NativeArtifact.sha256(bytes).equals(text(source, "sha256")), "source hash mismatch");
        require(source.member("bytes") instanceof Json.JNumber n && n.value().longValueExact() == bytes.length, "source extent mismatch");
        return new Frozen(source, relative, bytes, path);
    }

    private static void selected(Json manifest, Frozen frozen, Set<String> roles) {
        Json source = frozen.source();
        String role = text(source, "role");
        if (source.member("archive_only") instanceof Json.JBool b && b.value()) {
            require(!Set.of("stream", "transcript").contains(role), "selected source cannot be archived");
            return;
        }
        if ("sidecar".equals(role) && "opaque_text".equals(RawInventory.text(source, "profile"))) { return; }
        boolean child = ClaudeSubAgentSession.ROLE.equals(role);
        require(child || (Set.of("stream", "transcript").contains(role) && roles.add(role)), "unsupported or duplicate selected role");
        require(!child || source.member("child_binding") instanceof Json.JObject, "selected sub-agent session file needs its child binding");
        boolean stream = "stream".equals(role);
        require((stream ? "claude_stream" : "claude_transcript").equals(text(source, "profile")), "wrong source profile");
        require((stream ? "sdk_stdio" : "native_session_file").equals(text(source, "route")), "wrong source route");
        require("lines".equals(text(source, "framing")), "wrong framing");
        NativeArtifact artifact = NativeArtifact.read(frozen.path());
        require(artifact.sha256().equals(NativeArtifact.sha256(frozen.bytes())), "source changed during qualification");
        require(source.member("records") instanceof Json.JNumber n && n.value().intValueExact() == artifact.records().size(), "record extent mismatch");
        nativeIdentity(manifest, source, artifact, stream);
    }

    private static void nativeIdentity(Json manifest, Json source, NativeArtifact artifact, boolean stream) {
        String session = text(manifest.member("run"), "session_uid");
        String expected = version(manifest);
        if (!stream) { require(session.equals(text(source, "owner_session_uid")), "wrong native owner"); }
        int identities = 0;
        int versions = 0;
        for (NativeArtifact.Record record : artifact.records()) {
            if (!record.parsed()) { continue; }
            Json row = record.json();
            Json identity = row.member(stream ? "session_id" : "sessionId");
            if (identity != null) { require(session.equals(identity.text()), "native session mismatch"); identities++; }
            Json version = nativeVersion(row, stream);
            if (version != null) { require(expected.equals(version.text()), "native version mismatch"); versions++; }
        }
        require(identities > 0 && versions > 0, "native identity/version evidence absent");
    }

    private static Json nativeVersion(Json row, boolean stream) {
        if (stream) {
            return "system".equals(RawInventory.text(row, "type")) && "init".equals(RawInventory.text(row, "subtype"))
                    ? row.member("claude_code_version") : null;
        }
        Json entrypoint = row.member("entrypoint");
        require(entrypoint == null || "sdk-java".equals(entrypoint.text()), "native entrypoint mismatch");
        return row.member("version");
    }

    private static void qualifyOutputs(Json manifest, Path base) {
        java.util.Map<String, Json> bindings = ClaudeToolOutput.bindings(manifest, base);
        for (Json source : manifest.member("sources").elements()) {
            Json binding = bindings.get(RawInventory.text(source, "artifact"));
            if (binding == null) { continue; }
            java.util.Map<String, Json> fields = ((Json.JObject) source).members();
            fields.put("archive_only", new Json.JBool(false));
            fields.put("profile", new Json.JString("opaque_text"));
            fields.put("route", new Json.JString("native_tool_result"));
            fields.put("framing", new Json.JString("document"));
            fields.put("records", new Json.JNumber("1"));
            fields.put("tool_result_binding", binding);
        }
    }

    /** A sub-agent session file that binds to its spawning call is read into that call's child core; its meta file stays preserved. */
    private static void qualifySubAgents(Json manifest, Path base, Json meanings) {
        java.util.Map<String, Json> bindings = ClaudeSubAgentSession.bindings(manifest, base);
        for (Json source : manifest.member("sources").elements()) {
            Json binding = bindings.get(RawInventory.text(source, "artifact"));
            if (binding == null) { continue; }
            java.util.Map<String, Json> fields = ((Json.JObject) source).members();
            fields.put("child_binding", binding);
            if (!ClaudeSubAgentSession.ROLE.equals(RawInventory.text(source, "role"))) { continue; }
            fields.put("archive_only", new Json.JBool(false));
            fields.put("profile", new Json.JString("claude_transcript"));
            fields.put("route", new Json.JString("native_session_file"));
            fields.put("framing", new Json.JString("lines"));
            fields.put("records", new Json.JNumber(Integer.toString(NativeArtifact.read(base.resolve(RawInventory.text(source, "path"))).records().size())));
            bind(source, meanings);
        }
    }

    /** The CLI version the manifest declares; the header has checked it is one the family lists. */
    private static String version(Json manifest) {
        return text(manifest.member("run").member("cli"), "version");
    }

    private static String route(String profile) {
        return "claude_stream".equals(profile) ? "sdk_stdio" : "native_session_file";
    }

    private static void bind(Json source, Json bindings) {
        String profile = RawInventory.text(source, "profile");
        if (profile == null || "opaque_text".equals(profile)) { return; }
        for (Json binding : bindings.member("bindings").elements()) {
            // the version-neutral binding of the profile's SDK route: the version is recorded, not gated
            if (profile.equals(RawInventory.text(binding, "profile")) && binding.member("version") == null && route(profile).equals(RawInventory.text(binding, "route"))) {
                ((Json.JObject) source).members().put("binding", binding);
                return;
            }
        }
        throw new IllegalArgumentException("no exact source binding");
    }

    private static Json resource(String path) throws IOException {
        try (InputStream in = ClaudeAcquisition.class.getResourceAsStream(path)) {
            if (in == null) { throw new IOException("missing qualification resource " + path); }
            return NeutralJson.parse(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    /**
     * The assembly expectation for an admitted version: one set of independent native locators for the whole family,
     * stated under that version's profile id.
     */
    static Json expectation(String version) throws IOException {
        Json expectation = resource(EXPECTATION);
        ((Json.JObject) expectation).members().put("profile_id", new Json.JString(ClaudeSdkFamily.profileId(version)));
        return expectation;
    }

    private static String text(Json value, String key) {
        String result = value == null ? null : RawInventory.text(value, key);
        require(result != null && !result.isBlank(), "missing " + key);
        return result;
    }

    private static void require(boolean condition, String reason) {
        if (!condition) { throw new IllegalArgumentException(reason); }
    }

    private static void write(Path path, byte[] bytes) throws IOException {
        Files.write(path, bytes, StandardOpenOption.CREATE_NEW);
    }

    @SuppressWarnings("ArrayRecordComponent") // private owned snapshot, never compared using record equality
    private record Frozen(Json source, Path relative, byte[] bytes, Path path) {
        void write(Path directory) throws IOException {
            Path output = directory.resolve(relative);
            Files.createDirectories(output.getParent());
            ClaudeAcquisition.write(output, bytes);
        }
    }

    /** Packaged qualification command. Refuses malformed inputs with a nonzero exit; never launches a CLI. */
    public static void main(String[] args) throws IOException {
        if (args.length != 2) { throw new IllegalArgumentException("usage: ClaudeAcquisition <sealed-manifest> <new-qualified-directory>"); }
        qualify(Path.of(args[0]), Path.of(args[1]));
    }
}
