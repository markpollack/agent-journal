package io.github.markpollack.journal.tck.ocsf;

import io.github.markpollack.journal.tck.verify.Check.Category;
import io.github.markpollack.journal.tck.verify.Check.Outcome;
import io.github.markpollack.journal.tck.verify.Difference;
import io.github.markpollack.journal.tck.verify.Json;
import io.github.markpollack.journal.tck.verify.NeutralJson;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * The independent Java oracle for a self-describing OCSF prototype package. It reads the native artifacts as bytes
 * ({@link NativeArtifact}), lets the declared {@link SourceProfile} state what each artifact reports, and judges the
 * saved core against that: expected populations per family (a missing normalized row is a defect, an empty
 * population is never a pass), ownership by the id at the record's own declared path (never by equal content or a
 * record-wide search), exact normalized values and kinds (usage amounts, cost amounts, results, decisions,
 * operations), whole-record retention with structure/value, representation and bytes reported separately, framing,
 * time basis, and scoped reconstruction.
 *
 * <p>The checking path is this package plus {@code Json}/{@code NeutralJson}; it never calls the Python prototype,
 * a production parser or a generated model. Each core (the root and every linked child) is judged on its own.
 */
public final class OcsfOracle {

    private final OracleReport report = new OracleReport();
    private final Path nativeDir;

    private OcsfOracle(Path nativeDir) {
        this.nativeDir = nativeDir;
    }

    /**
     * @param pkg the opened package (root core and linked children)
     * @param nativeDir directory holding the native artifacts under the names the package's source inventory uses
     */
    public static OracleReport verify(OcsfPackage pkg, Path nativeDir) {
        OcsfOracle o = new OcsfOracle(nativeDir);
        o.describe(pkg);
        for (OcsfPackage core : pkg.all()) {
            o.core(core);
        }
        return o.report;
    }

    private void describe(OcsfPackage pkg) {
        report.candidate("rootDir", pkg.dir.toString());
        report.candidate("runUid", pkg.manifest == null ? null : pkg.manifest.runUid());
        report.candidate("packageVersion", pkg.manifest == null ? null : pkg.manifest.packageVersion());
        report.candidate("extension", pkg.manifest == null ? null : pkg.manifest.extensionName() + " uid " + pkg.manifest.extensionUid() + " " + pkg.manifest.extensionVersion());
        List<String> diags = new ArrayList<>();
        for (OcsfPackage core : pkg.all()) {
            for (Diagnostic d : core.diagnostics) {
                diags.add(core.label() + " " + d);
            }
        }
        report.candidate("readerDiagnostics", diags);
        List<String> cores = new ArrayList<>();
        for (OcsfPackage core : pkg.all()) {
            cores.add(core.label() + " " + core.manifest.runUid() + " (" + core.events.size() + " events)");
        }
        report.candidate("cores", cores);
        report.oracle("nativeDir", nativeDir.toString());
    }

    private void core(OcsfPackage core) {
        String label = core.label();
        if (core.refused()) {
            report.add(Category.COMPLETENESS, "package.readable", label, Outcome.UNSUPPORTED,
                    "reader refused the package: " + core.diagnostics(Diagnostic.Severity.REFUSED));
            return;
        }
        report.add(Category.COMPLETENESS, "package.readable", label, core.diagnostics(Diagnostic.Severity.DEFECT).isEmpty() ? Outcome.VERIFIED : Outcome.DEFECTIVE,
                core.diagnostics(Diagnostic.Severity.DEFECT).isEmpty() ? "pins, hashes, definitions and shared references resolve"
                        : core.diagnostics(Diagnostic.Severity.DEFECT).toString());
        order(core, label);
        for (Json source : core.manifest.sources()) {
            String artifact = SchemaCatalog.text(source, "artifact");
            String owner = label + "/" + artifact;
            Path file = nativeDir.resolve(artifact);
            if (!Files.isRegularFile(file)) {
                report.add(Category.COMPLETENESS, "source.available", owner, Outcome.UNRESOLVED, "native artifact not supplied at " + file);
                continue;
            }
            NativeArtifact art = NativeArtifact.read(file);
            Map<String, Object> o = new LinkedHashMap<>();
            o.put("path", file.toString());
            o.put("sha256", art.sha256());
            o.put("records", art.records().size());
            o.put("opaque", art.opaqueCount());
            report.oracle(owner, o);
            SourceProfile profile = SourceProfile.forName(SchemaCatalog.text(source, "profile"));
            if (profile == null) {
                report.add(Category.COMPLETENESS, "source.profile", owner, Outcome.UNSUPPORTED,
                        "no oracle profile for " + SchemaCatalog.text(source, "profile") + "; only retention is judged");
                profile = new OpaqueTextProfile();
            }
            Json scope = core.manifest.sourceScope();
            if (scope != null && !artifact.equals(SchemaCatalog.text(scope, "artifact"))) {
                scope = null;
            }
            ExpectedFacts facts = profile.expected(art, source, scope);
            report.denominator(owner, facts.counts());
            sourceInventory(source, art, owner);
            List<Event> events = new ArrayList<>();
            for (Event e : core.events) {
                if (e.className != null && artifact.equals(e.artifact())) {
                    events.add(e);
                }
            }
            retention(art, facts, events, owner, core);
            requests(facts, events, owner, core);
            results(facts, events, owner);
            responses(facts, events, owner);
            prompts(facts, events, owner);
            usage(facts, events, owner);
            cost(facts, events, owner);
            decisions(facts, events, owner);
            operations(facts, events, owner);
            turns(facts, events, owner);
            time(art, facts, events, owner);
            reconstruction(art, facts, events, owner);
        }
    }

    // ------------------------------------------------------------------------------------------ inventory and order

