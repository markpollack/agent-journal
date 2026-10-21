package io.github.markpollack.journal.tck.ocsf;

import io.github.markpollack.journal.tck.verify.Json;
import io.github.markpollack.journal.tck.verify.NeutralJson;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Opens a run package from {@code events.jsonl} or its directory.
 *
 * <p>A standalone event file (layout {@code 0.3.0-prototype}, first line a {@code format} declaration) is read from
 * its own declarations ({@link DeclaredFile}): format and reader compatibility, the schema pin and class table, the
 * run context, every contract, source and definition, the session, child links, source ends and finalization. The
 * ancillary {@code run.json} beside it, when present, is cross-checked on identity and adds the final whole-file
 * hash, the persisted-prefix hash, counts, failure detail and the semantic contract; when absent, the same manifest
 * view is derived from the declarations and the reader says so. Every event is checked against the declared
 * definitions, every reference (class, attribute, contract, source) must have been declared on an earlier line, and
 * linked children are opened from their own files.
 *
 * <p>The historical two-file layouts ({@code 0.1.0-prototype}, {@code 0.2.0-prototype}) are read as before: the
 * manifest is the sibling {@code run.json}, which carries the definition closure and contracts; a detached file of
 * those layouts is refused because nothing in it describes itself.
 *
 * <p>Every problem is an explicit {@link Diagnostic}. A refusal means "this reader does not implement that
 * interpretation": the raw lines are still returned so a forwarding tool loses nothing. The reader opens no network
 * connection, consults no registry or cache, and never loads production or generated classes.
 */
public final class PackageReader {

    public static final String READER_CONTRACT = "agent-journal/ocsf-reader";
    public static final String READER_CONTRACT_VERSION = "0.3";
    public static final List<String> SUPPORTED_PACKAGE_VERSIONS = List.of("0.1.0-prototype", "0.2.0-prototype", "0.3.0-prototype");

    private PackageReader() {
    }

    /** @param eventsOrDir {@code events.jsonl} or the directory holding it */
    public static OcsfPackage open(Path eventsOrDir) {
        Path dir = Files.isDirectory(eventsOrDir) ? eventsOrDir : eventsOrDir.toAbsolutePath().getParent();
        return open(dir, null);
    }

    private static OcsfPackage open(Path dir, RunManifest parent) {
        List<Diagnostic> diags = new ArrayList<>();
        Path eventsFile = dir.resolve("events.jsonl");
        Path manifestFile = dir.resolve("run.json");
        List<String> rawLines = readRaw(eventsFile);
        if (DeclaredFile.isDeclared(rawLines)) {
            return openDeclared(dir, parent, rawLines, eventsFile, manifestFile, diags);
        }
        if (!Files.isRegularFile(manifestFile)) {
            diags.add(new Diagnostic(Diagnostic.Severity.REFUSED, "NO_MANIFEST",
                    "events.jsonl in " + dir + " declares no format on its first line and has no adjacent run.json; a two-file layout's detached events file is not self-describing"));
            return new OcsfPackage(dir, null, List.of(), rawLines, null, List.of(), diags, null);
        }
        RunManifest manifest;
        try {
            manifest = new RunManifest(NeutralJson.readDocument(manifestFile));
        }
        catch (IllegalArgumentException e) {
            diags.add(new Diagnostic(Diagnostic.Severity.REFUSED, "MANIFEST_UNREADABLE", e.getMessage()));
            return new OcsfPackage(dir, null, List.of(), rawLines, null, List.of(), diags, null);
        }
        compatibility(manifest, diags);
        stateWarnings(manifest.packageState(), diags);
        wholeFileHash(manifest, eventsFile, diags);
        SchemaCatalog catalog = legacyDefinitions(manifest, diags);
        List<Event> events = events(rawLines, manifest, catalog, diags, null);
        if (!"RECORDING".equals(manifest.packageState()) && !"FAILED".equals(manifest.packageState())) {
            countCheck(manifest, events.size(), false, diags);
        }
        else if ("RECORDING".equals(manifest.packageState())) {
            countCheck(manifest, events.size(), true, diags);
        }
        sharedReferences(manifest, events, diags, true);
        semanticContract(manifest, catalog, diags, List.of());
        parentLink(manifest, parent, diags);
        return new OcsfPackage(dir, manifest, events, rawLines, catalog, children(dir, manifest, diags, true), diags, null);
    }

    // ------------------------------------------------------------------------------------------ standalone layout

