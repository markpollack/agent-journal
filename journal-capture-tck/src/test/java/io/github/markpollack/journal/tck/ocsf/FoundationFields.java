package io.github.markpollack.journal.tck.ocsf;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The field inventory of the three Tier 1 foundations (OTel GenAI semantic conventions, OpenInference semantic
 * conventions, Weave's Agents span model), derived mechanically from their pinned source files so that the parity
 * ledger can be reconciled against an exact, reproducible set rather than a hand-picked list.
 *
 * <p>Each foundation's boundary is declared with the inventory: which files and classes are read, and which are
 * excluded with a reason. Every extracted item becomes one field with a stable id
 * {@code <foundation>:<kind>:<name>}. The extraction is purely syntactic (YAML and JSON Schema documents; Python
 * constant and annotated-field declarations); it records what the source declares, never what it means for Journal.
 * Meaning, tier and Journal correspondence live in the parity ledger ({@code foundation-parity.json}).
 */
final class FoundationFields {

    static final ObjectMapper JSON = new ObjectMapper();
    static final YAMLMapper YAML = new YAMLMapper();

    static final String OTEL = "otel_genai.semconv";
    static final String OI = "openinference.semconv";
    static final String WEAVE = "weave.agents_schema";

    /** One declared item of a foundation. {@code detail} holds kind-specific facts (attribute refs, enum parent, lookup keys). */
    record Field(String id, String foundation, String kind, String name, String locator, String type, String stability, String brief,
            Map<String, Object> detail) {
    }

    /** A source file read for the inventory, with its git blob id at the pin. */
    record SourceFile(String path, String blob) {
    }

    /** One foundation's extraction: files read, boundary statement, exclusions and fields. */
    record Extraction(String foundation, List<SourceFile> files, String boundary, List<Map<String, String>> excluded, List<Field> fields) {
    }

    private FoundationFields() {
    }

    // ---- files ---------------------------------------------------------------------------------------------------------

    /** The git blob id of a file's bytes, so a copy can be matched to the pinned commit without the repository. */
    static String gitBlob(byte[] content) {
        try {
            MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
            sha1.update(("blob " + content.length + "\0").getBytes(StandardCharsets.US_ASCII));
            sha1.update(content);
            return HexFormat.of().formatHex(sha1.digest());
        }
        catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
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

    static String text(Path p) {
        return new String(bytes(p), StandardCharsets.UTF_8);
    }

    static String brief(String s) {
        if (s == null) {
            return null;
        }
        String t = s.strip().replaceAll("\\s+", " ");
        return t.isEmpty() ? null : t;
    }

    // ---- OTel GenAI ----------------------------------------------------------------------------------------------------

    static final List<String> OTEL_YAML = List.of("model/gen-ai/registry.yaml", "model/mcp/registry.yaml", "model/openai/registry.yaml",
            "model/aws-bedrock/registry.yaml", "model/gen-ai/spans.yaml", "model/mcp/spans.yaml", "model/mcp/common.yaml", "model/gen-ai/events.yaml",
            "model/gen-ai/metrics.yaml", "model/gen-ai/token-metrics.yaml", "model/mcp/metrics.yaml", "model/gen-ai/entities.yaml");
    static final List<String> OTEL_SCHEMAS = List.of("model/gen-ai/gen-ai-input-messages.json", "model/gen-ai/gen-ai-output-messages.json",
            "model/gen-ai/gen-ai-system-instructions.json", "model/gen-ai/gen-ai-tool-definitions.json", "model/gen-ai/gen-ai-tool-call-arguments.json",
            "model/gen-ai/gen-ai-tool-call-result.json", "model/gen-ai/gen-ai-retrieval-documents.json", "model/gen-ai/gen-ai-memory-records.json");

    /**
     * Registry attributes and their enum members; spans and span refinements; events; metrics and metric refinements;
     * entities; attributes those definitions reference but the GenAI registries do not define (core semantic
     * conventions); and the properties of every message/tool JSON Schema definition (identical definitions shared by
     * several schema files are one field with every locator).
     */
    static Extraction otel(Path root) {
        List<SourceFile> files = new ArrayList<>();
        Map<String, Field> out = new LinkedHashMap<>();
        Map<String, JsonNode> docs = new LinkedHashMap<>();
        for (String f : OTEL_YAML) {
            files.add(new SourceFile(f, gitBlob(bytes(root.resolve(f)))));
            try {
                docs.put(f, YAML.readTree(root.resolve(f).toFile()));
            }
            catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        Map<String, String> registered = new TreeMap<>();
        for (Map.Entry<String, JsonNode> d : docs.entrySet()) {
            for (JsonNode a : d.getValue().path("attributes")) {
                String key = a.path("key").asText();
                registered.put(key, d.getKey());
                JsonNode type = a.path("type");
                boolean isEnum = type.isObject() && type.has("members");
                put(out, new Field(OTEL + ":attribute:" + key, OTEL, "attribute", key, d.getKey() + "#" + key, isEnum ? "enum" : type.asText(),
                        a.path("stability").asText(null), brief(a.path("brief").asText(null)), Map.of()));
                if (isEnum) {
                    for (JsonNode m : type.path("members")) {
                        String value = m.path("value").asText();
                        put(out, new Field(OTEL + ":enum_member:" + key + "=" + value, OTEL, "enum_member", key + "=" + value, d.getKey() + "#" + key + "/" + m.path("id").asText(),
                                "member", m.path("stability").asText(null), brief(m.path("brief").asText(null)), Map.of("attribute", key)));
                    }
                }
            }
        }
        Map<String, String> referenced = new TreeMap<>();
        for (Map.Entry<String, JsonNode> d : docs.entrySet()) {
            for (String section : List.of("spans", "span_refinements", "events", "metrics", "metric_refinements", "entities")) {
                for (JsonNode g : d.getValue().path(section)) {
                    String kind = section.endsWith("ies") ? "entity" : section.substring(0, section.length() - 1);
                    String name = firstText(g, "type", "id", "name");
                    Map<String, Object> detail = new LinkedHashMap<>();
                    List<String> refs = new ArrayList<>();
                    for (JsonNode a : g.path("attributes")) {
                        if (a.has("ref")) {
                            refs.add(a.path("ref").asText() + requirement(a.path("requirement_level")));
                            referenced.putIfAbsent(a.path("ref").asText(), d.getKey() + "#" + name);
                        }
                        else if (a.has("ref_group")) {
                            refs.add("group " + a.path("ref_group").asText());
                        }
                    }
                    if (!refs.isEmpty()) {
                        detail.put("attributes", refs);
                    }
                    for (String k : List.of("kind", "ref", "instrument", "unit")) {
                        if (g.hasNonNull(k)) {
                            detail.put(k, g.path(k).asText());
                        }
                    }
                    if (g.has("requirement_level")) {
                        detail.put("requirement_level", requirement(g.path("requirement_level")).strip());
                    }
                    put(out, new Field(OTEL + ":" + kind + ":" + name, OTEL, kind, name, d.getKey() + "#" + name, null, g.path("stability").asText(null),
                            brief(g.path("brief").asText(null)), detail));
                }
            }
            for (JsonNode g : d.getValue().path("attribute_groups")) {
                for (JsonNode a : g.path("attributes")) {
                    if (a.has("ref")) {
                        referenced.putIfAbsent(a.path("ref").asText(), d.getKey() + "#" + g.path("id").asText());
                    }
                }
            }
        }
        for (Map.Entry<String, String> r : referenced.entrySet()) {
            if (!registered.containsKey(r.getKey())) {
                put(out, new Field(OTEL + ":referenced_attribute:" + r.getKey(), OTEL, "referenced_attribute", r.getKey(), r.getValue(), null, null,
                        "defined by the core semantic conventions the GenAI manifest depends on, not by the GenAI registries", Map.of()));
            }
        }
        Map<String, List<String>> shared = new LinkedHashMap<>();
        Map<String, JsonNode> definitions = new LinkedHashMap<>();
        for (String f : OTEL_SCHEMAS) {
            files.add(new SourceFile(f, gitBlob(bytes(root.resolve(f)))));
            JsonNode schema;
            try {
                schema = JSON.readTree(root.resolve(f).toFile());
            }
            catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            String stem = Path.of(f).getFileName().toString().replace(".json", "");
            if (!schema.has("$defs")) {
                // a whole-document schema without definitions: one field for the document itself
                put(out, new Field(OTEL + ":message_schema:" + stem, OTEL, "message_schema", stem, f, schema.path("type").asText(null), null,
                        brief(schema.path("description").asText(null)), Map.of()));
                continue;
            }
            schema.path("$defs").fields().forEachRemaining(e -> {
                String key = e.getKey() + " " + e.getValue().toString();
                shared.computeIfAbsent(key, k -> new ArrayList<>()).add(f);
                definitions.putIfAbsent(key, e.getValue());
            });
        }
        Map<String, Integer> variants = new TreeMap<>();
        shared.keySet().forEach(k -> variants.merge(k.substring(0, k.indexOf(' ')), 1, Integer::sum));
        for (Map.Entry<String, List<String>> s : shared.entrySet()) {
            String def = s.getKey().substring(0, s.getKey().indexOf(' '));
            JsonNode body = definitions.get(s.getKey());
            // a definition name that differs between files gets the first file's stem as qualifier
            String qualified = variants.get(def) > 1 ? Path.of(s.getValue().get(0)).getFileName().toString().replace(".json", "") + "#" + def : def;
            String locator = String.join(", ", s.getValue().stream().map(f -> f + "#/$defs/" + def).toList());
            if (body.has("properties")) {
                body.path("properties").fields().forEachRemaining(p -> {
                    JsonNode v = p.getValue();
                    Map<String, Object> detail = new LinkedHashMap<>();
                    if (v.has("const")) {
                        detail.put("const", v.path("const").asText());
                    }
                    if (body.path("required").toString().contains("\"" + p.getKey() + "\"")) {
                        detail.put("required", true);
                    }
                    put(out, new Field(OTEL + ":message_property:" + qualified + "." + p.getKey(), OTEL, "message_property", qualified + "." + p.getKey(), locator,
                            v.has("type") ? v.path("type").asText() : v.has("$ref") ? "ref" : v.has("anyOf") ? "anyOf" : "any", null,
                            brief(v.path("description").asText(null)), detail));
                });
            }
            else {
                List<String> members = new ArrayList<>();
                collectEnum(body, members);
                put(out, new Field(OTEL + ":message_type:" + qualified, OTEL, "message_type", qualified, locator, body.path("type").asText(null), null,
                        brief(body.path("description").asText(null)), members.isEmpty() ? Map.of() : Map.of("values", members)));
            }
        }
        String boundary = "Every attribute of the GenAI, MCP, OpenAI and AWS Bedrock registries with each enum member; every span, span refinement, event, "
                + "metric, metric refinement and entity of the listed YAML files with its attribute references and requirement levels; every attribute "
                + "those definitions reference that the listed registries do not define (core semantic conventions, named only); and every property of "
                + "every $defs definition of the message, instruction, tool and retrieval/memory JSON Schemas (or the document itself when it has no "
                + "definitions). Attribute groups are reuse constructs: their member references are counted, the groups are not.";
        List<Map<String, String>> excluded = List.of(
                Map.of("what", "model/manifest.yaml", "reason", "manifest metadata (schema URL, dependencies), no execution field"),
                Map.of("what", "docs/** and examples in notes", "reason", "prose that explains the YAML definitions; the definitions are the inventory"),
                Map.of("what", "attribute_groups (as items)", "reason", "reuse constructs; every member reference is counted under its span/metric and as a referenced attribute"));
        return new Extraction(OTEL, files, boundary, excluded, new ArrayList<>(out.values()));
    }

    static void collectEnum(JsonNode n, List<String> into) {
        if (n.has("enum")) {
            n.path("enum").forEach(v -> into.add(v.asText()));
        }
        if (n.has("const")) {
            into.add(n.path("const").asText());
        }
        for (String k : List.of("anyOf", "oneOf")) {
            n.path(k).forEach(x -> collectEnum(x, into));
        }
    }

    static String requirement(JsonNode r) {
        if (r.isMissingNode() || r.isNull()) {
            return "";
        }
        if (r.isTextual()) {
            return " (" + r.asText() + ")";
        }
        StringBuilder sb = new StringBuilder();
        r.fields().forEachRemaining(e -> sb.append(" (").append(e.getKey()).append(": ").append(brief(e.getValue().asText())).append(')'));
        return sb.toString();
    }

    static String firstText(JsonNode n, String... keys) {
        for (String k : keys) {
            if (n.path(k).isTextual() && !n.path(k).asText().isBlank()) {
                return n.path(k).asText();
            }
        }
        return null;
    }

    // ---- OpenInference ---------------------------------------------------------------------------------------------------

    static final String OI_TRACE = "python/openinference-semantic-conventions/src/openinference/semconv/trace/__init__.py";
    static final String OI_RESOURCE = "python/openinference-semantic-conventions/src/openinference/semconv/resource/__init__.py";
    static final String OI_SPEC = "spec/semantic_conventions.md";
    static final Pattern TABLE_KEY = Pattern.compile("^\\|\\s*`([^`]+)`\\s*\\|");
    static final Pattern WELL_KNOWN = Pattern.compile("^`([a-z_.]+)` has the following list of well-known values");
    /** The package enum that holds each attribute's well-known values. */
    static final Map<String, String> VALUE_ENUMS = Map.of("llm.system", "OpenInferenceLLMSystemValues", "llm.provider", "OpenInferenceLLMProviderValues",
            "decision.system", "OpenInferenceDecisionSystemValues", "decision.provider", "OpenInferenceDecisionProviderValues");

    static final Pattern CLASS = Pattern.compile("^class (\\w+)(\\((\\w+)\\))?:", Pattern.MULTILINE);
    static final Pattern CONSTANT = Pattern.compile("^    ([A-Z][A-Z0-9_]*) = (\"([^\"]*)\"|(\\w+)\\.(\\w+)\\.value)\\s*$");

    /**
     * Every class constant of the trace and resource convention modules: attribute-key classes give one attribute per
     * constant (keyed by its string value); Enum classes give one enum member each, aliases resolved to the aliased
     * member's value. The docstring that follows a constant is its brief.
     */
    static Extraction openInference(Path root) {
        List<SourceFile> files = List.of(new SourceFile(OI_TRACE, gitBlob(bytes(root.resolve(OI_TRACE)))),
                new SourceFile(OI_RESOURCE, gitBlob(bytes(root.resolve(OI_RESOURCE)))), new SourceFile(OI_SPEC, gitBlob(bytes(root.resolve(OI_SPEC)))));
        Map<String, Field> out = new LinkedHashMap<>();
        Map<String, String> enumValues = new LinkedHashMap<>();
        for (SourceFile f : files.subList(0, 2)) {
            List<String> lines = text(root.resolve(f.path())).lines().toList();
            String cls = null;
            boolean isEnum = false;
            for (int i = 0; i < lines.size(); i++) {
                Matcher c = CLASS.matcher(lines.get(i));
                if (c.find()) {
                    cls = c.group(1);
                    isEnum = "Enum".equals(c.group(3));
                    continue;
                }
                Matcher m = CONSTANT.matcher(lines.get(i));
                if (cls == null || !m.matches()) {
                    continue;
                }
                String value = m.group(3) != null ? m.group(3) : enumValues.get(m.group(4) + "." + m.group(5));
                if (value == null) {
                    throw new IllegalStateException("unresolved alias " + lines.get(i));
                }
                String brief = docstring(lines, i + 1);
                String locator = f.path() + "#" + cls + "." + m.group(1);
                if (isEnum) {
                    enumValues.put(cls + "." + m.group(1), value);
                    Map<String, Object> detail = new LinkedHashMap<>();
                    detail.put("enum", cls);
                    if (m.group(4) != null) {
                        detail.put("alias_of", m.group(4) + "." + m.group(5));
                    }
                    put(out, new Field(OI + ":enum_member:" + cls + "=" + value, OI, "enum_member", cls + "=" + value, locator, "member", null, brief, detail));
                }
                else {
                    // one key declared by several classes (SpanAttributes.TOOL_NAME and ToolAttributes.TOOL_NAME) is one field with every locator
                    String id = OI + ":attribute:" + value;
                    Field prior = out.get(id);
                    if (prior == null) {
                        put(out, new Field(id, OI, "attribute", value, locator, null, null, brief, Map.of("classes", List.of(cls + "." + m.group(1)))));
                    }
                    else {
                        List<String> classes = new ArrayList<>(castList(prior.detail().get("classes")));
                        classes.add(cls + "." + m.group(1));
                        out.put(id, new Field(id, OI, "attribute", value, prior.locator() + ", " + locator, null, null, prior.brief() != null ? prior.brief() : brief,
                                Map.of("classes", classes)));
                    }
                }
            }
        }
        spec(text(root.resolve(OI_SPEC)), out);
        String boundary = "Every constant of every class in the trace and resource semantic-convention modules of the openinference-semantic-conventions "
                + "0.1.41 package: attribute keys (absolute or relative keys such as message.role used under indexed prefixes) and every enum member; plus, from "
                + "spec/semantic_conventions.md at the pinned spec head 7da3360, every reserved attribute and every well-known value that the package does not declare "
                + "(spec_attribute, spec_value).";
        List<Map<String, String>> excluded = List.of(
                Map.of("what", "other spec/*.md files at 7da3360 (llm_spans, tool_calling, multimodal_attributes, configuration, traces, decision_spans, embedding_spans, annotations)",
                        "reason", "prose and examples that explain the reserved attributes; their keys are in the reserved table; cited per field for meaning"),
                Map.of("what", "instrumentor packages (openinference-instrumentation-*)", "reason", "emitter behaviour of particular integrations, not the convention; covered by the October observations"));
        return new Extraction(OI, files, boundary, excluded, new ArrayList<>(out.values()));
    }

    /**
     * The spec's reserved-attribute table and well-known value tables: an attribute or value the package already declares
     * is that field; one only the spec names becomes a spec_attribute or spec_value field, so the two sources cannot
     * silently disagree.
     */
    static void spec(String md, Map<String, Field> out) {
        List<String> lines = md.lines().toList();
        String section = null;
        boolean reserved = false;
        String valuesOf = null;
        boolean inValues = false;
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            if (line.startsWith("## ")) {
                section = line.substring(3).strip();
                reserved = false;
                valuesOf = null;
                continue;
            }
            Matcher w = WELL_KNOWN.matcher(line);
            if (w.find()) {
                valuesOf = w.group(1);
                inValues = false;
                continue;
            }
            if ("Reserved Attributes".equals(section) && line.startsWith("| Attribute")) {
                reserved = true;
                continue;
            }
            if (!line.startsWith("|")) {
                // a table ends at its first non-row line; a value list's introductory prose may span lines before it
                reserved = false;
                if (inValues) {
                    valuesOf = null;
                    inValues = false;
                }
                continue;
            }
            Matcher k = TABLE_KEY.matcher(line);
            if (!k.find()) {
                continue;
            }
            String key = k.group(1);
            String locator = OI_SPEC + "#L" + (i + 1);
            if (valuesOf != null) {
                inValues = true;
                String member = OI + ":enum_member:" + VALUE_ENUMS.get(valuesOf) + "=" + key;
                if (VALUE_ENUMS.containsKey(valuesOf) && !out.containsKey(member)) {
                    put(out, new Field(OI + ":spec_value:" + valuesOf + "=" + key, OI, "spec_value", valuesOf + "=" + key, locator, "value", null,
                            brief(line.substring(k.end()).replace("|", "")), Map.of("attribute", valuesOf, "package_enum", VALUE_ENUMS.get(valuesOf))));
                }
            }
            else if (reserved && !out.containsKey(OI + ":attribute:" + key)) {
                put(out, new Field(OI + ":spec_attribute:" + key, OI, "spec_attribute", key, locator, null, null, "reserved in the spec; not declared by the 0.1.41 package",
                        Map.of()));
            }
        }
    }

    static String docstring(List<String> lines, int from) {
        if (from >= lines.size() || !lines.get(from).strip().startsWith("\"\"\"")) {
            return null;
        }
        StringBuilder sb = new StringBuilder(lines.get(from).strip().substring(3));
        int i = from;
        while (!sb.toString().contains("\"\"\"") && ++i < lines.size()) {
            sb.append(' ').append(lines.get(i).strip());
        }
        String s = sb.toString();
        int end = s.indexOf("\"\"\"");
        return brief(end < 0 ? s : s.substring(0, end));
    }

    // ---- Weave ---------------------------------------------------------------------------------------------------------

    static final String WEAVE_SCHEMA = "weave/shared/agents/schema.py";
    static final String WEAVE_TYPES = "weave/shared/agents/types.py";
    static final String WEAVE_SEMCONV = "weave/shared/agents/semconv.py";

    /** The record classes read: the span row, its message tuple, and the structured trajectory (chat) timeline payloads. */
    static final List<String> WEAVE_RECORDS = List.of("NormalizedMessage", "AgentSpanSchema", "AgentChatMessage", "AgentChatUserMessage",
            "AgentChatAssistantMessage", "AgentChatToolCall", "AgentChatAgentStart", "AgentChatAgentHandoff", "AgentChatContextCompacted", "AgentChatFeedback");
    static final List<String> WEAVE_LITERALS = List.of("SpanKindLiteral", "StatusCodeLiteral", "AgentChatMessageType");

    static final Pattern PY_FIELD = Pattern.compile("^    ([a-z_][a-z0-9_]*): (.+?)(?: = (.+))?$");
    static final Pattern ATTRIBUTE_CALL = Pattern.compile("^([A-Z][A-Z0-9_]*) = Attribute\\(", Pattern.MULTILINE);
    static final Pattern STRING = Pattern.compile("\"((?:[^\"\\\\]|\\\\.)*)\"");

    /**
     * The fields of the span row (AgentSpanSchema), its message tuple (NormalizedMessage) and the structured trajectory
     * timeline payloads; the members of their literal vocabularies; and every ingest attribute declared in semconv.py
     * with the OTel keys it recognises.
     */
    static Extraction weave(Path root) {
        List<SourceFile> files = List.of(new SourceFile(WEAVE_SCHEMA, gitBlob(bytes(root.resolve(WEAVE_SCHEMA)))),
                new SourceFile(WEAVE_TYPES, gitBlob(bytes(root.resolve(WEAVE_TYPES)))), new SourceFile(WEAVE_SEMCONV, gitBlob(bytes(root.resolve(WEAVE_SEMCONV)))));
        Map<String, Field> out = new LinkedHashMap<>();
        Map<String, String> declaredIn = new LinkedHashMap<>();
        for (String f : List.of(WEAVE_SCHEMA, WEAVE_TYPES)) {
            String src = text(root.resolve(f));
            for (String lit : WEAVE_LITERALS) {
                Matcher m = Pattern.compile("^" + lit + " = Literal\\[(.*?)\\]", Pattern.MULTILINE | Pattern.DOTALL).matcher(src);
                if (m.find()) {
                    Matcher s = STRING.matcher(m.group(1));
                    while (s.find()) {
                        put(out, new Field(WEAVE + ":enum_member:" + lit + "=" + s.group(1), WEAVE, "enum_member", lit + "=" + s.group(1), f + "#" + lit, "member", null, null,
                                Map.of("enum", lit)));
                    }
                    declaredIn.put(lit, f);
                }
            }
            List<String> lines = src.lines().toList();
            String cls = null;
            for (int i = 0; i < lines.size(); i++) {
                String line = lines.get(i);
                Matcher c = CLASS.matcher(line);
                if (c.find()) {
                    cls = WEAVE_RECORDS.contains(c.group(1)) ? c.group(1) : null;
                    if (cls != null) {
                        declaredIn.put(cls, f);
                    }
                    continue;
                }
                if (!line.isEmpty() && !Character.isWhitespace(line.charAt(0))) {
                    cls = null;
                }
                Matcher m = PY_FIELD.matcher(line);
                if (cls == null || !m.matches()) {
                    continue;
                }
                String name = cls + "." + m.group(1);
                Map<String, Object> detail = new LinkedHashMap<>();
                if (m.group(3) != null) {
                    detail.put("default", m.group(3));
                }
                put(out, new Field(WEAVE + ":field:" + name, WEAVE, "field", name, f + "#" + name, m.group(2), null, null, detail));
            }
        }
        for (String cls : WEAVE_RECORDS) {
            if (!declaredIn.containsKey(cls)) {
                throw new IllegalStateException("record class " + cls + " not found");
            }
        }
        String src = text(root.resolve(WEAVE_SEMCONV));
        Matcher a = ATTRIBUTE_CALL.matcher(src);
        while (a.find()) {
            List<String> args = arguments(src, a.end());
            List<String> key = literals(args.get(0));
            List<String> aliases = args.size() > 3 ? literals(args.get(3)) : List.of();
            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("constant", a.group(1));
            detail.put("recognized_keys", aliases);
            put(out, new Field(WEAVE + ":ingest_attribute:" + key.get(0), WEAVE, "ingest_attribute", key.get(0), WEAVE_SEMCONV + "#" + a.group(1),
                    String.join("", literals(args.get(1))), null, brief(String.join("", literals(args.get(2)))), detail));
        }
        String boundary = "The execution record model of weave 0.53.12: every annotated field of AgentSpanSchema (the agent span row), NormalizedMessage "
                + "(its message tuple) and the structured trajectory timeline (AgentChatMessage and its user, assistant, tool call, agent start, handoff, "
                + "context compaction and feedback payloads); the members of SpanKindLiteral, StatusCodeLiteral and AgentChatMessageType; and every "
                + "ingest Attribute of semconv.py with the OTel keys it recognises.";
        List<Map<String, String>> excluded = List.of(
                Map.of("what", "types.py query, statistics, search, grouping, distribution, filter and sort request/response classes (AgentSpanStats*, "
                        + "AgentSpanGroup*, AgentSpansQuery*, AgentSearch*, AgentConversationSpans*, AgentTraceChatReq/Res, AgentConversationChatReq/Res, "
                        + "AgentCustomAttrs*, AgentsQueryFilters, AgentSortBy, AgentGroupByRef, RatingCondition, AgentSignalFilter, AgentInsightFilter, "
                        + "AgentSpanValueRef, AgentSpanMeasureSpec)", "reason", "hosted query API envelopes and specifications, not execution records"),
                Map.of("what", "types.py aggregate listing rows (AgentSchema, AgentSpanGroupRow, AgentConversationSpan, AgentConversationSpanRating, "
                        + "AgentConversationSpanFeedback, AgentConversationMessagePreview, AgentSearchMatchedMessage, AgentSearchConversationResult)",
                        "reason", "query-time aggregates or previews computed from AgentSpanSchema rows; their execution facts are the span fields"),
                Map.of("what", "genai_extraction.py and span_costs.py", "reason", "ingest and query-time cost behaviour, cited per field as evidence of meaning; not a field declaration"));
        return new Extraction(WEAVE, files, boundary, excluded, new ArrayList<>(out.values()));
    }

    /** The top-level comma-separated arguments of the call whose opening parenthesis ends at {@code from}, comments removed. */
    static List<String> arguments(String src, int from) {
        List<String> args = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        int depth = 0;
        boolean inString = false;
        for (int i = from; i < src.length(); i++) {
            char ch = src.charAt(i);
            if (inString) {
                cur.append(ch);
                if (ch == '\\') {
                    cur.append(src.charAt(++i));
                }
                else if (ch == '"') {
                    inString = false;
                }
                continue;
            }
            if (ch == '#') {
                while (i < src.length() && src.charAt(i) != '\n') {
                    i++;
                }
                continue;
            }
            if (ch == '"') {
                inString = true;
                cur.append(ch);
            }
            else if (ch == '(' || ch == '[') {
                depth++;
                cur.append(ch);
            }
            else if (ch == ')' || ch == ']') {
                if (depth == 0) {
                    args.add(cur.toString().strip());
                    return args.stream().filter(s -> !s.isEmpty()).toList();
                }
                depth--;
                cur.append(ch);
            }
            else if (ch == ',' && depth == 0) {
                args.add(cur.toString().strip());
                cur.setLength(0);
            }
            else {
                cur.append(ch);
            }
        }
        throw new IllegalStateException("unterminated call");
    }

    @SuppressWarnings("unchecked")
    static List<String> castList(Object o) {
        return (List<String>) o;
    }

    static List<String> literals(String arg) {
        List<String> out = new ArrayList<>();
        Matcher s = STRING.matcher(arg);
        while (s.find()) {
            out.add(s.group(1));
        }
        return out;
    }

    static void put(Map<String, Field> out, Field f) {
        if (out.putIfAbsent(f.id(), f) != null) {
            throw new IllegalStateException("duplicate inventory id " + f.id());
        }
    }

    // ---- document ------------------------------------------------------------------------------------------------------

    /** The inventory document: per foundation its files with blob ids, boundary, exclusions, counts per kind and fields. */
    static Map<String, Object> document(List<Extraction> extractions, Map<String, String> revisions) {
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("kind", "agent-journal/foundation-fields");
        doc.put("version", "0.1");
        doc.put("meaning", "The declared items of the three Tier 1 foundations at their pins (foundation-pins.json), derived mechanically by FoundationFields "
                + "from the pinned source files (git blob ids below). Each item is one field of the parity ledger's denominator; foundation-parity.json "
                + "accounts for every one exactly once. Extraction is syntactic: it records what the source declares, not what it means for Journal.");
        doc.put("derived_by", "FoundationFieldsTest#theInventoryIsExactlyWhatThePinnedSourcesDeclare (-Djournal.foundation.sources=<dir with one subdirectory per foundation id>)");
        List<Map<String, Object>> foundations = new ArrayList<>();
        for (Extraction e : extractions) {
            Map<String, Object> f = new LinkedHashMap<>();
            f.put("foundation", e.foundation());
            f.put("revision", revisions.get(e.foundation()));
            List<Map<String, String>> files = new ArrayList<>();
            e.files().forEach(s -> files.add(Map.of("path", s.path(), "blob", s.blob())));
            f.put("files", files);
            f.put("boundary", e.boundary());
            f.put("excluded", e.excluded());
            Map<String, Integer> counts = new TreeMap<>();
            e.fields().forEach(x -> counts.merge(x.kind(), 1, Integer::sum));
            f.put("counts", counts);
            f.put("total", e.fields().size());
            List<Map<String, Object>> fields = new ArrayList<>();
            for (Field x : e.fields()) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", x.id());
                m.put("kind", x.kind());
                m.put("name", x.name());
                m.put("locator", x.locator());
                if (x.type() != null) {
                    m.put("type", x.type());
                }
                if (x.stability() != null) {
                    m.put("stability", x.stability());
                }
                if (x.brief() != null) {
                    m.put("brief", x.brief());
                }
                if (!x.detail().isEmpty()) {
                    m.put("detail", x.detail());
                }
                fields.add(m);
            }
            f.put("fields", fields);
            foundations.add(f);
        }
        doc.put("foundations", foundations);
        return doc;
    }
}
