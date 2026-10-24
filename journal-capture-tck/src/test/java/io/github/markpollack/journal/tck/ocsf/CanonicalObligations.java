package io.github.markpollack.journal.tck.ocsf;

import io.github.markpollack.journal.tck.ocsf.inventory.InventoryComparison;
import io.github.markpollack.journal.tck.ocsf.inventory.RawInventory;
import io.github.markpollack.journal.tck.ocsf.inventory.SourceSpecification;
import io.github.markpollack.journal.tck.verify.Check;
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
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * The canonical-obligation gate (agent-journal/canonical-obligations 0.1): required canonical outcomes as catalog data
 * ({@code schema/vendor/<cli>/<profile>/obligations.json}), their expected native occurrences counted from the pinned
 * recordings alone, and an executed judgement of each occurrence against the candidate package and the per-source
 * oracle. Findings fall into six separate categories:
 * <ol>
 * <li>an observed token-named vendor member that no obligation maps and no reviewed residual classifies, or that has no
 * disposition at all;</li>
 * <li>an ambiguous disposition (two exact entries, or an entry whose destination disagrees with the obligation);</li>
 * <li>an unmet required canonical outcome: no observation at the exact owner, record, scope, unit and model, or a
 * disposition that keeps the member retained-only or hides it inside a broad subtree;</li>
 * <li>a failed or missing check: a wrong value, status, aggregation or native data, a duplicate, an occurrence the
 * oracle's source profile does not expect, or an oracle property for that owner that is not VERIFIED;</li>
 * <li>a required source binding without its disposition catalog, oracle profile, disposition-gate registration or
 * obligation catalog, derived from the compatibility registry and the final-assembly profiles;</li>
 * <li>unreviewed drift of a registered recording against its binding's specification, an obligation without an
 * approval, a meaning changed without a new revision, or an unresolved residual.</li>
 * </ol>
 * Occurrences are identified by obligation and revision, recording, artifact hash, record index, JSON pointer and
 * owner; the comparison is by these identities, never by counts. Nothing here calls the writer or a mapper: expected
 * occurrences come from {@link NativeArtifact} records and the catalog, candidate values from the package as written.
 */
final class CanonicalObligations {

    /** Token-named keys, the vocabulary of {@code SourceAvailability} (its pattern is package-private there). */
    static final Pattern TOKEN_KEY = Pattern.compile("(?i)tokens?($|count|[A-Z_])");
    static final Path PROJECT = FieldDispositionTest.PROJECT;
    static final Path FIXTURES = PROJECT.resolve("journal-capture-tck/src/test/resources/ocsf/fixtures");
    static final Path REGISTRY = PROJECT.resolve("journal-ocsf-writer/src/main/resources/ocsf/compatibility.json");
    static final Path PROFILES = PROJECT.resolve("journal-ocsf-writer/src/main/resources/ocsf/profiles");
    static final Path DECISIONS = PROJECT.resolve("journal-capture-tck/src/test/resources/ocsf/mapping-catalog/owner-decisions.json");
    static final String ARTIFACT = "stream.jsonl";

    private CanonicalObligations() {
    }

    // ---- the catalog --------------------------------------------------------------------------------------------------

    record Obligation(String id, int revision, String variant, String payload, List<String> requestedTools, String unitPath,
            String destinationClass, Integer destinationActivity, String contextKind, String unitMember,
            int scopeId, Json status, Json aggregation, String model, Map<String, String> components, String tiers,
            String reviewStatus, String digest, Json raw) {

        String key() {
            return id + "@r" + revision;
        }

        /** The destination path of a canonical component, in the disposition catalog's notation. */
        String destination(String component) {
            return destinationClass + ".usage_observations[]." + component;
        }
    }

    record Residual(String variant, String path, String classification, String reason) {
    }

    record Binding(String entry, List<String> recordings) {
    }

    record Catalog(Path file, String profile, String ownerPath, Path dispositions, List<Binding> bindings, List<Obligation> obligations,
            List<Residual> residuals, Json raw) {
    }

    static Catalog read(Path file) {
        Json raw = NeutralJson.readDocument(file);
        return parse(file, raw);
    }

