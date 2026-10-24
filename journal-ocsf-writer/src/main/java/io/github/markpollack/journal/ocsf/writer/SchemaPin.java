package io.github.markpollack.journal.ocsf.writer;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * The compiled OCSF schema the writer pins: class and category identifiers are never hand-written; they are read
 * from the output of the pinned {@code ocsf-schema-compiler} (the whole {@code compiled.json}, or the bundled
 * excerpt {@link #BUNDLED_EXCERPT} that holds the extension's classes, every object reachable from them and the
 * scalar types, plus the full document's hash). The pin and the bundled extension source are embedded in every
 * {@code run.json}.
 */
public final class SchemaPin {

    public static final String EXTENSION = "agentic_cli";
    public static final String BUNDLED_EXCERPT = "/ocsf/compiled-excerpt-agentic_cli-0.12.0-prototype.json";

    /** One written class as the compiled schema defines it. */
    public record ClassDef(String name, String caption, int classUid, int categoryUid, String categoryName, Map<String, String> activities) {
        public String activityName(int activity) {
            String n = activities.get(Integer.toString(activity));
            if (n == null) {
                throw new IllegalArgumentException("class " + name + " has no activity " + activity);
            }
            return n;
        }
    }

    private final Jv compiled;
    private final String compiledSha256;
    private final String excerptSha256;
    private final String ocsfVersion;
    private final int extensionUid;
    private final String extensionVersion;
    private final Map<String, ClassDef> classes = new TreeMap<>();

    private SchemaPin(byte[] bytes, boolean excerpt) {
        this.compiled = Jv.parse(new String(bytes, StandardCharsets.UTF_8));
        String own = sha256(bytes);
        Jv source = compiled.member("source_compiled_schema");
        this.compiledSha256 = excerpt && source != null ? source.member("sha256").text() : own;
        this.excerptSha256 = excerpt ? own : null;
        this.ocsfVersion = compiled.member("version").text();
        Jv ext = compiled.path("extensions", EXTENSION);
        if (ext == null) {
            throw new IllegalArgumentException("compiled schema carries no extension " + EXTENSION);
        }
        this.extensionUid = (int) ((Jv.Num) ext.member("uid")).value().longValueExact();
        this.extensionVersion = ext.member("version").text();
        for (Map.Entry<String, Jv> c : ((Jv.Obj) compiled.member("classes")).members().entrySet()) {
            if (!c.getKey().startsWith(EXTENSION + "/")) {
                continue;
            }
            Jv cls = c.getValue();
            Map<String, String> acts = new LinkedHashMap<>();
            for (Map.Entry<String, Jv> a : ((Jv.Obj) cls.path("attributes", "activity_id", "enum")).members().entrySet()) {
                acts.put(a.getKey(), a.getValue().member("caption").text());
            }
            classes.put(cls.member("name").text(), new ClassDef(cls.member("name").text(), cls.member("caption").text(),
                    (int) ((Jv.Num) cls.member("uid")).value().longValueExact(), (int) ((Jv.Num) cls.member("category_uid")).value().longValueExact(),
                    cls.member("category_name") == null ? null : cls.member("category_name").text(), acts));
        }
    }

    /** The excerpt bundled with this writer (see {@code SchemaExcerpt}). */
    public static SchemaPin bundled() {
        try (InputStream in = SchemaPin.class.getResourceAsStream(BUNDLED_EXCERPT)) {
            if (in == null) {
                throw new IllegalStateException("missing resource " + BUNDLED_EXCERPT);
            }
            return new SchemaPin(in.readAllBytes(), true);
        }
        catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** A whole {@code compiled.json} written by the pinned compiler. */
    public static SchemaPin of(Path compiledJson) {
        try {
            return new SchemaPin(Files.readAllBytes(compiledJson), false);
        }
        catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public Jv compiled() {
        return compiled;
    }

    public String compiledSha256() {
        return compiledSha256;
    }

    /** The bundled excerpt's own hash, or {@code null} when the whole compiled schema was loaded. */
    public String excerptSha256() {
        return excerptSha256;
    }

    public String ocsfVersion() {
        return ocsfVersion;
    }

    public int extensionUid() {
        return extensionUid;
    }

    public String extensionVersion() {
        return extensionVersion;
    }

    public ClassDef classNamed(String name) {
        ClassDef c = classes.get(name);
        if (c == null) {
            throw new IllegalArgumentException("class " + name + " is not defined by extension " + EXTENSION + " in the pinned schema");
        }
        return c;
    }

    public Map<String, ClassDef> classes() {
        return classes;
    }

    public Map<String, Integer> classUids() {
        Map<String, Integer> out = new TreeMap<>();
        classes.forEach((n, c) -> out.put(n, c.classUid()));
        return out;
    }

    /** The class table pinned into {@code run.json}: {@code {name: {class_uid, category_uid}}}. */
    public Jv classTable() {
        Jv.Obj table = new Jv.Obj();
        for (ClassDef c : classes.values()) {
            table.put(c.name(), new Jv.Obj().put("class_uid", c.classUid()).put("category_uid", c.categoryUid()));
        }
        return table;
    }

    // ------------------------------------------------------------------------------------------ extension source

    /** The hand-written files of the extension source, bundled under {@code ocsf/extension/<name>/}. */
    public static final List<String> AUTHORED_SOURCE_FILES = List.of(
            "categories.json", "dictionary.json", "extension.json",
            "events/agent_execution/model_response.json", "events/agent_execution/prompt_turn.json",
            "events/agent_execution/session_context.json", "events/agent_execution/source_record.json",
            "events/agent_execution/tool_call.json",
            "objects/assembly_conflict.json", "objects/assembly_record.json", "objects/attribution.json", "objects/cache_write_tier.json",
            "objects/cli_agent.json", "objects/content_block.json", "objects/cost_observation.json", "objects/error_info.json",
            "objects/field_evidence.json", "objects/journal_provenance.json", "objects/json_schema.json", "objects/mcp_observation.json",
            "objects/model_ref.json", "objects/observation_ref.json", "objects/operation.json", "objects/permission_decision.json",
            "objects/session_settings.json", "objects/source_ref.json", "objects/tool_definition.json", "objects/tool_result.json",
            "objects/usage_component.json", "objects/usage_observation.json");

    /**
     * Every file of the extension source: the authored files plus the MCP protocol objects {@link McpBindingGenerator}
     * generated, which the bundled binding lists.
     */
    public static final List<String> EXTENSION_SOURCE_FILES = sourceFiles();

    private static List<String> sourceFiles() {
        List<String> all = new java.util.ArrayList<>(AUTHORED_SOURCE_FILES);
        for (Jv f : McpBinding.bundled().document().member("extension_files").elements()) {
            all.add(f.text());
        }
        return List.copyOf(all);
    }

    /**
     * Reads the bundled extension source and returns the hash over every file's path and bytes. {@code files} receives
     * each authored file parsed and, for each generated MCP protocol object, its sha256 and generator (the generated
     * files are reproducible from the pinned MCP schemas and the binding, so the ancillary manifest names them by hash).
     */
    public static String extensionSource(Map<String, Jv> files) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (String rel : EXTENSION_SOURCE_FILES) {
                try (InputStream in = SchemaPin.class.getResourceAsStream("/ocsf/extension/" + EXTENSION + "/" + rel)) {
                    if (in == null) {
                        throw new IllegalStateException("missing bundled extension source " + rel);
                    }
                    byte[] bytes = in.readAllBytes();
                    digest.update(rel.getBytes(StandardCharsets.UTF_8));
                    digest.update(bytes);
                    files.put(rel, AUTHORED_SOURCE_FILES.contains(rel) ? Jv.parse(new String(bytes, StandardCharsets.UTF_8))
                            : new Jv.Obj().put("generated_by", McpBindingGenerator.class.getSimpleName()).put("sha256", sha256(bytes)));
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        }
        catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public static String sha256(byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        }
        catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
