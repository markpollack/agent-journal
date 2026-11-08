package io.github.markpollack.journal.tck.ocsf.inventory;

import io.github.markpollack.journal.tck.ocsf.CodexRetainedGeneration;
import io.github.markpollack.journal.tck.ocsf.CodexRetainedReading;
import io.github.markpollack.journal.tck.ocsf.NativeArtifact;
import io.github.markpollack.journal.tck.verify.Json;
import io.github.markpollack.journal.tck.verify.NeutralJson;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Opt-in qualifier for one turn of a sealed Codex retained thread ({@value CodexRetainedGeneration#KIND}) under an
 * explicitly named terminal profile: the frozen two-turn {@value CodexRetainedGeneration#PROFILE} or the ordinary
 * {@value CodexRetainedGeneration#ORDINARY_PROFILE}. It validates the whole thread, then writes a
 * single-turn rollout projection (shared {@code session_meta} plus exactly this turn's region) beside the
 * turn's own process originals so the existing Codex assembly reads one turn with its original decision.
 * Cumulative native usage is preserved on the turn and never summed again. Deterministic qualification of
 * a labelled synthetic thread is fixture evidence only; native/provider admission is separate.
 */
public final class CodexRetainedAcquisition {
    private CodexRetainedAcquisition() { }

    /** The original signature: qualifies under the frozen profile {@value CodexRetainedGeneration#PROFILE}, its default before the ordinary profile existed. */
    public static AcquisitionQualifier.Qualified qualify(Path threadManifest, String executionId, Path newDirectory) throws IOException {
        return qualify(threadManifest, executionId, newDirectory, CodexRetainedGeneration.PROFILE);
    }

    /** Copies originals and the exact projection to a new directory, leaving every input unchanged. */
    public static AcquisitionQualifier.Qualified qualify(Path threadManifest, String executionId, Path newDirectory, String profile) throws IOException {
        byte[] bytes = Files.readAllBytes(threadManifest);
        Json manifest = NeutralJson.parse(new String(bytes, StandardCharsets.UTF_8));
        CodexRetainedGeneration.RetainedThread thread = CodexRetainedGeneration.validate(manifest, threadManifest.toAbsolutePath().getParent(), profile);
        CodexRetainedGeneration.Turn turn = thread.turn(executionId);
        Files.createDirectory(newDirectory);
        Json relativized = relativize(manifest); byte[] declaration = NeutralJson.write(relativized).getBytes(StandardCharsets.UTF_8);
        List<Json> sources = copyGeneration(newDirectory, thread, executionId, bytes, declaration);
        sources.addAll(copyTurn(newDirectory, thread, turn, executionId));
        Json.JObject acquisition = acquisition(turn, relativized, sources, bytes, Files.readAllBytes(turn.perTurnManifestPath()), profile);
        Path output = newDirectory.resolve("acquisition.json"); write(output, NeutralJson.write(acquisition).getBytes(StandardCharsets.UTF_8));
        // The frozen /1 profile pins the one-shot expectation it always pinned (stream-sourced fresh-turn totals, defective for a native
        // thread total); the ordinary /2 profile pins the retained expectation (own final from token_usage_record).
        Json spec = CodexRetainedGeneration.PROFILE.equals(profile) ? CodexAcquisition.expectation() : CodexAcquisition.retainedExpectation();
        Path expectation = newDirectory.resolve("assembly-expectation.json"); write(expectation, NeutralJson.write(spec).getBytes(StandardCharsets.UTF_8));
        validate(acquisition, newDirectory);
        return new AcquisitionQualifier.Qualified(output, expectation);
    }

    /** Byte-exact thread manifest, its relativized declaration, the final transcript and every turn's originals, all archive-only. */
    private static List<Json> copyGeneration(Path dir, CodexRetainedGeneration.RetainedThread thread, String execution, byte[] original, byte[] declaration) throws IOException {
        List<Json> sources = new ArrayList<>(); String origin = text(thread.manifest(), "native_path");
        write(dir, "generation/codex-thread.original.json", original); sources.add(archive("generation/codex-thread.original.json", "generation-codex-thread.original.json", original, execution, "codex-thread"));
        write(dir, "generation/codex-thread.json", declaration); sources.add(archive("generation/codex-thread.json", "generation-codex-thread.json", declaration, execution, "codex-thread#relativized"));
        write(dir, "generation/transcript.bin", thread.transcript()); sources.add(archive("generation/transcript.bin", "generation-transcript.bin", thread.transcript(), execution, origin));
        for (CodexRetainedGeneration.Turn each : thread.turns()) {
            Path from = each.perTurnManifestPath().getParent(); String prefix = "generation/turn-" + each.index() + "/";
            for (String name : List.of("codex-acquisition.json", "stdin.bin", "stdout.bin", "stderr.bin", "native.bin")) {
                byte[] copy = Files.readAllBytes(from.resolve(name)); write(dir, prefix + name, copy);
                sources.add(archive(prefix + name, "generation-turn-" + each.index() + "-" + name, copy, execution, name.equals("native.bin") ? origin + "#prefix" : "codex-per-turn"));
            }
        }
        return sources;
    }

    /** This turn's own process originals plus the single-turn rollout projection, in the roles the Codex assembly reads. */
    private static List<Json> copyTurn(Path dir, CodexRetainedGeneration.RetainedThread thread, CodexRetainedGeneration.Turn turn, String execution) throws IOException {
        List<Json> sources = new ArrayList<>(); String origin = text(thread.manifest(), "native_path");
        for (var e : Map.of("STDIN", "turn/stdin.bin", "STDERR", "turn/stderr.bin").entrySet()) {
            byte[] bytes = turn.originals().get(e.getKey()); write(dir, e.getValue(), bytes);
            sources.add(archive(e.getValue(), e.getKey().toLowerCase(java.util.Locale.ROOT), bytes, execution, "codex-" + e.getKey().toLowerCase(java.util.Locale.ROOT)));
        }
        byte[] stdout = turn.originals().get("STDOUT"); write(dir, "turn/stdout.jsonl", stdout);
        sources.add(selected(archive("turn/stdout.jsonl", "stream", stdout, execution, "codex-stdout"), stdout, "codex_stream", "stdout", null));
        byte[] projection = thread.projection(turn); write(dir, "turn/transcript.jsonl", projection);
        sources.add(selected(archive("turn/transcript.jsonl", "transcript", projection, execution, origin + "#turn=" + turn.index()), projection, "codex_rollout", "native_session_file", thread.session()));
        return sources;
    }

    /** Read-only check of a qualified turn, shared with the independent terminal reader. */
    public static void validate(Json acquisition, Path base) throws IOException { CodexRetainedReading.validate(acquisition, base); }

    /** Per-turn references become {@code turn-<i>/<file>} under the copied generation; every other value is unchanged. */
    private static Json relativize(Json manifest) {
        Json.JObject copy = (Json.JObject) NeutralJson.parse(NeutralJson.write(manifest));
        for (Json t : array(copy, "turns")) {
            int i = Integer.parseInt(t.member("invocation_index").text());
            for (Json s : array(t, "sources")) ((Json.JObject) s).members().put("path", new Json.JString("turn-" + i + "/" + Path.of(text(s, "path")).getFileName()));
            ((Json.JObject) t).members().put("per_turn_directory", new Json.JString("turn-" + i));
            ((Json.JObject) t).members().put("per_turn_manifest", new Json.JString("turn-" + i + "/codex-acquisition.json"));
        }
        return copy;
    }

    private static Json.JObject acquisition(CodexRetainedGeneration.Turn turn, Json thread, List<Json> sources, byte[] threadBytes, byte[] perTurnBytes, String profile) {
        Json.JObject m = (Json.JObject) NeutralJson.parse(NeutralJson.write(turn.perTurnManifest()));
        Json.JObject t = (Json.JObject) array(m, "turns").get(0);
        for (String key : CodexRetainedReading.COPIED) t.members().put(key, turn.declaration().member(key));
        m.members().put("sources", new Json.JArray(sources));
        m.members().put("retained_generation", thread); m.members().put("retained_profile", new Json.JString(profile));
        m.members().put("retained_execution_id", new Json.JString(turn.executionId()));
        m.members().put("wire_usage_scope", new Json.JString(CodexRetainedGeneration.scope(turn.declaration())));
        m.members().put("qualification", object("original_thread_manifest_sha256", NativeArtifact.sha256(threadBytes), "per_turn_manifest_sha256", NativeArtifact.sha256(perTurnBytes),
                "declaration", "generation/codex-thread.json is the original with per-turn references relativized; generation/codex-thread.original.json is byte-exact",
                "basis", "independent thread partition, prefix continuity, emitted identity, region membership and cumulative usage arithmetic; semantic acceptance is separate"));
        return m;
    }

    private static Json archive(String path, String role, byte[] bytes, String execution, String origin) {
        return object("artifact", path, "path", path, "role", role, "origin", origin, "required", true, "archive_only", true, "state", "acquired",
                "bytes", bytes.length, "sha256", NativeArtifact.sha256(bytes), "observed", bytes.length, "dropped", 0, "end", "EOF", "acquisition", execution);
    }

    private static Json selected(Json archive, byte[] bytes, String profile, String route, String owner) throws IOException {
        long lines = 0; for (byte b : bytes) if (b == '\n') lines++;
        Json.JObject source = (Json.JObject) archive;
        source.members().put("archive_only", new Json.JBool(false));
        source.members().putAll(object("profile", profile, "route", route, "framing", "lines", "records", lines).members());
        if (owner != null) source.members().put("owner_session_uid", new Json.JString(owner));
        source.members().put("binding", binding(profile)); return source;
    }

    private static Json binding(String profile) throws IOException {
        try (var in = CodexRetainedAcquisition.class.getResourceAsStream("/ocsf/source-meaning-bindings.json")) {
            if (in == null) throw new IOException("source bindings unavailable");
            Json bindings = NeutralJson.parse(new String(in.readAllBytes(), StandardCharsets.UTF_8));
            return bindings.member("bindings").elements().stream().filter(b -> profile.equals(b.member("profile").text()) && b.member("version") == null).findFirst().orElseThrow();
        }
    }

    private static Json.JObject object(Object... pairs) { return RetainedClaudeAcquisition.object(pairs); }

    private static void write(Path dir, String path, byte[] bytes) throws IOException { write(dir.resolve(path), bytes); }
    private static void write(Path path, byte[] bytes) throws IOException { RetainedClaudeAcquisition.write(path, bytes); }
    private static String text(Json v, String key) { return v.member(key).text(); }
    private static List<Json> array(Json v, String key) { return ((Json.JArray) v.member(key)).elements(); }

    /** Packaged command; refuses invalid input before creating output and never invokes a provider. */
    public static void main(String[] args) throws IOException {
        if (args.length != 3 && args.length != 4) throw new IllegalArgumentException("usage: CodexRetainedAcquisition <codex-thread.json> <execution-id> <new-directory> [<retained-profile>, default " + CodexRetainedGeneration.PROFILE + "]");
        qualify(Path.of(args[0]), args[1], Path.of(args[2]), args.length == 4 ? args[3] : CodexRetainedGeneration.PROFILE);
    }
}