    private void sourceInventory(Json source, NativeArtifact art, String owner) {
        List<String> problems = new ArrayList<>();
        String sha = SchemaCatalog.text(source, "sha256");
        if (!art.sha256().equals(sha)) {
            problems.add("sha256 " + sha + " declared, artifact is " + art.sha256());
        }
        Integer records = Event.integer(source.member("records"));
        if (records == null || records != art.records().size()) {
            problems.add("records " + records + " declared, artifact has " + art.records().size());
        }
        Integer opaque = Event.integer(source.member("opaque_records"));
        if (opaque == null || opaque != art.opaqueCount()) {
            problems.add("opaque_records " + opaque + " declared, artifact has " + art.opaqueCount());
        }
        Json trailing = source.member("trailing_newline");
        if (!(trailing instanceof Json.JBool b) || b.value() != art.trailingNewline()) {
            problems.add("trailing_newline " + (trailing == null ? "absent" : trailing.scalarText()) + ", artifact: " + art.trailingNewline());
        }
        Json bytes = source.member("bytes");
        if (!(bytes instanceof Json.JNumber n) || n.value().intValueExact() != art.bytes().length) {
            problems.add("bytes " + (bytes == null ? "absent" : bytes.scalarText()) + ", artifact has " + art.bytes().length);
        }
        report.add(Category.COMPLETENESS, "source.inventory", owner, problems.isEmpty() ? Outcome.VERIFIED : Outcome.DEFECTIVE,
                problems.isEmpty() ? "sha256, bytes, record count, opaque count and trailing newline match the artifact" : String.join("; ", problems));
    }

    private void order(OcsfPackage core, String label) {
        List<String> problems = new ArrayList<>();
        Integer last = null;
        Map<String, Integer> lastRecord = new LinkedHashMap<>();
        for (Event e : core.events) {
            if (e.className == null) {
                continue;
            }
            if (e.sequence == null || (last != null && e.sequence <= last)) {
                problems.add("line " + e.line + " sequence " + e.sequence + " after " + last);
            }
            last = e.sequence;
            Integer idx = e.recordIndex();
            Integer prev = lastRecord.get(e.artifact());
            if (idx != null && prev != null && idx < prev) {
                problems.add("line " + e.line + " " + e.artifact() + " record " + idx + " after record " + prev);
            }
            if (idx != null) {
                lastRecord.put(e.artifact(), idx);
            }
        }
        report.add(Category.NORMALIZATION, "event.order", label, problems.isEmpty() ? Outcome.VERIFIED : Outcome.DEFECTIVE,
                problems.isEmpty() ? "journal.sequence strictly increasing; record_index non-decreasing per artifact" : String.join("; ", problems));
    }

    // ------------------------------------------------------------------------------------------ retention