    static Catalog parse(Path file, Json raw) {
        if (!"agent-journal/canonical-obligations".equals(raw.member("kind").text())) {
            throw new IllegalArgumentException(file + " is not a canonical-obligations catalog");
        }
        List<Binding> bindings = new ArrayList<>();
        for (Json b : raw.member("bindings").elements()) {
            bindings.add(new Binding(b.member("compatibility_entry").text(), texts(b.member("recordings"))));
        }
        List<Obligation> obligations = new ArrayList<>();
        for (Json o : raw.member("obligations").elements()) {
            obligations.add(obligation(o));
        }
        List<Residual> residuals = new ArrayList<>();
        for (Json r : raw.member("residuals").elements()) {
            residuals.add(new Residual(r.member("variant").text(), r.member("path").text(), r.member("classification").text(), r.member("reason").text()));
        }
        return new Catalog(file, raw.member("profile").text(), raw.member("owner_path").text(), PROJECT.resolve(raw.member("disposition_catalog").text()),
                bindings, obligations, residuals, raw);
    }

    private static Obligation obligation(Json o) {
        Json trigger = o.member("trigger");
        Json dest = o.member("destination");
        Json obs = o.member("observation");
        Map<String, String> components = new LinkedHashMap<>();
        ((Json.JObject) obs.member("components")).members().forEach((k, v) -> components.put(k, v.text()));
        return new Obligation(o.member("id").text(), Event.integer(o.member("revision")), trigger.member("variant").text(), trigger.member("payload").text(),
                texts(trigger.member("requested_tool")), SourceProfile.text(trigger, "unit"), dest.member("class").text(), Event.integer(dest.member("activity_id")),
                SourceProfile.text(dest, "context_kind"), SourceProfile.text(dest, "unit_member"), Event.integer(obs.member("scope_id")), obs.member("usage_status"),
                obs.member("aggregation_id"), SourceProfile.text(obs, "model"), components, SourceProfile.text(obs, "tiers"),
                SourceProfile.text(o.member("review"), "status"), SourceProfile.text(o, "meaning_digest"), o);
    }

    static List<String> texts(Json array) {
        List<String> out = new ArrayList<>();
        for (Json e : array == null ? List.<Json>of() : array.elements()) {
            out.add(e.text());
        }
        return out;
    }

    /** sha256 over the canonical JSON of the obligation's normative content: {trigger, destination, observation}. */
    static String meaningDigest(Obligation o) {
        Map<String, Json> m = new TreeMap<>();
        m.put("destination", o.raw().member("destination"));
        m.put("observation", o.raw().member("observation"));
        m.put("trigger", o.raw().member("trigger"));
        return SemanticClaims.sha256(SemanticClaims.canonical(new Json.JObject(m)));
    }

    // ---- expected occurrences, from native records only --------------------------------------------------------------

    /**
     * One expected occurrence of an obligation. {@code unresolved} states why ownership or the unit is not established
     * (an explicit result, never a guessed identifier); such an occurrence can never be discharged.
     */
    record Occurrence(Obligation obligation, String entry, String fixture, String sha256, int record, String pointer, String owner, String unit,
            String model, Json payload, int status, String unresolved) {

        String id() {
            return obligation.key() + "|" + fixture + "|" + ARTIFACT + ":" + record + "|" + pointer + "|" + owner;
        }
    }

    /** Every occurrence of the catalog's obligations in one registered recording of a binding. */
    static List<Occurrence> occurrences(Catalog c, Binding b, String fixture, NativeArtifact art) {
        RawInventory.ProfileRules rules = RawInventory.ProfileRules.forProfile(c.profile());
        Map<String, String> requestedTool = requests(art);
        List<Occurrence> out = new ArrayList<>();
        for (NativeArtifact.Record r : art.records()) {
            if (!r.parsed()) {
                continue;
            }
            String variant = rules.variant(r.json());
            for (Obligation o : c.obligations()) {
                if (o.variant().equals(variant)) {
                    out.addAll(occurrences(c, o, b, fixture, art.sha256(), r, requestedTool));
                }
            }
        }
        return out;
    }

