package io.github.markpollack.journal.tck.ocsf.inventory;

import io.github.markpollack.journal.tck.verify.Json;
import io.github.markpollack.journal.tck.verify.NeutralJson;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * A Journal-owned, versioned native-source specification: the accepted variant/path/kind inventory of one source
 * profile at stated CLI versions, launch modes and capture routes, with its provenance (which frozen artifacts it was
 * observed on, which external descriptions cross-checked it) and its explicit limits. The accepted inventory is
 * reviewed and committed; the tools here never widen it. A derived JSON Schema (Draft 2020-12 subset) is generated
 * from it for readers that want a shape description; the inventory, not the schema, is what drift is compared to,
 * so a new field is discovered even where the schema would be permissive.
 *
 * <pre>
 * {"kind": "agent-journal/native-source-specification", "version": "0.1", "specification_version": "0.1.0",
 *  "profile": "claude_transcript", "applies_to": {...}, "provenance": {...}, "limits": [...],
 *  "external_cross_checks": [...], "open_variants": [...], "inventory": {... RawInventory variants ...}}
 * </pre>
 */
public final class SourceSpecification {

    public static final String KIND = "agent-journal/native-source-specification";
    public static final String VERSION = "0.1";

    private SourceSpecification() {
    }

    public static Json read(Path file) {
        Json doc = NeutralJson.readDocument(file);
        if (!KIND.equals(RawInventory.text(doc, "kind"))) {
            throw new IllegalArgumentException("not a native-source specification: kind " + RawInventory.text(doc, "kind"));
        }
        return doc;
    }

    /**
     * A draft specification from one or more inventories of the same profile (their variants and paths merged, record
     * evidence qualified by artifact), with the applicability/provenance/limits the caller states. Labelled as a
     * draft until it is reviewed and committed as the accepted specification.
     */
    public static Json draft(String profile, String specificationVersion, Json appliesTo, Json provenance, List<String> limits,
            Json externalCrossChecks, List<Json> inventories) {
        Map<String, Json> doc = new LinkedHashMap<>();
        doc.put("kind", RawInventory.str(KIND));
        doc.put("version", RawInventory.str(VERSION));
        doc.put("status", RawInventory.str("DRAFT: proposed from observed inventories; accepted only when reviewed and committed"));
        doc.put("specification_version", RawInventory.str(specificationVersion));
        doc.put("profile", RawInventory.str(profile));
        doc.put("applies_to", appliesTo == null ? Json.JNull.INSTANCE : appliesTo);
        doc.put("provenance", provenance == null ? Json.JNull.INSTANCE : provenance);
        List<Json> lim = new ArrayList<>();
        for (String l : limits == null ? List.<String>of() : limits) {
            lim.add(RawInventory.str(l));
        }
        doc.put("limits", new Json.JArray(lim));
        doc.put("external_cross_checks", externalCrossChecks == null ? new Json.JArray(List.of()) : externalCrossChecks);
        doc.put("open_variants", new Json.JArray(List.of()));
        doc.put("inventory", merge(inventories));
        return new Json.JObject(doc);
    }

