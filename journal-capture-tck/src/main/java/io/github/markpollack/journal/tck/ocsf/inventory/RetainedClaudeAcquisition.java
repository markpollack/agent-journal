package io.github.markpollack.journal.tck.ocsf.inventory;

import io.github.markpollack.journal.tck.ocsf.ClaudeSdkFamily;

import io.github.markpollack.journal.tck.ocsf.FreshRetainedGeneration;
import io.github.markpollack.journal.tck.ocsf.NativeArtifact;
import io.github.markpollack.journal.tck.ocsf.RetainedAcquisition;
import io.github.markpollack.journal.tck.ocsf.RetainedGeneration;
import io.github.markpollack.journal.tck.verify.Json;
import io.github.markpollack.journal.tck.verify.NeutralJson;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Opt-in qualifier for a sealed two-text-turn generation. No process is launched or awaited.
 * All source and prefix observations must already exist. Successful qualification is deterministic
 * fixture evidence, not native/provider admission; independent final-file verification is still required.
 */
public final class RetainedClaudeAcquisition {
    private RetainedClaudeAcquisition() { }

    /** Copies originals and exact per-turn projections to a new directory, leaving every input unchanged. */
    public static AcquisitionQualifier.Qualified qualify(Path generationManifest, String executionId, Path newDirectory) throws IOException {
        return qualify(generationManifest, executionId, newDirectory, null);
    }

    /** {@code freshProfile} names the fresh-start profile the generation is qualified under; null is the frozen retained kind. */
    static AcquisitionQualifier.Qualified qualify(Path generationManifest, String executionId, Path newDirectory, String freshProfile) throws IOException {
        boolean fresh = freshProfile != null;
        byte[] bytes = Files.readAllBytes(generationManifest);
        Json generation = NeutralJson.parse(new String(bytes, StandardCharsets.UTF_8));
        Path base = generationManifest.toAbsolutePath().getParent();
        // a version-neutral profile names its predecessor's contract; the delivered name itself is stamped below
        String contract = ClaudeSdkFamily.contract(freshProfile);
        if (fresh) FreshRetainedGeneration.validate(generation, base, contract); else RetainedGeneration.validate(generation, base);
        RetainedGeneration.turn(generation, executionId);
        Files.createDirectory(newDirectory);
        List<Json> sources = copySources(generation, base, executionId, newDirectory, contract);
        if (fresh) {
            String observations = text(generation.member("observations"), "path"); byte[] journal = Files.readAllBytes(base.resolve(observations));
            write(newDirectory.resolve("generation/" + observations), journal);
            sources.add(source("generation/" + observations, "generation-observations", journal, executionId, "retained-fresh-observations", true));
        }
        String path = RetainedAcquisition.manifestPath(fresh); write(newDirectory.resolve(path), bytes);
        sources.add(source(path, "generation-manifest", bytes, executionId, fresh ? "retained-fresh-generation" : "retained-generation", true));
        Json.JObject manifest = manifest(generation, executionId, sources); stamp(manifest, generation, freshProfile);
        Path output = newDirectory.resolve("acquisition.json"); write(output, NeutralJson.write(manifest).getBytes(StandardCharsets.UTF_8));
        Path expectation = newDirectory.resolve("assembly-expectation.json");
        write(expectation, NeutralJson.write(ClaudeAcquisition.expectation(text(generation, "cliVersion"))).getBytes(StandardCharsets.UTF_8));
        RetainedAcquisition.validate(manifest, newDirectory, fresh);
        return new AcquisitionQualifier.Qualified(output, expectation);
    }

    /** The fresh profile the generation was qualified under and, for the native profile, the recorded launch-argv completeness. */
    private static void stamp(Json.JObject manifest, Json generation, String freshProfile) {
        if (freshProfile != null) manifest.members().put("retained_profile", new Json.JString(freshProfile));
        if (FreshRetainedGeneration.NATIVE_PROFILE.equals(ClaudeSdkFamily.contract(freshProfile))) manifest.members().put("retained_launch_argv", new Json.JString(FreshRetainedGeneration.launchArgvObservation(generation)));
    }

    private static List<Json> copySources(Json generation, Path base, String execution, Path directory, String freshProfile) throws IOException {
        List<Json> sources = new ArrayList<>();
        for (Json original : generation.member("sources").elements()) {
            String name = text(original, "source"), path = "generation/" + text(original, "path");
            byte[] bytes = Files.readAllBytes(base.resolve(text(original, "path")));
            write(directory.resolve(path), bytes);
            sources.add(source(path, "generation-" + name, bytes, execution, text(original, "origin"), true));
            byte[] projected = RetainedGeneration.projection(generation, execution, name, base, freshProfile);
            String selectedPath = RetainedAcquisition.projectionPath(name), role = RetainedAcquisition.role(name);
            write(directory.resolve(selectedPath), projected);
            sources.add(source(selectedPath, role, projected, execution, text(original, "origin") + "#execution=" + execution,
                    role.equals("stdin") || role.equals("stderr")));
        }
        return sources;
    }

