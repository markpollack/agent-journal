package io.github.markpollack.journal.tck.ocsf;

import io.github.markpollack.journal.tck.ocsf.inventory.AssemblyOracle;
import io.github.markpollack.journal.tck.verify.Check;
import io.github.markpollack.journal.tck.verify.Json;
import io.github.markpollack.journal.tck.verify.NeutralJson;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.UnaryOperator;

import static io.github.markpollack.journal.tck.ocsf.OcsfFixtures.copy;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The independent assembly oracle on the committed finally assembled fixtures (the packages the writer produced from
 * the sanitized live recordings), read alone, with the native bytes and the hand-written expectations: every assembly
 * holds; the per-source oracle still judges each source's own observations; and files altered after assembly, or
 * native inputs that disagree with what the file declares, are rejected for the reason that applies.
 */
class AssemblyOracleTest {

    static final String CLAUDE = "claude-live-2.1.304-mcp-pair";
    static final String COPILOT = "copilot-live-1.0.100-otel";

    @ParameterizedTest
    @ValueSource(strings = {CLAUDE, COPILOT, "junie-live-3813.7-view", "junie-live-3813.7-terminal"})
    void eachCommittedAssemblyHoldsUnderTheIndependentOracle(String name, @TempDir Path work) throws IOException {
        Path fixture = copy(name, work);
        Path alone = Files.createDirectories(work.resolve("alone"));
        Files.copy(fixture.resolve("package/events.jsonl"), alone.resolve("events.jsonl"));
        OracleReport report = AssemblyOracle.verify(PackageReader.open(alone.resolve("events.jsonl")), expectation(fixture), supplied(fixture));
        assertThat(report.defects()).as(report.toString()).isEmpty();
        for (String property : List.of("assembly.declaration", "assembly.gate", "assembly.required", "assembly.entities", "assembly.references", "assembly.consolidation",
                "assembly.accounting.distinct")) {
            assertThat(report.outcome(property, "assembly")).as(name + " " + property + ": " + report).isEqualTo(Check.Outcome.VERIFIED);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {CLAUDE, COPILOT, "junie-live-3813.7-view"})
    void thePerSourceOracleStillJudgesEachSourcesOwnObservations(String name, @TempDir Path work) {
        Path fixture = copy(name, work);
        OracleReport report = OcsfFixtures.verify(fixture);
        assertThat(report.defects()).as(report.toString()).isEmpty();
        OcsfPackage pkg = OcsfFixtures.open(fixture);
        // the canonical projection counts an entity once; observations keep every source's view
        assertThat(pkg.canonical().size()).isLessThan(pkg.events.size());
        assertThat(pkg.observations().size()).isLessThan(pkg.events.size());
    }

    @Test
    void aFileAlteredAfterAssemblyIsRejectedForItsReason(@TempDir Path work) throws IOException {
        Path fixture = copy(CLAUDE, work);
        assertThat(judge(fixture, line -> line.contains("\"assembly_role_id\":2") && line.contains("\"Tool Call\""),
                l -> l.replace("\"assembly_role_id\":2,\"assembly_role\":\"Supporting observation\"", "\"assembly_role_id\":1,\"assembly_role\":\"Canonical single observation\""))
                .outcome("assembly.entities", "assembly")).isEqualTo(Check.Outcome.DEFECTIVE);
        assertThat(judge(fixture, line -> line.contains("\"assembly_role_id\":3") && line.contains("\"stop_reason\""),
                l -> l.replaceFirst("\"stop_reason\":\"[a-z_]+\"", "\"stop_reason\":\"invented\"")).outcome("assembly.consolidation", "assembly")).isEqualTo(Check.Outcome.DEFECTIVE);
        assertThat(judge(fixture, line -> line.contains("\"assembly_role_id\":3") && line.contains("\"observation_refs\""),
                l -> l.replaceFirst("\"record_index\":(\\d+),\"emit_ordinal\"", "\"record_index\":9999,\"emit_ordinal\"")).outcome("assembly.references", "assembly"))
                .isEqualTo(Check.Outcome.DEFECTIVE);
        assertThat(judge(fixture, line -> line.contains("\"kind\":\"assembly\""), l -> l.replace("\"origin\":\"CliCapture stdout copy of the launch (sanitized)\"", "\"origin\":\"elsewhere\""))
                .outcome("assembly.declaration", "assembly")).isEqualTo(Check.Outcome.DEFECTIVE);
        assertThat(judge(fixture, line -> line.contains("\"kind\":\"assembly\""), l -> l.replace("\"status\":\"complete with conflicts\"", "\"status\":\"incomplete\""))
                .outcome("assembly.required", "assembly")).isEqualTo(Check.Outcome.DEFECTIVE);
        Path noDeclaration = Files.createDirectories(work.resolve("undeclared"));
        Files.write(noDeclaration.resolve("events.jsonl"), Files.readAllLines(fixture.resolve("package/events.jsonl"), StandardCharsets.UTF_8).stream()
                .filter(l -> !l.contains("\"kind\":\"assembly\"")).toList(), StandardCharsets.UTF_8);
        assertThat(AssemblyOracle.verify(PackageReader.open(noDeclaration.resolve("events.jsonl")), expectation(fixture), supplied(fixture))
                .outcome("assembly.declaration", "assembly")).isEqualTo(Check.Outcome.DEFECTIVE);
    }

    @Test
    void anAccountingTotalThatDisagreesWithTheNativeRecordsIsRejected(@TempDir Path work) throws IOException {
        Path fixture = copy(COPILOT, work);
        // the invocation's multiplier sum doubled on its canonical event
        OracleReport report = judge(fixture, line -> line.contains("\"assembly_role_id\":3") && line.contains("\"multiplier sum\""),
                l -> l.replace("\"total_cost\":5.0,\"quantity_kind_id\":3", "\"total_cost\":10.0,\"quantity_kind_id\":3"));
        assertThat(report.outcome("assembly.accounting.run_multiplier_sum", "assembly")).isEqualTo(Check.Outcome.DEFECTIVE);
    }

    @Test
    void aRunTotalSelectedByScopeAloneCountsTheRunsLastResponseTwice(@TempDir Path work) throws IOException {
        // the result record carries the run total (no aggregation) and, at the same scope 4, the final usage of the run's
        // last response (aggregation 3 Single response): a total that does not name its window adds the two
        Path fixture = copy(CLAUDE, work);
        assertThat(AssemblyOracle.verify(OcsfFixtures.open(fixture), expectation(fixture), supplied(fixture)).outcome("assembly.accounting.run_output_tokens", "assembly"))
                .isEqualTo(Check.Outcome.VERIFIED);
        Path spec = fixture.resolve("native/assembly-expectation.json");
        String windowed = Files.readString(spec, StandardCharsets.UTF_8);
        String scopeOnly = windowed.replaceFirst("\"scope_id\": 4,\\s*\"aggregation_id\": null", "\"scope_id\": 4");
        assertThat(scopeOnly).isNotEqualTo(windowed);
        Files.writeString(spec, scopeOnly, StandardCharsets.UTF_8);
        OracleReport report = AssemblyOracle.verify(OcsfFixtures.open(fixture), expectation(fixture), supplied(fixture));
        assertThat(report.outcome("assembly.accounting.run_output_tokens", "assembly")).isEqualTo(Check.Outcome.DEFECTIVE);
        assertThat(report.check("assembly.accounting.run_output_tokens", "assembly").detail()).contains("native 425").contains("canonical 468");
    }

    @Test
    void nativeInputsThatDisagreeWithTheFileAreRejected(@TempDir Path work) throws IOException {
        Path fixture = copy(COPILOT, work);
        // the committed file accepted this telemetry; supplied with another execution's session it must have been refused
        Path otel = fixture.resolve("native/otel.jsonl");
        Files.writeString(otel, Files.readString(otel, StandardCharsets.UTF_8).replace("00000000-0000-4000-8000-0000000000c1", "11111111-1111-4111-8111-111111111111"),
                StandardCharsets.UTF_8);
        OracleReport report = AssemblyOracle.verify(OcsfFixtures.open(fixture), expectation(fixture), supplied(fixture));
        assertThat(report.outcome("assembly.gate", "assembly")).isEqualTo(Check.Outcome.DEFECTIVE);
        assertThat(report.outcome("assembly.declaration", "assembly")).as("the bytes differ from the declared sha256").isEqualTo(Check.Outcome.DEFECTIVE);
    }

    // ---- support -----------------------------------------------------------------------------------------------------

    static Json expectation(Path fixture) {
        return NeutralJson.readDocument(fixture.resolve("native/assembly-expectation.json"));
    }

    /** The inputs the fixture's spec says were assembled, with their roles and origins, read as their framing declares. */
    static List<AssemblyOracle.Supplied> supplied(Path fixture) {
        List<AssemblyOracle.Supplied> out = new ArrayList<>();
        for (Json i : NeutralJson.readDocument(fixture.resolve("spec.json")).path("assembly", "inputs").elements()) {
            String artifact = i.member("artifact").text();
            Path file = fixture.resolve("native").resolve(artifact);
            out.add(new AssemblyOracle.Supplied(artifact, i.member("role").text(), i.member("origin").text(),
                    artifact.endsWith(".json") ? NativeArtifact.document(file) : NativeArtifact.read(file), i.member("acquisition") == null ? null : i.member("acquisition").text()));
        }
        return out;
    }

    /** The oracle's report on a copy of the fixture's file with the first matching line edited. */
    static OracleReport judge(Path fixture, java.util.function.Predicate<String> match, UnaryOperator<String> edit) throws IOException {
        Path dir = Files.createTempDirectory(fixture.getParent(), "altered");
        boolean[] done = {false};
        List<String> lines = Files.readAllLines(fixture.resolve("package/events.jsonl"), StandardCharsets.UTF_8).stream().map(l -> {
            if (!done[0] && match.test(l)) {
                done[0] = true;
                return edit.apply(l);
            }
            return l;
        }).toList();
        assertThat(done[0]).as("the alteration applied").isTrue();
        Files.write(dir.resolve("events.jsonl"), lines, StandardCharsets.UTF_8);
        return AssemblyOracle.verify(PackageReader.open(dir.resolve("events.jsonl")), expectation(fixture), supplied(fixture));
    }
}
