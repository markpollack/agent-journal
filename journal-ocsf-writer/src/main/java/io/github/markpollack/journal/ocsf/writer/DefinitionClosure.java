package io.github.markpollack.journal.ocsf.writer;

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
 * The data-definition closure a core embeds, computed from the pinned compiled schema and the events written so
 * far. Policy {@code WRITTEN}: the written classes keep every attribute our extension defines plus the core
 * attributes present in the saved events or required; extension objects are kept complete; core objects keep
 * present-or-required attributes; profile-only attributes of profiles the events do not enable are omitted; every
 * object a kept attribute refers to is included (fixpoint); the scalar types used are listed. Policy
 * {@code FULL_REACHED} keeps every attribute of every reachable definition and is only measured.
 *
 * <p>The writer feeds events through {@link Collector#collect} as it emits them, so the closure of an open package
 * is computed at every flush from per-definition attribute-name sets, never from a retained list of events.
 */
public final class DefinitionClosure {

    public enum Policy {
        WRITTEN, FULL_REACHED
    }

    /** Running record of which attributes the written events used, per class/object definition, and which profiles. */
    public static final class Collector {
        private final Jv compiled;
        private final Jv.Obj classes;
        private final Jv.Obj objects;
        private final Map<Integer, String> keyByUid = new LinkedHashMap<>();
        final Set<String> profiles = new LinkedHashSet<>();
        final Map<String, Set<String>> present = new LinkedHashMap<>();

        public Collector(Jv compiled) {
            this.compiled = compiled;
            this.classes = (Jv.Obj) compiled.member("classes");
            this.objects = (Jv.Obj) compiled.member("objects");
            for (Map.Entry<String, Jv> e : classes.members().entrySet()) {
                if (e.getValue().member("uid") instanceof Jv.Num n) {
                    keyByUid.put((int) n.value().longValueExact(), e.getKey());
                }
            }
        }

        public void collect(Jv event) {
            Jv ps = event.path("metadata", "profiles");
            for (Jv pr : ps == null ? List.<Jv>of() : ps.elements()) {
                if (pr.text() != null) {
                    profiles.add(pr.text());
                }
            }
            String key = event.member("class_uid") instanceof Jv.Num n ? keyByUid.get((int) n.value().longValueExact()) : null;
            if (key != null) {
                collect(event, classes.get(key), key);
            }
        }

        private void collect(Jv value, Jv def, String key) {
            if (!(value instanceof Jv.Obj o) || def == null) {
                return;
            }
            Set<String> names = present.computeIfAbsent(key, k -> new LinkedHashSet<>());
            Jv.Obj attrs = (Jv.Obj) def.member("attributes");
            for (Map.Entry<String, Jv> m : o.members().entrySet()) {
                Jv attr = attrs.get(m.getKey());
                if (attr == null) {
                    continue;
                }
                names.add(m.getKey());
                String ot = text(attr, "object_type");
                if (ot != null) {
                    Jv od = objects.get(ot);
                    if (attr.member("is_array") instanceof Jv.Bool b && b.value()) {
                        for (Jv el : m.getValue().elements()) {
                            collect(el, od, ot);
                        }
                    }
                    else {
                        collect(m.getValue(), od, ot);
                    }
                }
            }
        }

        public Jv build(Map<String, Integer> classUids, Policy policy) {
            return DefinitionClosure.build(compiled, classUids, profiles, present, policy);
        }
    }

    private DefinitionClosure() {
    }

    static Jv build(Jv compiled, Map<String, Integer> classUids, Set<String> profiles, Map<String, Set<String>> present, Policy policy) {
        Jv.Obj classes = (Jv.Obj) compiled.member("classes");
        Jv.Obj objects = (Jv.Obj) compiled.member("objects");
        Jv.Obj typeDefs = (Jv.Obj) compiled.path("dictionary", "types", "attributes");
        Map<Integer, String> keyByUid = new LinkedHashMap<>();
        for (Map.Entry<String, Jv> e : classes.members().entrySet()) {
            if (e.getValue().member("uid") instanceof Jv.Num n) {
                keyByUid.put((int) n.value().longValueExact(), e.getKey());
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
        String extension = SchemaPin.EXTENSION;
        Map<String, Jv> outClasses = new LinkedHashMap<>();
        Map<String, Jv> outObjects = new TreeMap<>();
        Map<String, Jv> outTypes = new TreeMap<>();
        Deque<String> todo = new ArrayDeque<>(classKeyByName.values());
        Set<String> seen = new LinkedHashSet<>();
        while (!todo.isEmpty()) {
            String key = todo.pop();
            if (!seen.add(key)) {
                continue;
            }
            boolean isClass = classKeyByName.containsValue(key);
            Jv def = isClass ? classes.get(key) : objects.get(key);
            if (def == null) {
                continue;
            }
            boolean extensionObject = !isClass && key.startsWith(extension + "/");
            Set<String> keep = present.getOrDefault(key, Set.of());
            Map<String, Jv> attrs = new LinkedHashMap<>();
            int omittedCore = 0;
            int omittedProfile = 0;
            for (Map.Entry<String, Jv> a : ((Jv.Obj) def.member("attributes")).members().entrySet()) {
                Jv attr = a.getValue();
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
                Jv.Obj copy = new Jv.Obj();
                for (Map.Entry<String, Jv> f : ((Jv.Obj) attr).members().entrySet()) {
                    if (!"description".equals(f.getKey())) {
                        copy.put(f.getKey(), f.getValue());
                    }
                }
                attrs.put(a.getKey(), copy);
                String ot = text(attr, "object_type");
                if (ot != null) {
                    todo.push(ot);
                }
                String t = text(attr, "type");
                if (t != null && !"object_t".equals(t) && typeDefs.get(t) != null) {
                    outTypes.put(t, new Jv.Str(text(typeDefs.get(t), "caption")));
                }
            }
            Jv.Obj out = new Jv.Obj();
            for (String k : List.of("caption", "name", "uid", "category_uid", "extends", "extension", "extension_id")) {
                if (def.member(k) != null) {
                    out.put(k, def.member(k));
                }
            }
            out.put("attributes", new Jv.Obj(attrs));
            if (omittedCore > 0) {
                out.put("omitted_optional_core_attributes", omittedCore);
            }
            if (omittedProfile > 0) {
                out.put("omitted_profile_attributes_not_enabled", omittedProfile);
            }
            if (isClass) {
                outClasses.put(key, out);
            }
            else {
                outObjects.put(key, out);
            }
        }
        Jv.Obj out = new Jv.Obj();
        out.put("policy", policy == Policy.WRITTEN
                ? "WRITTEN: written classes keep every extension-defined attribute plus core attributes present in the saved events or required; extension objects complete; core objects present-or-required; profile-only attributes of profiles the events do not enable omitted; every referenced object included; scalar types used listed"
                : "FULL_REACHED: every attribute of the written classes and every object reachable from them");
        Jv.Arr profs = new Jv.Arr();
        for (String p : profiles) {
            profs.add(new Jv.Str(p));
        }
        out.put("enabled_profiles", profs);
        out.put("classes", new Jv.Obj(outClasses));
        out.put("objects", new Jv.Obj(outObjects));
        out.put("types", new Jv.Obj(outTypes));
        return out;
    }

    private static boolean profileEnabled(Jv attr, Set<String> profiles) {
        Jv ps = attr.member("profiles");
        if (!(ps instanceof Jv.Arr a) || a.elements().isEmpty()) {
            return true;
        }
        for (Jv p : a.elements()) {
            if (profiles.contains(p.text())) {
                return true;
            }
        }
        return false;
    }

    static String text(Jv json, String key) {
        Jv v = json == null ? null : json.member(key);
        return v == null ? null : v.text();
    }

    /** Canonical bytes of a closure document, for its sha256 and size. */
    public static byte[] bytes(Jv closure) {
        return Jv.write(closure).getBytes(StandardCharsets.UTF_8);
    }
}