    private static OcsfPackage openDeclared(Path dir, RunManifest parent, List<String> rawLines, Path eventsFile, Path manifestFile, List<Diagnostic> diags) {
        DeclaredFile declared = DeclaredFile.read(rawLines);
        diags.addAll(declared.diagnostics);
        for (Diagnostic d : declared.diagnostics) {
            if (d.severity() == Diagnostic.Severity.REFUSED) {
                return new OcsfPackage(dir, null, List.of(), rawLines, null, List.of(), diags, declared);
            }
        }
        List<Json> eventJson = new ArrayList<>();
        int eventCount = 0;
        for (String text : rawLines) {
            try {
                Json json = NeutralJson.parse(text);
                if (!DeclaredFile.isDeclaration(json)) {
                    eventCount++;
                }
            }
            catch (IllegalArgumentException e) {
                eventCount++; // reported by the event pass
            }
        }
        RunManifest derived = new RunManifest(declared.deriveManifest(eventCount));
        compatibility(derived, diags);
        RunManifest manifest = derived;
        Json ancillary = null;
        if (Files.isRegularFile(manifestFile)) {
            try {
                ancillary = NeutralJson.readDocument(manifestFile);
            }
            catch (IllegalArgumentException e) {
                diags.add(new Diagnostic(Diagnostic.Severity.DEFECT, "MANIFEST_UNREADABLE", "ancillary run.json: " + e.getMessage() + "; the file is read from its declarations alone"));
            }
        }
        if (ancillary != null) {
            RunManifest a = new RunManifest(ancillary);
            List<String> disagreements = identityDisagreements(a, derived);
            if (!disagreements.isEmpty()) {
                diags.add(new Diagnostic(Diagnostic.Severity.DEFECT, "MANIFEST_DISAGREES",
                        "ancillary run.json disagrees with the file's declarations on " + disagreements + "; the declarations are read, run.json is ignored"));
            }
            else {
                manifest = new RunManifest(overlay(ancillary, declared));
                finalizationConsistency(manifest, declared, diags);
                wholeFileHash(manifest, eventsFile, diags);
                persistedPrefix(manifest, eventsFile, diags);
                if ("RECORDING".equals(manifest.packageState())) {
                    countCheck(manifest, eventCount, true, diags);
                }
                else if (!"FAILED".equals(manifest.packageState())) {
                    countCheck(manifest, eventCount, false, diags);
                }
            }
        }
        else {
            diags.add(new Diagnostic(Diagnostic.Severity.WARNING, "ANCILLARY_ABSENT",
                    "no run.json beside events.jsonl: read from its declarations alone; there is no whole-file hash, and the state is " + manifest.packageState()
                            + (declared.recording() == null ? " (no recording declaration: an open prefix, or the writer failed)" : " (from the recording declaration)")));
        }
        stateWarnings(manifest.packageState(), diags);
        Json launchCli = declared.run() == null ? null : declared.run().member("cli");
        String launchVersion = SchemaCatalog.text(launchCli, "version");
        String reportedVersion = declared.cliVersion() == null ? null : SchemaCatalog.text(declared.cliVersion(), "version");
        if (launchVersion != null && reportedVersion != null && !launchVersion.equals(reportedVersion)) {
            diags.add(new Diagnostic(Diagnostic.Severity.WARNING, "CLI_VERSION_DIFFERS",
                    "the capture declared CLI version " + launchVersion + " at launch but the selected stream reported " + reportedVersion + "; both are kept, the launch value is the run's"));
        }
        SchemaCatalog catalog = SchemaCatalog.fromClosure(declared.closure());
        if (eventCount > 0 && catalog.classes().isEmpty()) {
            diags.add(new Diagnostic(Diagnostic.Severity.REFUSED, "MISSING_DEFINITIONS",
                    "events.jsonl carries " + eventCount + " event line(s) but declares no class definition; the saved model cannot be enumerated or validated offline"));
            catalog = null;
        }
        else {
            pinAndReferenceChecks(manifest, catalog, diags, false);
        }
        List<Event> events = events(rawLines, manifest, catalog, diags, declared);
        sharedReferences(manifest, events, diags, false);
        List<String> absent = new ArrayList<>();
        for (String name : manifest.classTable().keySet()) {
            if (catalog == null || catalog.classNamed(name) == null) {
                absent.add(name);
            }
        }
        semanticContract(manifest, catalog, diags, absent);
        parentLink(manifest, parent, diags);
        return new OcsfPackage(dir, manifest, events, rawLines, catalog, children(dir, manifest, diags, ancillary != null), diags, declared);
    }

