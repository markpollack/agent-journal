package io.github.markpollack.journal.tck.ocsf;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.markpollack.journal.tck.verify.Json;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Consumer;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The Tier 1 parity ledger: {@code foundation-parity.json} assigns every field of the pinned foundation inventory
 * ({@code foundation-fields.json}) to exactly one facet with its Journal correspondence, tier, definition state,
 * per-route availability and next action. This test checks the assignment is an exact set reconciliation (nothing
 * unassigned, nothing twice, nothing outside the inventory), that facets reference real catalog concepts, claims,
 * routes and resolvable destinations, and that each facet's state agrees with its definition and routes. It then joins
 * the facets with the catalog, the claims and this build's CLI coverage calculation and generates under
 * {@code target/mapping-catalog/}: {@code PARITY.md}, {@code PARITY-FIELDS.md} and {@code parity.json} (the ledger),
 * {@code TOPICS.md} and {@code topics.json} (the 44 catalog topics explained). Semantic definition, implemented
 * mapping, executed evidence, live exercise and approval are reported as separate dimensions; there is no composite
 * score. Facet records are reviewed claims; only the coverage states are executed evidence.
 */
class ParityLedgerTest {

    static final Path FILE = FoundationComparisonTest.CATALOG_DIR.resolve("foundation-parity.json");

    /** Gap ranking: states whose source fact is available first, then by how much of the meaning is missing. */
    static final List<String> GAP_ORDER = List.of("mapper_missing", "definition_missing", "partial", "definition_unresolved", "not_exercised", "source_limited");

    record Inputs(JsonNode parity, JsonNode inventory, JsonNode catalog, JsonNode october) {
        Inputs copy() {
            return new Inputs(parity.deepCopy(), inventory.deepCopy(), catalog, october);
        }
    }

    static Inputs inputs() {
        return new Inputs(FoundationComparisonTest.read(FILE), FoundationFieldsTest.inventory(), FoundationComparisonTest.read(FoundationComparisonTest.CATALOG_DIR.resolve("mapping-catalog.json")),
                FoundationComparisonTest.read(FoundationComparisonTest.CATALOG_DIR.resolve("foundation-inventory.json")));
    }

    static Set<String> vocabulary(JsonNode parity, String name) {
        Set<String> out = new TreeSet<>();
        parity.path("vocabularies").path(name).forEach(v -> out.add(v.asText()));
        return out;
    }

    static Map<String, JsonNode> inventoryFields(JsonNode inventory) {
        Map<String, JsonNode> out = new LinkedHashMap<>();
        for (JsonNode f : inventory.path("foundations")) {
            for (JsonNode x : f.path("fields")) {
                out.put(x.path("id").asText(), x);
            }
        }
        return out;
    }

    static Set<String> claimIds(JsonNode catalog) {
        Set<String> out = new TreeSet<>();
        catalog.path("concepts").forEach(c -> c.path("claims").forEach(k -> out.add(k.path("id").asText())));
        return out;
    }

    // ------------------------------------------------------------------------------------------ validation

    /**
     * The pinned Tier 1 denominator: the number of fields each pinned foundation revision declares. A field leaving the
     * inventory, or a changed pin, is a deliberate edit here, never a silent shrink (the gated re-derivation in
     * FoundationFieldsTest checks the inventory against the pinned files themselves).
     */
    static final Map<String, Integer> PINNED_DENOMINATOR = Map.of("otel_genai.semconv@547934afa90a34298441caf80c43b27255c3a292", 301,
            "openinference.semconv@48becd7a6098992c189ba86b837e5acf045270ef", 190, "weave.agents_schema@9c3780483c5c743daba1487a5f75e1fdf7c5c347", 213);

    /**
     * The proposed exclusions awaiting review, with the number of pinned fields each keeps in the denominator. A proposal
     * is visible until a recorded decision disposes of it; re-tiering, deleting, merging or adding one is a deliberate edit
     * of this roster, never a side effect of editing a facet.
     */
    static final Map<String, Integer> PROPOSED_EXCLUSIONS = Map.ofEntries(Map.entry("identity.hosted-project", 2), Map.entry("operation.agent-creation", 2),
            Map.entry("operation.mcp-protocol", 28), Map.entry("content.storage-references", 9), Map.entry("rag.embeddings", 10), Map.entry("rag.retrieval", 14),
            Map.entry("rag.reranker", 6), Map.entry("api.decision-models", 12), Map.entry("api.fetch-response", 2), Map.entry("hosting.weave-linkage", 7),
            Map.entry("tracing.span-naming", 8));

    /** The denominator and the proposed-exclusion roster against their pins. */
    static List<String> pinned(Inputs in) {
        List<String> problems = new ArrayList<>();
        Map<String, Integer> denominator = new TreeMap<>();
        for (JsonNode f : in.inventory().path("foundations")) {
            denominator.put(f.path("foundation").asText() + "@" + f.path("revision").asText(), f.path("fields").size());
        }
        if (!denominator.equals(new TreeMap<>(PINNED_DENOMINATOR))) {
            problems.add("denominator " + denominator + " is not the pinned " + new TreeMap<>(PINNED_DENOMINATOR)
                    + " (a pinned field left the inventory or a pin changed: edit PINNED_DENOMINATOR deliberately)");
        }
        Map<String, Integer> proposed = new TreeMap<>();
        for (JsonNode f : in.parity().path("facets")) {
            if ("out_of_scope_proposed".equals(f.path("tier").asText()) || "excluded_proposed".equals(f.path("state").asText())) {
                proposed.put(f.path("id").asText(), f.path("fields").size());
            }
        }
        if (!proposed.equals(new TreeMap<>(PROPOSED_EXCLUSIONS))) {
            problems.add("proposed exclusion roster " + proposed + " is not the pinned " + new TreeMap<>(PROPOSED_EXCLUSIONS)
                    + " (an exclusion was hidden, re-tiered, merged or added without a deliberate roster edit and a recorded decision)");
        }
        return problems;
    }

