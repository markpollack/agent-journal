package io.github.markpollack.journal.ocsf.writer;

import io.github.markpollack.journal.tck.ocsf.OcsfReaderTool;
import io.github.markpollack.journal.tck.ocsf.PackageReader;
import io.github.markpollack.journal.tck.ocsf.inventory.AcquisitionVerification;
import io.github.markpollack.journal.tck.ocsf.inventory.CodexAcquisition;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Labelled synthetic public structures: the native inventory precedes candidate output. */
class CodexOnboardingTest {
    @TempDir Path work;
    private Path fixture() { return Path.of("../journal-capture-tck/src/test/resources/ocsf/syn-codex-onboarding-0.166.0"); }
    private Path assemble() throws Exception {
        var qualified = CodexAcquisition.qualify(fixture().resolve("codex-acquisition.json"), work.resolve("qualified"));
        Path out = work.resolve("archive");
        FinalAssembly.assemble(qualified.manifest(), out);
        return out.resolve("events.jsonl");
    }
    private List<Jv> events(Path file) throws Exception {
        return Files.readAllLines(file).stream().map(Jv::parse).filter(e -> e.member("class_uid") != null).toList();
    }
    @Test void combinedOutputAndDistinctEqualOperationsArePreservedWithoutInventingStreams() throws Exception {
        var operations = events(assemble()).stream().map(e -> e.member("tool_operation")).filter(java.util.Objects::nonNull).toList();
        assertThat(operations).hasSize(4);
        assertThat(operations.stream().map(o -> o.member("uid").text()).distinct()).hasSize(4);
        for (Jv operation : operations) {
            assertThat(operation.member("combined_output")).isEqualTo(new Jv.Str("same output\n"));
            assertThat(operation.member("stdout")).isNull();
            assertThat(operation.member("stderr")).isNull();
        }
    }
    @Test void knownNativeMeaningsAndRepeatedSnapshotsPassIndependentReader() throws Exception {
        Path file = assemble();
        var report = new AcquisitionVerification().verify(PackageReader.open(file), work.resolve("qualified"),
                work.resolve("qualified/acquisition.json"), work.resolve("qualified/assembly-expectation.json"));
        assertThat(report.defects()).isEmpty();
        assertThat(OcsfReaderTool.acceptance(report).exit()).as(OcsfReaderTool.acceptance(report).toString()).isZero();
    }
    @Test void originalAcquisitionCannotBeBypassedByThePublicAssemblyApi() throws Exception {
        Jv run = Jv.parse(Files.readString(fixture().resolve("codex-acquisition.json"))).member("run");
        assertThatThrownBy(() -> FinalAssembly.assemble(work.resolve("bypass"), RunDescriptor.fromSpec(run),
                AssemblyProfile.bundled("codex-cli-0.166.0-exec-stdio-rollout"), List.of(), new Jv.Arr(), WriterOptions.defaults()))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("original validated acquisition");
        assertThat(work.resolve("bypass")).doesNotExist();
    }
    @Test void currentArchiveCannotSubstituteHistoricalExpectationOrProfileRevision() throws Exception {
        var pkg = PackageReader.open(assemble());
        Path historical = work.resolve("historical.json");
        try (var in = getClass().getResourceAsStream("/ocsf/qualification/codex-cli-0.166.0-profile-0.1.json")) {
            Files.copy(in, historical);
        }
        assertThatThrownBy(() -> new AcquisitionVerification().verify(pkg, work.resolve("qualified"),
                work.resolve("qualified/acquisition.json"), historical)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("qualified Codex expectation changed");
        var profile = (io.github.markpollack.journal.tck.verify.Json.JObject) pkg.declarations.assembly().member("profile");
        profile.members().put("version", new io.github.markpollack.journal.tck.verify.Json.JString("0.1.0-prototype"));
        assertThatThrownBy(() -> new AcquisitionVerification().verify(pkg, work.resolve("qualified"),
                work.resolve("qualified/acquisition.json"), historical)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("profile/schema combination");
    }
    @Test void missingDeclaredOriginalAcquisitionRefusesWithoutNullDereference() throws Exception {
        var pkg = PackageReader.open(assemble());
        var settings = (io.github.markpollack.journal.tck.verify.Json.JObject) pkg.declarations.run().member("launch_settings");
        assertThat(settings.members().remove("acquired_execution")).isNotNull();
        assertThatThrownBy(() -> new AcquisitionVerification().verify(pkg, work.resolve("qualified"),
                work.resolve("qualified/acquisition.json"), work.resolve("qualified/assembly-expectation.json")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("original acquisition manifest");
    }
    @Test void omittedOutputSourceIdentityAndDuplicateOrDroppedAccountingAreDefective() throws Exception {
        Path file = assemble(); String original = Files.readString(file);
        for (String mutation : List.of("output", "identity", "duplicate-final", "drop-snapshot", "extra-wire")) {
            var lines = original.lines().map(Jv::parse).toList();
            for (Jv value : lines) {
                if (!(value instanceof Jv.Obj e) || e.member("class_uid") == null) continue;
                if (mutation.equals("output") && e.member("tool_operation") instanceof Jv.Obj operation) operation.members().remove("combined_output");
                if (mutation.equals("identity") && "additional_tools".equals(MapperSupport.text(e, "context_kind"))) {
                    ((Jv.Obj) e.member("source_ref")).members().remove("native_uid");
                    ((Jv.Obj) e.member("metadata")).members().remove("original_event_uid");
                }
                if (!(e.path("assembly", "assembly_role_id") instanceof Jv.Num role) || role.value().intValueExact() != 3) continue;
                if (!(e.member("usage_observations") instanceof Jv.Arr observations)) continue;
                var changed = new java.util.ArrayList<>(observations.elements());
                if (mutation.equals("duplicate-final")) changed.add(Jv.copy(changed.get(2)));
                if (mutation.equals("drop-snapshot")) changed.remove(0);
                if (mutation.equals("extra-wire")) {
                    Jv wire = lines.stream().filter(x -> x.member("usage_observations") != null && "stdout.jsonl".equals(MapperSupport.text(x, "source_ref", "artifact"))).findFirst().orElseThrow();
                    changed.add(Jv.copy(wire.member("usage_observations").elements().get(0)));
                }
                e.put("usage_observations", new Jv.Arr(changed));
            }
            Files.writeString(file, lines.stream().map(Jv::write).collect(java.util.stream.Collectors.joining("\n", "", "\n")));
            var report = new AcquisitionVerification().verify(PackageReader.open(file), work.resolve("qualified"),
                    work.resolve("qualified/acquisition.json"), work.resolve("qualified/assembly-expectation.json"));
            assertThat(OcsfReaderTool.acceptance(report).exit()).as(mutation + ": " + OcsfReaderTool.acceptance(report)).isEqualTo(3);
            String expected = switch (mutation) {
                case "output" -> "combined_output";
                case "identity" -> "native_uid";
                case "duplicate-final" -> "assembly.accounting.distinct";
                case "drop-snapshot" -> "fact.final.value";
                case "extra-wire" -> "assembly.selection.extra";
                default -> throw new IllegalArgumentException(mutation);
            };
            assertThat(report.defects()).as(mutation + " must fail its independent semantic check")
                    .anyMatch(c -> c.toString().contains(expected));
        }
        Files.writeString(file, original);
    }
}
