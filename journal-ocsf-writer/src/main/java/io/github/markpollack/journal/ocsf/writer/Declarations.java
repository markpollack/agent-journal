package io.github.markpollack.journal.ocsf.writer;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The declaration records of the standalone event file (layout {@code 0.3.0-prototype}): the lines that make
 * {@code events.jsonl} independently interpretable. Every declaration is one JSON object whose top-level member
 * {@code declaration} is an object {@code {kind, uid, index}}; the payload sits under a member named after the kind.
 * Execution events never carry a top-level {@code declaration} member, which is the one rule a reader needs to tell
 * the two apart (and the rule the upstream OCSF validator step uses to select execution records).
 *
 * <p>Kinds, in the order a file can contain them:
 * <ul>
 * <li>{@code format} (always line 1): file format and version, reader compatibility, the record rule, the shape of
 * every declaration kind (in the compiled-definition attribute form the reader already validates events with), and
 * what the ancillary {@code run.json} beside the file may add.</li>
 * <li>{@code schema}: the OCSF and extension pins, the class table, the time policy and the definition policy.</li>
 * <li>{@code run}: run and invocation identity, CLI product, launch settings, parent and owner scope (child files),
 * producer and capture time.</li>
 * <li>{@code contract}: a mapping contract embedded by value with its hash. A contract uid identifies a JSON mapping
 * description, not a JSON Schema; mapper identity is provenance, not something the reader executes.</li>
 * <li>{@code source}: a declared native source (artifact, kind, profile, surface, contract).</li>
 * <li>{@code definition}: one compiled class, object or scalar-type definition, or an amendment adding attributes to
 * one declared earlier ({@code amends}); written before the first event that uses it.</li>
 * <li>{@code session}: the native session id once the source reports it.</li>
 * <li>{@code cli_version}: the CLI version once the selected stream reports it (a capture launched without knowing
 * the version retains the records that arrive before it; no version command is run).</li>
 * <li>{@code compatibility}: the compatibility selection for a source (entry, reviewed range, tested evidence and
 * the status tested, in_range, no_match or version_unknown); declared with the source and again when a version
 * becomes known, so the latest declaration for an artifact is the one in force (RULE-10, RULE-33).</li>
 * <li>{@code child}: a linked child event file (location and spawn identity), never its content.</li>
 * <li>{@code source_end}: how a source ended, with its final extent, hash and family inventory.</li>
 * <li>{@code recording}: the writer's finalization of this file (COMPLETED or INTERRUPTED) with the acquisition
 * status and, for a capture that launched the CLI, the process outcome (how it ended, its exit code, a cancellation
 * reason): storage finalization, acquisition extent and CLI outcome are three distinct facts; a file without one is
 * an open prefix (or the writer failed, which only the ancillary manifest can say).</li>
 * </ul>
 *
 * Declarations are append-only and immutable once written; later facts arrive as new declarations with their own
 * identity. Nothing here buffers the stream or rewrites an earlier line.
 */
final class Declarations {

    static final String FORMAT_NAME = "agent-journal/events-jsonl";
    static final String RECORD_RULE = "a line whose top-level member 'declaration' is an object is a declaration record; every other line is an OCSF execution event carrying class_uid";

    private Declarations() {
    }

