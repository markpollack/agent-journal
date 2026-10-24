package io.github.markpollack.journal.ocsf.writer;

import io.github.markpollack.journal.tck.ocsf.McpProtocol;
import io.github.markpollack.journal.tck.ocsf.SchemaCatalog;
import io.github.markpollack.journal.tck.verify.Json;
import io.github.markpollack.journal.tck.verify.NeutralJson;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Constructive containment of the pinned MCP protocol data model in the canonical model (extension 0.11.0): the
 * generated objects and binding are reproducible from the pinned schemas; every pinned definition and member has a
 * binding; every official example (2026-07-28) and a synthetic instance of every object definition of both pinned
 * revisions (each first checked valid against its pinned definition) embeds through the writer, conforms to the
 * generated OCSF definitions and is extracted by the independent reader ({@link McpProtocol}) back to an equal object
 * that still validates. These are finite examples of the binding's construction rules, not a proof about all JSON.
 * Negative controls show unknown, nonconforming and ambiguous values preserved rather than dropped or forced.
 */
class McpProtocolContainmentTest {

    static final Path ROOT = Path.of("..").toAbsolutePath().normalize();
    static final Path MCP = ROOT.resolve("schema/protocol/mcp");
    static final List<String> REVISIONS = List.of("2025-11-25", "2026-07-28");

    @Test
    void theGeneratedObjectsAndBindingAreReproducibleFromThePinnedSchemas() {
        Map<String, String> files = new McpBindingGenerator(ROOT).generate();
        assertThat(McpBindingGenerator.differences(ROOT, files)).as("regenerate with McpBindingGenerator <root> --write").isEmpty();
        Jv pins = Jv.parse(read(MCP.resolve("PINS.json")));
        for (Jv r : pins.member("revisions").elements()) {
            assertThat(SchemaPin.sha256(bytes(MCP.resolve(r.path("schema_json", "path").text())))).isEqualTo(r.path("schema_json", "sha256").text());
            assertThat(SchemaPin.sha256(bytes(MCP.resolve(r.path("schema_ts", "path").text())))).isEqualTo(r.path("schema_ts", "sha256").text());
        }
    }

    @Test
    void everyPinnedDefinitionAndMemberHasABinding() {
        McpBinding binding = McpBinding.bundled();
        int definitions = 0;
        int members = 0;
        for (String rev : REVISIONS) {
            Json schema = official(rev);
            for (Map.Entry<String, Json> d : ((Json.JObject) schema.member("$defs")).members().entrySet()) {
                definitions++;
                assertThat(binding.definition(rev, d.getKey())).as(rev + " " + d.getKey()).isNotNull();
                Jv node = binding.definition(rev, d.getKey());
                String kind = node.member("kind").text();
                if ("object".equals(kind)) {
                    Jv shape = binding.document().path("objects", node.member("object").text(), "revisions", rev);
                    Json props = d.getValue().member("properties");
                    if (props instanceof Json.JObject p) {
                        for (String m : p.members().keySet()) {
                            members++;
                            assertThat(shape.path("members", m, "attribute")).as(rev + " " + d.getKey() + "." + m).isNotNull();
                        }
                    }
                }
            }
        }
        System.out.println("MCP binding: " + definitions + " pinned definitions and " + members + " members of definition-level objects bound");
        assertThat(definitions).isEqualTo(145 + 155);
    }

    @Test
    void everyOfficialExampleRoundTripsAndValidates() throws IOException {
        String rev = "2026-07-28";
        McpProtocol.Binding reader = readerBinding(rev);
        SchemaCatalog ocsf = ocsfCatalog();
        List<Path> examples;
        try (Stream<Path> s = Files.walk(MCP.resolve(rev).resolve("examples"))) {
            examples = s.filter(p -> p.toString().endsWith(".json")).sorted().toList();
        }
        List<String> problems = new ArrayList<>();
        for (Path example : examples) {
            String def = example.getParent().getFileName().toString();
            problems.addAll(roundTrip(rev, def, example.getFileName().toString(), read(example), reader, ocsf, true));
        }
        System.out.println("MCP " + rev + ": " + examples.size() + " official examples embedded, OCSF-checked, extracted and validated");
        assertThat(examples).hasSize(129);
        assertThat(problems).isEmpty();
    }

