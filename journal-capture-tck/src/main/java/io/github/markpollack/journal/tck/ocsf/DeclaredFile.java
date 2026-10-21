package io.github.markpollack.journal.tck.ocsf;

import io.github.markpollack.journal.tck.verify.Json;
import io.github.markpollack.journal.tck.verify.NeutralJson;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * The declaration records of a standalone {@code events.jsonl} (layout {@code 0.3.0-prototype}), read from the raw
 * lines alone: the format, schema and run declarations, every contract, source, definition (with amendments
 * merged), session, child link, source end and the finalization record, each with the line it was declared on.
 * From these it derives the same manifest view ({@link RunManifest}) that the two-file layouts carried in
 * {@code run.json}, so the oracle, views and measurements read a copied event file without anything beside it.
 *
 * <p>Every problem is an explicit {@link Diagnostic}: a declaration whose shape does not match the format's own
 * declaration definitions, a duplicate identity, an amendment before its base, a conflicting redefinition, a hash
 * that does not match its body. The reader opens no network connection and consults no cache: a missing mandatory
 * declaration is reported, never fetched.
 */
public final class DeclaredFile {

    /** One declaration line. */
    public record Declaration(int line, String kind, String uid, Integer index, Json payload, Json json) {
    }

    /** Whether a parsed line is a declaration record (the one rule that separates them from execution events). */
    public static boolean isDeclaration(Json json) {
        return json.member("declaration") instanceof Json.JObject;
    }

    public final List<Declaration> declarations = new ArrayList<>();
    public final List<Diagnostic> diagnostics = new ArrayList<>();
    public final Map<String, Declaration> byUid = new LinkedHashMap<>();
    private Json format;
    private Json schema;
    private Json run;
    private Json recording;
    private final List<Json> children = new ArrayList<>();
    private final Map<String, Json> sources = new LinkedHashMap<>();
    private final Map<String, Json> sourceEnds = new LinkedHashMap<>();
    private final Map<String, Json> contracts = new LinkedHashMap<>();
    private final List<String> sessions = new ArrayList<>();
    private final Map<String, Integer> declaredLine = new LinkedHashMap<>();
    private final Map<String, Map<String, Integer>> attributeLine = new LinkedHashMap<>();
    private final Map<String, Json.JObject> classes = new LinkedHashMap<>();
    private final Map<String, Json.JObject> objects = new TreeMap<>();
    private final Map<String, Json> types = new TreeMap<>();
    private int lineBytes;

    private DeclaredFile() {
    }

    /** Whether the first non-blank line declares the format: the test for the standalone layout. */
    public static boolean isDeclared(List<String> rawLines) {
        if (rawLines.isEmpty()) {
            return false;
        }
        try {
            Json first = NeutralJson.parse(rawLines.get(0));
            return isDeclaration(first) && "format".equals(SchemaCatalog.text(first.member("declaration"), "kind"));
        }
        catch (IllegalArgumentException e) {
            return false;
        }
    }

