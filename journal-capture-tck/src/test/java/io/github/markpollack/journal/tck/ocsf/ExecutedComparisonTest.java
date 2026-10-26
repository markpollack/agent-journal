package io.github.markpollack.journal.tck.ocsf;

import static io.github.markpollack.journal.tck.ocsf.OcsfFixtures.*;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.markpollack.journal.tck.verify.Check;
import io.github.markpollack.journal.tck.verify.Json;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

class ExecutedComparisonTest {

    @Test
    void junieReadersCompareBothFrozenRecordings(@TempDir Path work) {
        for (String name : List.of("junie-live-3813.7-view", "junie-live-3813.7-terminal")) {
            OracleReport report = verify(copy(name, work));
            assertThat(report.checks().stream().filter(c -> c.property().startsWith("fact.")))
                    .isNotEmpty()
                    .allSatisfy(
                            c ->
                                    assertThat(c.outcome())
                                            .as(c + " " + c.evidence())
                                            .isEqualTo(Check.Outcome.VERIFIED));
        }
    }

    @Test
    void copilotReadersCompareEveryFrozenSource(@TempDir Path work) {
        OracleReport report = verify(copy("copilot-live-1.0.100-otel", work));
        assertThat(report.checks().stream().filter(c -> c.property().startsWith("fact.")))
                .isNotEmpty()
                .allSatisfy(
                        c ->
                                assertThat(c.outcome())
                                        .as(c + " " + c.evidence())
                                        .isEqualTo(Check.Outcome.VERIFIED));
    }

    @Test
    void everySelectedFixtureExecutesItsNativeExpectedFacts(@TempDir Path work) throws Exception {
        java.util.Set<String> fixtures = new java.util.TreeSet<>();
        OccurrenceLedger.sources().stream()
                .filter(s -> s.entry() != null)
                .forEach(s -> fixtures.add(s.fixture()));
        java.util.List<String> defects = new java.util.ArrayList<>();
        for (String name : fixtures) {
            Path fixture = copy(name, work);
            OcsfPackage candidate = PackageReader.open(pkg(fixture));
            OracleReport report =
                    java.nio.file.Files.exists(fixture.resolve("native/assembly-expectation.json"))
                            ? io.github.markpollack.journal.tck.ocsf.inventory.AssemblyOracle
                                    .verifyAll(
                                            candidate,
                                            fixture.resolve("native"),
                                            ExpectedSources.beside(fixture.resolve("native")),
                                            AssemblyOracleTest.expectation(fixture),
                                            AssemblyOracleTest.supplied(fixture))
                            : verify(fixture);
            List<AssertionAccounting.Assertion> assertions =
                    AssertionAccounting.inspect(PackageReader.open(pkg(fixture)), report);
            java.nio.file.Path target = java.nio.file.Path.of("target", "executed-accounting");
            java.nio.file.Files.createDirectories(target);
            java.nio.file.Files.writeString(
                    target.resolve(name + "-failures.json"),
                    io.github.markpollack.journal.tck.verify.NeutralJson.write(
                            CanonicalConformanceTest.toJson(report.toMap().get("checks"))));
            java.nio.file.Files.writeString(
                    target.resolve(name + ".txt"),
                    assertions.stream()
                            .filter(a -> !a.status().equals("VERIFIED"))
                            .map(a -> a.status() + " " + a.address() + " " + a.value())
                            .collect(java.util.stream.Collectors.joining("\n")));
            int unresolved =
                    java.util.Map.of(
                                    "syn-codex-counterexamples",
                                    3,
                                    "syn-claude-pair-transcript",
                                    1,
                                    "copilot-live-1.0.100-otel",
                                    5,
                                    "junie-live-3813.7-view",
                                    7,
                                    "junie-live-3813.7-terminal",
                                    8)
                            .getOrDefault(name, 0);
            assertThat(report.withOutcome(Check.Outcome.UNRESOLVED))
                    .as(name + " exact unresolved population")
                    .hasSize(unresolved);
            report.checks().stream()
                    .filter(
                            c ->
                                    c.outcome() == Check.Outcome.DEFECTIVE
                                            || c.outcome() == Check.Outcome.UNRESOLVED
                                                    && !(List.of(
                                                                                    "syn-codex-counterexamples",
                                                                                    "copilot-live-1.0.100-otel",
                                                                                    "junie-live-3813.7-view",
                                                                                    "junie-live-3813.7-terminal")
                                                                            .contains(name)
                                                                    && c.property()
                                                                            .equals(
                                                                                    "source.meaning")
                                                            || name.equals(
                                                                            "syn-claude-pair-transcript")
                                                                    && c.property()
                                                                            .equals(
                                                                                    "combination.policy"))
                                            || c.evidence() != null
                                                    && c.outcome() != Check.Outcome.VERIFIED)
                    .forEach(c -> defects.add(name + " " + c));
            assertThat(report.uncheckedFacts()).as(name + " unchecked registered facts").isEmpty();
            assertions.stream()
                    .filter(a -> "UNCHECKED".equals(a.status()) || "NON_PASSING".equals(a.status()))
                    .forEach(a -> defects.add(name + " " + a.status() + " " + a.address()));
        }
        assertThat(defects).isEmpty();
    }

