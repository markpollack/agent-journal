package io.github.markpollack.journal.tck.ocsf;

import io.github.markpollack.journal.tck.verify.Check;
import io.github.markpollack.journal.tck.verify.NeutralJson;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Reporting of the ordinary oracle's executed comparisons, separate from declared checker
 * assignments.
 */
final class ExecutedAccounting {

    record Run(OracleReport report, List<AssertionAccounting.Assertion> assertions) {}

    private ExecutedAccounting() {}

    static Map<String, Run> run(Set<String> fixtures) {
        Map<String, Run> out = new TreeMap<>();
        for (String name : fixtures) {
            Path fixture = OccurrenceLedger.FIXTURES.resolve(name);
            OcsfPackage pkg = PackageReader.open(fixture.resolve("package/events.jsonl"));
            Path nativeDir = fixture.resolve("native");
            OracleReport report =
                    Files.exists(nativeDir.resolve("assembly-expectation.json"))
                            ? io.github.markpollack.journal.tck.ocsf.inventory.AssemblyOracle
                                    .verifyAll(
                                            pkg,
                                            nativeDir,
                                            ExpectedSources.beside(nativeDir),
                                            AssemblyOracleTest.expectation(fixture),
                                            AssemblyOracleTest.supplied(fixture))
                            : OcsfOracle.verify(pkg, nativeDir);
            out.put(name, new Run(report, AssertionAccounting.inspect(pkg, report)));
        }
        return out;
    }

    static Map<String, Object> summary(
            Map<String, Run> runs, List<OccurrenceLedger.Ledger> ledgers) {
        Map<String, Object> out = new LinkedHashMap<>();
        runs.forEach(
                (fixture, run) -> {
                    Map<String, Object> owners = new TreeMap<>();
                    Set<String> names = new java.util.TreeSet<>();
                    run.report().checks().forEach(c -> names.add(c.owner()));
                    for (String owner : names) {
                        List<Check> checks =
                                run.report().checks().stream()
                                        .filter(
                                                c ->
                                                        c.owner().equals(owner)
                                                                && c.evidence() != null)
                                        .toList();
                        Map<String, Long> outcomes = new TreeMap<>();
                        checks.forEach(c -> outcomes.merge(c.outcome().name(), 1L, Long::sum));
                        owners.put(
                                owner,
                                CanonicalConformanceTest.ordered(
                                        "required_comparisons",
                                        run.report().expectedFacts().stream()
                                                .filter(e -> e.owner().equals(owner))
                                                .count(),
                                        "executed_comparisons",
                                        checks.size(),
                                        "outcomes",
                                        outcomes,
                                        "required_without_result",
                                        run.report().uncheckedFacts().stream()
                                                .filter(e -> e.owner().equals(owner))
                                                .count()));
                    }
                    out.put(
                            fixture,
                            CanonicalConformanceTest.ordered(
                                    "owners",
                                    owners,
                                    "canonical_assertions",
                                    AssertionAccounting.counts(run.assertions(), true),
                                    "supporting_assertions",
                                    AssertionAccounting.counts(run.assertions(), false),
                                    "comparison_complete",
                                    run.report().uncheckedFacts().isEmpty()
                                            && run.report().defects().isEmpty()
                                            && run.report().missingRequired().isEmpty()
                                            && run.report().checks().stream()
                                                    .noneMatch(
                                                            c ->
                                                                    c.outcome()
                                                                                    == Check.Outcome
                                                                                            .UNRESOLVED
                                                                            || c.outcome()
                                                                                            == Check
                                                                                                    .Outcome
                                                                                                    .UNSUPPORTED
                                                                                    && !OcsfReaderTool
                                                                                            .OPTIONAL_PROPERTIES
                                                                                            .contains(
                                                                                                    c
                                                                                                            .property()))
                                            && ledgers.stream()
                                                    .filter(
                                                            l ->
                                                                    l.source()
                                                                            .fixture()
                                                                            .equals(fixture))
                                                    .flatMap(
                                                            l ->
                                                                    NativeAccounting.account(
                                                                            l, run.report())
                                                                            .stream())
                                                    .allMatch(o -> o.status().equals("VERIFIED"))
                                            && run.assertions().stream()
                                                    .noneMatch(
                                                            a ->
                                                                    a.status().equals("UNCHECKED")
                                                                            || a.status()
                                                                                    .equals(
                                                                                            "NON_PASSING")),
                                    "semantic_approval",
                                    "not granted by execution or this ratchet"));
                });
        return out;
    }

