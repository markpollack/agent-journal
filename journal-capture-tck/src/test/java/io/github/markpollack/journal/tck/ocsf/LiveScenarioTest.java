package io.github.markpollack.journal.tck.ocsf;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The shared live scenario roster ({@code ocsf/live-scenarios/roster.json}: behavior ids LIVE-01..10 and their
 * applicability per compatibility entry) and the attempts made against it ({@code results.json}). Every behavior a
 * result calls {@code observed} carries raw predicates, which this test evaluates on the attempt's committed native
 * recording with the oracle's own source reading ({@link CatalogCoverage#occurrences}); an untriggered behavior labelled
 * observed fails, as does an attempt whose evidence class contradicts its fixture (a live claim needs the frozen live
 * process record; a synthetic fixture says so in its spec). Only live attempts make a behavior live-observed; synthetic
 * and replay attempts are reported as such. Writes {@code target/mapping-catalog/LIVE-SCENARIOS.md} and {@code .json}.
 */
class LiveScenarioTest {

    static final ObjectMapper MAPPER = new ObjectMapper();
    static final Path DIR = FieldDispositionTest.PROJECT.resolve("journal-capture-tck/src/test/resources/ocsf/live-scenarios");
    static final Path FIXTURES = FieldDispositionTest.PROJECT.resolve("journal-capture-tck/src/test/resources/ocsf/fixtures");
    static final Set<String> STATUSES = Set.of("observed", "attempted-not-triggered", "not-run", "blocked");
    static final Set<String> CLASSES = Set.of("live", "replay", "synthetic");

