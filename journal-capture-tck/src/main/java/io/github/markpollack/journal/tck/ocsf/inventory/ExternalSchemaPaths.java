package io.github.markpollack.journal.tck.ocsf.inventory;

import io.github.markpollack.journal.tck.verify.Json;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Cross-check of an observed inventory against an external description (a vendor or community JSON Schema), kept
 * separate from the comparison with Journal's accepted specification. The schema is read for the paths it declares
 * per record variant (alternatives discriminated by a {@code const} on a named property, {@code $ref} into
 * {@code $defs}, {@code properties}, {@code items}, {@code anyOf}/{@code oneOf}/{@code allOf} united) and for where
 * it is open ({@code additionalProperties} absent or true, or no {@code properties}). The report says, per observed
 * variant, whether the external description knows it, which observed paths it does not declare (outside its open
 * subtrees) and which declared paths were not observed. External knowledge informs interpretation and
 * missing-scenario tests; it neither filters discovery nor defines Journal's support boundary.
 */
public final class ExternalSchemaPaths {

    public static final String KIND = "agent-journal/external-description-comparison";

    private ExternalSchemaPaths() {
    }

    /** Declared paths and open subtrees of one alternative of the external schema. */
    public record Declared(String discriminatorValue, Set<String> paths, Set<String> open) {
    }

    /** Every alternative of the schema keyed by its discriminator const (property {@code discriminator}). */
    public static Map<String, Declared> declared(Json schema, String discriminator) {
        Map<String, Declared> out = new TreeMap<>();
        for (Json alt : alternatives(schema, schema)) {
            Json resolved = resolve(schema, alt);
            Json c = resolved.path("properties", discriminator, "const");
            String value = c == null ? null : c.scalarText();
            if (value == null) {
                Json en = resolved.path("properties", discriminator, "enum");
                if (en != null && en.elements().size() == 1) {
                    value = en.elements().get(0).scalarText();
                }
            }
            if (value == null) {
                continue;
            }
            Set<String> paths = new TreeSet<>();
            Set<String> open = new TreeSet<>();
            collect(schema, resolved, "", paths, open, new ArrayList<>());
            Declared existing = out.get(value);
            if (existing != null) {
                paths.addAll(existing.paths());
                open.addAll(existing.open());
            }
            out.put(value, new Declared(value, paths, open));
        }
        return out;
    }

    private static List<Json> alternatives(Json root, Json node) {
        List<Json> out = new ArrayList<>();
        Json r = resolve(root, node);
        for (String key : List.of("anyOf", "oneOf")) {
            if (r.member(key) != null) {
                for (Json alt : r.member(key).elements()) {
                    out.addAll(alternatives(root, alt));
                }
            }
        }
        if (out.isEmpty()) {
            out.add(r);
        }
        return out;
    }

    static Json resolve(Json root, Json node) {
        Json ref = node == null ? null : node.member("$ref");
        if (ref == null || ref.text() == null || !ref.text().startsWith("#/")) {
            return node;
        }
        Json current = root;
        for (String seg : ref.text().substring(2).split("/")) {
            current = current == null ? null : current.member(seg.replace("~1", "/").replace("~0", "~"));
        }
        return current == null ? node : resolve(root, current);
    }

    private static void collect(Json root, Json node, String path, Set<String> paths, Set<String> open, List<Json> stack) {
        Json r = resolve(root, node);
        if (r == null || stack.contains(r)) {
            return;
        }
        stack.add(r);
        for (String key : List.of("anyOf", "oneOf", "allOf")) {
            if (r.member(key) != null) {
                for (Json alt : r.member(key).elements()) {
                    collect(root, alt, path, paths, open, stack);
                }
            }
        }
        Json props = r.member("properties");
        boolean isObject = "object".equals(RawInventory.text(r, "type")) || props != null;
        if (isObject) {
            Json ap = r.member("additionalProperties");
            boolean closed = ap instanceof Json.JBool b && !b.value();
            if (!closed || props == null) {
                open.add(path);
            }
            if (props instanceof Json.JObject po) {
                for (Map.Entry<String, Json> p : po.members().entrySet()) {
                    String child = path.isEmpty() ? p.getKey() : path + "." + p.getKey();
                    paths.add(child);
                    collect(root, p.getValue(), child, paths, open, stack);
                }
            }
            if (ap instanceof Json.JObject) {
                collect(root, ap, path + ".{*}", paths, open, stack);
            }
        }
        Json items = r.member("items");
        if (items != null) {
            collect(root, items, path + "[]", paths, open, stack);
        }
        if (r.member("prefixItems") != null) {
            for (Json it : r.member("prefixItems").elements()) {
                collect(root, it, path + "[]", paths, open, stack);
            }
        }
        stack.remove(stack.size() - 1);
    }

