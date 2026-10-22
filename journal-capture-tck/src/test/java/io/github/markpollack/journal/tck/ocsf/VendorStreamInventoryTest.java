package io.github.markpollack.journal.tck.ocsf;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.markpollack.journal.tck.ocsf.inventory.RawInventory;
import io.github.markpollack.journal.tck.verify.Check;
import io.github.markpollack.journal.tck.verify.Json;
import io.github.markpollack.journal.tck.verify.NeutralJson;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The pinned vendor capability inventory of a stream (what the vendor's own types, serializer and tests say the
 * selected emitter can write; {@code schema/vendor/<cli>/<profile>/<version>-vendor-inventory.json}) reconciled with
 * Journal's field dispositions, the committed recordings and the writer's actual behavior:
 * <ul>
 * <li>every declared leaf field of every declared family has a family-specific disposition (a wildcard-variant entry
 * counts only for the record discriminators), whether or not any recording carries it; every {@code vendor_declared}
 * disposition names a declared field;</li>
 * <li>every field a committed recording carries in a declared family is declared (an observed undeclared field means the
 * inventory must be refreshed), and every family seen in a live recording is declared; families only a labelled
 * synthetic fixture invents are reported as unknown and must be retained;</li>
 * <li>replaying the recordings through the writer, each record of a declared family produces exactly the rule the
 * inventory states for it: a family claimed normalized that falls back to raw retention fails;</li>
 * <li>with {@code -Dcodex.checkout=<clone>}, the pinned vendor files still have their recorded blob ids at the pinned
 * commit, and {@code -Dcodex.refresh=<rev>} lists the files that changed since, which a refresh must re-review.</li>
 * </ul>
 * It writes {@code target/mapping-catalog/<PROFILE>-VENDOR-COVERAGE.md} and {@code .json}: one record per family and
 * field with the vendor facts, observation (live recording, synthetic fixture, vendor test only, unexercised), Journal
 * disposition and the behavior shown by replay. Source inspection, synthetic replay and live recordings stay separate.
 */
class VendorStreamInventoryTest {

    static final ObjectMapper MAPPER = new ObjectMapper();
    static final Pattern HEX40 = Pattern.compile("[0-9a-f]{40}");

    /** A profile with a pinned vendor inventory: its file, the live and synthetic recordings of its route. */
    record Profile(String profile, String inventory, String checkoutProperty, List<String> live, List<String> synthetic) {
        List<String> all() {
            List<String> out = new ArrayList<>(live);
            out.addAll(synthetic);
            return out;
        }
    }

    static final List<Profile> PROFILES = List.of(
            new Profile("codex_stream", "schema/vendor/codex-cli/exec-json-stream/0.160.1-vendor-inventory.json", "codex.checkout",
                    List.of(OcsfFixtures.LIVE_CODEX, OcsfFixtures.LIVE_CODEX_CANCELLED),
                    List.of(OcsfFixtures.CODEX_STREAM, OcsfFixtures.CODEX_FAMILIES, OcsfFixtures.CODEX_TURN_FAILED)));