    /** Reads every declaration line of the file; execution events are left to the caller (they are the other lines). */
    public static DeclaredFile read(List<String> rawLines) {
        DeclaredFile f = new DeclaredFile();
        SchemaCatalog shapes = null;
        int number = 0;
        for (String text : rawLines) {
            number++;
            Json json;
            try {
                json = NeutralJson.parse(text);
            }
            catch (IllegalArgumentException e) {
                continue; // reported by the event pass
            }
            if (!isDeclaration(json)) {
                continue;
            }
            f.lineBytes += text.getBytes(StandardCharsets.UTF_8).length + 1;
            Json header = json.member("declaration");
            String kind = SchemaCatalog.text(header, "kind");
            String uid = SchemaCatalog.text(header, "uid");
            Declaration d = new Declaration(number, kind, uid, Event.integer(header.member("index")), kind == null ? null : json.member(kind), json);
            f.declarations.add(d);
            if (kind == null || uid == null) {
                f.diagnostics.add(new Diagnostic(Diagnostic.Severity.DEFECT, "DECLARATION_SHAPE", "line " + number + ": declaration without kind or uid"));
                continue;
            }
            if (f.byUid.putIfAbsent(uid, d) != null) {
                f.diagnostics.add(new Diagnostic(Diagnostic.Severity.DEFECT, "DECLARATION_DUPLICATE", "line " + number + ": " + uid + " was already declared on line " + f.byUid.get(uid).line()));
                continue;
            }
            if ("format".equals(kind) && shapes == null) {
                Json defs = d.payload() == null ? null : d.payload().member("declaration_definitions");
                if (defs != null) {
                    shapes = SchemaCatalog.fromClosure(defs);
                }
            }
            if (shapes != null) {
                SchemaCatalog.Definition def = shapes.object("declaration/" + kind);
                if (def == null) {
                    f.diagnostics.add(new Diagnostic(Diagnostic.Severity.WARNING, "UNKNOWN_DECLARATION_KIND", "line " + number + ": kind " + kind + " is not defined by the format; retained uninterpreted"));
                    continue;
                }
                List<String> problems = new ArrayList<>();
                for (SchemaCatalog.Finding finding : shapes.check(json, def)) {
                    if (finding.kind() != SchemaCatalog.Finding.Kind.UNKNOWN_ATTRIBUTE) {
                        problems.add(finding.toString());
                    }
                }
                if (!problems.isEmpty()) {
                    f.diagnostics.add(new Diagnostic(Diagnostic.Severity.DEFECT, "DECLARATION_SHAPE", "line " + number + " " + uid + ": " + problems));
                    continue;
                }
            }
            else if (!"format".equals(kind)) {
                f.diagnostics.add(new Diagnostic(Diagnostic.Severity.REFUSED, "FORMAT_UNDECLARED", "line " + number + ": a declaration precedes the format declaration or the format carries no declaration_definitions"));
                continue;
            }
            f.absorb(d);
        }
        if (f.format == null) {
            f.diagnostics.add(new Diagnostic(Diagnostic.Severity.REFUSED, "FORMAT_UNDECLARED", "no format declaration"));
        }
        if (f.schema == null) {
            f.diagnostics.add(new Diagnostic(Diagnostic.Severity.REFUSED, "SCHEMA_UNDECLARED", "no schema declaration: the class table and pins are unknown"));
        }
        if (f.run == null) {
            f.diagnostics.add(new Diagnostic(Diagnostic.Severity.REFUSED, "RUN_UNDECLARED", "no run declaration: run identity is unknown"));
        }
        return f;
    }

    private void absorb(Declaration d) {
        Json p = d.payload();
        declaredLine.put(d.uid(), d.line());
        switch (d.kind()) {
            case "format" -> format = p;
            case "schema" -> schema = p;
            case "run" -> run = p;
            case "contract" -> {
                String uid = SchemaCatalog.text(p, "contract_uid");
                Json embedded = p.member("embedded");
                String pinned = SchemaCatalog.text(p, "sha256");
                if (embedded != null && pinned != null && !pinned.equals(sha256(embedded))) {
                    diagnostics.add(new Diagnostic(Diagnostic.Severity.DEFECT, "CONTRACT_HASH_MISMATCH", "line " + d.line() + " " + uid + " embedded sha256 " + sha256(embedded) + " differs from its pin " + pinned));
                }
                contracts.put(uid, p);
                declaredLine.put("contract:" + uid, d.line());
            }
            case "source" -> {
                sources.put(SchemaCatalog.text(p, "artifact"), p);
                declaredLine.put("source:" + SchemaCatalog.text(p, "artifact"), d.line());
            }
            case "source_end" -> sourceEnds.put(SchemaCatalog.text(p, "artifact"), p);
            case "definition" -> definition(d);
            case "session" -> sessions.add(SchemaCatalog.text(p, "session_uid"));
            case "child" -> children.add(p);
            case "recording" -> recording = p;
            default -> {
            }
        }
    }