    @Test
    void anAssignedOrUnrelatedPassingCheckCannotDischargeAMissingComparison(@TempDir Path work) {
        OracleReport report = verify(copy(LIVE_CODEX, work));
        assertThat(report.uncheckedFacts()).isEmpty();
        Check comparison =
                report.checks().stream()
                        .filter(c -> c.property().equals("fact.value"))
                        .findFirst()
                        .orElseThrow();
        OracleReport missing = report.filtered(c -> c != comparison);
        assertThat(missing.uncheckedFacts())
                .anyMatch(e -> e.fact().equals(comparison.evidence().fact()));
        missing.add(
                Check.Category.NORMALIZATION,
                comparison.property(),
                comparison.owner(),
                Check.Outcome.VERIFIED,
                "unrelated collection passed",
                1);
        assertThat(missing.uncheckedFacts())
                .anyMatch(e -> e.fact().equals(comparison.evidence().fact()));
        assertThat(OcsfReaderTool.acceptance(missing).verdict()).isEqualTo("INCOMPLETE");
        assertThat(OcsfReaderTool.acceptance(missing).reasons())
                .anyMatch(r -> r.contains("required fact unchecked"));
    }

    @Test
    void recordedClaudeAndCodexHaveAttributedComparisonsAndRejectCoveredValueEdits(
            @TempDir Path work) {
        for (String name : List.of("claude-live-2.1.304-mcp-pair", LIVE_CODEX)) {
            Path fixture = copy(name, work);
            OracleReport control = verify(fixture);
            assertThat(control.checks().stream().filter(c -> c.property().startsWith("fact.usage")))
                    .isNotEmpty()
                    .allMatch(c -> c.outcome() == Check.Outcome.VERIFIED);
            Check checked =
                    control.checks().stream()
                            .filter(
                                    c ->
                                            c.evidence() != null
                                                    && c.evidence()
                                                            .fact()
                                                            .endsWith("/output_tokens")
                                                    && c.evidence().expected()
                                                            instanceof Json.JNumber)
                            .findFirst()
                            .orElseThrow();
            assertThat(checked.evidence().nativeAddresses()).isNotEmpty();
            editEvents(
                    pkg(fixture),
                    events -> {
                        Json event =
                                events.stream()
                                        .filter(e -> e.member("usage_observations") != null)
                                        .findFirst()
                                        .orElseThrow();
                        obj(event.member("usage_observations").elements().get(0))
                                .members()
                                .put("output_tokens", num("999999"));
                    });
            assertThat(verify(fixture).checks())
                    .anyMatch(
                            c ->
                                    c.property().equals("fact.usage.value")
                                            && c.outcome() == Check.Outcome.DEFECTIVE
                                            && c.evidence().fact().endsWith("/output_tokens"));
        }
    }

