package io.github.markpollack.journal.tck.ocsf.inventory;

import io.github.markpollack.journal.tck.ocsf.CodexRetainedGeneration;
import io.github.markpollack.journal.tck.ocsf.AcquisitionVerifier;
import io.github.markpollack.journal.tck.ocsf.ClaudeOwnerScope;
import io.github.markpollack.journal.tck.ocsf.ExpectedSources;
import io.github.markpollack.journal.tck.ocsf.NativeArtifact;
import io.github.markpollack.journal.tck.ocsf.OcsfPackage;
import io.github.markpollack.journal.tck.ocsf.OracleReport;
import io.github.markpollack.journal.tck.verify.Check;
import io.github.markpollack.journal.tck.verify.Json;
import io.github.markpollack.journal.tck.verify.NeutralJson;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;

/** Packaged independent acquisition verifier. The core CLI discovers this upper layer through its service interface. */
public final class AcquisitionVerification implements AcquisitionVerifier {
    @Override
    public OracleReport verify(OcsfPackage pkg, Path directory, Path acquisition, Path assembly) throws IOException {
        return Inputs.verify(pkg, directory, acquisition, assembly);
    }

    /** Native acquisition state, kept separate from the producer's declarations. */
    private static final class Inputs {
        private final Json manifest;
        private final Path base;
        private final List<Json> expected = new ArrayList<>();
        private final List<AssemblyOracle.Supplied> supplied = new ArrayList<>();
        private boolean complete;

        private Inputs(Path acquisition, Path directory) throws IOException {
            manifest = NeutralJson.readDocument(acquisition);
            require("agent-journal/acquired-execution".equals(text(manifest, "kind")) && "0.1".equals(text(manifest, "version")), "unsupported acquisition format");
            require(manifest.member("complete") instanceof Json.JBool, "explicit acquisition completeness required");
            require(manifest.member("process") instanceof Json.JObject, "explicit process outcome required");
            require(manifest.member("sources") instanceof Json.JArray, "sources must be an array");
            require(manifest.member("run") instanceof Json.JObject, "run descriptor required");
            text(manifest.member("run"), "run_uid");
            text(manifest, "acquisition_uid");
            base = acquisition.toAbsolutePath().getParent().toRealPath();
            require(directory.toRealPath().equals(base), "native directory must be acquisition manifest directory");
            complete = truth(manifest, "complete");
        }

        static OracleReport verify(OcsfPackage pkg, Path directory, Path acquisition, Path assembly) throws IOException {
            Inputs inputs = new Inputs(acquisition, directory);
            if (ClaudeAcquisition.PROFILE_ID.equals(text(inputs.manifest, "profile_id"))) {
                if (inputs.manifest.member("retained_generation") == null) ClaudeAcquisition.validate(inputs.manifest, inputs.base);
                else io.github.markpollack.journal.tck.ocsf.RetainedAcquisition.validate(inputs.manifest, inputs.base, inputs.manifest.member("retained_profile") != null);
            }
            inputs.readSources();
            if (CodexAcquisition.PROFILE_ID.equals(text(inputs.manifest, "profile_id"))) {
                CodexAcquisition.validate(inputs.manifest, inputs.base);
                if (inputs.manifest.member("retained_generation") != null) CodexRetainedAcquisition.validate(inputs.manifest, inputs.base);
            }
            Json spec = NeutralJson.readDocument(assembly);
            if (CodexAcquisition.PROFILE_ID.equals(text(inputs.manifest, "profile_id"))) {
                Json profile = pkg.declarations == null ? null : pkg.declarations.assembly();
                String revision = RawInventory.text(profile, "profile", "version");
                boolean ordinary = inputs.manifest.member("retained_generation") != null && CodexRetainedGeneration.ORDINARY_PROFILE.equals(text(inputs.manifest, "retained_profile"));
                require(Json.sameValue(spec, CodexAcquisition.expectation(revision, pkg.manifest.extensionVersion(), ordinary)), "qualified Codex expectation changed");
            }
            if (ClaudeAcquisition.PROFILE_ID.equals(text(inputs.manifest, "profile_id"))) {
                require(Json.sameValue(spec, ClaudeAcquisition.expectation()), "qualified assembly expectation changed");
            }
            require(text(inputs.manifest, "profile_id").equals(text(spec, "profile_id")), "acquisition/expectation profile mismatch");
            OracleReport report = AssemblyOracle.verifyAll(pkg, inputs.base, inputs.expected(), spec, inputs.supplied);
            inputs.metadata(pkg, report);
            inputs.sourceBindings(report);
            inputs.additionalNative(pkg, report);
            return report;
        }