    /** The shape of each declaration kind, in the compiled attribute form ({@code type}, {@code object_type}, {@code requirement}). */
    static Jv.Obj declarationDefinitions() {
        Jv.Obj objects = new Jv.Obj();
        objects.put("declaration/header", new Jv.Obj().put("attributes", new Jv.Obj()
                .put("kind", scalar("string_t", "required")).put("uid", scalar("string_t", "required")).put("index", scalar("integer_t", "required"))));
        declare(objects, "format", new Jv.Obj()
                .put("name", scalar("string_t", "required")).put("version", scalar("string_t", "required")).put("non_production", scalar("boolean_t", "required"))
                .put("package_kind", scalar("string_t", "required")).put("package_version", scalar("string_t", "required"))
                .put("reader_contract", scalar("string_t", "required")).put("reader_contract_version", scalar("string_t", "required"))
                .put("semantic_operations_version", scalar("string_t", "optional")).put("record_rule", scalar("string_t", "required"))
                .put("upstream_validation", scalar("string_t", "optional")).put("lifecycle", scalar("string_t", "optional"))
                .put("ancillary", scalar("string_t", "optional")).put("declaration_definitions", scalar("json_t", "required")));
        declare(objects, "schema", new Jv.Obj()
                .put("ocsf", scalar("json_t", "required")).put("extension", scalar("json_t", "required"))
                .put("time_policy", scalar("json_t", "optional")).put("definition_policy", scalar("string_t", "optional")));
        declare(objects, "run", new Jv.Obj()
                .put("run_uid", scalar("string_t", "required")).put("invocation_index", scalar("integer_t", "required")).put("capture_start", scalar("string_t", "required"))
                .put("cli", scalar("json_t", "required")).put("launch_settings", scalar("json_t", "optional")).put("synthetic", scalar("json_t", "optional"))
                .put("parent", scalar("json_t", "optional")).put("source_scope", scalar("json_t", "optional")).put("producer", scalar("json_t", "required"))
                .put("written_by", scalar("json_t", "required")).put("capture_time", scalar("long_t", "required")).put("capture_time_text", scalar("string_t", "required"))
                .put("started", scalar("string_t", "required")));
        declare(objects, "contract", new Jv.Obj()
                .put("contract_uid", scalar("string_t", "required")).put("sha256", scalar("string_t", "required")).put("source_file_sha256", scalar("string_t", "optional"))
                .put("embedded", scalar("json_t", "required")).put("note", scalar("string_t", "optional")));
        declare(objects, "source", new Jv.Obj()
                .put("artifact", scalar("string_t", "required")).put("kind", scalar("integer_t", "required")).put("profile", scalar("string_t", "required"))
                .put("route", scalar("string_t", "optional"))
                .put("surface", scalar("string_t", "optional")).put("path_at_capture", scalar("string_t", "optional")).put("contract_uid", scalar("string_t", "required")));
        declare(objects, "definition", new Jv.Obj()
                .put("kind_of", scalar("string_t", "required")).put("key", scalar("string_t", "required")).put("amends", scalar("string_t", "optional"))
                .put("sha256", scalar("string_t", "required")).put("body", scalar("json_t", "required")));
        declare(objects, "session", new Jv.Obj()
                .put("session_uid", scalar("string_t", "required")).put("basis", scalar("string_t", "optional")));
        declare(objects, "cli_version", new Jv.Obj()
                .put("version", scalar("string_t", "required")).put("basis", scalar("string_t", "required")).put("record_index", scalar("integer_t", "optional"))
                .put("artifact", scalar("string_t", "optional")));
        declare(objects, "compatibility", new Jv.Obj()
                .put("artifact", scalar("string_t", "required")).put("cli", scalar("string_t", "required")).put("profile", scalar("string_t", "required"))
                .put("route", scalar("string_t", "required")).put("version", scalar("string_t", "optional")).put("version_basis", scalar("string_t", "required"))
                .put("status", scalar("string_t", "required")).put("detail", scalar("string_t", "required")).put("records_before_selection", scalar("integer_t", "required"))
                .put("normalizes", scalar("string_t", "required")).put("entry_id", scalar("string_t", "optional")).put("min_version", scalar("string_t", "optional"))
                .put("max_version", scalar("string_t", "optional")).put("source_specification", scalar("json_t", "optional")).put("schema", scalar("json_t", "optional"))
                .put("mapper", scalar("string_t", "optional")).put("contract", scalar("string_t", "optional")).put("tested", scalar("json_t", "optional"))
                .put("note", scalar("string_t", "optional")).put("schema_sha256_in_use", scalar("string_t", "optional")));
        declare(objects, "child", new Jv.Obj()
                .put("run_uid", scalar("string_t", "required")).put("path", scalar("string_t", "required")).put("spawn_tool_call_uid", scalar("string_t", "required"))
                .put("basis", scalar("string_t", "optional")).put("parent_scope", scalar("json_t", "optional")));
        declare(objects, "source_end", new Jv.Obj()
                .put("artifact", scalar("string_t", "required")).put("how", scalar("string_t", "required")).put("at", scalar("string_t", "required"))
                .put("records", scalar("integer_t", "required")).put("bytes", scalar("long_t", "required")).put("sha256", scalar("string_t", "required"))
                .put("opaque_records", scalar("integer_t", "optional")).put("trailing_newline", scalar("boolean_t", "optional"))
                .put("incomplete_tail_record", scalar("integer_t", "optional")).put("first_arrival", scalar("string_t", "optional"))
                .put("last_arrival", scalar("string_t", "optional")).put("family_inventory", scalar("json_t", "optional")));
        declare(objects, "recording", new Jv.Obj()
                .put("state", scalar("string_t", "required")).put("started", scalar("string_t", "required")).put("ended", scalar("string_t", "required"))
                .put("interruption_reason", scalar("string_t", "optional")).put("acquisition", scalar("json_t", "required"))
                .put("process", scalar("json_t", "optional"))
                .put("events_before", scalar("integer_t", "required")).put("declarations_before", scalar("integer_t", "required")));
        return objects;
    }

    /** Declares {@code declaration/<kind>} (header plus body reference) and {@code declaration/<kind>_body} (the body's attributes). */
    private static void declare(Jv.Obj objects, String kind, Jv.Obj bodyAttributes) {
        Jv.Obj attrs = new Jv.Obj();
        attrs.put("declaration", new Jv.Obj().put("object_type", "declaration/header").put("requirement", "required"));
        attrs.put(kind, new Jv.Obj().put("object_type", "declaration/" + kind + "_body").put("requirement", "required"));
        objects.put("declaration/" + kind, new Jv.Obj().put("caption", "Declaration: " + kind).put("name", kind).put("attributes", attrs));
        objects.put("declaration/" + kind + "_body", new Jv.Obj().put("attributes", bodyAttributes));
    }

    private static Jv scalar(String type, String requirement) {
        return new Jv.Obj().put("type", type).put("requirement", requirement);
    }