    private static List<Occurrence> occurrences(Catalog c, Obligation o, Binding b, String fixture, String sha, NativeArtifact.Record r, Map<String, String> requestedTool) {
        Json rec = r.json();
        Json ownerValue = rec.member(c.ownerPath());
        String owner = ownerValue == null || ownerValue.isNull() ? "main" : "child:" + ownerValue.scalarText();
        List<Occurrence> out = new ArrayList<>();
        String unit = null;
        String unresolved = null;
        if (!o.requestedTools().isEmpty()) {
            List<String> results = toolResultIds(rec);
            boolean any = results.stream().anyMatch(id -> o.requestedTools().contains(requestedTool.get(id)));
            if (!any) {
                return out; // not this obligation's trigger: the record answers another tool
            }
            if (results.size() != 1) {
                unresolved = "the record carries " + results.size() + " tool_result blocks and one " + o.payload() + ": its owner block is not established";
            }
            else {
                unit = results.get(0);
            }
        }
        else if (o.unitPath() != null) {
            Json u = rec.path(o.unitPath().split("\\."));
            unit = u == null || u.isNull() ? null : u.scalarText();
            if (unit == null) {
                unresolved = "no " + o.unitPath() + ": the child run is not identified";
            }
        }
        for (Map.Entry<String, Json> p : payloads(rec, o.payload()).entrySet()) {
            if (!(p.getValue() instanceof Json.JObject)) {
                continue;
            }
            String model = o.model() == null ? null : "{key}".equals(o.model()) ? key(p.getKey()) : SourceProfile.text(rec, o.model().split("\\."));
            out.add(new Occurrence(o, b.entry(), fixture, sha, r.index(), p.getKey(), owner, unit, model, p.getValue(), status(o, rec), unresolved));
        }
        return out;
    }

    /** Tool-use id to requested tool name, over every assistant record of the artifact (any owner). */
    static Map<String, String> requests(NativeArtifact art) {
        Map<String, String> out = new LinkedHashMap<>();
        for (NativeArtifact.Record r : art.records()) {
            if (r.parsed() && "assistant".equals(SourceProfile.text(r.json(), "type"))) {
                for (Json b : r.json().path("message", "content") == null ? List.<Json>of() : r.json().path("message", "content").elements()) {
                    if ("tool_use".equals(SourceProfile.text(b, "type"))) {
                        out.put(SourceProfile.text(b, "id"), SourceProfile.text(b, "name"));
                    }
                }
            }
        }
        return out;
    }

    static List<String> toolResultIds(Json rec) {
        List<String> out = new ArrayList<>();
        for (Json b : rec.path("message", "content") == null ? List.<Json>of() : rec.path("message", "content").elements()) {
            if ("tool_result".equals(SourceProfile.text(b, "type"))) {
                out.add(SourceProfile.text(b, "tool_use_id"));
            }
        }
        return out;
    }

    /** The payload objects a dotted path with an optional trailing {@code {*}} selects, by JSON pointer. */
    static Map<String, Json> payloads(Json rec, String path) {
        Map<String, Json> out = new LinkedHashMap<>();
        boolean each = path.endsWith(".{*}");
        String base = each ? path.substring(0, path.length() - 4) : path;
        Json v = rec.path(base.split("\\."));
        String pointer = "/" + base.replace(".", "/");
        if (v == null) {
            return out;
        }
        if (each) {
            if (v instanceof Json.JObject o) {
                o.members().forEach((k, x) -> out.put(pointer + "/" + k.replace("~", "~0").replace("/", "~1"), x));
            }
        }
        else {
            out.put(pointer, v);
        }
        return out;
    }

    private static String key(String pointer) {
        return pointer.substring(pointer.lastIndexOf('/') + 1).replace("~1", "/").replace("~0", "~");
    }

    private static int status(Obligation o, Json rec) {
        Json s = o.status();
        if (s.member("value") != null) {
            return Event.integer(s.member("value"));
        }
        Json at = rec.path(s.member("from").text().split("\\."));
        return at == null || at.isNull() ? Event.integer(s.member("null")) : Event.integer(s.member("otherwise"));
    }

    // ---- judgement against the candidate package and the oracle -------------------------------------------------------

    /** One gate finding: its category, a stable identity, and what failed. */
    record Finding(int category, String id, String detail) {
    }

    /** The result for one occurrence: discharged, or the reasons it is not. */
    record Judged(Occurrence occurrence, boolean discharged, List<Finding> findings) {
    }