    /** Identity the ancillary manifest must agree on with the declarations before anything in it is used. */
    private static List<String> identityDisagreements(RunManifest ancillary, RunManifest derived) {
        List<String> out = new ArrayList<>();
        if (!String.valueOf(ancillary.runUid()).equals(String.valueOf(derived.runUid()))) {
            out.add("run_uid " + ancillary.runUid() + " vs declared " + derived.runUid());
        }
        if (!String.valueOf(ancillary.packageVersion()).equals(String.valueOf(derived.packageVersion()))) {
            out.add("package version " + ancillary.packageVersion() + " vs declared " + derived.packageVersion());
        }
        if (!String.valueOf(ancillary.extensionUid()).equals(String.valueOf(derived.extensionUid())) || !String.valueOf(ancillary.extensionVersion()).equals(String.valueOf(derived.extensionVersion()))) {
            out.add("extension " + ancillary.extensionUid() + " " + ancillary.extensionVersion() + " vs declared " + derived.extensionUid() + " " + derived.extensionVersion());
        }
        if (!ancillary.classTable().equals(derived.classTable())) {
            out.add("class table " + ancillary.classTable() + " vs declared " + derived.classTable());
        }
        return out;
    }

    /** The ancillary manifest with the declared definitions and contract documents overlaid: those never come from run.json. */
    private static Json overlay(Json ancillary, DeclaredFile declared) {
        Json.JObject m = (Json.JObject) NeutralJson.parse(NeutralJson.write(ancillary));
        Map<String, Json> defs = new LinkedHashMap<>();
        if (m.get("definitions") instanceof Json.JObject d) {
            defs.putAll(d.members());
        }
        defs.put("closure", declared.closure());
        m.members().put("definitions", new Json.JObject(defs));
        Json.JObject shared = m.get("shared") instanceof Json.JObject s ? s : new Json.JObject(Map.of());
        Map<String, Json> audit = new LinkedHashMap<>();
        if (shared.get("contracts") instanceof Json.JObject c) {
            audit.putAll(c.members());
        }
        // run.json lists every contract the run loaded (the mapping audit); this file resolves only the contracts it declared
        Map<String, Json> contracts = new LinkedHashMap<>();
        for (Map.Entry<String, Json> c : declared.contracts().entrySet()) {
            Map<String, Json> entry = new LinkedHashMap<>();
            if (audit.get(c.getKey()) instanceof Json.JObject existing) {
                entry.putAll(existing.members());
            }
            entry.putIfAbsent("sha256", c.getValue().member("sha256"));
            entry.put("embedded", c.getValue().member("embedded"));
            contracts.put(c.getKey(), new Json.JObject(entry));
        }
        shared.members().put("contracts", new Json.JObject(contracts));
        m.members().put("shared", shared);
        return m;
    }

    private static void finalizationConsistency(RunManifest manifest, DeclaredFile declared, List<Diagnostic> diags) {
        String state = manifest.packageState();
        Json rec = declared.recording();
        if (("COMPLETED".equals(state) || "INTERRUPTED".equals(state)) && rec == null) {
            diags.add(new Diagnostic(Diagnostic.Severity.DEFECT, "FINALIZATION_MISSING", "run.json says " + state + " but events.jsonl carries no recording declaration"));
        }
        else if (rec != null && !state.equals(SchemaCatalog.text(rec, "state"))) {
            diags.add(new Diagnostic(Diagnostic.Severity.DEFECT, "FINALIZATION_MISMATCH", "run.json says " + state + " but the recording declaration says " + SchemaCatalog.text(rec, "state")));
        }
    }

    /** An open package's persisted prefix, when the ancillary manifest states its extent and hash. */
    private static void persistedPrefix(RunManifest m, Path eventsFile, List<Diagnostic> diags) {
        Json prefix = m.json().path("package", "persisted_prefix");
        Integer bytes = prefix == null ? null : Event.integer(prefix.member("bytes"));
        String sha = prefix == null ? null : SchemaCatalog.text(prefix, "sha256");
        if (bytes == null || sha == null) {
            return;
        }
        byte[] file = readBytes(eventsFile);
        if (file.length < bytes) {
            diags.add(new Diagnostic(Diagnostic.Severity.DEFECT, "PREFIX_SHORT", "run.json says " + bytes + " bytes were persisted but events.jsonl holds " + file.length));
            return;
        }
        String actual = NativeArtifact.sha256(Arrays.copyOf(file, bytes));
        if (!actual.equals(sha)) {
            diags.add(new Diagnostic(Diagnostic.Severity.DEFECT, "PREFIX_HASH_MISMATCH", "the first " + bytes + " bytes of events.jsonl hash to " + actual + ", not the persisted-prefix pin " + sha));
        }
    }

    // ------------------------------------------------------------------------------------------ shared steps

