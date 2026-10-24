package io.github.markpollack.journal.ocsf.writer;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Generates Journal's typed embedding of the pinned MCP protocol data model from the official JSON Schemas under
 * {@code schema/protocol/mcp/} ({@code PINS.json} names the revisions): one OCSF object per object-like definition (the
 * union of its members over the pinned revisions), one variant object per union, one entry object per typed map, the
 * {@code mcp_*} dictionary attributes, the {@code mcp_protocol_object} slot object and the binding table the writer
 * embeds with and a reader extracts with. Deterministic: the same pinned inputs give byte-identical outputs, which a
 * test regenerates and compares with the committed files.
 *
 * <p>Conventions (also written into the binding as {@code conventions}): an object with properties becomes
 * {@code mcp_<snake(definition)>} (inline: {@code mcp_<owner>__<member>}); every protocol member is an optional OCSF
 * attribute whose protocol requirement stays in the binding; a union becomes a variant object with one attribute per
 * alternative and {@code mcp_unresolved}; a typed map becomes an array of entry objects; free JSON and scalar unions are
 * {@code json_t}; {@code mcp_additional_members} and {@code mcp_nonconforming_members} keep what a definition does not
 * name or what does not fit. Nothing is dropped. {@link McpBindingNames} names the attributes.
 *
 * <p>Usage: {@code McpBindingGenerator <project-root> [--write]}; without {@code --write} it reports what differs.
 */
public final class McpBindingGenerator {

    static final String PREFIX = McpBindingNames.PREFIX;
    static final String BINDING_KIND = "agent-journal/mcp-binding";
    static final String BINDING_VERSION = "0.1.0";
    static final String PINS = "schema/protocol/mcp/PINS.json";
    static final String OBJECT_DIR = "prototype/ocsf-acp/extension/agentic_cli/objects/mcp";
    static final String DICTIONARY = "prototype/ocsf-acp/extension/agentic_cli/dictionary.json";
    static final String BINDING = "journal-ocsf-writer/src/main/resources/ocsf/mcp/binding.json";
    static final String COVERAGE = "schema/protocol/mcp/COVERAGE.md";
    static final String SLOT_OBJECT = "mcp_protocol_object";
    static final String ADDITIONAL = "mcp_additional_members";
    static final String NONCONFORMING = "mcp_nonconforming_members";
    static final String UNRESOLVED = "mcp_unresolved";
    static final String KEY = "mcp_key";

    private final Path root;
    private final Jv pins;
    private final McpSchemaModel model;
    private final McpBindingNames names;
    private final Map<String, Set<String>> deprecated = new TreeMap<>();

    McpBindingGenerator(Path root) {
        this.root = root;
        this.pins = Jv.parse(read(root.resolve(PINS)));
        Map<String, Jv.Obj> defs = new TreeMap<>();
        for (Jv r : pins.member("revisions").elements()) {
            Jv schema = Jv.parse(read(root.resolve("schema/protocol/mcp").resolve(r.path("schema_json", "path").text())));
            defs.put(r.member("revision").text(), (Jv.Obj) schema.member("$defs"));
            deprecated.put(r.member("revision").text(), McpCoverage.deprecated(read(root.resolve("schema/protocol/mcp").resolve(r.path("schema_ts", "path").text()))));
        }
        this.model = new McpSchemaModel(defs);
        this.names = new McpBindingNames(model);
    }

    @SuppressWarnings("SystemOut") // a command-line tool: its standard output is its report
    public static void main(String[] args) {
        if (args.length < 1) {
            System.err.println("usage: McpBindingGenerator <project-root> [--write]");
            System.exit(64);
        }
        Path root = Path.of(args[0]);
        Map<String, String> files = new McpBindingGenerator(root).generate();
        List<String> differ = differences(root, files);
        if (args.length > 1 && "--write".equals(args[1])) {
            write(root, files);
            System.out.println("wrote " + files.size() + " files (" + differ.size() + " changed)");
            return;
        }
        differ.forEach(System.out::println);
        System.out.println(differ.isEmpty() ? "up to date" : differ.size() + " file(s) differ");
    }

