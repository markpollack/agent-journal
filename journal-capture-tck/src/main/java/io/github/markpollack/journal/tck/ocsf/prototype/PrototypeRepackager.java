package io.github.markpollack.journal.tck.ocsf.prototype;

import io.github.markpollack.journal.tck.ocsf.NativeArtifact;
import io.github.markpollack.journal.tck.ocsf.PackageReader;
import io.github.markpollack.journal.tck.ocsf.SemanticContract;
import io.github.markpollack.journal.tck.ocsf.SemanticOperation;
import io.github.markpollack.journal.tck.verify.Json;
import io.github.markpollack.journal.tck.verify.NeutralJson;

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
import java.util.stream.Stream;

/**
 * NON-PRODUCTION candidate support: turns a package written by the Python prototype adapter (layout
 * {@code 0.0.0-prototype}: every event repeats the CLI agent, producer, capture time, contract and artifact hashes)
 * into the self-describing layout {@code 0.1.0-prototype} the Java reader and oracle judge:
 *
 * <ul>
 * <li>invariant per-run facts move once into {@code run.json} under {@code shared}; events keep compact references
 * ({@code journal.run_uid}, {@code journal.contract_uid}, {@code source_ref.artifact}) and every required OCSF field;</li>
 * <li>the data-definition closure ({@link DefinitionClosure}, written-attribute policy) and the extension source are
 * embedded by value with hashes; the compiled schema is referenced by hash;</li>
 * <li>the mapping contracts stay embedded by value, re-hashed over their canonical form;</li>
 * <li>the semantic contract ({@link SemanticContract#RESOURCE}) is embedded with its hash;</li>
 * <li>{@code attribution.method_id} (0.2.0 extension) is set from the attribution's declared rule;</li>
 * <li>optionally, events are split into a parent core and child cores by an owner path on the native record
 * (for example Claude's {@code parent_tool_use_id}); each core is independently portable and names its scope.</li>
 * </ul>
 *
 * <p>This is a re-layout of existing prototype output for the proof, not a production writer and not an adapter: it
 * reads no native source and interprets no vendor record beyond the owner path. The oracle never calls it.
 */
public final class PrototypeRepackager {

    public static final String PACKAGE_VERSION = "0.1.0-prototype";
    public static final String REPACKAGER_VERSION = "0.1.0-prototype";

    private static final Map<String, Integer> METHOD_BY_RULE = Map.of(
            "tool_result_meta[].id", 1,
            "record-level permissionDecision", 1,
            "codex.operation_attribution", 2,
            "codex.stream_operation_attribution", 2,
            "agy.denial_candidates", 2,
            "permission_denials entry", 2);

    private final Json compiled;
    private final String compiledSha256;
    private final Path extensionDir;

    /** {@code <prototype-dir> <compiled.json> <extension-dir> <out-dir> [<owner-path> <owner-artifact>]}. */
    public static void main(String[] args) {
        if (args.length != 4 && args.length != 6) {
            System.err.println("usage: <prototype-dir> <compiled.json> <extension-dir> <out-dir> [<owner-path> <owner-artifact>]");
            System.exit(64);
        }
        new PrototypeRepackager(Path.of(args[1]), Path.of(args[2])).repackage(Path.of(args[0]), Path.of(args[3]),
                args.length == 6 ? args[4] : null, args.length == 6 ? args[5] : null);
        System.out.println("repackaged " + args[0] + " -> " + args[3]);
    }

    public PrototypeRepackager(Path compiledSchema, Path extensionDir) {
        byte[] bytes = read(compiledSchema);
        this.compiled = NeutralJson.parse(new String(bytes, StandardCharsets.UTF_8));
        this.compiledSha256 = NativeArtifact.sha256(bytes);
        this.extensionDir = extensionDir;
    }