    /** Every reconciliation, reference and consistency problem; {@code destination} resolves a path or says why not. */
    static List<String> problems(Inputs in, Function<String, String> destination, Set<String> compatibility) {
        List<String> problems = new ArrayList<>();
        JsonNode p = in.parity();
        Map<String, JsonNode> fields = inventoryFields(in.inventory());
        Set<String> concepts = new TreeSet<>();
        in.catalog().path("concepts").forEach(c -> concepts.add(c.path("id").asText()));
        Set<String> claims = claimIds(in.catalog());
        List<String> routes = new ArrayList<>();
        p.path("routes").forEach(r -> routes.add(r.asText()));
        for (String r : routes) {
            if (!compatibility.contains(r)) {
                problems.add("route " + r + " is not a compatibility entry");
            }
        }
        Map<String, String> assigned = new TreeMap<>();
        Set<String> facetIds = new TreeSet<>();
        for (JsonNode f : p.path("facets")) {
            String id = f.path("id").asText();
            if (!facetIds.add(id)) {
                problems.add("duplicate facet " + id);
            }
            for (String k : List.of("group", "summary", "tier", "tier_reason", "definition", "state", "relation", "differences", "route_evidence", "action")) {
                if (f.path(k).asText().isBlank()) {
                    problems.add(id + ": no " + k);
                }
            }
            for (String[] v : new String[][] {{"tier", "tier"}, {"definition", "definition"}, {"state", "state"}, {"relation", "relation"}}) {
                if (!vocabulary(p, v[1]).contains(f.path(v[0]).asText())) {
                    problems.add(id + ": " + v[0] + " " + f.path(v[0]).asText() + " not in the vocabulary");
                }
            }
            if (f.path("fields").isEmpty()) {
                problems.add(id + ": assigns no field");
            }
            for (JsonNode x : f.path("fields")) {
                String fid = x.asText();
                if (!fields.containsKey(fid)) {
                    problems.add(id + ": field " + fid + " is not in the pinned inventory");
                }
                String prior = assigned.put(fid, id);
                if (prior != null) {
                    problems.add("field " + fid + " is assigned twice (" + prior + ", " + id + ")");
                }
            }
            for (JsonNode c : f.path("concepts")) {
                if (!concepts.contains(c.asText())) {
                    problems.add(id + ": concept " + c.asText() + " is not a catalog concept");
                }
            }
            for (JsonNode d : f.path("destinations")) {
                String why = destination.apply(d.asText());
                if (why != null) {
                    problems.add(id + ": destination " + d.asText() + " does not resolve: " + why);
                }
            }
            for (JsonNode e : f.path("evidence")) {
                if (e.asText().startsWith("claim:") && !claims.contains(e.asText().substring("claim:".length()))) {
                    problems.add(id + ": evidence " + e.asText() + " names no catalog claim");
                }
            }
            Map<String, String> byRoute = new LinkedHashMap<>();
            f.path("routes").fields().forEachRemaining(e -> byRoute.put(e.getKey(), e.getValue().asText()));
            if (!byRoute.keySet().equals(new java.util.LinkedHashSet<>(routes))) {
                problems.add(id + ": routes " + byRoute.keySet() + " are not exactly " + routes);
            }
            byRoute.forEach((r, s) -> {
                if (!vocabulary(p, "route").contains(s)) {
                    problems.add(id + ": route state " + s + " not in the vocabulary");
                }
            });
            problems.addAll(consistency(id, f, byRoute));
        }
        for (String fid : fields.keySet()) {
            if (!assigned.containsKey(fid)) {
                problems.add("field " + fid + " is not assigned to any facet");
            }
        }
        problems.addAll(pinned(in));
        return problems;
    }

    /** The facet's state must agree with its definition, destinations, tier and route availability. */
    static List<String> consistency(String id, JsonNode f, Map<String, String> routes) {
        List<String> problems = new ArrayList<>();
        String state = f.path("state").asText();
        String tier = f.path("tier").asText();
        String definition = f.path("definition").asText();
        boolean destinations = !f.path("destinations").isEmpty();
        boolean anyMapped = routes.containsValue("mapped");
        boolean anyRetained = routes.containsValue("retained_only");
        boolean proposal = f.hasNonNull("proposal") && !f.path("proposal").path("id").asText().isBlank();
        switch (state) {
            case "covered" -> {
                if (!"defined".equals(definition) || !destinations || !anyMapped || anyRetained) {
                    problems.add(id + ": covered needs a defined meaning, destinations, a mapped route and no retained-only route");
                }
            }
            case "mapper_missing" -> {
                if (!anyRetained) {
                    problems.add(id + ": mapper_missing needs a route on which the source fact is retained only");
                }
            }
            case "source_limited" -> {
                if (anyMapped || anyRetained) {
                    problems.add(id + ": source_limited cannot have a mapped or retained-only route");
                }
            }
            case "definition_missing" -> {
                if (!"missing".equals(definition) || destinations || !proposal && f.path("concepts").isEmpty()) {
                    problems.add(id + ": definition_missing has no definition or destination and names a proposal or pending concept");
                }
            }
            case "excluded_proposed" -> {
                if (!"out_of_scope_proposed".equals(tier)) {
                    problems.add(id + ": excluded_proposed is exactly the out_of_scope_proposed tier");
                }
            }
            case "tier3_consumer" -> {
                if (!"3".equals(tier)) {
                    problems.add(id + ": tier3_consumer is exactly tier 3");
                }
            }
            default -> {
            }
        }
        if ("out_of_scope_proposed".equals(tier) && !"excluded_proposed".equals(state)) {
            problems.add(id + ": a proposed exclusion has state excluded_proposed");
        }
        if ("1".equals(tier) && "missing".equals(definition) && !proposal && f.path("concepts").isEmpty()) {
            problems.add(id + ": a Tier 1 facet without a definition names a proposal or a pending concept");
        }
        if ("none".equals(f.path("relation").asText()) == destinations) {
            problems.add(id + ": relation none exactly when there are no destinations");
        }
        problems.addAll(aspects(id, f, routes));
        return problems;
    }

    /**
     * A facet whose support differs by part (a role, or marking versus meaning) states it per aspect: each aspect cites
     * exactly one of the facet's concepts (the aspects partition them, so no aspect borrows another's concept), its own
     * destinations among the facet's, and a state on every route. The facet is covered only when every aspect is mapped,
     * source-limited or not applicable on every route; a route is mapped for the facet only when some aspect is mapped
     * there. Whether each aspect's route state agrees with its concept's executed state is {@link #aspectEvidence}.
     */
    static List<String> aspects(String id, JsonNode f, Map<String, String> routes) {
        List<String> problems = new ArrayList<>();
        JsonNode aspects = f.path("aspects");
        if (aspects.isMissingNode() || aspects.isEmpty()) {
            return problems;
        }
        Set<String> facetConcepts = new TreeSet<>();
        f.path("concepts").forEach(c -> facetConcepts.add(c.asText()));
        Set<String> facetDestinations = new TreeSet<>();
        f.path("destinations").forEach(d -> facetDestinations.add(d.asText()));
        Map<String, String> cited = new TreeMap<>();
        boolean allSettled = true;
        Map<String, Boolean> mappedSomewhere = new TreeMap<>();
        for (JsonNode a : aspects) {
            String aid = id + "/" + a.path("id").asText();
            String concept = a.path("concept").asText();
            if (!facetConcepts.contains(concept)) {
                problems.add(aid + ": concept " + concept + " is not one of the facet's concepts");
            }
            String prior = cited.put(concept, aid);
            if (prior != null) {
                problems.add(aid + ": concept " + concept + " is already the evidence of " + prior);
            }
            for (JsonNode d : a.path("destinations")) {
                if (!facetDestinations.contains(d.asText())) {
                    problems.add(aid + ": destination " + d.asText() + " is not one of the facet's");
                }
            }
            Map<String, String> byRoute = new LinkedHashMap<>();
            a.path("routes").fields().forEachRemaining(e -> byRoute.put(e.getKey(), e.getValue().asText()));
            if (!byRoute.keySet().equals(routes.keySet())) {
                problems.add(aid + ": aspect routes " + byRoute.keySet() + " are not exactly " + routes.keySet());
            }
            byRoute.forEach((r, st) -> {
                if ("mapped".equals(st)) {
                    mappedSomewhere.put(r, true);
                }
            });
            allSettled = allSettled && byRoute.values().stream().allMatch(st -> Set.of("mapped", "source_limited", "not_applicable").contains(st));
        }
        for (String c : facetConcepts) {
            if (!cited.containsKey(c)) {
                problems.add(id + ": concept " + c + " is cited by no aspect");
            }
        }
        if ("covered".equals(f.path("state").asText()) && !allSettled) {
            problems.add(id + ": covered needs every aspect mapped, source-limited or not applicable on every route");
        }
        routes.forEach((r, st) -> {
            if ("mapped".equals(st) != mappedSomewhere.getOrDefault(r, false)) {
                problems.add(id + ": route " + r + " is " + st + " for the facet, but " + (mappedSomewhere.getOrDefault(r, false) ? "an aspect is" : "no aspect is") + " mapped there");
            }
        });
        return problems;
    }

