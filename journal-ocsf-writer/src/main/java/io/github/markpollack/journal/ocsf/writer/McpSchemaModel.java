package io.github.markpollack.journal.ocsf.writer;

import io.github.markpollack.journal.ocsf.writer.McpNode.ArrayNode;
import io.github.markpollack.journal.ocsf.writer.McpNode.JsonNode;
import io.github.markpollack.journal.ocsf.writer.McpNode.Ref;
import io.github.markpollack.journal.ocsf.writer.McpNode.Scalar;
import io.github.markpollack.journal.ocsf.writer.McpNode.ScalarUnion;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * The pinned MCP JSON Schemas resolved into binding nodes and generated object shapes, per revision. A definition that
 * only refers to another ({@code EmptyResult}) is that other; an object with properties, an {@code allOf} and a union of
 * objects become generated objects; a map whose values have a schema becomes entry objects; everything else is a
 * scalar, a scalar union or free JSON. See {@link McpBindingGenerator} for the conventions.
 */
final class McpSchemaModel {

    record Member(String name, McpNode node, boolean required, String description) {
    }

    record Alternative(McpNode node, String label, Jv.Obj match) {
    }

    /** One generated object's shape in one revision. */
    static final class Shape {
        String location;
        String description;
        final Map<String, Member> members = new LinkedHashMap<>();
        boolean additional = true;
        final List<Alternative> alternatives = new ArrayList<>();
        McpNode mapValue;
    }

    static final class Generated {
        final String name;
        final String kind;
        final Map<String, Shape> revisions = new TreeMap<>();

        Generated(String name, String kind) {
            this.name = name;
            this.kind = kind;
        }
    }

    final Map<String, Jv.Obj> defsByRevision;
    final Map<String, Generated> objects = new TreeMap<>();
    final Map<String, Map<String, McpNode>> definitionNodes = new TreeMap<>();
    private final Map<String, McpNode> cache = new LinkedHashMap<>();
    private final Set<String> inProgress = new LinkedHashSet<>();

    McpSchemaModel(Map<String, Jv.Obj> defsByRevision) {
        this.defsByRevision = defsByRevision;
        for (Map.Entry<String, Jv.Obj> r : defsByRevision.entrySet()) {
            Map<String, McpNode> nodes = new TreeMap<>();
            for (String def : r.getValue().members().keySet()) {
                nodes.put(def, definition(r.getKey(), def));
            }
            definitionNodes.put(r.getKey(), nodes);
        }
    }

    /** The node a whole definition resolves to (aliases followed). */
    McpNode definition(String rev, String def) {
        String key = rev + "#" + def;
        McpNode cached = cache.get(key);
        if (cached != null) {
            return cached;
        }
        Jv schema = defsByRevision.get(rev).get(def);
        if (schema == null) {
            throw new IllegalStateException(rev + ": $ref to undefined definition " + def);
        }
        if (!inProgress.add(key)) {
            return reentered(rev, def);
        }
        try {
            McpNode n = alias(schema) ? definition(rev, refName(schema.member("$ref").text())) : resolve(rev, schema, def);
            cache.put(key, n);
            return n;
        }
        finally {
            inProgress.remove(key);
        }
    }

    /**
     * A definition reached again while it is being resolved: an object registers itself before its members, so
     * recursion through an object resolves to it; recursion through anything else (JSONValue through JSONObject) is
     * free JSON.
     */
    private McpNode reentered(String rev, String def) {
        Generated g = objects.get(McpBindingNames.objectName(def));
        return g != null && g.revisions.containsKey(rev) && "object".equals(g.kind) ? new Ref("object", g.name) : new JsonNode();
    }

    static boolean alias(Jv schema) {
        return schema.member("$ref") != null && ((Jv.Obj) schema).members().keySet().stream().allMatch(k -> k.equals("$ref") || k.equals("description"));
    }

