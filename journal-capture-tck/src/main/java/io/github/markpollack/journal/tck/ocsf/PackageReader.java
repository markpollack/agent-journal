package io.github.markpollack.journal.tck.ocsf;

import io.github.markpollack.journal.tck.verify.Json;
import io.github.markpollack.journal.tck.verify.NeutralJson;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Opens a self-describing run package from {@code events.jsonl} or its directory, by the documented convention: the
 * manifest is the sibling {@code run.json}; linked children live under the paths the manifest names. It verifies the
 * package and reader-compatibility pins, the events, definition and contract hashes, resolves class names through the
 * pin table, checks every saved event against the embedded definition closure, resolves every shared reference
 * (contract ids, artifacts) and validates the semantic contract's schema-path targets and operation names.
 *
 * <p>Every problem is an explicit {@link Diagnostic}. A refusal means "this reader does not implement that
 * interpretation": the raw lines are still returned so a forwarding tool loses nothing. The reader opens no network
 * connection, consults no registry or cache, and never loads production or generated classes.
 */
public final class PackageReader {

    public static final String READER_CONTRACT = "agent-journal/ocsf-reader";
    public static final String READER_CONTRACT_VERSION = "0.1";
    public static final List<String> SUPPORTED_PACKAGE_VERSIONS = List.of("0.1.0-prototype");

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
        if (!Files.isRegularFile(manifestFile)) {
            diags.add(new Diagnostic(Diagnostic.Severity.REFUSED, "NO_MANIFEST",
                    "events.jsonl has no adjacent run.json in " + dir + "; a detached events file is not self-describing"));
            return new OcsfPackage(dir, null, List.of(), rawLines, null, List.of(), diags);
        }
        RunManifest manifest;
        try {
            manifest = new RunManifest(NeutralJson.readDocument(manifestFile));
        }
        catch (IllegalArgumentException e) {
            diags.add(new Diagnostic(Diagnostic.Severity.REFUSED, "MANIFEST_UNREADABLE", e.getMessage()));
            return new OcsfPackage(dir, null, List.of(), rawLines, null, List.of(), diags);
        }
        compatibility(manifest, diags);
        if (manifest.eventsSha256() != null) {
            String actual = NativeArtifact.sha256(readBytes(eventsFile));
            if (!actual.equals(manifest.eventsSha256())) {
                diags.add(new Diagnostic(Diagnostic.Severity.DEFECT, "EVENTS_HASH_MISMATCH",
                        "events.jsonl sha256 " + actual + " differs from the manifest pin " + manifest.eventsSha256() + "; events are read anyway"));
            }
        }
        else {
            diags.add(new Diagnostic(Diagnostic.Severity.WARNING, "EVENTS_HASH_ABSENT", "manifest carries no events_jsonl_sha256"));
        }
        SchemaCatalog catalog = definitions(manifest, diags);
        Map<String, Integer> pin = manifest.classTable();
        Map<Integer, String> nameByUid = new LinkedHashMap<>();
        pin.forEach((n, uid) -> nameByUid.put(uid, n));
        List<Event> events = new ArrayList<>();
        Map<String, Integer> unknownClasses = new TreeMap<>();
        Map<String, Integer> findings = new TreeMap<>();
        Map<String, String> firstFinding = new TreeMap<>();
        int extensionMismatch = 0;
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
        sharedReferences(manifest, events, diags);
        semanticContract(manifest, catalog, diags);
        if (parent != null) {
            Json p = manifest.parent();
            String parentUid = p == null ? null : SchemaCatalog.text(p, "run_uid");
            if (parentUid == null || !parentUid.equals(parent.runUid())) {
                diags.add(new Diagnostic(Diagnostic.Severity.DEFECT, "CHILD_PARENT_MISMATCH",
                        "child " + manifest.runUid() + " names parent " + parentUid + ", linked from " + parent.runUid()));
            }
        }
        List<OcsfPackage> children = new ArrayList<>();
        for (Json child : manifest.children()) {
            String path = SchemaCatalog.text(child, "path");
            Path childDir = path == null ? null : dir.resolve(path);
            if (childDir == null || !Files.isRegularFile(childDir.resolve("run.json"))) {
                diags.add(new Diagnostic(Diagnostic.Severity.DEFECT, "CHILD_MISSING",
                        "child " + SchemaCatalog.text(child, "run_uid") + " at " + path + " is not present beside the core"));
                continue;
            }
            OcsfPackage c = open(childDir, manifest);
            if (!SchemaCatalog.text(child, "run_uid").equals(c.manifest == null ? null : c.manifest.runUid())) {
                diags.add(new Diagnostic(Diagnostic.Severity.DEFECT, "CHILD_UID_MISMATCH", "link names " + SchemaCatalog.text(child, "run_uid")
                        + " but the child manifest says " + (c.manifest == null ? null : c.manifest.runUid())));
            }
            children.add(c);
        }
        return new OcsfPackage(dir, manifest, events, rawLines, catalog, children, diags);
    }

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

    private static String major(String version) {
        int dot = version.indexOf('.');
        return dot < 0 ? version : version.substring(0, dot);
    }

    private static SchemaCatalog definitions(RunManifest m, List<Diagnostic> diags) {
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
        for (Map.Entry<String, Integer> e : m.classTable().entrySet()) {
            SchemaCatalog.Definition def = catalog.classNamed(e.getKey());
            if (def == null) {
                diags.add(new Diagnostic(Diagnostic.Severity.REFUSED, "PIN_MISMATCH",
                        "pinned class " + e.getKey() + " (uid " + e.getValue() + ") has no definition in the embedded closure"));
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
        return catalog;
    }

    private static void sharedReferences(RunManifest m, List<Event> events, List<Diagnostic> diags) {
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
                    "journal.contract_uid not found in shared.contracts: " + unresolvedContracts));
        }
        if (!unresolvedArtifacts.isEmpty()) {
            diags.add(new Diagnostic(Diagnostic.Severity.DEFECT, "SOURCE_REF_UNRESOLVED",
                    "source_ref.artifact not found in sources: " + unresolvedArtifacts));
        }
        for (Map.Entry<String, Json> c : m.contracts().entrySet()) {
            Json embedded = c.getValue().member("embedded");
            String pinned = SchemaCatalog.text(c.getValue(), "sha256");
            if (embedded == null) {
                diags.add(new Diagnostic(Diagnostic.Severity.DEFECT, "CONTRACT_NOT_EMBEDDED", c.getKey() + " is referenced by hash only"));
                continue;
            }
            String actual = NativeArtifact.sha256(NeutralJson.write(embedded).getBytes(StandardCharsets.UTF_8));
            if (pinned != null && !pinned.equals(actual)) {
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

    private static void semanticContract(RunManifest m, SchemaCatalog catalog, List<Diagnostic> diags) {
        Json sc = m.semanticContract();
        if (sc == null) {
            diags.add(new Diagnostic(Diagnostic.Severity.WARNING, "SEMANTIC_CONTRACT_ABSENT", "run.json carries no semantic_contract; analysis views are unavailable"));
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
            for (String problem : SemanticContract.validate(sc, catalog)) {
                diags.add(new Diagnostic(Diagnostic.Severity.DEFECT, "SEMANTIC_TARGET_UNRESOLVED", problem));
            }
        }
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
