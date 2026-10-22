package io.github.markpollack.journal.tck.ocsf;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * FND-1: the mapping catalog as the structured foundation comparison. Each concept carries one correspondence record
 * per pinned foundation (Spring AI release and development, ACP v1, OTel GenAI conventions, OpenInference conventions,
 * Weave's Agents model), Journal's decision, and the observations recorded for tested integrations; shared pins live in
 * {@code foundation-pins.json} and the ledger-row denominator in {@code foundation-inventory.json}.
 *
 * <p>This test validates shape, vocabularies, uniqueness and every cross-reference (foundation ids, integration ids,
 * evidence ids against the inventory rows, inventory rows against concepts, per-source row counts), shows that each
 * kind of corruption is detected, checks the upstream-OCSF projection against the extension's own attributes, and
 * generates under {@code target/mapping-catalog/}: the foundation representation comparison ({@code FOUNDATIONS.md},
 * {@code foundations.json}), the observed preservation comparison ({@code PRESERVATION.md}, {@code preservation.json};
 * its current Journal columns are the CLI coverage states, never hand-entered), the projection ({@code OCSF-PROJECTION.md},
 * {@code ocsf-projection.json}) and the model baseline ({@code MODEL-BASELINE.json}). The CLI coverage table is
 * {@link MappingCatalogTest}'s. A record in JSON is a reviewed claim; only the oracle-backed coverage states are executed
 * evidence.
 */
class FoundationComparisonTest {

    static final ObjectMapper MAPPER = new ObjectMapper();
    static final Path CATALOG_DIR = MappingCatalogTest.PROJECT.resolve("journal-capture-tck/src/test/resources/ocsf/mapping-catalog");
    static final Path EXTENSION = MappingCatalogTest.PROJECT.resolve("prototype/ocsf-acp/extension/agentic_cli");
    static final Path PROJECTION = MappingCatalogTest.PROJECT.resolve("prototype/ocsf-acp/projection/ocsf-1.9.0-projection.json");
    static final Set<String> FAMILIES = Set.of("spring_ai", "acp", "otel_genai", "openinference", "weave");
    static final Pattern COMMIT = Pattern.compile("[0-9a-f]{40}");

    /** The catalog, pins and inventory, as trees that corruption controls can alter. */
    record Documents(JsonNode catalog, JsonNode pins, JsonNode inventory) {
        Documents copy() {
            return new Documents(catalog.deepCopy(), pins.deepCopy(), inventory.deepCopy());
        }
    }

    static Documents documents() {
        return new Documents(read(CATALOG_DIR.resolve("mapping-catalog.json")), read(CATALOG_DIR.resolve("foundation-pins.json")),
                read(CATALOG_DIR.resolve("foundation-inventory.json")));
    }

    static JsonNode read(Path file) {
        try {
            return MAPPER.readTree(file.toFile());
        }
        catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static Set<String> vocabulary(JsonNode catalog, String name) {
        Set<String> out = new TreeSet<>();
        catalog.path("vocabularies").path(name).forEach(v -> out.add(v.asText()));
        return out;
    }

    static boolean blank(JsonNode n) {
        return n == null || n.isNull() || n.isMissingNode() || (n.isTextual() && n.asText().isBlank());
    }

    // ------------------------------------------------------------------------------------------ validation

    /** Every structural, vocabulary, uniqueness and reference problem in the three documents. */
    static List<String> problems(Documents d) {
        List<String> problems = new ArrayList<>();
        Map<String, String> foundations = foundations(d.pins(), problems);
        Map<String, Boolean> integrations = new LinkedHashMap<>();
        for (JsonNode i : d.pins().path("integrations")) {
            if (integrations.put(i.path("id").asText(), i.path("current").asBoolean()) != null) {
                problems.add("duplicate integration " + i.path("id").asText());
            }
        }
        Set<String> inventoryIds = inventory(d, problems);
        Set<String> notes = new TreeSet<>();
        Set<String> ledgers = new TreeSet<>();
        d.inventory().path("sources").forEach(s -> ledgers.add(s.asText()));
        d.pins().path("evidence_sources").forEach(s -> {
            if (!ledgers.contains(s.path("prefix").asText())) {
                notes.add(s.path("prefix").asText());
            }
        });
        Set<String> conceptIds = new TreeSet<>();
        for (JsonNode c : d.catalog().path("concepts")) {
            String id = c.path("id").asText();
            if (!conceptIds.add(id)) {
                problems.add("duplicate concept " + id);
            }
            concept(d, c, foundations, integrations, inventoryIds, ledgers, notes, problems);
        }
        for (JsonNode r : d.inventory().path("rows")) {
            for (String k : List.of("concept", "new_concept")) {
                if (!blank(r.get(k)) && !conceptIds.contains(r.get(k).asText())) {
                    problems.add("inventory row " + r.path("evidence").asText() + ": " + k + " " + r.get(k).asText() + " is not a catalog concept");
                }
            }
        }
        return problems;
    }

    /** Foundation id to family; each pinned to an exact commit, and every family present. */
    static Map<String, String> foundations(JsonNode pins, List<String> problems) {
        Map<String, String> out = new LinkedHashMap<>();
        for (JsonNode f : pins.path("foundations")) {
            String id = f.path("id").asText();
            if (out.put(id, f.path("family").asText()) != null) {
                problems.add("duplicate foundation " + id);
            }
            boolean pinned = Stream.of("revision", "commit", "tag_commit").anyMatch(k -> COMMIT.matcher(f.path(k).asText()).matches());
            if (!pinned) {
                problems.add("foundation " + id + " names no exact 40-hex commit");
            }
        }
        if (!out.values().containsAll(FAMILIES)) {
            problems.add("foundation families " + new TreeSet<>(out.values()) + " do not cover " + FAMILIES);
        }
        return out;
    }

    /** The inventory's evidence ids; each row accounted for exactly once, and each source's row count as pinned. */
    static Set<String> inventory(Documents d, List<String> problems) {
        Set<String> ids = new TreeSet<>();
        Map<String, Integer> counts = new HashMap<>();
        for (JsonNode r : d.inventory().path("rows")) {
            String ev = r.path("evidence").asText();
            if (!ids.add(ev)) {
                problems.add("duplicate inventory row " + ev);
            }
            counts.merge(ev.substring(0, Math.max(0, ev.indexOf(':'))), 1, Integer::sum);
            int accounted = (blank(r.get("concept")) ? 0 : 1) + (blank(r.get("new_concept")) ? 0 : 1) + (blank(r.get("exclusion")) ? 0 : 1);
            boolean inScope = r.path("in_scope").asBoolean();
            if (inScope ? blank(r.get("concept")) && blank(r.get("new_concept")) || !blank(r.get("exclusion")) : accounted != 1 || blank(r.get("exclusion"))) {
                problems.add("inventory row " + ev + " is not accounted for exactly once (in scope: a concept or a new concept; out of scope: one exclusion)");
            }
            if (!blank(r.get("exclusion")) && (blank(r.get("exclusion").get("kind")) || blank(r.get("exclusion").get("reason")))) {
                problems.add("inventory row " + ev + ": an exclusion needs a kind and a reason");
            }
        }
        for (JsonNode s : d.pins().path("evidence_sources")) {
            String prefix = s.path("prefix").asText();
            if (s.has("rows") && counts.getOrDefault(prefix, 0) != s.path("rows").asInt()) {
                problems.add("inventory holds " + counts.getOrDefault(prefix, 0) + " rows of " + prefix + "; its pin says " + s.path("rows").asInt());
            }
        }
        return ids;
    }

    static void concept(Documents d, JsonNode c, Map<String, String> foundations, Map<String, Boolean> integrations, Set<String> inventoryIds,
            Set<String> ledgers, Set<String> notes, List<String> problems) {
        String id = c.path("id").asText();
        String disposition = c.path("disposition").asText();
        if (!vocabulary(d.catalog(), "disposition").contains(disposition)) {
            problems.add(id + ": disposition " + disposition + " not in the vocabulary");
        }
        JsonNode decision = c.path("decision");
        if (!vocabulary(d.catalog(), "concept_decision").contains(decision.path("value").asText()) || blank(decision.get("reason"))) {
            problems.add(id + ": decision needs a value from the vocabulary and a reason");
        }
        if ("pending".equals(disposition) != !blank(decision.get("pending_proposal"))) {
            problems.add(id + ": a pending concept, and only a pending concept, names its pending proposal");
        }
        if (!"pending".equals(disposition) && c.path("ocsf").isEmpty()) {
            problems.add(id + ": a modelled concept names its canonical destinations");
        }
        Map<String, Integer> seen = new TreeMap<>();
        for (JsonNode r : c.path("foundations")) {
            String f = r.path("foundation").asText();
            seen.merge(f, 1, Integer::sum);
            correspondence(d, id, f, r, foundations, inventoryIds, ledgers, notes, problems);
        }
        for (String f : foundations.keySet()) {
            if (seen.getOrDefault(f, 0) != 1) {
                problems.add(id + ": " + seen.getOrDefault(f, 0) + " records for foundation " + f + " (exactly one required; not_researched is explicit)");
            }
        }
        for (JsonNode o : c.path("observations")) {
            String integration = o.path("integration").asText();
            Boolean current = integrations.get(integration);
            if (current == null) {
                problems.add(id + ": observation names unknown integration " + integration);
            }
            else if (current) {
                problems.add(id + ": observation for " + integration + ", whose states are derived from the CLI coverage calculation, not entered");
            }
            if (!vocabulary(d.catalog(), "observation_state").contains(o.path("state").asText())) {
                problems.add(id + ": observation state " + o.path("state").asText() + " not in the vocabulary");
            }
            if (!vocabulary(d.catalog(), "evidence_class").contains(o.path("evidence_class").asText())) {
                problems.add(id + ": observation evidence class " + o.path("evidence_class").asText() + " not in the vocabulary");
            }
            if ("blocked".equals(o.path("evidence_class").asText()) != "blocked".equals(o.path("state").asText())) {
                problems.add(id + ": observation on " + integration + " mixes a blocked state and a non-blocked evidence class");
            }
            if (blank(o.get("item")) || blank(o.get("input"))) {
                problems.add(id + ": observation on " + integration + " names no information item or input");
            }
            evidence(id, o.path("evidence"), inventoryIds, ledgers, notes, problems);
        }
    }

    static void correspondence(Documents d, String id, String f, JsonNode r, Map<String, String> foundations, Set<String> inventoryIds,
            Set<String> ledgers, Set<String> notes, List<String> problems) {
        String where = id + " / " + f;
        if (!foundations.containsKey(f)) {
            problems.add(where + ": not a pinned foundation");
        }
        String status = r.path("status").asText();
        if (!vocabulary(d.catalog(), "foundation_status").contains(status)) {
            problems.add(where + ": status " + status + " not in the vocabulary");
        }
        if (blank(r.get("reason"))) {
            problems.add(where + ": no reason");
        }
        if ("mapped".equals(status)) {
            for (String k : List.of("selector", "locator", "representation", "differences")) {
                if (blank(r.get(k))) {
                    problems.add(where + ": a mapped record needs " + k);
                }
            }
            if (!vocabulary(d.catalog(), "correspondence_decision").contains(r.path("decision").asText())) {
                problems.add(where + ": a mapped record needs a decision from the vocabulary");
            }
        }
        else if (!blank(r.get("decision"))) {
            problems.add(where + ": only a mapped record carries a decision on the foundation's representation");
        }
        if ("no_counterpart".equals(status) && blank(r.get("locator"))) {
            problems.add(where + ": no_counterpart must say where it looked (locator)");
        }
        if (!"not_researched".equals(status) && !vocabulary(d.catalog(), "evidence_class").contains(r.path("evidence_class").asText())) {
            problems.add(where + ": evidence class " + r.path("evidence_class").asText() + " not in the vocabulary");
        }
        evidence(where, r.path("evidence"), inventoryIds, ledgers, notes, problems);
    }

    /** An evidence id is a pinned note, or {@code <ledger>:<row>} naming an inventory row of that ledger. */
    static void evidence(String where, JsonNode ids, Set<String> inventoryIds, Set<String> ledgers, Set<String> notes, List<String> problems) {
        for (JsonNode e : ids) {
            String ev = e.asText();
            int colon = ev.indexOf(':');
            String prefix = colon < 0 ? ev : ev.substring(0, colon);
            if (colon < 0 ? !notes.contains(ev) : !ledgers.contains(prefix) || !inventoryIds.contains(ev)) {
                problems.add(where + ": evidence " + ev + " resolves to no pinned note or inventory row");
            }
        }
    }

    // ------------------------------------------------------------------------------------------ tests

    @Test
    void theFoundationRecordsPinsInventoryAndObservationsAreComplete() {
        Documents d = documents();
        assertThat(problems(d)).isEmpty();
        assertThat(d.catalog().path("concepts").size()).isGreaterThanOrEqualTo(MappingCatalogTest.INITIAL_CONCEPTS.size());
    }

    @Test
    void eachCorruptionIsDetected() {
        Map<String, Consumer<Documents>> corruptions = new LinkedHashMap<>();
        corruptions.put("records for foundation acp.v1", d -> remove(d.catalog().path("concepts").get(0).path("foundations"), "foundation", "acp.v1"));
        corruptions.put("duplicate concept", d -> ((ArrayNode) d.catalog().path("concepts")).add(d.catalog().path("concepts").get(0).deepCopy()));
        corruptions.put("unknown integration", d -> ((ObjectNode) first(d.catalog(), "observations")).put("integration", "weave.plugin-9.9.9"));
        corruptions.put("resolves to no pinned note or inventory row", d -> ((ArrayNode) first(d.catalog(), "observations").path("evidence")).add("weave-refresh-2026-10-19:W999"));
        corruptions.put("not accounted for exactly once", d -> {
            ObjectNode row = (ObjectNode) d.inventory().path("rows").get(0);
            row.put("in_scope", true);
            row.putNull("concept");
            row.putNull("new_concept");
            row.putNull("exclusion");
        });
        corruptions.put("is not a catalog concept", d -> ((ObjectNode) inScopeRow(d)).put("concept", "no-such-concept"));
        corruptions.put("rows of weave-refresh-2026-10-19", d -> ((ArrayNode) d.inventory().path("rows")).remove(0));
        corruptions.put("a mapped record needs selector", d -> ((ObjectNode) mapped(d)).putNull("selector"));
        corruptions.put("names no exact 40-hex commit", d -> ((ObjectNode) d.pins().path("foundations").get(3)).put("commit", "main"));
        corruptions.put("derived from the CLI coverage calculation", d -> ((ObjectNode) first(d.catalog(), "observations")).put("integration", "journal.claude-code-2.1.304-stdout"));
        corruptions.put("only a mapped record carries a decision", d -> ((ObjectNode) unmapped(d)).put("decision", "adopt"));
        Documents clean = documents();
        assertThat(problems(clean)).isEmpty();
        for (Map.Entry<String, Consumer<Documents>> c : corruptions.entrySet()) {
            Documents bad = clean.copy();
            c.getValue().accept(bad);
            assertThat(problems(bad)).as(c.getKey()).anyMatch(p -> p.contains(c.getKey()));
        }
    }

    static void remove(JsonNode array, String key, String value) {
        Iterator<JsonNode> it = array.iterator();
        while (it.hasNext()) {
            if (value.equals(it.next().path(key).asText())) {
                it.remove();
            }
        }
    }

    static JsonNode first(JsonNode catalog, String arrayKey) {
        for (JsonNode c : catalog.path("concepts")) {
            if (!c.path(arrayKey).isEmpty()) {
                return c.path(arrayKey).get(0);
            }
        }
        throw new AssertionError("no concept with " + arrayKey);
    }

    static JsonNode inScopeRow(Documents d) {
        for (JsonNode r : d.inventory().path("rows")) {
            if (r.path("in_scope").asBoolean() && !blank(r.get("concept"))) {
                return r;
            }
        }
        throw new AssertionError("no in-scope row");
    }

    static JsonNode mapped(Documents d) {
        for (JsonNode c : d.catalog().path("concepts")) {
            for (JsonNode r : c.path("foundations")) {
                if ("mapped".equals(r.path("status").asText())) {
                    return r;
                }
            }
        }
        throw new AssertionError("no mapped record");
    }

    static JsonNode unmapped(Documents d) {
        for (JsonNode c : d.catalog().path("concepts")) {
            for (JsonNode r : c.path("foundations")) {
                if ("no_counterpart".equals(r.path("status").asText())) {
                    return r;
                }
            }
        }
        throw new AssertionError("no unmapped record");
    }

    /** Every attribute the extension's classes and objects declare appears exactly once in the projection, with a valid verdict. */
    @Test
    void theProjectionCoversEveryExtensionAttributeOnce() throws IOException {
        JsonNode projection = read(PROJECTION);
        List<String> problems = new ArrayList<>(projectionProblems(projection));
        assertThat(projection.path("extension").path("version").asText()).isEqualTo(read(EXTENSION.resolve("extension.json")).path("version").asText());
        assertThat(problems).isEmpty();
        // corruption controls: a dropped row and a no_counterpart verdict naming an upstream path are both caught
        ObjectNode dropped = projection.deepCopy();
        ((ArrayNode) dropped.path("rows")).remove(0);
        assertThat(projectionProblems(dropped)).anyMatch(p -> p.contains("missing from the projection"));
        ObjectNode contradictory = projection.deepCopy();
        for (JsonNode r : contradictory.path("rows")) {
            if ("no_counterpart".equals(r.path("verdict").asText())) {
                ((ObjectNode) r).put("upstream", "base_event.message");
                break;
            }
        }
        assertThat(projectionProblems(contradictory)).anyMatch(p -> p.contains("upstream path exactly when"));
    }

    static List<String> projectionProblems(JsonNode projection) throws IOException {
        List<String> problems = new ArrayList<>();
        Set<String> declared = new TreeSet<>();
        try (Stream<Path> s = Stream.concat(Files.list(EXTENSION.resolve("objects")), Files.list(EXTENSION.resolve("events/agent_execution")))) {
            for (Path p : s.filter(x -> x.toString().endsWith(".json")).toList()) {
                JsonNode def = read(p);
                def.path("attributes").fieldNames().forEachRemaining(a -> declared.add(def.path("name").asText() + "." + a));
            }
        }
        Map<String, Integer> rows = new TreeMap<>();
        for (JsonNode r : projection.path("rows")) {
            rows.merge(r.path("ours").asText(), 1, Integer::sum);
            String verdict = r.path("verdict").asText();
            if (!Set.of("maps", "maps_with_loss", "no_counterpart").contains(verdict)) {
                problems.add(r.path("ours").asText() + ": verdict " + verdict);
            }
            if ("no_counterpart".equals(verdict) != blank(r.get("upstream"))) {
                problems.add(r.path("ours").asText() + ": names an upstream path exactly when the verdict is not no_counterpart");
            }
            if (!"maps".equals(verdict) && blank(r.get("caveat"))) {
                problems.add(r.path("ours").asText() + ": a lossy or missing correspondence states its caveat");
            }
        }
        for (String a : declared) {
            if (!rows.containsKey(a)) {
                problems.add(a + " is declared by the extension but missing from the projection");
            }
        }
        rows.forEach((k, n) -> {
            if (n > 1) {
                problems.add(k + " appears " + n + " times");
            }
        });
        return problems;
    }

    // ------------------------------------------------------------------------------------------ generation

    @Test
    void theFoundationAndPreservationComparisonsTheProjectionAndTheBaselineAreGenerated(@TempDir Path work) throws IOException {
        Documents d = documents();
        assertThat(problems(d)).isEmpty();
        Path out = MappingCatalogTest.outputDirectory();
        Map<String, Map<String, String>> coverage = MappingCatalogTest.routeStates(MappingCatalogTest.catalogDocument(), work);
        Files.writeString(out.resolve("FOUNDATIONS.md"), foundationsMarkdown(d), StandardCharsets.UTF_8);
        Files.writeString(out.resolve("foundations.json"), MappingCatalogTest.PRETTY.writeValueAsString(foundationsJson(d)) + "\n", StandardCharsets.UTF_8);
        Map<String, Object> preservation = preservationJson(d, coverage);
        Files.writeString(out.resolve("PRESERVATION.md"), preservationMarkdown(d, preservation), StandardCharsets.UTF_8);
        Files.writeString(out.resolve("preservation.json"), MappingCatalogTest.PRETTY.writeValueAsString(preservation) + "\n", StandardCharsets.UTF_8);
        JsonNode projection = read(PROJECTION);
        Files.writeString(out.resolve("OCSF-PROJECTION.md"), projectionMarkdown(projection), StandardCharsets.UTF_8);
        Files.writeString(out.resolve("ocsf-projection.json"), MappingCatalogTest.PRETTY.writeValueAsString(projection) + "\n", StandardCharsets.UTF_8);
        Files.writeString(out.resolve("MODEL-BASELINE.json"), MappingCatalogTest.PRETTY.writeValueAsString(baseline(d, coverage)) + "\n", StandardCharsets.UTF_8);
        // the current Journal columns come from the coverage calculation for every concept, pending concepts included
        assertThat(coverage).hasSize(d.catalog().path("concepts").size());
        assertThat(Files.readString(out.resolve("PRESERVATION.md"))).contains("journal.claude-code-2.1.304-stdout");
    }

    static List<String> foundationIds(Documents d) {
        List<String> out = new ArrayList<>();
        d.pins().path("foundations").forEach(f -> out.add(f.path("id").asText()));
        return out;
    }

    static Map<String, Object> foundationsJson(Documents d) {
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("kind", "agent-journal/foundation-comparison");
        doc.put("derived_by", "FoundationComparisonTest from mapping-catalog.json and foundation-pins.json");
        doc.put("catalog_version", d.catalog().path("version").asText());
        doc.put("foundations", d.pins().path("foundations"));
        List<Map<String, Object>> rows = new ArrayList<>();
        for (JsonNode c : d.catalog().path("concepts")) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", c.path("id").asText());
            row.put("concept", c.path("concept").asText());
            row.put("journal_decision", c.path("decision"));
            List<String> destinations = new ArrayList<>();
            c.path("ocsf").forEach(o -> destinations.add(o.path("path").asText()));
            row.put("journal_destinations", destinations);
            row.put("foundations", c.path("foundations"));
            rows.add(row);
        }
        doc.put("rows", rows);
        return doc;
    }

    static String cell(String s) {
        return s.replace("|", "\\|").replace("\n", " ");
    }

    static String foundationsMarkdown(Documents d) {
        List<String> ids = foundationIds(d);
        StringBuilder md = new StringBuilder("# Foundation representation comparison\n\n");
        md.append("Generated by `FoundationComparisonTest` from the mapping catalog and `foundation-pins.json`; do not edit. One row per stable concept id; one column per pinned foundation ")
                .append("(a standard or common model, never a CLI), with Spring AI's release and development pins kept apart, and Journal's decision and destinations. ")
                .append("A cell gives the correspondence status, the foundation's field or selector, and Journal's decision on that representation (`adopt`, `adapt`, `vendor_detail`, `defer`, `not_applicable`); ")
                .append("representation, identity, ordering, scope, absence, differences, locator, evidence class and evidence ids are in `foundations.json`. ")
                .append("These records are reviewed source and specification claims, not executed tests.\n\n");
        md.append("## Pins\n\n| Foundation | Kind | Revision |\n|---|---|---|\n");
        for (JsonNode f : d.pins().path("foundations")) {
            String rev = Stream.of("revision", "commit", "tag_commit").map(k -> f.path(k).asText()).filter(v -> !v.isEmpty()).findFirst().orElse("");
            md.append("| `").append(f.path("id").asText()).append("` ").append(cell(f.path("name").asText())).append(" | ").append(f.path("kind").asText())
                    .append(" | ").append(cell(f.path("repository").asText())).append(" `").append(rev.length() > 12 ? rev.substring(0, 12) : rev).append('`')
                    .append(f.has("package_version") ? " (" + f.path("package_version").asText() + ")" : "").append(" |\n");
        }
        md.append("\nUnresolved references:\n\n");
        d.pins().path("unresolved").forEach(u -> md.append("- ").append(u.asText()).append('\n'));
        md.append("\n## Comparison\n\n| Concept |");
        ids.forEach(f -> md.append(" `").append(f).append("` |"));
        md.append(" Journal |\n|---|");
        ids.forEach(f -> md.append("---|"));
        md.append("---|\n");
        for (JsonNode c : d.catalog().path("concepts")) {
            md.append("| ").append(cell(c.path("concept").asText())).append(" (`").append(c.path("id").asText()).append("`) |");
            for (String f : ids) {
                JsonNode r = null;
                for (JsonNode x : c.path("foundations")) {
                    if (f.equals(x.path("foundation").asText())) {
                        r = x;
                    }
                }
                String status = r == null ? "missing" : r.path("status").asText();
                md.append(' ').append("mapped".equals(status) ? r.path("decision").asText() + ": `" + cell(r.path("selector").asText()) + "`" : status.replace('_', ' ')).append(" |");
            }
            List<String> destinations = new ArrayList<>();
            c.path("ocsf").forEach(o -> destinations.add(o.path("path").asText()));
            md.append(' ').append(c.path("decision").path("value").asText()).append(destinations.isEmpty() ? " (pending)" : ": `" + String.join("`, `", destinations) + "`").append(" |\n");
        }
        return md.toString();
    }

    /** The CLI coverage state of a current Journal route, in the preservation table's vocabulary. */
    static String preservationState(String coverage) {
        String w = MappingCatalogTest.word(coverage);
        return switch (w) {
            case "verified" -> "normalized";
            case "retained-only" -> "retained_raw";
            case "unavailable-at-source" -> "unavailable_input";
            case "unexercised" -> "unexercised";
            case "unsupported" -> "retained_raw";
            default -> "unresolved";
        };
    }

    static Map<String, Object> preservationJson(Documents d, Map<String, Map<String, String>> coverage) {
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("kind", "agent-journal/observed-preservation");
        doc.put("derived_by", "FoundationComparisonTest: recorded observations from mapping-catalog.json; current Journal columns from the CLI coverage calculation of this run");
        doc.put("rule", "Each cell is one tested integration on its own input; inputs differ between integrations, so no cell is a head-to-head comparison. States: normalized (queryable field), retained_raw, measured_loss, unavailable_input, unexercised, blocked, and for current Journal routes unresolved. Retention, queryability and evidence class are separate attributes. Field or byte counts are not preservation metrics.");
        doc.put("integrations", d.pins().path("integrations"));
        List<Map<String, Object>> rows = new ArrayList<>();
        for (JsonNode c : d.catalog().path("concepts")) {
            String id = c.path("id").asText();
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", id);
            row.put("concept", c.path("concept").asText());
            Map<String, Object> cells = new LinkedHashMap<>();
            for (JsonNode i : d.pins().path("integrations")) {
                String iid = i.path("id").asText();
                List<Object> entries = new ArrayList<>();
                if (i.path("current").asBoolean()) {
                    String state = coverage.get(id).get(i.path("derived_from").asText());
                    Map<String, Object> e = new LinkedHashMap<>();
                    e.put("item", "the concept as the independent oracle checks it");
                    e.put("state", preservationState(state));
                    e.put("queryable", "verified".equals(MappingCatalogTest.word(state)));
                    e.put("coverage_state", state);
                    e.put("evidence_class", i.path("evidence_class").asText());
                    e.put("evidence", List.of("cli-coverage.json"));
                    entries.add(e);
                }
                else {
                    c.path("observations").forEach(o -> {
                        if (iid.equals(o.path("integration").asText())) {
                            entries.add(o);
                        }
                    });
                }
                cells.put(iid, entries);
            }
            row.put("integrations", cells);
            rows.add(row);
        }
        doc.put("rows", rows);
        return doc;
    }

    @SuppressWarnings("unchecked")
    static String preservationMarkdown(Documents d, Map<String, Object> doc) {
        StringBuilder md = new StringBuilder("# Observed preservation comparison\n\n");
        md.append("Generated by `FoundationComparisonTest`; do not edit. Rows: concept ids. Columns: exact tested integrations; the two current Journal columns are derived from this run's CLI coverage ")
                .append("calculation (sanitized live recordings replayed through the current writer and read back by the independent oracle); the other columns are observations recorded in the October 2026 teardowns. ")
                .append("Each entry: state (evidence class) information item [evidence ids]. Inputs differ between integrations (different prompts, models and scenarios): no cell is a head-to-head result, ")
                .append("and a blocked hosted readback is not a loss. The historical Journal 1.12.0 column describes the legacy writer, not the current one. An empty cell is not tested.\n\n");
        md.append("## Integrations\n\n");
        for (JsonNode i : d.pins().path("integrations")) {
            md.append("- `").append(i.path("id").asText()).append("`: ").append(cell(i.path("name").asText())).append(" (").append(i.path("evidence_class").asText()).append("). ")
                    .append(cell(i.path("readback_status").asText())).append('\n');
        }
        List<String> ids = new ArrayList<>();
        d.pins().path("integrations").forEach(i -> ids.add(i.path("id").asText()));
        md.append("\n## Comparison\n\n| Concept |");
        ids.forEach(i -> md.append(" `").append(i).append("` |"));
        md.append("\n|---|");
        ids.forEach(i -> md.append("---|"));
        md.append('\n');
        for (Map<String, Object> row : (List<Map<String, Object>>) doc.get("rows")) {
            md.append("| `").append(row.get("id")).append("` |");
            Map<String, List<Object>> cells = (Map<String, List<Object>>) row.get("integrations");
            for (String i : ids) {
                List<String> parts = new ArrayList<>();
                for (Object o : cells.get(i)) {
                    JsonNode n = MAPPER.valueToTree(o);
                    List<String> ev = new ArrayList<>();
                    n.path("evidence").forEach(e -> ev.add(e.asText()));
                    parts.add("**" + n.path("state").asText() + "** (" + n.path("evidence_class").asText() + ") " + cell(n.path("item").asText())
                            + (n.has("coverage_state") ? ": " + cell(n.path("coverage_state").asText()) : " [" + String.join(", ", ev) + "]"));
                }
                md.append(' ').append(String.join("<br>", parts)).append(" |");
            }
            md.append('\n');
        }
        return md.toString();
    }

    static String projectionMarkdown(JsonNode projection) {
        StringBuilder md = new StringBuilder("# Projection of the agentic_cli extension onto upstream OCSF\n\n");
        JsonNode up = projection.path("upstream");
        md.append("Generated by `FoundationComparisonTest` from `prototype/ocsf-acp/projection/ocsf-1.9.0-projection.json`; do not edit. Upstream: ").append(up.path("repository").asText())
                .append(' ').append(up.path("release").asText()).append(" `").append(up.path("commit").asText()).append("`. ")
                .append(cell(projection.path("purpose").asText())).append(" Not at ").append(up.path("release").asText()).append(": ")
                .append(String.join(", ", MAPPER.convertValue(projection.path("upstream_objects_at_release").path("absent"), List.class))).append(".\n\n");
        Map<String, Integer> verdicts = new TreeMap<>();
        projection.path("rows").forEach(r -> verdicts.merge(r.path("verdict").asText(), 1, Integer::sum));
        md.append("Verdicts: ").append(verdicts).append("\n\n| Our attribute | Upstream | Verdict | Caveat |\n|---|---|---|---|\n");
        for (JsonNode r : projection.path("rows")) {
            md.append("| `").append(r.path("ours").asText()).append('`').append(r.has("inherited_from") ? " (inherited from " + r.path("inherited_from").asText() + ")" : "")
                    .append(r.path("envelope").asBoolean() ? " (envelope)" : "").append(" | ").append(blank(r.get("upstream")) ? "" : "`" + r.path("upstream").asText() + "`")
                    .append(" | ").append(r.path("verdict").asText()).append(" | ").append(cell(r.path("caveat").asText(""))).append(" |\n");
        }
        return md.toString();
    }

    /** The formal model baseline: exact definitions and hashes, versions, pins and the evidence tooling this run used. */
    static Map<String, Object> baseline(Documents d, Map<String, Map<String, String>> coverage) throws IOException {
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("kind", "agent-journal/model-baseline");
        doc.put("derived_by", "FoundationComparisonTest");
        JsonNode ext = read(EXTENSION.resolve("extension.json"));
        Map<String, Object> extension = new LinkedHashMap<>();
        extension.put("name", ext.path("name").asText());
        extension.put("uid", ext.path("uid").asText());
        extension.put("version", ext.path("version").asText());
        Map<String, String> files = new TreeMap<>();
        try (Stream<Path> s = Files.walk(EXTENSION)) {
            for (Path p : s.filter(Files::isRegularFile).sorted().toList()) {
                files.put(EXTENSION.relativize(p).toString(), NeutralJson.sha256(p));
            }
        }
        extension.put("definition_files_sha256", files);
        extension.put("definitions_sha256", sha256(String.join("\n", files.entrySet().stream().map(e -> e.getKey() + " " + e.getValue()).toList())));
        Path compiled = MappingCatalogTest.PROJECT.resolve("journal-ocsf-writer/src/main/resources/ocsf");
        try (Stream<Path> s = Files.list(compiled)) {
            for (Path p : s.filter(x -> x.getFileName().toString().startsWith("compiled-excerpt-")).toList()) {
                extension.put("compiled_excerpt", Map.of("file", p.getFileName().toString(), "sha256", NeutralJson.sha256(p)));
            }
        }
        doc.put("extension", extension);
        JsonNode lock = read(MappingCatalogTest.PROJECT.resolve("prototype/ocsf-acp/toolchain.lock.json"));
        doc.put("ocsf_core", lock.path("ocsf_schema"));
        doc.put("toolchain_lock_sha256", NeutralJson.sha256(MappingCatalogTest.PROJECT.resolve("prototype/ocsf-acp/toolchain.lock.json")));
        doc.put("reader_contract", PackageReader.READER_CONTRACT + " " + PackageReader.READER_CONTRACT_VERSION);
        Map<String, Object> catalog = new LinkedHashMap<>();
        catalog.put("version", d.catalog().path("version").asText());
        catalog.put("concepts", d.catalog().path("concepts").size());
        for (String f : List.of("mapping-catalog.json", "foundation-pins.json", "foundation-inventory.json", "owner-decisions.json", "references.json")) {
            catalog.put(f, NeutralJson.sha256(CATALOG_DIR.resolve(f)));
        }
        Map<String, String> examples = new TreeMap<>();
        try (Stream<Path> s = Files.list(CATALOG_DIR.resolve("examples"))) {
            for (Path p : s.filter(x -> x.toString().endsWith(".json")).sorted().toList()) {
                examples.put(p.getFileName().toString(), NeutralJson.sha256(p));
            }
        }
        catalog.put("examples", examples);
        doc.put("catalog", catalog);
        Map<String, String> pins = new LinkedHashMap<>();
        for (JsonNode f : d.pins().path("foundations")) {
            pins.put(f.path("id").asText(), Stream.of("revision", "commit", "tag_commit").map(k -> f.path(k).asText()).filter(v -> !v.isEmpty()).findFirst().orElse(""));
        }
        doc.put("foundation_pins", pins);
        doc.put("compatibility_sha256", NeutralJson.sha256(MappingCatalogTest.COMPATIBILITY));
        doc.put("compatibility_entries", MappingCatalogTest.compatibilityEntries());
        Map<String, String> contracts = new TreeMap<>();
        try (Stream<Path> s = Files.list(MappingCatalogTest.MAPPINGS)) {
            for (Path p : s.filter(x -> x.toString().endsWith(".json")).toList()) {
                Json c = NeutralJson.readDocument(p);
                contracts.put(SchemaCatalog.text(c, "contract_uid"), NeutralJson.sha256(p));
            }
        }
        doc.put("mapping_contracts_sha256", contracts);
        doc.put("projection_sha256", NeutralJson.sha256(PROJECTION));
        doc.put("independent_obligations", "CatalogCoverage over the catalog's oracle items, property_scopes, property_triggers, representation_properties and incomplete_run_outcomes; MappingCatalogSummaryTest controls");
        doc.put("dimensions", dimensions(coverage));
        return doc;
    }

    /**
     * The baseline's four dimensions, kept apart: schema and catalog integrity (checked by this build's reference and
     * schema tests), the Owner's semantic approval of each claim's exact revision, behaviour verification per admitted
     * route (the coverage calculation of this build) and live exercise (the live scenario results). No composite figure.
     */
    static Map<String, Object> dimensions(Map<String, Map<String, String>> coverage) {
        Json catalog = MappingCatalogTest.catalogDocument();
        Map<String, Object> dims = new LinkedHashMap<>();
        dims.put("schema_catalog_integrity", "pinned compiled schema and OCSF projection (this test), catalog references (MappingCatalogTest#everyCatalogReferenceResolvesInJava), "
                + "coverage document schemas (CoverageDocumentSchemaTest), claim digests and links (SemanticClaimTest)");
        List<Map<String, Object>> claims = new ArrayList<>();
        for (SemanticClaims.Claim c : SemanticClaims.claims(catalog)) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("claim", c.id());
            m.put("concept", c.concept());
            m.put("revision", c.revision());
            m.put("digest", c.digest());
            m.put("approval", c.status());
            m.put("decision", SchemaCatalog.text(c.json().member("approval"), "decision"));
            claims.add(m);
        }
        dims.put("semantic_approval", claims);
        Map<String, String> approval = SemanticClaims.approvalByConcept(catalog);
        Map<String, Object> concepts = new LinkedHashMap<>();
        coverage.forEach((id, routes) -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("semantic_approval", approval.get(id));
            Map<String, String> behaviour = new LinkedHashMap<>();
            routes.forEach((route, state) -> behaviour.put(route, MappingCatalogTest.word(state)));
            m.put("behaviour_verification", behaviour);
            concepts.put(id, m);
        });
        dims.put("concepts", concepts);
        dims.put("live_exercise", "live-scenarios/results.json and LIVE-SCENARIOS.md: per behaviour and route, live, attempted-not-triggered and synthetic kept apart");
        return dims;
    }

    static String sha256(String text) {
        try {
            byte[] h = java.security.MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : h) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        }
        catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