    private static void compatibility(RunManifest m, List<Diagnostic> diags) {
        if (!RunManifest.PACKAGE_KIND.equals(m.packageKind())) {
            diags.add(new Diagnostic(Diagnostic.Severity.REFUSED, "UNSUPPORTED_PACKAGE_KIND",
                    "package kind " + m.packageKind() + "; this reader implements " + RunManifest.PACKAGE_KIND));
        }
        if (!SUPPORTED_PACKAGE_VERSIONS.contains(m.packageVersion())) {
            diags.add(new Diagnostic(Diagnostic.Severity.REFUSED, "UNSUPPORTED_PACKAGE_VERSION",
                    "package version " + m.packageVersion() + "; this reader implements " + SUPPORTED_PACKAGE_VERSIONS));
        }
        String contract = m.readerContract();
        String version = m.readerContractVersion();
        if (!READER_CONTRACT.equals(contract)) {
            diags.add(new Diagnostic(Diagnostic.Severity.REFUSED, "UNSUPPORTED_READER_CONTRACT",
                    "package requires reader contract " + contract + "; this reader implements " + READER_CONTRACT));
        }
        else if (version == null || !major(version).equals(major(READER_CONTRACT_VERSION))) {
            diags.add(new Diagnostic(Diagnostic.Severity.REFUSED, "UNSUPPORTED_READER_CONTRACT_VERSION",
                    "package requires reader contract version " + version + "; this reader implements " + READER_CONTRACT_VERSION
                            + " (different major)"));
        }
        else if (!version.equals(READER_CONTRACT_VERSION)) {
            diags.add(new Diagnostic(Diagnostic.Severity.WARNING, "READER_CONTRACT_MINOR_DIFFERS",
                    "package requires reader contract version " + version + "; this reader implements " + READER_CONTRACT_VERSION));
        }
        String ops = m.semanticOperationsVersion();
        if (ops != null && !ops.equals(SemanticOperation.VERSION)) {
            diags.add(new Diagnostic(Diagnostic.Severity.WARNING, "SEMANTIC_OPERATIONS_VERSION_DIFFERS",
                    "package pins semantic operations " + ops + "; this reader implements " + SemanticOperation.VERSION));
        }
    }

    private static void stateWarnings(String state, List<Diagnostic> diags) {
        if ("RECORDING".equals(state)) {
            diags.add(new Diagnostic(Diagnostic.Severity.WARNING, "PACKAGE_OPEN",
                    "the writer has not finalized this package: events_count/events_bytes are those persisted at the last flush, there is no final hash, and nothing here is complete"));
        }
        else if ("FAILED".equals(state)) {
            diags.add(new Diagnostic(Diagnostic.Severity.WARNING, "PACKAGE_FAILED",
                    "the writer failed while recording: recording.failure names the operation and the confirmed persisted boundary; there is no final hash"));
        }
        else if ("INTERRUPTED".equals(state)) {
            diags.add(new Diagnostic(Diagnostic.Severity.WARNING, "PACKAGE_INTERRUPTED",
                    "the recording was interrupted before every source ended; the hash and counts are final for what was acquired"));
        }
    }

    private static void wholeFileHash(RunManifest manifest, Path eventsFile, List<Diagnostic> diags) {
        if (manifest.eventsSha256() != null) {
            String actual = NativeArtifact.sha256(readBytes(eventsFile));
            if (!actual.equals(manifest.eventsSha256())) {
                diags.add(new Diagnostic(Diagnostic.Severity.DEFECT, "EVENTS_HASH_MISMATCH",
                        "events.jsonl sha256 " + actual + " differs from the manifest pin " + manifest.eventsSha256() + "; events are read anyway"));
            }
        }
        else if (manifest.finalized()) {
            diags.add(new Diagnostic(Diagnostic.Severity.WARNING, "EVENTS_HASH_ABSENT", "manifest carries no events_jsonl_sha256"));
        }
    }

    private static void countCheck(RunManifest manifest, int events, boolean open, List<Diagnostic> diags) {
        Integer declaredCount = manifest.integer("package", "events_count");
        if (declaredCount == null) {
            return;
        }
        if (!open && declaredCount != events) {
            diags.add(new Diagnostic(Diagnostic.Severity.DEFECT, "EVENTS_COUNT_MISMATCH",
                    "manifest declares " + declaredCount + " event(s) but events.jsonl holds " + events + " event line(s)"));
        }
        if (open && events < declaredCount) {
            diags.add(new Diagnostic(Diagnostic.Severity.DEFECT, "EVENTS_COUNT_MISMATCH",
                    "manifest declares " + declaredCount + " persisted event(s) but events.jsonl holds only " + events + " event line(s)"));
        }
    }

    private static String major(String version) {
        int dot = version.indexOf('.');
        return dot < 0 ? version : version.substring(0, dot);
    }