    @Test
    void aSyntheticInstanceOfEveryObjectDefinitionOfBothRevisionsRoundTrips() {
        int checked = 0;
        List<String> problems = new ArrayList<>();
        List<String> ambiguous = new ArrayList<>();
        SchemaCatalog ocsf = ocsfCatalog();
        for (String rev : REVISIONS) {
            McpProtocol.Binding reader = readerBinding(rev);
            Json schema = official(rev);
            Synthesizer syn = new Synthesizer((Json.JObject) schema.member("$defs"));
            for (String def : ((Json.JObject) schema.member("$defs")).members().keySet()) {
                Jv node = McpBinding.bundled().definition(rev, def);
                String kind = node.member("kind").text();
                if (!kind.equals("object") && !kind.equals("variant") && !kind.equals("map")) {
                    continue;
                }
                Json instance = syn.instance(def);
                List<String> valid = McpProtocol.validate(reader, def, instance, Set.of());
                if (!valid.isEmpty()) {
                    problems.add(rev + " " + def + ": the synthetic instance is not valid against the pinned definition: " + valid);
                    continue;
                }
                List<String> found = roundTrip(rev, def, "synthetic", NeutralJson.write(instance), reader, ocsf, false);
                if (found.size() == 1 && found.get(0).contains("unresolved or nonconforming") && found.get(0).contains("\"mcp_unresolved\"")) {
                    ambiguous.add(rev + " " + def);
                }
                else {
                    problems.addAll(found);
                }
                checked++;
            }
        }
        System.out.println("MCP: " + checked + " synthetic definition instances (labelled synthetic) embedded, OCSF-checked, extracted and validated; ambiguous unions kept whole: " + ambiguous);
        assertThat(problems).isEmpty();
        // a union whose alternatives a value cannot tell apart stays whole in mcp_unresolved: preserved, extracted and
        // validated, never guessed. These are ambiguous in the pinned schemas themselves: an empty Result is equally a
        // GetTaskPayloadResult (2025-11-25), and an untitled single-select enum {type, enum} is equally a legacy titled
        // enum without enumNames (both revisions)
        assertThat(ambiguous).containsExactly("2025-11-25 ClientResult", "2025-11-25 EnumSchema", "2025-11-25 ServerResult", "2026-07-28 EnumSchema");
        assertThat(checked).isGreaterThan(200);
    }

    @Test
    void unknownNonconformingAndAmbiguousValuesArePreservedNotDroppedOrForced() {
        McpBinding binding = McpBinding.bundled();
        String rev = "2025-11-25";
        McpProtocol.Binding reader = readerBinding(rev);

        // an unknown member of an open definition is kept in mcp_additional_members and comes back
        Jv withExtra = Jv.parse("{\"content\":[{\"type\":\"text\",\"text\":\"ok\"}],\"isError\":false,\"vendorFlag\":{\"x\":1}}");
        Jv.Obj po = binding.protocolObject(rev, "CallToolResult", withExtra, McpBinding.COMPLETE, null);
        Jv canonical = po.get("mcp_call_tool_result");
        assertThat(canonical.path("mcp_additional_members", "vendorFlag")).isNotNull();
        assertThat(extract(reader, po)).satisfies(j -> assertThat(McpProtocol.sameJson(j, json(withExtra))).isTrue());

        // isError absent stays absent; explicit false stays false
        Jv absent = Jv.parse("{\"content\":[]}");
        assertThat(binding.protocolObject(rev, "CallToolResult", absent, McpBinding.COMPLETE, null).path("mcp_call_tool_result", "mcp_is_error")).isNull();
        assertThat(binding.protocolObject(rev, "CallToolResult", withExtra, McpBinding.COMPLETE, null).path("mcp_call_tool_result", "mcp_is_error")).isEqualTo(new Jv.Bool(false));

        // a named member whose value does not fit is kept in mcp_nonconforming_members, comes back, and fails validation
        Jv wrong = Jv.parse("{\"content\":\"not an array\",\"isError\":\"no\"}");
        Jv.Obj bad = binding.protocolObject(rev, "CallToolResult", wrong, McpBinding.COMPLETE, null);
        assertThat(bad.path("mcp_call_tool_result", "mcp_nonconforming_members", "content")).isNotNull();
        assertThat(bad.path("mcp_call_tool_result", "mcp_nonconforming_members", "isError")).isNotNull();
        Json back = extract(reader, bad);
        assertThat(McpProtocol.sameJson(back, json(wrong))).isTrue();
        assertThat(McpProtocol.validate(reader, "CallToolResult", back, Set.of())).isNotEmpty();

        // a content block no alternative matches (unknown type) stays whole in mcp_unresolved
        Jv odd = Jv.parse("{\"content\":[{\"type\":\"hologram\",\"payload\":\"?\"}]}");
        Jv.Obj unresolved = binding.protocolObject(rev, "CallToolResult", odd, McpBinding.COMPLETE, null);
        assertThat(((Jv.Arr) unresolved.path("mcp_call_tool_result", "mcp_content_block_list")).elements().get(0).member("mcp_unresolved")).isNotNull();
        assertThat(McpProtocol.sameJson(extract(reader, unresolved), json(odd))).isTrue();

        // a JSON-RPC id keeps its JSON type: 1 and "1" are different ids
        Jv numeric = Jv.parse("{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{}}");
        Jv text = Jv.parse("{\"jsonrpc\":\"2.0\",\"id\":\"1\",\"result\":{}}");
        Json n = extract(reader, binding.protocolObject(rev, "JSONRPCResultResponse", numeric, McpBinding.COMPLETE, null));
        Json t = extract(reader, binding.protocolObject(rev, "JSONRPCResultResponse", text, McpBinding.COMPLETE, null));
        assertThat(n.member("id")).isInstanceOf(Json.JNumber.class);
        assertThat(t.member("id")).isInstanceOf(Json.JString.class);

        // a partial projection lists the required members it lacks; only those are relaxed
        Jv projected = Jv.parse("{\"structuredContent\":{\"echo\":\"alpha\"}}");
        Jv.Obj partial = binding.protocolObject(rev, "CallToolResult", projected, McpBinding.PARTIAL, "test projection");
        assertThat(Jv.write(partial.get("missing_required"))).isEqualTo("[\"content\"]");
        Json p = extract(reader, partial);
        assertThat(McpProtocol.validate(reader, "CallToolResult", p, Set.of())).as("incomplete as a complete object").isNotEmpty();
        assertThat(McpProtocol.validate(reader, "CallToolResult", p, Set.of("content"))).as("valid as the declared projection").isEmpty();
    }

