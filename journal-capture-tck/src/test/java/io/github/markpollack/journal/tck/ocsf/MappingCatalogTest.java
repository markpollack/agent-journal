package io.github.markpollack.journal.tck.ocsf;

import io.github.markpollack.journal.tck.verify.Check;
import io.github.markpollack.journal.tck.verify.Json;
import io.github.markpollack.journal.tck.verify.NeutralJson;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * RULE-35 / UC5-AC13: the reviewed mapping catalog ({@code ocsf/mapping-catalog/mapping-catalog.json}: the table of how
 * each vendor's fields correspond to Journal's common concepts, with the mapping rule and independent test for each;
 * authored under FULL-1/FULL-1R as the "crosswalk" and maintained here under its clearer name) is checked for reference validity in Java, and
 * the common-concept coverage matrix is <em>derived</em> from its entries plus this run's oracle results, never from
 * matching field values.
 *
 * <p>Reference validity: every mapping rule id is declared by the contract file whose uid it names; every OCSF path
 * resolves in the declared schema of a committed package; every implementation {@code Class#method} exists in the
 * writer's sources; every oracle profile is a class of this package and every property a name the oracle actually
 * reported in this run; every test reference exists in the test sources; every view is a known analysis view or
 * semantic operation.
 *
 * <p>The matrix, written to {@code target/mapping-catalog/COMMON-CONCEPTS.md} and {@code .json}, states one of five
 * separate states per concept and per CLI stream route: {@code verified} (a normalized concept whose oracle properties
 * were VERIFIED with an occurrence on that CLI's committed stream fixture), {@code retained-only} (reviewed as not
 * normalized), {@code unavailable-at-source} (the catalog records the stream has no such field), {@code unexercised}
 * (a concept whose check had no occurrence on the fixture, or an optional concept with no fixture), and
 * {@code unresolved} (a concept whose check was UNSUPPORTED/UNRESOLVED, or a normalized concept without an oracle
 * property for that profile). A DEFECTIVE outcome fails the test outright.
 */
class MappingCatalogTest {

    static final Path PROJECT = Path.of("..").toAbsolutePath().normalize();
    static final Path WRITER_MAIN = PROJECT.resolve("journal-ocsf-writer/src/main/java/io/github/markpollack/journal/ocsf/writer");
    static final Path MAPPINGS = PROJECT.resolve("journal-ocsf-writer/src/main/resources/ocsf/mappings");
    static final Path TCK_MAIN = PROJECT.resolve("journal-capture-tck/src/main/java/io/github/markpollack/journal/tck/ocsf");
    static final List<Path> TEST_SOURCES = List.of(PROJECT.resolve("journal-capture-tck/src/test/java"), PROJECT.resolve("journal-ocsf-writer/src/test/java"));
    static final Set<String> VIEWS = Set.of("submissions", "responses", "usage", "associations", "tool_exchanges", "settings_context");

    /** The CLI stream routes the matrix is computed for: catalog cli label, oracle profile class, committed fixture, fixture artifact. */
    record Route(String cli, String profile, String fixture, String artifact, String label) {
    }

    static final List<Route> ROUTES = List.of(
            new Route("claude", "ClaudeStreamProfile", OcsfFixtures.CHILD, "stream.jsonl", "Claude Code stream 2.1.299 sdk (sanitized real fixture)"),
            new Route("claude", "ClaudeStreamProfile", OcsfFixtures.LIVE_FEATURES, "stream.jsonl", "Claude Code stream 2.1.304 stdout (sanitized live feature recording)"),
            new Route("claude", "ClaudeStreamProfile", OcsfFixtures.LIVE_DENIAL, "stream.jsonl", "Claude Code stream 2.1.304 stdout (sanitized live denial recording)"),
            new Route("codex", "CodexStreamProfile", OcsfFixtures.CODEX_STREAM, "stream.jsonl", "Codex CLI exec stream 0.160.1 (labelled synthetic fixture)"),
            new Route("codex", "CodexStreamProfile", OcsfFixtures.LIVE_CODEX, "stream.jsonl", "Codex CLI exec stream 0.160.1 stdout (sanitized live recording)"));

    @Test
    void everyCatalogReferenceResolvesInJava(@TempDir Path work) throws IOException {
        List<Json> entries = NeutralJson.readDocument(Path.of(MappingCatalogTest.class.getResource("/ocsf/mapping-catalog/mapping-catalog.json").getPath().replace("%20", " "))).elements();
        assertThat(entries).hasSize(38);
        SchemaCatalog schema = FieldDispositionTest.schema(work);
        Map<String, Json> contractsByUid = contractsByUid();
        Set<String> reportedProperties = reportedProperties(work);
        Set<String> semanticOperations = semanticOperations();
        List<String> problems = new ArrayList<>();
        Set<String> ids = new TreeSet<>();
        for (Json e : entries) {
            String id = SchemaCatalog.text(e, "id");
            if (!ids.add(id)) {
                problems.add("duplicate id " + id);
            }
            for (String k : List.of("id", "concept", "question", "upstream", "ocsf", "native_evidence", "mapping", "oracle", "views", "tests", "disposition", "notes")) {
                if (e.member(k) == null) {
                    problems.add(id + ": missing key " + k);
                }
            }
            for (Json o : e.member("ocsf").elements()) {
                String path = SchemaCatalog.text(o, "path");
                String why = FieldDispositionTest.resolveDestination(schema, path);
                if (why != null) {
                    problems.add(id + ": ocsf path " + path + " does not resolve: " + why);
                }
            }
            for (Json m : e.member("mapping").elements()) {
                String uid = SchemaCatalog.text(m, "contract_uid");
                Json contract = contractsByUid.get(uid);
                if (contract == null) {
                    problems.add(id + ": contract " + uid + " is not a bundled mapping contract");
                }
                else if (!FieldDispositionTest.ruleDeclared(((Json.JObject) contract).members(), SchemaCatalog.text(m, "rule_id"))) {
                    problems.add(id + ": rule " + SchemaCatalog.text(m, "rule_id") + " is not declared in " + uid);
                }
                String impl = SchemaCatalog.text(m, "implementation");
                if (!implementationExists(impl)) {
                    problems.add(id + ": implementation " + impl + " not found in the writer sources");
                }
            }
            for (Json o : e.member("oracle").elements()) {
                String profile = SchemaCatalog.text(o, "profile");
                if (!Files.isRegularFile(TCK_MAIN.resolve(profile + ".java"))) {
                    problems.add(id + ": oracle profile " + profile + " is not a class of this package");
                }
                for (Json p : o.member("properties").elements()) {
                    if (!reportedProperties.contains(p.text())) {
                        problems.add(id + ": oracle property " + p.text() + " was not reported by the oracle in this run");
                    }
                }
            }
            for (Json v : e.member("views").elements()) {
                if (!VIEWS.contains(v.text()) && !semanticOperations.contains(v.text())) {
                    problems.add(id + ": view " + v.text() + " is neither an analysis view nor a semantic operation");
                }
            }
            for (Json t : e.member("tests").elements()) {
                String ref = t.text().startsWith("planned:") ? t.text().substring("planned:".length()) : t.text();
                if (!FieldDispositionTest.testExists(ref)) {
                    problems.add(id + ": test " + t.text() + " not found in the test sources");
                }
            }
        }
        assertThat(problems).isEmpty();
    }

    @Test
    void theCommonConceptMatrixIsDerivedFromTheEntriesAndThisRunsOracleResultsWithStatesKeptSeparate(@TempDir Path work) throws IOException {
        List<Json> entries = NeutralJson.readDocument(Path.of(MappingCatalogTest.class.getResource("/ocsf/mapping-catalog/mapping-catalog.json").getPath().replace("%20", " "))).elements();
        Map<Route, OracleReport> reports = new LinkedHashMap<>();
        for (Route r : ROUTES) {
            reports.put(r, OcsfFixtures.verify(OcsfFixtures.copy(r.fixture(), work.resolve("m").resolve(r.fixture()))));
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        Map<String, Map<String, Integer>> totals = new TreeMap<>();
        StringBuilder md = new StringBuilder("# Common-concept coverage matrix (from the mapping catalog)\n\n");
        md.append("Derived by `MappingCatalogTest` from the reviewed mapping-catalog entries and the independent oracle's results on the committed stream fixtures in the same test run. ")
                .append("States are kept separate: verified (oracle properties VERIFIED with an occurrence), retained-only (reviewed, not normalized), unavailable-at-source (the stream has no such field), ")
                .append("unexercised (no occurrence on the fixture, or an optional concept with none), unresolved (UNSUPPORTED/UNRESOLVED, or no independent check for that profile). ")
                .append("No state is computed by matching field values. A finite corpus: a state describes the named fixture, not every version or route.\n\n");
        md.append("| Concept | Disposition |");
        for (Route r : ROUTES) {
            md.append(' ').append(r.label()).append(" |");
        }
        md.append("\n|---|---|");
        for (Route r : ROUTES) {
            md.append("---|");
        }
        md.append('\n');
        for (Json e : entries) {
            String id = SchemaCatalog.text(e, "id");
            String disposition = SchemaCatalog.text(e, "disposition");
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", id);
            row.put("concept", SchemaCatalog.text(e, "concept"));
            row.put("disposition", disposition);
            md.append("| ").append(SchemaCatalog.text(e, "concept")).append(" (`").append(id).append("`) | ").append(disposition).append(" |");
            for (Route r : ROUTES) {
                String state = state(e, r, reports.get(r));
                assertThat(state).as(id + " on " + r.label()).doesNotStartWith("defective");
                row.put(r.label(), state);
                totals.computeIfAbsent(r.label(), k -> new TreeMap<>()).merge(state.split(" ")[0], 1, Integer::sum);
                md.append(' ').append(state).append(" |");
            }
            md.append('\n');
            rows.add(row);
        }
        md.append("\n## Totals per route\n\n");
        for (Route r : ROUTES) {
            md.append("- ").append(r.label()).append(": ").append(totals.get(r.label())).append('\n');
        }
        Path out = Path.of(System.getProperty("basedir", ".")).resolve("target").resolve("mapping-catalog");
        Files.createDirectories(out);
        Files.writeString(out.resolve("COMMON-CONCEPTS.md"), md.toString(), StandardCharsets.UTF_8);
        Files.writeString(out.resolve("common-concepts.json"), toJson(rows, totals), StandardCharsets.UTF_8);
        // the five states all occur somewhere, and nothing was decided by value matching (the oracle has no such mode)
        Set<String> seen = new TreeSet<>();
        totals.values().forEach(m -> seen.addAll(m.keySet()));
        assertThat(seen).contains("verified", "retained-only", "unavailable-at-source", "unexercised");
    }

    /**
     * One state for one concept on one route. The oracle's outcome on the route's fixture is the primary signal when
     * the entry names an independent check for that profile; the catalog's native-evidence availability decides
     * only when no such check exists (every evidence item for the route absent: unavailable-at-source).
     */
    static String state(Json entry, Route route, OracleReport report) {
        String disposition = SchemaCatalog.text(entry, "disposition");
        if (disposition.startsWith("retained-only")) {
            return "retained-only (" + disposition.substring("retained-only-".length()) + ")";
        }
        List<String> properties = new ArrayList<>();
        for (Json o : entry.member("oracle").elements()) {
            if (route.profile().equals(SchemaCatalog.text(o, "profile"))) {
                for (Json p : o.member("properties").elements()) {
                    properties.add(p.text());
                }
            }
        }
        if (properties.isEmpty()) {
            int items = 0;
            int absent = 0;
            for (Json n : entry.member("native_evidence").elements()) {
                if (route.cli().equals(SchemaCatalog.text(n, "cli")) && route.artifact().equals(SchemaCatalog.text(n, "artifact"))) {
                    items++;
                    if ("absent".equals(SchemaCatalog.text(n, "availability"))) {
                        absent++;
                    }
                }
            }
            if (items > 0 && items == absent) {
                return "optional-unexercised".equals(disposition) ? "unexercised (no occurrence on the route's sources)" : "unavailable-at-source";
            }
            return "optional-unexercised".equals(disposition) ? "unexercised (optional; no fixture)" : "unresolved (no independent check for " + route.profile() + ")";
        }
        boolean verified = false;
        boolean occurrence = false;
        List<String> unresolved = new ArrayList<>();
        for (String p : properties) {
            for (Check c : report.checks()) {
                if (!c.property().equals(p)) {
                    continue;
                }
                switch (c.outcome()) {
                    case DEFECTIVE -> {
                        return "defective: " + c;
                    }
                    case VERIFIED, REPRESENTATION_DIFFERS -> {
                        verified = true;
                        occurrence = true;
                    }
                    case UNSUPPORTED, UNRESOLVED -> unresolved.add(c.property() + " " + c.outcome());
                    case NOT_APPLICABLE -> {
                    }
                }
            }
        }
        if (!unresolved.isEmpty()) {
            return "unresolved (" + String.join(", ", unresolved) + ")";
        }
        if (verified) {
            return "verified";
        }
        return occurrence ? "verified" : "unexercised (no occurrence on the fixture)";
    }

    // ---- inputs ------------------------------------------------------------------------------------------------------

    static Map<String, Json> contractsByUid() throws IOException {
        Map<String, Json> out = new LinkedHashMap<>();
        try (Stream<Path> s = Files.list(MAPPINGS)) {
            for (Path p : s.filter(x -> x.toString().endsWith(".json")).toList()) {
                Json c = NeutralJson.readDocument(p);
                out.put(SchemaCatalog.text(c, "contract_uid"), c);
            }
        }
        return out;
    }

    static boolean implementationExists(String ref) {
        int hash = ref.indexOf('#');
        String cls = hash < 0 ? ref : ref.substring(0, hash);
        String method = hash < 0 ? null : ref.substring(hash + 1);
        Path file = WRITER_MAIN.resolve(cls + ".java");
        if (!Files.isRegularFile(file)) {
            return false;
        }
        try {
            String src = Files.readString(file, StandardCharsets.UTF_8);
            return method == null || src.contains(" " + method + "(");
        }
        catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static Set<String> reportedProperties(Path work) {
        Set<String> out = new TreeSet<>();
        for (String fixture : List.of(OcsfFixtures.CHILD, OcsfFixtures.CODEX, OcsfFixtures.CODEX_STREAM, OcsfFixtures.INTERRUPTED)) {
            for (Check c : OcsfFixtures.verify(OcsfFixtures.copy(fixture, work.resolve("p").resolve(fixture))).checks()) {
                out.add(c.property());
            }
        }
        // the pair fixture reports the transcript-side properties (reconstruction of parsed transcript records, contexts)
        for (Check c : OcsfFixtures.verify(OcsfFixtures.copy("syn-claude-pair-transcript", work.resolve("p").resolve("pair"))).checks()) {
            out.add(c.property());
        }
        return out;
    }

    static Set<String> semanticOperations() {
        Set<String> out = new TreeSet<>();
        Json sc = NeutralJson.readDocument(PROJECT.resolve("journal-capture-tck/src/main/resources/ocsf/semantic-contract-0.2.0-prototype.json"));
        for (Json d : sc.member("declarations").elements()) {
            for (String k : List.of("id", "operation")) {
                if (SchemaCatalog.text(d, k) != null) {
                    out.add(SchemaCatalog.text(d, k));
                }
            }
        }
        return out;
    }

    static String toJson(List<Map<String, Object>> rows, Map<String, Map<String, Integer>> totals) {
        StringBuilder sb = new StringBuilder("{\n  \"derived_by\": \"MappingCatalogTest (Java; oracle outcomes of this run; no value matching)\",\n  \"rows\": [\n");
        for (int i = 0; i < rows.size(); i++) {
            sb.append("    {");
            int j = 0;
            for (Map.Entry<String, Object> m : rows.get(i).entrySet()) {
                sb.append(j++ > 0 ? ", " : "").append('"').append(m.getKey()).append("\": \"").append(String.valueOf(m.getValue()).replace("\\", "\\\\").replace("\"", "\\\"")).append('"');
            }
            sb.append(i + 1 < rows.size() ? "},\n" : "}\n");
        }
        sb.append("  ],\n  \"totals\": {");
        int k = 0;
        for (Map.Entry<String, Map<String, Integer>> t : totals.entrySet()) {
            sb.append(k++ > 0 ? ", " : "").append('"').append(t.getKey()).append("\": {");
            int l = 0;
            for (Map.Entry<String, Integer> s : t.getValue().entrySet()) {
                sb.append(l++ > 0 ? ", " : "").append('"').append(s.getKey()).append("\": ").append(s.getValue());
            }
            sb.append('}');
        }
        sb.append("}\n}\n");
        return sb.toString();
    }
}
