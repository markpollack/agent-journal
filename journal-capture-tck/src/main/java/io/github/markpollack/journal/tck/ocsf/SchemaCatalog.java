package io.github.markpollack.journal.tck.ocsf;

import io.github.markpollack.journal.tck.verify.Json;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * The data definitions a run package carries: the written OCSF classes, the objects they reach and the scalar types
 * they use, in the compiled form the pinned {@code ocsf-schema-compiler} produced. A reader uses it to enumerate the
 * saved model, resolve schema paths named by the semantic contract, and report unknown attributes and type
 * mismatches in saved events without discarding anything. It never fetches a registry or loads production classes.
 */
public final class SchemaCatalog {

    /** One attribute of a class or object. */
    public record AttributeDef(String name, String type, String objectType, boolean isArray, String requirement,
            Map<String, String> enumCaptions, String caption) {
        public boolean isObject() {
            return objectType != null;
        }

        public boolean required() {
            return "required".equals(requirement);
        }
    }

    /** A class or object definition: its key as compiled (for example {@code agentic_cli/tool_call}) and attributes. */
    public record Definition(String key, String name, String caption, Integer uid, Map<String, AttributeDef> attributes) {
    }

    /** One finding of {@link #check}. */
    public record Finding(Kind kind, String path, String detail) {
        public enum Kind {
            UNKNOWN_ATTRIBUTE, TYPE_MISMATCH, ENUM_VALUE_UNKNOWN, MISSING_REQUIRED, MISSING_DEFINITION
        }

        @Override
        public String toString() {
            return kind + " at " + path + ": " + detail;
        }
    }

    private final Map<String, Definition> classes = new LinkedHashMap<>();
    private final Map<String, Definition> objects = new LinkedHashMap<>();
    private final Map<String, String> types = new LinkedHashMap<>();

    private SchemaCatalog() {
    }

    /**
     * Reads a closure document {@code {"classes": {key: def}, "objects": {key: def}, "types": {name: caption}}} as the
     * compiler emits definitions (attributes with {@code type}, {@code object_type}, {@code is_array},
     * {@code requirement}, {@code enum}).
     */
    public static SchemaCatalog fromClosure(Json closure) {
        SchemaCatalog c = new SchemaCatalog();
        Json cls = closure.member("classes");
        if (cls instanceof Json.JObject o) {
            o.members().forEach((k, v) -> c.classes.put(k, definition(k, v)));
        }
        Json objs = closure.member("objects");
        if (objs instanceof Json.JObject o) {
            o.members().forEach((k, v) -> c.objects.put(k, definition(k, v)));
        }
        Json ts = closure.member("types");
        if (ts instanceof Json.JObject o) {
            o.members().forEach((k, v) -> c.types.put(k, v.text() == null ? text(v, "caption") : v.text()));
        }
        return c;
    }

    static Definition definition(String key, Json def) {
        Map<String, AttributeDef> attrs = new LinkedHashMap<>();
        Json a = def.member("attributes");
        if (a instanceof Json.JObject o) {
            o.members().forEach((name, v) -> attrs.put(name, attribute(name, v)));
        }
        Json uid = def.member("uid");
        return new Definition(key, text(def, "name"), text(def, "caption"),
                uid instanceof Json.JNumber n ? n.value().intValueExact() : null, attrs);
    }

    static AttributeDef attribute(String name, Json v) {
        Map<String, String> captions = null;
        Json e = v.member("enum");
        if (e instanceof Json.JObject eo) {
            captions = new TreeMap<>();
            for (Map.Entry<String, Json> entry : eo.members().entrySet()) {
                captions.put(entry.getKey(), text(entry.getValue(), "caption"));
            }
        }
        Json arr = v.member("is_array");
        return new AttributeDef(name, text(v, "type"), text(v, "object_type"),
                arr instanceof Json.JBool b && b.value(), text(v, "requirement"), captions, text(v, "caption"));
    }

    static String text(Json json, String key) {
        Json v = json == null ? null : json.member(key);
        return v == null ? null : v.text();
    }

    public Map<String, Definition> classes() {
        return classes;
    }

    public Map<String, Definition> objects() {
        return objects;
    }

    public Map<String, String> types() {
        return types;
    }

    /** A class by compiled key or by bare name ({@code tool_call}). */
    public Definition classNamed(String name) {
        Definition d = classes.get(name);
        if (d != null) {
            return d;
        }
        for (Definition c : classes.values()) {
            if (name.equals(c.name())) {
                return c;
            }
        }
        return null;
    }

    public Definition object(String key) {
        return objects.get(key);
    }

    /**
     * Resolves a schema path of the form {@code <class>.<attr>(.<attr>|[])*}; the root may be {@code *} for every
     * written class (the path must then resolve on each). Returns the attribute reached, or throws with the first
     * failing step, so a semantic declaration with a wrong target fails loudly.
     */
    public AttributeDef resolve(String path) {
        String[] parts = path.split("\\.");
        if (parts.length < 2) {
            throw new IllegalArgumentException("schema path needs <class>.<attribute>: " + path);
        }
        List<Definition> roots = new ArrayList<>();
        if ("*".equals(parts[0])) {
            roots.addAll(classes.values());
        }
        else {
            Definition root = classNamed(parts[0]);
            if (root == null) {
                throw new IllegalArgumentException("unknown class " + parts[0] + " in " + path);
            }
            roots.add(root);
        }
        // a named class must resolve; "*" resolves when at least one written class carries the path
        AttributeDef last = null;
        IllegalArgumentException failure = null;
        for (Definition root : roots) {
            try {
                last = resolveFrom(root, parts, path);
            }
            catch (IllegalArgumentException e) {
                failure = e;
                if (roots.size() == 1) {
                    throw e;
                }
            }
        }
        if (last == null && failure != null) {
            throw failure;
        }
        return last;
    }