    // ------------------------------------------------------------------------------------------ helpers

    private List<String> roundTrip(String rev, String def, String label, String text, McpProtocol.Binding reader, SchemaCatalog ocsf, boolean mustValidate) {
        List<String> problems = new ArrayList<>();
        Jv value = Jv.parse(text);
        Json original = NeutralJson.parse(text);
        List<String> valid = McpProtocol.validate(reader, def, original, Set.of());
        if (mustValidate && !valid.isEmpty()) {
            problems.add(rev + " " + def + " " + label + ": the official example does not validate: " + valid);
        }
        Jv.Obj po = McpBinding.bundled().protocolObject(rev, def, value, McpBinding.SCHEMA_EXAMPLE, "official or synthetic schema example");
        String canonical = Jv.write(po);
        if (canonical.contains("\"mcp_unresolved\"") || canonical.contains("\"mcp_nonconforming_members\"")) {
            problems.add(rev + " " + def + " " + label + ": embedding left an unresolved or nonconforming value: " + canonical);
        }
        SchemaCatalog.Definition slotDef = ocsf.object("agentic_cli/mcp_protocol_object");
        for (SchemaCatalog.Finding f : ocsf.check(NeutralJson.parse(canonical), slotDef)) {
            problems.add(rev + " " + def + " " + label + ": OCSF " + f);
        }
        Json back = McpProtocol.extractProtocolObject(Map.of(rev, reader), NeutralJson.parse(canonical)).value();
        if (!McpProtocol.sameJson(back, original)) {
            problems.add(rev + " " + def + " " + label + ": extracted " + NeutralJson.write(back) + " differs from " + text);
        }
        if (valid.isEmpty() && !McpProtocol.validate(reader, def, back, Set.of()).isEmpty()) {
            problems.add(rev + " " + def + " " + label + ": the extracted object no longer validates");
        }
        return problems;
    }

    private static Json extract(McpProtocol.Binding reader, Jv.Obj protocolObject) {
        return McpProtocol.extractProtocolObject(Map.of(reader.revision(), reader), json(protocolObject)).value();
    }

    static Json json(Jv v) {
        return NeutralJson.parse(Jv.write(v));
    }

    static McpProtocol.Binding readerBinding(String rev) {
        return McpProtocol.fromBindingTable(json(McpBinding.bundled().document()), official(rev), rev);
    }

    static Json official(String rev) {
        return NeutralJson.parse(read(MCP.resolve(rev).resolve("schema.json")));
    }