    static JsonNode read(String name) {
        try {
            return MAPPER.readTree(DIR.resolve(name).toFile());
        }
        catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static JsonNode readFile(Path p) {
        try {
            return MAPPER.readTree(p.toFile());
        }
        catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** The fixture's own evidence class: live when its frozen manifest records the live process, synthetic when its spec says so. */
    static String fixtureClass(String fixture) {
        Path dir = FIXTURES.resolve(fixture);
        if (!Files.isDirectory(dir)) {
            return null;
        }
        if (!readFile(dir.resolve("native/expected-sources.json")).path("frozen").path("live_process").isMissingNode()) {
            return "live";
        }
        return readFile(dir.resolve("spec.json")).has("synthetic") ? "synthetic" : "replay";
    }

    static MappingCatalogTest.Route route(String fixture, String entry, Path copy) {
        String profile = readFile(OcsfFixtures.natives(copy).resolve("expected-sources.json")).path("sources").get(0).path("profile").asText();
        String cls = CatalogCoverage.PROFILE_NAMES.entrySet().stream().filter(e -> e.getValue().equals(profile)).map(Map.Entry::getKey).findFirst().orElseThrow();
        return new MappingCatalogTest.Route(entry.split("/")[0], cls, fixture, "stream.jsonl", fixture, entry);
    }

    static List<String> rosterProblems(JsonNode roster) {
        List<String> problems = new ArrayList<>();
        Set<String> ids = new TreeSet<>();
        roster.path("behaviors").forEach(b -> {
            if (!ids.add(b.path("id").asText())) {
                problems.add("behavior " + b.path("id").asText() + " listed twice");
            }
        });
        Set<String> routes = new TreeSet<>();
        for (JsonNode a : roster.path("applicability")) {
            String route = a.path("route").asText();
            routes.add(route);
            Map<String, Integer> seen = new TreeMap<>();
            a.path("applicable").forEach(x -> seen.merge(x.asText(), 1, Integer::sum));
            a.path("conditional").forEach(x -> seen.merge(x.asText(), 1, Integer::sum));
            for (JsonNode n : a.path("not_applicable")) {
                seen.merge(n.path("behavior").asText(), 1, Integer::sum);
                if (n.path("basis").asText().isBlank()) {
                    problems.add(route + ": " + n.path("behavior").asText() + " is not applicable without a version- and route-specific basis");
                }
            }
            for (String id : ids) {
                if (seen.getOrDefault(id, 0) != 1) {
                    problems.add(route + ": behavior " + id + " is classified " + seen.getOrDefault(id, 0) + " times (exactly once: applicable, conditional or not applicable)");
                }
            }
        }
        for (MappingCatalogTest.Column c : MappingCatalogTest.COLUMNS) {
            if ("admitted".equals(c.support()) && !routes.contains(c.entry())) {
                problems.add("admitted route " + c.entry() + " has no roster applicability");
            }
        }
        return problems;
    }

    static List<String> resultProblems(JsonNode roster, JsonNode results, Path work) {
        List<String> problems = new ArrayList<>();
        Set<String> behaviors = new TreeSet<>();
        roster.path("behaviors").forEach(b -> behaviors.add(b.path("id").asText()));
        Set<String> routes = new TreeSet<>();
        roster.path("applicability").forEach(a -> routes.add(a.path("route").asText()));
        Set<String> ids = new TreeSet<>();
        for (JsonNode a : results.path("attempts")) {
            String id = a.path("id").asText();
            if (!ids.add(id)) {
                problems.add("attempt " + id + " listed twice");
            }
            String route = a.path("route").asText();
            if (!routes.contains(route)) {
                problems.add(id + ": route " + route + " has no roster applicability");
            }
            String cls = a.path("evidence_class").asText();
            String fixture = a.path("fixture").asText(null);
            if (!CLASSES.contains(cls)) {
                problems.add(id + ": evidence class " + cls);
            }
            String actual = fixture == null ? null : fixtureClass(fixture);
            Path copy = null;
            if (fixture != null && actual == null) {
                problems.add(id + ": fixture " + fixture + " is not committed");
            }
            else if (fixture != null) {
                if (!cls.equals(actual)) {
                    problems.add(id + ": declared " + cls + " but fixture " + fixture + " is " + actual);
                }
                copy = OcsfFixtures.copy(fixture, work.resolve(id));
            }
            for (JsonNode b : a.path("behaviors")) {
                String beh = b.path("behavior").asText();
                String status = b.path("status").asText();
                if (!behaviors.contains(beh) || !STATUSES.contains(status)) {
                    problems.add(id + ": " + beh + " " + status + " is not a roster behavior and status");
                    continue;
                }
                if (("observed".equals(status) || "attempted-not-triggered".equals(status)) && (copy == null || b.path("predicates").isEmpty())) {
                    problems.add(id + ": " + beh + " " + status + " needs a committed recording and raw predicates");
                    continue;
                }
                if ("blocked".equals(status) && b.path("note").asText().isBlank()) {
                    problems.add(id + ": " + beh + " blocked without its named blocker");
                }
                for (JsonNode p : b.path("predicates")) {
                    String scenario = p.path("scenario").asText();
                    if (!CatalogCoverage.knownScenario(scenario)) {
                        problems.add(id + ": " + beh + ": unknown predicate " + scenario);
                        continue;
                    }
                    int n = CatalogCoverage.occurrences(scenario, route(fixture, route, copy), OcsfFixtures.natives(copy), ExpectedSources.beside(OcsfFixtures.natives(copy)));
                    int min = p.path("min").asInt(1);
                    if ("observed".equals(status) && n < min) {
                        problems.add(id + ": " + beh + " labelled observed but " + scenario + " occurs " + n + " time(s) on " + fixture + " (needs " + min + ")");
                    }
                    if ("attempted-not-triggered".equals(status) && n >= min) {
                        problems.add(id + ": " + beh + " labelled not triggered but " + scenario + " occurs " + n + " time(s) on " + fixture);
                    }
                }
                for (JsonNode t : b.path("assertions")) {
                    if (!FieldDispositionTest.testExists(t.asText())) {
                        problems.add(id + ": " + beh + ": assertion " + t.asText() + " not found in the test sources");
                    }
                }
            }
        }
        return problems;
    }

    @Test
    void theRosterAndEveryAttemptAreConsistentWithTheirRecordings(@TempDir Path work) {
        assertThat(rosterProblems(read("roster.json"))).isEmpty();
        assertThat(resultProblems(read("roster.json"), read("results.json"), work)).isEmpty();
    }

    @Test
    void anUntriggeredBehaviorLabelledObservedAndAMislabelledEvidenceClassAreDetected(@TempDir Path work) {
        JsonNode roster = read("roster.json");
        // the feature recording carries no redacted reasoning: calling LIVE-10 observed on it fails
        ObjectNode untriggered = read("results.json").deepCopy();
        ObjectNode first = (ObjectNode) untriggered.path("attempts").get(0);
        ((ArrayNode) first.path("behaviors")).addObject().put("behavior", "LIVE-10").put("status", "observed")
                .putArray("predicates").addObject().put("scenario", "block:redacted_thinking").put("min", 1);
        assertThat(resultProblems(roster, untriggered, work.resolve("a"))).anyMatch(p -> p.contains("LIVE-10 labelled observed") && p.contains("block:redacted_thinking"));
        // a synthetic fixture presented as live fails
        ObjectNode relabelled = read("results.json").deepCopy();
        for (JsonNode a : relabelled.path("attempts")) {
            if ("synthetic".equals(a.path("evidence_class").asText())) {
                ((ObjectNode) a).put("evidence_class", "live");
            }
        }
        assertThat(resultProblems(roster, relabelled, work.resolve("b"))).anyMatch(p -> p.contains("declared live but fixture") && p.contains("synthetic"));
        // a behavior dropped from a route's applicability fails
        ObjectNode dropped = roster.deepCopy();
        ArrayNode applicable = (ArrayNode) dropped.path("applicability").get(0).path("applicable");
        applicable.remove(applicable.size() - 1);
        assertThat(rosterProblems(dropped)).anyMatch(p -> p.contains("classified 0 times"));
        // the valid control holds
        assertThat(resultProblems(roster, read("results.json"), work.resolve("c"))).isEmpty();
    }

    @Test
    void theScenarioCoverageIsGenerated() throws IOException {
        JsonNode roster = read("roster.json");
        JsonNode results = read("results.json");
        List<Map<String, Object>> rows = new ArrayList<>();
        StringBuilder md = new StringBuilder("# Live scenario coverage\n\nGenerated by `LiveScenarioTest` from `ocsf/live-scenarios/roster.json` and `results.json`; do not edit. ")
                .append("Per route and behavior: live-observed (a live attempt's raw predicates hold on its committed sanitized recording), synthetic-only or replay-only, ")
                .append("attempted-not-triggered, blocked, not-run, or not applicable with its basis. One run can cover several behaviors; each is counted once per attempt.\n\n");
        md.append("| Route | Behavior | Applicability | State | Attempts |\n|---|---|---|---|---|\n");
        for (JsonNode a : roster.path("applicability")) {
            String route = a.path("route").asText();
            for (JsonNode beh : roster.path("behaviors")) {
                String id = beh.path("id").asText();
                String applicability = contains(a.path("applicable"), id) ? "applicable" : contains(a.path("conditional"), id) ? "conditional" : "not applicable";
                String basis = "";
                for (JsonNode n : a.path("not_applicable")) {
                    if (id.equals(n.path("behavior").asText())) {
                        basis = n.path("basis").asText();
                    }
                }
                Map<String, Set<String>> byClass = new TreeMap<>();
                List<String> attempts = new ArrayList<>();
                for (JsonNode at : results.path("attempts")) {
                    if (!route.equals(at.path("route").asText())) {
                        continue;
                    }
                    for (JsonNode b : at.path("behaviors")) {
                        if (id.equals(b.path("behavior").asText())) {
                            String status = b.path("status").asText();
                            byClass.computeIfAbsent(status, k -> new TreeSet<>()).add(at.path("evidence_class").asText());
                            attempts.add(at.path("id").asText() + " (" + at.path("evidence_class").asText() + ", " + status + ")");
                        }
                    }
                }
                String state = !basis.isEmpty() ? "not applicable: " + basis
                        : byClass.getOrDefault("observed", Set.of()).contains("live") ? "live-observed"
                        : byClass.containsKey("observed") ? String.join("/", byClass.get("observed")) + "-only"
                        : byClass.containsKey("attempted-not-triggered") ? "attempted-not-triggered"
                        : byClass.containsKey("blocked") ? "blocked" : "not-run";
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("route", route);
                row.put("behavior", id);
                row.put("applicability", applicability);
                row.put("state", state);
                row.put("attempts", attempts);
                rows.add(row);
                md.append("| `").append(route).append("` | ").append(id).append(' ').append(beh.path("name").asText()).append(" | ").append(applicability).append(" | ")
                        .append(FoundationComparisonTest.cell(state)).append(" | ").append(String.join("; ", attempts)).append(" |\n");
            }
        }
        Path out = MappingCatalogTest.outputDirectory();
        Files.writeString(out.resolve("LIVE-SCENARIOS.md"), md.toString(), StandardCharsets.UTF_8);
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("kind", "agent-journal/live-scenario-coverage");
        doc.put("rows", rows);
        Files.writeString(out.resolve("live-scenarios.json"), MappingCatalogTest.PRETTY.writeValueAsString(doc) + "\n", StandardCharsets.UTF_8);
        assertThat(rows).isNotEmpty();
    }

    static boolean contains(JsonNode array, String id) {
        for (JsonNode x : array) {
            if (id.equals(x.asText())) {
                return true;
            }
        }
        return false;
    }
}
