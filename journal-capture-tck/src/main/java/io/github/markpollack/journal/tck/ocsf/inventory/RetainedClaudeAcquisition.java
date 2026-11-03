package io.github.markpollack.journal.tck.ocsf.inventory;

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
    public static ClaudeAcquisition.Qualified qualify(Path generationManifest, String executionId, Path newDirectory) throws IOException {
        byte[] bytes = Files.readAllBytes(generationManifest);
        Json generation = NeutralJson.parse(new String(bytes, StandardCharsets.UTF_8));
        Path base = generationManifest.toAbsolutePath().getParent();
        RetainedGeneration.validate(generation, base); RetainedGeneration.turn(generation, executionId);
        Files.createDirectory(newDirectory);
        List<Json> sources = copySources(generation, base, executionId, newDirectory);
        String path = "generation/retained-generation.json"; write(newDirectory.resolve(path), bytes);
        sources.add(source(path, "generation-manifest", bytes, executionId, "retained-generation", true));
        Json manifest = manifest(generation, executionId, sources);
        Path output = newDirectory.resolve("acquisition.json"); write(output, NeutralJson.write(manifest).getBytes(StandardCharsets.UTF_8));
        Path expectation = newDirectory.resolve("assembly-expectation.json");
        write(expectation, NeutralJson.write(ClaudeAcquisition.expectation()).getBytes(StandardCharsets.UTF_8));
        RetainedAcquisition.validate(manifest, newDirectory);
        return new ClaudeAcquisition.Qualified(output, expectation);
    }

    private static List<Json> copySources(Json generation, Path base, String execution, Path directory) throws IOException {
        List<Json> sources = new ArrayList<>();
        for (Json original : generation.member("sources").elements()) {
            String name = text(original, "source"), path = "generation/" + text(original, "path");
            byte[] bytes = Files.readAllBytes(base.resolve(text(original, "path")));
            write(directory.resolve(path), bytes);
            sources.add(source(path, "generation-" + name, bytes, execution, text(original, "origin"), true));
            byte[] projected = RetainedGeneration.projection(generation, execution, name, base);
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

    private static Json manifest(Json generation, String execution, List<Json> sources) {
        String session = text(generation, "nativeSessionId");
        for (Json source : sources) if ("transcript".equals(text(source, "role"))) ((Json.JObject) source).members().put("owner_session_uid", new Json.JString(session));
        Json.JObject end = object("STDIN", extent(sources, "stdin"), "STDOUT", extent(sources, "stream"), "STDERR", 0);
        Json turn = object("invocation_uid", execution, "start_offsets", object("STDIN", 0, "STDOUT", 0, "STDERR", 0), "end_offsets", end);
        Json run = object("run_uid", execution, "invocation_index", 0, "session_uid", session, "capture_start", text(generation, "captureStart"),
                "cli", object("name", "Claude Code", "version", "2.1.316", "vendor_name", "Anthropic"), "launch_settings", object("route", "sdk_stdio"));
        return object("kind", "agent-journal/acquired-execution", "version", "0.1", "profile", ClaudeAcquisition.PROFILE,
                "profile_id", ClaudeAcquisition.PROFILE_ID, "acquisition_uid", execution, "complete", true, "capture_errors", new Json.JArray(List.of()),
                "run", run, "process", object("connection_uid", text(generation, "connectionId"), "observed_resources_terminated", true,
                        "stdout_natural_eof", true, "stderr_natural_eof", true, "basis", "generation observations; projection EOF is not per-turn transport EOF"),
                "turns", new Json.JArray(List.of(turn)), "sources", new Json.JArray(sources), "retained_generation", generation, "retained_execution_id", execution);
    }

    private static long extent(List<Json> sources, String role) { return sources.stream().filter(s -> role.equals(text(s, "role"))).mapToLong(s -> ((Json.JNumber) s.member("bytes")).value().longValueExact()).findFirst().orElseThrow(); }

    private static Json binding(String profile) throws IOException {
        try (var input = RetainedClaudeAcquisition.class.getResourceAsStream("/ocsf/source-meaning-bindings.json")) {
            if (input == null) throw new IOException("source bindings unavailable");
            Json bindings = NeutralJson.parse(new String(input.readAllBytes(), StandardCharsets.UTF_8));
            return bindings.member("bindings").elements().stream().filter(b -> profile.equals(text(b, "profile")) && "2.1.316".equals(text(b, "version"))).findFirst().orElseThrow();
        }
    }

    private static Json.JObject object(Object... pairs) {
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

    private static void write(Path path, byte[] bytes) throws IOException { Files.createDirectories(path.getParent()); Files.write(path, bytes, StandardOpenOption.CREATE_NEW); }
    private static String text(Json value, String key) { return value.member(key).text(); }

    /** Packaged command; refuses invalid input and never invokes a provider. */
    public static void main(String[] args) throws IOException {
        if (args.length != 3) throw new IllegalArgumentException("usage: RetainedClaudeAcquisition <generation-manifest> <execution-id> <new-directory>");
        qualify(Path.of(args[0]), args[1], Path.of(args[2]));
    }
}
