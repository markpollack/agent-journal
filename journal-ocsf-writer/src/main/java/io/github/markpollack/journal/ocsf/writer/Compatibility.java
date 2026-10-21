package io.github.markpollack.journal.ocsf.writer;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * The small compatibility registry (resource {@code ocsf/compatibility.json}): which source description, mapping
 * contract and Java mapper apply to a source, keyed by CLI, source profile, launch route and an inclusive CLI
 * version range, with the versions actually tested and their evidence.
 *
 * <p>Selection is explicit and bounded (RULE-10, RULE-33): exactly one entry may match; two entries whose ranges
 * overlap for the same CLI, profile and route are a configuration error rejected when the registry loads; a version
 * outside every range selects nothing, and the writer then retains the acquired input under the
 * {@code untested-compatibility} contract and flags it rather than choosing the nearest entry. A version inside a
 * range that no tested entry names is {@link Status#IN_RANGE}: a reviewed range, not an individually tested release.
 * A version not yet known is {@link Status#VERSION_UNKNOWN} until the stream reports it (RULE-9: records are
 * persisted meanwhile). Versions compare numerically per dot-separated component.
 */
final class Compatibility {

    static final String RESOURCE = "/ocsf/compatibility.json";
    static final String UNTESTED_CONTRACT = "untested-compatibility";

    enum Status {
        /** The version is named under the entry's tested evidence. */
        TESTED,
        /** The version lies inside the entry's reviewed range but no tested entry names it. */
        IN_RANGE,
        /** No entry matches the CLI, profile, route and version: retained and flagged, not normalized. */
        NO_MATCH,
        /** The version is not known yet; the selection is repeated once the stream reports it. */
        VERSION_UNKNOWN,
        /** The profile normalizes nothing and has no entry (opaque text). */
        NOT_VERSION_SCOPED
    }

    record Entry(String id, String cli, String profile, List<String> routes, String minVersion, String maxVersion, Jv sourceSpecification, Jv schema,
            String contract, String mapper, List<Jv> tested, String note, Jv raw) {

        boolean keyMatches(String cli, String profile, String route) {
            return this.cli.equals(cli) && this.profile.equals(profile) && route != null && routes.contains(route);
        }

        boolean inRange(String version) {
            return compare(minVersion, version) <= 0 && compare(version, maxVersion) <= 0;
        }

        boolean tested(String version, String route) {
            for (Jv t : tested) {
                if (version.equals(MapperSupport.text(t, "version")) && (route == null || route.equals(MapperSupport.text(t, "route")))) {
                    return true;
                }
            }
            return false;
        }

        boolean testedAtAnyRoute(String version) {
            return tested(version, null);
        }
    }

    /** The outcome of one selection: the status, the entry (when one matched) and the facts the declaration states. */
    record Selection(Status status, Entry entry, String cli, String profile, String route, String version, String detail) {

        /** The contract the writer uses for this source: the entry's, or the retention contract on no match. */
        String contractName(String mapperContract) {
            return status == Status.NO_MATCH ? UNTESTED_CONTRACT : mapperContract;
        }

        boolean normalizes() {
            return status != Status.NO_MATCH;
        }
    }

    private final Jv document;
    private final List<Entry> entries;
    private final Map<String, String> notVersionScoped = new TreeMap<>();

    private Compatibility(Jv document) {
        this.document = document;
        List<Entry> list = new ArrayList<>();
        for (Jv e : document.member("entries").elements()) {
            List<String> routes = new ArrayList<>();
            for (Jv r : e.member("routes").elements()) {
                routes.add(r.text());
            }
            List<Jv> tested = new ArrayList<>(e.member("tested") == null ? List.of() : e.member("tested").elements());
            list.add(new Entry(required(e, "id"), required(e, "cli"), required(e, "profile"), List.copyOf(routes), required(e, "min_version"), required(e, "max_version"),
                    e.member("source_specification"), e.member("schema"), required(e, "contract"), required(e, "mapper"), List.copyOf(tested), MapperSupport.text(e, "note"), e));
        }
        this.entries = List.copyOf(list);
        if (document.member("not_version_scoped") instanceof Jv.Obj n) {
            for (Map.Entry<String, Jv> m : n.members().entrySet()) {
                notVersionScoped.put(m.getKey(), m.getValue().text());
            }
        }
        validate();
    }

    private static String required(Jv e, String member) {
        String v = MapperSupport.text(e, member);
        if (v == null || v.isBlank()) {
            throw new IllegalStateException("compatibility entry " + MapperSupport.text(e, "id") + " lacks " + member);
        }
        return v;
    }

    /** The bundled registry, validated. */
    static Compatibility bundled() {
        try (InputStream in = Compatibility.class.getResourceAsStream(RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException("missing resource " + RESOURCE);
            }
            return new Compatibility(Jv.parse(new String(in.readAllBytes(), StandardCharsets.UTF_8)));
        }
        catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** A registry from a document (tests construct overlapping and ambiguous ones to prove they are rejected). */
    static Compatibility of(Jv document) {
        return new Compatibility(document);
    }

    List<Entry> entries() {
        return entries;
    }

    Jv document() {
        return document;
    }

    /** Every entry's declared facts are present and consistent, and no two entries overlap on (cli, profile, route) with intersecting ranges. */
    private void validate() {
        List<String> problems = new ArrayList<>();
        for (Entry e : entries) {
            if (compare(e.minVersion, e.maxVersion) > 0) {
                problems.add(e.id + ": min_version " + e.minVersion + " is above max_version " + e.maxVersion);
            }
            if (e.routes.isEmpty()) {
                problems.add(e.id + ": no routes");
            }
            if (e.sourceSpecification == null || e.schema == null) {
                problems.add(e.id + ": source_specification and schema are required");
            }
            for (Jv t : e.tested) {
                String v = MapperSupport.text(t, "version");
                if (v == null || !e.inRange(v)) {
                    problems.add(e.id + ": tested version " + v + " is outside [" + e.minVersion + ", " + e.maxVersion + "]");
                }
                String r = MapperSupport.text(t, "route");
                if (r == null || !e.routes.contains(r)) {
                    problems.add(e.id + ": tested route " + r + " is not one of the entry's routes " + e.routes);
                }
            }
            if (notVersionScoped.containsKey(e.profile)) {
                problems.add(e.id + ": profile " + e.profile + " is declared not version-scoped and cannot also have an entry");
            }
        }
        for (int i = 0; i < entries.size(); i++) {
            for (int j = i + 1; j < entries.size(); j++) {
                Entry a = entries.get(i);
                Entry b = entries.get(j);
                if (a.id.equals(b.id)) {
                    problems.add("duplicate entry id " + a.id);
                }
                if (!a.cli.equals(b.cli) || !a.profile.equals(b.profile)) {
                    continue;
                }
                for (String route : a.routes) {
                    if (b.routes.contains(route) && compare(a.minVersion, b.maxVersion) <= 0 && compare(b.minVersion, a.maxVersion) <= 0) {
                        problems.add("entries " + a.id + " and " + b.id + " overlap for " + a.cli + " " + a.profile + " route " + route + ": ["
                                + a.minVersion + ", " + a.maxVersion + "] and [" + b.minVersion + ", " + b.maxVersion + "]");
                    }
                }
            }
        }
        if (!problems.isEmpty()) {
            throw new IllegalStateException("compatibility registry is misconfigured: " + String.join("; ", problems));
        }
    }

    /**
     * Selects the one applicable entry for a source, or states why none applies. Throws when more than one entry
     * matches (unreachable for a validated registry; kept so an ambiguous selection can never be silently resolved).
     */
    Selection select(String cli, String profile, String route, String version) {
        if (notVersionScoped.containsKey(profile)) {
            return new Selection(Status.NOT_VERSION_SCOPED, null, cli, profile, route, version, "profile " + profile + ": " + notVersionScoped.get(profile));
        }
        List<Entry> keyed = new ArrayList<>();
        for (Entry e : entries) {
            if (e.keyMatches(cli, profile, route)) {
                keyed.add(e);
            }
        }
        if (version == null) {
            List<String> ids = new ArrayList<>();
            for (Entry e : keyed) {
                ids.add(e.id + " [" + e.minVersion + ", " + e.maxVersion + "]");
            }
            return new Selection(Status.VERSION_UNKNOWN, null, cli, profile, route, null, "the CLI version is not known yet; " + (keyed.isEmpty()
                    ? "no entry is keyed on " + cli + " " + profile + " route " + route + ", so the selection will be no_match whatever the version"
                    : "candidate entries keyed on " + cli + " " + profile + " route " + route + ": " + ids + "; the selection is repeated when the stream reports the version"));
        }
        List<Entry> matches = new ArrayList<>();
        for (Entry e : keyed) {
            if (e.inRange(version)) {
                matches.add(e);
            }
        }
        if (matches.size() > 1) {
            List<String> ids = new ArrayList<>();
            for (Entry e : matches) {
                ids.add(e.id);
            }
            throw new IllegalStateException("ambiguous compatibility selection for " + cli + " " + profile + " route " + route + " version " + version + ": " + ids);
        }
        if (matches.isEmpty()) {
            StringBuilder why = new StringBuilder("no compatibility entry for " + cli + " " + profile + " route " + String.valueOf(route) + " version " + version);
            if (keyed.isEmpty()) {
                why.append(": no entry is keyed on that CLI, profile and route");
            }
            else {
                why.append(": the keyed entries' ranges are");
                for (Entry e : keyed) {
                    why.append(' ').append(e.id).append(" [").append(e.minVersion).append(", ").append(e.maxVersion).append(']');
                }
                why.append("; the nearest entry is not selected");
            }
            why.append("; acquired input is retained whole under contract ").append(UNTESTED_CONTRACT).append(" and flagged untested (RULE-33)");
            return new Selection(Status.NO_MATCH, null, cli, profile, route, version, why.toString());
        }
        Entry e = matches.get(0);
        if (e.tested(version, route)) {
            return new Selection(Status.TESTED, e, cli, profile, route, version, "version " + version + " on route " + route + " is named under the tested evidence of " + e.id);
        }
        if (e.testedAtAnyRoute(version)) {
            return new Selection(Status.IN_RANGE, e, cli, profile, route, version,
                    "version " + version + " is tested under " + e.id + " on another route, not on route " + route + "; inside the reviewed range [" + e.minVersion + ", " + e.maxVersion + "]");
        }
        return new Selection(Status.IN_RANGE, e, cli, profile, route, version,
                "version " + version + " lies inside the reviewed range [" + e.minVersion + ", " + e.maxVersion + "] of " + e.id + " but is not individually tested");
    }

    /** The {@code compatibility} declaration payload for a selection of one source. */
    static Jv.Obj declaration(Selection s, String artifact, String versionBasis, int recordsBefore, String schemaSha256) {
        Jv.Obj d = new Jv.Obj().put("artifact", artifact).put("cli", s.cli()).put("profile", s.profile()).put("route", s.route() == null ? "unspecified" : s.route())
                .put("version", s.version() == null ? Jv.NULL : new Jv.Str(s.version())).put("version_basis", versionBasis)
                .put("status", s.status().name().toLowerCase()).put("detail", s.detail()).put("records_before_selection", recordsBefore)
                .put("normalizes", s.normalizes() && s.status() != Status.NOT_VERSION_SCOPED && s.status() != Status.VERSION_UNKNOWN ? "yes" : s.status() == Status.VERSION_UNKNOWN
                        ? "provisionally, under the profile's mapper, until the version is known" : "no");
        if (s.entry() != null) {
            Entry e = s.entry();
            d.put("entry_id", e.id()).put("min_version", e.minVersion()).put("max_version", e.maxVersion()).put("source_specification", e.sourceSpecification())
                    .put("schema", e.schema()).put("mapper", e.mapper()).put("contract", e.contract()).put("tested", new Jv.Arr(e.tested()));
            if (e.note() != null) {
                d.put("note", e.note());
            }
        }
        d.put("schema_sha256_in_use", schemaSha256);
        return d;
    }

    /** Numeric comparison per dot-separated component; a non-numeric component compares as text; a missing component is 0. */
    static int compare(String a, String b) {
        String[] x = a.split("\\.");
        String[] y = b.split("\\.");
        for (int i = 0; i < Math.max(x.length, y.length); i++) {
            String p = i < x.length ? x[i] : "0";
            String q = i < y.length ? y[i] : "0";
            int c;
            if (p.matches("\\d+") && q.matches("\\d+")) {
                c = new java.math.BigInteger(p).compareTo(new java.math.BigInteger(q));
            }
            else {
                c = p.compareTo(q);
            }
            if (c != 0) {
                return c;
            }
        }
        return 0;
    }
}