    /**
     * Each aspect's route state must agree with what this build executed for its own concept on that route: mapped is
     * the aspect's declared executed state (verified unless it says otherwise, for example retained-only for the
     * portable marking of an unknown operation), source_limited is unavailable-at-source, unexercised is an unexercised
     * or unresolved concept, retained_only is retained-only. A route's state comes from that route's recordings only.
     */
    static List<String> aspectEvidence(JsonNode parity, Map<String, Map<String, Executed>> executed) {
        List<String> problems = new ArrayList<>();
        for (JsonNode f : parity.path("facets")) {
            for (JsonNode a : f.path("aspects")) {
                String aid = f.path("id").asText() + "/" + a.path("id").asText();
                Map<String, Executed> byRoute = executed.get(a.path("concept").asText());
                a.path("routes").fields().forEachRemaining(e -> {
                    Executed x = byRoute == null ? null : byRoute.get(e.getKey());
                    String word = x == null ? "no executed state" : MappingCatalogTest.word(x.state());
                    Set<String> allowed = switch (e.getValue().asText()) {
                        case "mapped" -> Set.of(a.path("executed").asText("verified"));
                        case "source_limited" -> Set.of("unavailable-at-source");
                        case "unexercised" -> Set.of("unexercised", "unresolved");
                        case "retained_only" -> Set.of("retained-only");
                        default -> null;
                    };
                    if (allowed != null && !allowed.contains(word)) {
                        problems.add(aid + " on " + e.getKey() + ": aspect state " + e.getValue().asText() + " but concept " + a.path("concept").asText() + " executed " + word
                                + " there (expected " + allowed + ")");
                    }
                });
            }
        }
        return problems;
    }

    // ------------------------------------------------------------------------------------------ tests

    @Test
    void everyPinnedFieldIsAccountedForExactlyOnce(@TempDir Path work) {
        SchemaCatalog schema = FieldDispositionTest.schema(work);
        Inputs in = inputs();
        List<String> problems = problems(in, d -> FieldDispositionTest.resolveDestination(schema, d), MappingCatalogTest.compatibilityEntries());
        assertThat(problems).isEmpty();
        assertThat(inventoryFields(in.inventory())).hasSizeGreaterThan(600);
    }

    @Test
    void eachCorruptionIsDetected(@TempDir Path work) {
        SchemaCatalog schema = FieldDispositionTest.schema(work);
        Function<String, String> dest = d -> FieldDispositionTest.resolveDestination(schema, d);
        Set<String> compat = MappingCatalogTest.compatibilityEntries();
        Map<String, Consumer<Inputs>> corruptions = new LinkedHashMap<>();
        corruptions.put("is not assigned to any facet", in -> ((ArrayNode) facet(in, "usage.cache-write").path("fields")).remove(0));
        corruptions.put("is assigned twice", in -> ((ArrayNode) facet(in, "usage.output").path("fields")).add(facet(in, "usage.cache-read").path("fields").get(0).asText()));
        corruptions.put("is not in the pinned inventory", in -> ((ArrayNode) facet(in, "usage.output").path("fields")).add("otel_genai.semconv:attribute:gen_ai.usage.invented_tokens"));
        corruptions.put("is not a catalog concept", in -> ((ArrayNode) facet(in, "usage.output").path("concepts")).add("no-such-concept"));
        corruptions.put("does not resolve", in -> ((ArrayNode) facet(in, "usage.output").path("destinations")).add("usage_observation.input_tokens"));
        corruptions.put("covered needs", in -> ((ObjectNode) facet(in, "usage.output").path("routes")).put("codex-cli/exec-json-stream/0.160.1", "retained_only"));
        corruptions.put("mapper_missing needs", in -> ((ObjectNode) facet(in, "time.durations").path("routes")).put("claude-code/stream-json/2.1.304-stdout", "source_limited"));
        corruptions.put("a proposed exclusion has state", in -> ((ObjectNode) facet(in, "rag.embeddings")).put("state", "source_limited"));
        corruptions.put("names no catalog claim", in -> ((ArrayNode) facet(in, "usage.output").path("evidence")).add("claim:no.such.claim"));
        corruptions.put("not in the vocabulary", in -> ((ObjectNode) facet(in, "usage.output")).put("tier", "1.5"));
        corruptions.put("state accepted not in the vocabulary", in -> ((ObjectNode) facet(in, "rag.embeddings")).put("state", "accepted"));
        corruptions.put("route state verified not in the vocabulary", in -> ((ObjectNode) facet(in, "usage.output").path("routes")).put("codex-cli/exec-json-stream/0.160.1", "verified"));
        corruptions.put("are not exactly", in -> ((ObjectNode) facet(in, "usage.output").path("routes")).remove("codex-cli/exec-json-stream/0.160.1"));
        corruptions.put("duplicate facet", in -> ((ArrayNode) in.parity().path("facets")).add(facet(in, "usage.output").deepCopy()));
        corruptions.put("assigns no field", in -> ((ArrayNode) facet(in, "api.fetch-response").path("fields")).removeAll());
        // a proposed exclusion hidden by re-tiering it consistently: every other check stays green
        corruptions.put("proposed exclusion roster", in -> ((ObjectNode) facet(in, "rag.embeddings")).put("tier", "3").put("state", "tier3_consumer"));
        // a pinned field removed from the inventory and from its facet together: assignment stays exact, the denominator shrinks
        corruptions.put("is not the pinned", in -> {
            JsonNode first = in.inventory().path("foundations").get(0);
            String fid = first.path("fields").get(0).path("id").asText();
            ((ArrayNode) first.path("fields")).remove(0);
            for (JsonNode f : in.parity().path("facets")) {
                ArrayNode fs = (ArrayNode) f.path("fields");
                for (int i = 0; i < fs.size(); i++) {
                    if (fid.equals(fs.get(i).asText())) {
                        fs.remove(i);
                        return;
                    }
                }
            }
        });
        Inputs clean = inputs();
        assertThat(problems(clean, dest, compat)).isEmpty();
        for (Map.Entry<String, Consumer<Inputs>> c : corruptions.entrySet()) {
            Inputs bad = clean.copy();
            c.getValue().accept(bad);
            assertThat(problems(bad, dest, compat)).as(c.getKey()).anyMatch(x -> x.contains(c.getKey()));
        }
    }

