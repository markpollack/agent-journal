package io.github.markpollack.journal.ocsf.writer;

import io.github.markpollack.journal.tck.ocsf.Event;
import io.github.markpollack.journal.tck.ocsf.NativeArtifact;
import io.github.markpollack.journal.tck.ocsf.OcsfPackage;
import io.github.markpollack.journal.tck.ocsf.OracleReport;
import io.github.markpollack.journal.tck.ocsf.PackageReader;
import io.github.markpollack.journal.tck.ocsf.inventory.AssemblyOracle;
import io.github.markpollack.journal.tck.verify.Check;
import io.github.markpollack.journal.tck.verify.Json;
import io.github.markpollack.journal.tck.verify.NeutralJson;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static io.github.markpollack.journal.ocsf.writer.WriterTestSupport.FIXTURES;
import static io.github.markpollack.journal.ocsf.writer.WriterTestSupport.LIVE_JUNIE_TERMINAL;
import static io.github.markpollack.journal.ocsf.writer.WriterTestSupport.LIVE_JUNIE_VIEW;
import static io.github.markpollack.journal.ocsf.writer.WriterTestSupport.nativeOf;
import static io.github.markpollack.journal.ocsf.writer.WriterTestSupport.options;
import static io.github.markpollack.journal.ocsf.writer.WriterTestSupport.run;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Final Level 1 assembly of Junie 3813.7's session directory (summary.json and events.jsonl) as the primary input, with
 * standard output and standard error preserved, by the same engine, under the experimental session-primary profile;
 * judged by the independent {@link AssemblyOracle} on both sanitized live runs. The session events carry no session id:
 * the summary of the same directory identifies them. Standard output's steps carry no id and are never joined or
 * counted as operations. A usage entry's cost is a quantity of unnamed unit, never money; only taskCostUsd is USD.
 */
class FinalAssemblyJunieTest {

    static final String PROFILE = "junie-3813.7-session-primary";

    /** One input with the acquisition its supplier asserts (the fixture's own launch unless a test says otherwise). */
    record In(String artifact, String role, Path file, String origin, String acquisition) {
    }

    static List<In> all(String fixture) {
        String launch = WriterTestSupport.launch(fixture);
        return List.of(new In("summary.json", "summary", nativeOf(fixture, "summary.json"), "native session directory summary.json copied after the run (sanitized)", launch),
                new In("session-events.jsonl", "session", nativeOf(fixture, "session-events.jsonl"), "native session directory events.jsonl copied after the run (sanitized)", launch),
                new In("stdout.jsonl", "stdout", nativeOf(fixture, "stdout.jsonl"), "standard output of the launch (sanitized)", launch),
                new In("stderr.txt", "stderr", nativeOf(fixture, "stderr.txt"), "standard error of the launch (sanitized)", launch));
    }