    /**
     * Resolves one schema location. {@code path} names it: {@code Def.member} for an inline member, {@code []} for
     * array items, {@code {}} for map values, {@code |i} for a union alternative.
     */
    private McpNode resolve(String rev, Jv schema, String path) {
        if (!(schema instanceof Jv.Obj s)) {
            return new JsonNode();
        }
        if (s.get("$ref") != null) {
            return definition(rev, refName(s.get("$ref").text()));
        }
        String name = McpBindingNames.objectName(path);
        if (s.get("allOf") != null) {
            return object(rev, merged(rev, s), name, path);
        }
        if (s.get("anyOf") != null || s.get("oneOf") != null) {
            return union(rev, s, name, path);
        }
        return typed(rev, s, name, path);
    }

    private McpNode typed(String rev, Jv.Obj s, String name, String path) {
        Jv type = s.get("type");
        if (type instanceof Jv.Arr multi) {
            return scalarTypes(multi);
        }
        String t = type == null ? "" : type.text();
        if (McpNode.primitive(t)) {
            return new Scalar(t, s.get("const"), s.get("enum"), s.get("format") == null ? null : s.get("format").text());
        }
        return "array".equals(t) ? array(rev, s, path) : structured(rev, s, t, name, path);
    }

    private McpNode structured(String rev, Jv.Obj s, String type, String name, String path) {
        if (hasProperties(s)) {
            return object(rev, s, name, path);
        }
        return "object".equals(type) ? openObject(rev, s, name, path) : new JsonNode();
    }

    private static McpNode scalarTypes(Jv.Arr multi) {
        List<String> types = new ArrayList<>();
        for (Jv t : multi.elements()) {
            types.add(t.text());
        }
        return types.stream().allMatch(McpNode::primitive) ? new ScalarUnion(types) : new JsonNode();
    }

    private McpNode array(String rev, Jv.Obj s, String path) {
        McpNode items = s.get("items") == null ? new JsonNode() : resolve(rev, s.get("items"), path + "[]");
        boolean free = items instanceof JsonNode || items instanceof ArrayNode || (items instanceof Ref r && r.isMap());
        return free ? new JsonNode() : new ArrayNode(items); // an array of free JSON, of arrays or of maps is free JSON
    }

    /** An object without properties: a typed map when its additional members have a schema, free JSON otherwise. */
    private McpNode openObject(String rev, Jv.Obj s, String name, String path) {
        if (s.get("additionalProperties") instanceof Jv.Obj a && !a.members().isEmpty()) {
            McpNode value = resolve(rev, a, path + "{}");
            return value instanceof JsonNode ? value : map(rev, value, name, path, s);
        }
        return new JsonNode();
    }

    private static boolean hasProperties(Jv.Obj s) {
        return s.get("properties") instanceof Jv.Obj p && !p.members().isEmpty();
    }

    /**
     * The generated object of a name and kind; a definition that is an object in one pinned revision and a union in
     * another (ClientNotification) keeps the name in the earlier revision and a revision-qualified name in the later.
     */
    private Generated generated(String name, String kind, String rev) {
        Generated g = objects.computeIfAbsent(name, n -> new Generated(n, kind));
        if (g.kind.equals(kind)) {
            return g;
        }
        return objects.computeIfAbsent(name + "__" + rev.replace('-', '_'), n -> new Generated(n, kind));
    }

    private Shape shape(Generated g, String rev, Jv.Obj s, String path) {
        Shape shape = new Shape();
        g.revisions.put(rev, shape);
        shape.location = path;
        shape.description = s.get("description") == null ? null : s.get("description").text();
        return shape;
    }