    /** Merges inventories of one profile: variants and paths united, counts summed, evidence as {@code artifact:index}. */
    static Json merge(List<Json> inventories) {
        Map<String, Json> out = new LinkedHashMap<>();
        List<Json> observed = new ArrayList<>();
        Map<String, Map<String, Object>> variants = new TreeMap<>();
        Json dataKeyed = null;
        Json mapKeyed = null;
        for (Json inv : inventories) {
            String artifact = RawInventory.text(inv, "artifact");
            Map<String, Json> o = new LinkedHashMap<>();
            o.put("artifact", inv.member("artifact"));
            o.put("sha256", inv.member("sha256"));
            o.put("bytes", inv.member("bytes"));
            o.put("records", inv.member("records"));
            observed.add(new Json.JObject(o));
            dataKeyed = inv.member("data_keyed");
            mapKeyed = inv.member("map_keyed");
            Json vs = inv.member("variants");
            for (Map.Entry<String, Json> v : ((Json.JObject) vs).members().entrySet()) {
                Map<String, Object> merged = variants.computeIfAbsent(v.getKey(), k -> new LinkedHashMap<>());
                merged.putIfAbsent("discriminators", v.getValue().member("discriminators"));
                merged.put("records", ((Integer) merged.getOrDefault("records", 0)) + integer(v.getValue().member("records")));
                @SuppressWarnings("unchecked")
                List<Json> ev = (List<Json>) merged.computeIfAbsent("evidence", k -> new ArrayList<Json>());
                for (Json i : v.getValue().member("evidence").elements()) {
                    ev.add(RawInventory.str(artifact + ":" + i.scalarText()));
                }
                @SuppressWarnings("unchecked")
                Map<String, Map<String, Object>> paths = (Map<String, Map<String, Object>>) merged.computeIfAbsent("paths", k -> new TreeMap<String, Map<String, Object>>());
                for (Map.Entry<String, Json> p : ((Json.JObject) v.getValue().member("paths")).members().entrySet()) {
                    Map<String, Object> ps = paths.computeIfAbsent(p.getKey(), k -> new LinkedHashMap<>());
                    @SuppressWarnings("unchecked")
                    Map<String, Integer> kinds = (Map<String, Integer>) ps.computeIfAbsent("kinds", k -> new TreeMap<String, Integer>());
                    for (Map.Entry<String, Json> kk : ((Json.JObject) p.getValue().member("kinds")).members().entrySet()) {
                        kinds.merge(kk.getKey(), integer(kk.getValue()), Integer::sum);
                    }
                    for (String c : List.of("present_in_records", "absent_in_records", "occurrences", "null", "empty")) {
                        ps.put(c, ((Integer) ps.getOrDefault(c, 0)) + integer(p.getValue().member(c)));
                    }
                    if (p.getValue().member("distinct_data_keys") != null) {
                        ps.put("distinct_data_keys", Math.max((Integer) ps.getOrDefault("distinct_data_keys", 0), integer(p.getValue().member("distinct_data_keys"))));
                    }
                    @SuppressWarnings("unchecked")
                    List<Json> pev = (List<Json>) ps.computeIfAbsent("evidence", k -> new ArrayList<Json>());
                    for (Json i : p.getValue().member("evidence").elements()) {
                        pev.add(RawInventory.str(artifact + ":" + i.scalarText()));
                    }
                }
            }
        }
        out.put("observed_on", new Json.JArray(observed));
        out.put("data_keyed", dataKeyed == null ? new Json.JArray(List.of()) : dataKeyed);
        out.put("map_keyed", mapKeyed == null ? new Json.JArray(List.of()) : mapKeyed);
        Map<String, Json> vs = new LinkedHashMap<>();
        for (Map.Entry<String, Map<String, Object>> v : variants.entrySet()) {
            Map<String, Json> vd = new LinkedHashMap<>();
            vd.put("discriminators", (Json) v.getValue().get("discriminators"));
            vd.put("records", RawInventory.num((Integer) v.getValue().get("records")));
            @SuppressWarnings("unchecked")
            List<Json> ev = (List<Json>) v.getValue().get("evidence");
            vd.put("evidence", new Json.JArray(ev));
            Map<String, Json> ps = new LinkedHashMap<>();
            @SuppressWarnings("unchecked")
            Map<String, Map<String, Object>> paths = (Map<String, Map<String, Object>>) v.getValue().get("paths");
            for (Map.Entry<String, Map<String, Object>> p : paths.entrySet()) {
                Map<String, Json> pd = new LinkedHashMap<>();
                Map<String, Json> kinds = new LinkedHashMap<>();
                @SuppressWarnings("unchecked")
                Map<String, Integer> ks = (Map<String, Integer>) p.getValue().get("kinds");
                ks.forEach((k, n) -> kinds.put(k, RawInventory.num(n)));
                pd.put("kinds", new Json.JObject(kinds));
                for (String c : List.of("present_in_records", "absent_in_records", "occurrences", "null", "empty")) {
                    pd.put(c, RawInventory.num((Integer) p.getValue().get(c)));
                }
                if (p.getValue().containsKey("distinct_data_keys")) {
                    pd.put("distinct_data_keys", RawInventory.num((Integer) p.getValue().get("distinct_data_keys")));
                }
                @SuppressWarnings("unchecked")
                List<Json> pev = (List<Json>) p.getValue().get("evidence");
                pd.put("evidence", new Json.JArray(pev));
                ps.put(p.getKey(), new Json.JObject(pd));
            }
            vd.put("paths", new Json.JObject(ps));
            vs.put(v.getKey(), new Json.JObject(vd));
        }
        out.put("variants", new Json.JObject(vs));
        return new Json.JObject(out);
    }