    /**
     * Facet aspects agree with this build's executed concept states per route, and each way of overstating them is
     * detected: a role claimed mapped on a route whose source does not report it, a role the stream never demonstrated
     * claimed mapped, retained bytes upgraded to verified meaning, required evidence removed, an aspect borrowing
     * another aspect's concept or one outside its facet, and a covered facet with an unsettled aspect.
     */
    @Test
    void everyFacetAspectAgreesWithItsConceptsExecutedStatePerRoute(@TempDir Path work) {
        SchemaCatalog schema = FieldDispositionTest.schema(work);
        Function<String, String> dest = d -> FieldDispositionTest.resolveDestination(schema, d);
        Set<String> compat = MappingCatalogTest.compatibilityEntries();
        Map<String, Map<String, Executed>> executed = executed(MappingCatalogTest.catalogDocument(), work);
        Inputs clean = inputs();
        assertThat(aspectEvidence(clean.parity(), executed)).isEmpty();
        assertThat(problems(clean, dest, compat)).isEmpty();
        String claude = "claude-code/stream-json/2.1.304-stdout";
        String codex = "codex-cli/exec-json-stream/0.160.1";
        Map<String, Consumer<Inputs>> evidence = new LinkedHashMap<>();
        evidence.put("content.roles/user on " + codex, in -> ((ObjectNode) aspect(in, "content.roles", "user").path("routes")).put(codex, "mapped"));
        evidence.put("content.roles/injected-context on " + claude, in -> ((ObjectNode) aspect(in, "content.roles", "injected-context").path("routes")).put(claude, "mapped"));
        evidence.put("operation.unknown/marking-and-retention on " + claude, in -> ((ObjectNode) aspect(in, "operation.unknown", "marking-and-retention")).put("executed", "verified"));
        evidence.put("content.roles/tool on " + codex, in -> ((ObjectNode) aspect(in, "content.roles", "tool")).put("concept", "injected-context"));
        for (Map.Entry<String, Consumer<Inputs>> c : evidence.entrySet()) {
            Inputs bad = clean.copy();
            c.getValue().accept(bad);
            assertThat(aspectEvidence(bad.parity(), executed)).as(c.getKey()).anyMatch(x -> x.startsWith(c.getKey()));
        }
        Map<String, Consumer<Inputs>> structure = new LinkedHashMap<>();
        structure.put("is cited by no aspect", in -> ((ArrayNode) facet(in, "content.roles").path("aspects")).remove(0));
        structure.put("is already the evidence of", in -> ((ObjectNode) aspect(in, "content.roles", "injected-context")).put("concept", "user-submission"));
        structure.put("is not one of the facet's concepts", in -> ((ObjectNode) aspect(in, "content.roles", "injected-context")).put("concept", "launch-and-session-settings"));
        structure.put("covered needs every aspect", in -> ((ObjectNode) facet(in, "content.roles")).put("state", "covered"));
        structure.put("no aspect is mapped there", in -> {
            ((ObjectNode) aspect(in, "operation.unknown", "marking-and-retention").path("routes")).put(claude, "unexercised");
        });
        structure.put("are not exactly", in -> ((ObjectNode) aspect(in, "content.roles", "assistant").path("routes")).remove(codex));
        for (Map.Entry<String, Consumer<Inputs>> c : structure.entrySet()) {
            Inputs bad = clean.copy();
            c.getValue().accept(bad);
            assertThat(problems(bad, dest, compat)).as(c.getKey()).anyMatch(x -> x.contains(c.getKey()));
        }
    }

    static JsonNode aspect(Inputs in, String facet, String id) {
        for (JsonNode a : facet(in, facet).path("aspects")) {
            if (id.equals(a.path("id").asText())) {
                return a;
            }
        }
        throw new AssertionError("no aspect " + facet + "/" + id);
    }

    static JsonNode facet(Inputs in, String id) {
        for (JsonNode f : in.parity().path("facets")) {
            if (id.equals(f.path("id").asText())) {
                return f;
            }
        }
        throw new AssertionError("no facet " + id);
    }

    /**
     * The usage foundation records must not state that Journal never derives a tranche while a reviewed example shows
     * a derived component (the stale wording SEM-CLOSE left behind): definition, examples and comparison agree.
     */
    @Test
    void theUsageComparisonAgreesWithTheReviewedExamples() throws IOException {
        boolean derivedInExamples = false;
        try (var files = Files.list(FoundationComparisonTest.CATALOG_DIR.resolve("examples"))) {
            for (Path p : files.filter(x -> x.toString().endsWith(".json")).toList()) {
                for (JsonNode e : FoundationComparisonTest.read(p).path("expected")) {
                    var it = e.path("values").fields();
                    while (it.hasNext()) {
                        var v = it.next();
                        derivedInExamples |= v.getKey().endsWith("/component_basis_id") && v.getValue().asInt() == 2 || v.getKey().endsWith("/conditional_value");
                    }
                }
            }
        }
        assertThat(derivedInExamples).as("a reviewed example shows a derived usage component or a conditional derivation").isTrue();
        for (JsonNode c : inputs().catalog().path("concepts")) {
            if (!"usage-components-and-cache".equals(c.path("id").asText())) {
                continue;
            }
            List<String> texts = new ArrayList<>();
            texts.add(c.path("decision").path("reason").asText());
            c.path("foundations").forEach(f -> texts.add(f.path("reason").asText()));
            assertThat(texts).noneMatch(t -> t.toLowerCase(java.util.Locale.ROOT).contains("never derive"));
        }
    }

    // ------------------------------------------------------------------------------------------ generation

    /** Per concept and admitted route: the combined coverage state and how many live recordings verified it. */
    record Executed(String state, int recordings, int verified, int liveVerified) {
    }

    static Map<String, Map<String, Executed>> executed(Json catalog, Path work) {
        Map<MappingCatalogTest.Route, Path> fixtures = new LinkedHashMap<>();
        Map<MappingCatalogTest.Route, OracleReport> reports = new LinkedHashMap<>();
        for (MappingCatalogTest.Route r : MappingCatalogTest.ROUTES) {
            Path fixture = OcsfFixtures.copy(r.fixture(), work.resolve("states"));
            fixtures.put(r, fixture);
            reports.put(r, OcsfFixtures.verify(fixture));
        }
        Map<String, Map<String, Executed>> out = new LinkedHashMap<>();
        for (Json e : catalog.member("concepts").elements()) {
            Map<String, Executed> byRoute = new LinkedHashMap<>();
            for (MappingCatalogTest.Column col : MappingCatalogTest.COLUMNS) {
                Map<String, String> per = new LinkedHashMap<>();
                int verified = 0;
                int live = 0;
                for (MappingCatalogTest.Route r : MappingCatalogTest.recordings(col.entry())) {
                    String s = CatalogCoverage.state(catalog, e, r, fixtures.get(r), reports.get(r));
                    per.put(r.fixture(), s);
                    if ("verified".equals(MappingCatalogTest.word(s))) {
                        verified++;
                        live += r.label().contains("live") ? 1 : 0;
                    }
                }
                byRoute.put(col.entry(), new Executed(MappingCatalogTest.combine(per), per.size(), verified, live));
            }
            out.put(SchemaCatalog.text(e, "id"), byRoute);
        }
        return out;
    }