    private void retention(NativeArtifact art, ExpectedFacts facts, List<Event> events, String owner, OcsfPackage core) {
        Map<Integer, List<Event>> whole = new TreeMap<>();
        for (Event e : events) {
            if (e.wholeRecord() && e.recordIndex() != null) {
                whole.computeIfAbsent(e.recordIndex(), k -> new ArrayList<>()).add(e);
            }
        }
        List<String> coverage = new ArrayList<>();
        List<String> classProblems = new ArrayList<>();
        List<String> structure = new ArrayList<>();
        List<String> representation = new ArrayList<>();
        List<String> bytesProblems = new ArrayList<>();
        List<String> framing = new ArrayList<>();
        int opaqueVerified = 0;
        int parsedCompared = 0;
        int excludedDeclared = 0;
        List<String> undeclared = new ArrayList<>();
        for (NativeArtifact.Record r : art.records()) {
            List<Event> evs = whole.getOrDefault(r.index(), List.of());
            if (facts.excluded.containsKey(r.index())) {
                if (!evs.isEmpty()) {
                    coverage.add("record " + r.index() + " excluded by admission but retained " + evs.size() + " time(s)");
                }
                else if (declared(core, facts.artifact, facts.excluded.get(r.index()))) {
                    excludedDeclared++;
                }
                else {
                    undeclared.add(r.index() + ":" + facts.excluded.get(r.index()));
                }
                continue;
            }
            if (!facts.owned.contains(r.index())) {
                if (!evs.isEmpty()) {
                    coverage.add("record " + r.index() + " is not owned by this core but retained here");
                }
                continue;
            }
            if (evs.size() != 1) {
                coverage.add("record " + r.index() + ": " + evs.size() + " whole-record events");
                if (!r.parsed()) {
                    bytesProblems.add("record " + r.index() + " (" + r.status() + ") not retained");
                }
                continue;
            }
            Event e = evs.get(0);
            boolean fallback = "source_record".equals(e.className);
            if (facts.normalized.contains(r.index()) && fallback) {
                classProblems.add("record " + r.index() + " has a normalization rule but is retained only as a source_record fallback");
            }
            if (!facts.normalized.contains(r.index()) && r.parsed() && !fallback) {
                classProblems.add("record " + r.index() + " has no normalization rule but was normalized as " + e.className);
            }
            if (r.parsed()) {
                parsedCompared++;
                Json data = e.get("data");
                if (data == null) {
                    structure.add("record " + r.index() + ": whole-record event has no data");
                }
                else {
                    for (Difference d : Json.differences(r.json(), data)) {
                        if (d.kind() == Difference.Kind.REPRESENTATION) {
                            representation.add("record " + r.index() + " " + d);
                        }
                        else {
                            structure.add("record " + r.index() + " " + d);
                        }
                    }
                }
                if (!fallback || e.activityId == null || e.activityId != 1) {
                    if (fallback) {
                        classProblems.add("record " + r.index() + " parsed but retained as Opaque");
                    }
                }
            }
            else {
                if (!fallback || e.activityId == null || e.activityId != 2) {
                    classProblems.add("record " + r.index() + " (" + r.status() + ") must be a source_record Opaque event, found " + e.className + "/" + e.activityId);
                }
                boolean ok;
                if (r.status() == NativeArtifact.Status.UNDECODABLE) {
                    String b64 = e.text("raw_bytes_b64");
                    ok = b64 != null && java.util.Arrays.equals(Base64.getDecoder().decode(b64), r.raw());
                }
                else {
                    ok = Objects.equals(e.text("raw_data"), r.text());
                }
                if (ok) {
                    opaqueVerified++;
                }
                else {
                    bytesProblems.add("record " + r.index() + " (" + r.status() + ") raw bytes differ");
                }
            }
            Integer recordBytes = e.integer("source_ref", "record_bytes");
            String ending = e.text("source_ref", "line_ending");
            if (recordBytes == null || recordBytes != r.raw().length || !r.lineEnding().equals(ending)) {
                framing.add("record " + r.index() + ": record_bytes " + recordBytes + "/" + r.raw().length + ", line_ending " + ending + "/" + r.lineEnding());
            }
        }
        for (Integer idx : whole.keySet()) {
            if (art.record(idx) == null) {
                coverage.add("record " + idx + " retained but the artifact has " + art.records().size() + " records");
            }
        }
        String denominator = facts.owned.size() + " owned record(s)";
        report.add(Category.COMPLETENESS, "whole_record.coverage", owner, coverage.isEmpty() && undeclared.isEmpty() ? Outcome.VERIFIED : Outcome.DEFECTIVE,
                coverage.isEmpty() && undeclared.isEmpty() ? denominator + " each retained exactly once" + (excludedDeclared > 0 ? "; " + excludedDeclared + " excluded by declared admission (not preserved, not missing)" : "")
                        : String.join("; ", coverage) + (undeclared.isEmpty() ? "" : "; excluded without a declared disposition: " + undeclared));
        if (excludedDeclared > 0) {
            report.add(Category.COMPLETENESS, "whole_record.excluded", owner, Outcome.NOT_APPLICABLE, excludedDeclared + " record(s) excluded by the declared admission");
        }
        report.add(Category.COMPLETENESS, "whole_record.class", owner, classProblems.isEmpty() ? Outcome.VERIFIED : Outcome.DEFECTIVE,
                classProblems.isEmpty() ? facts.normalized.size() + " normalized, " + (facts.owned.size() - facts.normalized.size()) + " fallback" : String.join("; ", classProblems));
        report.add(Category.PRESERVATION, "whole_record.structure_value", owner, parsedCompared == 0 ? Outcome.NOT_APPLICABLE : structure.isEmpty() ? Outcome.VERIFIED : Outcome.DEFECTIVE,
                structure.isEmpty() ? parsedCompared + " parsed record(s) equal in structure and value" : String.join("; ", structure));
        report.add(Category.PRESERVATION, "whole_record.representation", owner, parsedCompared == 0 ? Outcome.NOT_APPLICABLE : representation.isEmpty() ? Outcome.VERIFIED : Outcome.REPRESENTATION_DIFFERS,
                representation.isEmpty() ? "number spellings equal" : String.join("; ", representation));
        int opaqueOwned = 0;
        for (NativeArtifact.Record r : art.records()) {
            if (!r.parsed() && facts.owned.contains(r.index())) {
                opaqueOwned++;
            }
        }
        report.add(Category.PRESERVATION, "whole_record.bytes", owner, opaqueOwned == 0 ? Outcome.NOT_APPLICABLE : bytesProblems.isEmpty() ? Outcome.VERIFIED : Outcome.DEFECTIVE,
                opaqueOwned == 0 ? "no opaque record; byte equality is not promised for parsed records (structure/value and representation are judged instead)"
                        : bytesProblems.isEmpty() ? opaqueVerified + " opaque record(s) byte-equal" : String.join("; ", bytesProblems));
        report.add(Category.PRESERVATION, "whole_record.framing", owner, framing.isEmpty() ? Outcome.VERIFIED : Outcome.DEFECTIVE,
                framing.isEmpty() ? "record_bytes and line_ending match the artifact for every retained record" : String.join("; ", framing));
    }

    private static boolean declared(OcsfPackage core, String artifact, String family) {
        Json source = core.manifest.source(artifact);
        Json inv = source == null ? null : source.member("family_inventory");
        Json fam = inv == null ? null : inv.member(family);
        String disposition = fam == null ? null : SchemaCatalog.text(fam, "disposition");
        return disposition != null && (disposition.contains("excluded") || disposition.contains("partially admitted"));
    }

    // ------------------------------------------------------------------------------------------ populations

    /** Compares two multisets of occurrence keys ({@code <record>:<id>}); returns problems. */
    private static List<String> population(List<String> expected, List<String> actual) {
        Map<String, Integer> e = new TreeMap<>();
        Map<String, Integer> a = new TreeMap<>();
        expected.forEach(k -> e.merge(k, 1, Integer::sum));
        actual.forEach(k -> a.merge(k, 1, Integer::sum));
        List<String> missing = new ArrayList<>();
        List<String> extra = new ArrayList<>();
        List<String> duplicated = new ArrayList<>();
        for (String k : new TreeSet<>(e.keySet())) {
            int diff = e.get(k) - a.getOrDefault(k, 0);
            if (diff > 0) {
                missing.add(k + (diff > 1 ? " x" + diff : ""));
            }
            else if (diff < 0) {
                duplicated.add(k + " x" + a.get(k));
            }
        }
        for (String k : new TreeSet<>(a.keySet())) {
            if (!e.containsKey(k)) {
                extra.add(k);
            }
        }
        List<String> problems = new ArrayList<>();
        if (!missing.isEmpty()) {
            problems.add("missing " + missing);
        }
        if (!extra.isEmpty()) {
            problems.add("extra " + extra);
        }
        if (!duplicated.isEmpty()) {
            problems.add("duplicated " + duplicated);
        }
        return problems;
    }

    private void populationCheck(String property, String owner, List<String> expected, List<String> actual) {
        List<String> problems = population(expected, actual);
        Outcome outcome = problems.isEmpty() ? (expected.isEmpty() ? Outcome.NOT_APPLICABLE : Outcome.VERIFIED) : Outcome.DEFECTIVE;
        report.add(Category.NORMALIZATION, property, owner, outcome,
                problems.isEmpty() ? expected.size() + " expected, " + actual.size() + " observed" : String.join("; ", problems) + " (expected " + expected.size() + ", observed " + actual.size() + ")");
    }