    static List<String> snapshot(Map<String, Run> runs) {
        List<String> out = new ArrayList<>();
        runs.forEach(
                (fixture, run) -> {
                    Map<String, List<String>> byOwner = new TreeMap<>();
                    run.report().checks().stream()
                            .filter(c -> c.evidence() != null)
                            .forEach(
                                    c ->
                                            byOwner.computeIfAbsent(
                                                            c.owner(), k -> new ArrayList<>())
                                                    .add(
                                                            c.evidence().fact()
                                                                    + "|"
                                                                    + c.evidence().meaning()
                                                                    + "|"
                                                                    + c.evidence().nativeAddresses()
                                                                    + "|"
                                                                    + c.evidence().assertion()
                                                                    + "|"
                                                                    + operand(
                                                                            c.evidence().expected())
                                                                    + "|"
                                                                    + operand(c.evidence().actual())
                                                                    + "|"
                                                                    + operand(
                                                                            c.evidence()
                                                                                    .assertionValue())
                                                                    + "|"
                                                                    + c.outcome()));
                    run.report().checks().stream()
                            .filter(
                                    c ->
                                            c.outcome() == Check.Outcome.UNRESOLVED
                                                    || c.outcome() == Check.Outcome.UNSUPPORTED
                                                    || c.outcome() == Check.Outcome.DEFECTIVE)
                            .forEach(
                                    c ->
                                            out.add(
                                                    "check-nonpassing "
                                                            + fixture
                                                            + " "
                                                            + c.owner()
                                                            + " "
                                                            + c.property()
                                                            + " "
                                                            + c.outcome()
                                                            + " "
                                                            + c.detail()));
                    byOwner.forEach(
                            (owner, comparisons) -> {
                                java.util.Collections.sort(comparisons);
                                String digest =
                                        NativeArtifact.sha256(
                                                String.join("\n", comparisons)
                                                        .getBytes(
                                                                java.nio.charset.StandardCharsets
                                                                        .UTF_8));
                                out.add(
                                        "comparison-population "
                                                + fixture
                                                + " "
                                                + owner
                                                + " "
                                                + comparisons.size()
                                                + " sha256:"
                                                + digest);
                            });
                    run.report().checks().stream()
                            .filter(
                                    c ->
                                            c.evidence() != null
                                                    && c.outcome() != Check.Outcome.VERIFIED)
                            .forEach(
                                    c ->
                                            out.add(
                                                    "comparison-nonpassing "
                                                            + fixture
                                                            + " "
                                                            + c.owner()
                                                            + " "
                                                            + c.evidence().fact()
                                                            + " "
                                                            + c.outcome()));
                    run.report()
                            .uncheckedFacts()
                            .forEach(
                                    e ->
                                            out.add(
                                                    "required-unchecked "
                                                            + fixture
                                                            + " "
                                                            + e.owner()
                                                            + " "
                                                            + e.fact()));
                    run.assertions().stream()
                            .filter(
                                    a ->
                                            a.status().equals("UNCHECKED")
                                                    || a.status().equals("NON_PASSING"))
                            .forEach(
                                    a ->
                                            out.add(
                                                    "assertion-outcome "
                                                            + fixture
                                                            + " "
                                                            + a.address()
                                                            + " "
                                                            + a.status()));
                });
        return out;
    }

    private static String operand(io.github.markpollack.journal.tck.verify.Json value) {
        return value == null ? "<absent>" : SemanticClaims.canonical(value);
    }

    static void write(Map<String, Run> runs, Path directory) throws IOException {
        Files.createDirectories(directory.resolve("executed"));
        for (Map.Entry<String, Run> entry : runs.entrySet()) {
            Map<String, Object> document = new LinkedHashMap<>(entry.getValue().report().toMap());
            document.put(
                    "assertions",
                    entry.getValue().assertions().stream()
                            .map(
                                    a ->
                                            CanonicalConformanceTest.ordered(
                                                    "address",
                                                    a.address(),
                                                    "canonical",
                                                    a.canonical(),
                                                    "status",
                                                    a.status(),
                                                    "checks",
                                                    a.checks()))
                            .toList());
            Files.writeString(
                    directory.resolve("executed").resolve(entry.getKey() + ".json"),
                    NeutralJson.write(CanonicalConformanceTest.toJson(document)) + "\n");
        }
    }

    static String markdown(Map<String, Run> runs) {
        StringBuilder text =
                new StringBuilder(
                        "\n"
                            + "## Executed comparisons\n\n"
                            + "Assignments above grant no credit. These counts come from exact"
                            + " native and candidate operands in executed Checks. Supporting"
                            + " observations are reported separately and are not counted as"
                            + " additional canonical facts. Missing comparisons remain"
                            + " UNCHECKED.\n\n"
                            + "| Fixture | Required comparisons | Executed | Verified | Defective |"
                            + " Required unchecked | Canonical assertions | Supporting assertions"
                            + " |\n"
                            + "|---|---:|---:|---:|---:|---:|---|---|\n");
        runs.forEach(
                (name, run) -> {
                    List<Check> checks =
                            run.report().checks().stream()
                                    .filter(c -> c.evidence() != null)
                                    .toList();
                    text.append("| ")
                            .append(name)
                            .append(" | ")
                            .append(run.report().expectedFacts().size())
                            .append(" | ")
                            .append(checks.size())
                            .append(" | ")
                            .append(
                                    checks.stream()
                                            .filter(c -> c.outcome() == Check.Outcome.VERIFIED)
                                            .count())
                            .append(" | ")
                            .append(
                                    checks.stream()
                                            .filter(c -> c.outcome() == Check.Outcome.DEFECTIVE)
                                            .count())
                            .append(" | ")
                            .append(run.report().uncheckedFacts().size())
                            .append(" | ")
                            .append(AssertionAccounting.counts(run.assertions(), true))
                            .append(" | ")
                            .append(AssertionAccounting.counts(run.assertions(), false))
                            .append(" |\n");
                });
        return text.toString();
    }
}