    /** Every generated file, by project-relative path, with its exact content. */
    Map<String, String> generate() {
        Map<String, String> files = new TreeMap<>();
        Map<String, Jv> shapes = new TreeMap<>();
        for (McpSchemaModel.Generated g : model.objects.values()) {
            Jv.Obj attrs = new Jv.Obj();
            Jv.Obj perRevision = new Jv.Obj();
            for (Map.Entry<String, McpSchemaModel.Shape> r : g.revisions.entrySet()) {
                perRevision.put(r.getKey(), shape(g.kind, r.getValue(), attrs));
            }
            fixedAttributes(g, attrs);
            files.put(OBJECT_DIR + "/" + g.name + ".json", pretty(objectFile(g, attrs)));
            shapes.put(g.name, new Jv.Obj().put("kind", g.kind).put("revisions", perRevision));
        }
        Set<String> slots = slots();
        files.put(OBJECT_DIR + "/" + SLOT_OBJECT + ".json", pretty(slotObject(slots)));
        files.put(DICTIONARY, dictionary());
        files.put(BINDING, Jv.write(binding(shapes, slots)) + "\n");
        files.put(COVERAGE, McpCoverage.markdown(pins, model.definitionNodes, model.defsByRevision, deprecated, shapes));
        return files;
    }

    /** One revision's shape in the binding, adding the attributes it uses to the object's attribute set. */
    private Jv.Obj shape(String kind, McpSchemaModel.Shape s, Jv.Obj attrs) {
        Jv.Obj shape = new Jv.Obj().put("location", s.location);
        switch (kind) {
            case "object" -> objectShape(s, shape, attrs);
            case "variant" -> variantShape(s, shape, attrs);
            case "map_entry" -> {
                String value = names.value(s.mapValue);
                attrs.put(KEY, attribute("The map key exactly as reported.", "required"));
                attrs.members().putIfAbsent(value, attribute("The value under the key.", "optional"));
                shape.put("key_attribute", KEY).put("value_attribute", value).put("value", s.mapValue.toJv());
            }
            default -> throw new IllegalStateException(kind);
        }
        return shape;
    }

    private void objectShape(McpSchemaModel.Shape s, Jv.Obj shape, Jv.Obj attrs) {
        Jv.Arr required = new Jv.Arr();
        Jv.Obj members = new Jv.Obj();
        Set<String> used = new TreeSet<>();
        for (McpSchemaModel.Member m : s.members.values()) {
            String a = names.member(m.name(), m.node());
            if (!used.add(a)) {
                throw new IllegalStateException(s.location + ": two members held in " + a);
            }
            if (m.required()) {
                required.add(new Jv.Str(m.name()));
            }
            members.put(m.name(), new Jv.Obj().put("attribute", a).put("node", m.node().toJv()));
            String desc = "MCP member `" + m.name() + "`" + (m.required() ? " (required by the protocol)" : "") + (m.description() == null ? "." : ": " + m.description());
            attrs.members().putIfAbsent(a, attribute(desc, "optional"));
        }
        shape.put("required", required).put("additional_allowed", s.additional).put("members", members);
    }

    private void variantShape(McpSchemaModel.Shape s, Jv.Obj shape, Jv.Obj attrs) {
        Jv.Arr alts = new Jv.Arr();
        for (McpSchemaModel.Alternative a : s.alternatives) {
            String attr = names.value(a.node());
            alts.add(new Jv.Obj().put("label", a.label()).put("attribute", attr).put("node", a.node().toJv()).put("match", a.match()));
            attrs.members().putIfAbsent(attr, attribute("The value when it is the alternative " + a.label() + ".", "optional"));
        }
        shape.put("alternatives", alts);
    }

