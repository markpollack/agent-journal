package io.github.markpollack.journal.tck.ocsf.inventory;

import io.github.markpollack.journal.tck.ocsf.JunieAcpFamily;
import io.github.markpollack.journal.tck.ocsf.JunieAcquisitionReading;
import io.github.markpollack.journal.tck.ocsf.NativeArtifact;
import io.github.markpollack.journal.tck.verify.Json;
import io.github.markpollack.journal.tck.verify.NeutralJson;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * Independent qualification of the original bytes of one fresh synchronous Junie ACP session ({@link JunieAcpFamily},
 * docs/junie-acquisition.md): the session directory's {@code summary.json} and {@code events.jsonl}, bound to the ACP
 * session by the sealed manifest. A version qualifies only when a source meaning binding exists for both sources at
 * that exact version; any other version is refused. Qualification reads and copies files only, and is not semantic
 * acceptance of every meaning.
 */
public final class JunieAcquisition {
    private JunieAcquisition() { }

    /**
     * The qualifier for one version's acquisition profile, delivered under {@link JunieAcpFamily#TERMINAL_PROFILE}; it runs
     * {@link #qualify(Path, Path)}, refuses a manifest declaring any other profile, and refuses a version without bindings.
     */
    public static AcquisitionQualifier qualifier(String version) {
        return new ProfileQualifier(JunieAcpFamily.profile(version), JunieAcpFamily.TERMINAL_PROFILE, (manifest, directory) -> {
            String declared = RawInventory.text(NeutralJson.parse(Files.readString(manifest)), "profile");
            if (!JunieAcpFamily.profile(version).equals(declared)) {
                throw new IllegalArgumentException("unqualified profile " + declared + ": this qualifier qualifies " + JunieAcpFamily.profile(version));
            }
            return qualify(manifest, directory);
        });
    }

    /** Validates everything before creating the new directory; copies originals without modifying the caller's files. */
    public static AcquisitionQualifier.Qualified qualify(Path sealedManifest, Path newDirectory) throws IOException {
        byte[] original = Files.readAllBytes(sealedManifest);
        Json m = NeutralJson.parse(new String(original, StandardCharsets.UTF_8));
        Json spec = bound(m);
        Map<String, JunieAcquisitionReading.Source> sources = JunieAcquisitionReading.read(m, sealedManifest.toAbsolutePath().getParent());
        String version = JunieAcquisitionReading.version(m);
        for (JunieAcquisitionReading.Source source : sources.values()) {
            ((Json.JObject) source.declaration()).members().put("binding", binding(RawInventory.text(source.declaration(), "profile"), version));
        }
        // in a fixed member order, so one sealed manifest gives one qualified manifest in every JVM
        ((Json.JObject) m).members().put("qualification", RetainedClaudeAcquisition.object(
                "original_manifest_sha256", new Json.JString(NativeArtifact.sha256(original)),
                "basis", new Json.JString("independent original-byte identity, ACP-to-native session binding, completeness and usage checks; semantic acceptance is separate")));
        Files.createDirectory(newDirectory);
        for (JunieAcquisitionReading.Source source : sources.values()) {
            RetainedClaudeAcquisition.write(newDirectory.resolve(source.relative()), source.bytes());
        }
        RetainedClaudeAcquisition.write(newDirectory.resolve("client-acquisition.json"), original);
        Path manifest = newDirectory.resolve("acquisition.json"), expectation = newDirectory.resolve("assembly-expectation.json");
        RetainedClaudeAcquisition.write(manifest, NeutralJson.write(m).getBytes(StandardCharsets.UTF_8));
        RetainedClaudeAcquisition.write(expectation, NeutralJson.write(spec).getBytes(StandardCharsets.UTF_8));
        return new AcquisitionQualifier.Qualified(manifest, expectation);
    }

    /** Read-only checks of a qualified manifest and its originals, also used by the packaged acquisition verifier. */
    public static void validate(Path manifest) throws IOException {
        validate(NeutralJson.readDocument(manifest), manifest.toAbsolutePath().getParent());
    }

    /** {@link #validate(Path)} of a parsed manifest; each source must carry the exact binding of its profile at the version. */
    static void validate(Json manifest, Path base) throws IOException {
        bound(manifest);
        Map<String, JunieAcquisitionReading.Source> sources = JunieAcquisitionReading.read(manifest, base);
        String version = JunieAcquisitionReading.version(manifest);
        for (JunieAcquisitionReading.Source source : sources.values()) {
            if (!Json.sameValue(binding(RawInventory.text(source.declaration(), "profile"), version), source.declaration().member("binding"))) {
                throw new IllegalArgumentException("source " + RawInventory.text(source.declaration(), "role") + " does not carry its exact meaning binding");
            }
        }
    }

    /** The version's assembly expectation once both sources have bindings at it; refuses a version without them. */
    private static Json bound(Json manifest) throws IOException {
        String version = JunieAcquisitionReading.version(manifest);
        binding(JunieAcpFamily.SUMMARY_PROFILE, version);
        binding(JunieAcpFamily.SESSION_PROFILE, version);
        return expectation(version);
    }

    /** The assembly expectation of {@code version}, stated under its profile id; refused when the TCK has none for it. */
    static Json expectation(String version) throws IOException {
        Json expectation = resource("/ocsf/qualification/junie-" + version + "-acp-session-files.json");
        ((Json.JObject) expectation).members().put("profile_id", new Json.JString(JunieAcpFamily.profileId(version)));
        return expectation;
    }

    /** The exact source meaning binding of {@code profile} at Junie {@code version}; never the nearest version. */
    static Json binding(String profile, String version) throws IOException {
        for (Json binding : resource("/ocsf/source-meaning-bindings.json").member("bindings").elements()) {
            if (profile.equals(RawInventory.text(binding, "profile")) && version.equals(RawInventory.text(binding, "version"))) {
                return binding;
            }
        }
        throw new IllegalArgumentException("unknown version: no source meaning binding for " + profile + " at Junie " + version + "; the version is refused, not mapped by guess");
    }

    private static Json resource(String name) throws IOException {
        try (InputStream in = JunieAcquisition.class.getResourceAsStream(name)) {
            if (in == null) {
                throw new IOException("missing resource " + name);
            }
            return NeutralJson.parse(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    /** Qualification command: no process launch, model call or network work. */
    public static void main(String[] args) throws IOException {
        if (args.length != 2) {
            throw new IllegalArgumentException("usage: JunieAcquisition <sealed-manifest> <new-directory>");
        }
        qualify(Path.of(args[0]), Path.of(args[1]));
    }
}
