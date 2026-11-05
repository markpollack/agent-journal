package io.github.markpollack.journal.tck.ocsf.inventory;

import io.github.markpollack.journal.tck.ocsf.NativeArtifact;
import io.github.markpollack.journal.tck.ocsf.CodexAcquisitionReading;
import io.github.markpollack.journal.tck.verify.Json;
import io.github.markpollack.journal.tck.verify.NeutralJson;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Map;

/** Independent qualification of original bytes from one successful fresh Codex 0.166.0 exec turn.
 * Qualification does not establish semantic archive acceptance or native/provider qualification.
 */
public final class CodexAcquisition {
    public static final String PROFILE = "codex-cli-0.166.0-exec-stdio-rollout";
    public static final String PROFILE_ID = "codex-cli/0.166.0/exec-stdio+rollout";
    private CodexAcquisition() { }

    /** Separate immutable derivative inputs for final assembly and the independent packaged reader. */
    public record Qualified(Path manifest, Path assemblyExpectation) { }

    /** Validates before creating the new directory; copies originals without modifying caller files. */
    public static Qualified qualify(Path sealedManifest, Path newDirectory) throws IOException {
        byte[] original = Files.readAllBytes(sealedManifest);
        Json m = NeutralJson.parse(new String(original, StandardCharsets.UTF_8));
        Map<String, CodexAcquisitionReading.Source> sources = CodexAcquisitionReading.read(m, sealedManifest.toAbsolutePath().getParent());
        for (CodexAcquisitionReading.Source source : sources.values()) bind(source.declaration());
        ((Json.JObject) m).members().put("qualification", new Json.JObject(Map.of(
                "original_manifest_sha256", new Json.JString(NativeArtifact.sha256(original)),
                "basis", new Json.JString("independent original-byte identity, fresh turn, closure and usage witnesses; semantic acceptance is separate"))));
        Files.createDirectory(newDirectory);
        for (CodexAcquisitionReading.Source source : sources.values()) {
            Path output = newDirectory.resolve(source.relative()); Files.createDirectories(output.getParent()); write(output, source.bytes());
        }
        write(newDirectory.resolve("client-acquisition.json"), original);
        Path manifest = newDirectory.resolve("acquisition.json"), expectation = newDirectory.resolve("assembly-expectation.json");
        write(manifest, NeutralJson.write(m).getBytes(StandardCharsets.UTF_8));
        write(expectation, NeutralJson.write(expectation()).getBytes(StandardCharsets.UTF_8));
        return new Qualified(manifest, expectation);
    }

    /** Read-only original-byte checks, also used by the independent packaged terminal reader. */
    public static void validate(Path manifest) throws IOException {
        validate(NeutralJson.readDocument(manifest), manifest.toAbsolutePath().getParent());
    }

    static void validate(Json manifest, Path base) throws IOException { CodexAcquisitionReading.read(manifest, base); }
    static Json expectation() throws IOException { return resource("/ocsf/qualification/codex-cli-0.166.0.json"); }
    /** One retained turn read through its single-turn projection: the turn's own final usage comes from the rollout token_usage_record,
     * because codex-cli 0.166.0 turn.completed carries the thread's cumulative usage on a resumed turn (native probe 2026-11-04). */
    static Json retainedExpectation() throws IOException { return resource("/ocsf/qualification/codex-cli-0.166.0-retained.json"); }

    /** Exact historical text and current singleton/retained profiles remain separate verification contracts. */
    static Json expectation(String profileVersion, String extensionVersion, boolean retained) throws IOException {
        if ("0.2.0-prototype".equals(profileVersion) && "0.16.0-prototype".equals(extensionVersion)) return retained ? retainedExpectation() : expectation();
        if (!retained && "0.1.0-prototype".equals(profileVersion) && "0.15.0-prototype".equals(extensionVersion)) {
            return resource("/ocsf/qualification/codex-cli-0.166.0-profile-0.1.json");
        }
        throw new IllegalArgumentException("unsupported Codex profile/schema combination");
    }

    private static void bind(Json source) throws IOException {
        if (source.member("archive_only") instanceof Json.JBool b && b.value()) return;
        for (Json binding : resource("/ocsf/source-meaning-bindings.json").member("bindings").elements()) {
            if (Json.sameValue(source.member("profile"), binding.member("profile")) && "0.166.0".equals(binding.member("version").text())) {
                ((Json.JObject) source).members().put("binding", binding); return;
            }
        }
        throw new IllegalArgumentException("no exact Codex source meaning binding");
    }

    private static Json resource(String name) throws IOException {
        try (InputStream in = CodexAcquisition.class.getResourceAsStream(name)) {
            if (in == null) throw new IOException("missing resource " + name);
            return NeutralJson.parse(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
    }
    private static void write(Path file, byte[] bytes) throws IOException { Files.write(file, bytes, StandardOpenOption.CREATE_NEW); }

    /** Qualification command: no process launch or network work. */
    public static void main(String[] args) throws IOException {
        if (args.length != 2) throw new IllegalArgumentException("usage: CodexAcquisition <sealed-manifest> <new-directory>");
        qualify(Path.of(args[0]), Path.of(args[1]));
    }
}