    /** Two-file layouts: the closure lives in run.json, pinned by its hash. */
    private static SchemaCatalog legacyDefinitions(RunManifest m, List<Diagnostic> diags) {
        Json closure = m.closure();
        if (closure == null) {
            diags.add(new Diagnostic(Diagnostic.Severity.REFUSED, "MISSING_DEFINITIONS",
                    "run.json carries no definitions.closure; the saved model cannot be enumerated or validated offline"));
            return null;
        }
        String pinned = m.text("definitions", "sha256");
        String actual = NativeArtifact.sha256(NeutralJson.write(closure).getBytes(StandardCharsets.UTF_8));
        if (pinned == null) {
            diags.add(new Diagnostic(Diagnostic.Severity.WARNING, "DEFINITIONS_HASH_ABSENT", "definitions carry no sha256"));
        }
        else if (!pinned.equals(actual)) {
            diags.add(new Diagnostic(Diagnostic.Severity.DEFECT, "DEFINITIONS_HASH_MISMATCH",
                    "embedded closure sha256 " + actual + " differs from its pin " + pinned));
        }
        SchemaCatalog catalog = SchemaCatalog.fromClosure(closure);
        pinAndReferenceChecks(m, catalog, diags, true);
        return catalog;
    }

    /**
     * The pin table must resolve in the catalog without remapping, and every object a kept attribute refers to must be
     * present. A two-file layout embeds every pinned class; a standalone file declares a class before its first use,
     * so a pinned class no event used is absent by design and only a uid that disagrees is a refusal (an event of an
     * undeclared class is reported by the event pass).
     */
    private static void pinAndReferenceChecks(RunManifest m, SchemaCatalog catalog, List<Diagnostic> diags, boolean everyPinnedClassEmbedded) {
        for (Map.Entry<String, Integer> e : m.classTable().entrySet()) {
            SchemaCatalog.Definition def = catalog.classNamed(e.getKey());
            if (def == null) {
                if (everyPinnedClassEmbedded) {
                    diags.add(new Diagnostic(Diagnostic.Severity.REFUSED, "PIN_MISMATCH",
                            "pinned class " + e.getKey() + " (uid " + e.getValue() + ") has no definition in the embedded closure"));
                }
            }
            else if (def.uid() == null || !def.uid().equals(e.getValue())) {
                diags.add(new Diagnostic(Diagnostic.Severity.REFUSED, "PIN_MISMATCH",
                        "pinned class " + e.getKey() + " uid " + e.getValue() + " != definition uid " + def.uid() + "; numeric ids are never remapped"));
            }
        }
        for (SchemaCatalog.Definition c : catalog.classes().values()) {
            for (SchemaCatalog.AttributeDef a : c.attributes().values()) {
                if (a.isObject() && catalog.object(a.objectType()) == null) {
                    diags.add(new Diagnostic(Diagnostic.Severity.DEFECT, "MISSING_DEFINITION_REFERENCE",
                            c.key() + "." + a.name() + " refers to object " + a.objectType() + " which the closure does not carry"));
                }
            }
        }
        for (SchemaCatalog.Definition o : catalog.objects().values()) {
            for (SchemaCatalog.AttributeDef a : o.attributes().values()) {
                if (a.isObject() && catalog.object(a.objectType()) == null) {
                    diags.add(new Diagnostic(Diagnostic.Severity.DEFECT, "MISSING_DEFINITION_REFERENCE",
                            o.key() + "." + a.name() + " refers to object " + a.objectType() + " which the closure does not carry"));
                }
            }
        }
    }