    @Test
    void equalMeasurementsRemainTwoOccurrencesAndMissingOrInventedOccurrencesFail() {
        ExpectedFacts facts = new ExpectedFacts("native.jsonl");
        facts.usages.add(
                new ExpectedFacts.Usage(
                        1, 1, 2, 2, java.util.Map.of("output_tokens", num("7")), "model"));
        facts.usages.add(
                new ExpectedFacts.Usage(
                        2, 2, 2, 2, java.util.Map.of("output_tokens", num("7")), "model"));
        List<Event> events = List.of(event(1, "model", "7"), event(2, "model", "7"));
        OracleReport control = new OracleReport();
        UsageComparisons.check(control, facts, events, "main/native.jsonl");
        assertThat(control.defects()).isEmpty();
        assertThat(
                        control.checks().stream()
                                .filter(c -> c.evidence().fact().endsWith("/output_tokens")))
                .hasSize(2);
        OracleReport missing = new OracleReport();
        UsageComparisons.check(missing, facts, events.subList(0, 1), "main/native.jsonl");
        assertThat(missing.defects()).anyMatch(c -> c.evidence().fact().startsWith("usage:2:"));
        OracleReport extra = new OracleReport();
        UsageComparisons.check(
                extra,
                facts,
                List.of(events.get(0), events.get(1), event(3, "model", "7")),
                "main/native.jsonl");
        assertThat(extra.defects()).anyMatch(c -> c.property().equals("fact.usage.unsupported"));
    }

    @Test
    void rightValueOnTheWrongModelIsMissingAtItsOwnerAndUnsupportedAtTheTarget() {
        ExpectedFacts facts = new ExpectedFacts("native.jsonl");
        facts.usages.add(
                new ExpectedFacts.Usage(
                        1, 1, 2, 2, java.util.Map.of("output_tokens", num("7")), "model"));
        OracleReport result = new OracleReport();
        UsageComparisons.check(
                result, facts, List.of(event(1, "other-model", "7")), "main/native.jsonl");
        assertThat(result.defects())
                .extracting(Check::property)
                .containsExactly("fact.usage.population", "fact.usage.unsupported");
    }

    @Test
    void aCorrectGrandTotalCannotHideTwoIncorrectMeasurements() {
        ExpectedFacts facts = new ExpectedFacts("native.jsonl");
        facts.usages.add(
                new ExpectedFacts.Usage(
                        1, 1, 2, 2, java.util.Map.of("output_tokens", num("7")), "model"));
        facts.usages.add(
                new ExpectedFacts.Usage(
                        2, 2, 2, 2, java.util.Map.of("output_tokens", num("7")), "model"));
        OracleReport report = new OracleReport();
        UsageComparisons.check(
                report,
                facts,
                List.of(event(1, "model", "8"), event(2, "model", "6")),
                "main/native.jsonl");
        assertThat(report.defects())
                .filteredOn(c -> c.evidence().fact().endsWith("/output_tokens"))
                .hasSize(2);
    }

    private static Event event(int record, String model, String output) {
        Json source =
                new Json.JObject(
                        java.util.Map.of(
                                "artifact",
                                str("native.jsonl"),
                                "record_index",
                                FactComparison.value(record)));
        Json usage =
                new Json.JObject(
                        java.util.Map.of(
                                "scope_id",
                                num("2"),
                                "usage_status_id",
                                num("2"),
                                "source_ref",
                                source,
                                "model_ref",
                                new Json.JObject(java.util.Map.of("name", str(model))),
                                "output_tokens",
                                num(output)));
        return new Event(
                record,
                new Json.JObject(
                        java.util.Map.of(
                                "source_ref",
                                source,
                                "usage_observations",
                                new Json.JArray(List.of(usage)))),
                "model_response");
    }
}