    /**
     * @param prototypeDir a {@code 0.0.0-prototype} package directory
     * @param out the output core directory
     * @param ownerPath native record member that scopes child cores (for example {@code parent_tool_use_id}), or
     * {@code null} for no split
     * @param ownerArtifact the artifact the owner path applies to (required when {@code ownerPath} is given)
     */
    public void repackage(Path prototypeDir, Path out, String ownerPath, String ownerArtifact) {
        Json run = NeutralJson.readDocument(prototypeDir.resolve("run.json"));
        List<Json> events = new ArrayList<>();
        for (NeutralJson.Line l : NeutralJson.readLines(prototypeDir.resolve("events.jsonl"))) {
            events.add(l.json());
        }
        if (ownerPath == null) {
            writeCore(run, events, out, null, null, null, List.of());
            return;
        }
        // owner of each (artifact, record): the whole-record event's data.<ownerPath>
        Map<String, String> ownerByRecord = new LinkedHashMap<>();
        for (Json e : events) {
            Json sr = e.member("source_ref");
            if (sr != null && ownerArtifact.equals(text(sr, "artifact")) && sr.member("is_whole_record") instanceof Json.JBool b && b.value()) {
                Json owner = e.member("data") == null ? null : e.member("data").member(ownerPath);
                ownerByRecord.put(key(sr), owner == null || owner.isNull() ? null : owner.text());
            }
        }
        Map<String, List<Json>> byOwner = new LinkedHashMap<>();
        List<Json> root = new ArrayList<>();
        for (Json e : events) {
            Json sr = e.member("source_ref");
            String owner = sr != null && ownerArtifact.equals(text(sr, "artifact")) ? ownerByRecord.get(key(sr)) : null;
            if (owner == null) {
                root.add(e);
            }
            else {
                byOwner.computeIfAbsent(owner, k -> new ArrayList<>()).add(e);
            }
        }
        List<Json> childLinks = new ArrayList<>();
        String rootUid = text(run.member("run"), "run_uid");
        for (Map.Entry<String, List<Json>> c : byOwner.entrySet()) {
            String childUid = rootUid + "/child/" + c.getKey();
            String path = "children/" + c.getKey().replaceAll("[^A-Za-z0-9_.-]", "_");
            Map<String, Json> link = new LinkedHashMap<>();
            link.put("run_uid", new Json.JString(childUid));
            link.put("path", new Json.JString(path));
            link.put("spawn_tool_call_uid", new Json.JString(c.getKey()));
            link.put("basis", new Json.JString("records of " + ownerArtifact + " whose " + ownerPath + " equals the spawning tool call id (labelled split; not live child support)"));
            childLinks.add(new Json.JObject(link));
            Map<String, Json> parent = new LinkedHashMap<>();
            parent.put("run_uid", new Json.JString(rootUid));
            parent.put("spawn_tool_call_uid", new Json.JString(c.getKey()));
            parent.put("invocation_index", run.member("run").member("invocation_index"));
            writeCore(run, c.getValue(), out.resolve(path), childUid, new Json.JObject(parent),
                    scope(ownerArtifact, ownerPath, new Json.JString(c.getKey())), List.of());
        }
        writeCore(run, root, out, null, null, scope(ownerArtifact, ownerPath, Json.JNull.INSTANCE), childLinks);
    }

    private static Json scope(String artifact, String ownerPath, Json ownerValue) {
        Map<String, Json> s = new LinkedHashMap<>();
        s.put("artifact", new Json.JString(artifact));
        s.put("owner_path", new Json.JString(ownerPath));
        s.put("owner_value", ownerValue);
        s.put("basis", new Json.JString("this core owns the records of the artifact whose owner_path member equals owner_value (null: records without one)"));
        return new Json.JObject(s);
    }

    private static String key(Json sr) {
        return text(sr, "artifact") + "#" + sr.member("record_index").scalarText();
    }