    /** Parses the event lines, checks each against its class definition, and (standalone layout) each reference's declaration line. */
    private static List<Event> events(List<String> rawLines, RunManifest manifest, SchemaCatalog catalog, List<Diagnostic> diags, DeclaredFile declared) {
        Map<String, Integer> pin = manifest.classTable();
        Map<Integer, String> nameByUid = new LinkedHashMap<>();
        pin.forEach((n, uid) -> nameByUid.put(uid, n));
        List<Event> events = new ArrayList<>();
        Map<String, Integer> unknownClasses = new TreeMap<>();
        Map<String, Integer> findings = new TreeMap<>();
        Map<String, String> firstFinding = new TreeMap<>();
        int extensionMismatch = 0;
        int early = 0;
        String firstEarly = null;
        int number = 0;
        for (String text : rawLines) {
            number++;
            Json json;
            try {
                json = NeutralJson.parse(text);
            }
            catch (IllegalArgumentException e) {
                diags.add(new Diagnostic(Diagnostic.Severity.DEFECT, "EVENT_UNPARSEABLE", "line " + number + ": " + e.getMessage()));
                continue;
            }
            if (declared != null && DeclaredFile.isDeclaration(json)) {
                continue;
            }
            Integer classUid = Event.integer(json.member("class_uid"));
            String className = classUid == null ? null : nameByUid.get(classUid);
            if (className == null) {
                unknownClasses.merge(String.valueOf(classUid), 1, Integer::sum);
            }
            Event event = new Event(number, json, className);
            events.add(event);
            if (className != null && catalog != null) {
                SchemaCatalog.Definition def = catalog.classNamed(className);
                if (def == null) {
                    findings.merge("MISSING_CLASS_DEFINITION", 1, Integer::sum);
                    firstFinding.putIfAbsent("MISSING_CLASS_DEFINITION", "line " + number + " class " + className);
                }
                else {
                    for (SchemaCatalog.Finding f : catalog.check(json, def)) {
                        findings.merge(f.kind().name(), 1, Integer::sum);
                        firstFinding.putIfAbsent(f.kind().name(), "line " + number + " " + f);
                    }
                    if (declared != null) {
                        List<String> problems = new ArrayList<>();
                        declaredBefore(declared, catalog, json, def, number, problems);
                        if (!problems.isEmpty()) {
                            early += problems.size();
                            if (firstEarly == null) {
                                firstEarly = "line " + number + ": " + problems.get(0);
                            }
                        }
                    }
                }
            }
            for (Json ext : event.elements("metadata", "extensions")) {
                if (manifest.extensionName() != null && manifest.extensionName().equals(SchemaCatalog.text(ext, "name"))) {
                    String uid = SchemaCatalog.text(ext, "uid");
                    String version = SchemaCatalog.text(ext, "version");
                    if (!String.valueOf(manifest.extensionUid()).equals(uid) || !manifest.extensionVersion().equals(version)) {
                        extensionMismatch++;
                    }
                }
            }
        }
        if (!unknownClasses.isEmpty()) {
            diags.add(new Diagnostic(Diagnostic.Severity.WARNING, "UNKNOWN_CLASS",
                    "events with a class_uid outside the pinned table are retained uninterpreted: " + unknownClasses));
        }
        if (extensionMismatch > 0) {
            diags.add(new Diagnostic(Diagnostic.Severity.DEFECT, "EVENT_EXTENSION_PIN_MISMATCH",
                    extensionMismatch + " event(s) declare an extension uid/version different from the manifest pin"));
        }
        for (Map.Entry<String, Integer> f : findings.entrySet()) {
            Diagnostic.Severity sev = f.getKey().equals("UNKNOWN_ATTRIBUTE") ? Diagnostic.Severity.WARNING : Diagnostic.Severity.DEFECT;
            diags.add(new Diagnostic(sev, f.getKey(), f.getValue() + " occurrence(s); first: " + firstFinding.get(f.getKey())));
        }
        if (early > 0) {
            diags.add(new Diagnostic(Diagnostic.Severity.DEFECT, "REFERENCE_BEFORE_DECLARATION",
                    early + " reference(s) to a definition, attribute, contract or source declared later in the file or not at all; first: " + firstEarly));
        }
        return events;
    }

    /** Every definition, attribute, contract and source an event uses must be declared on an earlier line. */
    private static void declaredBefore(DeclaredFile declared, SchemaCatalog catalog, Json event, SchemaCatalog.Definition def, int line, List<String> problems) {
        Integer at = declared.lineOf("definition:" + def.key());
        if (at == null || at >= line) {
            problems.add("class " + def.key() + " declared at " + at);
        }
        String contract = SchemaCatalog.text(event.member("journal"), "contract_uid");
        if (contract != null) {
            Integer c = declared.lineOf("contract:" + contract);
            if (c == null || c >= line) {
                problems.add("contract " + contract + " declared at " + c);
            }
        }
        String artifact = SchemaCatalog.text(event.member("source_ref"), "artifact");
        if (artifact != null) {
            Integer s = declared.lineOf("source:" + artifact);
            if (s == null || s >= line) {
                problems.add("source " + artifact + " declared at " + s);
            }
        }
        attributesDeclaredBefore(declared, catalog, event, def, line, "$", problems);
    }

    private static void attributesDeclaredBefore(DeclaredFile declared, SchemaCatalog catalog, Json value, SchemaCatalog.Definition def, int line, String path, List<String> problems) {
        if (!(value instanceof Json.JObject o)) {
            return;
        }
        for (Map.Entry<String, Json> m : o.members().entrySet()) {
            SchemaCatalog.AttributeDef attr = def.attributes().get(m.getKey());
            if (attr == null) {
                continue; // unknown attribute: reported by the shape check, retained
            }
            Integer at = declared.attributeLine(def.key(), m.getKey());
            if (at == null || at >= line) {
                problems.add(path + "." + m.getKey() + " of " + def.key() + " declared at " + at);
                continue;
            }
            if (attr.isObject()) {
                SchemaCatalog.Definition od = catalog.object(attr.objectType());
                if (od == null) {
                    continue; // missing definition: reported by the shape check
                }
                Integer oat = declared.lineOf("definition:" + od.key());
                if (oat == null || oat >= line) {
                    problems.add(path + "." + m.getKey() + " object " + od.key() + " declared at " + oat);
                    continue;
                }
                if (attr.isArray()) {
                    int i = 0;
                    for (Json el : m.getValue().elements()) {
                        attributesDeclaredBefore(declared, catalog, el, od, line, path + "." + m.getKey() + "[" + i++ + "]", problems);
                    }
                }
                else {
                    attributesDeclaredBefore(declared, catalog, m.getValue(), od, line, path + "." + m.getKey(), problems);
                }
            }
        }
    }