    /**
     * Judges one occurrence against the candidate's per-owner events (each core's own observations of {@link #ARTIFACT}),
     * the oracle's report and the oracle's own source-profile expectation for that owner.
     */
    static Judged judge(Occurrence occ, Map<String, List<Event>> cores, OracleReport report, Map<String, ExpectedFacts> profileFacts) {
        List<Finding> findings = new ArrayList<>();
        Obligation o = occ.obligation();
        if (occ.unresolved() != null) {
            findings.add(new Finding(3, occ.id(), "ownership not established: " + occ.unresolved()));
            return new Judged(occ, false, findings);
        }
        List<Json> matches = matches(occ, cores.getOrDefault(occ.owner(), List.of()));
        if (matches.isEmpty()) {
            findings.add(new Finding(3, occ.id(), "no " + o.destinationClass() + " usage observation at scope " + o.scopeId() + unitText(occ) + " for record " + occ.record()
                    + " in core " + occ.owner() + ": " + whatIsThere(occ, cores)));
        }
        else if (matches.size() > 1) {
            findings.add(new Finding(4, occ.id(), matches.size() + " observations match one occurrence"));
        }
        else {
            for (String p : values(occ, matches.get(0))) {
                findings.add(new Finding(4, occ.id(), p));
            }
        }
        ExpectedFacts facts = profileFacts.get(occ.owner());
        boolean expected = facts != null && facts.usages.stream().anyMatch(u -> u.recordIndex() == occ.record() && u.scopeId() == o.scopeId()
                && (occ.model() == null || occ.model().equals(u.model())));
        if (!expected) {
            findings.add(new Finding(4, occ.id(), "the oracle's source profile does not expect this occurrence (owner " + occ.owner() + ")"));
        }
        for (String property : oracleProperties(o)) {
            String owner = occ.owner() + "/" + ARTIFACT;
            List<Check> checks = report.checks().stream().filter(ch -> ch.property().equals(property) && ch.owner().equals(owner)).toList();
            if (checks.isEmpty() || checks.stream().anyMatch(ch -> ch.outcome() != Check.Outcome.VERIFIED)) {
                findings.add(new Finding(4, occ.id(), "oracle " + property + " [" + owner + "] " + (checks.isEmpty() ? "not reported" : checks.stream().map(ch -> ch.outcome().toString()).toList())));
            }
        }
        return new Judged(occ, findings.isEmpty(), findings);
    }

    static List<String> oracleProperties(Obligation o) {
        List<String> out = new ArrayList<>(List.of("population.usage", "usage.values"));
        if ("session_context".equals(o.destinationClass())) {
            out.addAll(List.of("population.contexts", "context.values"));
        }
        return out;
    }

    private static String unitText(Occurrence occ) {
        return (occ.unit() == null ? "" : " for unit " + occ.unit()) + (occ.model() == null ? "" : " for model " + occ.model());
    }

    /** The observations of the destination class at this record, scope, unit and model in one core. */
    static List<Json> matches(Occurrence occ, List<Event> events) {
        Obligation o = occ.obligation();
        List<Json> out = new ArrayList<>();
        for (Event e : events) {
            if (!Objects.equals(e.recordIndex(), occ.record()) || !o.destinationClass().equals(e.className)
                    || o.destinationActivity() != null && !o.destinationActivity().equals(e.activityId)
                    || o.contextKind() != null && !o.contextKind().equals(e.text("context_kind"))
                    || o.unitMember() != null && !Objects.equals(occ.unit(), e.text(o.unitMember()))) {
                continue;
            }
            for (Json u : e.elements("usage_observations")) {
                Integer scope = Event.integer(u.member("scope_id"));
                Integer at = Event.integer(u.path("source_ref", "record_index"));
                if (Objects.equals(scope, o.scopeId()) && (at == null || at == occ.record())
                        && (occ.model() == null || occ.model().equals(SourceProfile.text(u.member("model_ref"), "name")))) {
                    out.add(u);
                }
            }
        }
        return out;
    }

    /** Why nothing matched: what the owner core holds for the record, and whether another core or unit has it. */
    private static String whatIsThere(Occurrence occ, Map<String, List<Event>> cores) {
        Obligation o = occ.obligation();
        List<String> here = new ArrayList<>();
        for (Event e : cores.getOrDefault(occ.owner(), List.of())) {
            if (Objects.equals(e.recordIndex(), occ.record())) {
                here.add(e.className + (e.activityId == null ? "" : "/" + e.activityId) + ("source_record".equals(e.className) ? " (retained only)" : ""));
            }
        }
        List<String> elsewhere = new ArrayList<>();
        for (Map.Entry<String, List<Event>> c : cores.entrySet()) {
            for (Event e : c.getValue()) {
                if (!Objects.equals(e.recordIndex(), occ.record()) || !o.destinationClass().equals(e.className)) {
                    continue;
                }
                String unit = o.unitMember() == null ? null : e.text(o.unitMember());
                boolean otherPlace = !c.getKey().equals(occ.owner()) || !Objects.equals(occ.unit(), unit);
                for (Json u : e.elements("usage_observations")) {
                    if (otherPlace && Objects.equals(Event.integer(u.member("scope_id")), o.scopeId())) {
                        elsewhere.add(c.getKey() + (o.unitMember() == null ? "" : " unit " + unit));
                    }
                }
            }
        }
        return (here.isEmpty() ? "no event for the record" : "the record maps to " + here) + (elsewhere.isEmpty() ? "" : "; a scope-" + o.scopeId()
                + " observation exists at another owner or unit " + elsewhere + ", which cannot discharge this one");
    }

