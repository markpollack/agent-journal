package io.github.markpollack.journal.tck.ocsf.inventory;

import io.github.markpollack.journal.tck.ocsf.NativeArtifact;
import io.github.markpollack.journal.tck.verify.Json;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * An empirical inventory of one native artifact, independent of any typed parser, mapper or SDK: every record is
 * classified into a vendor record variant by its discriminator fields, and every path inside it is recorded with
 * the kinds observed there (object, array, string, integer, decimal, boolean, null), in how many records of the
 * variant the path is present, how often it is null or an empty container, and which records carry it (evidence
 * locations by record index, never content).
 *
 * <p>Arbitrary application data is kept apart from the vendor envelope: under a <em>data-keyed</em> path the keys
 * belong to a tool or a user (tool inputs, structured results) and only the value kinds are recorded under
 * {@code {*}}; under a <em>map-keyed</em> path the keys are data (model names) but the values have vendor structure,
 * which continues to be inventoried below {@code {*}}. Opaque records (not one JSON object) form their own variants.
 *
 * <p>The inventory is the raw observation; {@link SourceSpecification} holds the accepted, versioned baseline, and
 * {@link InventoryComparison} reports the differences as proposals, never by widening the baseline.
 */
public final class RawInventory {

    public static final String KIND = "agent-journal/native-inventory";
    public static final String VERSION = "0.1";

    private RawInventory() {
    }

    /** Per-profile discriminator and the data-keyed / map-keyed paths; the profile names are the oracle's. */
    public record ProfileRules(String profile, Set<String> dataKeyed, Set<String> mapKeyed) {

        public static ProfileRules forProfile(String profile) {
            return switch (profile == null ? "" : profile) {
                // tool inputs, tool-defined structured results, tool schemas and per-tool wire inputs are tool/user data;
                // modelUsage is keyed by model name with vendor-structured values
                case "claude_stream" -> new ProfileRules(profile,
                        Set.of("message.content[].input", "tool_use_result", "wire_tool_inputs"),
                        Set.of("modelUsage"));
                case "claude_transcript" -> new ProfileRules(profile,
                        Set.of("message.content[].input", "toolUseResult", "wireToolInputs", "shape.tools[].input_schema", "attachment.tools[].schema"),
                        Set.of("modelUsage"));
                case "codex_stream" -> new ProfileRules(profile, Set.of(), Set.of());
                case "codex_rollout" -> new ProfileRules(profile, Set.of("payload.output"), Set.of());
                default -> new ProfileRules(profile == null ? "(unknown)" : profile, Set.of(), Set.of());
            };
        }

        /** The variant name of a parsed record under this profile, with the discriminator values that produced it. */
        public Map<String, String> discriminators(Json o) {
            Map<String, String> d = new LinkedHashMap<>();
            String type = text(o, "type");
            d.put("type", type);
            switch (profile) {
                case "claude_stream" -> {
                    if ("system".equals(type)) {
                        d.put("subtype", text(o, "subtype"));
                    }
                    if ("user".equals(type)) {
                        d.put("message.content[].type", hasBlock(o, "tool_result") ? "tool_result" : null);
                    }
                }
                case "claude_transcript" -> {
                    if ("attachment".equals(type)) {
                        d.put("attachment.type", text(o, "attachment", "type"));
                    }
                    if ("user".equals(type)) {
                        d.put("message.content[].type", hasBlock(o, "tool_result") ? "tool_result" : "prompt");
                    }
                }
                case "codex_stream" -> {
                    if (o.member("item") != null) {
                        d.put("item.type", text(o, "item", "type"));
                    }
                }
                case "codex_rollout" -> {
                    if (o.path("payload", "type") != null) {
                        d.put("payload.type", text(o, "payload", "type"));
                    }
                    if ("message".equals(text(o, "payload", "type"))) {
                        d.put("payload.role", text(o, "payload", "role"));
                    }
                    if (o.path("payload", "item", "type") != null) {
                        d.put("payload.item.type", text(o, "payload", "item", "type"));
                    }
                }
                default -> {
                }
            }
            return d;
        }

        public String variant(Json o) {
            StringBuilder sb = new StringBuilder();
            for (String v : discriminators(o).values()) {
                if (v != null) {
                    sb.append(sb.length() == 0 ? "" : "/").append(v);
                }
            }
            return sb.length() == 0 ? "(untyped)" : sb.toString();
        }

        private static boolean hasBlock(Json o, String blockType) {
            Json content = o.path("message", "content");
            for (Json b : content == null ? List.<Json>of() : content.elements()) {
                if (blockType.equals(text(b, "type"))) {
                    return true;
                }
            }
            return false;
        }
    }

    /** Observed statistics of one path within one variant. */
    static final class PathStats {
        final Map<String, Integer> kinds = new TreeMap<>();
        final Set<Integer> records = new TreeSet<>();
        int occurrences;
        int nulls;
        int empty;
        final Set<String> dataKeys = new TreeSet<>();
    }

    /** Observed statistics of one variant. */
    static final class VariantStats {
        final Map<String, String> discriminators = new LinkedHashMap<>();
        final List<Integer> records = new ArrayList<>();
        final Map<String, PathStats> paths = new TreeMap<>();
        int opaque;
    }