    private void valueCheck(String property, String owner, int examined, List<String> problems, List<String> representation) {
        if (examined == 0) {
            report.add(Category.NORMALIZATION, property, owner, Outcome.NOT_APPLICABLE, "no occurrence");
            return;
        }
        report.add(Category.NORMALIZATION, property, owner, problems.isEmpty() ? Outcome.VERIFIED : Outcome.DEFECTIVE,
                problems.isEmpty() ? examined + " occurrence(s) exact" : String.join("; ", problems));
        if (representation != null) {
            report.add(Category.NORMALIZATION, property.replace(".values", ".representation"), owner,
                    representation.isEmpty() ? Outcome.VERIFIED : Outcome.REPRESENTATION_DIFFERS,
                    representation.isEmpty() ? "number spellings equal" : String.join("; ", representation));
        }
    }

    /** Value differences; representation differences go to {@code representation} when it is non-null. */
    private static void compare(String what, Json expected, Json actual, List<String> problems, List<String> representation) {
        for (Difference d : Json.differences(expected, actual)) {
            if (d.kind() == Difference.Kind.REPRESENTATION) {
                if (representation != null) {
                    representation.add(what + " " + d);
                }
            }
            else {
                problems.add(what + " " + d);
            }
        }
    }

    private static Event find(List<Event> events, String className, int activity, int recordIndex, String idAttribute, String id) {
        for (Event e : events) {
            if (e.is(className, activity) && Objects.equals(e.recordIndex(), recordIndex) && Objects.equals(e.text(idAttribute), id)) {
                return e;
            }
        }
        return null;
    }

    private void requests(ExpectedFacts facts, List<Event> events, String owner, OcsfPackage core) {
        List<String> expected = new ArrayList<>();
        for (ExpectedFacts.Request r : facts.requests) {
            expected.add(r.recordIndex() + ":" + r.id());
        }
        List<String> actual = new ArrayList<>();
        for (Event e : events) {
            if (e.is("tool_call", 1)) {
                actual.add(e.recordIndex() + ":" + e.text("tool_call_uid"));
            }
        }
        populationCheck("population.requests", owner, expected, actual);
        List<String> problems = new ArrayList<>();
        List<String> representation = new ArrayList<>();
        int examined = 0;
        for (ExpectedFacts.Request r : facts.requests) {
            Event e = find(events, "tool_call", 1, r.recordIndex(), "tool_call_uid", r.id());
            if (e == null) {
                continue;
            }
            examined++;
            String what = r.recordIndex() + ":" + r.id();
            if (!Objects.equals(r.name(), e.text("tool_name"))) {
                problems.add(what + " tool_name " + e.text("tool_name") + " != " + r.name());
            }
            compare(what + " arguments", r.arguments(), e.get("arguments"), problems, representation);
            if (r.responseUid() != null && !r.responseUid().equals(e.text("response_uid"))) {
                problems.add(what + " response_uid " + e.text("response_uid") + " != " + r.responseUid());
            }
            if (r.reportedState() != null && !r.reportedState().equals(e.text("reported_state"))) {
                problems.add(what + " reported_state " + e.text("reported_state") + " != " + r.reportedState());
            }
            Integer kind = e.integer("tool_kind_id");
            Integer expectedKind = toolKind(core, e.contractUid(), r.name());
            if (expectedKind != null && !expectedKind.equals(kind)) {
                problems.add(what + " tool_kind_id " + kind + " != " + expectedKind + " from the embedded name table");
            }
        }
        valueCheck("request.values", owner, examined, problems, representation);
    }

    /** The kind the package's own embedded contract table assigns to a tool name (shared declarative data). */
    private static Integer toolKind(OcsfPackage core, String contractUid, String name) {
        Json contract = core.manifest.contract(contractUid);
        Json table = contract == null ? null : contract.path("embedded", "tool_kind_table");
        if (table == null) {
            return null;
        }
        Json v = name == null ? null : table.path("map", name);
        if (v == null) {
            v = table.member("default");
        }
        return Event.integer(v);
    }