    @ParameterizedTest
    @ValueSource(strings = {LIVE_JUNIE_VIEW, LIVE_JUNIE_TERMINAL})
    void theSessionDirectoryAssemblesWithItsOutputsAndTheOracleVerifiesIt(String fixture, @TempDir Path work) throws IOException {
        Path out = assemble(fixture, work.resolve("final"), all(fixture));
        OracleReport report = oracle(fixture, out, all(fixture));
        assertThat(report.defects()).as(report.toString()).isEmpty();
        for (String total : List.of("per_model_output_tokens", "per_model_unnamed_cost", "task_usd")) {
            assertThat(report.outcome("assembly.accounting." + total, "assembly")).as(total + ": " + report).isEqualTo(Check.Outcome.VERIFIED);
        }
        OcsfPackage pkg = PackageReader.open(out.resolve("events.jsonl"));
        assertThat(pkg.declarations.assembly().path("result", "status").text()).isEqualTo("complete");
        // one final answer and one final accounting, each consolidated from the session file and standard output
        assertThat(pkg.events("model_response", 3)).singleElement().satisfies(e -> assertThat(e.assemblyRole()).isEqualTo(3));
        Event accounting = pkg.events("session_context", 3).stream().filter(e -> e.assemblyRole() == 3).findFirst().orElseThrow();
        assertThat(accounting.elements("assembly", "observation_refs")).hasSize(3);
        // the task's total is named USD; per-model costs are a quantity of unnamed unit, never money
        assertThat(accounting.elements("cost_observations")).filteredOn(c -> "1".equals(c.member("quantity_kind_id").scalarText()))
                .singleElement().satisfies(c -> assertThat(c.member("currency").text()).isEqualTo("USD"));
        assertThat(accounting.elements("cost_observations")).filteredOn(c -> "99".equals(c.member("quantity_kind_id").scalarText()))
                .isNotEmpty().allSatisfy(c -> {
                    assertThat(c.member("currency")).isNull();
                    assertThat(c.member("model_ref")).isNotNull();
                });
        // standard output's steps carry no id: retained, canonical once, never joined and never operations
        List<Event> steps = pkg.events.stream().filter(e -> "stdout.jsonl".equals(e.artifact()) && "step".equals(e.text("record_type"))).toList();
        assertThat(steps).isNotEmpty().allSatisfy(e -> assertThat(e.assemblyRole()).isEqualTo(1));
        assertThat(pkg.events.stream().filter(e -> "stdout.jsonl".equals(e.artifact()) && e.className != null && e.className.equals("tool_call"))).isEmpty();
        // the version line on standard error is preserved as text
        assertThat(pkg.events.stream().filter(e -> "stderr.txt".equals(e.artifact()))).isNotEmpty();
        // per-call usage (scope 2) and per-model totals (scope 6) are distinct scopes that agree, never added
        BigDecimal perCall = pkg.events("model_response", 2).stream().flatMap(e -> e.elements("usage_observations").stream())
                .map(u -> ((Json.JNumber) u.member("output_tokens")).value()).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal perModel = accounting.elements("usage_observations").stream().filter(u -> "6".equals(u.member("scope_id").scalarText()))
                .map(u -> ((Json.JNumber) u.member("output_tokens")).value()).reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(perCall).isEqualByComparingTo(perModel);
    }

    @Test
    void aTerminalStepKeepsItsCommandOutputAndExitCode(@TempDir Path work) throws IOException {
        OcsfPackage pkg = PackageReader.open(assemble(LIVE_JUNIE_TERMINAL, work.resolve("final"), all(LIVE_JUNIE_TERMINAL)).resolve("events.jsonl"));
        Event failed = pkg.events("tool_call", 3).stream().filter(e -> "FAILED".equals(e.text("tool_operation", "reported_state"))).findFirst().orElseThrow();
        assertThat(failed.text("tool_operation", "command_line")).isEqualTo("ls -1; cat missing.txt");
        assertThat(failed.get("tool_operation", "exit_code").scalarText()).isEqualTo("1");
        assertThat(failed.integer("status_id")).isEqualTo(2);
    }

    @Test
    void aSessionFileOfAnotherAcquisitionTakesNoIdentityFromASummary(@TempDir Path work) throws IOException {
        // CDX-R1-06: the view run's summary beside the terminal run's session events, stdout and stderr (each asserted
        // as acquired by its own launch). The role names match, but nothing relates the session file to that summary:
        // it is refused, and so is the stderr of the other acquisition; the assembly is incomplete, never complete
        List<In> inputs = List.of(all(LIVE_JUNIE_VIEW).get(0), all(LIVE_JUNIE_TERMINAL).get(1), all(LIVE_JUNIE_TERMINAL).get(2), all(LIVE_JUNIE_TERMINAL).get(3));
        Path out = assemble(LIVE_JUNIE_TERMINAL, work.resolve("final"), inputs);
        OcsfPackage pkg = PackageReader.open(out.resolve("events.jsonl"));
        Json declared = pkg.declarations.assembly();
        assertThat(pkg.events.stream().filter(e -> !"summary.json".equals(e.artifact()) && e.className != null)).as("only the summary's own observations are written").isEmpty();
        assertThat(declared.path("result", "status").text()).isEqualTo("incomplete");
        assertThat(declared.path("result", "missing_required_roles").elements()).extracting(Json::text).containsExactly("session");
        assertThat(source(declared, "session-events.jsonl").member("verdict").text()).contains("no summary source in the same declared acquisition");
        assertThat(source(declared, "stderr.txt").member("verdict").text()).contains("membership not established");
        OracleReport report = oracle(LIVE_JUNIE_TERMINAL, out, inputs);
        assertThat(report.outcome("assembly.gate", "assembly")).as(report.toString()).isEqualTo(Check.Outcome.VERIFIED);
        assertThat(report.outcome("assembly.required", "assembly")).as(report.toString()).isEqualTo(Check.Outcome.VERIFIED);
        // the same inputs all asserted as one acquisition: the caller's assertion is what relates them, and the file says so
        List<In> asserted = inputs.stream().map(i -> new In(i.artifact(), i.role(), i.file(), i.origin(), "one acquisition, as the caller asserts")).toList();
        Json together = PackageReader.open(assemble(LIVE_JUNIE_TERMINAL, work.resolve("asserted"), asserted).resolve("events.jsonl")).declarations.assembly();
        assertThat(source(together, "session-events.jsonl").member("identity_basis").text()).contains("caller assertion");
    }