    @Test
    void theLedgerAndTheTopicExplanationAreGenerated(@TempDir Path work) throws IOException {
        SchemaCatalog schema = FieldDispositionTest.schema(work);
        Inputs in = inputs();
        assertThat(problems(in, d -> FieldDispositionTest.resolveDestination(schema, d), MappingCatalogTest.compatibilityEntries())).isEmpty();
        Json catalog = MappingCatalogTest.catalogDocument();
        Map<String, Map<String, Executed>> executed = executed(catalog, work);
        assertThat(aspectEvidence(in.parity(), executed)).isEmpty();
        Map<String, String> approval = SemanticClaims.approvalByConcept(catalog);
        Path out = MappingCatalogTest.outputDirectory();
        Map<String, Object> ledger = ledger(in, executed, approval);
        Files.writeString(out.resolve("parity.json"), MappingCatalogTest.PRETTY.writeValueAsString(ledger) + "\n", StandardCharsets.UTF_8);
        Files.writeString(out.resolve("PARITY.md"), parityMarkdown(in, ledger), StandardCharsets.UTF_8);
        Files.writeString(out.resolve("PARITY-FIELDS.md"), fieldsMarkdown(in), StandardCharsets.UTF_8);
        Map<String, Object> topics = topics(in, executed, approval);
        Files.writeString(out.resolve("topics.json"), MappingCatalogTest.PRETTY.writeValueAsString(topics) + "\n", StandardCharsets.UTF_8);
        Files.writeString(out.resolve("TOPICS.md"), topicsMarkdown(in, topics), StandardCharsets.UTF_8);
        assertThat(Files.readString(out.resolve("PARITY.md"))).contains("What already works").contains("Remaining Tier 1 gaps");
        assertThat(((List<?>) topics.get("topics"))).hasSize(in.catalog().path("concepts").size());
    }

    static List<String> routes(JsonNode parity) {
        List<String> out = new ArrayList<>();
        parity.path("routes").forEach(r -> out.add(r.asText()));
        return out;
    }

    static String foundationOf(String fieldId) {
        return fieldId.substring(0, fieldId.indexOf(':'));
    }

    static Map<String, Integer> octoberRows(JsonNode october) {
        Map<String, Integer> out = new TreeMap<>();
        for (JsonNode r : october.path("rows")) {
            for (String k : List.of("concept", "new_concept")) {
                if (r.hasNonNull(k) && !r.path(k).asText().isBlank()) {
                    out.merge(r.path(k).asText(), 1, Integer::sum);
                }
            }
        }
        return out;
    }

    static Map<String, JsonNode> conceptsById(JsonNode catalog) {
        Map<String, JsonNode> out = new LinkedHashMap<>();
        catalog.path("concepts").forEach(c -> out.put(c.path("id").asText(), c));
        return out;
    }

    static Map<String, Object> ledger(Inputs in, Map<String, Map<String, Executed>> executed, Map<String, String> approval) {
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("kind", "agent-journal/parity-ledger");
        doc.put("derived_by", "ParityLedgerTest from foundation-fields.json, foundation-parity.json, mapping-catalog.json, foundation-inventory.json and this run's CLI coverage calculation");
        doc.put("catalog_version", in.catalog().path("version").asText());
        Map<String, Object> pins = new LinkedHashMap<>();
        for (JsonNode f : in.inventory().path("foundations")) {
            pins.put(f.path("foundation").asText(), Map.of("revision", f.path("revision").asText(), "fields", f.path("total").asInt()));
        }
        doc.put("foundations", pins);
        doc.put("routes", routes(in.parity()));
        Map<String, JsonNode> concepts = conceptsById(in.catalog());
        Map<String, Integer> october = octoberRows(in.october());
        Map<String, JsonNode> fields = inventoryFields(in.inventory());
        List<Map<String, Object>> facets = new ArrayList<>();
        for (JsonNode f : in.parity().path("facets")) {
            Map<String, Object> m = new LinkedHashMap<>();
            for (String k : List.of("id", "group", "summary", "tier", "tier_reason", "definition", "state", "relation", "differences", "routes", "route_evidence", "destinations",
                    "proposal", "evidence", "action")) {
                m.put(k, f.get(k));
            }
            if (f.has("aspects")) {
                m.put("aspects", f.get("aspects"));
            }
            if (f.has("excludes")) {
                m.put("excludes", f.get("excludes"));
            }
            Map<String, Integer> counts = new TreeMap<>();
            f.path("fields").forEach(x -> counts.merge(foundationOf(x.asText()), 1, Integer::sum));
            m.put("field_counts", counts);
            List<Map<String, Object>> cs = new ArrayList<>();
            for (JsonNode c : f.path("concepts")) {
                String cid = c.asText();
                JsonNode concept = concepts.get(cid);
                Map<String, Object> cm = new LinkedHashMap<>();
                cm.put("id", cid);
                cm.put("disposition", concept.path("disposition").asText());
                Map<String, Object> ex = new LinkedHashMap<>();
                executed.get(cid).forEach((r, e) -> ex.put(r, Map.of("state", e.state(), "recordings", e.recordings(), "verified", e.verified(), "live_verified", e.liveVerified())));
                cm.put("executed", ex);
                cm.put("approval", approval.get(cid));
                List<String> props = new ArrayList<>();
                concept.path("oracle").forEach(o -> o.path("properties").forEach(p -> props.add(o.path("profile").asText() + "/" + p.asText())));
                cm.put("independent_checks", props);
                cm.put("tests", concept.path("tests").size());
                cm.put("mapping_rules", concept.path("mapping").size());
                cm.put("october_ledger_rows", october.getOrDefault(cid, 0));
                cm.put("observations", concept.path("observations").size());
                cs.add(cm);
            }
            m.put("concepts", cs);
            m.put("fields", f.path("fields"));
            facets.add(m);
        }
        doc.put("facets", facets);
        List<Map<String, Object>> rows = new ArrayList<>();
        for (JsonNode f : in.parity().path("facets")) {
            for (JsonNode x : f.path("fields")) {
                JsonNode field = fields.get(x.asText());
                Map<String, Object> r = new LinkedHashMap<>();
                r.put("id", x.asText());
                r.put("kind", field.path("kind").asText());
                r.put("locator", field.path("locator").asText());
                r.put("facet", f.path("id").asText());
                r.put("tier", f.path("tier").asText());
                r.put("state", f.path("state").asText());
                rows.add(r);
            }
        }
        rows.sort(Comparator.comparing(r -> (String) r.get("id")));
        doc.put("fields", rows);
        return doc;
    }

    static String cell(String s) {
        return s == null ? "" : s.replace("|", "\\|").replace("\n", " ");
    }

    static String routeLabel(String route) {
        return route.startsWith("claude") ? "Claude 2.1.304" : route.startsWith("codex") ? "Codex 0.160.1" : route;
    }