    /** The inventory of an artifact under a profile's rules, as a JSON document (paths, kinds, counts, indexes; no content). */
    public static Json inventory(NativeArtifact art, String artifactName, String profile) {
        ProfileRules rules = ProfileRules.forProfile(profile);
        Map<String, VariantStats> variants = new TreeMap<>();
        for (NativeArtifact.Record r : art.records()) {
            if (!r.parsed()) {
                VariantStats v = variants.computeIfAbsent("(opaque:" + r.status() + ")", k -> new VariantStats());
                v.records.add(r.index());
                v.opaque++;
                continue;
            }
            Json o = r.json();
            String name = rules.variant(o);
            VariantStats v = variants.computeIfAbsent(name, k -> new VariantStats());
            if (v.discriminators.isEmpty()) {
                v.discriminators.putAll(rules.discriminators(o));
            }
            v.records.add(r.index());
            walk(o, "", v, r.index(), rules, false);
        }
        Map<String, Json> doc = new LinkedHashMap<>();
        doc.put("kind", str(KIND));
        doc.put("version", str(VERSION));
        doc.put("artifact", str(artifactName));
        doc.put("profile", str(rules.profile()));
        doc.put("sha256", str(art.sha256()));
        doc.put("bytes", num(art.bytes().length));
        doc.put("records", num(art.records().size()));
        doc.put("opaque_records", num(art.opaqueCount()));
        doc.put("data_keyed", strings(rules.dataKeyed()));
        doc.put("map_keyed", strings(rules.mapKeyed()));
        doc.put("path_notation", str("object members joined by '.', array elements as '[]', data-keyed or map-keyed members as '{*}'; kinds: object, array, string, integer, decimal, boolean, null"));
        Map<String, Json> vs = new LinkedHashMap<>();
        for (Map.Entry<String, VariantStats> e : variants.entrySet()) {
            VariantStats v = e.getValue();
            Map<String, Json> vd = new LinkedHashMap<>();
            Map<String, Json> disc = new LinkedHashMap<>();
            v.discriminators.forEach((k, val) -> disc.put(k, val == null ? Json.JNull.INSTANCE : str(val)));
            vd.put("discriminators", new Json.JObject(disc));
            vd.put("records", num(v.records.size()));
            vd.put("evidence", ints(v.records));
            if (v.opaque > 0) {
                vd.put("opaque", num(v.opaque));
            }
            Map<String, Json> ps = new LinkedHashMap<>();
            for (Map.Entry<String, PathStats> p : v.paths.entrySet()) {
                PathStats s = p.getValue();
                Map<String, Json> pd = new LinkedHashMap<>();
                Map<String, Json> kinds = new LinkedHashMap<>();
                s.kinds.forEach((k, n) -> kinds.put(k, num(n)));
                pd.put("kinds", new Json.JObject(kinds));
                pd.put("present_in_records", num(s.records.size()));
                pd.put("absent_in_records", num(v.records.size() - s.records.size()));
                pd.put("occurrences", num(s.occurrences));
                pd.put("null", num(s.nulls));
                pd.put("empty", num(s.empty));
                if (!s.dataKeys.isEmpty()) {
                    pd.put("distinct_data_keys", num(s.dataKeys.size()));
                }
                pd.put("evidence", ints(new ArrayList<>(s.records)));
                ps.put(p.getKey(), new Json.JObject(pd));
            }
            vd.put("paths", new Json.JObject(ps));
            vs.put(e.getKey(), new Json.JObject(vd));
        }
        doc.put("variants", new Json.JObject(vs));
        return new Json.JObject(doc);
    }

    private static void walk(Json value, String path, VariantStats v, int record, ProfileRules rules, boolean collapsed) {
        if (!path.isEmpty()) {
            PathStats s = v.paths.computeIfAbsent(path, k -> new PathStats());
            s.kinds.merge(kind(value), 1, Integer::sum);
            s.records.add(record);
            s.occurrences++;
            if (value.isNull()) {
                s.nulls++;
            }
            if ((value instanceof Json.JObject o && o.members().isEmpty()) || (value instanceof Json.JArray a && a.elements().isEmpty())) {
                s.empty++;
            }
        }
        if (collapsed) {
            return; // below a data-keyed subtree only the value kinds under {*} are recorded
        }
        if (value instanceof Json.JObject o) {
            boolean data = rules.dataKeyed().contains(path);
            boolean map = rules.mapKeyed().contains(path);
            if (data || map) {
                PathStats s = v.paths.computeIfAbsent(path, k -> new PathStats());
                s.dataKeys.addAll(o.members().keySet());
                for (Json child : o.members().values()) {
                    walk(child, path + ".{*}", v, record, rules, data);
                }
                return;
            }
            for (Map.Entry<String, Json> m : o.members().entrySet()) {
                walk(m.getValue(), path.isEmpty() ? m.getKey() : path + "." + m.getKey(), v, record, rules, false);
            }
        }
        else if (value instanceof Json.JArray a) {
            boolean data = rules.dataKeyed().contains(path);
            for (Json el : a.elements()) {
                walk(el, path + "[]", v, record, rules, data);
            }
        }
    }

    static String kind(Json v) {
        if (v instanceof Json.JNumber n) {
            return n.value().stripTrailingZeros().scale() <= 0 ? "integer" : "decimal";
        }
        return Json.kindOf(v);
    }

    static String text(Json json, String... path) {
        Json v = json == null ? null : json.path(path);
        return v == null || v.isNull() ? null : v.text();
    }

    static Json str(String s) {
        return s == null ? Json.JNull.INSTANCE : new Json.JString(s);
    }

    static Json num(int n) {
        return new Json.JNumber(Integer.toString(n));
    }

    static Json ints(List<Integer> values) {
        List<Json> out = new ArrayList<>();
        for (Integer i : values) {
            out.add(num(i));
        }
        return new Json.JArray(out);
    }

    static Json strings(Set<String> values) {
        List<Json> out = new ArrayList<>();
        for (String s : new TreeSet<>(values)) {
            out.add(str(s));
        }
        return new Json.JArray(out);
    }
}