    static JsonNode inventory(Profile p) {
        try {
            return MAPPER.readTree(FieldDispositionTest.PROJECT.resolve(p.inventory()).toFile());
        }
        catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static Map<String, JsonNode> families(JsonNode inventory) {
        Map<String, JsonNode> out = new LinkedHashMap<>();
        inventory.path("families").forEach(f -> out.put(f.path("variant").asText(), f));
        return out;
    }

    static List<String> paths(JsonNode family) {
        List<String> out = new ArrayList<>();
        family.path("fields").forEach(f -> out.add(f.path("path").asText()));
        return out;
    }

    /** Declared fields with no declared field beneath them: the ones a disposition must name. */
    static List<String> leaves(JsonNode family) {
        List<String> all = paths(family);
        return all.stream().filter(p -> all.stream().noneMatch(q -> !q.equals(p) && (q.startsWith(p + ".") || q.startsWith(p + "[")))).toList();
    }

    // ------------------------------------------------------------------------------------------ checks

    /** Inventory shape and pins. */
    static List<String> shapeProblems(JsonNode inv) {
        List<String> problems = new ArrayList<>();
        if (!HEX40.matcher(inv.path("source").path("commit").asText()).matches()) {
            problems.add("the vendor source names no exact commit");
        }
        inv.path("source").path("files").fields().forEachRemaining(e -> {
            if (!HEX40.matcher(e.getValue().asText()).matches()) {
                problems.add("vendor file " + e.getKey() + " has no blob id");
            }
        });
        Set<String> seen = new TreeSet<>();
        for (JsonNode f : inv.path("families")) {
            String v = f.path("variant").asText();
            if (!seen.add(v)) {
                problems.add("family " + v + " declared twice");
            }
            if (!Set.of("always", "conditional").contains(f.path("emitted").asText())) {
                problems.add(v + ": emitted must be always or conditional");
            }
            if (!Set.of("normalized", "retained-only", "opaque").contains(f.path("journal").path("behavior").asText()) || f.path("journal").path("rule").asText().isEmpty()) {
                problems.add(v + ": journal behavior and rule required");
            }
            for (JsonNode x : f.path("fields")) {
                for (String k : List.of("path", "type", "meaning", "availability", "symbol")) {
                    if (x.path(k).asText().isEmpty()) {
                        problems.add(v + ":" + x.path("path").asText() + " has no " + k);
                    }
                }
                if (!Set.of("always", "conditional").contains(x.path("availability").asText())) {
                    problems.add(v + ":" + x.path("path").asText() + ": availability must be always or conditional (omissions go to omitted_by_projection)");
                }
            }
        }
        return problems;
    }

    /** Every declared leaf field has a family-specific disposition; every vendor-declared disposition is declared. */
    static List<String> dispositionProblems(JsonNode inv, FieldDispositionTest.Catalog catalog) {
        List<String> problems = new ArrayList<>();
        Map<String, JsonNode> families = families(inv);
        for (JsonNode f : families.values()) {
            String v = f.path("variant").asText();
            for (String path : leaves(f)) {
                List<FieldDispositionTest.Entry> m = FieldDispositionTest.applicable(catalog, v, path);
                boolean specific = m.size() == 1 && (m.get(0).variant().equals(v)
                        || (m.get(0).variant().equals("*") && m.get(0).path().equals(path) && "normalized".equals(m.get(0).disposition())));
                if (!specific) {
                    problems.add("declared field " + v + ":" + path + " has no family-specific disposition" + (m.isEmpty() ? "" : " (only " + m.get(0).key() + ")"));
                }
            }
        }
        for (FieldDispositionTest.Entry e : catalog.entries()) {
            if (e.vendorDeclared() && (!families.containsKey(e.variant()) || !paths(families.get(e.variant())).contains(e.path()))) {
                problems.add("vendor-declared disposition " + e.key() + " is not in the vendor inventory");
            }
        }
        return problems;
    }

    /** Observed fields of declared families must be declared; observed families must be declared unless only synthetic. */
    static List<String> reconciliationProblems(JsonNode inv, Map<String, Set<String>> observed, Set<String> liveVariants, Set<String> unknown) {
        List<String> problems = new ArrayList<>();
        Map<String, JsonNode> families = families(inv);
        for (Map.Entry<String, Set<String>> o : observed.entrySet()) {
            JsonNode f = families.get(o.getKey());
            if (f == null) {
                if (liveVariants.contains(o.getKey())) {
                    problems.add("family " + o.getKey() + " occurs in a live recording but the vendor inventory does not declare it (refresh the inventory)");
                }
                else {
                    unknown.add(o.getKey());
                }
                continue;
            }
            List<String> declared = paths(f);
            for (String path : o.getValue()) {
                boolean ok = declared.contains(path) || (path.endsWith("[]") && declared.contains(path.substring(0, path.length() - 2))) || declared.stream().anyMatch(d -> (path.startsWith(d + ".") || path.startsWith(d + "["))
                        && declared.stream().noneMatch(q -> !q.equals(d) && (q.startsWith(d + ".") || q.startsWith(d + "["))));
                if (!ok) {
                    problems.add("observed field " + o.getKey() + ":" + path + " is not declared by the vendor inventory (refresh it)");
                }
            }
        }
        return problems;
    }

    /** The family of one native line, read leniently so that a record the neutral reader refuses still names its family. */
    static String variant(String line) {
        try {
            JsonNode o = MAPPER.readTree(line);
            String type = o.path("type").asText(null);
            String item = o.path("item").path("type").asText(null);
            return item == null ? type : type + "/" + item;
        }
        catch (IOException e) {
            return null;
        }
    }

    /** Per declared family on one replayed fixture: the rule ids its records actually produced. */
    static Map<String, Set<String>> rulesByFamily(Path fixture) throws IOException {
        List<String> lines = Files.readAllLines(OcsfFixtures.natives(fixture).resolve("stream.jsonl"), StandardCharsets.UTF_8);
        Map<Integer, Set<String>> rules = new TreeMap<>();
        for (Event e : OcsfFixtures.open(fixture).events) {
            if (e.className != null && e.recordIndex() != null) {
                rules.computeIfAbsent(e.recordIndex(), k -> new TreeSet<>()).add(e.text("unmapped", "rule_id"));
            }
        }
        Map<String, Set<String>> out = new TreeMap<>();
        for (int i = 0; i < lines.size(); i++) {
            String v = variant(lines.get(i));
            if (v != null) {
                out.computeIfAbsent(v, k -> new TreeSet<>()).addAll(rules.getOrDefault(i + 1, Set.of("(no event)")));
            }
        }
        return out;
    }

    static List<String> behaviorProblems(JsonNode inv, Map<String, Map<String, Set<String>>> rulesByFixture) {
        List<String> problems = new ArrayList<>();
        Map<String, JsonNode> families = families(inv);
        for (Map.Entry<String, Map<String, Set<String>>> fx : rulesByFixture.entrySet()) {
            for (Map.Entry<String, Set<String>> r : fx.getValue().entrySet()) {
                JsonNode f = families.get(r.getKey());
                String expected = f == null ? null : f.path("journal").path("rule").asText();
                if (f == null) {
                    if (r.getValue().stream().noneMatch(x -> x.endsWith("fallback->source_record") || x.endsWith("opaque->source_record"))) {
                        problems.add(fx.getKey() + ": unknown family " + r.getKey() + " is not retained (" + r.getValue() + ")");
                    }
                }
                else if (!r.getValue().equals(Set.of(expected))) {
                    problems.add(fx.getKey() + ": family " + r.getKey() + " produced " + r.getValue() + " but the inventory states " + expected
                            + ("normalized".equals(f.path("journal").path("behavior").asText()) ? " (a required canonical mapping replaced by raw-only fallback?)" : ""));
                }
            }
        }
        return problems;
    }

    // ------------------------------------------------------------------------------------------ tests

    record Run(JsonNode inventory, FieldDispositionTest.Catalog catalog, Map<String, Set<String>> observed, Set<String> liveVariants,
            Map<String, Map<String, Set<String>>> rules, Map<String, Path> fixtures) {
    }

    static Run run(Profile p, Path work) throws IOException {
        Map<String, Set<String>> observed = FieldDispositionTest.observed(p.profile(), work.resolve("observed"));
        Set<String> liveVariants = new TreeSet<>();
        Map<String, Map<String, Set<String>>> rules = new LinkedHashMap<>();
        Map<String, Path> fixtures = new LinkedHashMap<>();
        for (String f : p.all()) {
            Path fixture = OcsfFixtures.copy(f, work.resolve("fx"));
            fixtures.put(f, fixture);
            rules.put(f, rulesByFamily(fixture));
            if (p.live().contains(f)) {
                liveVariants.addAll(rules.get(f).keySet());
            }
        }
        return new Run(inventory(p), FieldDispositionTest.catalog(p.profile()), observed, liveVariants, rules, fixtures);
    }

    @Test
    void everyDeclaredFieldHasAFamilySpecificDispositionAndEveryObservedFieldIsDeclared(@TempDir Path work) throws IOException {
        for (Profile p : PROFILES) {
            Run r = run(p, work.resolve(p.profile()));
            assertThat(shapeProblems(r.inventory())).as(p.profile()).isEmpty();
            assertThat(dispositionProblems(r.inventory(), r.catalog())).as(p.profile()).isEmpty();
            Set<String> unknown = new TreeSet<>();
            assertThat(reconciliationProblems(r.inventory(), r.observed(), r.liveVariants(), unknown)).as(p.profile()).isEmpty();
            // undeclared: the two record shapes the labelled synthetic stream invents to exercise fallback, and the web_search
            // lines, which the neutral reader refuses as malformed because they carry two "id" members (a defect to resolve;
            // everyDeclaredFamilyBehavesAsItsInventoryStates shows they are retained as opaque bytes)
            assertThat(unknown).as(p.profile() + " unknown families").containsExactlyInAnyOrder("synthetic.unknown_event", "item.completed/synthetic_unknown_item",
                    "(opaque:MALFORMED)");
        }
    }

    @Test
    void everyDeclaredFamilyBehavesAsItsInventoryStates(@TempDir Path work) throws IOException {
        for (Profile p : PROFILES) {
            Run r = run(p, work.resolve(p.profile()));
            assertThat(behaviorProblems(r.inventory(), r.rules())).as(p.profile()).isEmpty();
            for (Map.Entry<String, Path> f : r.fixtures().entrySet()) {
                OracleReport report = OcsfFixtures.verify(f.getValue());
                assertThat(report.defects()).as(f.getKey()).isEmpty();
                assertThat(report.outcome("whole_record.coverage", "main/stream.jsonl")).as(f.getKey() + " retention").isEqualTo(Check.Outcome.VERIFIED);
            }
        }
    }

    @Test
    void aMissingDispositionAnUndeclaredObservedFieldAndAFalseNormalizationClaimAreDetected(@TempDir Path work) throws IOException {
        Profile p = PROFILES.get(0);
        Run r = run(p, work);
        // a declared family loses one field's disposition
        FieldDispositionTest.Catalog without = FieldDispositionTest.withEntries(r.catalog(),
                r.catalog().entries().stream().filter(e -> !e.key().equals("item.completed/mcp_tool_call:item.server")).toList());
        assertThat(dispositionProblems(r.inventory(), without)).anyMatch(x -> x.contains("item.completed/mcp_tool_call:item.server") && x.contains("no family-specific disposition"));
        // a recording carries a field the inventory does not declare
        Map<String, Set<String>> extra = new LinkedHashMap<>(r.observed());
        Set<String> mcp = new LinkedHashSet<>(extra.get("item.completed/mcp_tool_call"));
        mcp.add("item.duration_ms");
        extra.put("item.completed/mcp_tool_call", mcp);
        assertThat(reconciliationProblems(r.inventory(), extra, r.liveVariants(), new TreeSet<>())).anyMatch(x -> x.contains("item.duration_ms") && x.contains("not declared"));
        // a live family missing from the inventory
        ObjectNode noMessage = r.inventory().deepCopy();
        ArrayNode fams = (ArrayNode) noMessage.path("families");
        for (int i = 0; i < fams.size(); i++) {
            if ("item.completed/agent_message".equals(fams.get(i).path("variant").asText())) {
                fams.remove(i);
                break;
            }
        }
        assertThat(reconciliationProblems(noMessage, r.observed(), r.liveVariants(), new TreeSet<>())).anyMatch(x -> x.contains("item.completed/agent_message") && x.contains("live recording"));
        // the inventory claims a normalization the writer does not perform: the raw-only fallback is caught
        ObjectNode claimed = r.inventory().deepCopy();
        for (JsonNode f : claimed.path("families")) {
            if ("item.completed/mcp_tool_call".equals(f.path("variant").asText())) {
                ((ObjectNode) f.path("journal")).put("behavior", "normalized").put("rule", "codex.stream.mcp_tool_call->tool_call");
            }
        }
        assertThat(behaviorProblems(claimed, r.rules())).anyMatch(x -> x.contains("item.completed/mcp_tool_call") && x.contains("raw-only fallback"));
        // and the valid control holds
        assertThat(behaviorProblems(r.inventory(), r.rules())).isEmpty();
    }

    /** Gated: {@code -Dcodex.checkout=<clone of the vendor repository>} (and optionally {@code -Dcodex.refresh=<rev>}). */
    @Test
    void thePinnedVendorSourceIsUnchanged() throws IOException, InterruptedException {
        for (Profile p : PROFILES) {
            String checkout = System.getProperty(p.checkoutProperty());
            Assumptions.assumeTrue(checkout != null, "no -D" + p.checkoutProperty());
            JsonNode inv = inventory(p);
            String commit = inv.path("source").path("commit").asText();
            String refresh = System.getProperty(p.checkoutProperty().replace("checkout", "refresh"));
            List<String> changed = new ArrayList<>();
            var it = inv.path("source").path("files").fields();
            while (it.hasNext()) {
                var e = it.next();
                assertThat(blob(checkout, commit, e.getKey())).as(e.getKey() + " at " + commit).isEqualTo(e.getValue().asText());
                if (refresh != null && !e.getValue().asText().equals(blob(checkout, refresh, e.getKey()))) {
                    changed.add(e.getKey());
                }
            }
            assertThat(changed).as("vendor files changed at " + refresh + ": re-review the inventory and bump its source pin").isEmpty();
        }
    }

    static String blob(String checkout, String rev, String path) throws IOException, InterruptedException {
        Process pr = new ProcessBuilder("git", "-C", checkout, "rev-parse", rev + ":" + path).redirectErrorStream(true).start();
        String out = new String(pr.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
        pr.waitFor();
        return out;
    }

    // ------------------------------------------------------------------------------------------ coverage records

    @Test
    void theVendorCoverageRecordsAreGenerated(@TempDir Path work) throws IOException {
        for (Profile p : PROFILES) {
            Run r = run(p, work.resolve(p.profile()));
            Map<String, Map<String, Set<String>>> observedBy = observedByFixture(p, r);
            List<Map<String, Object>> records = new ArrayList<>();
            for (JsonNode f : r.inventory().path("families")) {
                String v = f.path("variant").asText();
                Set<String> behaviorOn = new TreeSet<>();
                r.rules().forEach((fx, m) -> {
                    if (m.containsKey(v)) {
                        behaviorOn.add(fx);
                    }
                });
                for (JsonNode x : f.path("fields")) {
                    String path = x.path("path").asText();
                    Map<String, Object> rec = new LinkedHashMap<>();
                    rec.put("profile", p.profile());
                    rec.put("cli_version", r.inventory().path("cli_version").asText());
                    rec.put("source", r.inventory().path("source").path("repository").asText() + "@" + r.inventory().path("source").path("commit").asText());
                    rec.put("family", v);
                    rec.put("family_emitted", f.path("emitted").asText() + (f.has("condition") ? ": " + f.path("condition").asText() : ""));
                    rec.put("field", path);
                    rec.put("type", x.path("type").asText());
                    rec.put("values", x.path("values"));
                    rec.put("meaning", x.path("meaning").asText());
                    rec.put("identity", x.path("identity").asText(""));
                    rec.put("availability", x.path("availability").asText() + (x.has("condition") ? ": " + x.path("condition").asText() : ""));
                    rec.put("symbol", x.path("symbol").asText());
                    rec.put("observed", observation(p, observedBy, v, path, f));
                    List<FieldDispositionTest.Entry> m = FieldDispositionTest.applicable(r.catalog(), v, path);
                    FieldDispositionTest.Entry e = m.size() == 1 ? m.get(0) : null;
                    rec.put("journal_disposition", e == null ? "(container: see its fields)" : e.disposition());
                    rec.put("journal_destination", e == null ? List.of() : e.destinations());
                    rec.put("journal_rule", e == null || e.rule() == null ? "" : e.rule());
                    rec.put("family_behavior", f.path("journal").path("behavior").asText() + " (" + f.path("journal").path("rule").asText() + ")");
                    rec.put("family_behavior_shown_by_replay_of", behaviorOn);
                    rec.put("vendor_tests", f.path("vendor_tests"));
                    records.add(rec);
                }
            }
            Map<String, Object> doc = new LinkedHashMap<>();
            doc.put("kind", "agent-journal/vendor-coverage");
            doc.put("derived_by", "VendorStreamInventoryTest from the pinned vendor inventory, the field dispositions, the committed recordings and their replay through the writer");
            doc.put("inventory", p.inventory());
            doc.put("source", r.inventory().path("source"));
            doc.put("records", records);
            doc.put("omitted_by_projection", r.inventory().path("omitted_by_projection"));
            Path out = MappingCatalogTest.outputDirectory();
            String name = p.profile().toUpperCase(java.util.Locale.ROOT).replace('_', '-');
            Files.writeString(out.resolve(name + "-VENDOR-COVERAGE.md"), markdown(p, r.inventory(), records), StandardCharsets.UTF_8);
            Files.writeString(out.resolve(p.profile().replace('_', '-') + "-vendor-coverage.json"), MappingCatalogTest.PRETTY.writeValueAsString(doc) + "\n", StandardCharsets.UTF_8);
            assertThat(records).isNotEmpty();
        }
    }

    /** Per fixture, the raw-inventory (variant, path) set of its stream, read with the neutral inventory. */
    static Map<String, Map<String, Set<String>>> observedByFixture(Profile p, Run r) {
        Map<String, Map<String, Set<String>>> out = new LinkedHashMap<>();
        for (Map.Entry<String, Path> f : r.fixtures().entrySet()) {
            Json inv = RawInventory.inventory(NativeArtifact.read(OcsfFixtures.natives(f.getValue()).resolve("stream.jsonl")), "stream.jsonl", p.profile());
            Map<String, Set<String>> m = new TreeMap<>();
            if (inv.member("variants") instanceof Json.JObject vs) {
                vs.members().forEach((v, d) -> m.put(v, d.member("paths") instanceof Json.JObject ps ? new TreeSet<>(ps.members().keySet()) : Set.of()));
            }
            out.put(f.getKey(), m);
        }
        return out;
    }

    static String observation(Profile p, Map<String, Map<String, Set<String>>> observedBy, String variant, String path, JsonNode family) {
        List<String> live = new ArrayList<>();
        List<String> synthetic = new ArrayList<>();
        observedBy.forEach((fx, m) -> {
            Set<String> ps = m.getOrDefault(variant, Set.of());
            if (ps.contains(path) || ps.stream().anyMatch(x -> x.startsWith(path + ".") || x.startsWith(path + "["))) {
                (p.live().contains(fx) ? live : synthetic).add(fx);
            }
        });
        if (!live.isEmpty()) {
            return "live recording: " + String.join(", ", live);
        }
        if (!synthetic.isEmpty()) {
            return "synthetic fixture only: " + String.join(", ", synthetic);
        }
        return family.path("vendor_tests").isEmpty() ? "unexercised" : "vendor test only (" + family.path("vendor_tests").get(0).asText() + ")";
    }

    static String markdown(Profile p, JsonNode inv, List<Map<String, Object>> records) {
        StringBuilder md = new StringBuilder("# Vendor capability coverage: ").append(inv.path("cli").asText()).append(' ').append(inv.path("cli_version").asText())
                .append(", ").append(p.profile()).append("\n\n");
        md.append("Generated by `VendorStreamInventoryTest`; do not edit. Vendor facts from `").append(p.inventory()).append("` (source ")
                .append(inv.path("source").path("repository").asText()).append(" `").append(inv.path("source").path("commit").asText()).append("`, reviewed reading of pinned files). ")
                .append("Observed: in a sanitized live recording, only in a labelled synthetic fixture (derived from vendor types and tests), only in a vendor test, or unexercised. ")
                .append("Journal disposition per field and the family behavior shown by replaying the fixtures through the writer; a retained-only family is preserved, not normalized. ")
                .append("A source-declared feature absent from recordings is unexercised, not unavailable.\n\n");
        md.append("| Family | Field | Availability | Observed | Journal disposition | Family behavior |\n|---|---|---|---|---|---|\n");
        for (Map<String, Object> r : records) {
            md.append("| `").append(r.get("family")).append("` | `").append(r.get("field")).append("` | ").append(FoundationComparisonTest.cell(String.valueOf(r.get("availability"))))
                    .append(" | ").append(r.get("observed")).append(" | ").append(r.get("journal_disposition"))
                    .append(String.valueOf(r.get("journal_rule")).isEmpty() ? "" : " (`" + r.get("journal_rule") + "`)").append(" | ").append(r.get("family_behavior")).append(((Set<?>) r.get("family_behavior_shown_by_replay_of")).isEmpty() ? " (stated; no recording shows it)" : "").append(" |\n");
        }
        md.append("\n## Omitted by the exec projection\n\n");
        inv.path("omitted_by_projection").forEach(o -> md.append("- **").append(o.path("what").asText()).append("** (").append(o.path("applies_to").asText()).append("): ")
                .append(o.path("consequence").asText()).append(". `").append(o.path("symbol").asText()).append("`\n"));
        return md.toString();
    }
}
