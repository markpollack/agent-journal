package io.github.markpollack.journal.tck.ocsf;

import io.github.markpollack.journal.tck.verify.Json;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Typed access to {@code run.json} of a self-describing prototype package (layout {@code 0.1.0-prototype}): package
 * and reader-compatibility pins, run identity and children, OCSF/extension pins, the embedded definition closure,
 * the shared per-run facts (CLI agent, producer, capture time, mapping contracts), the source inventory and the
 * semantic contract. Nothing here interprets event content.
 */
public final class RunManifest {

    public static final String PACKAGE_KIND = "agent-journal/ocsf-prototype-package";

    private final Json json;

    public RunManifest(Json json) {
        this.json = json;
    }

    public Json json() {
        return json;
    }

    public String text(String... path) {
        Json v = json.path(path);
        return v == null ? null : v.text();
    }

    public Integer integer(String... path) {
        return Event.integer(json.path(path));
    }

    public String packageKind() {
        return text("package", "kind");
    }

    public String packageVersion() {
        return text("package", "version");
    }

    public String readerContract() {
        return text("package", "reader_compat", "reader_contract");
    }

    public String readerContractVersion() {
        return text("package", "reader_compat", "reader_contract_version");
    }

    public String semanticOperationsVersion() {
        return text("package", "reader_compat", "semantic_operations_version");
    }

    public String eventsSha256() {
        return text("package", "events_jsonl_sha256");
    }

    /** {@code RECORDING}, {@code COMPLETED}, {@code INTERRUPTED} or {@code FAILED}; a 0.1.0 package without a state is final. */
    public String packageState() {
        String s = text("package", "state");
        return s == null ? "COMPLETED" : s;
    }

    /** Whether the package's events are finalized (counts and hash final). */
    public boolean finalized() {
        String s = packageState();
        return "COMPLETED".equals(s) || "INTERRUPTED".equals(s);
    }

    /** Whether a source's inventory is final (its bytes ended); a 0.1.0 source without the flag is final. */
    public static boolean sourceFinal(Json source) {
        Json f = source == null ? null : source.member("final");
        return !(f instanceof Json.JBool b) || b.value();
    }

    public String runUid() {
        return text("run", "run_uid");
    }

    public String sessionUid() {
        return text("run", "session_uid");
    }

    public String cliVersion() {
        return text("run", "cli", "version");
    }

    public String cliName() {
        return text("run", "cli", "name");
    }

    public Json parent() {
        return json.path("run", "parent");
    }

    public List<Json> children() {
        Json c = json.path("run", "children");
        return c == null ? List.of() : c.elements();
    }

    /** The owner-scoped source selector of a split core, or {@code null} when the core owns its artifacts whole. */
    public Json sourceScope() {
        return json.path("run", "source_scope");
    }

    public String extensionName() {
        return text("extension", "name");
    }

    public Integer extensionUid() {
        return integer("extension", "uid");
    }

    public String extensionVersion() {
        return text("extension", "version");
    }

    /** Pinned class name to class_uid. */
    public Map<String, Integer> classTable() {
        Map<String, Integer> out = new LinkedHashMap<>();
        Json classes = json.path("extension", "classes");
        if (classes instanceof Json.JObject o) {
            o.members().forEach((k, v) -> out.put(k, Event.integer(v.member("class_uid"))));
        }
        return out;
    }

    public Json definitions() {
        return json.member("definitions");
    }

    public Json closure() {
        return json.path("definitions", "closure");
    }

    public Json shared() {
        return json.member("shared");
    }

    public Json contract(String uid) {
        return json.path("shared", "contracts", uid);
    }

    public Map<String, Json> contracts() {
        Map<String, Json> out = new LinkedHashMap<>();
        Json c = json.path("shared", "contracts");
        if (c instanceof Json.JObject o) {
            out.putAll(o.members());
        }
        return out;
    }

    public List<Json> sources() {
        Json s = json.member("sources");
        return s == null ? List.of() : s.elements();
    }

    /** The compatibility selection in force for a declared source, or {@code null} when the package declares none. */
    public Json compatibility(String artifact) {
        Json s = source(artifact);
        return s == null ? null : s.member("compatibility");
    }

    public Json source(String artifact) {
        for (Json s : sources()) {
            if (artifact != null && artifact.equals(SchemaCatalog.text(s, "artifact"))) {
                return s;
            }
        }
        return null;
    }

    public Json semanticContract() {
        return json.member("semantic_contract");
    }
}
