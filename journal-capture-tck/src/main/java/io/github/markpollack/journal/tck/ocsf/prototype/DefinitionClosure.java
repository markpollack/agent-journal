package io.github.markpollack.journal.tck.ocsf.prototype;

import io.github.markpollack.journal.tck.verify.Json;
import io.github.markpollack.journal.tck.verify.NeutralJson;

import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * NON-PRODUCTION candidate support: computes the data-definition closure a run package embeds, from the pinned
 * compiled schema (output of {@code ocsf-schema-compiler}) and the events actually written.
 *
 * <p>Two policies are measured. {@code WRITTEN}: the written classes keep every attribute our extension defines (so
 * the semantic contract can target an attribute no record of this run happened to write) plus the core attributes
 * that are present in the saved events or required; extension objects are kept complete; core objects keep
 * present-or-required attributes; profile-only attributes of profiles the events do not enable are omitted; every
 * object a kept attribute refers to is included (fixpoint), so the closure is reference-complete; the scalar types
 * used are listed. {@code FULL_REACHED}: every attribute of the written classes and of every object reachable from
 * them (the compiler's closure through metadata/product/ai_agent). The package embeds {@code WRITTEN} and records
 * both sizes; the whole compiled schema is referenced by hash only.
 */
public final class DefinitionClosure {

    public enum Policy {
        WRITTEN, FULL_REACHED
    }

    private DefinitionClosure() {
    }

    /**
     * @param compiled the compiled schema document
     * @param classUids written class name to class_uid (the pin table)
     * @param events the saved events
     */
    public static Json build(Json compiled, Map<String, Integer> classUids, List<Json> events, Policy policy) {
        Json.JObject classes = (Json.JObject) compiled.member("classes");
        Json.JObject objects = (Json.JObject) compiled.member("objects");
        Json.JObject typeDefs = (Json.JObject) compiled.member("dictionary").member("types").member("attributes");
        Map<Integer, String> keyByUid = new LinkedHashMap<>();
        for (Map.Entry<String, Json> e : classes.members().entrySet()) {
            if (e.getValue().member("uid") instanceof Json.JNumber n) {
                keyByUid.put(n.value().intValueExact(), e.getKey());
            }
        }
        Map<String, String> classKeyByName = new LinkedHashMap<>();
        for (Map.Entry<String, Integer> e : classUids.entrySet()) {
            String key = keyByUid.get(e.getValue());
            if (key == null) {
                throw new IllegalArgumentException("class " + e.getKey() + " uid " + e.getValue() + " is not in the compiled schema");
            }
            classKeyByName.put(e.getKey(), key);
        }
        String extension = classKeyByName.isEmpty() ? "" : classKeyByName.values().iterator().next().split("/")[0];

        // profiles the events enable; profile-only attributes outside them are not part of the written model
        Set<String> profiles = new LinkedHashSet<>();
        Map<String, Set<String>> present = new LinkedHashMap<>();
        for (Json event : events) {
            Json ps = event.path("metadata", "profiles");
            for (Json pr : ps == null ? List.<Json>of() : ps.elements()) {
                if (pr.text() != null) {
                    profiles.add(pr.text());
                }
            }
            String key = event.member("class_uid") instanceof Json.JNumber n ? keyByUid.get(n.value().intValueExact()) : null;
            if (key != null) {
                collect(event, classes.get(key), key, objects, present);
            }
        }

        // fixpoint over kept attributes: every referenced object is included
        Map<String, Json> outClasses = new LinkedHashMap<>();
        Map<String, Json> outObjects = new TreeMap<>();
        Map<String, Json> outTypes = new TreeMap<>();
        Deque<String> todo = new ArrayDeque<>(classKeyByName.values());
        Set<String> seen = new LinkedHashSet<>();
        while (!todo.isEmpty()) {
            String key = todo.pop();
            if (!seen.add(key)) {
                continue;
            }
            boolean isClass = classKeyByName.containsValue(key);
            Json def = isClass ? classes.get(key) : objects.get(key);
            if (def == null) {
                continue;
            }
            boolean extensionObject = !isClass && key.startsWith(extension + "/");
            Set<String> keep = present.getOrDefault(key, Set.of());
            Map<String, Json> attrs = new LinkedHashMap<>();
            int omittedCore = 0;
            int omittedProfile = 0;
            for (Map.Entry<String, Json> a : ((Json.JObject) def.member("attributes")).members().entrySet()) {
                Json attr = a.getValue();
                boolean required = "required".equals(text(attr, "requirement"));
                boolean ours = extensionObject || extension.equals(text(attr, "extension"));
                boolean enabled = profileEnabled(attr, profiles);
                boolean keepIt = policy == Policy.FULL_REACHED
                        || (enabled && (keep.contains(a.getKey()) || required || ours))
                        || keep.contains(a.getKey());
                if (!keepIt) {
                    if (enabled) {
                        omittedCore++;
                    }
                    else {
                        omittedProfile++;
                    }
                    continue;
                }
                Map<String, Json> copy = new LinkedHashMap<>();
                for (Map.Entry<String, Json> f : ((Json.JObject) attr).members().entrySet()) {
                    if (!"description".equals(f.getKey())) {
                        copy.put(f.getKey(), f.getValue());
                    }
                }
                attrs.put(a.getKey(), new Json.JObject(copy));
                String ot = text(attr, "object_type");
                if (ot != null) {
                    todo.push(ot);
                }
                String t = text(attr, "type");
                if (t != null && !"object_t".equals(t) && typeDefs.get(t) != null) {
                    outTypes.put(t, new Json.JString(text(typeDefs.get(t), "caption")));
                }
            }
            Map<String, Json> out = new LinkedHashMap<>();
            for (String k : List.of("caption", "name", "uid", "category_uid", "extends", "extension", "extension_id")) {
                if (def.member(k) != null) {
                    out.put(k, def.member(k));
                }
            }
            out.put("attributes", new Json.JObject(attrs));
            if (omittedCore > 0) {
                out.put("omitted_optional_core_attributes", new Json.JNumber(Integer.toString(omittedCore)));
            }
            if (omittedProfile > 0) {
                out.put("omitted_profile_attributes_not_enabled", new Json.JNumber(Integer.toString(omittedProfile)));
            }
            if (isClass) {
                outClasses.put(key, new Json.JObject(out));
            }
            else {
                outObjects.put(key, new Json.JObject(out));
            }
        }
        Map<String, Json> out = new LinkedHashMap<>();
        out.put("policy", new Json.JString(policy == Policy.WRITTEN
                ? "WRITTEN: written classes keep every extension-defined attribute plus core attributes present in the saved events or required; extension objects complete; core objects present-or-required; profile-only attributes of profiles the events do not enable omitted; every referenced object included; scalar types used listed"
                : "FULL_REACHED: every attribute of the written classes and every object reachable from them"));
        out.put("enabled_profiles", new Json.JArray(profiles.stream().map(p -> (Json) new Json.JString(p)).toList()));
        out.put("classes", new Json.JObject(outClasses));
        out.put("objects", new Json.JObject(outObjects));
        out.put("types", new Json.JObject(outTypes));
        return new Json.JObject(out);
    }

    private static boolean profileEnabled(Json attr, Set<String> profiles) {
        Json ps = attr.member("profiles");
        if (!(ps instanceof Json.JArray a) || a.elements().isEmpty()) {
            return true;
        }
        for (Json p : a.elements()) {
            if (profiles.contains(p.text())) {
                return true;
            }
        }
        return false;
    }

    private static void collect(Json value, Json def, String key, Json.JObject objects, Map<String, Set<String>> present) {
        if (!(value instanceof Json.JObject o) || def == null) {
            return;
        }
        Set<String> names = present.computeIfAbsent(key, k -> new LinkedHashSet<>());
        Json.JObject attrs = (Json.JObject) def.member("attributes");
        for (Map.Entry<String, Json> m : o.members().entrySet()) {
            Json attr = attrs.get(m.getKey());
            if (attr == null) {
                continue; // unknown attribute: the reader reports it; it cannot be described
            }
            names.add(m.getKey());
            String ot = text(attr, "object_type");
            if (ot != null) {
                Json od = objects.get(ot);
                if (attr.member("is_array") instanceof Json.JBool b && b.value()) {
                    for (Json el : m.getValue().elements()) {
                        collect(el, od, ot, objects, present);
                    }
                }
                else {
                    collect(m.getValue(), od, ot, objects, present);
                }
            }
        }
    }

    static String text(Json json, String key) {
        Json v = json == null ? null : json.member(key);
        return v == null ? null : v.text();
    }

    /** Canonical bytes of a closure document, for its sha256 and size. */
    public static byte[] bytes(Json closure) {
        return NeutralJson.write(closure).getBytes(StandardCharsets.UTF_8);
    }
}