    private void fixedAttributes(McpSchemaModel.Generated g, Jv.Obj attrs) {
        switch (g.kind) {
            case "variant" -> {
                names.claimFixed(UNRESOLVED, "json_t");
                attrs.put(UNRESOLVED, attribute("The value exactly as reported when no single alternative matches it by its constant and required members.", "optional"));
            }
            case "object" -> {
                if (g.revisions.values().stream().anyMatch(s -> s.additional)) {
                    names.claimFixed(ADDITIONAL, "json_t");
                    attrs.put(ADDITIONAL, attribute("Members the pinned definition does not name, exactly as reported, in source order.", "optional"));
                }
                names.claimFixed(NONCONFORMING, "json_t");
                attrs.put(NONCONFORMING, attribute("Named members whose value does not fit the pinned definition, exactly as reported.", "optional"));
            }
            default -> names.claimFixed(KEY, "string_t");
        }
    }

    private static Jv.Obj attribute(String description, String requirement) {
        return new Jv.Obj().put("description", description).put("requirement", requirement);
    }

    private static Jv.Obj objectFile(McpSchemaModel.Generated g, Jv.Obj attrs) {
        Set<String> defs = new TreeSet<>();
        String description = null;
        for (Map.Entry<String, McpSchemaModel.Shape> r : g.revisions.entrySet()) {
            defs.add(r.getKey() + " " + r.getValue().location);
            description = description == null ? r.getValue().description : description;
        }
        String caption = McpBindingNames.caption(g.name);
        String desc = (description == null ? caption + "." : description) + " Generated from the pinned MCP schema (" + String.join("; ", defs) + ").";
        return new Jv.Obj().put("caption", caption).put("description", desc).put("extends", "object").put("name", g.name).put("attributes", attrs);
    }

    /** The slot attributes of {@code mcp_protocol_object}: one per definition that is an object, a union or a map. */
    private Set<String> slots() {
        Set<String> slots = new TreeSet<>();
        for (Map<String, McpNode> nodes : model.definitionNodes.values()) {
            for (McpNode n : nodes.values()) {
                if (n instanceof McpNode.Ref) {
                    slots.add(names.value(n));
                }
            }
        }
        return slots;
    }

    private static Jv.Obj slotObject(Set<String> slots) {
        Jv.Obj attrs = new Jv.Obj();
        for (String fixed : List.of("protocol_definition", "binding_revision", "projection_id")) {
            attrs.put(fixed, new Jv.Obj().put("requirement", "required"));
        }
        for (String fixed : List.of("projection", "projection_basis", "missing_required")) {
            attrs.put(fixed, new Jv.Obj().put("requirement", "optional"));
        }
        for (String s : slots) {
            attrs.put(s, attribute("The protocol object when protocol_definition binds to " + s + ".", "optional"));
        }
        return new Jv.Obj().put("caption", "MCP Protocol Object")
                .put("description", "One MCP protocol object typed by the pinned binding: protocol_definition names the official definition, binding_revision the pinned revision whose binding typed it, and exactly one slot holds it. projection_id states whether it is a complete acquired protocol object, a partial projection a CLI reported (missing_required lists the protocol-required members the projection lacks) or a labelled schema example. Generated with the mcp_* objects.")
                .put("extends", "object").put("name", SLOT_OBJECT).put("attributes", attrs);
    }

    /** The dictionary with every generated attribute replaced; the hand-written ones keep their place and order. */
    private String dictionary() {
        Jv dict = Jv.parse(read(root.resolve(DICTIONARY)));
        Map<String, Jv> kept = new LinkedHashMap<>();
        for (Map.Entry<String, Jv> a : ((Jv.Obj) dict.member("attributes")).members().entrySet()) {
            if (!a.getKey().startsWith(PREFIX)) {
                kept.put(a.getKey(), a.getValue());
            }
        }
        for (Map.Entry<String, String> s : names.signatures.entrySet()) {
            kept.put(s.getKey(), dictionaryAttribute(s.getKey(), s.getValue()));
        }
        Jv.Obj out = new Jv.Obj();
        for (Map.Entry<String, Jv> m : ((Jv.Obj) dict).members().entrySet()) {
            out.put(m.getKey(), m.getKey().equals("attributes") ? new Jv.Obj(kept) : m.getValue());
        }
        return pretty(out);
    }