    /** Value checks of one matched observation against the native payload. */
    static List<String> values(Occurrence occ, Json u) {
        Obligation o = occ.obligation();
        List<String> problems = new ArrayList<>();
        Integer status = Event.integer(u.member("usage_status_id"));
        if (!Objects.equals(status, occ.status())) {
            problems.add("usage_status_id " + status + " != " + occ.status());
        }
        Integer aggregation = Event.integer(u.member("aggregation_id"));
        Integer wantAggregation = o.aggregation() == null || o.aggregation().isNull() ? null : Event.integer(o.aggregation());
        if (!Objects.equals(aggregation, wantAggregation)) {
            problems.add("aggregation_id " + aggregation + " != " + (wantAggregation == null ? "absent (no aggregation is established)" : wantAggregation));
        }
        for (String c : AnalysisViews.COMPONENTS) {
            String nativePath = o.components().get(c);
            Json want = nativePath == null ? null : occ.payload().path(nativePath.split("\\."));
            Json have = u.member(c);
            if (want instanceof Json.JNumber && SourceProfile.isIntegral(want)) {
                if (!(have instanceof Json.JNumber) || !Json.sameValue(want, have)) {
                    problems.add(c + " " + (have == null ? "absent" : have.scalarText()) + " != native " + nativePath + " " + want.scalarText());
                }
            }
            else if (have != null) {
                problems.add(c + " present (" + have.scalarText() + ") but " + (nativePath == null ? "the obligation maps no native member to it" : "the native " + nativePath + " is absent") + "; absence is not zero");
            }
        }
        if (o.tiers() != null) {
            List<String> want = new ArrayList<>();
            if (occ.payload().member(o.tiers()) instanceof Json.JObject t) {
                t.members().forEach((k, v) -> {
                    if (SourceProfile.isIntegral(v)) {
                        want.add(k + "=" + v.scalarText());
                    }
                });
            }
            List<String> have = new ArrayList<>();
            for (Json t : u.member("cache_write_tiers") == null ? List.<Json>of() : u.member("cache_write_tiers").elements()) {
                have.add(SourceProfile.text(t, "tier") + "=" + (t.member("token_count") == null ? null : t.member("token_count").scalarText()));
            }
            if (!want.equals(have)) {
                problems.add("cache_write_tiers " + have + " != native " + o.tiers() + " " + want);
            }
        }
        if (u.member("data") == null || !Json.sameValue(occ.payload(), u.member("data"))) {
            problems.add("data is not the native " + o.payload() + " object exactly");
        }
        return problems;
    }

    // ---- static checks of the catalogs ---------------------------------------------------------------------------------

    /**
     * The dispositions of every native member an occurrence maps: each must be one normalized entry, exact or a subtree
     * that names the obligation in {@code carve_outs}, whose destinations agree with the obligation.
     */
    static List<Finding> dispositions(Occurrence occ, FieldDispositionTest.Catalog dispositions) {
        List<Finding> out = new ArrayList<>();
        Obligation o = occ.obligation();
        String base = inventoryPath(o.payload());
        Map<String, String> members = new LinkedHashMap<>();
        o.components().forEach((canonical, nativePath) -> {
            if (occ.payload().path(nativePath.split("\\.")) instanceof Json.JNumber) {
                members.put(base + "." + nativePath, canonical);
            }
        });
        if (o.tiers() != null && occ.payload().member(o.tiers()) instanceof Json.JObject t) {
            t.members().keySet().forEach(k -> members.put(base + "." + o.tiers() + "." + k, "cache_write_tiers[].token_count"));
        }
        for (Map.Entry<String, String> m : members.entrySet()) {
            String id = o.key() + "|" + o.variant() + ":" + m.getKey();
            List<FieldDispositionTest.Entry> applicable = FieldDispositionTest.applicable(dispositions, o.variant(), m.getKey());
            if (applicable.isEmpty()) {
                out.add(new Finding(1, id, "no disposition for the mapped member " + o.variant() + ":" + m.getKey()));
                continue;
            }
            if (applicable.size() > 1) {
                out.add(new Finding(2, id, "more than one disposition: " + applicable.stream().map(FieldDispositionTest.Entry::key).toList()));
                continue;
            }
            FieldDispositionTest.Entry e = applicable.get(0);
            if (!"normalized".equals(e.disposition())) {
                out.add(new Finding(3, id, "disposition " + e.key() + " is " + e.disposition() + ": a required canonical mapping cannot be retained-only"));
            }
            else if (e.subtree() && !texts(e.raw() == null ? null : e.raw().member("carve_outs")).contains(o.id())) {
                out.add(new Finding(3, id, "hidden inside the broad disposition " + e.key() + " (" + e.destinations() + "), which names no carve-out for " + o.id()));
            }
            else if (!e.subtree() && !e.destinations().contains(o.destination(m.getValue()))) {
                out.add(new Finding(2, id, "disposition " + e.key() + " names " + e.destinations() + ", not the obligation's " + o.destination(m.getValue())));
            }
        }
        return out;
    }