    @SuppressWarnings("unchecked")
    static String parityMarkdown(Inputs in, Map<String, Object> ledger) {
        JsonNode p = in.parity();
        List<String> routes = routes(p);
        List<Map<String, Object>> facets = (List<Map<String, Object>>) ledger.get("facets");
        StringBuilder md = new StringBuilder("# Tier 1 foundation parity ledger\n\n");
        md.append("Generated by `ParityLedgerTest`; do not edit. Every declared field of the three Tier 1 foundations at their pins (`foundation-fields.json`, derived mechanically from the pinned files) ")
                .append("is assigned to exactly one reviewed facet (`foundation-parity.json`) with its Journal correspondence, tier, semantic-definition state, per-route availability and next action. ")
                .append("Five dimensions are reported apart and never combined into one score: the semantic definition (reviewed), the implemented mapping per admitted route (reviewed), ")
                .append("executed evidence (this build's CLI coverage calculation over the committed recordings), live exercise (verified live recordings) and the Owner's approval of claims. ")
                .append("A facet's route state says whether the source supplies the fact on that route: `mapped`, `retained_only` (preserved raw, not canonical parity), `source_limited` (the route does not supply it), ")
                .append("`unexercised` (declared and mapped but no recording exercises it) or `not_applicable`. Facet records are reviewed claims, not executed tests.\n\n");
        md.append("## 1. Denominators\n\n| Foundation | Pin | Fields | By kind |\n|---|---|---|---|\n");
        for (JsonNode f : in.inventory().path("foundations")) {
            md.append("| `").append(f.path("foundation").asText()).append("` | `").append(f.path("revision").asText(), 0, 12).append("` | ").append(f.path("total").asInt()).append(" | ")
                    .append(cell(f.path("counts").toString())).append(" |\n");
        }
        int total = 0;
        for (JsonNode f : in.inventory().path("foundations")) {
            total += f.path("total").asInt();
        }
        md.append("\nTotal: ").append(total).append(" fields in ").append(p.path("facets").size()).append(" facets. Declared boundaries and exclusions of each inventory:\n\n");
        for (JsonNode f : in.inventory().path("foundations")) {
            md.append("- `").append(f.path("foundation").asText()).append("`: ").append(cell(f.path("boundary").asText())).append(" Excluded: ");
            List<String> ex = new ArrayList<>();
            f.path("excluded").forEach(x -> ex.add(x.path("what").asText() + " (" + x.path("reason").asText() + ")"));
            md.append(cell(String.join("; ", ex))).append('\n');
        }
        md.append("\nThe October 2026 teardown denominators stay separate and unchanged: ").append(in.october().path("rows").size())
                .append(" ledger rows (`foundation-inventory.json`) and the catalog's recorded integration observations; per-concept counts appear in `parity.json`.\n\n");
        // tiers by foundation
        Map<String, Map<String, Integer>> tiers = new TreeMap<>();
        for (Map<String, Object> f : facets) {
            Map<String, Integer> counts = (Map<String, Integer>) f.get("field_counts");
            String tier = ((JsonNode) f.get("tier")).asText();
            counts.forEach((fd, n) -> tiers.computeIfAbsent(fd, k -> new TreeMap<>()).merge(tier, n, Integer::sum));
        }
        md.append("## 2. Tier assignment (fields)\n\n| Foundation | Tier 1 | Tier 2 | Tier 3 | Proposed exclusion (awaiting review) |\n|---|---|---|---|---|\n");
        tiers.forEach((fd, t) -> md.append("| `").append(fd).append("` | ").append(t.getOrDefault("1", 0)).append(" | ").append(t.getOrDefault("2", 0)).append(" | ")
                .append(t.getOrDefault("3", 0)).append(" | ").append(t.getOrDefault("out_of_scope_proposed", 0)).append(" |\n"));
        // tier 1 dimensions
        List<Map<String, Object>> tier1 = facets.stream().filter(f -> "1".equals(((JsonNode) f.get("tier")).asText())).toList();
        md.append("\n## 3. Tier 1 by dimension\n\n");
        md.append("**Semantic definition** (fields / facets): ");
        md.append(dimension(tier1, f -> ((JsonNode) f.get("definition")).asText())).append("\n\n");
        md.append("**Facet state** (fields / facets): ").append(dimension(tier1, f -> ((JsonNode) f.get("state")).asText())).append("\n\n");
        for (String r : routes) {
            md.append("**Implemented mapping, ").append(routeLabel(r)).append("** (fields / facets): ").append(dimension(tier1, f -> ((JsonNode) f.get("routes")).path(r).asText())).append("\n\n");
        }
        for (String r : routes) {
            md.append("**Executed evidence, ").append(routeLabel(r)).append("** (Tier 1 facets by their concepts' coverage state; a facet with several concepts counts once per concept): ")
                    .append(executedDimension(tier1, r, false)).append("\n\n");
            md.append("**Live exercise, ").append(routeLabel(r)).append("** (concepts verified on at least one live recording / referenced): ").append(executedDimension(tier1, r, true)).append("\n\n");
        }
        md.append("**Approval** (Tier 1 facets by their concepts' claims): ").append(approvalDimension(tier1)).append("\n\n");
        // what works
        md.append("## 4. What already works\n\nTier 1 facets whose meaning is defined and mapped on at least one admitted route, with the executed coverage state of their concepts per route.\n\n");
        md.append("| Facet | Fields | State |");
        routes.forEach(r -> md.append(' ').append(routeLabel(r)).append(" (mapping; executed) |"));
        md.append(" Approval |\n|---|---|---|");
        routes.forEach(r -> md.append("---|"));
        md.append("---|\n");
        for (Map<String, Object> f : tier1) {
            JsonNode rt = (JsonNode) f.get("routes");
            boolean mapped = routes.stream().anyMatch(r -> "mapped".equals(rt.path(r).asText()));
            String state = ((JsonNode) f.get("state")).asText();
            if (!mapped || !Set.of("covered", "derivable", "partial", "not_exercised").contains(state)) {
                continue;
            }
            md.append("| `").append(((JsonNode) f.get("id")).asText()).append("` ").append(cell(((JsonNode) f.get("summary")).asText())).append(" | ").append(fieldCount(f)).append(" | ").append(state).append(" |");
            for (String r : routes) {
                md.append(' ').append(rt.path(r).asText()).append("; ").append(cell(executedCell(f, r))).append(" |");
            }
            md.append(' ').append(cell(approvalCell(f))).append(" |\n");
        }
        // gaps
        md.append("\n## 5. Remaining Tier 1 gaps, ranked\n\nRanked by rule: facets whose source fact is available on an admitted route (retained only) first; then by state (")
                .append(String.join(", ", GAP_ORDER)).append("); then by field count. Source-limited facets keep their canonical obligation and are not route parity.\n\n");
        md.append("| Rank | Facet | State | Definition | Fields |");
        routes.forEach(r -> md.append(' ').append(routeLabel(r)).append(" |"));
        md.append(" Action |\n|---|---|---|---|---|");
        routes.forEach(r -> md.append("---|"));
        md.append("---|\n");
        List<Map<String, Object>> gaps = new ArrayList<>(tier1.stream().filter(f -> GAP_ORDER.contains(((JsonNode) f.get("state")).asText())).toList());
        gaps.sort(Comparator.<Map<String, Object>>comparingInt(f -> routes.stream().anyMatch(r -> "retained_only".equals(((JsonNode) f.get("routes")).path(r).asText())) ? 0 : 1)
                .thenComparingInt(f -> GAP_ORDER.indexOf(((JsonNode) f.get("state")).asText())).thenComparingInt(f -> -fieldCount(f)));
        int rank = 1;
        for (Map<String, Object> f : gaps) {
            md.append("| ").append(rank++).append(" | `").append(((JsonNode) f.get("id")).asText()).append("` ").append(cell(((JsonNode) f.get("summary")).asText())).append(" | ")
                    .append(((JsonNode) f.get("state")).asText()).append(" | ").append(((JsonNode) f.get("definition")).asText()).append(" | ").append(fieldCount(f)).append(" |");
            JsonNode rt = (JsonNode) f.get("routes");
            routes.forEach(r -> md.append(' ').append(rt.path(r).asText()).append(" |"));
            md.append(' ').append(cell(((JsonNode) f.get("action")).asText())).append(" |\n");
        }
        // other tiers
        md.append("\n## 6. Tier 2, Tier 3 and proposed exclusions\n\nProposed exclusions are visible proposals for review, not decisions.\n\n| Facet | Tier | Fields | Reason |\n|---|---|---|---|\n");
        for (Map<String, Object> f : facets) {
            String tier = ((JsonNode) f.get("tier")).asText();
            if (!"1".equals(tier)) {
                md.append("| `").append(((JsonNode) f.get("id")).asText()).append("` ").append(cell(((JsonNode) f.get("summary")).asText())).append(" | ").append(tier).append(" | ").append(fieldCount(f)).append(" | ")
                        .append(cell(((JsonNode) f.get("tier_reason")).asText())).append(" |\n");
            }
        }
        // detail
        md.append("\n## 7. Facet detail\n");
        String group = null;
        for (Map<String, Object> f : facets) {
            String g = ((JsonNode) f.get("group")).asText();
            if (!g.equals(group)) {
                md.append("\n### ").append(g).append("\n");
                group = g;
            }
            md.append("\n**`").append(((JsonNode) f.get("id")).asText()).append("`** — ").append(cell(((JsonNode) f.get("summary")).asText())).append(". Tier ").append(((JsonNode) f.get("tier")).asText())
                    .append("; definition ").append(((JsonNode) f.get("definition")).asText()).append("; state ").append(((JsonNode) f.get("state")).asText()).append("; relation ")
                    .append(((JsonNode) f.get("relation")).asText()).append(". Fields: ").append(f.get("field_counts")).append(".\n\n");
            md.append("- Correspondence: ").append(cell(((JsonNode) f.get("differences")).asText()));
            JsonNode d = (JsonNode) f.get("destinations");
            if (!d.isEmpty()) {
                List<String> ds = new ArrayList<>();
                d.forEach(x -> ds.add("`" + x.asText() + "`"));
                md.append(" Destinations: ").append(String.join(", ", ds)).append('.');
            }
            md.append("\n- Routes: ").append(cell(((JsonNode) f.get("route_evidence")).asText())).append('\n');
            md.append("- Concepts: ").append(cell(conceptCell(f))).append('\n');
            JsonNode proposal = (JsonNode) f.get("proposal");
            if (proposal != null && !proposal.isNull()) {
                md.append("- Proposed concept `").append(proposal.path("id").asText()).append("`: ").append(cell(proposal.path("question").asText())).append('\n');
            }
            md.append("- Tier reason: ").append(cell(((JsonNode) f.get("tier_reason")).asText())).append("\n- Action: ").append(cell(((JsonNode) f.get("action")).asText())).append('\n');
        }
        return md.toString();
    }