    private void results(ExpectedFacts facts, List<Event> events, String owner) {
        List<String> expected = new ArrayList<>();
        for (ExpectedFacts.Result r : facts.results) {
            expected.add(r.recordIndex() + ":" + r.id());
        }
        List<String> actual = new ArrayList<>();
        for (Event e : events) {
            if (e.is("tool_call", 2)) {
                actual.add(e.recordIndex() + ":" + e.text("tool_call_uid"));
            }
        }
        populationCheck("population.results", owner, expected, actual);
        // ownership: every result event's owner must be an id its own record names at the declared path
        List<String> ownership = new ArrayList<>();
        int resultEvents = 0;
        for (Event e : events) {
            if (!e.is("tool_call", 2)) {
                continue;
            }
            resultEvents++;
            List<String> idsOfRecord = new ArrayList<>();
            for (ExpectedFacts.Result r : facts.results) {
                if (r.recordIndex() == e.recordIndex()) {
                    idsOfRecord.add(r.id());
                }
            }
            if (!idsOfRecord.contains(e.text("tool_call_uid"))) {
                ownership.add("record " + e.recordIndex() + " names " + idsOfRecord + " at its declared path, event claims " + e.text("tool_call_uid"));
            }
        }
        report.add(Category.NORMALIZATION, "result.owner", owner, resultEvents == 0 ? Outcome.NOT_APPLICABLE : ownership.isEmpty() ? Outcome.VERIFIED : Outcome.DEFECTIVE,
                ownership.isEmpty() ? resultEvents + " result(s) owned by the id of their own record" : String.join("; ", ownership));
        List<String> problems = new ArrayList<>();
        List<String> representation = new ArrayList<>();
        int examined = 0;
        for (ExpectedFacts.Result r : facts.results) {
            Event e = find(events, "tool_call", 2, r.recordIndex(), "tool_call_uid", r.id());
            if (e == null) {
                continue;
            }
            examined++;
            String what = r.recordIndex() + ":" + r.id();
            Json result = e.get("tool_result");
            if (r.content() == null) {
                if (result != null && result.member("data") != null) {
                    problems.add(what + " source reports no result content but tool_result.data is present");
                }
            }
            else if (result == null) {
                problems.add(what + " tool_result absent");
            }
            else {
                compare(what + " content", r.content(), result.member("data"), problems, representation);
                Json err = result.member("error_reported");
                if (r.isError() == null) {
                    if (err != null) {
                        problems.add(what + " error_reported present but is_error not reported");
                    }
                }
                else if (!(err instanceof Json.JBool b) || !(r.isError() instanceof Json.JBool eb) || b.value() != eb.value()) {
                    problems.add(what + " error_reported " + (err == null ? "absent" : err.scalarText()) + " != " + r.isError().scalarText());
                }
                if (r.structured() != null) {
                    compare(what + " structured_result", r.structured(), result.member("structured_result"), problems, representation);
                }
                else if (result.member("structured_result") != null) {
                    problems.add(what + " structured_result present but not reported");
                }
                if (r.reportedState() != null && !r.reportedState().equals(e.text("reported_state"))) {
                    problems.add(what + " reported_state " + e.text("reported_state") + " != " + r.reportedState());
                }
            }
            int expectedStatus = r.isError() instanceof Json.JBool b ? (b.value() ? 2 : 1) : 0;
            int expectedBasis = r.isError() instanceof Json.JBool ? 1 : 3;
            if (!Objects.equals(e.integer("status_id"), expectedStatus) || !Objects.equals(e.integer("status_basis_id"), expectedBasis)) {
                problems.add(what + " status_id/status_basis_id " + e.integer("status_id") + "/" + e.integer("status_basis_id") + " != " + expectedStatus + "/" + expectedBasis);
            }
        }
        valueCheck("result.values", owner, examined, problems, representation);
    }

    private void responses(ExpectedFacts facts, List<Event> events, String owner) {
        List<String> expected = new ArrayList<>();
        for (ExpectedFacts.Response r : facts.responses) {
            expected.add(r.recordIndex() + ":" + r.id());
        }
        List<String> actual = new ArrayList<>();
        for (Event e : events) {
            if ("model_response".equals(e.className)) {
                actual.add(e.recordIndex() + ":" + e.text("response_uid"));
            }
        }
        populationCheck("population.responses", owner, expected, actual);
        List<String> problems = new ArrayList<>();
        List<String> representation = new ArrayList<>();
        int examined = 0;
        for (ExpectedFacts.Response r : facts.responses) {
            Event e = null;
            for (Event c : events) {
                if ("model_response".equals(c.className) && Objects.equals(c.recordIndex(), r.recordIndex()) && Objects.equals(c.text("response_uid"), r.id())) {
                    e = c;
                }
            }
            if (e == null) {
                continue;
            }
            examined++;
            String what = r.recordIndex() + ":" + r.id();
            if (!Objects.equals(e.activityId, r.terminal() ? 2 : 1)) {
                problems.add(what + " activity_id " + e.activityId + " but the record is " + (r.terminal() ? "terminal" : "non-terminal"));
            }
            if (!Objects.equals(r.stopReason(), e.text("stop_reason"))) {
                problems.add(what + " stop_reason " + e.text("stop_reason") + " != " + r.stopReason());
            }
            if (r.model() != null && !r.model().equals(e.text("model_ref", "name"))) {
                problems.add(what + " model_ref.name " + e.text("model_ref", "name") + " != " + r.model());
            }
            List<Json> blocks = e.get("content_blocks") == null ? null : e.elements("content_blocks");
            if (r.blocks() == null) {
                if (blocks != null) {
                    problems.add(what + " content unavailable at source but content_blocks present");
                }
                continue;
            }
            if (blocks == null || blocks.size() != r.blocks().size()) {
                problems.add(what + " content_blocks " + (blocks == null ? "absent" : blocks.size()) + " != " + r.blocks().size() + " native blocks");
                continue;
            }
            for (int i = 0; i < blocks.size(); i++) {
                Json native_ = r.blocks().get(i);
                Json b = blocks.get(i);
                if (!Objects.equals(b.member("block_index") == null ? null : b.member("block_index").scalarText(), Integer.toString(i))) {
                    problems.add(what + " block " + i + " block_index " + (b.member("block_index") == null ? null : b.member("block_index").scalarText()));
                }
                if (native_ instanceof Json.JObject && !Objects.equals(SchemaCatalog.text(native_, "type"), SchemaCatalog.text(b, "block_type"))) {
                    problems.add(what + " block " + i + " block_type " + SchemaCatalog.text(b, "block_type") + " != " + SchemaCatalog.text(native_, "type"));
                }
                compare(what + " block " + i + " data", native_, b.member("data"), problems, representation);
            }
        }
        valueCheck("response.values", owner, examined, problems, representation);
    }

    private void prompts(ExpectedFacts facts, List<Event> events, String owner) {
        List<String> expected = new ArrayList<>();
        for (ExpectedFacts.Prompt p : facts.prompts) {
            expected.add(Integer.toString(p.recordIndex()));
        }
        List<String> actual = new ArrayList<>();
        for (Event e : events) {
            if (e.is("prompt_turn", 1)) {
                actual.add(String.valueOf(e.recordIndex()));
            }
        }
        populationCheck("population.prompts", owner, expected, actual);
        List<String> problems = new ArrayList<>();
        int examined = 0;
        for (ExpectedFacts.Prompt p : facts.prompts) {
            Event e = null;
            for (Event c : events) {
                if (c.is("prompt_turn", 1) && Objects.equals(c.recordIndex(), p.recordIndex())) {
                    e = c;
                }
            }
            if (e == null) {
                continue;
            }
            examined++;
            if (!Objects.equals(p.turnUid(), e.text("turn_uid"))) {
                problems.add("record " + p.recordIndex() + " turn_uid " + e.text("turn_uid") + " != " + p.turnUid());
            }
            if (p.texts() != null) {
                List<String> texts = new ArrayList<>();
                for (Json b : e.elements("content_blocks")) {
                    if (SchemaCatalog.text(b, "text") != null) {
                        texts.add(SchemaCatalog.text(b, "text"));
                    }
                }
                if (!texts.equals(p.texts())) {
                    problems.add("record " + p.recordIndex() + " block texts differ (" + texts.size() + " vs " + p.texts().size() + " text block(s))");
                }
            }
            else if (e.get("content_blocks") != null) {
                problems.add("record " + p.recordIndex() + " content unavailable at source but content_blocks present");
            }
        }
        valueCheck("prompt.values", owner, examined, problems, null);
    }

