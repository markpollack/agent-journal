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
 * {@code ocsf/conformance/claude-stream-usage-baseline.json} pins the reviewed obligation set (id, revision, digest) and
 * the frozen debt by exact finding identity: a new finding fails, a frozen one that no longer occurs fails until it is
 * removed from the file, and a changed obligation set fails until the baseline is edited (a reviewed change). A green
 * ratchet says the baseline holds, not that the scope is accepted: semantic approval stays a separate category.
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
            return f.category() != 5 || catalog.bindings().stream().anyMatch(b -> f.id().endsWith(":" + b.entry()));
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
            List<Object> recs = new ArrayList<>();
            for (String f : b.recordings()) {
                NativeArtifact art = recordings.get(f).art();
                recs.add(ordered("fixture", f, "artifact", CanonicalObligations.ARTIFACT, "sha256", art.sha256(), "reported_version", CanonicalObligations.reportedVersion(art),
                        "declared_route", CanonicalObligations.declaredRoute(f)));
            }
            bd.put("recordings", recs);
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
        assertThat(obligationKeys(r.catalog())).as("the obligation set is the reviewed one (id, revision, meaning digest); changing it, a reclassification included, is a baseline edit")
                .isEqualTo(reviewedObligations());
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
        assertThat(missing).singleElement().asString().startsWith("claude.stream.usage.child-run.task-progress@r1#");
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
                "claude.stream.usage.child-run.task-progress@r1|system/task_progress:usage.cache_read_input_tokens");
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

    /** The reviewed obligation set the baseline pins: id, revision and meaning digest. */
    static Set<String> reviewedObligations() {
        Set<String> out = new TreeSet<>();
        for (Json o : NeutralJson.readDocument(BASELINE).member("obligations").elements()) {
            out.add(o.member("id").text() + "@r" + o.member("revision").scalarText() + "#" + o.member("meaning_digest").text());
        }
        return out;
    }

    static Set<String> obligationKeys(Catalog c) {
        Set<String> out = new TreeSet<>();
        c.obligations().forEach(o -> out.add(o.key() + "#" + CanonicalObligations.meaningDigest(o)));
        return out;
    }

    static Map<String, Integer> counts(Report r) {
        Map<String, Integer> out = new TreeMap<>();
        r.findings().forEach(f -> out.merge(Integer.toString(f.category()), 1, Integer::sum));
        return out;
    }
}