    private void definition(Declaration d) {
        Json p = d.payload();
        String kindOf = SchemaCatalog.text(p, "kind_of");
        String key = SchemaCatalog.text(p, "key");
        Json body = p.member("body");
        String pinned = SchemaCatalog.text(p, "sha256");
        if (body == null || key == null) {
            diagnostics.add(new Diagnostic(Diagnostic.Severity.DEFECT, "DECLARATION_SHAPE", "line " + d.line() + " " + d.uid() + ": definition without key or body"));
            return;
        }
        if (pinned != null && !pinned.equals(sha256(body))) {
            diagnostics.add(new Diagnostic(Diagnostic.Severity.DEFECT, "DEFINITIONS_HASH_MISMATCH", "line " + d.line() + " " + d.uid() + " body sha256 " + sha256(body) + " differs from its pin " + pinned));
        }
        if ("type".equals(kindOf)) {
            types.put(key, body.member("caption") == null ? new Json.JString(key) : body.member("caption"));
            declaredLine.put("definition:type/" + key, d.line());
            return;
        }
        Map<String, Json.JObject> table = "class".equals(kindOf) ? classes : objects;
        String amends = SchemaCatalog.text(p, "amends");
        Json.JObject attrs = body.member("attributes") instanceof Json.JObject o ? o : new Json.JObject(Map.of());
        if (amends == null) {
            if (table.containsKey(key)) {
                diagnostics.add(new Diagnostic(Diagnostic.Severity.DEFECT, "DEFINITION_CONFLICT", "line " + d.line() + ": " + key + " is defined a second time (line " + declaredLine.get("definition:" + key) + ") without amends"));
                return;
            }
            table.put(key, (Json.JObject) NeutralJson.parse(NeutralJson.write(body)));
            declaredLine.put("definition:" + key, d.line());
            Map<String, Integer> lines = new LinkedHashMap<>();
            attrs.members().keySet().forEach(a -> lines.put(a, d.line()));
            attributeLine.put(key, lines);
            return;
        }
        Json.JObject base = table.get(key);
        if (base == null) {
            diagnostics.add(new Diagnostic(Diagnostic.Severity.DEFECT, "AMENDMENT_BEFORE_DEFINITION", "line " + d.line() + ": " + d.uid() + " amends " + amends + " which is not declared earlier in this file"));
            return;
        }
        Json.JObject baseAttrs = (Json.JObject) base.member("attributes");
        for (Map.Entry<String, Json> a : attrs.members().entrySet()) {
            if (baseAttrs.members().containsKey(a.getKey())) {
                diagnostics.add(new Diagnostic(Diagnostic.Severity.DEFECT, "DEFINITION_CONFLICT", "line " + d.line() + ": " + key + "." + a.getKey() + " is redefined by " + d.uid()));
                continue;
            }
            baseAttrs.members().put(a.getKey(), a.getValue());
            attributeLine.get(key).put(a.getKey(), d.line());
        }
    }

    static String sha256(Json json) {
        return NativeArtifact.sha256(NeutralJson.write(json).getBytes(StandardCharsets.UTF_8));
    }

    // ------------------------------------------------------------------------------------------ what was declared

    public Json format() {
        return format;
    }

    public Json schema() {
        return schema;
    }

    public Json run() {
        return run;
    }

    public Json recording() {
        return recording;
    }

    public List<Json> children() {
        return List.copyOf(children);
    }

    public Map<String, Json> sources() {
        return sources;
    }

    public Map<String, Json> sourceEnds() {
        return sourceEnds;
    }

    public Map<String, Json> contracts() {
        return contracts;
    }

    public List<String> sessions() {
        return List.copyOf(sessions);
    }

    public int bytes() {
        return lineBytes;
    }

    /** The merged definition closure in the form {@link SchemaCatalog#fromClosure} reads. */
    public Json closure() {
        Map<String, Json> out = new LinkedHashMap<>();
        out.put("classes", new Json.JObject(new LinkedHashMap<>(classes)));
        out.put("objects", new Json.JObject(new LinkedHashMap<>(objects)));
        out.put("types", new Json.JObject(new LinkedHashMap<>(types)));
        return new Json.JObject(out);
    }

    /** The line a declaration identity was written on, or {@code null}. */
    public Integer lineOf(String uid) {
        return declaredLine.get(uid);
    }

    /** The line an attribute of a definition became declared on (base or amendment), or {@code null}. */
    public Integer attributeLine(String key, String attribute) {
        Map<String, Integer> lines = attributeLine.get(key);
        return lines == null ? null : lines.get(attribute);
    }

    // ------------------------------------------------------------------------------------------ derived manifest