    private void usage(ExpectedFacts facts, List<Event> events, String owner) {
        List<String> expected = new ArrayList<>();
        for (ExpectedFacts.Usage u : facts.usages) {
            expected.add(u.eventRecordIndex() + ":" + u.recordIndex() + ":" + u.scopeId() + ":" + u.model());
        }
        record Observed(Event event, Json obs) {
        }
        List<Observed> observed = new ArrayList<>();
        List<String> actual = new ArrayList<>();
        for (Event e : events) {
            for (Json u : e.elements("usage_observations")) {
                observed.add(new Observed(e, u));
                actual.add(e.recordIndex() + ":" + (u.path("source_ref", "record_index") == null ? null : u.path("source_ref", "record_index").scalarText())
                        + ":" + (u.member("scope_id") == null ? null : u.member("scope_id").scalarText()) + ":" + SchemaCatalog.text(u.member("model_ref"), "name"));
            }
        }
        populationCheck("population.usage", owner, expected, actual);
        List<String> problems = new ArrayList<>();
        List<String> representation = new ArrayList<>();
        int examined = 0;
        for (ExpectedFacts.Usage u : facts.usages) {
            String key = u.eventRecordIndex() + ":" + u.recordIndex() + ":" + u.scopeId() + ":" + u.model();
            for (int i = 0; i < observed.size(); i++) {
                if (!actual.get(i).equals(key)) {
                    continue;
                }
                examined++;
                Json o = observed.get(i).obs();
                if (!Objects.equals(Event.integer(o.member("usage_status_id")), u.statusId())) {
                    problems.add(key + " usage_status_id " + (o.member("usage_status_id") == null ? null : o.member("usage_status_id").scalarText()) + " != " + u.statusId());
                }
                for (String c : AnalysisViews.COMPONENTS) {
                    Json want = u.components().get(c);
                    Json have = o.member(c);
                    if (want == null && have != null) {
                        problems.add(key + " " + c + " present (" + have.scalarText() + ") but not reported; absence is not zero");
                    }
                    else if (want != null) {
                        compare(key + " " + c, want, have, problems, representation);
                    }
                }
                Json data = o.member("data");
                if (data == null) {
                    problems.add(key + " native usage object (data) not retained");
                }
            }
        }
        valueCheck("usage.values", owner, examined, problems, representation);
    }

    private void cost(ExpectedFacts facts, List<Event> events, String owner) {
        List<String> expected = new ArrayList<>();
        for (ExpectedFacts.Cost c : facts.costs) {
            expected.add(Integer.toString(c.recordIndex()));
        }
        List<String> actual = new ArrayList<>();
        Map<String, Json> byRecord = new LinkedHashMap<>();
        for (Event e : events) {
            Json c = e.get("cost_observation");
            if (c != null) {
                actual.add(String.valueOf(e.recordIndex()));
                byRecord.put(String.valueOf(e.recordIndex()), c);
            }
        }
        populationCheck("population.cost", owner, expected, actual);
        List<String> problems = new ArrayList<>();
        List<String> representation = new ArrayList<>();
        int examined = 0;
        for (ExpectedFacts.Cost c : facts.costs) {
            Json o = byRecord.get(Integer.toString(c.recordIndex()));
            if (o == null) {
                continue;
            }
            examined++;
            compare("record " + c.recordIndex() + " total_cost", c.amount(), o.member("total_cost"), problems, representation);
            if (!c.currency().equals(SchemaCatalog.text(o, "currency"))) {
                problems.add("record " + c.recordIndex() + " currency " + SchemaCatalog.text(o, "currency") + " != " + c.currency());
            }
            if (!Objects.equals(Event.integer(o.member("scope_id")), c.scopeId())) {
                problems.add("record " + c.recordIndex() + " scope_id " + (o.member("scope_id") == null ? null : o.member("scope_id").scalarText()) + " != " + c.scopeId());
            }
        }
        valueCheck("cost.values", owner, examined, problems, representation);
    }

