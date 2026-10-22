package io.github.markpollack.journal.tck.ocsf;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
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
 * the CLI coverage table is <em>derived</em> from its entries plus this run's oracle results, never from matching field
 * values.
 *
 * <p>Reference validity: every mapping rule id is declared by the contract file whose uid it names; every OCSF path
 * resolves in the declared schema of a committed package; every implementation {@code Class#method} exists in the
 * writer's sources; every oracle profile is a class of this package and every property a name the oracle actually
 * reported in this run, at the owner scope the catalog declares; every scenario is known; every route-scoped
 * availability claim names a compatibility entry and holds on that route's committed recordings; every test reference
 * exists in the test sources; every view is a known analysis view or semantic operation.
 *
 * <p>The table, written to {@code target/mapping-catalog/CLI-COVERAGE.md} and {@code cli-coverage.json}, has one
 * column per admitted CLI/version/profile/route (the historical sdk route labelled as such) and one state per concept,
 * computed by {@link CatalogCoverage} on every committed recording of the route and combined: {@code defective} and
 * {@code blocked} (an owed result missing) fail this test; {@code unresolved}, {@code verified}, {@code retained-only},
 * {@code unavailable-at-source} and {@code unexercised} are kept separate.
 */
class MappingCatalogTest {

    static final Path PROJECT = Path.of("..").toAbsolutePath().normalize();
    static final Path WRITER_MAIN = PROJECT.resolve("journal-ocsf-writer/src/main/java/io/github/markpollack/journal/ocsf/writer");
    static final Path MAPPINGS = PROJECT.resolve("journal-ocsf-writer/src/main/resources/ocsf/mappings");
    static final Path COMPATIBILITY = PROJECT.resolve("journal-ocsf-writer/src/main/resources/ocsf/compatibility.json");
    static final Path TCK_MAIN = PROJECT.resolve("journal-capture-tck/src/main/java/io/github/markpollack/journal/tck/ocsf");
    static final List<Path> TEST_SOURCES = List.of(PROJECT.resolve("journal-capture-tck/src/test/java"), PROJECT.resolve("journal-ocsf-writer/src/test/java"));
    static final Set<String> VIEWS = Set.of("submissions", "responses", "usage", "associations", "tool_exchanges", "settings_context");
    static final ObjectMapper PRETTY = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    /** The 38 concepts the catalog started FND-1 with: the denominator may grow, never silently shrink. */
    static final Set<String> INITIAL_CONCEPTS = Set.of("session-identity", "prompt-turn-identity", "journal-run-and-invocation", "user-submission",
            "model-response-identity", "ordered-content-blocks", "reasoning-blocks-and-signature", "redacted-or-encrypted-reasoning",
            "media-and-multimodal-blocks", "unknown-block-kinds", "response-stop-reason-and-completion", "response-update-assembly",
            "tool-request-identity-and-arguments", "tool-kind-classification", "tool-result-ownership-and-content", "structured-tool-result-detail",
            "tool-result-error-and-status", "observed-operations", "operation-attribution", "operation-and-request-reported-state",
            "interrupted-run-and-never-observed-result", "usage-components-and-cache", "reasoning-token-counts", "usage-scope-finality-and-repeats",
            "reported-cost", "model-identity-and-provider", "permission-decisions", "launch-and-session-settings", "injected-context",
            "session-accounting", "child-runs", "source-occurrence-provenance", "whole-record-residual-and-reconstruction",
            "opaque-malformed-and-blank-records", "retained-only-known-meaning-families", "retained-only-unresolved-families", "time-basis",
            "reported-durations");

    /**
     * One committed recording of a CLI stream route: catalog cli label, oracle profile class, committed fixture,
     * fixture artifact, a label naming the recording's origin, and the compatibility entry (CLI, profile, version and
     * launch route) it was recorded on.
     */
    record Route(String cli, String profile, String fixture, String artifact, String label, String entry) {
    }

    static final List<Route> ROUTES = List.of(
            new Route("claude", "ClaudeStreamProfile", OcsfFixtures.CHILD, "stream.jsonl", "Claude Code stream 2.1.299 sdk (sanitized real fixture)", "claude-code/stream-json/2.1.299-sdk"),
            new Route("claude", "ClaudeStreamProfile", OcsfFixtures.LIVE_FEATURES, "stream.jsonl", "Claude Code stream 2.1.304 stdout (sanitized live feature recording)", "claude-code/stream-json/2.1.304-stdout"),
            new Route("claude", "ClaudeStreamProfile", OcsfFixtures.LIVE_DENIAL, "stream.jsonl", "Claude Code stream 2.1.304 stdout (sanitized live denial recording)", "claude-code/stream-json/2.1.304-stdout"),
            new Route("codex", "CodexStreamProfile", OcsfFixtures.CODEX_STREAM, "stream.jsonl", "Codex CLI exec stream 0.160.1 (labelled synthetic fixture)", "codex-cli/exec-json-stream/0.160.1"),
            new Route("codex", "CodexStreamProfile", OcsfFixtures.LIVE_CODEX, "stream.jsonl", "Codex CLI exec stream 0.160.1 stdout (sanitized live recording)", "codex-cli/exec-json-stream/0.160.1"),
            new Route("claude", "ClaudeStreamProfile", OcsfFixtures.LIVE_CANCELLED, "stream.jsonl", "Claude Code stream 2.1.304 stdout (sanitized live cancelled recording)", "claude-code/stream-json/2.1.304-stdout"),
            new Route("codex", "CodexStreamProfile", OcsfFixtures.LIVE_CODEX_CANCELLED, "stream.jsonl", "Codex CLI exec stream 0.160.1 stdout (sanitized live cancelled recording)", "codex-cli/exec-json-stream/0.160.1"),
            new Route("claude", "ClaudeStreamProfile", OcsfFixtures.INTERRUPTED, "stream.jsonl", "Claude Code stream 2.1.299 sdk (interrupted prefix of the real fixture)", "claude-code/stream-json/2.1.299-sdk"));

    /** A coverage-table column: one admitted CLI/version/profile/route, or the historical sdk route, labelled so. */
    record Column(String entry, String label, String support) {
    }

    static final List<Column> COLUMNS = List.of(
            new Column("claude-code/stream-json/2.1.304-stdout", "Claude Code 2.1.304, claude_stream, stdout", "admitted"),
            new Column("codex-cli/exec-json-stream/0.160.1", "Codex CLI 0.160.1, codex_stream, stdout", "admitted"),
            new Column("claude-code/stream-json/2.1.299-sdk", "Claude Code 2.1.299, claude_stream, sdk (historical evidence, retired route)", "historical"));

    static List<Route> recordings(String entry) {
        return ROUTES.stream().filter(r -> r.entry().equals(entry)).toList();
    }

    @Test
    void everyCatalogReferenceResolvesInJava(@TempDir Path work) throws IOException {
        Json catalog = catalogDocument();
        List<Json> entries = catalog.member("concepts").elements();
        SchemaCatalog schema = FieldDispositionTest.schema(work);
        Map<String, Json> contractsByUid = contractsByUid();
        Set<String> compatibilityEntries = compatibilityEntries();
        List<Check> reported = reportedChecks(work);
        Set<String> reportedProperties = new TreeSet<>();
        reported.forEach(c -> reportedProperties.add(c.property()));
        Set<String> semanticOperations = semanticOperations();
        List<String> problems = new ArrayList<>(scopeProblems(catalog, reported));
        Set<String> ids = new TreeSet<>();
        for (Json e : entries) {
            String id = SchemaCatalog.text(e, "id");
            if (!ids.add(id)) {
                problems.add("duplicate id " + id);
            }
            for (String k : List.of("id", "concept", "question", "foundations", "ocsf", "native_evidence", "mapping", "oracle", "views", "tests", "disposition", "decision", "notes", "observations")) {
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
                if (!Files.isRegularFile(TCK_MAIN.resolve(profile + ".java")) || !CatalogCoverage.PROFILE_NAMES.containsKey(profile)) {
                    problems.add(id + ": oracle profile " + profile + " is not a class of this package");
                }
                String scenario = SchemaCatalog.text(o, "scenario");
                if (scenario != null && !CatalogCoverage.knownScenario(scenario)) {
                    problems.add(id + ": oracle scenario " + scenario + " is not a known native scenario");
                }
                for (Json p : o.member("properties").elements()) {
                    if (!reportedProperties.contains(p.text())) {
                        problems.add(id + ": oracle property " + p.text() + " was not reported by the oracle in this run");
                    }
                }
            }
            for (Json n : e.member("native_evidence").elements()) {
                String route = SchemaCatalog.text(n, "route");
                if (route == null) {
                    continue; // unscoped history: kept for review, never counted as availability on a route
                }
                if (!compatibilityEntries.contains(route)) {
                    problems.add(id + ": availability claim names " + route + ", which is not a compatibility entry");
                    continue;
                }
                problems.addAll(vendorBasisProblems(id, route, n));
                String refuted = CatalogCoverage.availabilityRefuted(n, recordings(route), work.resolve("claims").resolve(id));
                if (refuted != null) {
                    problems.add(id + ": availability claim on " + route + " (" + SchemaCatalog.text(n, "availability") + ") is refuted: " + refuted);
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
        if (!ids.containsAll(INITIAL_CONCEPTS)) {
            Set<String> lost = new TreeSet<>(INITIAL_CONCEPTS);
            lost.removeAll(ids);
            problems.add("initial concepts removed: " + lost);
        }
        assertThat(problems).isEmpty();
    }

    /**
     * On a route with a pinned vendor inventory an availability claim rests on the vendor source, not on recordings
     * alone: {@code absent} names one of the inventory's projection omissions, {@code supported} names the declared
     * families that carry the concept while Journal does not normalize it (a family may be normalized for another concept, as
     * a declined command is normalized as an operation state, not as a permission decision).
     */
    static List<String> vendorBasisProblems(String id, String route, Json claim) {
        List<String> problems = new ArrayList<>();
        for (VendorStreamInventoryTest.Profile p : VendorStreamInventoryTest.PROFILES) {
            com.fasterxml.jackson.databind.JsonNode inv = VendorStreamInventoryTest.inventory(p);
            if (!route.equals(inv.path("compatibility_entry").asText())) {
                continue;
            }
            String availability = SchemaCatalog.text(claim, "availability");
            if ("absent".equals(availability)) {
                String omission = SchemaCatalog.text(claim, "vendor_omission");
                boolean found = false;
                for (com.fasterxml.jackson.databind.JsonNode o : inv.path("omitted_by_projection")) {
                    found |= o.path("what").asText().equals(omission);
                }
                if (!found) {
                    problems.add(id + ": absence on " + route + " names no projection omission of the vendor inventory (" + omission + ")");
                }
            }
            else if ("supported".equals(availability)) {
                Map<String, com.fasterxml.jackson.databind.JsonNode> families = VendorStreamInventoryTest.families(inv);
                if (claim.member("vendor_families") == null || claim.member("vendor_families").elements().isEmpty()) {
                    problems.add(id + ": a supported claim on " + route + " names its vendor families");
                }
                else {
                    for (Json v : claim.member("vendor_families").elements()) {
                        com.fasterxml.jackson.databind.JsonNode f = families.get(v.text());
                        if (f == null) {
                            problems.add(id + ": vendor family " + v.text() + " is not declared by the inventory");
                        }
                    }
                }
            }
            else {
                problems.add(id + ": availability " + availability + " on " + route + " is neither absent nor supported");
            }
        }
        return problems;
    }

    /**
     * Each property the oracle reported is reported at the scope the catalog declares for it: a core-scoped property
     * under a bare core label ({@code main}, {@code child:<id>}), a root-scoped one under {@code main} only, every
     * other property under {@code <core>/<artifact>}. A wrong declaration would change which owners the summary owes.
     */
    static List<String> scopeProblems(Json catalog, List<Check> reported) {
        List<String> problems = new ArrayList<>();
        Set<String> seen = new TreeSet<>();
        for (Check c : reported) {
            String scope = CatalogCoverage.scope(catalog, c.property());
            boolean bare = !c.owner().contains("/");
            boolean ok = switch (scope) {
                case "root" -> "main".equals(c.owner());
                case "core" -> bare;
                default -> !bare;
            };
            if (!ok && seen.add(c.property() + " " + c.owner())) {
                problems.add("property " + c.property() + " is declared " + scope + "-scoped but was reported for owner " + c.owner());
            }
        }
        return problems;
    }

    @Test
    void theCommonConceptMatrixIsDerivedFromTheEntriesAndThisRunsOracleResultsWithStatesKeptSeparate(@TempDir Path work) throws IOException {
        Json catalog = catalogDocument();
        Map<Route, Path> fixtures = new LinkedHashMap<>();
        Map<Route, OracleReport> reports = new LinkedHashMap<>();
        for (Route r : ROUTES) {
            Path fixture = OcsfFixtures.copy(r.fixture(), work.resolve("m"));
            fixtures.put(r, fixture);
            reports.put(r, OcsfFixtures.verify(fixture));
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        Map<String, Map<String, Integer>> totals = new TreeMap<>();
        List<String> failures = new ArrayList<>();
        for (Json e : catalog.member("concepts").elements()) {
            String id = SchemaCatalog.text(e, "id");
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", id);
            row.put("concept", SchemaCatalog.text(e, "concept"));
            row.put("disposition", SchemaCatalog.text(e, "disposition"));
            Map<String, Object> cells = new LinkedHashMap<>();
            for (Column col : COLUMNS) {
                Map<String, String> perRecording = new LinkedHashMap<>();
                for (Route r : recordings(col.entry())) {
                    perRecording.put(r.fixture(), CatalogCoverage.state(catalog, e, r, fixtures.get(r), reports.get(r)));
                }
                String state = combine(perRecording);
                if (state.startsWith("defective") || state.startsWith("blocked")) {
                    failures.add(id + " on " + col.entry() + ": " + state);
                }
                totals.computeIfAbsent(col.label(), k -> new TreeMap<>()).merge(word(state), 1, Integer::sum);
                Map<String, Object> cell = new LinkedHashMap<>();
                cell.put("state", state);
                cell.put("recordings", perRecording);
                cells.put(col.entry(), cell);
            }
            row.put("routes", cells);
            rows.add(row);
        }
        Path out = outputDirectory();
        Files.writeString(out.resolve("CLI-COVERAGE.md"), coverageMarkdown(rows, totals), StandardCharsets.UTF_8);
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("kind", "agent-journal/cli-coverage");
        doc.put("derived_by", "MappingCatalogTest with CatalogCoverage (Java; oracle outcomes of this run against independently owed results; no value matching)");
        doc.put("catalog_version", SchemaCatalog.text(catalog, "version"));
        List<Map<String, Object>> columns = new ArrayList<>();
        for (Column c : COLUMNS) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("entry", c.entry());
            m.put("label", c.label());
            m.put("support", c.support());
            m.put("recordings", recordings(c.entry()).stream().map(r -> r.fixture() + ": " + r.label()).toList());
            columns.add(m);
        }
        doc.put("columns", columns);
        doc.put("rows", rows);
        doc.put("totals", totals);
        Files.writeString(out.resolve("cli-coverage.json"), PRETTY.writeValueAsString(doc) + "\n", StandardCharsets.UTF_8);
        assertThat(failures).isEmpty();
        Set<String> seen = new TreeSet<>();
        totals.values().forEach(m -> seen.addAll(m.keySet()));
        assertThat(seen).contains("verified", "retained-only", "unavailable-at-source", "unexercised");
    }

    /**
     * One route's state from its recordings' states: a defect, then a missing result, then an unresolved check on any
     * recording decides; otherwise verified on any recording, else retained-only, else unavailable-at-source when every
     * recording says so, else unexercised.
     */
    static String combine(Map<String, String> perRecording) {
        for (String prefix : List.of("defective", "blocked", "unresolved")) {
            for (Map.Entry<String, String> e : perRecording.entrySet()) {
                if (e.getValue().startsWith(prefix)) {
                    return e.getValue() + " on " + e.getKey();
                }
            }
        }
        List<String> exercising = new ArrayList<>();
        String unsupported = null;
        for (Map.Entry<String, String> e : perRecording.entrySet()) {
            if (e.getValue().startsWith("unsupported")) {
                unsupported = e.getValue().substring(0, e.getValue().indexOf("; retained whole") + "; retained whole, not normalized".length());
                if (!e.getValue().contains("no occurrence")) {
                    exercising.add(e.getKey());
                }
            }
        }
        if (unsupported != null) {
            return unsupported + "; " + (exercising.isEmpty() ? "unexercised on the route's " + perRecording.size() + " recordings" : "occurs on " + String.join(", ", exercising)) + ")";
        }
        long verified = perRecording.values().stream().filter("verified"::equals).count();
        if (verified > 0) {
            return "verified (" + verified + " of " + perRecording.size() + " recordings)";
        }
        for (String s : perRecording.values()) {
            if (s.startsWith("retained-only")) {
                return s;
            }
        }
        if (!perRecording.isEmpty() && perRecording.values().stream().allMatch("unavailable-at-source"::equals)) {
            return "unavailable-at-source (" + perRecording.size() + " recordings)";
        }
        return perRecording.isEmpty() ? "unexercised (no recording)" : perRecording.values().iterator().next();
    }

    /** Concept id to compatibility entry to combined route state, over every committed recording, for every column. */
    static Map<String, Map<String, String>> routeStates(Json catalog, Path work) {
        Map<Route, Path> fixtures = new LinkedHashMap<>();
        Map<Route, OracleReport> reports = new LinkedHashMap<>();
        for (Route r : ROUTES) {
            Path fixture = OcsfFixtures.copy(r.fixture(), work.resolve("states"));
            fixtures.put(r, fixture);
            reports.put(r, OcsfFixtures.verify(fixture));
        }
        Map<String, Map<String, String>> out = new LinkedHashMap<>();
        for (Json e : catalog.member("concepts").elements()) {
            Map<String, String> byRoute = new LinkedHashMap<>();
            for (Column col : COLUMNS) {
                Map<String, String> perRecording = new LinkedHashMap<>();
                for (Route r : recordings(col.entry())) {
                    perRecording.put(r.fixture(), CatalogCoverage.state(catalog, e, r, fixtures.get(r), reports.get(r)));
                }
                byRoute.put(col.entry(), combine(perRecording));
            }
            out.put(SchemaCatalog.text(e, "id"), byRoute);
        }
        return out;
    }

    static String word(String state) {
        int cut = state.indexOf(' ');
        String w = cut < 0 ? state : state.substring(0, cut);
        return w.endsWith(":") ? w.substring(0, w.length() - 1) : w;
    }

    @SuppressWarnings("unchecked")
    static String coverageMarkdown(List<Map<String, Object>> rows, Map<String, Map<String, Integer>> totals) {
        StringBuilder md = new StringBuilder("# Journal CLI coverage (from the mapping catalog)\n\n");
        md.append("Generated by `MappingCatalogTest` with `CatalogCoverage` from the reviewed catalog entries and the independent oracle's results on every committed recording of each route, in the same test run. ")
                .append("Each concept owes its listed oracle properties for every owner the frozen expected sources establish (root and child cores, at the property's declared scope), with an occurrence of its native scenario. ")
                .append("States: verified; retained-only (retention evidence held); unavailable-at-source (a route-scoped absence claim that holds on the route's recordings); unexercised (every owed result present, no occurrence); ")
                .append("unresolved (an UNSUPPORTED/UNRESOLVED result, or no independent check or established availability for the route). A defect or a missing owed result fails the generating test. ")
                .append("A finite corpus: a state describes the named recordings, not every version, route or future behavior. Do not edit; regenerate.\n\n");
        md.append("| Concept | Disposition |");
        COLUMNS.forEach(c -> md.append(' ').append(c.label()).append(" |"));
        md.append("\n|---|---|");
        COLUMNS.forEach(c -> md.append("---|"));
        md.append('\n');
        for (Map<String, Object> row : rows) {
            md.append("| ").append(row.get("concept")).append(" (`").append(row.get("id")).append("`) | ").append(row.get("disposition")).append(" |");
            Map<String, Map<String, Object>> cells = (Map<String, Map<String, Object>>) row.get("routes");
            for (Column c : COLUMNS) {
                md.append(' ').append(String.valueOf(cells.get(c.entry()).get("state")).replace("|", "\\|")).append(" |");
            }
            md.append('\n');
        }
        md.append("\n## Recordings per route\n\n");
        for (Column c : COLUMNS) {
            md.append("- ").append(c.label()).append(" (").append(c.support()).append("): ")
                    .append(String.join("; ", recordings(c.entry()).stream().map(r -> "`" + r.fixture() + "` " + r.label()).toList())).append('\n');
        }
        md.append("\n## Totals per route\n\n");
        totals.forEach((k, v) -> md.append("- ").append(k).append(": ").append(v).append('\n'));
        return md.toString();
    }

    /**
     * The summary of one concept on one route's committed fixture ({@code fixture}: the copied fixture directory, whose
     * {@code native/} holds the frozen expected sources), by {@link CatalogCoverage#state}.
     */
    static String summarize(Json entry, Route route, Path fixture, OracleReport report) {
        return CatalogCoverage.state(catalogDocument(), entry, route, fixture, report);
    }

    /** The whole catalog document (declared property scopes and the concepts). */
    static Json catalogDocument() {
        return NeutralJson.readDocument(Path.of(MappingCatalogTest.class.getResource("/ocsf/mapping-catalog/mapping-catalog.json").getPath().replace("%20", " ")));
    }

    /** The catalog's concept entries. */
    static List<Json> catalog() {
        return catalogDocument().member("concepts").elements();
    }

    static Path outputDirectory() throws IOException {
        Path out = Path.of(System.getProperty("basedir", ".")).resolve("target").resolve("mapping-catalog");
        Files.createDirectories(out);
        return out;
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

    static Set<String> compatibilityEntries() {
        Set<String> out = new TreeSet<>();
        for (Json e : NeutralJson.readDocument(COMPATIBILITY).member("entries").elements()) {
            out.add(SchemaCatalog.text(e, "id"));
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

    /** Every check the oracle reports on the committed fixtures that exercise its properties (stream routes, rollout, pair transcript). */
    static List<Check> reportedChecks(Path work) {
        List<Check> out = new ArrayList<>();
        for (String fixture : List.of(OcsfFixtures.CHILD, OcsfFixtures.CODEX, OcsfFixtures.CODEX_STREAM, OcsfFixtures.INTERRUPTED, OcsfFixtures.LIVE_FEATURES,
                OcsfFixtures.LIVE_DENIAL, OcsfFixtures.LIVE_CODEX)) {
            out.addAll(OcsfFixtures.verify(OcsfFixtures.copy(fixture, work.resolve("p"))).checks());
        }
        // the pair fixture reports the transcript-side properties (reconstruction of parsed transcript records, contexts)
        out.addAll(OcsfFixtures.verify(OcsfFixtures.copy("syn-claude-pair-transcript", work.resolve("p"))).checks());
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
}