    /** The disposition catalog's notation for a payload path: a map key is {@code {*}}. */
    static String inventoryPath(String payload) {
        return payload;
    }

    /**
     * Token-named integral members of the recording: inside an occurrence's payload they must be a mapped component, a
     * tier, or a residual; elsewhere, an obligation payload or a residual. Inside a data-keyed subtree of a record that
     * triggers no obligation (tool-defined application data) a token-named key is a heuristic notice, not a finding.
     */
    static List<Finding> tokenMembers(Catalog c, NativeArtifact art, List<Occurrence> occurrences, Set<String> dataKeyed, List<String> notices) {
        RawInventory.ProfileRules rules = RawInventory.ProfileRules.forProfile(c.profile());
        Map<Integer, List<Occurrence>> byRecord = new LinkedHashMap<>();
        occurrences.forEach(o -> byRecord.computeIfAbsent(o.record(), k -> new ArrayList<>()).add(o));
        List<Finding> out = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (NativeArtifact.Record r : art.records()) {
            if (!r.parsed()) {
                continue;
            }
            String variant = rules.variant(r.json());
            List<Occurrence> here = byRecord.getOrDefault(r.index(), List.of());
            List<String> leaves = new ArrayList<>();
            leaves(r.json(), "", leaves, rules.mapKeyed());
            for (String path : leaves) {
                String id = variant + ":" + path;
                if (residual(c, variant, path) != null || mapped(here, path) || !seen.add(id)) {
                    continue;
                }
                Occurrence inside = here.stream().filter(o -> path.startsWith(inventoryPath(o.obligation().payload()) + ".")).findFirst().orElse(null);
                if (inside != null) {
                    out.add(new Finding(1, inside.obligation().key() + "|" + id, "token-named member inside the payload of " + inside.obligation().id()
                            + " with no canonical component and no reviewed residual (record " + r.index() + ")"));
                }
                else if (here.isEmpty() && dataKeyed.stream().anyMatch(d -> path.startsWith(d + ".") || path.startsWith(d + "["))) {
                    notices.add(id + " (record " + r.index() + "): token-named key inside tool-defined data; not classified as vendor usage");
                }
                else {
                    out.add(new Finding(1, id, "token-named vendor member that no obligation maps and no reviewed residual classifies (record " + r.index() + ")"));
                }
            }
        }
        return out;
    }

    private static boolean mapped(List<Occurrence> here, String path) {
        for (Occurrence o : here) {
            String base = inventoryPath(o.obligation().payload());
            for (String nativePath : o.obligation().components().values()) {
                if (path.equals(base + "." + nativePath)) {
                    return true;
                }
            }
            if (o.obligation().tiers() != null && path.startsWith(base + "." + o.obligation().tiers() + ".")) {
                return true;
            }
        }
        return false;
    }

    static Residual residual(Catalog c, String variant, String path) {
        for (Residual r : c.residuals()) {
            if (r.variant().equals(variant) && (path.equals(r.path()) || path.startsWith(r.path() + ".") || path.startsWith(r.path() + "["))) {
                return r;
            }
        }
        return null;
    }