    /** @param checkContractHashes false for a standalone file, whose declarations were already checked against their own hashes */
    private static void sharedReferences(RunManifest m, List<Event> events, List<Diagnostic> diags, boolean checkContractHashes) {
        Map<String, Integer> unresolvedContracts = new TreeMap<>();
        Map<String, Integer> unresolvedArtifacts = new TreeMap<>();
        for (Event e : events) {
            if (e.className == null) {
                continue;
            }
            String c = e.contractUid();
            if (c == null || m.contract(c) == null) {
                unresolvedContracts.merge(String.valueOf(c), 1, Integer::sum);
            }
            String a = e.artifact();
            if (a == null || m.source(a) == null) {
                unresolvedArtifacts.merge(String.valueOf(a), 1, Integer::sum);
            }
            Json runUid = e.get("journal", "run_uid");
            if (runUid == null || !m.runUid().equals(runUid.text())) {
                diags.add(new Diagnostic(Diagnostic.Severity.DEFECT, "RUN_UID_MISMATCH", "line " + e.line + " journal.run_uid "
                        + (runUid == null ? null : runUid.text()) + " != manifest " + m.runUid()));
            }
        }
        if (!unresolvedContracts.isEmpty()) {
            diags.add(new Diagnostic(Diagnostic.Severity.DEFECT, "CONTRACT_REF_UNRESOLVED",
                    "journal.contract_uid not found in the declared contracts: " + unresolvedContracts));
        }
        if (!unresolvedArtifacts.isEmpty()) {
            diags.add(new Diagnostic(Diagnostic.Severity.DEFECT, "SOURCE_REF_UNRESOLVED",
                    "source_ref.artifact not found in the declared sources: " + unresolvedArtifacts));
        }
        for (Map.Entry<String, Json> c : m.contracts().entrySet()) {
            Json embedded = c.getValue().member("embedded");
            String pinned = SchemaCatalog.text(c.getValue(), "sha256");
            if (embedded == null) {
                diags.add(new Diagnostic(Diagnostic.Severity.DEFECT, "CONTRACT_NOT_EMBEDDED", c.getKey() + " is referenced by hash only"));
                continue;
            }
            String actual = NativeArtifact.sha256(NeutralJson.write(embedded).getBytes(StandardCharsets.UTF_8));
            if (checkContractHashes && pinned != null && !pinned.equals(actual)) {
                diags.add(new Diagnostic(Diagnostic.Severity.DEFECT, "CONTRACT_HASH_MISMATCH",
                        c.getKey() + " embedded sha256 " + actual + " differs from its pin " + pinned));
            }
            String appliesVersion = SchemaCatalog.text(embedded.member("applies_to"), "version");
            if (appliesVersion != null && m.cliVersion() != null && !appliesVersion.equals(m.cliVersion())) {
                diags.add(new Diagnostic(Diagnostic.Severity.WARNING, "CONTRACT_APPLICABILITY",
                        c.getKey() + " applies to " + m.cliName() + " " + appliesVersion + " but the run reports " + m.cliVersion()
                                + "; evidence is version-scoped, not a support claim"));
            }
        }
    }

    private static void semanticContract(RunManifest m, SchemaCatalog catalog, List<Diagnostic> diags, List<String> absentClasses) {
        Json sc = m.semanticContract();
        if (sc == null) {
            diags.add(new Diagnostic(Diagnostic.Severity.WARNING, "SEMANTIC_CONTRACT_ABSENT", "no semantic_contract (it lives in the ancillary run.json); analysis views are unavailable"));
            return;
        }
        String pinned = SchemaCatalog.text(sc, "sha256");
        Json decls = sc.member("declarations");
        if (decls != null && pinned != null) {
            String actual = NativeArtifact.sha256(NeutralJson.write(decls).getBytes(StandardCharsets.UTF_8));
            if (!pinned.equals(actual)) {
                diags.add(new Diagnostic(Diagnostic.Severity.DEFECT, "SEMANTIC_CONTRACT_HASH_MISMATCH",
                        "declarations sha256 " + actual + " differs from its pin " + pinned));
            }
        }
        if (catalog != null) {
            List<String> undeclared = new ArrayList<>();
            for (String problem : SemanticContract.validate(sc, catalog)) {
                if (SemanticContract.undeclaredTarget(problem, absentClasses)) {
                    undeclared.add(problem);
                }
                else {
                    diags.add(new Diagnostic(Diagnostic.Severity.DEFECT, "SEMANTIC_TARGET_UNRESOLVED", problem));
                }
            }
            if (!undeclared.isEmpty()) {
                diags.add(new Diagnostic(Diagnostic.Severity.WARNING, "SEMANTIC_TARGET_UNDECLARED", undeclared.size()
                        + " semantic target(s) fall under pinned classes this file declares no definition for (no event of that class was written): " + absentClasses
                        + "; those views are empty here, not wrong"));
            }
        }
    }