        private void additionalNative(OcsfPackage pkg, OracleReport report) throws IOException {
            if (ClaudeAcquisition.PROFILE_ID.equals(text(manifest, "profile_id"))) {
                ToolOutputVerification.check(pkg, manifest, base, report);
                spawns(report);
            }
        }

        /** Each split core a Claude stream's owner paths require is backed by the native request of its spawning call. */
        private void spawns(OracleReport report) {
            for (AssemblyOracle.Supplied source : supplied) {
                if (!"stream".equals(source.role())) { continue; }
                for (String owner : ClaudeOwnerScope.splitCores(source.bytes())) {
                    boolean spawned = ClaudeOwnerScope.spawned(source.bytes(), owner);
                    report.add(Check.Category.COMPLETENESS, "acquisition.child_spawn", "child:" + owner, spawned ? Check.Outcome.VERIFIED : Check.Outcome.UNRESOLVED,
                            spawned ? "the stream requests the spawning tool call " + owner
                                    : "records name the owner " + owner + " but no record of " + source.name() + " requests that tool call: a sub-agent spawn is not shown");
                }
            }
        }

        private void readSources() throws IOException {
            Set<String> names = new HashSet<>();
            for (Json source : manifest.member("sources").elements()) {
                require(names.add(text(source, "artifact")), "duplicate acquired artifact");
                require(source.member("required") instanceof Json.JBool, "explicit required flag needed");
                if ("missing".equals(text(source, "state"))) {
                    text(source, "reason");
                    if (truth(source, "required")) { complete = false; }
                }
                else { acquire(source); }
            }
        }

        private void acquire(Json source) throws IOException {
            require("acquired".equals(text(source, "state")), "unknown acquisition source state");
            text(source, "origin");
            text(source, "acquisition");
            String end = text(source, "end");
            require("EOF".equals(end) || "INTERRUPTED".equals(end), "unknown source end");
            if (!"EOF".equals(end)) { complete = false; }
            Path file = checkedFile(source);
            if (truth(source, "archive_only")) { return; }
            String framing = text(source, "framing");
            require("document".equals(framing) || "lines".equals(framing), "unknown source framing");
            NativeArtifact nativeBytes = "document".equals(framing) ? NativeArtifact.document(file) : NativeArtifact.read(file);
            require(source.member("records") instanceof Json.JNumber n && n.value().intValueExact() == nativeBytes.records().size(), "acquired record extent mismatch");
            LinkedHashMap<String, Json> fields = new LinkedHashMap<>(((Json.JObject) source).members());
            fields.put("required", new Json.JBool(true)); // every selected input owes preservation, even an optional role
            expected.add(new Json.JObject(fields));
            supplied.add(new AssemblyOracle.Supplied(text(source, "artifact"), text(source, "role"), text(source, "origin"), nativeBytes,
                    text(source, "acquisition"), RawInventory.text(source, "copy_of")));
        }

        private Path checkedFile(Json source) throws IOException {
            Path relative = Path.of(text(source, "path"));
            require(!relative.isAbsolute(), "source path must be relative");
            Path file = base.resolve(relative).toRealPath();
            require(file.startsWith(base), "source path escapes acquisition directory");
            byte[] bytes = Files.readAllBytes(file);
            require(NativeArtifact.sha256(bytes).equals(text(source, "sha256")), "acquired hash mismatch");
            require(source.member("bytes") instanceof Json.JNumber n && n.value().longValueExact() == bytes.length, "acquired byte extent mismatch");
            return file;
        }

