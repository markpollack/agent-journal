package io.github.markpollack.journal.tck.ocsf;

import io.github.markpollack.journal.tck.ocsf.inventory.RawInventory;
import io.github.markpollack.journal.tck.verify.Json;
import io.github.markpollack.journal.tck.verify.NeutralJson;

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
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * The occurrence ledger of the accounting procedure (ACCT-1): every concrete native occurrence of every registered
 * recorded source, classified by the exact source catalog, grouped by execution family, and the typed canonical
 * assertions of every package, each judged, journal-origin, caller-origin or inventoried as unjudged.
 *
 * <p>Step 1 enumerates occurrences from the native bytes alone: each parsed record is walked whole (containers and
 * leaves), keeping the record index, the concrete RFC 6901 pointer (array indexes and map keys as written) beside the
 * catalog's templated path ({@code a[].b}, {@code m.{*}.c}), the record's variant under the profile's rules and the
 * owner core the record routes to. An opaque line is one occurrence. Members beneath a data-keyed boundary (tool
 * arguments, tool-defined results, user data) are counted and marked {@code opaque_payload}: their keys are not the
 * vendor's, so they are never classified by name.
 *
 * <p>Step 2 classifies each occurrence by the one applicable disposition entry of the profile's catalog
 * ({@link FieldDispositionTest#applicable}): a normalized entry is {@code canonical}; a retained entry carries its
 * reviewed {@code classification} ({@code vendor_residual}, {@code structural}, {@code redundant},
 * {@code common_capacity_gap}, {@code candidate}) or is {@code retained_unclassified}; an unresolved entry is
 * {@code unresolved}; no entry is {@code unclassified}, two are {@code ambiguous}; a profile without a catalog leaves
 * every occurrence {@code no_catalog}. Naming heuristics play no part: the catalog is reviewed source data.
 *
 * <p>Families come from {@code schema/vendor/families.json}, ordered first-match rules over {@code variant:path};
 * an occurrence no rule names is {@code unassigned}, a visible gap of the rule table, never guessed.
 *
 * <p>The assertion census (step 4) walks every event of every package core and classifies each attribute path by
 * {@code ocsf/conformance/assertion-coverage.json}: judged by a named oracle property, journal origin, caller origin
 * (declarations), or unjudged. A basis string is text the writer wrote; it is inventoried, never counted as judged.
 */
final class OccurrenceLedger {

    static final Path PROJECT = FieldDispositionTest.PROJECT;
    static final Path FIXTURES = CanonicalObligations.FIXTURES;
    static final Path REGISTRY = CanonicalObligations.REGISTRY;
    static final Path PROFILES = CanonicalObligations.PROFILES;
    static final Path FAMILIES = PROJECT.resolve("schema/vendor/families.json");
    static final Path ASSERTIONS = PROJECT.resolve("journal-capture-tck/src/test/resources/ocsf/conformance/assertion-coverage.json");

    /** The classification vocabulary, in reporting order. */
    static final List<String> CLASSIFICATIONS = List.of("canonical", "vendor_residual", "structural", "redundant", "opaque_payload", "common_capacity_gap",
            "candidate", "unresolved", "retained_unclassified", "unclassified", "ambiguous", "no_catalog");

    /** Classifications that are exact gaps of the accounting (reported by id, never summed away). */
    static final Set<String> GAPS = Set.of("common_capacity_gap", "candidate", "unresolved", "retained_unclassified", "unclassified", "ambiguous", "no_catalog");

    private OccurrenceLedger() {
    }

    // ---- registered sources -------------------------------------------------------------------------------------------

    /**
     * One registered native source: an artifact of a public fixture under its frozen expected-source manifest, with the
     * compatibility entry the registry selects for its profile, route and version (or {@code null} with the reason).
     */
    record Source(String fixture, String artifact, String profile, String route, String sha256, int records, String version, String entry, String cli,
            String admission, boolean synthetic, String unselected) {

        String id() {
            return fixture + "/" + artifact;
        }

        boolean document() {
            return RawInventory.DOCUMENT_PROFILES.contains(profile);
        }
    }

    /** Every artifact of every public fixture that has a frozen manifest, in fixture order; a synthetic fixture is labelled, not dropped. */
    static List<Source> sources() {
        Json registry = NeutralJson.readDocument(REGISTRY);
        List<Json> profiles = CanonicalObligations.profiles();
        List<Source> out = new ArrayList<>();
        try (Stream<Path> dirs = Files.list(FIXTURES)) {
            for (Path dir : dirs.sorted().toList()) {
                Path specFile = dir.resolve("spec.json");
                Path manifest = dir.resolve("native").resolve("expected-sources.json");
                if (!Files.isRegularFile(specFile) || !Files.isRegularFile(manifest)) {
                    continue;
                }
                Json spec = NeutralJson.readDocument(specFile);
                String label = SourceProfile.text(spec.member("synthetic"), "label");
                boolean synthetic = label != null && label.startsWith("LABELLED SYNTHETIC");
                String cli = SourceProfile.text(spec, "cli", "name");
                String declaredVersion = SourceProfile.text(spec, "cli", "version");
                for (Json s : NeutralJson.readDocument(manifest).member("sources").elements()) {
                    String artifact = SourceProfile.text(s, "artifact");
                    String profile = SourceProfile.text(s, "profile");
                    String route = route(spec, profiles, artifact, profile);
                    Path file = dir.resolve("native").resolve(artifact);
                    NativeArtifact art = RawInventory.DOCUMENT_PROFILES.contains(profile) ? NativeArtifact.document(file) : NativeArtifact.read(file);
                    String version = declaredVersion != null ? declaredVersion : reported(profile, art);
                    String[] selected = select(registry, profile, route, version);
                    out.add(new Source(dir.getFileName().toString(), artifact, profile, route, art.sha256(), art.records().size(), version, selected[0], cli,
                            selected[1], synthetic, selected[2]));
                }
            }
        }
        catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
        return out;
    }

    /** The route the fixture spec declares for the artifact, else the one the final-assembly profile naming that artifact and profile declares. */
    static String route(Json spec, List<Json> profiles, String artifact, String profile) {
        for (Json s : spec.member("sources") == null ? List.<Json>of() : spec.member("sources").elements()) {
            if (artifact.equals(SourceProfile.text(s, "artifact")) && SourceProfile.text(s, "route") != null) {
                return SourceProfile.text(s, "route");
            }
        }
        for (Json p : profiles) {
            for (Json s : p.member("sources").elements()) {
                if (artifact.equals(SourceProfile.text(s, "artifact")) && Objects.equals(profile, SourceProfile.text(s, "profile"))) {
                    return SourceProfile.text(s, "route");
                }
            }
        }
        return null;
    }

    /** The version the source itself reports, read by the profile's version bootstrap (the registry's rule), else {@code null}. */
    static String reported(String profile, NativeArtifact art) {
        switch (profile == null ? "" : profile) {
            case "claude_stream" -> {
                return CanonicalObligations.reportedVersion(art);
            }
            case "claude_transcript" -> {
                for (NativeArtifact.Record r : art.records()) {
                    if (r.parsed() && SourceProfile.text(r.json(), "version") != null) {
                        return SourceProfile.text(r.json(), "version");
                    }
                }
                return null;
            }
            case "copilot_events" -> {
                for (NativeArtifact.Record r : art.records()) {
                    if (r.parsed() && "session.start".equals(SourceProfile.text(r.json(), "type"))) {
                        return SourceProfile.text(r.json(), "data", "copilotVersion");
                    }
                }
                return null;
            }
            default -> {
                return null;
            }
        }
    }

    /** {entry id, admission, reason when none}: exactly one registry entry whose profile, route and version range match. */
    static String[] select(Json registry, String profile, String route, String version) {
        List<Json> matches = new ArrayList<>();
        for (Json e : registry.member("entries").elements()) {
            if (Objects.equals(profile, SourceProfile.text(e, "profile")) && CanonicalObligations.texts(e.member("routes")).contains(route)
                    && CanonicalObligations.inRange(version, e)) {
                matches.add(e);
            }
        }
        if (matches.size() == 1) {
            return new String[] {SourceProfile.text(matches.get(0), "id"), SourceProfile.text(matches.get(0), "admission"), null};
        }
        return new String[] {null, null, matches.isEmpty() ? "no compatibility entry for profile " + profile + " route " + route + " version " + version
                : matches.size() + " compatibility entries match"};
    }

    // ---- step 1: concrete occurrences -----------------------------------------------------------------------------------

    /**
     * One concrete native occurrence.
     *
     * @param path the catalog's templated path ({@code []} per array level, {@code {*}} per data- or map-keyed level), or
     *        {@code (opaque record)} for a line that is not one JSON object
     * @param pointer the exact RFC 6901 pointer with concrete indexes and keys
     * @param kind object, array, string, integer, decimal, boolean, null or opaque
     * @param opaque whether the occurrence lies beneath a data-keyed boundary (its key belongs to a tool or a user)
     */
    record Occurrence(String variant, int record, String pointer, String path, String kind, String owner, boolean opaque) {

        String key() {
            return variant + ":" + path;
        }
    }

    /** Every occurrence of an artifact under a profile's rules, with the owner each record routes to under {@code ownerPath} ({@code main} without one). */
    static List<Occurrence> occurrences(NativeArtifact art, String profile, String ownerPath) {
        RawInventory.ProfileRules rules = RawInventory.ProfileRules.forProfile(profile);
        List<Occurrence> out = new ArrayList<>();
        for (NativeArtifact.Record r : art.records()) {
            if (!r.parsed()) {
                out.add(new Occurrence("(opaque:" + r.status() + ")", r.index(), "", "(opaque record)", "opaque", "main", false));
                continue;
            }
            Json o = r.json();
            Json ownerValue = ownerPath == null ? null : o.member(ownerPath);
            String owner = ownerValue == null || ownerValue.isNull() ? "main" : "child:" + ownerValue.scalarText();
            walk(o, "", "", rules.variant(o), r.index(), owner, rules, false, out);
        }
        return out;
    }

    private static void walk(Json value, String path, String pointer, String variant, int record, String owner, RawInventory.ProfileRules rules, boolean opaque,
            List<Occurrence> out) {
        if (!path.isEmpty()) {
            out.add(new Occurrence(variant, record, pointer, path, RawInventory.kind(value), owner, opaque));
        }
        if (value instanceof Json.JObject o) {
            boolean data = rules.dataKeyed().contains(path);
            boolean map = rules.mapKeyed().contains(path);
            for (Map.Entry<String, Json> m : o.members().entrySet()) {
                String child = data || map ? path + ".{*}" : path.isEmpty() ? m.getKey() : path + "." + m.getKey();
                walk(m.getValue(), child, pointer + "/" + m.getKey().replace("~", "~0").replace("/", "~1"), variant, record, owner, rules, opaque || data, out);
            }
        }
        else if (value instanceof Json.JArray a) {
            boolean data = rules.dataKeyed().contains(path);
            for (int i = 0; i < a.elements().size(); i++) {
                walk(a.elements().get(i), path + "[]", pointer + "/" + i, variant, record, owner, rules, opaque || data, out);
            }
        }
    }

    // ---- step 2: classification by the exact source catalog ---------------------------------------------------------------

    record Classified(Occurrence occurrence, String entryKey, String disposition, String classification, String family) {
    }

    /** The reviewed classification of a disposition entry: normalized is canonical; retained carries its member; unresolved is unresolved. */
    static String classification(FieldDispositionTest.Entry e) {
        return switch (e.disposition()) {
            case "normalized" -> "canonical";
            case "unresolved" -> "unresolved";
            case "omitted" -> "redundant";
            default -> {
                String c = e.raw() == null ? null : SourceProfile.text(e.raw(), "classification");
                yield c == null ? "retained_unclassified" : c;
            }
        };
    }

    /**
     * The exact native occurrences the profile's obligation catalog selects in this artifact, by record and pointer:
     * members beneath a data-keyed boundary that the pinned source contract establishes as the CLI's own (an Agent
     * result's usage inside tool_use_result) are carved out of the opaque payload and accounted as canonical under
     * their obligation. Record index -> pointer -> obligation id.
     */
    static Map<Integer, Map<String, String>> carveOuts(String profile, String fixture, NativeArtifact art) {
        Map<Integer, Map<String, String>> out = new TreeMap<>();
        try (Stream<Path> files = Files.walk(PROJECT.resolve("schema/vendor"))) {
            for (Path p : files.filter(x -> x.getFileName().toString().equals("obligations.json")).toList()) {
                CanonicalObligations.Catalog c = CanonicalObligations.read(p);
                if (!profile.equals(c.profile()) || c.bindings().isEmpty()) {
                    continue;
                }
                CanonicalObligations.Binding b = c.bindings().stream().filter(x -> x.recordings().contains(fixture)).findFirst()
                        .orElse(new CanonicalObligations.Binding(c.bindings().get(0).entry(), List.of(fixture)));
                for (CanonicalObligations.Occurrence o : CanonicalObligations.occurrences(c, b, fixture, art)) {
                    if (o.unresolved() == null) {
                        out.computeIfAbsent(o.record(), k -> new LinkedHashMap<>()).put(o.pointer(), o.obligation().id());
                    }
                }
            }
        }
        catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
        return out;
    }

    static List<Classified> classify(List<Occurrence> occurrences, FieldDispositionTest.Catalog catalog, Families families, String profile,
            Map<Integer, Map<String, String>> carveOuts) {
        List<Classified> out = new ArrayList<>();
        Map<String, List<FieldDispositionTest.Entry>> cache = new LinkedHashMap<>();
        for (Occurrence o : occurrences) {
            String family = families.family(profile, o.key());
            if (o.kind().equals("opaque")) {
                out.add(new Classified(o, null, catalog == null ? "no_catalog" : "retained", catalog == null ? "no_catalog" : "structural", family));
                continue;
            }
            if (o.opaque()) {
                String obligation = null;
                for (Map.Entry<String, String> c : carveOuts.getOrDefault(o.record(), Map.of()).entrySet()) {
                    if (o.pointer().equals(c.getKey()) || o.pointer().startsWith(c.getKey() + "/")) {
                        obligation = c.getValue();
                    }
                }
                out.add(obligation != null ? new Classified(o, "obligation:" + obligation, "normalized", "canonical", families.family(profile, "obligation:" + obligation))
                        : new Classified(o, null, "opaque", "opaque_payload", family));
                continue;
            }
            if (catalog == null) {
                out.add(new Classified(o, null, "no_catalog", "no_catalog", family));
                continue;
            }
            List<FieldDispositionTest.Entry> applicable = cache.computeIfAbsent(o.key(), k -> FieldDispositionTest.applicable(catalog, o.variant(), o.path()));
            if (applicable.isEmpty()) {
                out.add(new Classified(o, null, "none", "unclassified", family));
            }
            else if (applicable.size() > 1) {
                out.add(new Classified(o, applicable.stream().map(FieldDispositionTest.Entry::key).toList().toString(), "several", "ambiguous", family));
            }
            else {
                FieldDispositionTest.Entry e = applicable.get(0);
                out.add(new Classified(o, e.key(), e.disposition(), classification(e), family));
            }
        }
        return out;
    }

    /** The ordered family rules per profile, from {@code schema/vendor/families.json}. */
    record Families(List<String> names, Map<String, List<Map.Entry<Pattern, String>>> rules, String sha256) {

        static Families read() {
            Json doc = NeutralJson.readDocument(FAMILIES);
            Map<String, List<Map.Entry<Pattern, String>>> rules = new LinkedHashMap<>();
            ((Json.JObject) doc.member("profiles")).members().forEach((profile, list) -> {
                List<Map.Entry<Pattern, String>> r = new ArrayList<>();
                for (Json rule : list.elements()) {
                    r.add(Map.entry(Pattern.compile(SourceProfile.text(rule, "match")), SourceProfile.text(rule, "family")));
                }
                rules.put(profile, r);
            });
            return new Families(CanonicalObligations.texts(doc.member("families")), rules, NativeArtifact.sha256(CanonicalConformanceTest.read(FAMILIES)));
        }

        String family(String profile, String variantPath) {
            for (Map.Entry<Pattern, String> r : rules.getOrDefault(profile, List.of())) {
                if (r.getKey().matcher(variantPath).matches()) {
                    return r.getValue();
                }
            }
            return "unassigned";
        }
    }

    // ---- the per-source ledger ---------------------------------------------------------------------------------------------

    /** The ledger of one source: its classified occurrences and the exact gap ids. */
    record Ledger(Source source, List<Classified> rows, FieldDispositionTest.Catalog catalog) {

        /** Distinct templated paths (variant:path) by classification, each with its occurrence count. */
        Map<String, Map<String, Integer>> pathsByClassification() {
            Map<String, Map<String, Integer>> out = new TreeMap<>();
            for (Classified c : rows) {
                out.computeIfAbsent(c.classification(), k -> new TreeMap<>()).merge(c.occurrence().key(), 1, Integer::sum);
            }
            return out;
        }

        /** family -> classification -> {paths, occurrences}. */
        Map<String, Map<String, int[]>> matrix() {
            Map<String, Map<String, Set<String>>> paths = new TreeMap<>();
            Map<String, Map<String, Integer>> occurrences = new TreeMap<>();
            for (Classified c : rows) {
                paths.computeIfAbsent(c.family(), k -> new TreeMap<>()).computeIfAbsent(c.classification(), k -> new TreeSet<>()).add(c.occurrence().key());
                occurrences.computeIfAbsent(c.family(), k -> new TreeMap<>()).merge(c.classification(), 1, Integer::sum);
            }
            Map<String, Map<String, int[]>> out = new TreeMap<>();
            paths.forEach((family, byClass) -> byClass.forEach((cls, ps) -> out.computeIfAbsent(family, k -> new TreeMap<>()).put(cls, new int[] {ps.size(), occurrences.get(family).get(cls)})));
            return out;
        }

        /** Exact gap ids: {@code <classification> <variant:path>} with the count of occurrences and the first pointer, sorted. */
        List<String> gaps() {
            Map<String, int[]> counts = new TreeMap<>();
            Map<String, String> first = new TreeMap<>();
            for (Classified c : rows) {
                if (GAPS.contains(c.classification()) || "unassigned".equals(c.family())) {
                    String id = (GAPS.contains(c.classification()) ? c.classification() : "unassigned-family") + " " + c.occurrence().key();
                    counts.computeIfAbsent(id, k -> new int[1])[0]++;
                    first.putIfAbsent(id, "record " + c.occurrence().record() + " " + c.occurrence().pointer());
                }
            }
            List<String> out = new ArrayList<>();
            counts.forEach((id, n) -> out.add(id + " (" + n[0] + " occurrence" + (n[0] == 1 ? "" : "s") + ", first at " + first.get(id) + ")"));
            return out;
        }

        /** Catalog entries no occurrence of this source exercises, by disposition: declared-only population (unexercised, never silently dropped). */
        Map<String, Integer> unexercisedEntries() {
            Map<String, Integer> out = new TreeMap<>();
            if (catalog == null) {
                return out;
            }
            Set<String> used = new LinkedHashSet<>();
            rows.forEach(c -> {
                if (c.entryKey() != null) {
                    used.add(c.entryKey());
                }
            });
            for (FieldDispositionTest.Entry e : catalog.entries()) {
                if (!used.contains(e.key())) {
                    out.merge(e.disposition(), 1, Integer::sum);
                }
            }
            return out;
        }
    }

    static Ledger ledger(Source s, Map<String, FieldDispositionTest.Catalog> catalogs, Families families, String ownerPath) {
        return ledger(s, FIXTURES, catalogs, families, ownerPath);
    }

    /** The ledger of a source read from {@code fixtures/<fixture>/native/<artifact>} under another fixture root (a mutated working copy). */
    static Ledger ledger(Source s, Path fixtures, Map<String, FieldDispositionTest.Catalog> catalogs, Families families, String ownerPath) {
        Path file = fixtures.resolve(s.fixture()).resolve("native").resolve(s.artifact());
        NativeArtifact art = s.document() ? NativeArtifact.document(file) : NativeArtifact.read(file);
        FieldDispositionTest.Catalog catalog = catalogs.get(s.profile());
        return new Ledger(s, classify(occurrences(art, s.profile(), ownerPath), catalog, families, s.profile(), carveOuts(s.profile(), s.fixture(), art)), catalog);
    }

    /** The owner routing of a profile: its obligation catalog's owner path, else none. */
    static String ownerPath(String profile) {
        try (Stream<Path> files = Files.walk(PROJECT.resolve("schema/vendor"))) {
            for (Path p : files.filter(x -> x.getFileName().toString().equals("obligations.json")).toList()) {
                Json raw = NeutralJson.readDocument(p);
                if (profile.equals(SourceProfile.text(raw, "profile"))) {
                    return SourceProfile.text(raw, "owner_path");
                }
            }
        }
        catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
        return null;
    }

    /** The disposition catalogs by profile (every {@code dispositions.json} under schema/vendor, whether or not the gate registers it). */
    static Map<String, FieldDispositionTest.Catalog> catalogs() {
        Map<String, FieldDispositionTest.Catalog> out = new TreeMap<>();
        FieldDispositionTest.CATALOGS.keySet().forEach(p -> out.put(p, FieldDispositionTest.catalog(p)));
        return out;
    }

    // ---- step 4: the assertion census of the packages ------------------------------------------------------------------------

    /** The coverage table: regexes over {@code <class>/<activity>:<attribute path>} for journal origin, caller origin and judged attributes. */
    record Coverage(List<Pattern> journal, List<Pattern> caller, List<Pattern> basis, List<Map.Entry<Pattern, String>> judged, Set<String> properties, String sha256) {

        static Coverage read() {
            Json doc = NeutralJson.readDocument(ASSERTIONS);
            List<Map.Entry<Pattern, String>> judged = new ArrayList<>();
            Set<String> properties = new TreeSet<>();
            for (Json j : doc.member("judged").elements()) {
                judged.add(Map.entry(Pattern.compile(SourceProfile.text(j, "match")), SourceProfile.text(j, "property")));
                properties.add(SourceProfile.text(j, "property"));
            }
            return new Coverage(patterns(doc.member("journal_origin")), patterns(doc.member("caller_origin")), patterns(doc.member("basis_text")), judged, properties,
                    NativeArtifact.sha256(CanonicalConformanceTest.read(ASSERTIONS)));
        }

        private static List<Pattern> patterns(Json array) {
            List<Pattern> out = new ArrayList<>();
            for (String s : CanonicalObligations.texts(array)) {
                out.add(Pattern.compile(s));
            }
            return out;
        }

        /** journal, caller, basis_text, judged:<property> or unjudged. */
        String origin(String key) {
            for (Pattern p : basis) {
                if (p.matcher(key).matches()) {
                    return "basis_text";
                }
            }
            for (Pattern p : journal) {
                if (p.matcher(key).matches()) {
                    return "journal";
                }
            }
            for (Pattern p : caller) {
                if (p.matcher(key).matches()) {
                    return "caller";
                }
            }
            for (Map.Entry<Pattern, String> j : judged) {
                if (j.getKey().matcher(key).matches()) {
                    return "judged:" + j.getValue();
                }
            }
            return "unjudged";
        }
    }

    /**
     * Members (by their last path segment) that hold retained native bytes, application payloads or free text: inventoried
     * as one assertion each, never by their inner keys, which are the vendor's, a tool's or a user's.
     */
    static final Set<String> PAYLOADS = Set.of("data", "raw_data", "raw_bytes_b64", "structured_result", "arguments", "argv", "conditional_value", "input_schema",
            "schema_document", "mcp_arguments", "mcp_structured_content", "value", "stdout", "stderr", "text", "tool_names", "native_pointers", "candidates", "observed_values");

    /** One typed assertion: {@code <profile>|<class>/<activity>:<path>} with the assembly role of its event. */
    record Assertion(String key, Integer role, String fixture, String core, int line) {
    }

    /** @param profiles the source profile of each native artifact of the fixture (the frozen manifest's); an event without one is {@code assembly} */
    static List<Assertion> assertions(String fixture, OcsfPackage pkg, Map<String, String> profiles) {
        List<Assertion> out = new ArrayList<>();
        for (OcsfPackage core : pkg.all()) {
            for (Event e : core.events) {
                if (e.className == null) {
                    continue;
                }
                String profile = e.artifact() == null ? "assembly" : profiles.getOrDefault(e.artifact(), "unknown");
                String prefix = profile + "|" + e.className + "/" + e.activityId + ":";
                attributes(e.json, "", prefix, e.assemblyRole(), fixture, core.label(), e.line, out);
            }
        }
        return out;
    }

    private static void attributes(Json value, String path, String prefix, Integer role, String fixture, String core, int line, List<Assertion> out) {
        if (!path.isEmpty()) {
            out.add(new Assertion(prefix + path, role, fixture, core, line));
        }
        String last = path.substring(path.lastIndexOf('.') + 1).replace("[]", "");
        if (PAYLOADS.contains(last)) {
            return;
        }
        if (value instanceof Json.JObject o) {
            for (Map.Entry<String, Json> m : o.members().entrySet()) {
                attributes(m.getValue(), path.isEmpty() ? m.getKey() : path + "." + m.getKey(), prefix, role, fixture, core, line, out);
            }
        }
        else if (value instanceof Json.JArray a) {
            for (Json el : a.elements()) {
                attributes(el, path + "[]", prefix, role, fixture, core, line, out);
            }
        }
    }

    /** Per class: origin -> distinct attribute paths with occurrence counts. */
    static Map<String, Map<String, Map<String, Integer>>> census(List<Assertion> assertions, Coverage coverage) {
        Map<String, Map<String, Map<String, Integer>>> out = new TreeMap<>();
        for (Assertion a : assertions) {
            String cls = a.key().substring(a.key().indexOf('|') + 1, a.key().indexOf('/'));
            String origin = coverage.origin(a.key());
            String bucket = origin.startsWith("judged:") ? "judged" : origin;
            out.computeIfAbsent(cls, k -> new TreeMap<>()).computeIfAbsent(bucket, k -> new TreeMap<>()).merge(a.key(), 1, Integer::sum);
        }
        return out;
    }

    // ---- support ----------------------------------------------------------------------------------------------------------------

    static Set<String> keys(Map<String, ?> m) {
        return new TreeSet<>(m.keySet());
    }
}