    static Json source(Json declared, String artifact) {
        return declared.member("sources").elements().stream().filter(s -> artifact.equals(s.member("artifact").text())).findFirst().orElseThrow();
    }

    @Test
    void withoutItsSummaryTheSessionFileHasNoIdentity(@TempDir Path work) throws IOException {
        List<In> inputs = all(LIVE_JUNIE_VIEW).subList(1, 4);
        Path out = assemble(LIVE_JUNIE_VIEW, work.resolve("final"), inputs);
        OcsfPackage pkg = PackageReader.open(out.resolve("events.jsonl"));
        Json declared = pkg.declarations.assembly();
        assertThat(declared.path("result", "status").text()).isEqualTo("incomplete");
        assertThat(declared.path("result", "missing_required_roles").elements()).extracting(Json::text).contains("summary", "session");
        OracleReport report = oracle(LIVE_JUNIE_VIEW, out, inputs);
        assertThat(report.outcome("assembly.required", "assembly")).as(report.toString()).isEqualTo(Check.Outcome.VERIFIED);
        assertThat(report.outcome("assembly.gate", "assembly")).as(report.toString()).isEqualTo(Check.Outcome.VERIFIED);
    }

    @Test
    void theFinalFileReadsAlone(@TempDir Path work) throws IOException {
        Path out = assemble(LIVE_JUNIE_VIEW, work.resolve("final"), all(LIVE_JUNIE_VIEW));
        Path alone = Files.createDirectories(work.resolve("elsewhere"));
        Files.copy(out.resolve("events.jsonl"), alone.resolve("events.jsonl"));
        OcsfPackage pkg = PackageReader.open(alone.resolve("events.jsonl"));
        assertThat(pkg.diagnostics(io.github.markpollack.journal.tck.ocsf.Diagnostic.Severity.DEFECT)).as(pkg.diagnostics.toString()).isEmpty();
        assertThat(pkg.events("model_response", 3)).hasSize(1);
        assertThat(Files.readString(alone.resolve("events.jsonl"), StandardCharsets.UTF_8)).contains("\"kind\":\"assembly\"");
    }

    // ---- support -----------------------------------------------------------------------------------------------------

    static Path assemble(String fixture, Path out, List<In> inputs) {
        List<FinalAssembly.Input> in = inputs.stream().map(i -> new FinalAssembly.Input(i.artifact(), i.role(), i.file(), i.origin(), i.acquisition())).toList();
        FinalAssembly.assemble(out, run(fixture), AssemblyProfile.bundled(PROFILE), in, new Jv.Arr(), options());
        return out;
    }

    static OracleReport oracle(String fixture, Path out, List<In> inputs) throws IOException {
        Json spec = NeutralJson.parse(Files.readString(FIXTURES.resolve(fixture).resolve("native/assembly-expectation.json")));
        List<AssemblyOracle.Supplied> supplied = inputs.stream().map(i -> new AssemblyOracle.Supplied(i.artifact(), i.role(), i.origin(),
                "summary".equals(i.role()) ? NativeArtifact.document(i.file()) : NativeArtifact.read(i.file()), i.acquisition())).toList();
        return AssemblyOracle.verify(PackageReader.open(out.resolve("events.jsonl")), spec, supplied);
    }
}