    private AttributeDef resolveFrom(Definition root, String[] parts, String path) {
        Definition current = root;
        AttributeDef last = null;
        for (int i = 1; i < parts.length; i++) {
            String step = parts[i];
            boolean array = step.endsWith("[]");
            String attrName = array ? step.substring(0, step.length() - 2) : step;
            AttributeDef attr = current.attributes().get(attrName);
            if (attr == null) {
                throw new IllegalArgumentException("unknown attribute " + attrName + " on " + current.key() + " in " + path);
            }
            if (array && !attr.isArray()) {
                throw new IllegalArgumentException(attrName + " is not an array in " + path);
            }
            last = attr;
            if (i < parts.length - 1) {
                if (!attr.isObject()) {
                    throw new IllegalArgumentException(attrName + " is a scalar; cannot descend in " + path);
                }
                current = objects.get(attr.objectType());
                if (current == null) {
                    throw new IllegalArgumentException("object " + attr.objectType() + " is not in the closure (" + path + ")");
                }
            }
        }
        return last;
    }

    /** Checks one saved event against the class definition: unknown attributes, type and enum mismatches, missing required. */
    public List<Finding> check(Json event, Definition classDef) {
        List<Finding> out = new ArrayList<>();
        checkObject(event, classDef, "$", out);
        return out;
    }

    private void checkObject(Json value, Definition def, String path, List<Finding> out) {
        if (!(value instanceof Json.JObject o)) {
            out.add(new Finding(Finding.Kind.TYPE_MISMATCH, path, "expected object " + def.key() + ", found " + Json.kindOf(value)));
            return;
        }
        if ("object".equals(def.key()) && def.attributes().isEmpty()) {
            return; // OCSF's free-form base object (for example unmapped): any members are allowed
        }
        for (Map.Entry<String, Json> m : o.members().entrySet()) {
            AttributeDef attr = def.attributes().get(m.getKey());
            String p = path + "." + m.getKey();
            if (attr == null) {
                out.add(new Finding(Finding.Kind.UNKNOWN_ATTRIBUTE, p, "not defined on " + def.key() + " (" + Json.kindOf(m.getValue()) + " kept)"));
                continue;
            }
            Json v = m.getValue();
            if (attr.isArray()) {
                if (!(v instanceof Json.JArray a)) {
                    out.add(new Finding(Finding.Kind.TYPE_MISMATCH, p, "expected array of " + attr.type() + ", found " + Json.kindOf(v)));
                    continue;
                }
                for (int i = 0; i < a.elements().size(); i++) {
                    checkScalarOrObject(a.elements().get(i), attr, p + "[" + i + "]", out);
                }
            }
            else {
                checkScalarOrObject(v, attr, p, out);
            }
        }
        for (AttributeDef attr : def.attributes().values()) {
            if (attr.required() && !o.members().containsKey(attr.name())) {
                out.add(new Finding(Finding.Kind.MISSING_REQUIRED, path + "." + attr.name(), "required by " + def.key()));
            }
        }
    }

    private void checkScalarOrObject(Json v, AttributeDef attr, String p, List<Finding> out) {
        if (attr.isObject()) {
            Definition od = objects.get(attr.objectType());
            if (od == null) {
                out.add(new Finding(Finding.Kind.MISSING_DEFINITION, p, "object " + attr.objectType() + " is not in the closure"));
                return;
            }
            checkObject(v, od, p, out);
            return;
        }
        String t = attr.type() == null ? "" : attr.type();
        boolean ok;
        switch (t) {
            case "integer_t", "long_t", "timestamp_t", "port_t" -> ok = v instanceof Json.JNumber n && n.value().scale() <= 0
                    || v instanceof Json.JNumber n2 && n2.value().stripTrailingZeros().scale() <= 0;
            case "float_t" -> ok = v instanceof Json.JNumber;
            case "boolean_t" -> ok = v instanceof Json.JBool;
            case "json_t" -> ok = true;
            case "" -> ok = true;
            default -> ok = v instanceof Json.JString || v.isNull();
        }
        if (!ok) {
            out.add(new Finding(Finding.Kind.TYPE_MISMATCH, p, "expected " + t + ", found " + Json.kindOf(v)
                    + (v instanceof Json.JNumber n ? " " + n.text() : "")));
            return;
        }
        if (attr.enumCaptions() != null && v instanceof Json.JNumber n && !attr.enumCaptions().containsKey(n.text())) {
            out.add(new Finding(Finding.Kind.ENUM_VALUE_UNKNOWN, p, n.text() + " not in " + attr.enumCaptions().keySet()));
        }
    }

    /** A plain-text enumeration of the saved model: classes, their attributes and types, reached objects and enums. */
    public String enumerate() {
        StringBuilder sb = new StringBuilder();
        for (Definition c : classes.values()) {
            sb.append("class ").append(c.key()).append(c.uid() == null ? "" : " uid " + c.uid()).append('\n');
            appendAttributes(sb, c);
        }
        for (Definition o : objects.values()) {
            sb.append("object ").append(o.key()).append('\n');
            appendAttributes(sb, o);
        }
        sb.append("types ").append(types.keySet()).append('\n');
        return sb.toString();
    }

    private static void appendAttributes(StringBuilder sb, Definition d) {
        for (AttributeDef a : d.attributes().values()) {
            sb.append("  ").append(a.name()).append(": ").append(a.isObject() ? a.objectType() : a.type())
                    .append(a.isArray() ? "[]" : "").append(" (").append(a.requirement()).append(')');
            if (a.enumCaptions() != null) {
                sb.append(" enum ").append(a.enumCaptions());
            }
            sb.append('\n');
        }
    }
}