    /** Integral leaves under token-named keys, in the inventory notation ({@code a.b}, {@code a[].b}, a map key as {@code {*}}). */
    static void leaves(Json v, String path, List<String> out, Set<String> mapKeyed) {
        if (v instanceof Json.JObject o) {
            boolean map = mapKeyed.contains(path);
            o.members().forEach((k, x) -> {
                String p = (path.isEmpty() ? "" : path + ".") + (map ? "{*}" : k);
                if (TOKEN_KEY.matcher(k).find() && x instanceof Json.JNumber && !out.contains(p)) {
                    out.add(p);
                }
                leaves(x, p, out, mapKeyed);
            });
        }
        else if (v instanceof Json.JArray a) {
            a.elements().forEach(x -> leaves(x, path + "[]", out, mapKeyed));
        }
    }

    /**
     * Required registrations of every source binding the registry admits or a final-assembly profile names: a
     * disposition catalog that lists the entry, an oracle source profile, the disposition gate's registration and an
     * obligation catalog binding the entry.
     */
    static List<Finding> registrations(Set<String> obligationEntries) {
        Json registry = NeutralJson.readDocument(REGISTRY);
        Map<String, Set<String>> catalogued = dispositionCatalogs();
        List<Finding> out = new ArrayList<>();
        Map<String, String> required = new TreeMap<>();
        for (Json e : registry.member("entries").elements()) {
            if ("admitted".equals(SourceProfile.text(e, "admission"))) {
                required.put(SourceProfile.text(e, "id"), "admitted");
            }
        }
        for (Json p : profiles()) {
            String version = SourceProfile.text(p, "cli", "version");
            for (Json s : p.member("sources").elements()) {
                for (Json e : registry.member("entries").elements()) {
                    if (Objects.equals(SourceProfile.text(s, "profile"), SourceProfile.text(e, "profile")) && inRange(version, e)) {
                        required.merge(SourceProfile.text(e, "id"), "final-assembly profile " + SourceProfile.text(p, "profile_id"), (a, b) -> a + "; " + b);
                    }
                }
            }
        }
        for (Map.Entry<String, String> r : required.entrySet()) {
            Json e = entry(registry, r.getKey());
            String profile = SourceProfile.text(e, "profile");
            String why = " (required: " + r.getValue() + ")";
            if (!catalogued.getOrDefault(profile, Set.of()).contains(r.getKey())) {
                out.add(new Finding(5, "disposition-catalog:" + r.getKey(), "no field-disposition catalog lists " + r.getKey() + why));
            }
            if (SourceProfile.forName(profile) == null) {
                out.add(new Finding(5, "oracle-profile:" + r.getKey(), "no independent oracle source profile for " + profile + why));
            }
            if (!FieldDispositionTest.CATALOGS.containsKey(profile)) {
                out.add(new Finding(5, "disposition-gate:" + r.getKey(), "FieldDispositionTest does not register profile " + profile + why));
            }
            if (!obligationEntries.contains(r.getKey())) {
                out.add(new Finding(5, "obligation-catalog:" + r.getKey(), "no canonical-obligation catalog binds " + r.getKey() + why));
            }
        }
        return out;
    }

    private static Json entry(Json registry, String id) {
        return registry.member("entries").elements().stream().filter(e -> id.equals(SourceProfile.text(e, "id"))).findFirst().orElseThrow();
    }

    static boolean inRange(String version, Json entry) {
        return version != null && Compare.version(SourceProfile.text(entry, "min_version"), version) <= 0 && Compare.version(version, SourceProfile.text(entry, "max_version")) <= 0;
    }

    /** Profile name to the compatibility entries its field-disposition catalogs list. */
    static Map<String, Set<String>> dispositionCatalogs() {
        Map<String, Set<String>> out = new TreeMap<>();
        try (Stream<Path> s = Files.walk(PROJECT.resolve("schema/vendor"))) {
            for (Path p : s.filter(x -> x.getFileName().toString().equals("dispositions.json")).toList()) {
                Json d = NeutralJson.readDocument(p);
                out.computeIfAbsent(SourceProfile.text(d, "profile"), k -> new LinkedHashSet<>()).addAll(texts(d.member("compatibility_entries")));
            }
        }
        catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
        return out;
    }