    static SchemaCatalog ocsfCatalog() {
        Json excerpt = NeutralJson.parse(read(ROOT.resolve("journal-ocsf-writer/src/main/resources/ocsf/compiled-excerpt-agentic_cli-0.11.0-prototype.json")));
        return SchemaCatalog.fromClosure(excerpt);
    }

    static String read(Path p) {
        try {
            return Files.readString(p, StandardCharsets.UTF_8);
        }
        catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static byte[] bytes(Path p) {
        try {
            return Files.readAllBytes(p);
        }
        catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Labelled synthetic instances: every member of a definition populated with a value of its declared type (constants
     * and the first enum value where declared, the first alternative of a union, one element or entry of an array or
     * map), nesting cut to required members below a depth. Each is checked valid against the pinned definition before use.
     */
    static final class Synthesizer {
        private final Json.JObject defs;

        Synthesizer(Json.JObject defs) {
            this.defs = defs;
        }

        Json instance(String def) {
            return value(defs.get(def), 0);
        }

        private Json value(Json schema, int depth) {
            if (schema instanceof Json.JBool || schema == null) {
                return obj(Map.of("k", new Json.JNumber("1")));
            }
            if (schema.member("$ref") != null) {
                String ref = schema.member("$ref").text().substring("#/$defs/".length());
                return value(defs.get(ref), depth + 1);
            }
            if (schema.member("const") != null) {
                return schema.member("const");
            }
            if (schema.member("enum") != null) {
                return schema.member("enum").elements().get(0);
            }
            if (schema.member("allOf") != null) {
                Map<String, Json> merged = new LinkedHashMap<>();
                for (Json part : schema.member("allOf").elements()) {
                    if (value(part, depth) instanceof Json.JObject o) {
                        merged.putAll(o.members());
                    }
                }
                return obj(merged);
            }
            if (schema.member("anyOf") != null || schema.member("oneOf") != null) {
                Json alts = schema.member("anyOf") != null ? schema.member("anyOf") : schema.member("oneOf");
                // deep inside recursive structures (JSONValue) take the last alternative, a scalar, so that nesting ends
                return value(alts.elements().get(depth > 8 ? alts.elements().size() - 1 : 0), depth);
            }
            Json type = schema.member("type");
            String t = type instanceof Json.JArray a ? a.elements().get(0).text() : type == null ? (schema.member("properties") != null ? "object" : null) : type.text();
            if (t == null) {
                return obj(Map.of("k", new Json.JNumber("1")));
            }
            switch (t) {
                case "string": {
                    String format = schema.member("format") == null ? "" : schema.member("format").text();
                    return new Json.JString(switch (format) {
                        case "uri", "uri-reference", "iri" -> "https://example.com/x";
                        case "date-time" -> "2026-10-24T00:00:00Z";
                        case "date" -> "2026-10-24";
                        case "byte" -> "AAAA";
                        case "email" -> "a@example.com";
                        default -> "x";
                    });
                }
                case "integer": {
                    long v = schema.member("minimum") instanceof Json.JNumber min ? min.value().longValue() : 1;
                    return new Json.JNumber(Long.toString(v));
                }
                case "number": {
                    if (schema.member("minimum") instanceof Json.JNumber min) {
                        return min;
                    }
                    return new Json.JNumber(schema.member("maximum") instanceof Json.JNumber max && max.value().doubleValue() < 1.5 ? max.text() : "1.5");
                }
                case "boolean":
                    return Json.JBool.class.cast(new Json.JBool(true));
                case "null":
                    return Json.JNull.INSTANCE;
                case "array":
                    return new Json.JArray(depth > 8 ? List.of() : List.of(value(schema.member("items"), depth + 1)));
                default: {
                    Map<String, Json> out = new LinkedHashMap<>();
                    Set<String> required = new java.util.HashSet<>();
                    for (Json r : schema.member("required") == null ? List.<Json>of() : schema.member("required").elements()) {
                        required.add(r.text());
                    }
                    if (schema.member("properties") instanceof Json.JObject props) {
                        for (Map.Entry<String, Json> p : props.members().entrySet()) {
                            if (depth < 5 || required.contains(p.getKey())) {
                                out.put(p.getKey(), value(p.getValue(), depth + 1));
                            }
                        }
                    }
                    else if (depth <= 8 && schema.member("additionalProperties") instanceof Json.JObject addl && !addl.members().isEmpty()) {
                        out.put("k", value(addl, depth + 1));
                    }
                    return obj(out);
                }
            }
        }

        private static Json obj(Map<String, Json> m) {
            return new Json.JObject(new TreeMap<>(m));
        }
    }
}
