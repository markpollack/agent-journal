package io.github.markpollack.journal.tck.ocsf;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.markpollack.journal.tck.verify.Json;
import io.github.markpollack.journal.tck.verify.NeutralJson;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Catalog assignments, exact input pins and actual attributed results are independently required.
 */
class ExpectedFactCatalogTest {
    static List<Path> catalogs() {
        return FieldDispositionTest.CATALOGS.values().stream()
                .map(FieldDispositionTest.PROJECT::resolve)
                .map(Path::getParent)
                .map(
                        p ->
                                Files.exists(p.resolve("expected-facts.json"))
                                        ? p.resolve("expected-facts.json")
                                        : p.resolve("obligations.json"))
                .toList();
    }

    static Set<String> entries(Path file) {
        Json catalog = NeutralJson.readDocument(file);
        assertThat(SourceProfile.text(catalog, "implementation")).isEqualTo("java-expected-facts");
        assertThat(catalog.member("obligations").elements()).isNotEmpty();
        Set<String> entries = new TreeSet<>();
        for (Json binding : catalog.member("bindings").elements()) {
            String path = SourceProfile.text(binding, "source_specification");
            assertThat(
                            NativeArtifact.sha256(
                                    CanonicalConformanceTest.read(
                                            FieldDispositionTest.PROJECT.resolve(path))))
                    .as(file + " source specification " + path)
                    .isEqualTo(SourceProfile.text(binding, "specification_sha256"));
            entries.add(SourceProfile.text(binding, "entry_id"));
        }
        return entries;
    }

    static List<String> check(Json catalog, OccurrenceLedger.Source source, OracleReport report) {
        List<String> problems = new ArrayList<>();
        Set<String> meanings = new TreeSet<>();
        catalog.member("obligations")
                .elements()
                .forEach(o -> meanings.add(SourceProfile.text(o, "meaning")));
        String suffix = "/" + source.artifact();
        for (OracleReport.JsonExpectation fact :
                report.expectedFacts().stream().filter(f -> f.owner().endsWith(suffix)).toList()) {
            if (!meanings.contains(fact.meaning())) {
                problems.add("unassigned meaning " + fact.meaning());
            }
        }
        report.uncheckedFacts().stream()
                .filter(f -> f.owner().endsWith(suffix))
                .forEach(f -> problems.add("UNCHECKED " + f.fact()));
        if (source.entry() != null
                && catalog.member("bindings").elements().stream()
                        .noneMatch(b -> source.entry().equals(SourceProfile.text(b, "entry_id")))) {
            problems.add("missing exact source binding " + source.entry());
        }
        return problems;
    }

    @Test
    void everySelectedReaderHasAConcreteCatalogAndExactSpecificationPins() {
        List<OccurrenceLedger.Source> sources = OccurrenceLedger.sources();
        Map<String, ExecutedAccounting.Run> reports =
                ExecutedAccounting.run(
                        sources.stream()
                                .map(OccurrenceLedger.Source::fixture)
                                .collect(java.util.stream.Collectors.toSet()));
        for (Path file : catalogs()) {
            entries(file);
            Json catalog = NeutralJson.readDocument(file);
            String profile = SourceProfile.text(catalog, "profile");
            for (OccurrenceLedger.Source source :
                    sources.stream()
                            .filter(s -> s.profile().equals(profile) && s.entry() != null)
                            .toList()) {
                assertThat(check(catalog, source, reports.get(source.fixture()).report()))
                        .as(source.id())
                        .isEmpty();
                Json stripped =
                        new Json.JObject(
                                SourceFacts.fields(
                                        "obligations",
                                        List.of(),
                                        "bindings",
                                        catalog.member("bindings")));
                assertThat(check(stripped, source, reports.get(source.fixture()).report()))
                        .as("missing assignments " + source.id())
                        .isNotEmpty();
            }
        }
    }
}