    static List<Json> profiles() {
        try (Stream<Path> s = Files.list(PROFILES)) {
            return s.filter(p -> p.toString().endsWith(".json")).sorted().map(NeutralJson::readDocument).toList();
        }
        catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    /**
     * The binding's recordings against its compatibility entry: each declares the entry's route and reports a version in
     * its range, and shows no unreviewed drift (a proposal-severity finding) against the entry's bound specification.
     */
    static List<Finding> binding(Binding b, Map<String, NativeArtifact> natives) {
        Json registry = NeutralJson.readDocument(REGISTRY);
        Json e = entry(registry, b.entry());
        List<Finding> out = new ArrayList<>();
        Path specFile = PROJECT.resolve(SourceProfile.text(e.member("source_specification"), "path"));
        Json spec = SourceSpecification.read(specFile);
        for (String fixture : b.recordings()) {
            NativeArtifact art = natives.get(fixture);
            String version = reportedVersion(art);
            String route = declaredRoute(fixture);
            if (!inRange(version, e) || !texts(e.member("routes")).contains(route)) {
                out.add(new Finding(6, "binding:" + b.entry() + ":" + fixture, "the recording reports version " + version + " on route " + route + ", outside " + b.entry()));
            }
            Json report = InventoryComparison.compare(spec, RawInventory.inventory(art, ARTIFACT, SourceProfile.text(e, "profile")));
            for (Json f : report.member("findings").elements()) {
                if ("proposal".equals(SourceProfile.text(f, "severity"))) {
                    out.add(new Finding(6, "drift:" + b.entry() + ":" + fixture + ":" + SourceProfile.text(f, "finding") + ":" + SourceProfile.text(f, "variant")
                            + (f.member("path") == null || f.member("path").isNull() ? "" : ":" + SourceProfile.text(f, "path")),
                            "unreviewed drift against " + PROJECT.relativize(specFile) + " " + SourceProfile.text(spec, "specification_version")));
                }
            }
        }
        return out;
    }

    /** The CLI version the stream itself reports (the init record's claude_code_version). */
    static String reportedVersion(NativeArtifact art) {
        for (NativeArtifact.Record r : art.records()) {
            if (r.parsed() && "init".equals(SourceProfile.text(r.json(), "subtype"))) {
                return SourceProfile.text(r.json(), "claude_code_version");
            }
        }
        return null;
    }

    /** The capture route the fixture's own specification declares for its stream artifact. */
    static String declaredRoute(String fixture) {
        Json spec = NeutralJson.readDocument(FIXTURES.resolve(fixture).resolve("spec.json"));
        for (Json s : spec.member("sources").elements()) {
            if (ARTIFACT.equals(SourceProfile.text(s, "artifact"))) {
                return SourceProfile.text(s, "route");
            }
        }
        return null;
    }

    /** Semantic state of the support claim: approvals, meaning digests, unresolved residuals. */
    static List<Finding> semantics(Catalog c) {
        Set<String> approved = new LinkedHashSet<>();
        for (Json d : NeutralJson.readDocument(DECISIONS).member("decisions").elements()) {
            for (Json a : d.member("approves") == null ? List.<Json>of() : d.member("approves").elements()) {
                approved.add(SourceProfile.text(a, "obligation") + "@r" + (a.member("revision") == null ? null : a.member("revision").scalarText()) + "#" + SourceProfile.text(a, "digest"));
            }
        }
        List<Finding> out = new ArrayList<>();
        for (Obligation o : c.obligations()) {
            String digest = meaningDigest(o);
            if (!digest.equals(o.digest())) {
                out.add(new Finding(6, "digest:" + o.key(), "meaning_digest " + o.digest() + " != " + digest + ": the meaning changed without a new revision"));
            }
            if (!approved.contains(o.key() + "#" + digest)) {
                out.add(new Finding(6, "approval:" + o.key(), "no Owner decision approves " + o.key() + " at its meaning digest (review status: " + o.reviewStatus() + ")"));
            }
        }
        for (Residual r : c.residuals()) {
            if ("unresolved".equals(r.classification())) {
                out.add(new Finding(6, "unresolved:" + r.variant() + ":" + r.path(), r.reason()));
            }
        }
        return out;
    }

    /** Dotted version comparison (2.1.299 &lt; 2.1.304). */
    static final class Compare {
        private Compare() {
        }

        static int version(String a, String b) {
            String[] x = a.split("\\.");
            String[] y = b.split("\\.");
            for (int i = 0; i < Math.max(x.length, y.length); i++) {
                int d = Integer.compare(i < x.length ? Integer.parseInt(x[i]) : 0, i < y.length ? Integer.parseInt(y[i]) : 0);
                if (d != 0) {
                    return d;
                }
            }
            return 0;
        }
    }
}
