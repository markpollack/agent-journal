package io.github.markpollack.journal.tck.ocsf;

import io.github.markpollack.journal.tck.ocsf.inventory.RawInventory;
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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * RULE-34 / UC3-AC14: every meaningful acquired field of a selected stream has exactly one recorded disposition, and
 * each disposition is backed by what actually executed in this run.
 *
 * <p>For each streaming profile the catalog {@code schema/vendor/<cli>/<profile>/dispositions.json} is checked against
 * the <em>observed</em> inventory: the union of the accepted specification's variants and paths and the raw inventory
 * of every committed native stream of that profile (read with the neutral inventory, never a mapper). Tool and user
 * data subtrees ({@code .{*}}) are covered by the entry of their enclosing vendor path. Then:
 * <ul>
 * <li>an observed (variant, path) without a disposition, or with two exact dispositions, fails naming it;</li>
 * <li>an entry that matches nothing observed is stale and fails, unless it is marked {@code vendor_declared}: a field the pinned
 * vendor inventory declares stays dispositioned while no recording carries it (unexercised; {@code VendorStreamInventoryTest}
 * checks every such entry against the inventory), and its evidence obligations apply once a recording carries it;</li>
 * <li>a {@code normalized} entry names destinations that resolve in the declared schema of a committed package (or a
 * declaration field the format defines), a rule the named contract declares (or the contract's discriminator/routing
 * member), and an oracle property that this test's own oracle run reported VERIFIED or REPRESENTATION_DIFFERS with
 * an occurrence on one of the evidence fixtures: a claim with no exercised evidence fails;</li>
 * <li>a {@code retained} or {@code unresolved} entry needs whole-record retention VERIFIED on an evidence fixture, and
 * an {@code unresolved} entry states its open question;</li>
 * <li>an {@code omitted} entry needs a reviewed rule and a test (none exists; the branch is kept so that adding one
 * without its rule fails);</li>
 * <li>every {@code Class#method} test reference exists in the test sources of this module or the writer.</li>
 * </ul>
 * Negative controls below remove a disposition, break a destination, a rule and a test reference, and point a
 * normalized claim at a property with no occurrence; each must fail for that reason.
 */
class FieldDispositionTest {

    static final Path PROJECT = Path.of("..").toAbsolutePath().normalize();
    static final Path MAPPINGS = PROJECT.resolve("journal-ocsf-writer/src/main/resources/ocsf/mappings");
    static final List<Path> TEST_SOURCES = List.of(PROJECT.resolve("journal-capture-tck/src/test/java"), PROJECT.resolve("journal-ocsf-writer/src/test/java"));

    /** Profile to (catalog, native fixtures whose stream artifact is inventoried). */
    static final Map<String, List<String>> PROFILE_FIXTURES = Map.of(
            "claude_stream", List.of(OcsfFixtures.CHILD, OcsfFixtures.INTERRUPTED, "syn-claude-untested-version", OcsfFixtures.LIVE_FEATURES, OcsfFixtures.LIVE_DENIAL, OcsfFixtures.LIVE_CANCELLED),
            "codex_stream", List.of(OcsfFixtures.CODEX_STREAM, OcsfFixtures.LIVE_CODEX, OcsfFixtures.LIVE_CODEX_CANCELLED, OcsfFixtures.CODEX_FAMILIES, OcsfFixtures.CODEX_TURN_FAILED, OcsfFixtures.LIVE_CODEX_FILE_CHANGE, OcsfFixtures.LIVE_CODEX_MCP, OcsfFixtures.LIVE_CODEX_REASONING, OcsfFixtures.LIVE_CODEX_PLAN, OcsfFixtures.LIVE_CODEX_WEB_SEARCH, OcsfFixtures.LIVE_CODEX_COLLAB_WAIT, OcsfFixtures.LIVE_CODEX_INTERRUPT_OPEN, OcsfFixtures.LIVE_CODEX_SANDBOX_REFUSAL));
    static final Map<String, String> CATALOGS = Map.of(
            "claude_stream", "schema/vendor/claude-code/stream-json/dispositions.json",
            "codex_stream", "schema/vendor/codex-cli/exec-json-stream/dispositions.json");

    record Entry(String variant, String path, boolean subtree, String disposition, List<String> destinations, String rule, String property, List<String> tests, String note, Json raw) {
        String key() {
            return variant + ":" + path;
        }

        /** Declared by the pinned vendor inventory: kept when no recording carries it (unexercised), never silently dropped as stale. */
        boolean vendorDeclared() {
            return raw != null && raw.member("vendor_declared") instanceof Json.JBool b && b.value();
        }
    }

    record Catalog(String profile, String contractFile, List<String> evidenceFixtures, List<Entry> entries, Json raw) {
    }

    @Test
    void everyObservedStreamFieldHasExactlyOneBackedDisposition(@TempDir Path work) {
        for (String profile : CATALOGS.keySet()) {
            Catalog catalog = catalog(profile);
            Map<String, Set<String>> observed = observed(profile, work.resolve(profile));
            List<String> problems = check(catalog, observed, reports(catalog, work.resolve(profile)), schema(work.resolve(profile)));
            assertThat(problems).as(profile + " dispositions").isEmpty();
            int paths = observed.values().stream().mapToInt(Set::size).sum();
            System.out.println(profile + ": " + catalog.entries().size() + " disposition entries cover " + paths + " observed variant paths across " + observed.size() + " variants; " + summary(catalog));
        }
    }

    @Test
    void aMissingDispositionABrokenReferenceAndAnUnexercisedClaimEachFail(@TempDir Path work) {
        Catalog catalog = catalog("claude_stream");
        Map<String, Set<String>> observed = observed("claude_stream", work);
        Map<String, OracleReport> reports = reports(catalog, work.resolve("r"));
        SchemaCatalog schema = schema(work.resolve("r"));
        assertThat(check(catalog, observed, reports, schema)).as("control").isEmpty();

        // one disposition removed: its paths are uncovered
        Catalog without = withEntries(catalog, catalog.entries().stream().filter(e -> !e.key().equals("result:total_cost_usd")).toList());
        assertThat(check(without, observed, reports, schema)).anyMatch(p -> p.contains("no disposition") && p.contains("result:total_cost_usd"));

        // a duplicate exact disposition
        Entry cost = catalog.entries().stream().filter(e -> e.key().equals("result:total_cost_usd")).findFirst().orElseThrow();
        List<Entry> dup = new ArrayList<>(catalog.entries());
        dup.add(cost);
        assertThat(check(withEntries(catalog, dup), observed, reports, schema)).anyMatch(p -> p.contains("more than one exact disposition") && p.contains("result:total_cost_usd"));

        // a destination that does not exist in the schema
        assertThat(check(withEntries(catalog, replace(catalog, cost, new Entry(cost.variant(), cost.path(), cost.subtree(), cost.disposition(),
                List.of("prompt_turn.cost_observation.no_such_attribute"), cost.rule(), cost.property(), cost.tests(), cost.note(), cost.raw()))), observed, reports, schema))
                .anyMatch(p -> p.contains("destination") && p.contains("no_such_attribute"));

        // a rule the contract does not declare
        assertThat(check(withEntries(catalog, replace(catalog, cost, new Entry(cost.variant(), cost.path(), cost.subtree(), cost.disposition(),
                cost.destinations(), "claude.stream.no_such_rule", cost.property(), cost.tests(), cost.note(), cost.raw()))), observed, reports, schema))
                .anyMatch(p -> p.contains("rule claude.stream.no_such_rule") && p.contains("not declared"));

        // a test reference that does not exist
        assertThat(check(withEntries(catalog, replace(catalog, cost, new Entry(cost.variant(), cost.path(), cost.subtree(), cost.disposition(),
                cost.destinations(), cost.rule(), cost.property(), List.of("OcsfOracleMutationTest#noSuchTestMethod"), cost.note(), cost.raw()))), observed, reports, schema))
                .anyMatch(p -> p.contains("test OcsfOracleMutationTest#noSuchTestMethod") && p.contains("not found"));

        // a normalized claim whose property has no occurrence on the evidence fixtures (operations: Claude reports one result per call)
        assertThat(check(withEntries(catalog, replace(catalog, cost, new Entry(cost.variant(), cost.path(), cost.subtree(), cost.disposition(),
                cost.destinations(), cost.rule(), "operation.values", cost.tests(), cost.note(), cost.raw()))), observed, reports, schema))
                .anyMatch(p -> p.contains("operation.values") && p.contains("no exercised"));

        // a stale entry: a path nothing observes
        List<Entry> stale = new ArrayList<>(catalog.entries());
        stale.add(new Entry("result", "no_such_field", false, "retained", List.of(), null, "whole_record.coverage", List.of(), null, null));
        assertThat(check(withEntries(catalog, stale), observed, reports, schema)).anyMatch(p -> p.contains("stale") && p.contains("result:no_such_field"));
        // the same unobserved entry marked vendor-declared is kept as unexercised, not stale
        List<Entry> declared = new ArrayList<>(catalog.entries());
        declared.add(new Entry("result", "no_such_field", false, "retained", List.of(), null, "whole_record.coverage", List.of(), null,
                NeutralJson.parse("{\"vendor_declared\": true}")));
        assertThat(check(withEntries(catalog, declared), observed, reports, schema)).noneMatch(p -> p.contains("result:no_such_field"));

        // an omission without its reviewed rule
        assertThat(check(withEntries(catalog, replace(catalog, cost, new Entry(cost.variant(), cost.path(), cost.subtree(), "omitted",
                List.of(), null, null, List.of(), "no rule", cost.raw()))), observed, reports, schema))
                .anyMatch(p -> p.contains("omitted") && p.contains("reviewed rule"));
    }

    // ---- the check ---------------------------------------------------------------------------------------------------

    static List<String> check(Catalog catalog, Map<String, Set<String>> observed, Map<String, OracleReport> reports, SchemaCatalog schema) {
        List<String> problems = new ArrayList<>();
        Map<String, List<Entry>> exact = new LinkedHashMap<>();
        for (Entry e : catalog.entries()) {
            exact.computeIfAbsent(e.key(), k -> new ArrayList<>()).add(e);
        }
        Set<Entry> used = new LinkedHashSet<>();
        for (Map.Entry<String, Set<String>> v : observed.entrySet()) {
            for (String path : v.getValue()) {
                List<Entry> matches = applicable(catalog, v.getKey(), path);
                if (matches.isEmpty()) {
                    problems.add("no disposition for observed field " + v.getKey() + ":" + path);
                }
                else if (matches.size() > 1) {
                    problems.add("more than one exact disposition for " + v.getKey() + ":" + path + ": " + matches.stream().map(Entry::key).toList());
                }
                else {
                    used.add(matches.get(0));
                }
            }
        }
        for (Entry e : catalog.entries()) {
            if (!used.contains(e) && !e.vendorDeclared()) {
                problems.add("stale disposition " + e.key() + ": no observed field matches it");
            }
        }
        Map<String, Json> contract = contract(catalog.contractFile());
        for (Entry e : catalog.entries()) {
            if (!used.contains(e)) {
                continue; // a vendor-declared field no committed recording carries: unexercised, its evidence cannot exist yet
            }
            switch (e.disposition()) {
                case "normalized" -> {
                    if (e.destinations().isEmpty()) {
                        problems.add(e.key() + ": normalized without a destination");
                    }
                    for (String d : e.destinations()) {
                        String why = resolveDestination(schema, d);
                        if (why != null) {
                            problems.add(e.key() + ": destination " + d + " does not resolve: " + why);
                        }
                    }
                    if (e.rule() == null || !ruleDeclared(contract, e.rule())) {
                        problems.add(e.key() + ": rule " + e.rule() + " is not declared in contract " + catalog.contractFile());
                    }
                    problems.addAll(exercised(e, catalog, reports, Set.of(Check.Outcome.VERIFIED, Check.Outcome.REPRESENTATION_DIFFERS)));
                }
                case "retained", "unresolved" -> {
                    if (!"whole_record.coverage".equals(e.property())) {
                        problems.add(e.key() + ": a " + e.disposition() + " field is backed by whole_record.coverage, not " + e.property());
                    }
                    problems.addAll(exercised(e, catalog, reports, Set.of(Check.Outcome.VERIFIED)));
                    if ("unresolved".equals(e.disposition()) && (e.note() == null || e.note().isBlank())) {
                        problems.add(e.key() + ": unresolved without the open question stated");
                    }
                }
                case "omitted" -> {
                    if (e.rule() == null || !ruleDeclared(contract, e.rule()) || e.tests().isEmpty()) {
                        problems.add(e.key() + ": omitted without a reviewed rule in the contract and a test");
                    }
                }
                default -> problems.add(e.key() + ": unknown disposition " + e.disposition());
            }
            for (String t : e.tests()) {
                if (!testExists(t)) {
                    problems.add(e.key() + ": test " + t + " not found in the test sources");
                }
            }
        }
        return problems;
    }

    /** The exact entries for (variant, path), else the longest subtree entry whose path is a prefix within the variant or the wildcard variant. */
    static List<Entry> applicable(Catalog catalog, String variant, String path) {
        List<Entry> exact = new ArrayList<>();
        Entry best = null;
        for (Entry e : catalog.entries()) {
            boolean variantOk = e.variant().equals(variant) || e.variant().equals("*");
            if (!variantOk) {
                continue;
            }
            if (e.path().equals(path) && !e.subtree()) {
                exact.add(e);
            }
            else if (e.path().equals(path) && e.subtree()) {
                exact.add(e);
            }
            else if (e.subtree() && (e.path().equals("*") || path.startsWith(e.path() + ".") || path.startsWith(e.path() + "["))) {
                if (best == null || e.path().length() > best.path().length() || (e.path().length() == best.path().length() && !e.variant().equals("*"))) {
                    best = e;
                }
            }
        }
        if (exact.size() > 1) {
            // an exact variant-specific entry wins over a wildcard-variant one; two at the same specificity are a duplicate
            List<Entry> specific = exact.stream().filter(x -> !x.variant().equals("*")).toList();
            return specific.size() == 1 ? specific : exact;
        }
        if (exact.size() == 1) {
            return exact;
        }
        return best == null ? List.of() : List.of(best);
    }

    static List<String> exercised(Entry e, Catalog catalog, Map<String, OracleReport> reports, Set<Check.Outcome> accepted) {
        if (e.property() == null) {
            return List.of(e.key() + ": no oracle property names its evidence");
        }
        boolean seen = false;
        boolean occurrence = false;
        List<String> outcomes = new ArrayList<>();
        for (String fixture : catalog.evidenceFixtures()) {
            OracleReport r = reports.get(fixture);
            for (Check c : r.checks()) {
                if (c.property().equals(e.property())) {
                    seen = true;
                    outcomes.add(fixture + " " + c.owner() + " " + c.outcome());
                    if (accepted.contains(c.outcome())) {
                        occurrence = true;
                    }
                    else if (c.outcome() == Check.Outcome.DEFECTIVE) {
                        return List.of(e.key() + ": evidence property " + e.property() + " is DEFECTIVE on " + fixture + " " + c.owner());
                    }
                }
            }
        }
        if (!seen) {
            return List.of(e.key() + ": evidence property " + e.property() + " is not one the oracle reported on the evidence fixtures " + catalog.evidenceFixtures());
        }
        if (!occurrence) {
            return List.of(e.key() + ": no exercised evidence: property " + e.property() + " had no accepted outcome with an occurrence on " + catalog.evidenceFixtures() + " (" + outcomes + ")");
        }
        return List.of();
    }

    /** {@code <class>.<attr>...} through the declared schema, or {@code declaration:<kind>.<field>} through the format's declaration definitions. */
    static String resolveDestination(SchemaCatalog schema, String destination) {
        try {
            if (destination.startsWith("declaration:")) {
                String rest = destination.substring("declaration:".length());
                int dot = rest.indexOf('.');
                String kind = dot < 0 ? rest : rest.substring(0, dot);
                String field = dot < 0 ? null : rest.substring(dot + 1);
                SchemaCatalog.Definition body = schema.object("declaration/" + kind + "_body");
                if (body == null) {
                    return "no declaration kind " + kind;
                }
                if (field != null && !body.attributes().containsKey(field)) {
                    return "declaration " + kind + " has no field " + field;
                }
                return null;
            }
            String root = destination.substring(0, destination.indexOf('.'));
            SchemaCatalog.Definition objectRoot = objectNamed(schema, root);
            if (!"*".equals(root) && schema.classNamed(root) == null && objectRoot != null) {
                // an object root (usage_observation.input_tokens): resolve from the object
                SchemaCatalog.Definition current = objectRoot;
                String[] parts = destination.split("\\.");
                for (int i = 1; i < parts.length; i++) {
                    String step = parts[i].endsWith("[]") ? parts[i].substring(0, parts[i].length() - 2) : parts[i];
                    SchemaCatalog.AttributeDef a = current.attributes().get(step);
                    if (a == null) {
                        return "unknown attribute " + step + " on " + current.key();
                    }
                    if (i < parts.length - 1) {
                        current = schema.object(a.objectType());
                        if (current == null) {
                            return "object " + a.objectType() + " not declared";
                        }
                    }
                }
                return null;
            }
            schema.resolve(destination);
            return null;
        }
        catch (IllegalArgumentException ex) {
            return ex.getMessage();
        }
    }

    /** An object by its bare name or its qualified key ({@code agentic_cli/usage_observation}). */
    static SchemaCatalog.Definition objectNamed(SchemaCatalog schema, String name) {
        if (schema.object(name) != null) {
            return schema.object(name);
        }
        for (Map.Entry<String, SchemaCatalog.Definition> o : schema.objects().entrySet()) {
            if (o.getKey().endsWith("/" + name) || name.equals(o.getValue().name())) {
                return o.getValue();
            }
        }
        return null;
    }

    static boolean ruleDeclared(Map<String, Json> contract, String rule) {
        if ("record_discriminator".equals(rule) || "owner_routing".equals(rule)) {
            return contract.containsKey(rule);
        }
        for (Json r : contract.get("rules").elements()) {
            if (rule.equals(SchemaCatalog.text(r, "id"))) {
                return true;
            }
        }
        return false;
    }

    static boolean testExists(String ref) {
        int hash = ref.indexOf('#');
        String cls = hash < 0 ? ref : ref.substring(0, hash);
        String method = hash < 0 ? null : ref.substring(hash + 1);
        for (Path root : TEST_SOURCES) {
            try (Stream<Path> s = Files.walk(root)) {
                for (Path p : s.filter(x -> x.getFileName().toString().equals(cls + ".java")).toList()) {
                    String src = Files.readString(p, StandardCharsets.UTF_8);
                    if (method == null || src.contains("void " + method + "(")) {
                        return true;
                    }
                }
            }
            catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        return false;
    }

    // ---- inputs ------------------------------------------------------------------------------------------------------

    static Catalog catalog(String profile) {
        Json raw = NeutralJson.readDocument(PROJECT.resolve(CATALOGS.get(profile)));
        assertThat(SchemaCatalog.text(raw, "kind")).isEqualTo("agent-journal/field-dispositions");
        assertThat(SchemaCatalog.text(raw, "profile")).isEqualTo(profile);
        List<Entry> entries = new ArrayList<>();
        for (Json e : raw.member("entries").elements()) {
            List<String> dest = new ArrayList<>();
            for (Json d : e.member("destination") == null ? List.<Json>of() : e.member("destination").elements()) {
                dest.add(d.text());
            }
            List<String> tests = new ArrayList<>();
            for (Json t : e.member("tests") == null ? List.<Json>of() : e.member("tests").elements()) {
                tests.add(t.text());
            }
            entries.add(new Entry(SchemaCatalog.text(e, "variant"), SchemaCatalog.text(e, "path"), e.member("subtree") instanceof Json.JBool b && b.value(),
                    SchemaCatalog.text(e, "disposition"), dest, SchemaCatalog.text(e, "rule"), SchemaCatalog.text(e.member("evidence"), "property"), tests, SchemaCatalog.text(e, "note"), e));
        }
        List<String> fixtures = new ArrayList<>();
        for (Json f : raw.member("evidence_fixtures").elements()) {
            fixtures.add(f.text());
        }
        return new Catalog(profile, SchemaCatalog.text(raw, "contract_file"), fixtures, entries, raw);
    }

    static Catalog withEntries(Catalog c, List<Entry> entries) {
        return new Catalog(c.profile(), c.contractFile(), c.evidenceFixtures(), entries, c.raw());
    }

    static List<Entry> replace(Catalog c, Entry old, Entry replacement) {
        List<Entry> out = new ArrayList<>();
        for (Entry e : c.entries()) {
            out.add(e == old ? replacement : e);
        }
        return out;
    }

    /** Observed (variant to paths): the accepted specification's inventory plus the raw inventory of every committed stream of the profile. */
    static Map<String, Set<String>> observed(String profile, Path work) {
        Map<String, Set<String>> out = new TreeMap<>();
        Json spec = NeutralJson.readDocument(PROJECT.resolve(SchemaCatalog.text(NeutralJson.readDocument(PROJECT.resolve(CATALOGS.get(profile))), "specification")));
        absorb(out, spec.path("inventory", "variants"));
        for (String fixture : PROFILE_FIXTURES.get(profile)) {
            Path copy = OcsfFixtures.copy(fixture, work.resolve("obs").resolve(fixture));
            NativeArtifact art = NativeArtifact.read(copy.resolve("native").resolve("stream.jsonl"));
            absorb(out, RawInventory.inventory(art, "stream.jsonl", profile).member("variants"));
        }
        return out;
    }

    private static void absorb(Map<String, Set<String>> out, Json variants) {
        for (Map.Entry<String, Json> v : ((Json.JObject) variants).members().entrySet()) {
            Set<String> paths = out.computeIfAbsent(v.getKey(), k -> new TreeSet<>());
            paths.addAll(((Json.JObject) v.getValue().member("paths")).members().keySet());
        }
    }

    static Map<String, OracleReport> reports(Catalog catalog, Path work) {
        Map<String, OracleReport> out = new LinkedHashMap<>();
        for (String fixture : catalog.evidenceFixtures()) {
            out.put(fixture, OcsfFixtures.verify(OcsfFixtures.copy(fixture, work.resolve(fixture))));
        }
        return out;
    }

    /**
     * The union of the declared schemas of several committed packages (extension classes and objects are complete in
     * each; core objects carry the attributes those packages used, so the union covers opaque records, transcripts and
     * rollouts too) plus the format's declaration definitions.
     */
    static SchemaCatalog schema(Path work) {
        Map<String, Json> classes = new LinkedHashMap<>();
        Map<String, Json> objects = new LinkedHashMap<>();
        Map<String, Json> types = new LinkedHashMap<>();
        Json format = null;
        for (String fixture : List.of(OcsfFixtures.CHILD, OcsfFixtures.CODEX, OcsfFixtures.CODEX_STREAM, "syn-claude-pair-transcript")) {
            OcsfPackage p = OcsfFixtures.open(OcsfFixtures.copy(fixture, work.resolve("schema").resolve(fixture)));
            if (format == null) {
                format = p.declarations.format();
            }
            Json closure = p.declarations.closure();
            mergeDefinitions(classes, closure.member("classes"));
            mergeDefinitions(objects, closure.member("objects"));
            if (closure.member("types") instanceof Json.JObject t) {
                types.putAll(t.members());
            }
        }
        objects.putAll(((Json.JObject) format.path("declaration_definitions", "objects")).members());
        Map<String, Json> merged = new LinkedHashMap<>();
        merged.put("classes", new Json.JObject(classes));
        merged.put("objects", new Json.JObject(objects));
        merged.put("types", new Json.JObject(types));
        return SchemaCatalog.fromClosure(new Json.JObject(merged));
    }

    /** Merges definitions by key, taking the union of their attributes. */
    private static void mergeDefinitions(Map<String, Json> into, Json section) {
        if (!(section instanceof Json.JObject s)) {
            return;
        }
        for (Map.Entry<String, Json> d : s.members().entrySet()) {
            Json existing = into.get(d.getKey());
            if (existing == null) {
                into.put(d.getKey(), d.getValue());
                continue;
            }
            Map<String, Json> attrs = new LinkedHashMap<>(((Json.JObject) existing.member("attributes")).members());
            if (d.getValue().member("attributes") instanceof Json.JObject a) {
                a.members().forEach(attrs::putIfAbsent);
            }
            Map<String, Json> body = new LinkedHashMap<>(((Json.JObject) existing).members());
            body.put("attributes", new Json.JObject(attrs));
            into.put(d.getKey(), new Json.JObject(body));
        }
    }

    static Map<String, Json> contract(String file) {
        return ((Json.JObject) NeutralJson.readDocument(MAPPINGS.resolve(file + ".json"))).members();
    }

    static String summary(Catalog c) {
        Map<String, Integer> by = new TreeMap<>();
        for (Entry e : c.entries()) {
            by.merge(e.disposition(), 1, Integer::sum);
        }
        return by.toString();
    }
}