    static int integer(Json v) {
        return v instanceof Json.JNumber n ? n.value().intValueExact() : 0;
    }

    /**
     * A JSON Schema (Draft 2020-12 subset) derived from the accepted inventory: one object schema per variant with
     * nested properties from the paths, {@code required} for paths present in every record of the variant,
     * {@code type} from the observed kinds, {@code additionalProperties: false} except under data-keyed or map-keyed
     * paths and in variants listed as {@code open_variants}. Derived output: regenerate, never hand-edit.
     */
    public static Json jsonSchema(Json spec) {
        Json inv = spec.member("inventory");
        List<String> dataKeyed = strings(inv.member("data_keyed"));
        List<String> mapKeyed = strings(inv.member("map_keyed"));
        List<String> open = strings(spec.member("open_variants"));
        Map<String, Json> defs = new LinkedHashMap<>();
        List<Json> oneOf = new ArrayList<>();
        for (Map.Entry<String, Json> v : ((Json.JObject) inv.member("variants")).members().entrySet()) {
            if (v.getKey().startsWith("(opaque")) {
                continue;
            }
            int records = integer(v.getValue().member("records"));
            Node root = new Node();
            for (Map.Entry<String, Json> p : ((Json.JObject) v.getValue().member("paths")).members().entrySet()) {
                root.insert(p.getKey(), p.getValue(), records);
            }
            String name = v.getKey().replaceAll("[^A-Za-z0-9]+", "_");
            Json schema = root.schema(dataKeyed, mapKeyed, "", open.contains(v.getKey()), true);
            Map<String, Json> withTitle = new LinkedHashMap<>(((Json.JObject) schema).members());
            withTitle.put("title", RawInventory.str(v.getKey()));
            withTitle.put("x-journal-discriminators", v.getValue().member("discriminators"));
            withTitle.put("x-journal-observed-records", v.getValue().member("records"));
            defs.put(name, new Json.JObject(withTitle));
            oneOf.add(new Json.JObject(Map.of("$ref", RawInventory.str("#/$defs/" + name))));
        }
        Map<String, Json> doc = new LinkedHashMap<>();
        doc.put("$schema", RawInventory.str("https://json-schema.org/draft/2020-12/schema"));
        doc.put("title", RawInventory.str("agent-journal native source " + RawInventory.text(spec, "profile") + " " + RawInventory.text(spec, "specification_version")));
        doc.put("description", RawInventory.str("DERIVED from the accepted Journal inventory (" + RawInventory.text(spec, "profile") + "); observed shape at the stated versions, not a vendor promise; regenerate, never hand-edit"));
        doc.put("x-journal-applies-to", spec.member("applies_to") == null ? Json.JNull.INSTANCE : spec.member("applies_to"));
        doc.put("x-journal-derived-from", RawInventory.str(KIND + " " + RawInventory.text(spec, "specification_version")));
        doc.put("oneOf", new Json.JArray(oneOf));
        doc.put("$defs", new Json.JObject(defs));
        return new Json.JObject(doc);
    }

    private static List<String> strings(Json arr) {
        List<String> out = new ArrayList<>();
        for (Json s : arr == null ? List.<Json>of() : arr.elements()) {
            out.add(s.text());
        }
        return out;
    }