    private static void parentLink(RunManifest manifest, RunManifest parent, List<Diagnostic> diags) {
        if (parent == null) {
            return;
        }
        Json p = manifest.parent();
        String parentUid = p == null ? null : SchemaCatalog.text(p, "run_uid");
        if (parentUid == null || !parentUid.equals(parent.runUid())) {
            diags.add(new Diagnostic(Diagnostic.Severity.DEFECT, "CHILD_PARENT_MISMATCH",
                    "child " + manifest.runUid() + " names parent " + parentUid + ", linked from " + parent.runUid()));
        }
    }

    /**
     * @param layoutClaimed whether a manifest beside the file claims the package layout (then a linked child that is
     *        absent is a defect of the package); a standalone file alone only identifies its children as external
     */
    private static List<OcsfPackage> children(Path dir, RunManifest manifest, List<Diagnostic> diags, boolean layoutClaimed) {
        List<OcsfPackage> children = new ArrayList<>();
        for (Json child : manifest.children()) {
            String path = SchemaCatalog.text(child, "path");
            Path childDir = path == null ? null : dir.resolve(path);
            if (childDir == null || !Files.isRegularFile(childDir.resolve("events.jsonl"))) {
                diags.add(layoutClaimed
                        ? new Diagnostic(Diagnostic.Severity.DEFECT, "CHILD_MISSING",
                                "child " + SchemaCatalog.text(child, "run_uid") + " at " + path + " is not present beside the core")
                        : new Diagnostic(Diagnostic.Severity.WARNING, "CHILD_EXTERNAL",
                                "child " + SchemaCatalog.text(child, "run_uid") + " at " + path + " is identified by this file but not present beside it; its content is external and was not read"));
                continue;
            }
            OcsfPackage c = open(childDir, manifest);
            if (!SchemaCatalog.text(child, "run_uid").equals(c.manifest == null ? null : c.manifest.runUid())) {
                diags.add(new Diagnostic(Diagnostic.Severity.DEFECT, "CHILD_UID_MISMATCH", "link names " + SchemaCatalog.text(child, "run_uid")
                        + " but the child says " + (c.manifest == null ? null : c.manifest.runUid())));
            }
            children.add(c);
        }
        return children;
    }

    static List<String> readRaw(Path file) {
        if (!Files.isRegularFile(file)) {
            return List.of();
        }
        try {
            List<String> out = new ArrayList<>();
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                if (!line.isBlank()) {
                    out.add(line);
                }
            }
            return out;
        }
        catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static byte[] readBytes(Path file) {
        try {
            return Files.readAllBytes(file);
        }
        catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * A self-contained view of one event for export: the shared per-run facts joined back onto it
     * ({@code cli_agent}, {@code journal.producer}, capture time, contract and artifact hashes). The saved event is not
     * modified; this is what a consumer that insists on self-describing event lines receives.
     */
    public static Json selfContained(OcsfPackage pkg, Event event) {
        Json.JObject copy = (Json.JObject) NeutralJson.parse(NeutralJson.write(event.json));
        Json shared = pkg.manifest.shared();
        if (shared == null) {
            return copy;
        }
        if (shared.member("cli_agent") != null && copy.get("cli_agent") == null) {
            copy.members().put("cli_agent", shared.member("cli_agent"));
        }
        Json journal = copy.get("journal");
        if (journal instanceof Json.JObject j) {
            if (shared.member("producer") != null) {
                j.members().putIfAbsent("producer", shared.member("producer"));
            }
            if (shared.member("capture_time") != null) {
                j.members().putIfAbsent("capture_time", shared.member("capture_time"));
            }
            if (shared.member("capture_time_text") != null) {
                j.members().putIfAbsent("capture_time_text", shared.member("capture_time_text"));
            }
            Json contract = pkg.manifest.contract(event.contractUid());
            if (contract != null && contract.member("sha256") != null) {
                j.members().putIfAbsent("contract_sha256", contract.member("sha256"));
            }
        }
        Json sr = copy.get("source_ref");
        Json source = pkg.manifest.source(event.artifact());
        if (sr instanceof Json.JObject s && source != null && source.member("sha256") != null) {
            s.members().putIfAbsent("artifact_sha256", source.member("sha256"));
        }
        return copy;
    }
}