    private Ref object(String rev, Jv.Obj s, String name, String path) {
        Generated g = generated(name, "object", rev);
        Ref ref = new Ref("object", g.name);
        if (g.revisions.containsKey(rev)) {
            return ref;
        }
        Shape shape = shape(g, rev, s, path);
        shape.additional = !(s.get("additionalProperties") instanceof Jv.Bool b && !b.value());
        Set<String> required = names(s.get("required"));
        if (s.get("properties") instanceof Jv.Obj p) {
            for (Map.Entry<String, Jv> m : p.members().entrySet()) {
                McpNode n = resolve(rev, m.getValue(), path + "." + m.getKey());
                Jv d = m.getValue().member("description");
                shape.members.put(m.getKey(), new Member(m.getKey(), n, required.contains(m.getKey()), d == null ? null : d.text()));
            }
        }
        return ref;
    }

    private McpNode union(String rev, Jv.Obj s, String name, String path) {
        Jv alts = s.get("anyOf") != null ? s.get("anyOf") : s.get("oneOf");
        List<McpNode> nodes = new ArrayList<>();
        int i = 0;
        for (Jv a : alts.elements()) {
            nodes.add(resolve(rev, a, path + "|" + i++));
        }
        if (nodes.stream().allMatch(n -> n instanceof Scalar || n instanceof ScalarUnion)) {
            return new ScalarUnion(scalarTypes(nodes)); // a union of scalars: json_t, its alternatives kept in the schema
        }
        if (nodes.stream().allMatch(n -> n instanceof JsonNode || n instanceof ScalarUnion || n instanceof Scalar)) {
            return new JsonNode(); // free JSON beside scalars (JSONValue)
        }
        Generated g = generated(name, "variant", rev);
        Ref ref = new Ref("variant", g.name);
        if (!g.revisions.containsKey(rev)) {
            Shape shape = shape(g, rev, s, path);
            for (int k = 0; k < nodes.size(); k++) {
                Jv alt = alts.elements().get(k);
                shape.alternatives.add(new Alternative(nodes.get(k), label(alt, nodes.get(k)), McpUnionMatch.of(this, rev, alt, nodes.get(k))));
            }
        }
        return ref;
    }

    private static List<String> scalarTypes(List<McpNode> nodes) {
        Set<String> types = new LinkedHashSet<>();
        for (McpNode n : nodes) {
            if (n instanceof Scalar sc) {
                types.add(sc.type());
            }
            else {
                types.addAll(((ScalarUnion) n).types());
            }
        }
        return List.copyOf(types);
    }

    private Ref map(String rev, McpNode value, String name, String path, Jv.Obj s) {
        Generated g = generated(name + "__entry", "map_entry", rev);
        if (!g.revisions.containsKey(rev)) {
            Shape shape = shape(g, rev, s, path);
            shape.mapValue = value;
            shape.additional = false;
        }
        return new Ref("map", g.name);
    }

    /** The members of an {@code allOf} merged into one object schema (see {@link McpAllOf}). */
    Jv.Obj merged(String rev, Jv.Obj s) {
        return new McpAllOf(defsByRevision.get(rev)).merged(s);
    }

    /** A schema with its references followed and its own allOf merged. */
    Jv.Obj dereference(String rev, Jv.Obj schema) {
        return new McpAllOf(defsByRevision.get(rev)).dereference(schema);
    }

    static Set<String> names(Jv array) {
        Set<String> out = new LinkedHashSet<>();
        for (Jv r : array == null ? List.<Jv>of() : array.elements()) {
            out.add(r.text());
        }
        return out;
    }

    private static String label(Jv schema, McpNode node) {
        Jv ref = schema.member("$ref");
        if (ref != null) {
            return refName(ref.text());
        }
        if (node instanceof Ref r) {
            return r.object();
        }
        if (node instanceof ArrayNode) {
            return "array";
        }
        if (node instanceof Scalar sc) {
            return sc.type();
        }
        return node instanceof ScalarUnion su ? String.join("|", su.types()) : "json";
    }

    static String refName(String ref) {
        String prefix = "#/$defs/";
        if (!ref.startsWith(prefix)) {
            throw new IllegalArgumentException("unsupported $ref " + ref);
        }
        return ref.substring(prefix.length());
    }
}