    /**
     * The manifest view a two-file package carried in {@code run.json}, derived from the declarations alone: state
     * from the recording declaration (an open prefix otherwise), sources from the source and source_end declarations,
     * contracts embedded, the merged closure, the run identity and the child links. No final whole-file hash exists
     * here; the file's integrity is per declaration (hashes) and per line (shape and references).
     */
    public Json deriveManifest(int eventCount) {
        Map<String, Json> m = new LinkedHashMap<>();
        Map<String, Json> pkg = new LinkedHashMap<>();
        pkg.put("kind", str(SchemaCatalog.text(format, "package_kind")));
        pkg.put("version", str(SchemaCatalog.text(format, "package_version")));
        Map<String, Json> compat = new LinkedHashMap<>();
        compat.put("reader_contract", str(SchemaCatalog.text(format, "reader_contract")));
        compat.put("reader_contract_version", str(SchemaCatalog.text(format, "reader_contract_version")));
        if (format.member("semantic_operations_version") != null) {
            compat.put("semantic_operations_version", format.member("semantic_operations_version"));
        }
        pkg.put("reader_compat", new Json.JObject(compat));
        String state = recording == null ? "RECORDING" : SchemaCatalog.text(recording, "state");
        pkg.put("state", str(state));
        pkg.put("events_count", num(eventCount));
        pkg.put("declarations_count", num(declarations.size()));
        pkg.put("events_jsonl_sha256", Json.JNull.INSTANCE);
        pkg.put("derived_from", str("events.jsonl declarations; no ancillary run.json was read"));
        m.put("package", new Json.JObject(pkg));
        if (run.member("synthetic") != null) {
            m.put("synthetic", run.member("synthetic"));
        }
        Map<String, Json> rec = new LinkedHashMap<>();
        rec.put("started", run.member("started"));
        rec.put("state", str(state));
        if (recording != null) {
            rec.put("ended", recording.member("ended"));
            if (recording.member("interruption_reason") != null) {
                rec.put("interruption_reason", recording.member("interruption_reason"));
            }
            rec.put("acquisition", recording.member("acquisition"));
        }
        else {
            rec.put("acquisition", derivedAcquisition());
        }
        m.put("recording", new Json.JObject(rec));
        Map<String, Json> runObj = new LinkedHashMap<>();
        runObj.put("run_uid", run.member("run_uid"));
        runObj.put("invocation_index", run.member("invocation_index"));
        runObj.put("session_uid", sessions.isEmpty() ? Json.JNull.INSTANCE : str(sessions.get(0)));
        runObj.put("capture_start", run.member("capture_start"));
        runObj.put("cli", run.member("cli"));
        if (run.member("launch_settings") != null) {
            runObj.put("launch_settings", run.member("launch_settings"));
        }
        if (run.member("parent") != null) {
            runObj.put("parent", run.member("parent"));
        }
        List<Json> links = new ArrayList<>();
        Json parentScope = null;
        for (Json c : children) {
            Map<String, Json> link = new LinkedHashMap<>();
            for (String k : List.of("run_uid", "path", "spawn_tool_call_uid", "basis")) {
                if (c.member(k) != null) {
                    link.put(k, c.member(k));
                }
            }
            links.add(new Json.JObject(link));
            if (parentScope == null) {
                parentScope = c.member("parent_scope");
            }
        }
        runObj.put("children", new Json.JArray(links));
        if (run.member("source_scope") != null) {
            runObj.put("source_scope", run.member("source_scope"));
        }
        else if (parentScope != null) {
            runObj.put("source_scope", parentScope);
        }
        m.put("run", new Json.JObject(runObj));
        m.put("ocsf", schema.member("ocsf"));
        m.put("extension", schema.member("extension"));
        Map<String, Json> defs = new LinkedHashMap<>();
        defs.put("kind", str("declared in events.jsonl"));
        defs.put("declarations_count", num(declarations.size()));
        defs.put("closure", closure());
        m.put("definitions", new Json.JObject(defs));
        Map<String, Json> shared = new LinkedHashMap<>();
        Json cli = run.member("cli");
        Map<String, Json> agent = new LinkedHashMap<>();
        for (String k : List.of("name", "version", "type_id", "type")) {
            if (cli != null && cli.member(k) != null) {
                agent.put(k, cli.member(k));
            }
        }
        if (!sessions.isEmpty()) {
            agent.put("instance_uid", str(sessions.get(0)));
        }
        if (cli != null && cli.member("product") != null) {
            agent.put("product", cli.member("product"));
        }
        shared.put("cli_agent", new Json.JObject(agent));
        shared.put("producer", run.member("producer"));
        shared.put("capture_time", run.member("capture_time"));
        shared.put("capture_time_text", run.member("capture_time_text"));
        shared.put("written_by", run.member("written_by"));
        Map<String, Json> cs = new LinkedHashMap<>();
        for (Map.Entry<String, Json> c : contracts.entrySet()) {
            Map<String, Json> entry = new LinkedHashMap<>();
            entry.put("sha256", c.getValue().member("sha256"));
            if (c.getValue().member("source_file_sha256") != null) {
                entry.put("source_file_sha256", c.getValue().member("source_file_sha256"));
            }
            entry.put("embedded", c.getValue().member("embedded"));
            cs.put(c.getKey(), new Json.JObject(entry));
        }
        shared.put("contracts", new Json.JObject(cs));
        m.put("shared", new Json.JObject(shared));
        List<Json> src = new ArrayList<>();
        for (Map.Entry<String, Json> s : sources.entrySet()) {
            src.add(sourceEntry(s.getValue(), sourceEnds.get(s.getKey())));
        }
        m.put("sources", new Json.JArray(src));
        if (schema.member("time_policy") != null) {
            m.put("time_policy", schema.member("time_policy"));
        }
        Map<String, Json> ev = new LinkedHashMap<>();
        ev.put("count", num(eventCount));
        ev.put("declarations", num(declarations.size()));
        m.put("events", new Json.JObject(ev));
        return new Json.JObject(m);
    }

