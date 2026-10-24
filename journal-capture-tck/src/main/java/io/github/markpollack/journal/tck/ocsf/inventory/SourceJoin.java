package io.github.markpollack.journal.tck.ocsf.inventory;

import io.github.markpollack.journal.tck.ocsf.NativeArtifact;
import io.github.markpollack.journal.tck.ocsf.inventory.SourceLocator.Located;
import io.github.markpollack.journal.tck.verify.Difference;
import io.github.markpollack.journal.tck.verify.Json;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Which facts two native sources of one execution share, judged only on identities the sources themselves report
 * (EXPERIMENTAL analysis tool; not a writer path). A join specification names, per source, where its session identity
 * is reported and, per identity key, which records carry the key and at which RFC 6901 pointer ({@link SourceLocator}).
 * The sources are joined only when both report the same single session identity: a different identity rejects the
 * join and a missing one leaves it unestablished, and in both cases no key is joined. Records are never paired by
 * position, timestamp or similar text: a source whose selected records carry no key is reported as having no identity
 * for it.
 *
 * <p>A {@code record} key identifies one record per source (for example an event id that both a stream and a session
 * file write). Each matched pair is classified by value, the way the oracle compares values: identical, different only
 * in number spelling, one side carrying members the other lacks, or conflicting (a shared member with a different
 * value, kind or nullness). A key repeated within one source is ambiguous and not compared. A {@code fact} key
 * identifies one fact several records may describe (for example a tool call id on its start and completion records);
 * declared value pairs are compared per matched key, and an absent value is reported as absent, never as zero.
 *
 * <p>The report holds identities, counts, record indexes, pointers and difference kinds, never record content; it is
 * evidence for a combined-source proposal, not a merge.
 */
public final class SourceJoin {

    public static final String KIND = "agent-journal/source-join";
    public static final String SPECIFICATION_KIND = "agent-journal/source-join-specification";
    public static final String VERSION = "0.1";

    private static final Set<Difference.Kind> CONFLICTS = EnumSet.of(Difference.Kind.VALUE, Difference.Kind.KIND, Difference.Kind.NULL_VS_VALUE);
    private static final Map<String, String> NOT_JOINED = Map.of(
            "NOT_JOINED_SESSION", "the session identity is not established, so no key is joined",
            "NO_IDENTITY_IN_A", "the selected records of source a report no value for this key; they are not paired by position or content",
            "NO_IDENTITY_IN_B", "the selected records of source b report no value for this key; they are not paired by position or content");
    private static final List<String> PAIR_CLASSES = List.of("identical", "representation_only", "a_has_more", "b_has_more", "each_has_more", "conflicting", "ambiguous");

    private SourceJoin() {
    }

    /**
     * Joins side {@code a} with side {@code b} under the specification. Each side is one or more native artifacts read in
     * order (for example a session directory's summary and event file).
     */
    public static Json join(Json specification, List<NativeArtifact> a, List<NativeArtifact> b) {
        if (!SPECIFICATION_KIND.equals(RawInventory.text(specification, "kind"))) {
            throw new IllegalArgumentException("not a source-join specification: kind " + RawInventory.text(specification, "kind"));
        }
        List<Json> sides = specification.member("sources").elements();
        if (sides.size() != 2) {
            throw new IllegalArgumentException("a source-join specification names exactly two sources");
        }
        List<Located> ra = located(a);
        List<Located> rb = located(b);
        Set<String> sa = SourceLocator.values(ra, sides.get(0).member("session"));
        Set<String> sb = SourceLocator.values(rb, sides.get(1).member("session"));
        String session = sessionStatus(sa, sb);
        Map<String, Json> out = new LinkedHashMap<>();
        out.put("kind", RawInventory.str(KIND));
        out.put("version", RawInventory.str(VERSION));
        out.put("specification", specification.member("name") == null ? Json.JNull.INSTANCE : specification.member("name"));
        out.put("sources", new Json.JArray(List.of(side(sides.get(0), a, ra), side(sides.get(1), b, rb))));
        Map<String, Json> s = new LinkedHashMap<>();
        s.put("status", RawInventory.str(session));
        s.put("a", RawInventory.strings(sa));
        s.put("b", RawInventory.strings(sb));
        s.put("rule", RawInventory.str("joined only when both sources report the same single session identity; a different identity rejects the join, a missing one leaves it unestablished, and neither joins any key"));
        out.put("session", new Json.JObject(s));
        List<Json> keys = new ArrayList<>();
        for (Json k : specification.member("keys").elements()) {
            keys.add(key(k, ra, rb, "ESTABLISHED".equals(session)));
        }
        out.put("keys", new Json.JArray(keys));
        return new Json.JObject(out);
    }

