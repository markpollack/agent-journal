package io.github.markpollack.journal.tck.ocsf;

import io.github.markpollack.journal.tck.schema.McpValidation;
import io.github.markpollack.journal.tck.verify.Json;
import io.github.markpollack.journal.tck.verify.NeutralJson;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * The reader side of Journal's MCP protocol binding, written independently of the writer's embedding: it reads the
 * {@code protocol_binding} declarations of an event file (the pinned official JSON Schema definitions of the reference
 * closure and the binding shapes), extracts each typed {@code mcp_protocol_object} back into the protocol's own JSON,
 * and validates it against the pinned definition: a complete object as the schema requires, a partial projection with
 * only the required members it declares missing relaxed. It never consults the native source or a network schema.
 */
public final class McpProtocol {

    static final String ADDITIONAL = "mcp_additional_members";
    static final String NONCONFORMING = "mcp_nonconforming_members";
    static final String UNRESOLVED = "mcp_unresolved";

    /** What a file declared for one revision: official definitions, binding definition nodes and object shapes. */
    public record Binding(String revision, String dialect, Map<String, Json> schemaDefinitions, Map<String, Json> definitions, Map<String, Json> shapes) {
    }

    /** One protocol object extracted from an event: its definition, revision, projection and the protocol JSON. */
    public record Extracted(String definition, String revision, int projection, List<String> missingRequired, Json value) {
    }

    private McpProtocol() {
    }

    /** The bindings an event file declared, merged per revision in declaration order (amendments add definitions). */
    public static Map<String, Binding> fromDeclarations(DeclaredFile file) {
        Map<String, Binding> out = new TreeMap<>();
        if (file == null) {
            return out; // a historical two-file package declares nothing in its event file
        }
        for (DeclaredFile.Declaration d : file.declarations) {
            if (!"protocol_binding".equals(d.kind()) || d.payload() == null) {
                continue;
            }
            Json p = d.payload();
            String rev = p.member("revision").text();
            Binding b = out.computeIfAbsent(rev, r -> new Binding(r, p.member("dialect").text(), new TreeMap<>(), new TreeMap<>(), new TreeMap<>()));
            addAll(b.schemaDefinitions(), p.member("schema_definitions"));
            addAll(b.definitions(), p.path("binding", "definitions"));
            if (p.path("binding", "objects") instanceof Json.JObject objs) {
                for (Map.Entry<String, Json> o : objs.members().entrySet()) {
                    b.shapes().put(o.getKey(), o.getValue());
                }
            }
        }
        return out;
    }

    /**
     * A binding for one revision from the committed binding table and the pinned schema.json: for checks of the
     * binding itself (official examples, synthetic instances) where no event file is involved.
     */
    public static Binding fromBindingTable(Json bindingTable, Json officialSchema, String revision) {
        Map<String, Json> defs = new TreeMap<>();
        addAll(defs, bindingTable.path("revisions", revision, "definitions"));
        Map<String, Json> shapes = new TreeMap<>();
        for (Map.Entry<String, Json> o : ((Json.JObject) bindingTable.member("objects")).members().entrySet()) {
            Json shape = o.getValue().path("revisions", revision);
            if (shape != null) {
                Map<String, Json> s = new LinkedHashMap<>();
                s.put("kind", o.getValue().member("kind"));
                s.put("shape", shape);
                shapes.put(o.getKey(), new Json.JObject(s));
            }
        }
        Map<String, Json> official = new TreeMap<>();
        addAll(official, officialSchema.member("$defs"));
        return new Binding(revision, officialSchema.member("$schema").text(), official, defs, shapes);
    }

    private static void addAll(Map<String, Json> into, Json obj) {
        if (obj instanceof Json.JObject o) {
            into.putAll(o.members());
        }
    }

    /** Every protocol object an event carries (under any {@code mcp.protocol_objects}), extracted. */
    public static List<Extracted> extractAll(Map<String, Binding> bindings, Json event) {
        List<Extracted> out = new ArrayList<>();
        collect(bindings, event, out);
        return out;
    }

    private static void collect(Map<String, Binding> bindings, Json v, List<Extracted> out) {
        if (v instanceof Json.JObject o) {
            if (o.get("protocol_definition") != null && o.get("binding_revision") != null) {
                out.add(extractProtocolObject(bindings, o));
                return;
            }
            o.members().values().forEach(x -> collect(bindings, x, out));
        }
        else if (v instanceof Json.JArray a) {
            a.elements().forEach(x -> collect(bindings, x, out));
        }
    }

