package io.github.markpollack.journal.tck.ocsf;

import io.github.markpollack.journal.tck.ocsf.OccurrenceLedger.Assertion;
import io.github.markpollack.journal.tck.ocsf.OccurrenceLedger.Classified;
import io.github.markpollack.journal.tck.ocsf.OccurrenceLedger.Coverage;
import io.github.markpollack.journal.tck.ocsf.OccurrenceLedger.Families;
import io.github.markpollack.journal.tck.ocsf.OccurrenceLedger.Ledger;
import io.github.markpollack.journal.tck.ocsf.OccurrenceLedger.Occurrence;
import io.github.markpollack.journal.tck.ocsf.OccurrenceLedger.Source;
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
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The cross-CLI accounting report (ACCT-1) and its ratchet. For every registered recorded source of every fixture the
 * {@link OccurrenceLedger} enumerates and classifies concrete native occurrences; for every package it inventories the
 * typed assertions. The report is written to {@code target/accounting/} (the matrix as JSON and Markdown, one ledger
 * per source). The baseline {@code ocsf/conformance/cross-cli-accounting-baseline.json} pins, by exact identity: each
 * disposition catalog's hash and entry counts, the family and coverage tables' hashes, every source (fixture, artifact,
 * hash, entry) with its classification counts per family and its exact gap ids, and per class the exact set of
 * unjudged assertion paths. Any change fails with a human-readable delta; editing the baseline is the reviewable
 * change, never its approval. A green ratchet says the ledger holds, not that any gap is accepted.
 */
class CrossCliAccountingTest {

    static final Path PROJECT = FieldDispositionTest.PROJECT;
    static final Path BASELINE = PROJECT.resolve("journal-capture-tck/src/test/resources/ocsf/conformance/cross-cli-accounting-baseline.json");
    static final Path OUT = Path.of(System.getProperty("basedir", ".")).resolve("target").resolve("accounting");

    /** The generated result: every ledger, the assertion census and the snapshot lines the baseline pins. */
    record Result(List<Ledger> ledgers, Map<String, Map<String, Map<String, Integer>>> census, Map<String, FieldDispositionTest.Catalog> catalogs, Families families,
            Coverage coverage, Map<String, ExecutedAccounting.Run> executed, List<String> snapshot) {
    }

    static Result generate() {
        return generate(OccurrenceLedger.sources(), OccurrenceLedger.catalogs(), Families.read(), Coverage.read());
    }

    static Result generate(List<Source> sources, Map<String, FieldDispositionTest.Catalog> catalogs, Families families, Coverage coverage) {
        List<Ledger> ledgers = new ArrayList<>();
        Map<String, String> ownerPaths = new LinkedHashMap<>();
        for (Source s : sources) {
            ledgers.add(OccurrenceLedger.ledger(s, catalogs, families, ownerPaths.computeIfAbsent(s.profile(), OccurrenceLedger::ownerPath)));
        }
        List<Assertion> assertions = new ArrayList<>();
        Set<String> fixtures = new TreeSet<>();
        sources.forEach(s -> fixtures.add(s.fixture()));
        for (String fixture : fixtures) {
            Map<String, String> profiles = new LinkedHashMap<>();
            sources.stream().filter(s -> s.fixture().equals(fixture)).forEach(s -> profiles.put(s.artifact(), s.profile()));
            assertions.addAll(OccurrenceLedger.assertions(fixture, PackageReader.open(OccurrenceLedger.FIXTURES.resolve(fixture).resolve("package").resolve("events.jsonl")), profiles));
        }
        Map<String, Map<String, Map<String, Integer>>> census = OccurrenceLedger.census(assertions, coverage);
        Map<String, ExecutedAccounting.Run> executed = ExecutedAccounting.run(fixtures);
        List<String> snapshot = snapshot(ledgers, census, catalogs, families, coverage);
        snapshot.addAll(ExecutedAccounting.snapshot(executed));
        snapshot.addAll(dependencies(PROJECT));
        java.util.Collections.sort(snapshot);
        return new Result(ledgers, census, catalogs, families, coverage, executed, snapshot);
    }