        private ExpectedSources expected() {
            Json.JObject frozen = (Json.JObject) NeutralJson.parse("{\"kind\":\"agent-journal/expected-sources\",\"version\":\"0.1\"}");
            frozen.members().put("run_uid", manifest.path("run", "run_uid"));
            frozen.members().put("complete", new Json.JBool(complete));
            frozen.members().put("sources", new Json.JArray(expected));
            if (manifest.member("children") != null) { frozen.members().put("children", manifest.member("children")); }
            else if (ClaudeAcquisition.PROFILE_ID.equals(text(manifest, "profile_id"))) { frozen.members().put("children", splitCores()); }
            return ExpectedSources.parse(frozen);
        }

        /** A sealed Claude acquisition names no split cores; the acquired stream's own owner paths require them. */
        private Json splitCores() {
            List<Json> children = new ArrayList<>();
            for (AssemblyOracle.Supplied source : supplied) {
                if (!"stream".equals(source.role())) { continue; }
                for (String owner : ClaudeOwnerScope.splitCores(source.bytes())) {
                    children.add(new Json.JObject(new LinkedHashMap<>(java.util.Map.of("spawn_tool_call_uid", new Json.JString(owner),
                            "artifact", new Json.JString(source.name()), "owner_path", new Json.JString(ClaudeOwnerScope.OWNER_PATH)))));
                }
            }
            return new Json.JArray(children);
        }

        private void sourceBindings(OracleReport report) {
            for (Json source : expected) {
                if ("sidecar".equals(text(source, "role")) && "opaque_text".equals(text(source, "profile"))) {
                    report.add(Check.Category.COMPLETENESS, "acquisition.source_binding", text(source, "artifact"), Check.Outcome.NOT_APPLICABLE,
                            "opaque_text retains a whole document without version normalization; separate native tool-output binding, bytes and association checks are mandatory");
                    continue;
                }
                Json binding = source.member("binding");
                Check.Outcome outcome = Check.Outcome.UNRESOLVED;
                if (binding instanceof Json.JObject) {
                    outcome = Json.sameValue(source.member("route"), binding.member("route"))
                            ? Check.Outcome.VERIFIED : Check.Outcome.DEFECTIVE;
                }
                report.add(Check.Category.COMPLETENESS, "acquisition.source_binding", text(source, "artifact"), outcome,
                        "selected source needs its exact frozen meaning binding and matching route; the source oracle compares revision/hash and selection");
            }
        }

        private void metadata(OcsfPackage pkg, OracleReport report) {
            Json recording = pkg.declarations == null ? null : pkg.declarations.recording();
            Json process = recording == null ? null : recording.member("process");
            report.add(Check.Category.COMPLETENESS, "acquisition.process", "acquisition",
                    Json.sameValue(manifest.member("process"), process) ? Check.Outcome.VERIFIED : Check.Outcome.DEFECTIVE,
                    "recorded process outcome compared with frozen caller acquisition (not domain task success)");
            Json recorded = pkg.declarations == null ? null : pkg.declarations.run().path("launch_settings", "acquired_execution");
            report.add(Check.Category.COMPLETENESS, "acquisition.manifest", "acquisition",
                    Json.sameValue(manifest, recorded) ? Check.Outcome.VERIFIED : Check.Outcome.DEFECTIVE,
                    "caller acquisition metadata compared with the final file's declared copy");
        }

        private static String text(Json json, String key) {
            String value = RawInventory.text(json, key);
            require(value != null && !value.isBlank(), "missing " + key);
            return value;
        }
        private static boolean truth(Json json, String key) { return json.member(key) instanceof Json.JBool b && b.value(); }
        private static void require(boolean pass, String reason) { if (!pass) { throw new IllegalArgumentException(reason); } }
    }
}