    /** One {@code mcp_protocol_object}: the slot named by its definition, extracted under the declared binding. */
    public static Extracted extractProtocolObject(Map<String, Binding> bindings, Json protocolObject) {
        String def = protocolObject.member("protocol_definition").text();
        String rev = protocolObject.member("binding_revision").text();
        Binding b = bindings.get(rev);
        if (b == null) {
            throw new IllegalStateException("no protocol_binding declared for MCP " + rev + " before an object bound by it");
        }
        Json node = b.definitions().get(def);
        if (node == null) {
            throw new IllegalStateException("the declared MCP " + rev + " binding has no definition " + def);
        }
        String slot = slot(node);
        Json canonical = protocolObject.member(slot);
        if (canonical == null) {
            throw new IllegalStateException("protocol object " + def + " has no slot " + slot);
        }
        List<String> missing = new ArrayList<>();
        for (Json m : protocolObject.member("missing_required") == null ? List.<Json>of() : protocolObject.member("missing_required").elements()) {
            missing.add(m.text());
        }
        int projection = Event.integer(protocolObject.member("projection_id"));
        return new Extracted(def, rev, projection, missing, extract(b, node, canonical));
    }

    static String slot(Json node) {
        String kind = node.member("kind").text();
        return switch (kind) {
            case "object", "variant" -> node.member("object").text();
            case "map" -> node.member("object").text() + "_list";
            default -> throw new IllegalStateException("definition node " + kind + " has no slot");
        };
    }

    /** The protocol JSON a canonical value stands for under a binding node. */
    public static Json extract(Binding b, Json node, Json canonical) {
        String kind = node.member("kind").text();
        return switch (kind) {
            case "json", "scalar", "scalar_union" -> canonical;
            case "array" -> extractArray(b, node.member("items"), canonical);
            case "object" -> extractObject(b, node.member("object").text(), (Json.JObject) canonical);
            case "variant" -> extractVariant(b, node.member("object").text(), (Json.JObject) canonical);
            case "map" -> extractMap(b, node.member("object").text(), canonical);
            default -> throw new IllegalStateException("unknown binding node kind " + kind);
        };
    }

    private static Json extractArray(Binding b, Json items, Json canonical) {
        List<Json> out = new ArrayList<>();
        for (Json e : canonical.elements()) {
            out.add(extract(b, items, e));
        }
        return new Json.JArray(out);
    }

    private static Json extractVariant(Binding b, String variant, Json.JObject c) {
        if (c.members().size() != 1) {
            throw new IllegalStateException("variant " + variant + " holds " + c.members().keySet() + ", not exactly one alternative");
        }
        Map.Entry<String, Json> only = c.members().entrySet().iterator().next();
        if (UNRESOLVED.equals(only.getKey())) {
            return only.getValue();
        }
        for (Json alt : shape(b, variant).member("alternatives").elements()) {
            if (only.getKey().equals(alt.member("attribute").text())) {
                return extract(b, alt.member("node"), only.getValue());
            }
        }
        throw new IllegalStateException("variant " + variant + " has no alternative " + only.getKey());
    }

    private static Json extractMap(Binding b, String entryObject, Json canonical) {
        Json entry = shape(b, entryObject);
        Map<String, Json> out = new LinkedHashMap<>();
        for (Json e : canonical.elements()) {
            out.put(e.member(entry.member("key_attribute").text()).text(), extract(b, entry.member("value"), e.member(entry.member("value_attribute").text())));
        }
        return new Json.JObject(out);
    }

    private static Json extractObject(Binding b, String object, Json.JObject canonical) {
        Map<String, Map.Entry<String, Json>> byAttribute = membersByAttribute(object, shape(b, object));
        Map<String, Json> out = new LinkedHashMap<>();
        for (Map.Entry<String, Json> a : canonical.members().entrySet()) {
            if (ADDITIONAL.equals(a.getKey()) || NONCONFORMING.equals(a.getKey())) {
                out.putAll(((Json.JObject) a.getValue()).members());
                continue;
            }
            Map.Entry<String, Json> member = byAttribute.get(a.getKey());
            if (member == null) {
                throw new IllegalStateException(object + " has no member held in " + a.getKey());
            }
            out.put(member.getKey(), extract(b, member.getValue(), a.getValue()));
        }
        return new Json.JObject(out);
    }

    /** attribute to (member name, node) for one object shape; an attribute held by two members is a binding defect. */
    private static Map<String, Map.Entry<String, Json>> membersByAttribute(String object, Json shape) {
        Map<String, Map.Entry<String, Json>> out = new LinkedHashMap<>();
        if (shape.member("members") instanceof Json.JObject members) {
            for (Map.Entry<String, Json> m : members.members().entrySet()) {
                String attr = m.getValue().member("attribute").text();
                if (out.put(attr, Map.entry(m.getKey(), m.getValue().member("node"))) != null) {
                    throw new IllegalStateException(object + ": two members share attribute " + attr);
                }
            }
        }
        return out;
    }

    private static Json shape(Binding b, String object) {
        Json o = b.shapes().get(object);
        if (o == null) {
            throw new IllegalStateException("the declared MCP " + b.revision() + " binding has no object " + object);
        }
        return o.member("shape");
    }

    /** Validation against the pinned definition (see {@link McpValidation}). */
    public static List<String> validate(Binding b, String definition, Json value, Set<String> relaxed) {
        return McpValidation.validate(b.dialect(), b.schemaDefinitions(), definition, value, relaxed);
    }

    /** JSON equality ignoring member order (see {@link McpValidation}). */
    public static boolean sameJson(Json a, Json b) {
        return McpValidation.sameJson(a, b);
    }
}