    /** The record envelope. */
    static Jv.Obj record(String kind, String uid, int index, Jv.Obj payload) {
        return new Jv.Obj().put("declaration", new Jv.Obj().put("kind", kind).put("uid", uid).put("index", index)).put(kind, payload);
    }

    static Jv.Obj format() {
        Jv.Obj f = new Jv.Obj().put("name", FORMAT_NAME).put("version", Pins.PACKAGE_VERSION).put("non_production", true)
                .put("package_kind", Pins.PACKAGE_KIND).put("package_version", Pins.PACKAGE_VERSION)
                .put("reader_contract", Pins.READER_CONTRACT).put("reader_contract_version", Pins.READER_CONTRACT_VERSION)
                .put("semantic_operations_version", Pins.SEMANTIC_OPERATIONS_VERSION)
                .put("record_rule", RECORD_RULE)
                .put("upstream_validation", "the pinned upstream OCSF validator is run on execution records only (every line without a top-level 'declaration' member); declaration records are validated by the reader against declaration_definitions")
                .put("lifecycle", "declarations are append-only and written before the first event that references them; a 'recording' declaration marks finalization (COMPLETED or INTERRUPTED); a file without one is an open prefix, or the writer failed, which only the ancillary run.json can state")
                .put("ancillary", "run.json beside this file is an index and summary (final hash, counts, failure detail, extension source, semantic contract, mapping audit); it is not needed to interpret this file and may be absent")
                .put("declaration_definitions", new Jv.Obj().put("objects", declarationDefinitions()));
        return f;
    }

    static Jv.Obj schema(SchemaPin pin, String extensionSourceSha256) {
        Jv.Obj ocsf = new Jv.Obj().put("version", pin.ocsfVersion()).put("core_commit", Pins.OCSF_SCHEMA_COMMIT).put("compiler_commit", Pins.OCSF_COMPILER_COMMIT)
                .put("toolkit", Pins.ocsfPins()).put("compiled_schema_sha256", pin.compiledSha256());
        if (pin.excerptSha256() != null) {
            ocsf.put("compiled_excerpt_sha256", pin.excerptSha256());
        }
        Jv.Obj extension = new Jv.Obj().put("name", SchemaPin.EXTENSION).put("uid", pin.extensionUid()).put("uid_status", Pins.EXTENSION_UID_STATUS)
                .put("version", pin.extensionVersion()).put("source_sha256", extensionSourceSha256).put("classes", pin.classTable())
                .put("reader_rule", "resolve class names through this table; refuse a file whose pinned uid/class table differs from the reader's compiled schema");
        return new Jv.Obj().put("ocsf", ocsf).put("extension", extension)
                .put("time_policy", new Jv.Obj().put("time", "source-reported time when the record has one (time_basis 1); else the latest preceding reported time in the same artifact, else capture start (time_basis 2); metadata.original_time holds the source text verbatim; ordering is journal.sequence, never time")
                        .put("severity_id", "always 0 Unknown: Journal convention, not a vendor assertion"))
                .put("definition_policy", "each class, object and scalar type is declared before the first event that uses it: extension-defined classes and objects complete; core objects with their required attributes plus those the events use, amended by a later definition record when an event uses a further attribute; profile-only attributes of profiles the events do not enable omitted");
    }

    /** Per-definition body hash, over the canonical bytes of the body. */
    static String sha256(Jv body) {
        return SchemaPin.sha256(Jv.write(body).getBytes(StandardCharsets.UTF_8));
    }

    /** The set of attribute names a definition body declares. */
    static Set<String> attributeNames(Jv body) {
        Set<String> out = new LinkedHashSet<>();
        Jv attrs = body.member("attributes");
        if (attrs instanceof Jv.Obj o) {
            out.addAll(o.members().keySet());
        }
        return out;
    }

    /** A definition body restricted to the given attribute names (for an amendment), keeping the identity members. */
    static Jv.Obj restrict(Jv body, Set<String> names) {
        Jv.Obj out = new Jv.Obj();
        for (String k : List.of("caption", "name", "uid", "category_uid", "extends", "extension", "extension_id")) {
            if (body.member(k) != null) {
                out.put(k, body.member(k));
            }
        }
        Jv.Obj attrs = new Jv.Obj();
        for (Map.Entry<String, Jv> a : ((Jv.Obj) body.member("attributes")).members().entrySet()) {
            if (names.contains(a.getKey())) {
                attrs.put(a.getKey(), a.getValue());
            }
        }
        out.put("attributes", attrs);
        return out;
    }

    /** A definition body without the per-closure bookkeeping counters. */
    static Jv.Obj body(Jv def) {
        Jv.Obj out = new Jv.Obj();
        for (Map.Entry<String, Jv> m : ((Jv.Obj) def).members().entrySet()) {
            if (!m.getKey().startsWith("omitted_")) {
                out.put(m.getKey(), m.getValue());
            }
        }
        return out;
    }

    static Map<String, Jv> copyOf(Jv.Obj o) {
        return new LinkedHashMap<>(o.members());
    }
}