    /** The pinned identity of the result, one line per fact, sorted: a change in any line is a reviewable delta. */
    static List<String> snapshot(List<Ledger> ledgers, Map<String, Map<String, Map<String, Integer>>> census, Map<String, FieldDispositionTest.Catalog> catalogs, Families families,
            Coverage coverage) {
        Set<String> out = new TreeSet<>();
        catalogs.forEach((profile, c) -> {
            out.add("catalog " + profile + " sha256 " + NativeArtifact.sha256(CanonicalConformanceTest.read(PROJECT.resolve(FieldDispositionTest.CATALOGS.get(profile)))));
            Map<String, Integer> counts = new TreeMap<>();
            for (FieldDispositionTest.Entry e : c.entries()) {
                counts.merge(e.disposition(), 1, Integer::sum);
                if (e.vendorDeclared()) {
                    counts.merge("vendor_declared", 1, Integer::sum);
                }
                if ("retained".equals(e.disposition())) {
                    counts.merge("retained:" + OccurrenceLedger.classification(e), 1, Integer::sum);
                }
            }
            counts.forEach((k, v) -> out.add("catalog " + profile + " entries " + k + " " + v));
        });
        out.add("families sha256 " + families.sha256());
        out.add("assertion-coverage sha256 " + coverage.sha256());
        for (Ledger l : ledgers) {
            String id = "source " + l.source().id();
            out.add(id + " sha256 " + l.source().sha256() + " profile " + l.source().profile() + " entry " + l.source().entry() + (l.source().synthetic() ? " synthetic" : ""));
            out.add(id + " occurrences " + l.rows().size() + " records " + l.source().records());
            l.matrix().forEach((family, byClass) -> byClass.forEach((cls, n) -> out.add(id + " " + family + " " + cls + " paths " + n[0] + " occurrences " + n[1])));
            l.gaps().forEach(g -> out.add(id + " gap " + g.substring(0, g.indexOf(" ("))));
            l.unexercisedEntries().forEach((d, n) -> out.add(id + " unexercised-entries " + d + " " + n));
        }
        census.forEach((cls, byOrigin) -> {
            byOrigin.forEach((origin, paths) -> out.add("assertions " + cls + " " + origin + " paths " + paths.size()));
            byOrigin.getOrDefault("unjudged", Map.of()).keySet().forEach(p -> out.add("assertions " + cls + " unjudged " + p));
        });
        return new ArrayList<>(out);
    }

    /** Content dependencies of the executed procedure, including readers, checks, meanings and native manifests. */
    static List<String> dependencies(Path project) {
        List<String> result = new ArrayList<>();
        for (String path : List.of("journal-capture-tck/src/main", "journal-capture-tck/src/test/java",
                "journal-capture-tck/src/test/resources/ocsf/fixtures", "journal-capture-tck/src/test/resources/ocsf/mapping-catalog",
                "journal-capture-tck/src/test/resources/ocsf/conformance", "journal-ocsf-writer/src/main",
                "schema/vendor", "prototype/ocsf-acp/extension", "prototype/ocsf-acp/toolchain.lock.json")) {
            result.add("dependency " + path + " sha256:" + dependencyDigest(project.resolve(path)));
        }
        return result;
    }