    private void decisions(ExpectedFacts facts, List<Event> events, String owner) {
        record Found(Event event, Json decision) {
        }
        List<Found> found = new ArrayList<>();
        List<String> actual = new ArrayList<>();
        for (Event e : events) {
            List<Json> ds = new ArrayList<>();
            if (e.get("permission_decision") != null) {
                ds.add(e.get("permission_decision"));
            }
            ds.addAll(e.elements("permission_decisions"));
            for (Json d : ds) {
                found.add(new Found(e, d));
                actual.add(e.recordIndex() + ":" + (d.member("scope_id") == null ? null : d.member("scope_id").scalarText())
                        + ":" + ("tool_call".equals(e.className) ? e.text("tool_call_uid") : "-"));
            }
        }
        List<String> expected = new ArrayList<>();
        for (ExpectedFacts.Decision d : facts.decisions) {
            expected.add(d.recordIndex() + ":" + d.scopeId() + ":" + (d.scopeId() == 1 ? d.ownerId() : "-"));
        }
        populationCheck("population.decisions", owner, expected, actual);
        List<String> problems = new ArrayList<>();
        int examined = 0;
        for (ExpectedFacts.Decision d : facts.decisions) {
            String key = d.recordIndex() + ":" + d.scopeId() + ":" + (d.scopeId() == 1 ? d.ownerId() : "-");
            for (int i = 0; i < found.size(); i++) {
                if (!actual.get(i).equals(key)) {
                    continue;
                }
                examined++;
                Json o = found.get(i).decision();
                if (!Objects.equals(Event.integer(o.member("decision_id")), d.decisionId())) {
                    problems.add(key + " decision_id " + scalar(o, "decision_id") + " != " + d.decisionId());
                }
                if (!Objects.equals(SchemaCatalog.text(o, "reported_decision"), d.reportedDecision())) {
                    problems.add(key + " reported_decision " + SchemaCatalog.text(o, "reported_decision") + " != " + d.reportedDecision());
                }
                if (!Objects.equals(SchemaCatalog.text(o, "decision_source"), d.source())) {
                    problems.add(key + " decision_source " + SchemaCatalog.text(o, "decision_source") + " != " + d.source());
                }
                if (!Objects.equals(SchemaCatalog.text(o, "reason_type"), d.reasonType())) {
                    problems.add(key + " reason_type " + SchemaCatalog.text(o, "reason_type") + " != " + d.reasonType());
                }
                Json att = o.member("attribution");
                if (!Objects.equals(Event.integer(att == null ? null : att.member("outcome_id")), d.outcomeId())
                        || !Objects.equals(Event.integer(att == null ? null : att.member("method_id")), d.methodId())) {
                    problems.add(key + " attribution outcome/method " + scalar(att, "outcome_id") + "/" + scalar(att, "method_id") + " != " + d.outcomeId() + "/" + d.methodId());
                }
                List<String> cands = new ArrayList<>();
                for (Json c : att == null || att.member("candidates") == null ? List.<Json>of() : att.member("candidates").elements()) {
                    cands.add(c.text());
                }
                if (!new TreeSet<>(cands).equals(new TreeSet<>(d.candidates())) || cands.size() != d.candidates().size()) {
                    problems.add(key + " candidates " + cands + " != " + d.candidates());
                }
                if (d.scopeId() != 1 && "tool_call".equals(found.get(i).event().className)) {
                    problems.add(key + " a " + AnalysisViews.SCOPE.get(Integer.toString(d.scopeId())) + "-scope decision is attached to a tool call");
                }
            }
        }
        valueCheck("decision.values", owner, examined, problems, null);
    }

    private static String scalar(Json o, String key) {
        Json v = o == null ? null : o.member(key);
        return v == null ? null : v.scalarText();
    }

    private void operations(ExpectedFacts facts, List<Event> events, String owner) {
        List<String> expected = new ArrayList<>();
        for (ExpectedFacts.Operation op : facts.operations) {
            expected.add(op.recordIndex() + ":" + op.uid());
        }
        List<String> actual = new ArrayList<>();
        for (Event e : events) {
            if (e.is("tool_call", 3)) {
                actual.add(e.recordIndex() + ":" + e.text("tool_operation", "uid"));
            }
        }
        populationCheck("population.operations", owner, expected, actual);
        List<String> problems = new ArrayList<>();
        List<String> representation = new ArrayList<>();
        int examined = 0;
        for (ExpectedFacts.Operation op : facts.operations) {
            Event e = null;
            for (Event c : events) {
                if (c.is("tool_call", 3) && Objects.equals(c.recordIndex(), op.recordIndex()) && Objects.equals(c.text("tool_operation", "uid"), op.uid())) {
                    e = c;
                }
            }
            if (e == null) {
                continue;
            }
            examined++;
            String what = op.recordIndex() + ":" + op.uid();
            Json o = e.get("tool_operation");
            if (op.argv() != null) {
                compare(what + " argv", op.argv(), o.member("argv"), problems, representation);
            }
            if (op.exitCode() != null && !op.exitCode().isNull()) {
                compare(what + " exit_code", op.exitCode(), o.member("exit_code"), problems, representation);
            }
            else if (o.member("exit_code") != null) {
                problems.add(what + " exit_code present but not reported");
            }
            if (!Objects.equals(op.reportedState(), SchemaCatalog.text(o, "reported_state"))) {
                problems.add(what + " reported_state " + SchemaCatalog.text(o, "reported_state") + " != " + op.reportedState());
            }
            if (!Objects.equals(e.integer("status_id"), op.statusId())) {
                problems.add(what + " status_id " + e.integer("status_id") + " != " + op.statusId());
            }
            Json att = o.member("attribution");
            if (!Objects.equals(Event.integer(att == null ? null : att.member("outcome_id")), op.outcomeId())
                    || !Objects.equals(Event.integer(att == null ? null : att.member("method_id")), op.methodId())) {
                problems.add(what + " attribution outcome/method " + scalar(att, "outcome_id") + "/" + scalar(att, "method_id") + " != " + op.outcomeId() + "/" + op.methodId()
                        + " (recount: " + op.candidates().size() + " candidate(s) " + op.candidates() + ")");
            }
            List<String> cands = new ArrayList<>();
            for (Json c : att == null || att.member("candidates") == null ? List.<Json>of() : att.member("candidates").elements()) {
                cands.add(c.text());
            }
            if (!new TreeSet<>(cands).equals(new TreeSet<>(op.candidates())) || cands.size() != op.candidates().size()) {
                problems.add(what + " candidates " + cands + " != recount " + op.candidates());
            }
            String expectedOwner = op.outcomeId() == 1 ? op.candidates().get(0) : null;
            if (!Objects.equals(e.text("tool_call_uid"), expectedOwner)) {
                problems.add(what + " tool_call_uid " + e.text("tool_call_uid") + " != " + expectedOwner + " (set only for a unique candidate)");
            }
        }
        valueCheck("operation.values", owner, examined, problems, representation);
    }

