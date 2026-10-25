package io.github.markpollack.journal.tck.ocsf;

import io.github.markpollack.journal.tck.ocsf.CanonicalObligations.Binding;
import io.github.markpollack.journal.tck.ocsf.CanonicalObligations.Catalog;
import io.github.markpollack.journal.tck.ocsf.CanonicalObligations.Finding;
import io.github.markpollack.journal.tck.ocsf.CanonicalObligations.Judged;
import io.github.markpollack.journal.tck.ocsf.CanonicalObligations.Obligation;
import io.github.markpollack.journal.tck.ocsf.CanonicalObligations.Occurrence;
import io.github.markpollack.journal.tck.ocsf.inventory.RawInventory;
import io.github.markpollack.journal.tck.verify.Check;
import io.github.markpollack.journal.tck.verify.Json;
import io.github.markpollack.journal.tck.verify.NeutralJson;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.UnaryOperator;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The generated canonical-conformance report for the Claude Code stream's token-usage obligations
 * ({@link CanonicalObligations}) and its ratchet, plus the controls that show each escape route of the earlier matrix
 * is now an unmet obligation or a failed check.
 *
 * <p>The report is written to {@code target/conformance/claude-stream-usage.json} and {@code .md}. The ratchet file
 * {@code ocsf/conformance/claude-stream-usage-baseline.json} pins, each by exact identity: the obligation set (id,
 * revision, meaning digest, relevant digest), the residual set (variant, path, classification, digest), the judged
 * population (per binding: its entry's admission, each recording with its native hash, every occurrence id) and the
 * frozen debt. A new finding fails, a frozen one that no longer occurs fails until it is removed from the file, and a
 * changed obligation, residual or population fails until the baseline is edited: the edit is the reviewable change, not
 * its approval. A green ratchet says the baseline holds, not that the scope is accepted: the report states baseline
 * respected, behaviour verified, semantics approved and accepted zero-gap apart.
 */
class CanonicalConformanceTest {

    static final Path PROJECT = FieldDispositionTest.PROJECT;
    static final Path CATALOG = PROJECT.resolve("schema/vendor/claude-code/stream-json/obligations.json");
    static final Path BASELINE = PROJECT.resolve("journal-capture-tck/src/test/resources/ocsf/conformance/claude-stream-usage-baseline.json");
    static final Map<Integer, String> CATEGORIES = Map.of(
            1, "unaccounted observed vendor fields",
            2, "ambiguous disposition assignments",
            3, "unmet required canonical mappings",
            4, "missing expected occurrence/check results or failed property/owner/value checks",
            5, "missing required per-source catalogs, profiles or registrations",
            6, "unreviewed drift and missing or stale semantic approvals");

    /** The inputs of one recording: its native stream, its candidate cores and the oracle's report and profile reading. */
    record Recording(String fixture, NativeArtifact art, Map<String, List<Event>> cores, OracleReport report, Map<String, ExpectedFacts> facts) {
    }

    /** One generated report: its judged occurrences, every finding, the heuristic notices and the document. */
    record Report(Catalog catalog, List<Judged> judged, List<Finding> findings, List<String> notices, Map<String, Object> document) {

        Set<String> ids() {
            Set<String> out = new TreeSet<>();
            findings.forEach(f -> out.add(f.category() + " " + f.id()));
            return out;
        }

        List<Finding> category(int c) {
            return findings.stream().filter(f -> f.category() == c).toList();
        }

        boolean inScope(Finding f) {
            return f.category() != 5 || catalog.bindings().stream().anyMatch(b -> f.id().endsWith(":" + b.entry()) || f.id().startsWith("recording:" + b.entry() + ":"));
        }
    }

    // ---- generation -----------------------------------------------------------------------------------------------------

    static Recording recording(Catalog c, Path fixtureDir) {
        String fixture = fixtureDir.getFileName().toString();
        NativeArtifact art = NativeArtifact.read(fixtureDir.resolve("native").resolve(CanonicalObligations.ARTIFACT));
        OcsfPackage pkg = PackageReader.open(fixtureDir.resolve("package").resolve("events.jsonl"));
        Map<String, List<Event>> cores = new LinkedHashMap<>();
        for (OcsfPackage core : pkg.all()) {
            cores.put(core.label(), core.events.stream().filter(e -> e.className != null && e.observation() && CanonicalObligations.ARTIFACT.equals(e.artifact())).toList());
        }
        OracleReport report = OcsfOracle.verify(pkg, fixtureDir.resolve("native"));
        return new Recording(fixture, art, cores, report, facts(c, art));
    }

    /** The oracle's own source-profile reading per owner, with the owner selector of the catalog (never the candidate's). */
    static Map<String, ExpectedFacts> facts(Catalog c, NativeArtifact art) {
        Set<String> owners = new LinkedHashSet<>();
        owners.add(null);
        for (NativeArtifact.Record r : art.records()) {
            Json v = r.parsed() ? r.json().member(c.ownerPath()) : null;
            if (v != null && !v.isNull()) {
                owners.add(v.scalarText());
            }
        }
        Map<String, ExpectedFacts> out = new LinkedHashMap<>();
        Json source = NeutralJson.parse("{\"artifact\": \"" + CanonicalObligations.ARTIFACT + "\", \"profile\": \"" + c.profile() + "\"}");
        for (String owner : owners) {
            Json scope = NeutralJson.parse("{\"artifact\": \"" + CanonicalObligations.ARTIFACT + "\", \"owner_path\": \"" + c.ownerPath() + "\", \"owner_value\": "
                    + (owner == null ? "null" : "\"" + owner + "\"") + "}");
            out.put(owner == null ? "main" : "child:" + owner, SourceProfile.forName(c.profile()).expected(art, source, scope));
        }
        return out;
    }

    static Map<String, Recording> recordings(Catalog c) {
        Map<String, Recording> out = new LinkedHashMap<>();
        for (Binding b : c.bindings()) {
            for (String f : b.recordings()) {
                out.computeIfAbsent(f, k -> recording(c, CanonicalObligations.FIXTURES.resolve(k)));
            }
        }
        return out;
    }

    static Report generate(Catalog c, Map<String, Recording> recordings, FieldDispositionTest.Catalog dispositions, Set<String> obligationEntries) {
        List<Judged> judged = new ArrayList<>();
        Map<String, Finding> findings = new LinkedHashMap<>();
        List<String> notices = new ArrayList<>();
        Set<String> dataKeyed = RawInventory.ProfileRules.forProfile(c.profile()).dataKeyed();
        for (Binding b : c.bindings()) {
            Map<String, NativeArtifact> natives = new LinkedHashMap<>();
            for (String fixture : b.recordings()) {
                Recording rec = recordings.get(fixture);
                natives.put(fixture, rec.art());
                List<Occurrence> occurrences = CanonicalObligations.occurrences(c, b, fixture, rec.art());
                for (Occurrence occ : occurrences) {
                    Judged j = CanonicalObligations.judge(occ, rec.cores(), rec.report(), rec.facts());
                    judged.add(j);
                    j.findings().forEach(f -> findings.putIfAbsent(f.category() + " " + f.id(), f));
                    CanonicalObligations.dispositions(occ, dispositions).forEach(f -> findings.putIfAbsent(f.category() + " " + f.id(), f));
                }
                CanonicalObligations.tokenMembers(c, rec.art(), occurrences, dataKeyed, notices).forEach(f -> findings.putIfAbsent(f.category() + " " + f.id(), f));
            }
            CanonicalObligations.binding(b, natives).forEach(f -> findings.putIfAbsent(f.category() + " " + f.id(), f));
            CanonicalObligations.population(b).forEach(f -> findings.putIfAbsent(f.category() + " " + f.id(), f));
        }
        CanonicalObligations.registrations(obligationEntries).forEach(f -> findings.putIfAbsent(f.category() + " " + f.id(), f));
        CanonicalObligations.semantics(c).forEach(f -> findings.putIfAbsent(f.category() + " " + f.id(), f));
        List<Finding> all = new ArrayList<>(findings.values());
        Report draft = new Report(c, judged, all, notices.stream().distinct().toList(), Map.of());
        return new Report(c, judged, all, draft.notices(), document(draft, recordings));
    }

    static Map<String, Object> document(Report r, Map<String, Recording> recordings) {
        Catalog c = r.catalog();
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("kind", "agent-journal/conformance-report");
        doc.put("version", "0.1");
        doc.put("scope", NeutralJson.readDocument(c.file()).member("scope").text());
        doc.put("catalog", pin(c.file()));
        doc.put("dispositions", pin(c.dispositions()));
        doc.put("registry", pin(CanonicalObligations.REGISTRY));
        Json registry = NeutralJson.readDocument(CanonicalObligations.REGISTRY);
        List<Object> bindings = new ArrayList<>();
        for (Binding b : c.bindings()) {
            Json e = registry.member("entries").elements().stream().filter(x -> b.entry().equals(SourceProfile.text(x, "id"))).findFirst().orElseThrow();
            Map<String, Object> bd = new LinkedHashMap<>();
            bd.put("compatibility_entry", b.entry());
            Path spec = PROJECT.resolve(SourceProfile.text(e, "source_specification", "path"));
            Map<String, Object> sp = pin(spec);
            sp.put("specification_version", NeutralJson.readDocument(spec).member("specification_version").text());
            bd.put("specification", sp);
            bd.put("contract", pin(PROJECT.resolve("journal-ocsf-writer/src/main/resources/ocsf/mappings/" + SourceProfile.text(e, "contract") + ".json")));
            bd.put("admission", SourceProfile.text(e, "admission"));
            List<Object> recs = new ArrayList<>();
            Map<String, List<String>> bySha = new LinkedHashMap<>();
            for (String f : b.recordings()) {
                NativeArtifact art = recordings.get(f).art();
                bySha.computeIfAbsent(art.sha256(), k -> new ArrayList<>()).add(f);
                recs.add(ordered("fixture", f, "artifact", CanonicalObligations.ARTIFACT, "sha256", art.sha256(), "reported_version", CanonicalObligations.reportedVersion(art),
                        "declared_route", CanonicalObligations.declaredRoute(f)));
            }
            bd.put("recordings", recs);
            bd.put("judged_candidates", b.recordings().size());
            bd.put("distinct_native_recordings", bySha.size());
            bd.put("byte_identical_candidates", bySha.values().stream().filter(l -> l.size() > 1).toList());
            List<Object> obligations = new ArrayList<>();
            for (Obligation o : c.obligations()) {
                List<Judged> mine = r.judged().stream().filter(j -> j.occurrence().obligation().id().equals(o.id()) && j.occurrence().entry().equals(b.entry())).toList();
                long discharged = mine.stream().filter(Judged::discharged).count();
                obligations.add(ordered("id", o.id(), "revision", o.revision(), "occurrences", mine.size(), "discharged", discharged,
                        "state", mine.isEmpty() ? "unexercised (no native trigger in the registered recordings)" : discharged == mine.size() ? "discharged" : "not discharged",
                        "not_discharged", mine.stream().filter(j -> !j.discharged()).map(j -> j.occurrence().id()).toList()));
            }
            bd.put("obligations", obligations);
            bindings.add(bd);
        }
        doc.put("bindings", bindings);
        SemanticClaims.Definitions defs = SemanticClaims.Definitions.read(FoundationComparisonTest.EXTENSION);
        List<Object> obligationRows = new ArrayList<>();
        for (Obligation o : c.obligations()) {
            obligationRows.add(ordered("id", o.id(), "revision", o.revision(), "meaning_digest", CanonicalObligations.meaningDigest(c, o),
                    "relevant_digest", CanonicalObligations.relevantDigest(c, o, defs, new ArrayList<>()), "rules", o.rules(), "review_status", o.reviewStatus()));
        }
        doc.put("obligations", obligationRows);
        List<Object> residualRows = new ArrayList<>();
        for (CanonicalObligations.Residual x : c.residuals()) {
            residualRows.add(ordered("variant", x.variant(), "path", x.path(), "members", x.members(), "classification", x.classification(),
                    "digest", CanonicalObligations.residualDigest(x), "review_status", x.reviewStatus()));
        }
        doc.put("residuals", residualRows);
        List<Object> occurrences = new ArrayList<>();
        for (Judged j : r.judged()) {
            Occurrence o = j.occurrence();
            occurrences.add(ordered("id", o.id(), "obligation", o.obligation().id(), "revision", o.obligation().revision(), "compatibility_entry", o.entry(), "fixture", o.fixture(),
                    "artifact_sha256", o.sha256(), "record", o.record(), "pointer", o.pointer(), "owner", o.owner(), "unit", o.unit(), "model", o.model(),
                    "discharged", j.discharged(), "findings", j.findings().stream().map(Finding::detail).toList()));
        }
        doc.put("occurrences", occurrences);
        Map<String, Object> categories = new LinkedHashMap<>();
        for (int i = 1; i <= 6; i++) {
            List<Finding> in = r.category(i).stream().filter(r::inScope).toList();
            List<Finding> out = r.category(i).stream().filter(f -> !r.inScope(f)).toList();
            categories.put(Integer.toString(i), ordered("name", CATEGORIES.get(i), "in_scope", in.size(), "outside_scope", out.size(),
                    "findings", r.category(i).stream().map(f -> ordered("id", f.id(), "in_scope", r.inScope(f), "detail", f.detail())).toList()));
        }
        doc.put("categories", categories);
        boolean deterministicZero = r.findings().stream().noneMatch(f -> f.category() <= 5 && r.inScope(f));
        doc.put("deterministic_categories_zero_in_scope", deterministicZero);
        long discharged = r.judged().stream().filter(Judged::discharged).count();
        long obligationsPending = r.category(6).stream().filter(f -> f.id().startsWith("approval:") && !f.id().startsWith("approval:residual:")).count();
        long residualsPending = r.category(6).stream().filter(f -> f.id().startsWith("approval:residual:")).count();
        doc.put("states", ordered(
                "baseline_respected", "decided by CanonicalConformanceTest#theGeneratedReportHoldsTheReviewedBaselineExactly against " + PROJECT.relativize(BASELINE),
                "behavior_verified", discharged + " of " + r.judged().size() + " occurrences discharged on the judged population (bindings above; repeated byte-identical candidates are not independent recordings)",
                "semantics_approved", (c.obligations().size() - obligationsPending) + " of " + c.obligations().size() + " obligations and " + (c.residuals().size() - residualsPending)
                        + " of " + c.residuals().size() + " residual classifications approved by an Owner decision at their current digests",
                "accepted_zero_gap", "false: no acceptance is inferred from any count; it needs every category zero for the scope and an Owner decision"));
        doc.put("accepted_zero_gap", false);
        doc.put("acceptance_note", "categories 1 to 5 are deterministic; category 6 carries the semantic review and drift state. The scope is accepted only when every category is zero for it"
                + " and the Owner approves the obligations; a finite corpus of pinned recordings, not every future vendor behavior.");
        doc.put("heuristic_notices", r.notices());
        doc.put("not_measured", List.of(
                "members outside the token-usage family (durations, counts, statuses, content) of this profile: accounted by the field-disposition catalog, not judged against obligations",
                "every other profile, route and source kind: no obligation catalog yet (category 5 lists the required ones)",
                "the overall canonical-coverage gap count of the product"));
        return doc;
    }

    static Map<String, Object> pin(Path file) {
        return ordered("path", PROJECT.relativize(file).toString(), "sha256", NativeArtifact.sha256(read(file)));
    }

    static byte[] read(Path file) {
        try {
            return Files.readAllBytes(file);
        }
        catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    static Map<String, Object> ordered(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }

    @SuppressWarnings("unchecked")
    static String markdown(Report r) {
        Map<String, Object> doc = r.document();
        StringBuilder md = new StringBuilder();
        md.append("# Canonical conformance: Claude Code stream token usage\n\n");
        md.append("Generated by `CanonicalConformanceTest` from `").append(((Map<String, Object>) doc.get("catalog")).get("path")).append("` (sha256 `")
                .append(((Map<String, Object>) doc.get("catalog")).get("sha256")).append("`). Do not edit; regenerate.\n\n");
        md.append("Scope: ").append(doc.get("scope")).append("\n\n");
        md.append("| Binding | Specification | Obligation | Occurrences | Discharged | State |\n|---|---|---|---|---|---|\n");
        for (Object bo : (List<Object>) doc.get("bindings")) {
            Map<String, Object> b = (Map<String, Object>) bo;
            Map<String, Object> spec = (Map<String, Object>) b.get("specification");
            for (Object oo : (List<Object>) b.get("obligations")) {
                Map<String, Object> o = (Map<String, Object>) oo;
                md.append("| ").append(b.get("compatibility_entry")).append(" | ").append(spec.get("path")).append(' ').append(spec.get("specification_version"))
                        .append(" | ").append(o.get("id")).append(" r").append(o.get("revision")).append(" | ").append(o.get("occurrences")).append(" | ")
                        .append(o.get("discharged")).append(" | ").append(o.get("state")).append(" |\n");
            }
        }
        md.append("\n| Category | In scope | Outside this catalog's scope |\n|---|---|---|\n");
        Map<String, Object> cats = (Map<String, Object>) doc.get("categories");
        cats.forEach((k, v) -> {
            Map<String, Object> c = (Map<String, Object>) v;
            md.append("| ").append(k).append(". ").append(c.get("name")).append(" | ").append(c.get("in_scope")).append(" | ").append(c.get("outside_scope")).append(" |\n");
        });
        md.append("\n| Binding | Admission | Judged candidates | Distinct native recordings | Byte-identical candidates |\n|---|---|---|---|---|\n");
        for (Object bo : (List<Object>) doc.get("bindings")) {
            Map<String, Object> b = (Map<String, Object>) bo;
            md.append("| ").append(b.get("compatibility_entry")).append(" | ").append(b.get("admission")).append(" | ").append(b.get("judged_candidates")).append(" | ")
                    .append(b.get("distinct_native_recordings")).append(" | ").append(b.get("byte_identical_candidates")).append(" |\n");
        }
        md.append("\n## States\n\n");
        ((Map<String, Object>) doc.get("states")).forEach((k, v) -> md.append("- ").append(k).append(": ").append(v).append('\n'));
        md.append("\nDeterministic categories 1 to 5 zero in scope: ").append(doc.get("deterministic_categories_zero_in_scope"))
                .append(". Accepted zero-gap: ").append(doc.get("accepted_zero_gap")).append(" (").append(doc.get("acceptance_note")).append(")\n\n");
        md.append("## Findings\n\n");
        cats.forEach((k, v) -> {
            List<Object> fs = (List<Object>) ((Map<String, Object>) v).get("findings");
            if (!fs.isEmpty()) {
                md.append("### ").append(k).append(". ").append(((Map<String, Object>) v).get("name")).append("\n\n");
                for (Object fo : fs) {
                    Map<String, Object> f = (Map<String, Object>) fo;
                    md.append("- `").append(f.get("id")).append("`").append(Boolean.TRUE.equals(f.get("in_scope")) ? "" : " (outside scope)").append(": ")
                            .append(String.valueOf(f.get("detail")).replace("|", "\\|")).append('\n');
                }
                md.append('\n');
            }
        });
        List<String> notices = (List<String>) doc.get("heuristic_notices");
        if (!notices.isEmpty()) {
            md.append("## Heuristic notices (not findings)\n\n");
            notices.forEach(n -> md.append("- ").append(n).append('\n'));
            md.append('\n');
        }
        md.append("## Not measured\n\n");
        ((List<String>) doc.get("not_measured")).forEach(n -> md.append("- ").append(n).append('\n'));
        return md.toString();
    }

    static Set<String> obligationEntries() {
        Set<String> out = new TreeSet<>();
        try (var s = Files.walk(PROJECT.resolve("schema/vendor"))) {
            for (Path p : s.filter(x -> x.getFileName().toString().equals("obligations.json")).toList()) {
                CanonicalObligations.read(p).bindings().forEach(b -> out.add(b.entry()));
            }
        }
        catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
        return out;
    }

    static Report real() {
        Catalog c = CanonicalObligations.read(CATALOG);
        return generate(c, recordings(c), FieldDispositionTest.catalog(c.profile()), obligationEntries());
    }

    // ---- the report and its ratchet ---------------------------------------------------------------------------------------

    @Test
    void theGeneratedReportHoldsTheReviewedBaselineExactly() throws IOException {
        Report r = real();
        Path out = Path.of(System.getProperty("basedir", ".")).resolve("target").resolve("conformance");
        Files.createDirectories(out);
        Files.writeString(out.resolve("claude-stream-usage.json"), NeutralJson.write(toJson(r.document())) + "\n", StandardCharsets.UTF_8);
        Files.writeString(out.resolve("claude-stream-usage.md"), markdown(r), StandardCharsets.UTF_8);
        Json baseline = NeutralJson.readDocument(BASELINE);
        assertThat(obligationKeys(r.catalog())).as("the obligation set is the reviewed one (id, revision, meaning and relevant digests); changing it, a reclassification included, is a baseline edit")
                .isEqualTo(reviewedObligations());
        assertThat(residualKeys(r.catalog())).as("the residual set is the reviewed one (variant, path, classification, digest)").isEqualTo(reviewedResiduals());
        assertThat(population(r)).as("the judged population is the reviewed one (entry admission, recordings with their hashes, every occurrence id)").isEqualTo(reviewedPopulation());
        Set<String> frozen = new TreeSet<>();
        for (Json d : baseline.member("frozen").elements()) {
            frozen.add(d.member("category").scalarText() + " " + d.member("id").text());
        }
        Set<String> added = new TreeSet<>(r.ids());
        added.removeAll(frozen);
        Set<String> removed = new TreeSet<>(frozen);
        removed.removeAll(r.ids());
        assertThat(added).as("new findings (not frozen debt); see target/conformance/claude-stream-usage.md").isEmpty();
        assertThat(removed).as("frozen debt that no longer occurs: remove it from " + BASELINE.getFileName()).isEmpty();
    }

    @Test
    void theRepairedClaudeUsageObligationsAreDischargedOnBothBindings() {
        Report r = real();
        assertThat(r.findings().stream().filter(f -> f.category() <= 5 && r.inScope(f)).map(f -> f.category() + " " + f.id()))
                .as("deterministic categories 1 to 5 in scope").isEmpty();
        for (Binding b : r.catalog().bindings()) {
            for (String id : List.of("claude.stream.usage.child-run.agent-result", "claude.stream.usage.child-run.task-progress", "claude.stream.usage.child-run.task-notification")) {
                List<Judged> mine = r.judged().stream().filter(j -> j.occurrence().entry().equals(b.entry()) && j.occurrence().obligation().id().equals(id)).toList();
                assertThat(mine).as(b.entry() + " " + id + " is exercised").isNotEmpty();
                assertThat(mine).as(b.entry() + " " + id).allMatch(Judged::discharged);
            }
        }
        assertThat(r.document().get("accepted_zero_gap")).isEqualTo(false);
    }

    // ---- controls: each escape route of the earlier matrix --------------------------------------------------------------

    static final String FEATURES = OcsfFixtures.LIVE_FEATURES;

    static List<Judged> judgeAll(Catalog c, Recording rec, String entry) {
        Binding b = c.bindings().stream().filter(x -> x.entry().equals(entry)).findFirst().orElseThrow();
        return CanonicalObligations.occurrences(c, b, rec.fixture(), rec.art()).stream().map(o -> CanonicalObligations.judge(o, rec.cores(), rec.report(), rec.facts())).toList();
    }

    static Recording withEvents(Recording rec, UnaryOperator<Event> edit) {
        Map<String, List<Event>> cores = new LinkedHashMap<>();
        rec.cores().forEach((k, v) -> {
            List<Event> out = new ArrayList<>();
            for (Event e : v) {
                Event x = edit.apply(e);
                if (x != null) {
                    out.add(x);
                }
            }
            cores.put(k, out);
        });
        return new Recording(rec.fixture(), rec.art(), cores, rec.report(), rec.facts());
    }

    static Event edited(Event e, UnaryOperator<Map<String, Json>> edit) {
        return new Event(e.line, new Json.JObject(edit.apply(new LinkedHashMap<>(((Json.JObject) e.json).members()))), e.className);
    }

    static boolean childUsage(Event e) {
        return "session_context".equals(e.className) && e.elements("usage_observations").stream().anyMatch(u -> Integer.valueOf(7).equals(Event.integer(u.member("scope_id"))));
    }

    @Test
    void childUsagePreservedOnlyInsideTheToolResultIsAnUnmetObligation() {
        Catalog c = CanonicalObligations.read(CATALOG);
        Recording rec = recording(c, CanonicalObligations.FIXTURES.resolve(FEATURES));
        assertThat(judgeAll(c, rec, "claude-code/stream-json/2.1.304-stdout").stream().filter(j -> j.occurrence().obligation().scopeId() == 7)).hasSize(4).allMatch(Judged::discharged);
        // the 24e8ecc state: the child-run accounting events absent, the Agent result kept whole as structured_result
        Recording retainedOnly = withEvents(rec, e -> childUsage(e) ? null : e);
        List<Judged> judged = judgeAll(c, retainedOnly, "claude-code/stream-json/2.1.304-stdout").stream().filter(j -> j.occurrence().obligation().scopeId() == 7).toList();
        assertThat(judged).hasSize(4).noneMatch(Judged::discharged);
        assertThat(judged).allSatisfy(j -> assertThat(j.findings()).anyMatch(f -> f.category() == 3 && f.detail().contains("no session_context usage observation")));
        // the usage is still preserved: the tool result's structured_result carries it whole
        Event result = retainedOnly.cores().get("main").stream().filter(e -> e.is("tool_call", 2) && Integer.valueOf(33).equals(e.recordIndex())).findFirst().orElseThrow();
        assertThat(result.get("tool_result", "structured_result", "usage", "cache_read_input_tokens").scalarText()).isEqualTo("18492");
        // and the earlier disposition gate accepted the structured_result destination on any owner's VERIFIED result.values
        FieldDispositionTest.Catalog dispositions = FieldDispositionTest.catalog("claude_stream");
        FieldDispositionTest.Entry subtree = FieldDispositionTest.applicable(dispositions, "user/tool_result", "tool_use_result.usage.input_tokens").get(0);
        assertThat(subtree.key()).isEqualTo("user/tool_result:tool_use_result");
        assertThat(subtree.disposition()).isEqualTo("normalized");
    }

    @Test
    void aBroadOrRetainedDispositionCannotHideARequiredMapping() {
        Catalog c = CanonicalObligations.read(CATALOG);
        Recording rec = recording(c, CanonicalObligations.FIXTURES.resolve(FEATURES));
        Binding b = c.bindings().get(1);
        List<Occurrence> occ = CanonicalObligations.occurrences(c, b, FEATURES, rec.art());
        FieldDispositionTest.Catalog dispositions = FieldDispositionTest.catalog("claude_stream");
        assertThat(occ.stream().flatMap(o -> CanonicalObligations.dispositions(o, dispositions).stream())).isEmpty();
        // the Agent result's subtree without its carve-out: the usage is hidden inside structured_result
        FieldDispositionTest.Catalog noCarveOut = replaced(dispositions, "user/tool_result:tool_use_result", e -> without(e, "carve_outs"));
        assertThat(occ.stream().filter(o -> o.obligation().id().endsWith("agent-result")).flatMap(o -> CanonicalObligations.dispositions(o, noCarveOut).stream()))
                .isNotEmpty().allMatch(f -> f.category() == 3 && f.detail().contains("hidden inside the broad disposition user/tool_result:tool_use_result"));
        // task progress usage reclassified as retained: a required canonical mapping cannot be retained-only
        FieldDispositionTest.Catalog retained = replaced(dispositions, "system/task_progress:usage.total_tokens", e -> new FieldDispositionTest.Entry(e.variant(), e.path(), e.subtree(),
                "retained", List.of(), null, "whole_record.coverage", List.of(), e.note(), e.raw()));
        assertThat(occ.stream().filter(o -> o.obligation().id().endsWith("task-progress")).flatMap(o -> CanonicalObligations.dispositions(o, retained).stream()))
                .isNotEmpty().allMatch(f -> f.category() == 3 && f.detail().contains("is retained"));
        // reclassifying the obligation itself as a residual changes the reviewed obligation set, which the baseline pins
        Json raw = NeutralJson.readDocument(CATALOG);
        Map<String, Json> m = new LinkedHashMap<>(((Json.JObject) raw).members());
        m.put("obligations", new Json.JArray(raw.member("obligations").elements().stream().filter(o -> !o.member("id").text().endsWith("task-progress")).toList()));
        Catalog reclassified = CanonicalObligations.parse(CATALOG, new Json.JObject(m));
        assertThat(reclassified.obligations()).hasSize(c.obligations().size() - 1);
        Set<String> missing = new TreeSet<>(reviewedObligations());
        missing.removeAll(obligationKeys(reclassified));
        assertThat(missing).singleElement().asString().startsWith("claude.stream.usage.child-run.task-progress@r2#");
    }

    @Test
    void aPassingCheckForAnotherOwnerUnitOrFieldCannotDischargeAnOccurrence() {
        Catalog c = CanonicalObligations.read(CATALOG);
        Recording rec = recording(c, CanonicalObligations.FIXTURES.resolve(FEATURES));
        // the progress observations relabelled to another tool call: the unit check fails, the other call's value cannot stand in
        Recording otherUnit = withEvents(rec, e -> childUsage(e) && "system/task_progress".equals(e.text("context_kind"))
                ? edited(e, m -> {
                    m.put("tool_call_uid", new Json.JString("toolu_01RnNHTYgz6yym3A1m1sYZ6S"));
                    return m;
                }) : e);
        List<Judged> progress = judgeAll(c, otherUnit, "claude-code/stream-json/2.1.304-stdout").stream().filter(j -> j.occurrence().obligation().id().endsWith("task-progress")).toList();
        assertThat(progress).hasSize(2).noneMatch(Judged::discharged)
                .allSatisfy(j -> assertThat(j.findings()).anyMatch(f -> f.category() == 3 && f.detail().contains("another owner or unit")));
        // the observation moved to the child core: wrong owner
        Recording moved = new Recording(rec.fixture(), rec.art(), movedToChild(rec), rec.report(), rec.facts());
        assertThat(judgeAll(c, moved, "claude-code/stream-json/2.1.304-stdout").stream().filter(j -> j.occurrence().obligation().id().endsWith("task-notification")))
                .singleElement().satisfies(j -> assertThat(j.findings()).anyMatch(f -> f.category() == 3 && f.detail().contains("child:" + OcsfFixtures.LIVE_SPAWN)));
        // usage.values VERIFIED for main, but the child's own result is missing: the child's occurrences are not discharged
        Recording child = recording(c, CanonicalObligations.FIXTURES.resolve(OcsfFixtures.CHILD));
        OracleReport mainOnly = child.report().filtered(ch -> !(ch.property().equals("usage.values") && ch.owner().startsWith("child:")));
        assertThat(mainOnly.checks()).anyMatch(ch -> ch.property().equals("usage.values") && ch.owner().equals("main/stream.jsonl") && ch.outcome() == Check.Outcome.VERIFIED);
        List<Judged> childOwned = judgeAll(c, new Recording(child.fixture(), child.art(), child.cores(), mainOnly, child.facts()), "claude-code/stream-json/2.1.299-sdk")
                .stream().filter(j -> j.occurrence().owner().startsWith("child:")).toList();
        assertThat(childOwned).isNotEmpty().noneMatch(Judged::discharged)
                .allSatisfy(j -> assertThat(j.findings()).anyMatch(f -> f.category() == 4 && f.detail().contains("oracle usage.values [child:")));
        // the earlier disposition gate: one VERIFIED usage.values anywhere satisfied every normalized usage entry
        FieldDispositionTest.Catalog dispositions = FieldDispositionTest.catalog("claude_stream");
        FieldDispositionTest.Entry input = dispositions.entries().stream().filter(e -> e.key().equals("assistant:message.usage.input_tokens")).findFirst().orElseThrow();
        FieldDispositionTest.Catalog childEvidence = new FieldDispositionTest.Catalog(dispositions.profile(), dispositions.contractFile(), List.of(OcsfFixtures.CHILD),
                dispositions.entries(), dispositions.raw());
        assertThat(FieldDispositionTest.exercised(input, childEvidence, Map.of(OcsfFixtures.CHILD, mainOnly), java.util.EnumSet.of(Check.Outcome.VERIFIED)))
                .as("the property-level check passes without the child's result").isEmpty();
    }

    private static Map<String, List<Event>> movedToChild(Recording rec) {
        Map<String, List<Event>> cores = new LinkedHashMap<>();
        List<Event> main = new ArrayList<>();
        List<Event> child = new ArrayList<>(rec.cores().get("child:" + OcsfFixtures.LIVE_SPAWN));
        for (Event e : rec.cores().get("main")) {
            if (childUsage(e) && "system/task_notification".equals(e.text("context_kind"))) {
                child.add(e);
            }
            else {
                main.add(e);
            }
        }
        cores.put("main", main);
        cores.put("child:" + OcsfFixtures.LIVE_SPAWN, child);
        return cores;
    }

    @Test
    void aWrongValueStatusAggregationOrDataAndAMissingProfileExpectationEachFail() {
        Catalog c = CanonicalObligations.read(CATALOG);
        Recording rec = recording(c, CanonicalObligations.FIXTURES.resolve(FEATURES));
        record Corruption(String name, UnaryOperator<Map<String, Json>> edit, String expected) {
        }
        List<Corruption> corruptions = List.of(
                new Corruption("value", u -> put(u, "reported_total_tokens", new Json.JNumber("21162")), "reported_total_tokens 21162 != native total_tokens"),
                new Corruption("status", u -> put(u, "usage_status_id", new Json.JNumber("2")), "usage_status_id 2 != 1"),
                new Corruption("aggregation", u -> put(u, "aggregation_id", new Json.JNumber("2")), "aggregation_id 2 != absent"),
                new Corruption("data", u -> put(u, "data", new Json.JObject(Map.of())), "data is not the native usage object"),
                new Corruption("invented component", u -> put(u, "output_tokens", new Json.JNumber("0")), "output_tokens present (0)"));
        for (Corruption k : corruptions) {
            Recording bad = withEvents(rec, e -> childUsage(e) && "system/task_progress".equals(e.text("context_kind")) ? edited(e, m -> {
                List<Json> obs = new ArrayList<>();
                for (Json u : m.get("usage_observations").elements()) {
                    obs.add(new Json.JObject(k.edit().apply(new LinkedHashMap<>(((Json.JObject) u).members()))));
                }
                m.put("usage_observations", new Json.JArray(obs));
                return m;
            }) : e);
            List<Judged> progress = judgeAll(c, bad, "claude-code/stream-json/2.1.304-stdout").stream().filter(j -> j.occurrence().obligation().id().endsWith("task-progress")).toList();
            assertThat(progress).as(k.name()).hasSize(2).noneMatch(Judged::discharged)
                    .allSatisfy(j -> assertThat(j.findings()).as(k.name()).anyMatch(f -> f.category() == 4 && f.detail().contains(k.expected())));
        }
        // a duplicate observation of one occurrence
        Recording twice = withEvents(rec, e -> childUsage(e) && "system/task_notification".equals(e.text("context_kind")) ? edited(e, m -> {
            List<Json> obs = new ArrayList<>(m.get("usage_observations").elements());
            obs.add(obs.get(0));
            m.put("usage_observations", new Json.JArray(obs));
            return m;
        }) : e);
        assertThat(judgeAll(c, twice, "claude-code/stream-json/2.1.304-stdout").stream().filter(j -> j.occurrence().obligation().id().endsWith("task-notification")))
                .singleElement().satisfies(j -> assertThat(j.findings()).anyMatch(f -> f.detail().contains("2 observations match one occurrence")));
        // the oracle's profile without its child-run expectations: the occurrence is not independently expected
        Map<String, ExpectedFacts> facts = new LinkedHashMap<>(rec.facts());
        ExpectedFacts main = facts.get("main");
        main.usages.removeIf(u -> u.scopeId() == 7);
        assertThat(judgeAll(c, new Recording(rec.fixture(), rec.art(), rec.cores(), rec.report(), facts), "claude-code/stream-json/2.1.304-stdout").stream()
                .filter(j -> j.occurrence().obligation().scopeId() == 7))
                .hasSize(4).allSatisfy(j -> assertThat(j.findings()).anyMatch(f -> f.category() == 4 && f.detail().contains("source profile does not expect")));
    }

    @Test
    void aZeroMatchTriggerIsUnexercisedAndAnAddedVendorFieldIsUnaccounted(@TempDir Path work) throws IOException {
        Catalog c = CanonicalObligations.read(CATALOG);
        // a trigger that matches nothing: no occurrence, reported unexercised, never discharged
        Json raw = NeutralJson.readDocument(CATALOG);
        List<Json> obligations = new ArrayList<>(raw.member("obligations").elements());
        Json progress = obligations.stream().filter(o -> o.member("id").text().endsWith("task-progress")).findFirst().orElseThrow();
        Map<String, Json> renamed = new LinkedHashMap<>(((Json.JObject) progress).members());
        renamed.put("id", new Json.JString("claude.stream.usage.control.zero-match"));
        renamed.put("trigger", NeutralJson.parse("{\"variant\": \"system/task_progress\", \"payload\": \"usage.no_such_member\", \"unit\": \"tool_use_id\"}"));
        obligations.add(new Json.JObject(renamed));
        Map<String, Json> m = new LinkedHashMap<>(((Json.JObject) raw).members());
        m.put("obligations", new Json.JArray(obligations));
        Catalog withZero = CanonicalObligations.parse(CATALOG, new Json.JObject(m));
        Report r = generate(withZero, recordings(withZero), FieldDispositionTest.catalog("claude_stream"), obligationEntries());
        assertThat(r.judged()).noneMatch(j -> j.occurrence().obligation().id().endsWith("zero-match"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows = (List<Map<String, Object>>) ((Map<String, Object>) ((List<Object>) r.document().get("bindings")).get(1)).get("obligations");
        assertThat(rows).filteredOn(row -> "claude.stream.usage.control.zero-match".equals(row.get("id"))).singleElement()
                .satisfies(row -> assertThat(String.valueOf(row.get("state"))).startsWith("unexercised"));
        // an added vendor field: a top-level token count on task_progress (at 24e8ecc the whole-variant wildcard system/task_progress:* accepted it)
        Path copy = OcsfFixtures.copy(FEATURES, work);
        Path stream = copy.resolve("native").resolve("stream.jsonl");
        List<String> lines = new ArrayList<>(Files.readAllLines(stream, StandardCharsets.UTF_8));
        lines.set(24, lines.get(24).replaceFirst("\\{\"type\":\"system\"", "{\"type\":\"system\",\"summary_tokens\":5"));
        lines.set(27, lines.get(27).replace("\"usage\":{\"total_tokens\":21161", "\"usage\":{\"cache_read_input_tokens\":7,\"total_tokens\":21161"));
        Files.write(stream, lines, StandardCharsets.UTF_8);
        NativeArtifact added = NativeArtifact.read(stream);
        assertThat(added.record(25).json().member("summary_tokens")).isNotNull();
        assertThat(added.record(28).json().path("usage", "cache_read_input_tokens")).isNotNull();
        FieldDispositionTest.Catalog dispositions = FieldDispositionTest.catalog("claude_stream");
        assertThat(FieldDispositionTest.applicable(dispositions, "system/task_progress", "summary_tokens"))
                .as("D1 replaced the whole-variant wildcards with exact entries: the disposition gate now reports the new field too").isEmpty();
        // the 24e8ecc catalog's wildcard: with it restored the disposition gate passes, while the obligation gate below still fails
        List<FieldDispositionTest.Entry> withWildcard = new ArrayList<>(dispositions.entries());
        withWildcard.add(new FieldDispositionTest.Entry("system/task_progress", "*", true, "retained", List.of(), null, "whole_record.coverage", List.of(), "24e8ecc wildcard", null));
        assertThat(FieldDispositionTest.applicable(FieldDispositionTest.withEntries(dispositions, withWildcard), "system/task_progress", "summary_tokens"))
                .singleElement().satisfies(e -> assertThat(e.key()).isEqualTo("system/task_progress:*"));
        List<Occurrence> occ = CanonicalObligations.occurrences(c, c.bindings().get(1), FEATURES, added);
        List<Finding> found = CanonicalObligations.tokenMembers(c, added, occ, RawInventory.ProfileRules.forProfile("claude_stream").dataKeyed(), new ArrayList<>());
        assertThat(found).extracting(Finding::id).contains("system/task_progress:summary_tokens",
                "claude.stream.usage.child-run.task-progress@r2|system/task_progress:usage.cache_read_input_tokens");
        assertThat(found).allMatch(f -> f.category() == 1);
    }

    @Test
    void aRequiredBindingWithoutItsCatalogsOrProfileCannotPassAdmission() {
        List<Finding> all = CanonicalObligations.registrations(obligationEntries());
        // the Claude stream binding is fully registered
        assertThat(all).noneMatch(f -> f.id().endsWith(":claude-code/stream-json/2.1.304-stdout"));
        // the final-assembly profiles name sources that have no catalog or oracle profile yet (the D2 dependencies)
        assertThat(all).extracting(Finding::id).contains("disposition-catalog:claude-code/session-transcript/2.1.304-session-file",
                "oracle-profile:github-copilot-cli/events/1.0.100-session-file", "disposition-catalog:junie/session-summary/3813.7");
        // a binding dropped from the obligation catalog is reported, not silently out of the denominator
        Set<String> without = new TreeSet<>(obligationEntries());
        without.remove("claude-code/stream-json/2.1.304-stdout");
        assertThat(CanonicalObligations.registrations(without)).extracting(Finding::id).contains("obligation-catalog:claude-code/stream-json/2.1.304-stdout");
    }

    // ---- R1 controls: the ratchet's identities (review findings CDX-R1-01 and CDX-R1-02) -------------------------------

    static Catalog edited(UnaryOperator<Map<String, Json>> edit) {
        Json raw = NeutralJson.readDocument(CATALOG);
        return CanonicalObligations.parse(CATALOG, new Json.JObject(edit.apply(new LinkedHashMap<>(((Json.JObject) raw).members()))));
    }

    static Json obligationEdited(Json raw, String suffix, UnaryOperator<Map<String, Json>> edit) {
        List<Json> out = new ArrayList<>();
        for (Json o : raw.member("obligations").elements()) {
            out.add(o.member("id").text().endsWith(suffix) ? new Json.JObject(edit.apply(new LinkedHashMap<>(((Json.JObject) o).members()))) : o);
        }
        return new Json.JArray(out);
    }

    @Test
    void aRecordingDroppedFromItsBindingShrinksThePinnedPopulationAndIsReported() {
        // CDX-R1-01: at d36b8eb removing the nonduplicate denial recording left every finding id and obligation key unchanged
        Catalog dropped = edited(m -> {
            List<Json> bindings = new ArrayList<>();
            for (Json b : m.get("bindings").elements()) {
                Map<String, Json> x = new LinkedHashMap<>(((Json.JObject) b).members());
                x.put("recordings", new Json.JArray(b.member("recordings").elements().stream().filter(r -> !r.text().equals("claude-live-2.1.304-denial")).toList()));
                bindings.add(new Json.JObject(x));
            }
            m.put("bindings", new Json.JArray(bindings));
            return m;
        });
        Report r = generate(dropped, recordings(dropped), FieldDispositionTest.catalog("claude_stream"), obligationEntries());
        Set<String> lost = new TreeSet<>(reviewedPopulation());
        lost.removeAll(population(r));
        assertThat(lost).as("the recording and its nine occurrences leave the pinned population").hasSize(10)
                .anyMatch(l -> l.contains("recording claude-live-2.1.304-denial")).filteredOn(l -> l.contains(" occurrence ")).hasSize(9);
        assertThat(r.ids()).contains("5 recording:claude-code/stream-json/2.1.304-stdout:claude-live-2.1.304-denial");
        assertThat(r.findings().stream().filter(f -> f.id().startsWith("recording:"))).allMatch(r::inScope);
        // a byte-identical candidate is still a registered recording: dropping it is reported too, while the report
        // counts it as a repeated candidate, not an independent recording
        assertThat(CanonicalObligations.registered("claude-code/stream-json/2.1.304-stdout")).extracting(CanonicalObligations.Recording::fixture)
                .containsExactly("claude-live-2.1.304-cancelled", "claude-live-2.1.304-denial", "claude-live-2.1.304-features", "claude-live-2.1.304-mcp", "claude-live-2.1.304-mcp-pair");
        assertThat(CanonicalObligations.registered("claude-code/stream-json/2.1.299-sdk")).as("the labelled synthetic pair repeats the child stream: an example, not a recording")
                .extracting(CanonicalObligations.Recording::fixture).containsExactly("claude-child-2.1.299", "claude-child-2.1.299-interrupted");
    }

    @Test
    void aChangedSemanticStatementOrOwnerRoutingIsAChangedMeaning() {
        // CDX-R1-02: at d36b8eb this statement replaced the Agent-result semantics with identical keys and finding ids
        Catalog reversed = edited(m -> {
            m.put("obligations", obligationEdited(new Json.JObject(m), "agent-result", o -> put(o, "semantics",
                    new Json.JString("This is the complete total of all model calls in the entire child run, safe to sum with modelUsage."))));
            return m;
        });
        assertThat(obligationKeys(reversed)).isNotEqualTo(reviewedObligations());
        assertThat(CanonicalObligations.semantics(reversed)).extracting(Finding::id).contains("digest:claude.stream.usage.child-run.agent-result@r3");
        // owner routing is part of every meaning
        Catalog rerouted = edited(m -> put(m, "owner_path", new Json.JString("session_id")));
        assertThat(CanonicalObligations.semantics(rerouted)).extracting(Finding::id).filteredOn(id -> id.startsWith("digest:")).hasSize(7);
        // a review label written into the catalog is not an approval
        Catalog labelled = edited(m -> {
            m.put("obligations", obligationEdited(new Json.JObject(m), "agent-result", o -> put(o, "review", NeutralJson.parse("{\"status\": \"approved\"}"))));
            return m;
        });
        assertThat(CanonicalObligations.semantics(labelled)).extracting(Finding::id).contains("approval:claude.stream.usage.child-run.agent-result@r3")
                .doesNotContain("digest:claude.stream.usage.child-run.agent-result@r3");
    }

    @Test
    void aChangedDefinitionOrRuleTheMeaningDependsOnMakesItsBindingStale() {
        Catalog c = CanonicalObligations.read(CATALOG);
        Obligation agent = c.obligations().stream().filter(o -> o.id().endsWith("agent-result")).findFirst().orElseThrow();
        SemanticClaims.Definitions defs = SemanticClaims.Definitions.read(FoundationComparisonTest.EXTENSION);
        assertThat(CanonicalObligations.relevantDigest(c, agent, defs, new ArrayList<>())).isEqualTo(agent.relevantDigest());
        // the extension's aggregation definition reworded: outside the catalog, so the meaning digest cannot see it
        Map<String, Json> dictionary = new TreeMap<>(defs.dictionary());
        dictionary.put("aggregation_id", NeutralJson.parse("{\"caption\": \"Aggregation ID\", \"type\": \"integer_t\", \"enum\": {\"3\": {\"caption\": \"Child run total\"}}}"));
        SemanticClaims.Definitions reworded = new SemanticClaims.Definitions(defs.classes(), defs.objects(), dictionary);
        assertThat(CanonicalObligations.relevantDigest(c, agent, reworded, new ArrayList<>())).isNotEqualTo(agent.relevantDigest());
        assertThat(CanonicalObligations.meaningDigest(c, agent)).isEqualTo(agent.digest());
        // CDX-R1D-02: the usage observation's own description, and a nested object's definition it depends on (a
        // component's basis enum), are part of the relevant content of every obligation
        Map<String, Json> objects = new TreeMap<>(defs.objects());
        Map<String, Json> observation = new LinkedHashMap<>(((Json.JObject) objects.get("usage_observation")).members());
        observation.put("description", new Json.JString("Absent components mean zero; input includes cache reads and cache writes."));
        objects.put("usage_observation", new Json.JObject(observation));
        SemanticClaims.Definitions described = new SemanticClaims.Definitions(defs.classes(), objects, defs.dictionary());
        Map<String, Json> nestedDictionary = new TreeMap<>(defs.dictionary());
        Map<String, Json> basis = new LinkedHashMap<>(((Json.JObject) nestedDictionary.get("component_basis_id")).members());
        basis.put("enum", NeutralJson.parse("{\"1\": {\"caption\": \"Guessed from undocumented assumptions\"}}"));
        nestedDictionary.put("component_basis_id", new Json.JObject(basis));
        SemanticClaims.Definitions nested = new SemanticClaims.Definitions(defs.classes(), defs.objects(), nestedDictionary);
        Map<String, Json> classes = new TreeMap<>(defs.classes());
        Map<String, Json> context = new LinkedHashMap<>(((Json.JObject) classes.get("session_context")).members());
        context.put("description", new Json.JString("A reworded class meaning."));
        classes.put("session_context", new Json.JObject(context));
        SemanticClaims.Definitions container = new SemanticClaims.Definitions(classes, defs.objects(), defs.dictionary());
        for (Obligation o : c.obligations()) {
            assertThat(CanonicalObligations.relevantDigest(c, o, described, new ArrayList<>())).as(o.key() + " under a reworded object description").isNotEqualTo(o.relevantDigest());
            assertThat(CanonicalObligations.relevantDigest(c, o, nested, new ArrayList<>())).as(o.key() + " under a reworded nested definition").isNotEqualTo(o.relevantDigest());
        }
        assertThat(CanonicalObligations.relevantDigest(c, agent, container, new ArrayList<>())).isNotEqualTo(agent.relevantDigest());
        // a named rule the contract no longer has
        List<String> problems = new ArrayList<>();
        Obligation renamed = new Obligation(agent.id(), agent.revision(), agent.variant(), agent.payload(), agent.requestedTools(), agent.unitPath(), agent.destinationClass(),
                agent.destinationActivity(), agent.contextKind(), agent.unitMember(), agent.scopeId(), agent.status(), agent.aggregation(), agent.model(), agent.components(),
                agent.tiers(), agent.reviewStatus(), agent.digest(), List.of("claude.stream.no_such_rule"), agent.relevantDigest(), agent.raw());
        assertThat(CanonicalObligations.relevantDigest(c, renamed, defs, problems)).isNotEqualTo(agent.relevantDigest());
        assertThat(problems).singleElement().asString().contains("claude.stream.no_such_rule");
    }

    @Test
    void aSecondDefectOfOneOccurrenceIsASecondFinding() {
        // a frozen finding cannot mask another reason on the same occurrence: its id names the reason
        Catalog c = CanonicalObligations.read(CATALOG);
        Recording rec = recording(c, CanonicalObligations.FIXTURES.resolve(FEATURES));
        Recording bad = withEvents(rec, e -> childUsage(e) && "system/task_notification".equals(e.text("context_kind")) ? edited(e, m -> {
            List<Json> obs = new ArrayList<>();
            for (Json u : m.get("usage_observations").elements()) {
                Map<String, Json> x = new LinkedHashMap<>(((Json.JObject) u).members());
                x.put("usage_status_id", new Json.JNumber("1"));
                x.put("reported_total_tokens", new Json.JNumber("21190"));
                obs.add(new Json.JObject(x));
            }
            m.put("usage_observations", new Json.JArray(obs));
            return m;
        }) : e);
        Judged j = judgeAll(c, bad, "claude-code/stream-json/2.1.304-stdout").stream().filter(x -> x.occurrence().obligation().id().endsWith("task-notification")).findFirst().orElseThrow();
        assertThat(j.findings()).extracting(Finding::id).contains(j.occurrence().id() + "#value:usage_status_id", j.occurrence().id() + "#value:reported_total_tokens")
                .doesNotHaveDuplicates();
    }

    @Test
    void aResidualCoversOnlyItsReviewedMembersAndANewOneIsAChangedSet(@TempDir Path work) throws IOException {
        Catalog c = CanonicalObligations.read(CATALOG);
        // a member added under the reviewed iterations[] residual is not covered by it (a subtree prefix hid it at d36b8eb)
        Path copy = OcsfFixtures.copy(FEATURES, work);
        Path stream = copy.resolve("native").resolve("stream.jsonl");
        List<String> lines = new ArrayList<>(Files.readAllLines(stream, StandardCharsets.UTF_8));
        int result = 49;
        assertThat(lines.get(result)).contains("\"type\":\"result\"").contains("\"iterations\":[{\"input_tokens\"");
        lines.set(result, lines.get(result).replaceFirst("\"iterations\":\\[\\{\"input_tokens\"", "\"iterations\":[{\"advisor_tokens\":3,\"input_tokens\""));
        Files.write(stream, lines, StandardCharsets.UTF_8);
        NativeArtifact added = NativeArtifact.read(stream);
        List<Occurrence> occ = CanonicalObligations.occurrences(c, c.bindings().get(1), FEATURES, added);
        List<Finding> found = CanonicalObligations.tokenMembers(c, added, occ, RawInventory.ProfileRules.forProfile("claude_stream").dataKeyed(), new ArrayList<>());
        assertThat(found).extracting(Finding::id).contains("claude.stream.usage.run@r1|result:usage.iterations[].advisor_tokens");
        // a residual added to the catalog changes the reviewed set and is approval debt until an Owner decision approves it
        Catalog widened = edited(m -> {
            List<Json> residuals = new ArrayList<>(m.get("residuals").elements());
            residuals.add(NeutralJson.parse("{\"variant\": \"user/tool_result\", \"path\": \"tool_use_result.usage.output_tokens\", \"classification\": \"vendor_specific\", \"reason\": \"control\"}"));
            m.put("residuals", new Json.JArray(residuals));
            return m;
        });
        assertThat(residualKeys(widened)).isNotEqualTo(reviewedResiduals());
        assertThat(CanonicalObligations.semantics(widened)).extracting(Finding::id).contains("approval:residual:user/tool_result:tool_use_result.usage.output_tokens");
        // and a residual never removes an obligation's occurrence: the Agent result's usage is still judged
        assertThat(CanonicalObligations.occurrences(widened, widened.bindings().get(1), FEATURES, rec(widened).art()))
                .anyMatch(o -> o.obligation().id().endsWith("agent-result"));
    }

    @Test
    void childRunExamplesAreJudgedAtTheirOwnOwnerAndUnit() {
        // the labelled synthetic examples (not part of any binding's population): two Agent calls of one parent, and a
        // nested Agent result in child A reusing child B's tool-use id. Expected identities authored from its records
        Catalog c = CanonicalObligations.read(CATALOG);
        Recording rec = recording(c, CanonicalObligations.FIXTURES.resolve(OcsfFixtures.SYN_CHILD_RUNS));
        Binding stdout = c.bindings().get(1);
        List<Judged> judged = CanonicalObligations.occurrences(c, stdout, rec.fixture(), rec.art()).stream()
                .map(o -> CanonicalObligations.judge(o, rec.cores(), rec.report(), rec.facts())).toList();
        String a = "toolu_01SYNTHETICCHILDRUNAAAAA";
        String b = "toolu_01SYNTHETICCHILDRUNBBBBB";
        assertThat(judged.stream().filter(j -> j.occurrence().obligation().scopeId() == 7).map(j -> j.occurrence().obligation().id().replace("claude.stream.usage.child-run.", "")
                + " record " + j.occurrence().record() + " owner " + j.occurrence().owner() + " unit " + j.occurrence().unit()))
                .containsExactlyInAnyOrder("task-progress record 6 owner main unit " + a, "task-progress record 7 owner main unit " + b,
                        "agent-result record 9 owner child:" + a + " unit " + b,
                        "task-notification record 11 owner main unit " + a, "task-notification record 12 owner main unit " + b,
                        "agent-result record 13 owner main unit " + a, "agent-result record 14 owner main unit " + b);
        assertThat(judged).as(judged.stream().filter(j -> !j.discharged()).map(j -> j.findings().toString()).toList().toString()).allMatch(Judged::discharged);
        // one unit id under two owners is two occurrences, and moving the nested result to main is an unmet obligation
        assertThat(judged.stream().map(j -> j.occurrence().id())).doesNotHaveDuplicates();
        Recording moved = withEvents(rec, e -> e);
        Map<String, List<Event>> cores = new LinkedHashMap<>(moved.cores());
        List<Event> nested = cores.get("child:" + a).stream().filter(e -> childUsage(e)).toList();
        assertThat(nested).hasSize(1);
        cores.put("child:" + a, cores.get("child:" + a).stream().filter(e -> !childUsage(e)).toList());
        List<Event> main = new ArrayList<>(cores.get("main"));
        main.addAll(nested);
        cores.put("main", main);
        Recording relocated = new Recording(rec.fixture(), rec.art(), cores, rec.report(), rec.facts());
        Judged nine = CanonicalObligations.occurrences(c, stdout, rec.fixture(), rec.art()).stream().filter(o -> o.record() == 9)
                .map(o -> CanonicalObligations.judge(o, relocated.cores(), relocated.report(), relocated.facts())).findFirst().orElseThrow();
        assertThat(nine.discharged()).isFalse();
        assertThat(nine.findings()).anyMatch(f -> f.id().endsWith("#no-observation") && f.detail().contains("another owner or unit"));
    }

    @Test
    void aResultReportsItsLastResponseOnlyAsASoleMessageIterationAndOtherShapesAreAGap() {
        // features record 50: iterations[] holds one message entry, 2/29493/592 input side and 521 output (the streamed
        // record 49 of the same response says 7, provisionally); expected values authored from the native record
        Catalog c = CanonicalObligations.read(CATALOG);
        Recording rec = rec(c);
        List<Judged> judged = atRecord(judgeAll(c, rec, STDOUT), FEATURES, 50);
        assertThat(judged).extracting(j -> j.occurrence().obligation().id() + " " + j.occurrence().pointer())
                .contains("claude.stream.usage.run.last-response /usage/iterations/0", "claude.stream.usage.run /usage");
        assertThat(judged).allMatch(Judged::discharged);
        Json last = rec.cores().get("main").stream().filter(e -> Objects.equals(e.recordIndex(), 50)).flatMap(e -> e.elements("usage_observations").stream())
                .filter(CanonicalConformanceTest::singleResponse).findFirst().orElseThrow();
        assertThat(List.of(last.member("scope_id").scalarText(), last.member("usage_status_id").scalarText(), last.member("uncached_input_tokens").scalarText(),
                last.member("cache_read_tokens").scalarText(), last.member("cache_write_tokens").scalarText(), last.member("output_tokens").scalarText()))
                .containsExactly("4", "2", "2", "29493", "592", "521");
        assertThat(last.member("reasoning_tokens")).as("an iteration reports no thinking tokens; absence is not zero").isNull();
        assertThat(last.member("model_ref")).as("the entry names no model, so none is stated").isNull();
        // without the observation the obligation is unmet: the one scope-4 observation left, the run total, is another
        // measurement window and fails it, while the run total's own occurrence is not confused by what remains
        Recording dropped = withLastResponse(rec, u -> null);
        List<Judged> after = atRecord(judgeAll(c, dropped, STDOUT), FEATURES, 50);
        assertThat(after).filteredOn(j -> j.occurrence().obligation().id().endsWith("last-response")).singleElement()
                .satisfies(j -> assertThat(j.findings()).extracting(Finding::id).contains(j.occurrence().id() + "#value:aggregation_id", j.occurrence().id() + "#value:output_tokens"));
        assertThat(after).filteredOn(j -> j.occurrence().obligation().id().endsWith("usage.run")).allMatch(Judged::discharged);
        // the streamed provisional output in its place is a value defect of this occurrence only
        Recording provisional = withLastResponse(rec, u -> put(u, "output_tokens", new Json.JNumber("7")));
        assertThat(judgeAll(c, provisional, STDOUT).stream().filter(j -> !j.discharged()))
                .singleElement().satisfies(j -> assertThat(j.findings()).extracting(Finding::id).containsExactly(j.occurrence().id() + "#value:output_tokens"));
        // the labelled synthetic result (record 9): a compaction and a message entry state no last response, and the gate
        // keeps that occurrence as an explicit gap instead of a guessed sum or choice
        Recording shapes = recording(c, CanonicalObligations.FIXTURES.resolve(OcsfFixtures.SYN_AGENT_DEFAULTS));
        List<Judged> lastResponses = judgeAll(c, shapes, STDOUT).stream().filter(j -> j.occurrence().obligation().id().endsWith("last-response")).toList();
        assertThat(lastResponses).singleElement().satisfies(j -> {
            assertThat(j.occurrence().record()).isEqualTo(9);
            assertThat(j.occurrence().pointer()).isEqualTo("/usage/iterations");
            assertThat(j.findings()).extracting(Finding::id).containsExactly(j.occurrence().id() + "#not-representable");
        });
        assertThat(shapes.cores().get("main").stream().filter(e -> Objects.equals(e.recordIndex(), 9)).flatMap(e -> e.elements("usage_observations").stream())
                .filter(u -> Integer.valueOf(4).equals(Event.integer(u.member("scope_id"))))).as("only the run total").singleElement()
                .satisfies(u -> assertThat(u.member("aggregation_id")).isNull());
    }

    @Test
    void theAgentResultsZeroDefaultsArePossiblyDefaultWhileAResponsesZerosStayReported() {
        // the labelled synthetic examples: record 5 is the emitter's default object exactly, record 6 a response's usage
        // with a real 0 cache-write tranche, record 7 every tranche 0 but one iterations entry, which only a response's
        // usage over the defaults can carry; expectations authored from those records
        Catalog c = CanonicalObligations.read(CATALOG);
        Recording rec = recording(c, CanonicalObligations.FIXTURES.resolve(OcsfFixtures.SYN_AGENT_DEFAULTS));
        List<Judged> agent = judgeAll(c, rec, STDOUT).stream().filter(j -> j.occurrence().obligation().id().endsWith("agent-result")).toList();
        assertThat(agent).extracting(j -> j.occurrence().record()).containsExactly(5, 6, 7);
        assertThat(agent).as(agent.stream().flatMap(j -> j.findings().stream()).toList().toString()).allMatch(Judged::discharged);
        Map<Integer, Json> byRecord = new TreeMap<>();
        rec.cores().get("main").stream().filter(CanonicalConformanceTest::childUsage).forEach(e -> byRecord.put(e.recordIndex(), e.elements("usage_observations").get(0)));
        assertThat(byRecord.keySet()).containsExactly(5, 6, 7);
        assertThat(Event.integer(byRecord.get(5).member("measurement_status_id"))).isEqualTo(2);
        assertThat(byRecord.get(5).member("output_tokens").scalarText()).as("the zeros are kept as reported").isEqualTo("0");
        assertThat(byRecord.get(6).member("measurement_status_id")).isNull();
        assertThat(byRecord.get(6).member("cache_write_tokens").scalarText()).isEqualTo("0");
        assertThat(byRecord.get(7).member("measurement_status_id")).isNull();
        // the oracle's own reading expects the same, independently of the writer
        assertThat(rec.facts().get("main").usages.stream().filter(u -> u.scopeId() == 7).map(u -> u.recordIndex() + "=" + u.measurementStatusId()))
                .containsExactly("5=2", "6=null", "7=null");
        assertThat(rec.report().outcome("usage.values", "main/stream.jsonl")).isEqualTo(Check.Outcome.VERIFIED);
        // a default object stated as an evidenced response, or a response's zeros stated as possibly default, is a value defect
        Recording asserted = withEvents(rec, e -> childUsage(e) && List.of(5, 6).contains(e.recordIndex()) ? edited(e, m -> {
            Map<String, Json> u = new LinkedHashMap<>(((Json.JObject) e.elements("usage_observations").get(0)).members());
            if (Objects.equals(e.recordIndex(), 5)) {
                u.remove("measurement_status_id");
                u.remove("measurement_status");
            }
            else {
                u.put("measurement_status_id", new Json.JNumber("2"));
            }
            m.put("usage_observations", new Json.JArray(List.of(new Json.JObject(u))));
            return m;
        }) : e);
        assertThat(judgeAll(c, asserted, STDOUT).stream().filter(j -> j.occurrence().obligation().id().endsWith("agent-result")).filter(j -> !j.discharged())
                .flatMap(j -> j.findings().stream()).map(Finding::id))
                .containsExactlyInAnyOrder(agent.get(0).occurrence().id() + "#value:measurement_status_id", agent.get(1).occurrence().id() + "#value:measurement_status_id");
    }

    @Test
    void aResidualLimitedToMessageEntriesReportsAnEntryOfAnotherType(@TempDir Path work) throws IOException {
        // a compaction entry in the Agent result's iterations[] (its tokens are outside the response's top-level usage)
        // is not covered by the message-entry residual: it is an unaccounted entry, not silently owed nothing
        Catalog c = CanonicalObligations.read(CATALOG);
        Path stream = work.resolve(CanonicalObligations.ARTIFACT);
        List<String> lines = new ArrayList<>(Files.readAllLines(CanonicalObligations.FIXTURES.resolve(FEATURES).resolve("native").resolve(CanonicalObligations.ARTIFACT), StandardCharsets.UTF_8));
        String agentResult = lines.get(32);
        assertThat(agentResult).contains("\"totalTokens\":21133").contains("\"iterations\":[{");
        lines.set(32, agentResult.replace("\"iterations\":[{", "\"iterations\":[{\"input_tokens\":40,\"output_tokens\":600,\"cache_read_input_tokens\":900,\"cache_creation_input_tokens\":0,\"type\":\"compaction\"},{"));
        Files.write(stream, lines, StandardCharsets.UTF_8);
        NativeArtifact added = NativeArtifact.read(stream);
        Set<String> dataKeyed = RawInventory.ProfileRules.forProfile("claude_stream").dataKeyed();
        List<Finding> found = CanonicalObligations.tokenMembers(c, added, CanonicalObligations.occurrences(c, c.bindings().get(1), FEATURES, added), dataKeyed, new ArrayList<>());
        assertThat(found).extracting(Finding::id).containsExactly("user/tool_result:tool_use_result.usage.iterations[]{type=compaction}");
        NativeArtifact original = rec(c).art();
        assertThat(CanonicalObligations.tokenMembers(c, original, CanonicalObligations.occurrences(c, c.bindings().get(1), FEATURES, original), dataKeyed, new ArrayList<>())).isEmpty();
    }

    static final String STDOUT = "claude-code/stream-json/2.1.304-stdout";

    static List<Judged> atRecord(List<Judged> judged, String fixture, int record) {
        return judged.stream().filter(j -> j.occurrence().fixture().equals(fixture) && j.occurrence().record() == record).toList();
    }

    static boolean singleResponse(Json u) {
        return Integer.valueOf(4).equals(Event.integer(u.member("scope_id"))) && Integer.valueOf(3).equals(Event.integer(u.member("aggregation_id")));
    }

    /** The features recording with the result's last-response observation edited, or removed when {@code edit} returns {@code null}. */
    static Recording withLastResponse(Recording rec, UnaryOperator<Map<String, Json>> edit) {
        return withEvents(rec, e -> Objects.equals(e.recordIndex(), 50) && "prompt_turn".equals(e.className) ? edited(e, m -> {
            List<Json> obs = new ArrayList<>();
            for (Json u : e.elements("usage_observations")) {
                Map<String, Json> x = singleResponse(u) ? edit.apply(new LinkedHashMap<>(((Json.JObject) u).members())) : ((Json.JObject) u).members();
                if (x != null) {
                    obs.add(new Json.JObject(x));
                }
            }
            m.put("usage_observations", new Json.JArray(obs));
            return m;
        }) : e);
    }

    private static Recording rec(Catalog c) {
        return recording(c, CanonicalObligations.FIXTURES.resolve(FEATURES));
    }

    // ---- support ------------------------------------------------------------------------------------------------------------

    static Map<String, Json> put(Map<String, Json> m, String k, Json v) {
        m.put(k, v);
        return m;
    }

    static FieldDispositionTest.Catalog replaced(FieldDispositionTest.Catalog c, String key, UnaryOperator<FieldDispositionTest.Entry> edit) {
        List<FieldDispositionTest.Entry> out = new ArrayList<>();
        for (FieldDispositionTest.Entry e : c.entries()) {
            out.add(e.key().equals(key) ? edit.apply(e) : e);
        }
        return FieldDispositionTest.withEntries(c, out);
    }

    static FieldDispositionTest.Entry without(FieldDispositionTest.Entry e, String member) {
        Map<String, Json> m = new LinkedHashMap<>(((Json.JObject) e.raw()).members());
        m.remove(member);
        return new FieldDispositionTest.Entry(e.variant(), e.path(), e.subtree(), e.disposition(), e.destinations(), e.rule(), e.property(), e.tests(), e.note(), new Json.JObject(m));
    }

    @SuppressWarnings("unchecked")
    static Json toJson(Object o) {
        if (o == null) {
            return Json.JNull.INSTANCE;
        }
        if (o instanceof Json j) {
            return j;
        }
        if (o instanceof Map<?, ?> m) {
            Map<String, Json> out = new LinkedHashMap<>();
            ((Map<String, Object>) m).forEach((k, v) -> out.put(k, toJson(v)));
            return new Json.JObject(out);
        }
        if (o instanceof List<?> l) {
            return new Json.JArray(l.stream().map(CanonicalConformanceTest::toJson).toList());
        }
        if (o instanceof Boolean b) {
            return new Json.JBool(b);
        }
        if (o instanceof Number n) {
            return new Json.JNumber(n.toString());
        }
        return new Json.JString(o.toString());
    }

    /** The reviewed obligation set the baseline pins: id, revision, meaning digest and relevant digest. */
    static Set<String> reviewedObligations() {
        Set<String> out = new TreeSet<>();
        for (Json o : NeutralJson.readDocument(BASELINE).member("obligations").elements()) {
            out.add(o.member("id").text() + "@r" + o.member("revision").scalarText() + "#" + o.member("meaning_digest").text() + "#" + o.member("relevant_digest").text());
        }
        return out;
    }

    static Set<String> obligationKeys(Catalog c) {
        SemanticClaims.Definitions defs = SemanticClaims.Definitions.read(FoundationComparisonTest.EXTENSION);
        Set<String> out = new TreeSet<>();
        c.obligations().forEach(o -> out.add(o.key() + "#" + CanonicalObligations.meaningDigest(c, o) + "#" + CanonicalObligations.relevantDigest(c, o, defs, new ArrayList<>())));
        return out;
    }

    /** The reviewed residual set the baseline pins: variant, path, classification and digest. */
    static Set<String> reviewedResiduals() {
        Set<String> out = new TreeSet<>();
        for (Json r : NeutralJson.readDocument(BASELINE).member("residuals").elements()) {
            out.add(r.member("variant").text() + ":" + r.member("path").text() + "|" + r.member("classification").text() + "#" + r.member("digest").text());
        }
        return out;
    }

    static Set<String> residualKeys(Catalog c) {
        Set<String> out = new TreeSet<>();
        c.residuals().forEach(r -> out.add(r.key() + "|" + r.classification() + "#" + CanonicalObligations.residualDigest(r)));
        return out;
    }

    /** The reviewed population the baseline pins, one line per entry, recording and occurrence. */
    static Set<String> reviewedPopulation() {
        Set<String> out = new TreeSet<>();
        for (Json b : NeutralJson.readDocument(BASELINE).member("population").elements()) {
            String entry = b.member("compatibility_entry").text();
            out.add(entry + " admission " + b.member("admission").text());
            b.member("recordings").elements().forEach(x -> out.add(entry + " recording " + x.member("fixture").text() + " " + x.member("sha256").text()));
            b.member("occurrences").elements().forEach(x -> out.add(entry + " occurrence " + x.text()));
        }
        return out;
    }

    static Set<String> population(Report r) {
        Json registry = NeutralJson.readDocument(CanonicalObligations.REGISTRY);
        Set<String> out = new TreeSet<>();
        for (Binding b : r.catalog().bindings()) {
            Json e = registry.member("entries").elements().stream().filter(x -> b.entry().equals(SourceProfile.text(x, "id"))).findFirst().orElseThrow();
            out.add(b.entry() + " admission " + SourceProfile.text(e, "admission"));
            for (String f : b.recordings()) {
                out.add(b.entry() + " recording " + f + " " + NativeArtifact.read(CanonicalObligations.FIXTURES.resolve(f).resolve("native").resolve(CanonicalObligations.ARTIFACT)).sha256());
            }
            r.judged().stream().filter(j -> j.occurrence().entry().equals(b.entry())).forEach(j -> out.add(b.entry() + " occurrence " + j.occurrence().id()));
        }
        return out;
    }

    static Map<String, Integer> counts(Report r) {
        Map<String, Integer> out = new TreeMap<>();
        r.findings().forEach(f -> out.merge(Integer.toString(f.category()), 1, Integer::sum));
        return out;
    }
}