    /**
     * @param discriminator the external schema's discriminator property (for example {@code type})
     * @param inventoryDiscriminator the inventory discriminator key whose value selects the external alternative
     */
    public static Json compare(Json schema, String discriminator, Json inventory, String inventoryDiscriminator, String externalName) {
        Map<String, Declared> declared = declared(schema, discriminator);
        List<Json> rows = new ArrayList<>();
        int described = 0;
        int undescribed = 0;
        int observedNotDeclared = 0;
        for (Map.Entry<String, Json> v : ((Json.JObject) inventory.member("variants")).members().entrySet()) {
            if (v.getKey().startsWith("(opaque")) {
                continue;
            }
            Json dv = v.getValue().path("discriminators", inventoryDiscriminator);
            String value = dv == null || dv.isNull() ? null : dv.scalarText();
            Declared d = value == null ? null : declared.get(value);
            Map<String, Json> row = new LinkedHashMap<>();
            row.put("variant", RawInventory.str(v.getKey()));
            row.put("discriminator_value", value == null ? Json.JNull.INSTANCE : RawInventory.str(value));
            row.put("records", v.getValue().member("records"));
            row.put("evidence", v.getValue().member("evidence"));
            if (d == null) {
                undescribed++;
                row.put("described", new Json.JBool(false));
                row.put("note", RawInventory.str("the external description has no alternative for this discriminator value: not evidence that the records are wrong, nor that the description covers this version"));
                rows.add(new Json.JObject(row));
                continue;
            }
            described++;
            row.put("described", new Json.JBool(true));
            List<Json> notDeclared = new ArrayList<>();
            Set<String> observedPaths = ((Json.JObject) v.getValue().member("paths")).members().keySet();
            for (String p : observedPaths) {
                if (p.endsWith("[]") || p.contains("[].{*}") || p.endsWith(".{*}")) {
                    continue; // array element and wildcard entries are implied by their container
                }
                if (!d.paths().contains(p) && !underOpen(p, d.open())) {
                    notDeclared.add(RawInventory.str(p));
                    observedNotDeclared++;
                }
            }
            List<Json> notObserved = new ArrayList<>();
            for (String p : d.paths()) {
                if (!observedPaths.contains(p) && !observedPaths.contains(p + "[]")) {
                    notObserved.add(RawInventory.str(p));
                }
            }
            row.put("observed_not_declared", new Json.JArray(notDeclared));
            row.put("declared_not_observed", new Json.JArray(notObserved));
            row.put("open_subtrees_in_external", RawInventory.strings(d.open()));
            rows.add(new Json.JObject(row));
        }
        Map<String, Json> out = new LinkedHashMap<>();
        out.put("kind", RawInventory.str(KIND));
        out.put("external", RawInventory.str(externalName));
        out.put("external_discriminator", RawInventory.str(discriminator));
        out.put("profile", inventory.member("profile"));
        out.put("artifact", inventory.member("artifact"));
        out.put("artifact_sha256", inventory.member("sha256"));
        out.put("variants_described", RawInventory.num(described));
        out.put("variants_not_described", RawInventory.num(undescribed));
        out.put("observed_paths_not_declared", RawInventory.num(observedNotDeclared));
        out.put("external_alternatives", RawInventory.strings(declared.keySet()));
        out.put("rows", new Json.JArray(rows));
        out.put("meaning", RawInventory.str("a cross-check only: the external description informs interpretation and missing-scenario cases; it neither filters Journal's inventory nor defines the supported versions; "
                + "'not described' may mean a version, mode or coverage difference of the description, not a defect of the records"));
        return new Json.JObject(out);
    }

    private static boolean underOpen(String path, Set<String> open) {
        for (String o : open) {
            if (!o.isEmpty() && (path.startsWith(o + ".") || path.startsWith(o + "["))) {
                return true;
            }
        }
        return false;
    }
}
