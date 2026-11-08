package io.github.markpollack.journal.tck.ocsf.inventory;

import io.github.markpollack.journal.tck.ocsf.CodexExecFamily;

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

/** Independent qualification of original bytes from one successful fresh Codex exec turn, at any CLI version ({@link CodexExecFamily}).
 * The manifest's CLI version names its profile and profile id, and the native session_meta must state the same version.
 * Qualification does not establish semantic archive acceptance or native/provider qualification.
 */
public final class CodexAcquisition {
    private CodexAcquisition() { }

    /**
     * The qualifier for one version's acquisition profile, delivered under {@link CodexExecFamily#TERMINAL_PROFILE}; it runs
     * {@link #qualify(Path, Path)} and refuses a manifest declaring any other profile.
     */
    public static AcquisitionQualifier qualifier(String version) {
        return new ProfileQualifier(CodexExecFamily.profile(version), CodexExecFamily.TERMINAL_PROFILE, (manifest, directory) -> {
            if (!CodexExecFamily.profile(version).equals(RawInventory.text(NeutralJson.parse(Files.readString(manifest)), "profile"))) {
                throw new IllegalArgumentException("unqualified profile");
            }
            return qualify(manifest, directory);
        });
    }

    /** {@code expectation} stated under the profile id of CLI {@code version}: one set of native locators for the whole family. */
    static Json stated(Json expectation, String version) {
        ((Json.JObject) expectation).members().put("profile_id", new Json.JString(CodexExecFamily.profileId(version)));
        return expectation;
    }

    /** Validates before creating the new directory; copies originals without modifying caller files. */
    public static AcquisitionQualifier.Qualified qualify(Path sealedManifest, Path newDirectory) throws IOException {
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
        write(expectation, NeutralJson.write(stated(expectation(), RawInventory.text(m.path("run", "cli"), "version"))).getBytes(StandardCharsets.UTF_8));
        return new AcquisitionQualifier.Qualified(manifest, expectation);
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

    /** Exact historical text and current singleton/retained profiles remain separate verification contracts; {@code ordinaryRetained}
     * selects the retained expectation of the /2 profile, while the one-shot and the frozen retained /1 profile keep the original. */
    static Json expectation(String profileVersion, String extensionVersion, boolean ordinaryRetained) throws IOException {
        if ("0.2.0-prototype".equals(profileVersion) && "0.16.0-prototype".equals(extensionVersion)) return ordinaryRetained ? retainedExpectation() : expectation();
        if (!ordinaryRetained && "0.1.0-prototype".equals(profileVersion) && "0.15.0-prototype".equals(extensionVersion)) {
            return resource("/ocsf/qualification/codex-cli-0.166.0-profile-0.1.json");
        }
        throw new IllegalArgumentException("unsupported Codex profile/schema combination");
    }

    private static void bind(Json source) throws IOException {
        if (source.member("archive_only") instanceof Json.JBool b && b.value()) return;
        for (Json binding : resource("/ocsf/source-meaning-bindings.json").member("bindings").elements()) {
            // the version-neutral binding of the profile (no version): the version is recorded, not gated
            if (Json.sameValue(source.member("profile"), binding.member("profile")) && binding.member("version") == null) {
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