    static String dependencyDigest(Path path) {
        try (var files = Files.walk(path)) {
            List<String> content = new ArrayList<>();
            for (Path file : files.filter(Files::isRegularFile)
                    .filter(p -> !p.getFileName().toString().endsWith("baseline.json")).sorted().toList()) {
                content.add(path.relativize(file) + " " + NativeArtifact.sha256(Files.readAllBytes(file)));
            }
            return NativeArtifact.sha256(String.join("\n", content).getBytes(StandardCharsets.UTF_8));
        }
        catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    @Test
    void aChangedCheckerOrAddedMeaningInvalidatesTheDependencySnapshot(@TempDir Path work) throws IOException {
        Path reader = work.resolve("Reader.java");
        Files.writeString(reader, "exact comparison");
        String original = dependencyDigest(work);
        Files.writeString(reader, "comparison removed");
        assertThat(dependencyDigest(work)).isNotEqualTo(original);
        Files.writeString(reader, "exact comparison");
        assertThat(dependencyDigest(work)).isEqualTo(original);
        Files.writeString(work.resolve("new-meaning.json"), "new native meaning");
        assertThat(dependencyDigest(work)).isNotEqualTo(original);
    }

    // ---- the report ----------------------------------------------------------------------------------------------------------

    @Test
    void theLedgerHoldsTheReviewedBaselineExactly() throws IOException {
        Result r = generate();
        write(r);
        // A snapshot may preserve unresolved meanings or an unsupported version, never unfinished comparisons.
        for (Ledger ledger : r.ledgers()) {
            assertThat(NativeAccounting.account(ledger, r.executed().get(ledger.source().fixture()).report()))
                    .as(ledger.source().fixture() + "/" + ledger.source().artifact() + " executed native accounting")
                    .allMatch(o -> o.status().equals("VERIFIED")
                            || o.status().equals("UNRESOLVED") && o.classification().equals("unresolved")
                            || o.status().equals("UNSUPPORTED_VERSION") && ledger.source().entry() == null);
        }
        Json baseline = NeutralJson.readDocument(BASELINE);
        Set<String> pinned = new TreeSet<>(CanonicalObligations.texts(baseline.member("snapshot")));
        Set<String> added = new TreeSet<>(r.snapshot());
        added.removeAll(pinned);
        Set<String> removed = new TreeSet<>(pinned);
        removed.removeAll(r.snapshot());
        assertThat(delta(added, removed)).as("the cross-CLI accounting ledger differs from the reviewed baseline; edit " + BASELINE.getFileName()
                + " deliberately (see target/accounting/cross-cli-matrix.md)").isEmpty();
    }

    /** The human-readable delta: every line that appeared or disappeared, grouped. */
    static List<String> delta(Set<String> added, Set<String> removed) {
        List<String> out = new ArrayList<>();
        added.forEach(a -> out.add("+ " + a));
        removed.forEach(x -> out.add("- " + x));
        return out;
    }

    static void write(Result r) throws IOException {
        Files.createDirectories(OUT.resolve("ledgers"));
        ExecutedAccounting.write(r.executed(), OUT);
        Map<String, Object> doc = document(r);
        Files.writeString(OUT.resolve("cross-cli-matrix.json"), NeutralJson.write(CanonicalConformanceTest.toJson(doc)) + "\n", StandardCharsets.UTF_8);
        Files.writeString(OUT.resolve("cross-cli-matrix.md"), markdown(r, doc), StandardCharsets.UTF_8);
        Files.writeString(OUT.resolve("snapshot.txt"), String.join("\n", r.snapshot()) + "\n", StandardCharsets.UTF_8);
        for (Ledger l : r.ledgers()) {
            Map<String, Object> ld = new LinkedHashMap<>();
            ld.put("source", source(l.source()));
            ld.put("occurrences", l.rows().size());
            Map<String, Object> byClass = new LinkedHashMap<>();
            l.pathsByClassification().forEach((cls, paths) -> byClass.put(cls, paths));
            ld.put("paths_by_classification", byClass);
            ld.put("gaps", l.gaps());
            ld.put("unexercised_entries", l.unexercisedEntries());
            List<Object> rows = new ArrayList<>();
            for (Classified c : l.rows()) {
                Occurrence o = c.occurrence();
                rows.add(CanonicalConformanceTest.ordered("record", o.record(), "pointer", o.pointer(), "variant", o.variant(), "path", o.path(), "kind", o.kind(), "owner", o.owner(),
                        "entry", c.entryKey(), "disposition", c.disposition(), "classification", c.classification(), "family", c.family()));
            }
            ld.put("rows", rows);
            List<NativeAccounting.Outcome> outcomes = NativeAccounting.account(l, r.executed().get(l.source().fixture()).report());
            ld.put("executed_outcomes", outcomes.stream().map(o -> CanonicalConformanceTest.ordered("record", o.record(), "pointer", o.pointer(), "classification", o.classification(),
                    "status", o.status(), "comparisons", o.comparisons())).toList());
            ld.put("executed_counts", NativeAccounting.counts(outcomes));
            Files.writeString(OUT.resolve("ledgers").resolve(l.source().fixture() + "__" + l.source().artifact() + ".json"),
                    NeutralJson.write(CanonicalConformanceTest.toJson(ld)) + "\n", StandardCharsets.UTF_8);
        }
    }

    static Map<String, Object> source(Source s) {
        return CanonicalConformanceTest.ordered("fixture", s.fixture(), "artifact", s.artifact(), "profile", s.profile(), "route", s.route(), "sha256", s.sha256(),
                "records", s.records(), "version", s.version(), "compatibility_entry", s.entry(), "admission", s.admission(), "cli", s.cli(), "synthetic", s.synthetic(),
                "unselected", s.unselected());
    }

    static Map<String, Object> document(Result r) {
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("kind", "agent-journal/cross-cli-accounting");
        doc.put("version", "0.2");
        doc.put("meaning", "Native occurrences F are enumerated and classified from frozen sources. Independent readers construct E; the candidate supplies A. "
                + "Only attributed, executed comparisons discharge required facts and support assertions. Declared checker assignments grant no execution credit. "
                + "Exact populations, non-passing outcomes and content dependencies are ratcheted separately from semantic approvals.");
        doc.put("dependencies", dependencies(PROJECT));
        doc.put("families", r.families().names());
        doc.put("classifications", OccurrenceLedger.CLASSIFICATIONS);
        List<Object> sources = new ArrayList<>();
        Map<String, Set<String>> distinct = new TreeMap<>();
        for (Ledger l : r.ledgers()) {
            Source s = l.source();
            if (!s.synthetic()) {
                // a labelled synthetic example is not a recording; byte-identical candidates (mcp and mcp-pair) are one recording
                distinct.computeIfAbsent(s.cli() + " " + s.version() + " " + s.profile() + " " + s.artifact(), k -> new TreeSet<>()).add(s.sha256());
            }
            Map<String, Object> row = new LinkedHashMap<>(source(s));
            row.put("occurrences", l.rows().size());
            row.put("native_comparisons", NativeAccounting.counts(NativeAccounting.account(l, r.executed().get(s.fixture()).report())));
            Map<String, Object> matrix = new LinkedHashMap<>();
            l.matrix().forEach((family, byClass) -> {
                Map<String, Object> cells = new LinkedHashMap<>();
                byClass.forEach((cls, n) -> cells.put(cls, CanonicalConformanceTest.ordered("paths", n[0], "occurrences", n[1])));
                matrix.put(family, cells);
            });
            row.put("families", matrix);
            row.put("gaps", l.gaps());
            row.put("unexercised_entries", l.unexercisedEntries());
            sources.add(row);
        }
        doc.put("sources", sources);
        List<Object> recordings = new ArrayList<>();
        distinct.forEach((k, shas) -> recordings.add(CanonicalConformanceTest.ordered("source", k, "distinct_native_recordings", shas.size())));
        doc.put("distinct_recordings", recordings);
        Map<String, Object> census = new LinkedHashMap<>();
        r.census().forEach((cls, byOrigin) -> {
            Map<String, Object> c = new LinkedHashMap<>();
            byOrigin.forEach((origin, paths) -> c.put(origin, CanonicalConformanceTest.ordered("paths", paths.size(), "occurrences", paths.values().stream().mapToInt(Integer::intValue).sum(),
                    "list", "unjudged".equals(origin) ? new ArrayList<>(paths.keySet()) : List.of())));
            census.put(cls, c);
        });
        doc.put("declared_assertion_assignments", census);
        doc.put("executed_comparisons", ExecutedAccounting.summary(r.executed(), r.ledgers()));
        doc.put("not_measured", List.of(
                "whether a canonical classification is semantically right: that is the obligation gate and the Owner's approval, not this ledger",
                "future source versions, unrecorded behaviors, and unverified acquisition options; no live acquisition is performed by this report",
                "semantic approvals: a passed comparison does not approve its meaning; existing Owner decisions remain authoritative"));
        return doc;
    }

    @SuppressWarnings("unchecked")
    static String markdown(Result r, Map<String, Object> doc) {
        StringBuilder md = new StringBuilder("# Cross-CLI occurrence accounting\n\nGenerated by `CrossCliAccountingTest`. Do not edit; regenerate.\n\n");
        md.append("Cell: distinct templated paths per family as `canonical / residual / structural / redundant / opaque / gap`; a gap is any of ")
                .append(OccurrenceLedger.GAPS).append(" (exact ids in the per-source ledgers).\n\n");
        md.append("| CLI | Source | Entry | Records | Occurrences |");
        for (String f : r.families().names()) {
            md.append(' ').append(f).append(" |");
        }
        md.append(" unassigned |\n|---|---|---|---|---|");
        for (int i = 0; i <= r.families().names().size(); i++) {
            md.append("---|");
        }
        md.append('\n');
        for (Ledger l : r.ledgers()) {
            Source s = l.source();
            Map<String, Map<String, int[]>> m = l.matrix();
            md.append("| ").append(s.cli()).append(' ').append(s.version()).append(s.synthetic() ? " (synthetic)" : "").append(" | ").append(s.fixture()).append('/').append(s.artifact())
                    .append(" | ").append(s.entry() == null ? "none" : s.entry() + " (" + s.admission() + ")").append(" | ").append(s.records()).append(" | ").append(l.rows().size()).append(" |");
            List<String> columns = new ArrayList<>(r.families().names());
            columns.add("unassigned");
            for (String f : columns) {
                Map<String, int[]> byClass = m.getOrDefault(f, Map.of());
                md.append(' ').append(byClass.isEmpty() ? "absent" : cell(byClass)).append(" |");
            }
            md.append('\n');
        }
        md.append("\n| Source | Distinct native recordings |\n|---|---|\n");
        for (Object o : (List<Object>) doc.get("distinct_recordings")) {
            Map<String, Object> d = (Map<String, Object>) o;
            md.append("| ").append(d.get("source")).append(" | ").append(d.get("distinct_native_recordings")).append(" |\n");
        }
        md.append("\n## Declared checker assignments per class (no execution credit)\n\n| Class | assigned | journal | caller | unjudged |\n|---|---|---|---|---|\n");
        r.census().forEach((cls, byOrigin) -> md.append("| ").append(cls).append(" | ").append(count(byOrigin, "assigned")).append(" | ").append(count(byOrigin, "journal"))
                .append(" | ").append(count(byOrigin, "caller")).append(" | ").append(count(byOrigin, "unjudged")).append(" |\n"));
        md.append(ExecutedAccounting.markdown(r.executed()));
        md.append("\n## Exact gaps per source\n\n");
        for (Ledger l : r.ledgers()) {
            if (!l.gaps().isEmpty()) {
                md.append("### ").append(l.source().id()).append("\n\n");
                l.gaps().forEach(g -> md.append("- ").append(g.replace("|", "\\|")).append('\n'));
                md.append('\n');
            }
        }
        md.append("## Unjudged assertion paths\n\n");
        r.census().forEach((cls, byOrigin) -> byOrigin.getOrDefault("unjudged", Map.of()).forEach((p, n) -> md.append("- `").append(p).append("` (").append(n).append(")\n")));
        md.append("\n## Not measured\n\n");
        ((List<String>) doc.get("not_measured")).forEach(n -> md.append("- ").append(n).append('\n'));
        return md.toString();
    }

    private static String count(Map<String, Map<String, Integer>> byOrigin, String origin) {
        Map<String, Integer> paths = byOrigin.get(origin);
        return paths == null ? "0" : paths.size() + " paths / " + paths.values().stream().mapToInt(Integer::intValue).sum();
    }

    private static String cell(Map<String, int[]> byClass) {
        int canonical = paths(byClass, "canonical");
        int residual = paths(byClass, "vendor_residual");
        int structural = paths(byClass, "structural");
        int redundant = paths(byClass, "redundant");
        int opaque = paths(byClass, "opaque_payload");
        int gap = 0;
        for (String g : OccurrenceLedger.GAPS) {
            gap += paths(byClass, g);
        }
        return canonical + " / " + residual + " / " + structural + " / " + redundant + " / " + opaque + " / " + gap;
    }

    private static int paths(Map<String, int[]> byClass, String cls) {
        int[] n = byClass.get(cls);
        return n == null ? 0 : n[0];
    }

    // ---- controls ---------------------------------------------------------------------------------------------------------------

    /**
     * Every entry classified {@code redundant} is a repeat of a member normalized elsewhere; this check reads both members
     * from the native records and compares them, so the classification is evidenced, never a note: Claude's result text
     * repeats the last assistant text block (wire_tool_inputs turned out not to be an exact repeat and is vendor_residual); Antigravity's DONE
     * and ERROR steps repeat the ACTIVE step's tool name and parameters; Codex's completed MCP call repeats the started
     * call's server, tool and arguments; Claude's permission_denied record names the call the result's denial names.
     */
    @Test
    void redundantRepresentationsAreCheckedAgainstTheirOriginals() {
        int checked = 0;
        for (Source s : OccurrenceLedger.sources()) {
            if (s.synthetic() || !s.artifact().equals("stream.jsonl")) {
                continue;
            }
            NativeArtifact art = NativeArtifact.read(OccurrenceLedger.FIXTURES.resolve(s.fixture()).resolve("native").resolve(s.artifact()));
            List<Json> records = art.records().stream().filter(NativeArtifact.Record::parsed).map(NativeArtifact.Record::json).toList();
            switch (s.profile()) {
                case "claude_stream" -> checked += claudeRedundancies(s, records);
                case "agy_stream" -> checked += antigravityRedundancies(s, records);
                case "codex_stream" -> checked += codexRedundancies(s, records);
                default -> {
                }
            }
        }
        assertThat(checked).as("redundancy comparisons executed over the registered recordings").isGreaterThan(20);
    }

    static int claudeRedundancies(Source s, List<Json> records) {
        int checked = 0;
        String lastText = null;
        Map<String, Json> inputs = new LinkedHashMap<>();
        for (Json r : records) {
            String type = SourceProfile.text(r, "type");
            if ("assistant".equals(type) && r.member("parent_tool_use_id") != null && r.member("parent_tool_use_id").isNull()) {
                for (Json b : r.path("message", "content").elements()) {
                    if ("text".equals(SourceProfile.text(b, "type"))) {
                        lastText = SourceProfile.text(b, "text");
                    }
                    if ("tool_use".equals(SourceProfile.text(b, "type"))) {
                        inputs.put(SourceProfile.text(b, "id"), b.member("input"));
                    }
                }
                if (r.member("wire_tool_inputs") instanceof Json.JObject wire) {
                    for (Map.Entry<String, Json> w : wire.members().entrySet()) {
                        // executed finding (ACCT-1): the wire form is not an exact repeat; on the denial recording it omits the Edit block's
                        // replace_all member, so the catalog classifies it vendor_residual, and what holds is that every wire member equals the block's
                        Json input = inputs.get(w.getKey());
                        assertThat(input).as(s.id() + " wire_tool_inputs " + w.getKey() + " names a tool_use block").isNotNull();
                        for (Map.Entry<String, Json> m : ((Json.JObject) w.getValue()).members().entrySet()) {
                            assertThat(Json.sameValue(input.member(m.getKey()), m.getValue())).as(s.id() + " wire member " + m.getKey() + " equals the block's").isTrue();
                        }
                        checked++;
                    }
                }
            }
            if ("result".equals(type) && r.member("result") instanceof Json.JString text && lastText != null) {
                assertThat(text.value()).as(s.id() + " result.result repeats the last main-thread assistant text block").isEqualTo(lastText);
                checked++;
            }
            if ("system".equals(type) && "permission_denied".equals(SourceProfile.text(r, "subtype"))) {
                Json result = records.stream().filter(x -> "result".equals(SourceProfile.text(x, "type"))).findFirst().orElseThrow();
                assertThat(result.member("permission_denials").elements().stream().map(d -> SourceProfile.text(d, "tool_use_id") + "/" + SourceProfile.text(d, "tool_name")))
                        .as(s.id() + " the denied record names a call the result's denials name").contains(SourceProfile.text(r, "tool_use_id") + "/" + SourceProfile.text(r, "tool_name"));
                checked++;
            }
        }
        return checked;
    }

    static int antigravityRedundancies(Source s, List<Json> records) {
        int checked = 0;
        Map<String, Json> active = new LinkedHashMap<>();
        for (Json r : records) {
            Json step = r.member("step_update");
            if (step == null || !"tool".equals(SourceProfile.text(step, "step_type"))) {
                continue;
            }
            String index = step.member("step_index").scalarText();
            if ("ACTIVE".equals(SourceProfile.text(step, "state"))) {
                active.put(index, step);
                assertThat(SourceProfile.text(step, "tool_info", "name")).as(s.id() + " step " + index + " tool_info.name repeats tool_name").isEqualTo(SourceProfile.text(step, "tool_name"));
                checked++;
            }
            else {
                Json start = active.get(index);
                assertThat(start).as(s.id() + " step " + index + " has an ACTIVE start").isNotNull();
                assertThat(SourceProfile.text(step, "tool_name")).isEqualTo(SourceProfile.text(start, "tool_name"));
                assertThat(SourceProfile.text(step, "tool_info", "name")).isEqualTo(SourceProfile.text(start, "tool_info", "name"));
                assertThat(Json.sameValue(start.path("tool_info", "parameters"), step.path("tool_info", "parameters"))).as(s.id() + " step " + index + " parameters repeat the start's").isTrue();
                checked++;
            }
        }
        return checked;
    }

    static int codexRedundancies(Source s, List<Json> records) {
        int checked = 0;
        Map<String, Json> started = new LinkedHashMap<>();
        for (Json r : records) {
            Json item = r.member("item");
            if (item == null || !"mcp_tool_call".equals(SourceProfile.text(item, "type"))) {
                continue;
            }
            if ("item.started".equals(SourceProfile.text(r, "type"))) {
                started.put(SourceProfile.text(item, "id"), item);
            }
            else if ("item.completed".equals(SourceProfile.text(r, "type"))) {
                Json start = started.get(SourceProfile.text(item, "id"));
                assertThat(start).as(s.id() + " completed MCP call " + SourceProfile.text(item, "id") + " has a start").isNotNull();
                assertThat(SourceProfile.text(item, "server")).isEqualTo(SourceProfile.text(start, "server"));
                assertThat(SourceProfile.text(item, "tool")).isEqualTo(SourceProfile.text(start, "tool"));
                assertThat(Json.sameValue(start.member("arguments"), item.member("arguments"))).as(s.id() + " completed arguments repeat the start's").isTrue();
                checked++;
            }
        }
        return checked;
    }

    /**
     * The mutations the handoff names, each a visible delta of the pinned snapshot: a normalized member flipped to retained
     * (classification population and catalog hash change), a second equal-valued occurrence dropped from a recording
     * (occurrence count and source hash change), an invented assertion (a new unjudged path), and a replayed copy of a
     * source (one distinct recording, not two).
     */
    @Test
    void eachNamedMutationIsAVisibleDeltaOfTheSnapshot(@TempDir Path work) throws IOException {
        Result base = generate();
        Set<String> pinned = new TreeSet<>(base.snapshot());
        // normalize -> retain: the per-model cost entry reclassified
        Map<String, FieldDispositionTest.Catalog> catalogs = new TreeMap<>(base.catalogs());
        FieldDispositionTest.Catalog claude = catalogs.get("claude_stream");
        List<FieldDispositionTest.Entry> flipped = new ArrayList<>();
        for (FieldDispositionTest.Entry e : claude.entries()) {
            flipped.add(e.key().equals("result:modelUsage.{*}.costUSD") ? new FieldDispositionTest.Entry(e.variant(), e.path(), e.subtree(), "retained", List.of(), null,
                    "whole_record.coverage", e.tests(), e.note(), e.raw()) : e);
        }
        catalogs.put("claude_stream", FieldDispositionTest.withEntries(claude, flipped));
        Result retained = generate(OccurrenceLedger.sources(), catalogs, base.families(), base.coverage());
        assertThat(delta(minus(retained.snapshot(), pinned), minus(pinned, new TreeSet<>(retained.snapshot())))).isNotEmpty()
                .anyMatch(l -> l.startsWith("+ source claude-live-2.1.304-features/stream.jsonl economics retained_unclassified paths 1"))
                .anyMatch(l -> l.startsWith("- source claude-live-2.1.304-features/stream.jsonl economics canonical"));
        // one of two equal-valued occurrences dropped: the mcp recording's second echo_record result (records 11 and 13 report equal structured results)
        Path copy = OcsfFixtures.copy(OcsfFixtures.LIVE_MCP, work);
        Path stream = copy.resolve("native/stream.jsonl");
        List<String> lines = new ArrayList<>(Files.readAllLines(stream, StandardCharsets.UTF_8));
        int dropped = lines.size();
        lines.remove(12);
        Files.write(stream, lines, StandardCharsets.UTF_8);
        Source original = base.ledgers().stream().map(Ledger::source).filter(x -> x.fixture().equals(OcsfFixtures.LIVE_MCP)).findFirst().orElseThrow();
        Source edited = new Source(original.fixture(), original.artifact(), original.profile(), original.route(), NativeArtifact.read(stream).sha256(), dropped - 1,
                original.version(), original.entry(), original.cli(), original.admission(), false, null);
        Ledger before = base.ledgers().stream().filter(l -> l.source().fixture().equals(OcsfFixtures.LIVE_MCP)).findFirst().orElseThrow();
        Ledger after = OccurrenceLedger.ledger(edited, work, base.catalogs(), base.families(), OccurrenceLedger.ownerPath("claude_stream"));
        assertThat(after.rows().size()).isLessThan(before.rows().size());
        assertThat(after.source().sha256()).isNotEqualTo(before.source().sha256());
        // an invented assertion: a member no oracle property judges appears on a canonical event
        Coverage coverage = base.coverage();
        Map<String, String> profiles = Map.of("stream.jsonl", "claude_stream");
        OcsfPackage pkg = PackageReader.open(OccurrenceLedger.FIXTURES.resolve(OcsfFixtures.LIVE_FEATURES).resolve("package").resolve("events.jsonl"));
        Map<String, Map<String, Map<String, Integer>>> census = OccurrenceLedger.census(OccurrenceLedger.assertions(OcsfFixtures.LIVE_FEATURES, pkg, profiles), coverage);
        assertThat(census.get("prompt_turn").getOrDefault("unjudged", Map.of())).doesNotContainKey("claude_stream|prompt_turn/2:invented_total");
        Event turn = pkg.events("prompt_turn", 2).get(0);
        Event invented = new Event(turn.line, new Json.JObject(CanonicalConformanceTest.put(new LinkedHashMap<>(((Json.JObject) turn.json).members()), "invented_total", new Json.JNumber("1"))), turn.className);
        List<Assertion> withInvented = new ArrayList<>(OccurrenceLedger.assertions(OcsfFixtures.LIVE_FEATURES, pkg, profiles));
        withInvented.add(new Assertion("claude_stream|prompt_turn/2:invented_total", invented.assemblyRole(), OcsfFixtures.LIVE_FEATURES, "main", invented.line));
        assertThat(OccurrenceLedger.census(withInvented, coverage).get("prompt_turn").get("unjudged")).containsKey("claude_stream|prompt_turn/2:invented_total");
        // a replayed copy: mcp-pair's stream is byte-identical to mcp's, so the two sources are one distinct recording
        Map<String, Set<String>> distinct = new TreeMap<>();
        base.ledgers().stream().map(Ledger::source).filter(x -> x.profile().equals("claude_stream") && !x.synthetic()).forEach(x -> distinct.computeIfAbsent(x.sha256(), k -> new TreeSet<>()).add(x.fixture()));
        assertThat(distinct.values()).anySatisfy(fixtures -> assertThat(fixtures).containsExactly(OcsfFixtures.LIVE_MCP, "claude-live-2.1.304-mcp-pair"));
        assertThat(pinned).contains("source claude-live-2.1.304-mcp-pair/stream.jsonl sha256 " + original.sha256() + " profile claude_stream entry " + original.entry());
    }

    static Set<String> minus(java.util.Collection<String> a, Set<String> b) {
        Set<String> out = new TreeSet<>(a);
        out.removeAll(b);
        return out;
    }

    /** Every registered source has a family for each of its occurrences, and every admitted profile's catalog classifies every exercised retained entry. */
    @Test
    void admittedProfilesHaveNoUnassignedFamilyAndNoUnclassifiedRetainedEntry() {
        Result r = generate();
        for (Ledger l : r.ledgers()) {
            assertThat(l.rows().stream().filter(c -> "unassigned".equals(c.family())).map(c -> c.occurrence().key()).distinct().toList()).as(l.source().id() + " unassigned family").isEmpty();
            if ("admitted".equals(l.source().admission()) && !l.source().synthetic()) {
                assertThat(l.rows().stream().filter(c -> "retained_unclassified".equals(c.classification())).map(c -> c.occurrence().key()).distinct().toList())
                        .as(l.source().id() + " exercised retained entries without a reviewed classification").isEmpty();
                assertThat(l.rows().stream().filter(c -> "unclassified".equals(c.classification()) || "ambiguous".equals(c.classification())).map(c -> c.occurrence().key()).distinct().toList())
                        .as(l.source().id() + " occurrences without exactly one disposition").isEmpty();
            }
        }
    }


    /** Every observed variant:path per profile, for authoring family rules (written to target/accounting/paths-<profile>.txt). */
    @Test
    void observedPathsPerProfileAreListed() throws IOException {
        Files.createDirectories(OUT);
        Map<String, Set<String>> byProfile = new TreeMap<>();
        for (Ledger l : generate().ledgers()) {
            for (Classified c : l.rows()) {
                if (!c.occurrence().opaque()) {
                    byProfile.computeIfAbsent(l.source().profile(), k -> new TreeSet<>()).add(c.occurrence().key() + "\t" + c.classification() + "\t" + c.family());
                }
            }
        }
        for (Map.Entry<String, Set<String>> p : byProfile.entrySet()) {
            Files.writeString(OUT.resolve("paths-" + p.getKey() + ".txt"), String.join("\n", p.getValue()) + "\n", StandardCharsets.UTF_8);
        }
        assertThat(byProfile).isNotEmpty();
    }
}