    @SuppressWarnings("unchecked")
    static int fieldCount(Map<String, Object> f) {
        return ((Map<String, Integer>) f.get("field_counts")).values().stream().mapToInt(Integer::intValue).sum();
    }

    static String dimension(List<Map<String, Object>> facets, Function<Map<String, Object>, String> key) {
        Map<String, int[]> counts = new TreeMap<>();
        for (Map<String, Object> f : facets) {
            int[] c = counts.computeIfAbsent(key.apply(f), k -> new int[2]);
            c[0] += fieldCount(f);
            c[1]++;
        }
        List<String> parts = new ArrayList<>();
        counts.forEach((k, v) -> parts.add(k + " " + v[0] + " / " + v[1]));
        return String.join("; ", parts);
    }

    @SuppressWarnings("unchecked")
    static String executedDimension(List<Map<String, Object>> facets, String route, boolean live) {
        Map<String, Integer> counts = new TreeMap<>();
        int referenced = 0;
        int liveCount = 0;
        for (Map<String, Object> f : facets) {
            for (Map<String, Object> c : (List<Map<String, Object>>) f.get("concepts")) {
                Map<String, Object> e = (Map<String, Object>) ((Map<String, Object>) c.get("executed")).get(route);
                referenced++;
                liveCount += ((Integer) e.get("live_verified")) > 0 ? 1 : 0;
                counts.merge(MappingCatalogTest.word((String) e.get("state")), 1, Integer::sum);
            }
            if (((List<?>) f.get("concepts")).isEmpty()) {
                counts.merge("no catalog concept", 1, Integer::sum);
            }
        }
        if (live) {
            return liveCount + " / " + referenced;
        }
        List<String> parts = new ArrayList<>();
        counts.forEach((k, v) -> parts.add(k + " " + v));
        return String.join("; ", parts);
    }

    @SuppressWarnings("unchecked")
    static String approvalDimension(List<Map<String, Object>> facets) {
        Map<String, Integer> counts = new TreeMap<>();
        for (Map<String, Object> f : facets) {
            String a = approvalCell(f);
            String key = a.contains("approved ") ? "has an approved claim" : a.contains("proposed ") ? "claims proposed, none approved" : "no claim recorded";
            counts.merge(key, 1, Integer::sum);
        }
        List<String> parts = new ArrayList<>();
        counts.forEach((k, v) -> parts.add(k + " " + v));
        return String.join("; ", parts);
    }

    @SuppressWarnings("unchecked")
    static String executedCell(Map<String, Object> f, String route) {
        List<String> out = new ArrayList<>();
        for (Map<String, Object> c : (List<Map<String, Object>>) f.get("concepts")) {
            Map<String, Object> e = (Map<String, Object>) ((Map<String, Object>) c.get("executed")).get(route);
            out.add(c.get("id") + ": " + MappingCatalogTest.word((String) e.get("state")) + " " + e.get("verified") + "/" + e.get("recordings") + " (live " + e.get("live_verified") + ")");
        }
        return out.isEmpty() ? "no catalog concept" : String.join("; ", out);
    }

    @SuppressWarnings("unchecked")
    static String approvalCell(Map<String, Object> f) {
        Set<String> out = new TreeSet<>();
        for (Map<String, Object> c : (List<Map<String, Object>>) f.get("concepts")) {
            String a = (String) c.get("approval");
            if (a != null && !a.startsWith("no claim")) {
                out.add(a);
            }
        }
        return out.isEmpty() ? "no claim recorded" : String.join("; ", out);
    }

    @SuppressWarnings("unchecked")
    static String conceptCell(Map<String, Object> f) {
        List<String> out = new ArrayList<>();
        for (Map<String, Object> c : (List<Map<String, Object>>) f.get("concepts")) {
            out.add("`" + c.get("id") + "` (" + c.get("disposition") + "; checks " + ((List<?>) c.get("independent_checks")).size() + ", tests " + c.get("tests") + ", rules "
                    + c.get("mapping_rules") + "; October rows " + c.get("october_ledger_rows") + ", observations " + c.get("observations") + ")");
        }
        return out.isEmpty() ? "none" : String.join("; ", out);
    }

    static String fieldsMarkdown(Inputs in) {
        Map<String, JsonNode> fields = inventoryFields(in.inventory());
        StringBuilder md = new StringBuilder("# Tier 1 parity ledger: every field\n\nGenerated by `ParityLedgerTest`; do not edit. One row per pinned foundation field, with its facet. Facet meaning, routes and actions are in `PARITY.md`.\n\n");
        md.append("| Field | Kind | Facet | Tier | State | Locator |\n|---|---|---|---|---|---|\n");
        for (JsonNode f : in.parity().path("facets")) {
            for (JsonNode x : f.path("fields")) {
                JsonNode field = fields.get(x.asText());
                md.append("| `").append(cell(field.path("id").asText())).append("` | ").append(field.path("kind").asText()).append(" | `").append(f.path("id").asText()).append("` | ")
                        .append(f.path("tier").asText()).append(" | ").append(f.path("state").asText()).append(" | ").append(cell(field.path("locator").asText())).append(" |\n");
            }
        }
        return md.toString();
    }