    static String sessionStatus(Set<String> a, Set<String> b) {
        if (a.isEmpty() || b.isEmpty()) {
            return "UNESTABLISHED";
        }
        return a.size() == 1 && a.equals(b) ? "ESTABLISHED" : "REJECTED";
    }

    private static Json side(Json declared, List<NativeArtifact> artifacts, List<Located> records) {
        Map<String, Json> m = new LinkedHashMap<>();
        m.put("name", declared.member("name"));
        List<Json> arts = new ArrayList<>();
        for (NativeArtifact art : artifacts) {
            Map<String, Json> d = new LinkedHashMap<>();
            d.put("artifact", RawInventory.str(art.path().getFileName().toString()));
            d.put("sha256", RawInventory.str(art.sha256()));
            d.put("records", RawInventory.num(art.records().size()));
            arts.add(new Json.JObject(d));
        }
        m.put("artifacts", new Json.JArray(arts));
        m.put("parsed_records", RawInventory.num(records.size()));
        return new Json.JObject(m);
    }

    private static List<Located> located(List<NativeArtifact> artifacts) {
        List<Located> out = new ArrayList<>();
        for (NativeArtifact art : artifacts) {
            for (NativeArtifact.Record r : art.records()) {
                if (r.parsed()) {
                    out.add(new Located(art.path().getFileName().toString(), r.index(), r.json()));
                }
            }
        }
        return out;
    }

    // ------------------------------------------------------------------------------------------ keys

    private static Json key(Json k, List<Located> ra, List<Located> rb, boolean joined) {
        Map<String, List<Located>> ka = SourceLocator.keyed(ra, k.member("a"));
        Map<String, List<Located>> kb = SourceLocator.keyed(rb, k.member("b"));
        int selectedA = SourceLocator.selected(ra, k.member("a"));
        int selectedB = SourceLocator.selected(rb, k.member("b"));
        Map<String, Json> m = new LinkedHashMap<>();
        m.put("name", k.member("name"));
        m.put("unit", k.member("unit"));
        m.put("a_selected_records", RawInventory.num(selectedA));
        m.put("a_keyed_records", RawInventory.num(count(ka)));
        m.put("b_selected_records", RawInventory.num(selectedB));
        m.put("b_keyed_records", RawInventory.num(count(kb)));
        String status = keyStatus(joined, selectedA > 0 && ka.isEmpty(), selectedB > 0 && kb.isEmpty());
        m.put("status", RawInventory.str(status));
        if ("JOINED".equals(status)) {
            joinedKey(m, k, ka, kb);
        }
        else {
            m.put("detail", RawInventory.str(NOT_JOINED.get(status)));
        }
        return new Json.JObject(m);
    }

    static String keyStatus(boolean joined, boolean noIdentityInA, boolean noIdentityInB) {
        if (!joined) {
            return "NOT_JOINED_SESSION";
        }
        if (noIdentityInA) {
            return "NO_IDENTITY_IN_A";
        }
        return noIdentityInB ? "NO_IDENTITY_IN_B" : "JOINED";
    }

    private static void joinedKey(Map<String, Json> m, Json k, Map<String, List<Located>> ka, Map<String, List<Located>> kb) {
        Set<String> both = new LinkedHashSet<>(ka.keySet());
        both.retainAll(kb.keySet());
        List<String> onlyA = new ArrayList<>(ka.keySet());
        onlyA.removeAll(kb.keySet());
        List<String> onlyB = new ArrayList<>(kb.keySet());
        onlyB.removeAll(ka.keySet());
        m.put("matched", RawInventory.num(both.size()));
        m.put("a_only", strings(onlyA));
        m.put("b_only", strings(onlyB));
        if ("record".equals(RawInventory.text(k, "unit"))) {
            records(m, both, ka, kb);
            return;
        }
        m.put("a_occurrences", RawInventory.num(count(ka)));
        m.put("b_occurrences", RawInventory.num(count(kb)));
        if (k.member("compare") != null) {
            List<Json> results = new ArrayList<>();
            for (Json pair : k.member("compare").elements()) {
                results.add(compareValues(pair, both, ka, kb));
            }
            m.put("values", new Json.JArray(results));
        }
    }