    private void turns(ExpectedFacts facts, List<Event> events, String owner) {
        List<String> expected = new ArrayList<>();
        for (ExpectedFacts.TurnCompleted t : facts.completed) {
            expected.add(Integer.toString(t.recordIndex()));
        }
        List<String> actual = new ArrayList<>();
        for (Event e : events) {
            if (e.is("prompt_turn", 2)) {
                actual.add(String.valueOf(e.recordIndex()));
            }
        }
        populationCheck("population.turn_completed", owner, expected, actual);
        List<String> problems = new ArrayList<>();
        int examined = 0;
        for (ExpectedFacts.TurnCompleted t : facts.completed) {
            for (Event e : events) {
                if (!e.is("prompt_turn", 2) || !Objects.equals(e.recordIndex(), t.recordIndex())) {
                    continue;
                }
                examined++;
                if (!Objects.equals(e.integer("status_id"), t.statusId())) {
                    problems.add("record " + t.recordIndex() + " status_id " + e.integer("status_id") + " != " + t.statusId());
                }
                if (!Objects.equals(e.text("stop_reason"), t.stopReason())) {
                    problems.add("record " + t.recordIndex() + " stop_reason " + e.text("stop_reason") + " != " + t.stopReason());
                }
            }
        }
        valueCheck("turn.values", owner, examined, problems, null);
    }

    // ------------------------------------------------------------------------------------------ explanation

    private void time(NativeArtifact art, ExpectedFacts facts, List<Event> events, String owner) {
        List<String> problems = new ArrayList<>();
        int examined = 0;
        for (Event e : events) {
            Integer idx = e.recordIndex();
            if (idx == null || !facts.owned.contains(idx)) {
                continue;
            }
            examined++;
            String nativeTime = facts.nativeTime.get(idx);
            String original = e.text("metadata", "original_time");
            Integer basis = e.integer("journal", "time_basis_id");
            if (nativeTime != null) {
                if (!nativeTime.equals(original) || !Objects.equals(basis, 1)) {
                    problems.add("record " + idx + " reports " + nativeTime + "; event original_time " + original + ", time_basis_id " + basis);
                }
            }
            else if (original != null || !Objects.equals(basis, 2)) {
                problems.add("record " + idx + " reports no time; event original_time " + original + ", time_basis_id " + basis + " (fallback expected)");
            }
        }
        report.add(Category.EXPLANATION, "time.basis", owner, examined == 0 ? Outcome.NOT_APPLICABLE : problems.isEmpty() ? Outcome.VERIFIED : Outcome.DEFECTIVE,
                problems.isEmpty() ? examined + " event(s): original_time verbatim with basis 1, or absent with fallback basis 2" : String.join("; ", problems));
    }

    // ------------------------------------------------------------------------------------------ reconstruction

    private void reconstruction(NativeArtifact art, ExpectedFacts facts, List<Event> events, String owner) {
        Reconstruction.Result r = Reconstruction.reconstruct(events, art.records().size());
        List<String> structure = new ArrayList<>();
        List<String> representation = new ArrayList<>();
        List<String> bytes = new ArrayList<>();
        List<String> gaps = new ArrayList<>();
        int compared = 0;
        int opaque = 0;
        int parsedByteEqual = 0;
        for (NativeArtifact.Record rec : art.records()) {
            if (!facts.owned.contains(rec.index())) {
                continue;
            }
            byte[] line = r.lines().get(rec.index());
            if (line == null) {
                if (!facts.excluded.containsKey(rec.index())) {
                    gaps.add(Integer.toString(rec.index()));
                }
                continue;
            }
            compared++;
            if (rec.parsed()) {
                if (java.util.Arrays.equals(line, rec.raw())) {
                    parsedByteEqual++;
                }
                try {
                    Json again = NeutralJson.parse(new String(line, StandardCharsets.UTF_8));
                    for (Difference d : Json.differences(rec.json(), again)) {
                        if (d.kind() == Difference.Kind.REPRESENTATION) {
                            representation.add("record " + rec.index() + " " + d);
                        }
                        else {
                            structure.add("record " + rec.index() + " " + d);
                        }
                    }
                }
                catch (IllegalArgumentException e) {
                    structure.add("record " + rec.index() + " reconstructed text is not JSON: " + e.getMessage());
                }
            }
            else {
                opaque++;
                if (!java.util.Arrays.equals(line, rec.raw())) {
                    bytes.add("record " + rec.index() + " bytes differ");
                }
            }
        }
        report.add(Category.PRESERVATION, "reconstruction.coverage", owner, gaps.isEmpty() ? Outcome.VERIFIED : Outcome.DEFECTIVE,
                gaps.isEmpty() ? compared + " owned record(s) reconstructed from whole-record events" : "no whole-record event for record(s) " + gaps);
        report.add(Category.PRESERVATION, "reconstruction.structure_value", owner, compared == 0 ? Outcome.NOT_APPLICABLE : structure.isEmpty() ? Outcome.VERIFIED : Outcome.DEFECTIVE,
                structure.isEmpty() ? "re-serialized records equal the native records in structure and value" : String.join("; ", structure));
        report.add(Category.PRESERVATION, "reconstruction.representation", owner, compared == 0 ? Outcome.NOT_APPLICABLE : representation.isEmpty() ? Outcome.VERIFIED : Outcome.REPRESENTATION_DIFFERS,
                representation.isEmpty() ? "number spellings preserved" : String.join("; ", representation));
        int parsed = compared - opaque;
        boolean allBytes = bytes.isEmpty() && parsedByteEqual == parsed;
        report.add(Category.PRESERVATION, "reconstruction.bytes", owner,
                compared == 0 ? Outcome.NOT_APPLICABLE : !bytes.isEmpty() ? Outcome.DEFECTIVE : allBytes ? Outcome.VERIFIED : Outcome.UNSUPPORTED,
                !bytes.isEmpty() ? String.join("; ", bytes)
                        : allBytes ? compared + " record(s) byte-exact (" + opaque + " opaque, promised; " + parsed + " parsed, observed: compact native JSON with number spellings and key order kept)"
                        : "not promised for parsed records: " + parsedByteEqual + " of " + parsed + " parsed record(s) byte-equal (whitespace or key order differ elsewhere); "
                                + opaque + " opaque record(s) byte-exact");
    }
}