    private void writeCore(Json run, List<Json> events, Path out, String runUidOverride, Json parent, Json sourceScope, List<Json> children) {
        Json ext = compiled.member("extensions").member(text(run.member("extension"), "name"));
        String extVersion = text(ext, "version");
        Json.JObject firstJournal = events.isEmpty() ? null : (Json.JObject) events.get(0).member("journal");
        Json cliAgent = events.isEmpty() ? null : events.get(0).member("cli_agent");
        String runUid = runUidOverride != null ? runUidOverride : text(run.member("run"), "run_uid");

        // events: strip shared facts, add method_id, re-pin extension version, rewrite run_uid
        List<Json> rewritten = new ArrayList<>();
        for (Json e : events) {
            rewritten.add(rewrite(copy(e), extVersion, runUid));
        }
        StringBuilder sb = new StringBuilder();
        for (Json e : rewritten) {
            sb.append(NeutralJson.write(e)).append('\n');
        }
        byte[] eventBytes = sb.toString().getBytes(StandardCharsets.UTF_8);

        Map<String, Json> manifest = new LinkedHashMap<>();
        Map<String, Json> pkg = new LinkedHashMap<>();
        pkg.put("kind", new Json.JString("agent-journal/ocsf-prototype-package"));
        pkg.put("version", new Json.JString(PACKAGE_VERSION));
        pkg.put("non_production", new Json.JBool(true));
        Map<String, Json> layout = new LinkedHashMap<>();
        layout.put("manifest", new Json.JString("run.json"));
        layout.put("events", new Json.JString("events.jsonl"));
        layout.put("children", new Json.JString("children/<name>/ (each a core with its own run.json and events.jsonl)"));
        layout.put("discovery", new Json.JString("a reader given events.jsonl opens the sibling run.json; a reader given a directory opens run.json in it; a detached events.jsonl is not self-describing"));
        pkg.put("layout", new Json.JObject(layout));
        Map<String, Json> compat = new LinkedHashMap<>();
        compat.put("reader_contract", new Json.JString(PackageReader.READER_CONTRACT));
        compat.put("reader_contract_version", new Json.JString(PackageReader.READER_CONTRACT_VERSION));
        compat.put("semantic_operations_version", new Json.JString(SemanticOperation.VERSION));
        compat.put("note", new Json.JString("reader/software compatibility is pinned separately from the OCSF version, the extension version, the mapping contract versions and this package layout version"));
        pkg.put("reader_compat", new Json.JObject(compat));
        pkg.put("events_jsonl_sha256", new Json.JString(NativeArtifact.sha256(eventBytes)));
        pkg.put("events_count", new Json.JNumber(Integer.toString(rewritten.size())));
        manifest.put("package", new Json.JObject(pkg));
        if (run.member("synthetic") != null && !run.member("synthetic").isNull()) {
            manifest.put("synthetic", run.member("synthetic"));
        }

        Json.JObject runObj = (Json.JObject) copy(run.member("run"));
        runObj.members().put("run_uid", new Json.JString(runUid));
        if (parent != null) {
            runObj.members().put("parent", parent);
        }
        runObj.members().put("children", new Json.JArray(children));
        if (sourceScope != null) {
            runObj.members().put("source_scope", sourceScope);
        }
        manifest.put("run", runObj);

        Json.JObject ocsf = (Json.JObject) copy(run.member("ocsf"));
        ocsf.members().put("compiled_schema_sha256", new Json.JString(compiledSha256));
        ocsf.members().put("compiled_schema_location", new Json.JString("referenced by hash; the definitions this core needs are embedded under definitions"));
        manifest.put("ocsf", ocsf);

        Map<String, Json> extension = new LinkedHashMap<>();
        extension.put("name", new Json.JString(text(ext, "name")));
        extension.put("uid", ext.member("uid"));
        extension.put("uid_status", run.member("extension").member("uid_status"));
        extension.put("version", new Json.JString(extVersion));
        Map<String, Json> sourceFiles = new TreeMap<>();
        extension.put("source_sha256", new Json.JString(extensionSource(sourceFiles)));
        extension.put("classes", compiledClassTable(ext));
        extension.put("reader_rule", run.member("extension").member("reader_rule"));
        manifest.put("extension", new Json.JObject(extension));

        Map<String, Integer> classUids = new LinkedHashMap<>();
        ((Json.JObject) compiledClassTable(ext)).members().forEach((n, v) -> classUids.put(n, Integer.parseInt(v.member("class_uid").scalarText())));
        Json closure = DefinitionClosure.build(compiled, classUids, rewritten, DefinitionClosure.Policy.WRITTEN);
        Json full = DefinitionClosure.build(compiled, classUids, rewritten, DefinitionClosure.Policy.FULL_REACHED);
        Map<String, Json> definitions = new LinkedHashMap<>();
        definitions.put("kind", new Json.JString("ocsf-compiled-definitions + extension-source"));
        definitions.put("sha256", new Json.JString(NativeArtifact.sha256(DefinitionClosure.bytes(closure))));
        definitions.put("closure_bytes", new Json.JNumber(Integer.toString(DefinitionClosure.bytes(closure).length)));
        definitions.put("full_reached_closure_bytes_not_embedded", new Json.JNumber(Integer.toString(DefinitionClosure.bytes(full).length)));
        definitions.put("closure", closure);
        definitions.put("extension_source", new Json.JObject(sourceFiles));
        manifest.put("definitions", new Json.JObject(definitions));

        Map<String, Json> shared = new LinkedHashMap<>();
        if (cliAgent != null) {
            shared.put("cli_agent", cliAgent);
        }
        if (firstJournal != null) {
            shared.put("producer", firstJournal.get("producer"));
            shared.put("capture_time", firstJournal.get("capture_time"));
            shared.put("capture_time_text", firstJournal.get("capture_time_text"));
        }
        Map<String, Json> repack = new LinkedHashMap<>();
        repack.put("name", new Json.JString("journal-capture-tck PrototypeRepackager"));
        repack.put("version", new Json.JString(REPACKAGER_VERSION));
        repack.put("from_package_version", run.member("package").member("version"));
        shared.put("repackaged_by", new Json.JObject(repack));
        Map<String, Json> contracts = new LinkedHashMap<>();
        Json mc = run.member("mapping_contracts");
        if (mc instanceof Json.JObject m) {
            for (Map.Entry<String, Json> c : m.members().entrySet()) {
                Map<String, Json> entry = new LinkedHashMap<>();
                Json embedded = c.getValue().member("embedded");
                entry.put("sha256", new Json.JString(NativeArtifact.sha256(NeutralJson.write(embedded).getBytes(StandardCharsets.UTF_8))));
                entry.put("source_file_sha256", c.getValue().member("sha256"));
                entry.put("rules_executed", c.getValue().member("rules_executed"));
                entry.put("embedded", embedded);
                contracts.put(c.getKey(), new Json.JObject(entry));
            }
        }
        shared.put("contracts", new Json.JObject(contracts));
        manifest.put("shared", new Json.JObject(shared));

        List<Json> sources = new ArrayList<>();
        for (Json s : run.member("sources").elements()) {
            Json.JObject copy = (Json.JObject) copy(s);
            copy.members().remove("path_at_capture");
            copy.members().remove("path");
            sources.add(copy);
        }
        manifest.put("sources", new Json.JArray(sources));
        manifest.put("natives", new Json.JString("native artifacts are Level 0 and are not part of this core; the oracle receives them separately"));

        Json semantic = SemanticContract.loadDefault();
        Map<String, Json> sc = new LinkedHashMap<>(((Json.JObject) semantic).members());
        sc.put("sha256", new Json.JString(NativeArtifact.sha256(NeutralJson.write(semantic.member("declarations")).getBytes(StandardCharsets.UTF_8))));
        manifest.put("semantic_contract", new Json.JObject(sc));

        manifest.put("time_policy", run.member("time_policy"));
        Map<String, Json> counts = new LinkedHashMap<>();
        Map<String, Integer> byType = new TreeMap<>();
        for (Json e : rewritten) {
            byType.merge(text(e, "type_name"), 1, Integer::sum);
        }
        Map<String, Json> byTypeJson = new LinkedHashMap<>();
        byType.forEach((k, v) -> byTypeJson.put(k, new Json.JNumber(Integer.toString(v))));
        counts.put("count", new Json.JNumber(Integer.toString(rewritten.size())));
        counts.put("by_type", new Json.JObject(byTypeJson));
        manifest.put("events", new Json.JObject(counts));
        if (run.member("excluded") != null) {
            manifest.put("excluded", run.member("excluded"));
        }
        try {
            Files.createDirectories(out);
            Files.write(out.resolve("events.jsonl"), eventBytes);
            Files.writeString(out.resolve("run.json"), NeutralJson.write(new Json.JObject(manifest)) + "\n", StandardCharsets.UTF_8);
        }
        catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private Json rewrite(Json e, String extVersion, String runUid) {
        Json.JObject o = (Json.JObject) e;
        o.members().remove("cli_agent");
        Json journal = o.get("journal");
        if (journal instanceof Json.JObject j) {
            j.members().remove("producer");
            j.members().remove("capture_time");
            j.members().remove("capture_time_text");
            j.members().remove("contract_sha256");
            j.members().put("run_uid", new Json.JString(runUid));
        }
        stripArtifactHash(o);
        Json metadata = o.get("metadata");
        if (metadata instanceof Json.JObject m && m.get("extensions") != null) {
            for (Json x : m.get("extensions").elements()) {
                ((Json.JObject) x).members().put("version", new Json.JString(extVersion));
            }
        }
        method(o);
        return o;
    }

    private static void stripArtifactHash(Json v) {
        if (v instanceof Json.JObject o) {
            if (o.get("source_ref") instanceof Json.JObject sr) {
                sr.members().remove("artifact_sha256");
            }
            for (Json child : o.members().values()) {
                if (!(child instanceof Json.JObject c) || !c.members().containsKey("record_index")) {
                    stripArtifactHash(child);
                }
            }
        }
        else if (v instanceof Json.JArray a) {
            for (Json el : a.elements()) {
                stripArtifactHash(el);
            }
        }
    }

    private static void method(Json v) {
        if (v instanceof Json.JObject o) {
            Json att = o.get("attribution");
            if (att instanceof Json.JObject a && a.get("method_id") == null) {
                String basis = text(a, "basis") == null ? "" : text(a, "basis");
                int method = 0;
                for (Map.Entry<String, Integer> r : METHOD_BY_RULE.entrySet()) {
                    if (basis.contains(r.getKey())) {
                        method = r.getValue();
                    }
                }
                a.members().put("method_id", new Json.JNumber(Integer.toString(method)));
                a.members().put("method", new Json.JString(method == 1 ? "Reported identifier" : method == 2 ? "Derived by rule" : "Unknown"));
            }
            for (Json child : o.members().values()) {
                method(child);
            }
        }
        else if (v instanceof Json.JArray a) {
            for (Json el : a.elements()) {
                method(el);
            }
        }
    }

    private String extensionSource(Map<String, Json> files) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            List<Path> paths;
            try (Stream<Path> s = Files.walk(extensionDir)) {
                paths = s.filter(Files::isRegularFile).sorted().toList();
            }
            for (Path p : paths) {
                String rel = extensionDir.relativize(p).toString();
                byte[] bytes = Files.readAllBytes(p);
                digest.update(rel.getBytes(StandardCharsets.UTF_8));
                digest.update(bytes);
                files.put(rel, NeutralJson.parse(new String(bytes, StandardCharsets.UTF_8)));
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

    /** The compiled extension's class table {@code {name: {class_uid, category_uid}}}. */
    private Json compiledClassTable(Json ext) {
        Map<String, Json> table = new TreeMap<>();
        String prefix = text(ext, "name") + "/";
        for (Map.Entry<String, Json> c : ((Json.JObject) compiled.member("classes")).members().entrySet()) {
            if (c.getKey().startsWith(prefix)) {
                Map<String, Json> entry = new LinkedHashMap<>();
                entry.put("class_uid", c.getValue().member("uid"));
                entry.put("category_uid", c.getValue().member("category_uid"));
                table.put(text(c.getValue(), "name"), new Json.JObject(entry));
            }
        }
        return new Json.JObject(table);
    }

    static Json copy(Json json) {
        return NeutralJson.parse(NeutralJson.write(json));
    }

    static String text(Json json, String key) {
        Json v = json == null ? null : json.member(key);
        return v == null || v.isNull() ? null : v.text();
    }

    static byte[] read(Path p) {
        try {
            return Files.readAllBytes(p);
        }
        catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