    private static Jv.Obj dictionaryAttribute(String name, String signature) {
        boolean array = signature.endsWith("[]");
        String type = array ? signature.substring(0, signature.length() - 2) : signature;
        String desc = switch (name) {
            case ADDITIONAL -> "MCP members the pinned definition does not name, exactly as reported (generated).";
            case NONCONFORMING -> "MCP members whose value does not fit the pinned definition, exactly as reported (generated).";
            case UNRESOLVED -> "An MCP union value no single alternative matches, exactly as reported (generated).";
            case KEY -> "An MCP map key exactly as reported (generated).";
            default -> type.startsWith(PREFIX) ? "An MCP protocol value typed by " + type + " (generated from the pinned MCP schema; the containing object states the member)."
                    : "An MCP protocol member value (generated from the pinned MCP schema; the containing object states the member and its meaning).";
        };
        Jv.Obj a = new Jv.Obj().put("caption", McpBindingNames.caption(name)).put("description", desc).put("type", type);
        return array ? a.put("is_array", true) : a;
    }

    private Jv.Obj binding(Map<String, Jv> shapes, Set<String> slots) {
        Jv.Obj revs = new Jv.Obj();
        for (Map.Entry<String, Map<String, McpNode>> r : model.definitionNodes.entrySet()) {
            Jv.Obj defs = new Jv.Obj();
            r.getValue().forEach((d, n) -> defs.put(d, n.toJv()));
            Jv.Arr dep = new Jv.Arr();
            deprecated.get(r.getKey()).forEach(x -> dep.add(new Jv.Str(x)));
            revs.put(r.getKey(), new Jv.Obj().put("definitions", defs).put("deprecated", dep));
        }
        Jv.Arr slotList = new Jv.Arr();
        slots.forEach(s -> slotList.add(new Jv.Str(s)));
        Jv.Arr files = new Jv.Arr();
        model.objects.keySet().forEach(n -> files.add(new Jv.Str("objects/mcp/" + n + ".json")));
        files.add(new Jv.Str("objects/mcp/" + SLOT_OBJECT + ".json"));
        return new Jv.Obj().put("kind", BINDING_KIND).put("version", BINDING_VERSION).put("generator", McpBindingGenerator.class.getSimpleName())
                .put("pins", pins).put("conventions", conventions()).put("revisions", revs).put("objects", new Jv.Obj(shapes))
                .put("slot_object", SLOT_OBJECT).put("slots", slotList).put("extension_files", files);
    }

    private static Jv.Obj conventions() {
        return new Jv.Obj()
                .put("object", "a JSON Schema object with properties is an OCSF object mcp_<snake(definition)> (inline: mcp_<owner>__<member>); every protocol member is an optional OCSF attribute and the protocol's required list stays here")
                .put("member_attribute", "an object-typed member is held in an attribute named after its type (_list for an array or a map); a scalar or free-JSON member in mcp_<snake(member)>, suffixed with its signature when that member name has more than one signature in the pinned revisions")
                .put("variant", "a union is a variant object with one attribute per alternative and mcp_unresolved for a value that no single alternative matches by its JSON type, constant members and required members (ties keep the alternatives with more matched constants, then more matched required members, then fewer unnamed members)")
                .put("map", "a map whose values have a schema is an array of entry objects: mcp_key plus the typed value, in source order")
                .put("json", "free JSON (JSONValue, JSONObject, an empty or open object without properties, arrays of free JSON) and scalar unions are json_t, preserved exactly")
                .put("preservation", "mcp_additional_members keeps members a definition does not name; mcp_nonconforming_members keeps named members whose value does not fit; nothing is dropped")
                .put("requirement", "OCSF requirement is optional for every generated attribute so that partial CLI projections validate; complete objects are validated against the pinned JSON Schema, and a projection lists the required members it lacks");
    }

    static String read(Path p) {
        return McpGeneratedFiles.read(p);
    }

    static String pretty(Jv v) {
        return McpGeneratedFiles.pretty(v);
    }

    static List<String> differences(Path root, Map<String, String> files) {
        return McpGeneratedFiles.differences(root, files, OBJECT_DIR);
    }

    static void write(Path root, Map<String, String> files) {
        McpGeneratedFiles.write(root, files, OBJECT_DIR);
    }

    static String refName(String ref) {
        return McpSchemaModel.refName(ref);
    }
}