    /** One record per key and side: classify each matched pair by value; a key repeated within a source is ambiguous. */
    private static void records(Map<String, Json> m, Set<String> both, Map<String, List<Located>> ka, Map<String, List<Located>> kb) {
        Map<String, Integer> classes = new TreeMap<>();
        PAIR_CLASSES.forEach(c -> classes.put(c, 0));
        List<Json> findings = new ArrayList<>();
        for (String key : both) {
            List<Located> xa = ka.get(key);
            List<Located> xb = kb.get(key);
            List<Difference> diffs = xa.size() == 1 && xb.size() == 1 ? Json.differences(xa.get(0).json(), xb.get(0).json()) : List.of();
            String c = xa.size() == 1 && xb.size() == 1 ? classify(diffs) : "ambiguous";
            classes.merge(c, 1, Integer::sum);
            if (!"identical".equals(c)) {
                findings.add(finding(key, c, xa, xb, diffs));
            }
        }
        Map<String, Json> cj = new LinkedHashMap<>();
        classes.forEach((c, n) -> cj.put(c, RawInventory.num(n)));
        m.put("pairs", new Json.JObject(cj));
        m.put("findings", new Json.JArray(findings));
    }

    static String classify(List<Difference> diffs) {
        Set<Difference.Kind> kinds = EnumSet.noneOf(Difference.Kind.class);
        diffs.forEach(d -> kinds.add(d.kind()));
        if (!Collections.disjoint(kinds, CONFLICTS)) {
            return "conflicting";
        }
        boolean aMore = kinds.contains(Difference.Kind.MISSING);
        boolean bMore = kinds.contains(Difference.Kind.EXTRA);
        if (aMore || bMore) {
            return aMore && bMore ? "each_has_more" : aMore ? "a_has_more" : "b_has_more";
        }
        return kinds.contains(Difference.Kind.REPRESENTATION) ? "representation_only" : "identical";
    }

    private static Json finding(String key, String c, List<Located> xa, List<Located> xb, List<Difference> diffs) {
        Map<String, Json> f = new LinkedHashMap<>();
        f.put("key", RawInventory.str(key));
        f.put("class", RawInventory.str(c));
        f.put("a", strings(xa.stream().map(Located::where).toList()));
        f.put("b", strings(xb.stream().map(Located::where).toList()));
        List<Json> ds = new ArrayList<>();
        for (Difference d : diffs) {
            Map<String, Json> dj = new LinkedHashMap<>();
            dj.put("path", RawInventory.str(d.path()));
            dj.put("kind", RawInventory.str(d.kind().name()));
            ds.add(new Json.JObject(dj));
        }
        f.put("differences", new Json.JArray(ds));
        return new Json.JObject(f);
    }

    /**
     * One declared value pair over every matched fact key: a value both sources report must be equal in value; a value
     * one source lacks is absent there, never zero.
     */
    private static Json compareValues(Json pair, Set<String> both, Map<String, List<Located>> ka, Map<String, List<Located>> kb) {
        String pa = RawInventory.text(pair, "a");
        String pb = RawInventory.text(pair, "b");
        int equal = 0;
        List<String> differ = new ArrayList<>();
        List<String> absentA = new ArrayList<>();
        List<String> absentB = new ArrayList<>();
        for (String key : both) {
            Json va = firstValue(ka.get(key), pa);
            Json vb = firstValue(kb.get(key), pb);
            if (va == null) {
                absentA.add(key);
            }
            if (vb == null) {
                absentB.add(key);
            }
            if (va != null && vb != null) {
                if (Json.sameValue(va, vb)) {
                    equal++;
                }
                else {
                    differ.add(key);
                }
            }
        }
        Map<String, Json> r = new LinkedHashMap<>();
        r.put("a", RawInventory.str(pa));
        r.put("b", RawInventory.str(pb));
        r.put("equal", RawInventory.num(equal));
        r.put("differ", strings(differ));
        r.put("absent_in_a", strings(absentA));
        r.put("absent_in_b", strings(absentB));
        return new Json.JObject(r);
    }

    private static Json firstValue(List<Located> records, String pointer) {
        for (Located r : records) {
            Json v = SourceLocator.pointer(r.json(), pointer);
            if (v != null) {
                return v;
            }
        }
        return null;
    }

    private static int count(Map<String, List<Located>> keyed) {
        return keyed.values().stream().mapToInt(List::size).sum();
    }

    private static Json strings(List<String> values) {
        List<Json> out = new ArrayList<>();
        for (String v : values) {
            out.add(RawInventory.str(v));
        }
        return new Json.JArray(out);
    }
}