    private static Json sourceEntry(Json source, Json end) {
        Map<String, Json> s = new LinkedHashMap<>();
        for (String k : List.of("artifact", "kind", "profile", "surface", "path_at_capture", "contract_uid")) {
            if (source.member(k) != null) {
                s.put(k, source.member(k));
            }
        }
        if (end == null) {
            s.put("state", str("RECORDING"));
            s.put("final", new Json.JBool(false));
            s.put("note", str("no source_end declaration: the source was still open when this prefix was persisted"));
        }
        else {
            String how = SchemaCatalog.text(end, "how");
            s.put("state", str("EOF".equals(how) ? "ENDED" : "INTERRUPTED"));
            s.put("final", new Json.JBool(true));
            for (String k : List.of("records", "bytes", "opaque_records", "trailing_newline", "sha256", "incomplete_tail_record", "first_arrival", "last_arrival", "family_inventory")) {
                if (end.member(k) != null) {
                    s.put(k, end.member(k));
                }
            }
            Map<String, Json> ended = new LinkedHashMap<>();
            ended.put("how", end.member("how"));
            ended.put("at", end.member("at"));
            s.put("ended", new Json.JObject(ended));
        }
        return new Json.JObject(s);
    }

    private Json derivedAcquisition() {
        List<Json> detail = new ArrayList<>();
        boolean pending = false;
        for (Map.Entry<String, Json> s : sources.entrySet()) {
            Json end = sourceEnds.get(s.getKey());
            if (end == null) {
                pending = true;
                detail.add(str(s.getKey() + ": recording"));
            }
            else {
                if ("INTERRUPTED".equals(SchemaCatalog.text(end, "how"))) {
                    detail.add(str(s.getKey() + ": interrupted after " + end.member("records").scalarText() + " record(s)"));
                }
                if (end.member("incomplete_tail_record") != null) {
                    detail.add(str(s.getKey() + ": record " + end.member("incomplete_tail_record").scalarText() + " is incomplete at source end"));
                }
            }
        }
        Map<String, Json> a = new LinkedHashMap<>();
        a.put("status", str(sources.isEmpty() ? "no sources declared" : pending ? "pending" : detail.isEmpty() ? "complete" : "incomplete"));
        a.put("detail", new Json.JArray(detail));
        a.put("meaning", str("derived from the source and source_end declarations of an unfinalized file"));
        return new Json.JObject(a);
    }

    static Json str(String s) {
        return s == null ? Json.JNull.INSTANCE : new Json.JString(s);
    }

    static Json num(int n) {
        return new Json.JNumber(Integer.toString(n));
    }
}