    /** A path tree node for schema derivation. */
    private static final class Node {
        final Map<String, Node> members = new LinkedHashMap<>();
        Node items;
        Node wildcard;
        Json stats;
        int variantRecords;

        void insert(String path, Json stats, int records) {
            Node n = this;
            StringBuilder seg = new StringBuilder();
            List<String> segs = new ArrayList<>();
            for (int i = 0; i < path.length(); i++) {
                char c = path.charAt(i);
                if (c == '.') {
                    segs.add(seg.toString());
                    seg.setLength(0);
                }
                else if (c == '[' && i + 1 < path.length() && path.charAt(i + 1) == ']') {
                    segs.add(seg.toString());
                    segs.add("[]");
                    seg.setLength(0);
                    i++;
                }
                else {
                    seg.append(c);
                }
            }
            segs.add(seg.toString());
            for (String s : segs) {
                if (s.isEmpty()) {
                    continue;
                }
                if (s.equals("[]")) {
                    if (n.items == null) {
                        n.items = new Node();
                    }
                    n = n.items;
                }
                else if (s.equals("{*}")) {
                    if (n.wildcard == null) {
                        n.wildcard = new Node();
                    }
                    n = n.wildcard;
                }
                else {
                    n = n.members.computeIfAbsent(s, k -> new Node());
                }
            }
            n.stats = stats;
            n.variantRecords = records;
        }

        Json schema(List<String> dataKeyed, List<String> mapKeyed, String path, boolean open, boolean root) {
            Map<String, Json> s = new LinkedHashMap<>();
            List<String> types = new ArrayList<>();
            if (stats != null) {
                for (String k : ((Json.JObject) stats.member("kinds")).members().keySet()) {
                    String t = switch (k) {
                        case "decimal" -> "number";
                        default -> k;
                    };
                    if (!types.contains(t)) {
                        types.add(t);
                    }
                }
            }
            else if (root) {
                types.add("object");
            }
            if (types.size() == 1) {
                s.put("type", RawInventory.str(types.get(0)));
            }
            else if (!types.isEmpty()) {
                List<Json> ts = new ArrayList<>();
                for (String t : types) {
                    ts.add(RawInventory.str(t));
                }
                s.put("type", new Json.JArray(ts));
            }
            boolean data = dataKeyed.contains(path);
            if (!members.isEmpty()) {
                Map<String, Json> props = new LinkedHashMap<>();
                List<Json> required = new ArrayList<>();
                for (Map.Entry<String, Node> m : members.entrySet()) {
                    String childPath = path.isEmpty() ? m.getKey() : path + "." + m.getKey();
                    props.put(m.getKey(), m.getValue().schema(dataKeyed, mapKeyed, childPath, false, false));
                    Node child = m.getValue();
                    if (child.stats != null && integer(child.stats.member("present_in_records")) == child.variantRecords && child.variantRecords > 0) {
                        required.add(RawInventory.str(m.getKey()));
                    }
                }
                s.put("properties", new Json.JObject(props));
                if (!required.isEmpty()) {
                    s.put("required", new Json.JArray(required));
                }
                s.put("additionalProperties", new Json.JBool(open));
            }
            if (wildcard != null) {
                s.put("additionalProperties", data ? new Json.JBool(true) : wildcard.schema(dataKeyed, mapKeyed, path + ".{*}", false, false));
                s.put(data ? "x-journal-data-keyed" : "x-journal-map-keyed", new Json.JBool(true));
                if (data) {
                    s.put("x-journal-note", RawInventory.str("keys and values here are tool/user data, not vendor envelope structure; only value kinds were inventoried"));
                }
            }
            if (items != null) {
                s.put("items", items.schema(dataKeyed, mapKeyed, path + "[]", false, false));
            }
            if (stats != null) {
                s.put("x-journal-observed", new Json.JObject(Map.of("present_in_records", stats.member("present_in_records"),
                        "null", stats.member("null"), "empty", stats.member("empty"))));
            }
            return new Json.JObject(s);
        }
    }
}