    private static Json source(String path, String role, byte[] bytes, String execution, String origin, boolean archive) throws IOException {
        Json.JObject source = object("artifact", path, "path", path, "role", role, "origin", origin, "state", "acquired",
                "acquisition", execution, "required", true, "end", "EOF", "bytes", bytes.length, "sha256", NativeArtifact.sha256(bytes), "archive_only", archive);
        if (!archive) {
            String profile = role.equals("stream") ? "claude_stream" : "claude_transcript";
            source.members().putAll(object("profile", profile, "route", role.equals("stream") ? "sdk_stdio" : "native_session_file",
                    "framing", "lines", "records", new String(bytes, StandardCharsets.UTF_8).lines().count(), "binding", binding(profile)).members());
        }
        return source;
    }

    private static Json.JObject manifest(Json generation, String execution, List<Json> sources) {
        String session = text(generation, "nativeSessionId");
        for (Json source : sources) if ("transcript".equals(text(source, "role"))) ((Json.JObject) source).members().put("owner_session_uid", new Json.JString(session));
        Json.JObject end = object("STDIN", extent(sources, "stdin"), "STDOUT", extent(sources, "stream"), "STDERR", 0);
        Json turn = object("invocation_uid", execution, "start_offsets", object("STDIN", 0, "STDOUT", 0, "STDERR", 0), "end_offsets", end);
        Json run = object("run_uid", execution, "invocation_index", 0, "session_uid", session, "capture_start", text(generation, "captureStart"),
                "cli", object("name", "Claude Code", "version", text(generation, "cliVersion"), "vendor_name", "Anthropic"), "launch_settings", object("route", "sdk_stdio"));
        return object("kind", "agent-journal/acquired-execution", "version", "0.1", "profile", ClaudeSdkFamily.profile(text(generation, "cliVersion")),
                "profile_id", ClaudeSdkFamily.profileId(text(generation, "cliVersion")), "acquisition_uid", execution, "complete", true, "capture_errors", new Json.JArray(List.of()),
                "run", run, "process", object("connection_uid", text(generation, "connectionId"), "observed_resources_terminated", true,
                        "stdout_natural_eof", true, "stderr_natural_eof", true, "basis", "generation observations; projection EOF is not per-turn transport EOF"),
                "turns", new Json.JArray(List.of(turn)), "sources", new Json.JArray(sources), "retained_generation", generation, "retained_execution_id", execution);
    }

    private static long extent(List<Json> sources, String role) { return sources.stream().filter(s -> role.equals(text(s, "role"))).mapToLong(s -> ((Json.JNumber) s.member("bytes")).value().longValueExact()).findFirst().orElseThrow(); }

    private static Json binding(String profile) throws IOException {
        try (var input = RetainedClaudeAcquisition.class.getResourceAsStream("/ocsf/source-meaning-bindings.json")) {
            if (input == null) throw new IOException("source bindings unavailable");
            Json bindings = NeutralJson.parse(new String(input.readAllBytes(), StandardCharsets.UTF_8));
            return bindings.member("bindings").elements().stream().filter(b -> profile.equals(text(b, "profile")) && b.member("version") == null
                    && ("claude_stream".equals(profile) ? "sdk_stdio" : "native_session_file").equals(text(b, "route"))).findFirst().orElseThrow();
        }
    }

    /** Shared by the retained qualifiers: pairs of member name and Json/Boolean/Number/text value. */
    static Json.JObject object(Object... pairs) {
        Map<String, Json> values = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            Object value = pairs[i + 1]; Json json;
            if (value instanceof Json j) json = j;
            else if (value instanceof Boolean b) json = new Json.JBool(b);
            else if (value instanceof Number n) json = new Json.JNumber(n.toString());
            else json = new Json.JString(value.toString());
            values.put((String) pairs[i], json);
        }
        return new Json.JObject(values);
    }

    static void write(Path path, byte[] bytes) throws IOException { Files.createDirectories(path.getParent()); Files.write(path, bytes, StandardOpenOption.CREATE_NEW); }
    private static String text(Json value, String key) { return value.member(key).text(); }

    /** Packaged command; refuses invalid input and never invokes a provider. */
    public static void main(String[] args) throws IOException {
        if (args.length != 3) throw new IllegalArgumentException("usage: RetainedClaudeAcquisition <generation-manifest> <execution-id> <new-directory>");
        qualify(Path.of(args[0]), args[1], Path.of(args[2]));
    }
}