    // ------------------------------------------------------------------------------------------ topics

    static Map<String, Object> topics(Inputs in, Map<String, Map<String, Executed>> executed, Map<String, String> approval) {
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("kind", "agent-journal/topic-explanation");
        doc.put("derived_by", "ParityLedgerTest from mapping-catalog.json (concepts[].topic and its records), foundation-parity.json and this run's CLI coverage calculation");
        doc.put("rule", "A topic is a catalog concept: a question, not an event type. Claims, definitions, mappings and tests are counted separately; a topic without a claim can be modelled, mapped and tested, and a claim covers only its own statement.");
        doc.put("groups", in.catalog().path("topic_classification").path("groups"));
        Map<String, Integer> october = octoberRows(in.october());
        Map<String, List<String>> facetsByConcept = new LinkedHashMap<>();
        for (JsonNode f : in.parity().path("facets")) {
            for (JsonNode c : f.path("concepts")) {
                facetsByConcept.computeIfAbsent(c.asText(), k -> new ArrayList<>()).add(f.path("id").asText() + " (tier " + f.path("tier").asText() + ", " + f.path("state").asText() + ")");
            }
        }
        List<Map<String, Object>> topics = new ArrayList<>();
        for (JsonNode c : in.catalog().path("concepts")) {
            String id = c.path("id").asText();
            Map<String, Object> t = new LinkedHashMap<>();
            t.put("id", id);
            t.put("concept", c.path("concept").asText());
            t.put("question", c.path("question").asText());
            t.put("topic", c.path("topic"));
            t.put("catalog_disposition", c.path("disposition").asText());
            t.put("tier_facets", facetsByConcept.getOrDefault(id, List.of()));
            t.put("destinations", c.path("ocsf").size());
            t.put("mapping_rules", c.path("mapping").size());
            int checks = 0;
            for (JsonNode o : c.path("oracle")) {
                checks += o.path("properties").size();
            }
            t.put("independent_checks", checks);
            t.put("tests", c.path("tests").size());
            t.put("claims", c.path("claims").size());
            t.put("approval", approval.get(id));
            Map<String, String> ex = new LinkedHashMap<>();
            executed.get(id).forEach((r, e) -> ex.put(r, e.state()));
            t.put("executed", ex);
            t.put("october_ledger_rows", october.getOrDefault(id, 0));
            t.put("observations", c.path("observations").size());
            topics.add(t);
        }
        doc.put("topics", topics);
        Map<String, Map<String, Object>> proposals = new LinkedHashMap<>();
        for (JsonNode f : in.parity().path("facets")) {
            JsonNode p = f.path("proposal");
            if (p.isObject()) {
                Map<String, Object> m = proposals.computeIfAbsent(p.path("id").asText(), k -> new LinkedHashMap<>(Map.of("id", k, "question", p.path("question").asText(), "facets", new ArrayList<String>())));
                @SuppressWarnings("unchecked")
                List<String> fs = (List<String>) m.get("facets");
                fs.add(f.path("id").asText() + " (tier " + f.path("tier").asText() + ", " + f.path("state").asText() + ")");
            }
        }
        doc.put("proposed_concepts", new ArrayList<>(proposals.values()));
        return doc;
    }

    @SuppressWarnings("unchecked")
    static String topicsMarkdown(Inputs in, Map<String, Object> doc) {
        List<Map<String, Object>> topics = (List<Map<String, Object>>) doc.get("topics");
        long withClaims = topics.stream().filter(t -> ((Integer) t.get("claims")) > 0).count();
        int claims = topics.stream().mapToInt(t -> (Integer) t.get("claims")).sum();
        long withDestinations = topics.stream().filter(t -> ((Integer) t.get("destinations")) > 0).count();
        long withChecks = topics.stream().filter(t -> ((Integer) t.get("independent_checks")) > 0).count();
        long withTests = topics.stream().filter(t -> ((Integer) t.get("tests")) > 0).count();
        StringBuilder md = new StringBuilder("# The catalog's ").append(topics.size()).append(" topics, explained\n\n");
        md.append("Generated by `ParityLedgerTest`; do not edit. Each topic is a catalog concept: a question about recorded execution, its preservation or its provenance, not an event type. ")
                .append("Claims, definitions, mappings and tests are separate counts. ").append(claims).append(" claims attach to ").append(withClaims).append(" topics; that is a review-record count, not completion: ")
                .append(withDestinations).append(" topics have canonical destinations, ").append(withChecks).append(" have independent oracle checks and ").append(withTests)
                .append(" have named tests. Tier assignment is per field in the parity ledger; a topic can hold facets of several tiers.\n\n");
        JsonNode groups = (JsonNode) doc.get("groups");
        Map<String, List<Map<String, Object>>> byGroup = new LinkedHashMap<>();
        groups.fieldNames().forEachRemaining(g -> byGroup.put(g, new ArrayList<>()));
        for (Map<String, Object> t : topics) {
            byGroup.get(((JsonNode) t.get("topic")).path("group").asText()).add(t);
        }
        for (Map.Entry<String, List<Map<String, Object>>> g : byGroup.entrySet()) {
            md.append("## ").append(groups.path(g.getKey()).asText()).append("\n\n");
            md.append("| Topic | Question | Nature | Disposition | Parity facets | Destinations / rules / checks / tests | Claims | Claude 2.1.304 | Codex 0.160.1 | October rows / observations |\n")
                    .append("|---|---|---|---|---|---|---|---|---|---|\n");
            for (Map<String, Object> t : g.getValue()) {
                JsonNode topic = (JsonNode) t.get("topic");
                Map<String, String> ex = (Map<String, String>) t.get("executed");
                md.append("| `").append(t.get("id")).append("` ").append(cell((String) t.get("concept"))).append(" | ").append(cell((String) t.get("question"))).append(" | ")
                        .append(topic.path("nature").asText()).append(" | ").append(topic.path("disposition").asText()).append(": ").append(cell(topic.path("reason").asText())).append(" | ")
                        .append(cell(String.join("; ", (List<String>) t.get("tier_facets")))).append(" | ").append(t.get("destinations")).append(" / ").append(t.get("mapping_rules"))
                        .append(" / ").append(t.get("independent_checks")).append(" / ").append(t.get("tests")).append(" | ").append(t.get("claims")).append(" | ")
                        .append(cell(MappingCatalogTest.word(ex.get("claude-code/stream-json/2.1.304-stdout")))).append(" | ")
                        .append(cell(MappingCatalogTest.word(ex.get("codex-cli/exec-json-stream/0.160.1")))).append(" | ").append(t.get("october_ledger_rows")).append(" / ")
                        .append(t.get("observations")).append(" |\n");
            }
            md.append('\n');
        }
        md.append("## Proposed concepts from the parity ledger\n\nFoundation fields no current topic answers; each needs a reviewed definition before mapping.\n\n| Proposal | Question | Facets |\n|---|---|---|\n");
        for (Map<String, Object> p : (List<Map<String, Object>>) doc.get("proposed_concepts")) {
            md.append("| `").append(p.get("id")).append("` | ").append(cell((String) p.get("question"))).append(" | ").append(cell(String.join("; ", (List<String>) p.get("facets")))).append(" |\n");
        }
        return md.toString();
    }
}
