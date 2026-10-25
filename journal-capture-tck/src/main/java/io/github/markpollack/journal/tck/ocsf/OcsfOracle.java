package io.github.markpollack.journal.tck.ocsf;

import java.util.Locale;
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
import java.util.Set;
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

    /** Properties every judged core must report; acceptance refuses a report that lacks one. */
    static final List<String> MANDATORY_CORE = List.of("package.readable", "package.state", "acquisition.complete", "event.order");

    /** Properties every judged (available, frozen) source must report; acceptance refuses a report that lacks one. */
    static final List<String> MANDATORY_SOURCE = List.of("source.inventory", "source.state", "source.compatibility", "whole_record.coverage", "whole_record.class",
            "whole_record.structure_value", "whole_record.bytes", "whole_record.framing", "population.requests", "request.values",
            "population.results", "result.owner", "result.values", "population.responses", "response.values", "population.prompts",
            "prompt.values", "population.usage", "usage.values", "population.cost", "cost.values", "population.decisions", "decision.values",
            "population.operations", "operation.values", "population.turn_completed", "turn.values", "population.turn_started",
            "turn_started.values", "population.contexts", "context.values", "population.mcp", "mcp.values", "population.tool_errors", "tool_error.values", "time.basis", "identity.values",
            "reconstruction.coverage", "reconstruction.structure_value");

    private final OracleReport report = new OracleReport();
    private final Path nativeDir;
    private final ExpectedSources expected;

    private OcsfOracle(Path nativeDir, ExpectedSources expected) {
        this.nativeDir = nativeDir;
        this.expected = expected;
    }

    /**
     * Verifies against the expected-source manifest found beside the native artifacts ({@code expected-sources.json}),
     * or, when none exists, against the candidate's own declaration, which is then reported as an unresolved
     * denominator and never accepted.
     *
     * @param pkg the opened package (root core and linked children)
     * @param nativeDir directory holding the native artifacts under the names the package's source inventory uses
     */
    public static OracleReport verify(OcsfPackage pkg, Path nativeDir) {
        return verify(pkg, nativeDir, ExpectedSources.beside(nativeDir));
    }

    /**
     * @param expected the independently frozen expected-source manifest, or {@code null} for candidate-declared
     *        inspection (historical bounded packages), which cannot be accepted
     */
    public static OracleReport verify(OcsfPackage pkg, Path nativeDir, ExpectedSources expected) {
        OcsfOracle o = new OcsfOracle(nativeDir, expected);
        o.describe(pkg);
        for (OcsfPackage core : pkg.all()) {
            o.core(core);
        }
        o.combination(pkg);
        return o.report;
    }

    /**
     * The writer's combination policy as the file states it. One source can be a final execution here. A declared
     * final assembly is judged only by the assembly oracle ({@code AssemblyOracle.verify}, with an independently supplied
     * expectation and inputs), which this per-source verification does not run: a declaration states the policy but
     * does not show its checks ran, so whether the file is one combined execution is unresolved here (review finding
     * CDX-R1D-01). Several sources without assembly are an unvalidated evidence log: no identity relation between them
     * was checked, so the file cannot be accepted as a final combined execution, whether it declares that status or
     * predates the policy. Declaring both is a defect.
     */
    private void combination(OcsfPackage pkg) {
        if (pkg.refused()) {
            return; // nothing else is judged on a refused package
        }
        OcsfPackage.Combination c = pkg.combination();
        String owner = pkg.label();
        switch (c) {
            case SINGLE_SOURCE -> report.add(Category.COMPLETENESS, "combination.policy", owner, Outcome.VERIFIED, "one declared source");
            case ASSEMBLED -> report.add(Category.COMPLETENESS, "combination.policy", owner, Outcome.UNRESOLVED,
                    "a declared final assembly: its membership, grouping and consolidation are judged only by the assembly oracle with an independently supplied "
                            + "expectation and inputs, which this verification did not run; not accepted here as a combined execution");
            case CONTRADICTORY -> report.add(Category.COMPLETENESS, "combination.policy", owner, Outcome.DEFECTIVE,
                    "the file declares both a final assembly and an unvalidated evidence log (" + pkg.declarations.run().path("combination", "reason").text()
                            + "): two combination policies, neither believed");
            case UNVALIDATED_EVIDENCE_LOG -> report.add(Category.COMPLETENESS, "combination.policy", owner, Outcome.UNRESOLVED,
                    "a declared unvalidated evidence log (" + pkg.declarations.run().path("combination", "reason").text() + "): no identity relation between its sources was checked; not a final combined execution");
            case UNVALIDATED_UNDECLARED -> report.add(Category.COMPLETENESS, "combination.policy", owner, Outcome.UNRESOLVED,
                    "several sources without a final assembly or an evidence-log declaration (written before the combination policy): no identity relation between them was checked; not a final combined execution");
        }
    }

    private void describe(OcsfPackage pkg) {
        report.oracle("expectedSources", expected == null ? "none: candidate-declared inventory (inspection only; not acceptable)"
                : expected.describe() + (expected.complete() ? " [complete run]" : " [not a complete run]"));
        report.candidate("rootDir", pkg.dir.toString());
        report.candidate("runUid", pkg.manifest == null ? null : pkg.manifest.runUid());
        report.candidate("packageVersion", pkg.manifest == null ? null : pkg.manifest.packageVersion());
        report.candidate("packageState", pkg.manifest == null ? null : pkg.manifest.packageState());
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
            cores.add(core.label() + " " + (core.manifest == null ? "(refused)" : core.manifest.runUid()) + " (" + core.events.size() + " events)");
        }
        report.candidate("cores", cores);
        report.oracle("nativeDir", nativeDir.toString());
    }

    private void core(OcsfPackage core) {
        String label = core.label();
        report.require(label, "package.readable");
        if (core.refused()) {
            report.add(Category.COMPLETENESS, "package.readable", label, Outcome.UNSUPPORTED,
                    "reader refused the package: " + core.diagnostics(Diagnostic.Severity.REFUSED));
            return;
        }
        report.require(label, MANDATORY_CORE.toArray(String[]::new));
        report.add(Category.COMPLETENESS, "package.readable", label, core.diagnostics(Diagnostic.Severity.DEFECT).isEmpty() ? Outcome.VERIFIED : Outcome.DEFECTIVE,
                core.diagnostics(Diagnostic.Severity.DEFECT).isEmpty() ? "pins, hashes, definitions and shared references resolve"
                        : core.diagnostics(Diagnostic.Severity.DEFECT).toString());
        String state = core.manifest.packageState();
        report.add(Category.COMPLETENESS, "package.state", label,
                "COMPLETED".equals(state) ? Outcome.VERIFIED : "RECORDING".equals(state) || "FAILED".equals(state) ? Outcome.UNRESOLVED : Outcome.UNSUPPORTED,
                "COMPLETED".equals(state) ? "storage finalized by the writer (final event count and hash); acquisition completeness is judged separately"
                        : "RECORDING".equals(state) ? "open package: the writer has not finalized it; judged as a prefix, never as complete"
                        : "FAILED".equals(state) ? "the writer failed: " + (core.manifest.json().path("recording", "failure") == null ? "no failure detail" : NeutralJson.write(core.manifest.json().path("recording", "failure")))
                        : state + ": the recording did not complete; the acquired evidence is judged, completeness is not claimed");
        acquisition(core, label);
        order(core, label);
        List<String> artifacts = sourceSet(core, label);
        for (String artifact : artifacts) {
            Json source = core.manifest.source(artifact);
            ExpectedSources.Source exp = expected == null ? null : expected.source(artifact);
            String owner = label + "/" + artifact;
            report.require(owner, "source.available");
            Path file = nativeDir.resolve(artifact);
            if (!Files.isRegularFile(file)) {
                report.add(Category.COMPLETENESS, "source.available", owner, Outcome.UNRESOLVED, "native artifact not supplied at " + file);
                continue;
            }
            // a document-framed source (a JSON document such as a usage report) is one record
            NativeArtifact art = "document".equals(SchemaCatalog.text(source, "framing")) ? NativeArtifact.document(file) : NativeArtifact.read(file);
            Map<String, Object> o = new LinkedHashMap<>();
            o.put("path", file.toString());
            o.put("sha256", art.sha256());
            o.put("records", art.records().size());
            o.put("opaque", art.opaqueCount());
            report.oracle(owner, o);
            if (exp != null && !sourceFrozen(exp, art, owner)) {
                continue; // the supplied artifact is not the frozen one: judging the candidate against it would prove nothing
            }
            report.add(Category.COMPLETENESS, "source.available", owner, Outcome.VERIFIED, exp == null ? "native artifact supplied (candidate-declared; not frozen)"
                    : "native artifact supplied and equal to the frozen expected source");
            if (source != null) {
                // an honestly incomplete candidate is judged on the prefix it declares it acquired (verified as a prefix of the
                // supplied artifact); the shortfall against the frozen extent is reported by source.extent and acquisition.complete
                art = acquiredPrefix(source, art, file, owner);
            }
            if (source == null) {
                // the candidate omitted an expected source: every obligation still applies, with no events to satisfy them
                source = new Json.JObject(Map.of("artifact", new Json.JString(artifact), "profile", new Json.JString(exp.profile())));
            }
            else if (exp != null && source.member("admit_response_uid") != null) {
                // a candidate-declared admission never narrows a frozen denominator (and is a defect on the complete layout, below)
                Map<String, Json> stripped = new LinkedHashMap<>(((Json.JObject) source).members());
                stripped.remove("admit_response_uid");
                if (!"0.1.0-prototype".equals(core.manifest.packageVersion())) {
                    report.add(Category.COMPLETENESS, "source.admission", owner, Outcome.DEFECTIVE,
                            "the candidate declares admit_response_uid " + SchemaCatalog.text(source, "admit_response_uid")
                                    + " on a " + core.manifest.packageVersion() + " package; declared admission is ignored and every record is expected");
                }
                else {
                    report.add(Category.COMPLETENESS, "source.admission", owner, Outcome.DEFECTIVE,
                            "the candidate declares a bounded admission (admit_response_uid) but the frozen expected-source manifest requires the whole artifact");
                }
                source = new Json.JObject(stripped);
            }
            if (source.member("admit_response_uid") != null && !"0.1.0-prototype".equals(core.manifest.packageVersion())) {
                // a candidate cannot shrink its own denominator: declared admission is honoured only on the historical 0.1.0 layout
                report.add(Category.COMPLETENESS, "source.admission", owner, Outcome.DEFECTIVE,
                        "the candidate declares admit_response_uid " + SchemaCatalog.text(source, "admit_response_uid")
                                + " on a " + core.manifest.packageVersion() + " package; declared admission is ignored and every record is expected");
                Map<String, Json> stripped = new LinkedHashMap<>(((Json.JObject) source).members());
                stripped.remove("admit_response_uid");
                source = new Json.JObject(stripped);
            }
            String profileName = exp != null ? exp.profile() : SchemaCatalog.text(source, "profile");
            SourceProfile profile = SourceProfile.forName(profileName);
            if (profile == null) {
                report.add(Category.COMPLETENESS, "source.profile", owner, Outcome.UNSUPPORTED,
                        "no oracle profile for " + profileName + "; only retention is judged");
                profile = (a, src, sc) -> {
                    ExpectedFacts f = new OpaqueTextProfile().expected(a, src, sc);
                    f.classUnjudged = true;
                    return f;
                };
            }
            Json scope = core.manifest.sourceScope();
            if (scope != null && core.manifest.parent() == null && !artifact.equals(SchemaCatalog.text(scope, "artifact"))) {
                scope = null; // the root core owns every record of an artifact that is not split
            }
            if (scope != null && !artifact.equals(SchemaCatalog.text(scope, "artifact"))) {
                // a child core owns records of its owner artifact only; other declared artifacts belong to the root core
                boolean stray = false;
                for (Event e : core.events) {
                    if (e.className != null && e.observation() && artifact.equals(e.artifact())) {
                        stray = true;
                    }
                }
                report.add(Category.COMPLETENESS, "source.scope", owner, stray ? Outcome.DEFECTIVE : Outcome.NOT_APPLICABLE,
                        stray ? "this split core carries events of " + artifact + ", which is outside its owner scope (" + SchemaCatalog.text(scope, "artifact") + ")"
                                : "artifact outside this split core's owner scope (" + SchemaCatalog.text(scope, "artifact") + "); judged on the root core");
                continue;
            }
            ExpectedFacts facts = profile.expected(art, source, scope);
            boolean declared = core.manifest.source(artifact) != null;
            CompatibilityHistory history = CompatibilityHistory.of(declared ? source : null);
            Set<Integer> unselected = history.unselected(art.records().size());
            if (scope != null) {
                // routing a record to a child core is normalization: one retained under a non-normalizing selection stays in the root core
                if (core.manifest.parent() == null) {
                    facts.owned.addAll(unselected);
                }
                else {
                    facts.owned.removeAll(unselected);
                }
            }
            report.denominator(owner, facts.counts());
            report.require(owner, MANDATORY_SOURCE.toArray(String[]::new));
            List<Event> events = new ArrayList<>();
            for (Event e : core.events) {
                // a consolidated event of a final assembly is judged by the assembly oracle, never as this source's own
                if (e.className != null && e.observation() && artifact.equals(e.artifact())) {
                    events.add(e);
                }
            }
            if (!declared) {
                report.add(Category.COMPLETENESS, "source.inventory", owner, Outcome.DEFECTIVE,
                        "the candidate declares no such source; the frozen manifest expects " + art.records().size() + " record(s), " + art.bytes().length + " bytes");
                report.add(Category.COMPLETENESS, "source.state", owner, Outcome.DEFECTIVE, "the candidate declares no such source, so no acquisition state exists for it");
                report.add(Category.COMPLETENESS, "source.compatibility", owner, Outcome.DEFECTIVE, "the candidate declares no such source, so no compatibility selection exists for it");
            }
            else {
                sourceInventory(source, art, owner);
                sourceState(core, source, art, events, owner);
                sourceCompatibility(source, artifact, art, history, events, owner);
            }
            if (exp != null) {
                sourceExtent(exp, declared ? source : null, owner);
            }
            // RULE-9, RULE-10, RULE-33: a record that arrived while no entry was selected is retained whole and owes no fact
            retention(art, facts, events, owner, core, unselected);
            CompatibilityHistory.Step inForce = history.inForce();
            if (facts.classUnjudged) {
                unjudged(facts, owner, "no oracle profile for this source's profile: its normalized facts are not judged here (an assembly is judged by the assembly oracle)",
                        "this oracle does not judge here", "this oracle does not judge here");
            }
            else if (inForce != null && !CompatibilityHistory.normalizing(inForce.status())) {
                // the candidate declares no normalization for this source; every normalization family is reported UNSUPPORTED,
                // never VERIFIED and never silently absent, so acceptance cannot credit it
                String why = switch (String.valueOf(inForce.status())) {
                    case "no_match" -> "compatibility no_match declared (records retained whole under the untested-compatibility contract, not normalized)";
                    case "version_unknown" -> "compatibility version_unknown to the end (no entry selected; every record retained whole and unnormalized)";
                    case "conflict" -> "compatibility conflict declared (contradictory version assertions; normalization stopped after record " + inForce.before()
                            + "; facts written before it are kept but not credited)";
                    default -> "compatibility status " + inForce.status() + " declared";
                };
                unjudged(facts, owner, why + ": normalization is not claimed for this source", "this candidate does not claim; retained records carry the fallback basis",
                        "this candidate does not claim");
            }
            else {
                ExpectedFacts owed = unselected.isEmpty() ? facts : facts.without(unselected);
                requests(owed, events, owner, core);
                results(owed, events, owner);
                responses(owed, events, owner);
                prompts(owed, events, owner);
                usage(owed, events, owner);
                cost(owed, events, owner);
                decisions(owed, events, owner);
                operations(owed, events, owner);
                turns(owed, events, owner);
                started(owed, events, owner);
                contexts(owed, events, owner);
                mcp(owed, events, owner, core);
                toolErrors(owed, events, owner);
                time(owed, events, owner);
                identity(owed, events, owner);
            }
            reconstruction(art, facts, events, owner);
        }
    }

    // ------------------------------------------------------------------------------------------ denominator

    /**
     * The artifacts this core is judged on: the frozen expected set first (each compared with the candidate's
     * declaration: omitted, duplicated or relabelled declarations are defects), then any artifact the candidate
     * declares beyond the frozen set (a defect: it is not part of the selected run). Without a manifest the
     * candidate's own declaration is the only denominator, which is reported as unresolved and never accepted.
     */
    private List<String> sourceSet(OcsfPackage core, String label) {
        List<String> artifacts = new ArrayList<>();
        Map<String, Integer> declared = new LinkedHashMap<>();
        Map<String, String> declaredProfile = new LinkedHashMap<>();
        for (Json s : core.manifest.sources()) {
            String a = SchemaCatalog.text(s, "artifact");
            declared.merge(a, 1, Integer::sum);
            declaredProfile.putIfAbsent(a, SchemaCatalog.text(s, "profile"));
        }
        if (expected == null) {
            report.add(Category.COMPLETENESS, "source.denominator", label, Outcome.UNRESOLVED,
                    "no independent expected-source manifest: the denominator is the candidate's own declaration (" + declared.keySet()
                            + "), which cannot establish that the selected run is wholly represented");
            artifacts.addAll(declared.keySet());
            return artifacts;
        }
        report.add(Category.COMPLETENESS, "source.denominator", label, Outcome.VERIFIED,
                "frozen expected-source manifest: " + expected.sources().size() + " artifact(s) " + (expected.complete() ? "of a complete run" : "of a partial capture")
                        + (expected.runUid() == null ? "" : " for run " + expected.runUid()));
        if (expected.runUid() != null && core.manifest.parent() == null && !expected.runUid().equals(core.manifest.runUid())) {
            report.add(Category.COMPLETENESS, "source.denominator", label, Outcome.DEFECTIVE,
                    "the candidate's run_uid " + core.manifest.runUid() + " is not the frozen manifest's run " + expected.runUid());
        }
        for (ExpectedSources.Source e : expected.sources()) {
            String owner = label + "/" + e.artifact();
            artifacts.add(e.artifact());
            report.require(owner, "source.expected");
            Integer n = declared.get(e.artifact());
            if (n == null) {
                report.add(Category.COMPLETENESS, "source.expected", owner, e.required() ? Outcome.DEFECTIVE : Outcome.UNSUPPORTED,
                        (e.required() ? "omitted: " : "optional source not declared: ") + "the frozen manifest expects " + e.artifact() + " (" + e.profile() + ", "
                                + e.records() + " record(s), " + e.bytes() + " bytes) and the candidate declares no such source");
            }
            else if (n > 1) {
                report.add(Category.COMPLETENESS, "source.expected", owner, Outcome.DEFECTIVE, "declared " + n + " times by the candidate");
            }
            else if (!e.profile().equals(declaredProfile.get(e.artifact()))) {
                report.add(Category.COMPLETENESS, "source.expected", owner, Outcome.DEFECTIVE,
                        "declared under profile " + declaredProfile.get(e.artifact()) + "; the frozen manifest expects " + e.profile() + " (the expected profile is applied)");
            }
            else {
                report.add(Category.COMPLETENESS, "source.expected", owner, Outcome.VERIFIED, "declared once under the expected profile " + e.profile());
            }
        }
        for (String a : declared.keySet()) {
            if (expected.source(a) == null) {
                artifacts.add(a);
                report.add(Category.COMPLETENESS, "source.expected", label + "/" + a, Outcome.DEFECTIVE,
                        "declared by the candidate but not part of the frozen expected-source manifest (" + expected.describe() + ")");
            }
        }
        if (core.manifest.parent() == null) {
            children(core, label);
        }
        return artifacts;
    }

    /** Expected split cores against the candidate's child links and each child's declared scope. */
    private void children(OcsfPackage core, String label) {
        List<String> problems = new ArrayList<>();
        List<String> seen = new ArrayList<>();
        for (OcsfPackage child : core.children) {
            String spawn = child.manifest == null ? null : SchemaCatalog.text(child.manifest.parent(), "spawn_tool_call_uid");
            seen.add(spawn);
            ExpectedSources.Child e = expected.child(spawn);
            if (e == null) {
                problems.add("child " + spawn + " is not an expected split core");
                continue;
            }
            Json scope = child.manifest.sourceScope();
            if (scope == null || !e.artifact().equals(SchemaCatalog.text(scope, "artifact")) || !e.ownerPath().equals(SchemaCatalog.text(scope, "owner_path"))
                    || !spawn.equals(SchemaCatalog.text(scope, "owner_value"))) {
                problems.add("child " + spawn + " declares scope " + (scope == null ? "none" : NeutralJson.write(scope)) + "; expected " + e.artifact() + "." + e.ownerPath() + " = " + spawn);
            }
        }
        for (ExpectedSources.Child e : expected.children()) {
            if (!seen.contains(e.spawnToolCallUid())) {
                problems.add("expected child " + e.spawnToolCallUid() + " (" + e.artifact() + "." + e.ownerPath() + ") is not linked by the candidate");
            }
        }
        report.add(Category.COMPLETENESS, "package.children", label, problems.isEmpty() ? (expected.children().isEmpty() ? Outcome.NOT_APPLICABLE : Outcome.VERIFIED) : Outcome.DEFECTIVE,
                problems.isEmpty() ? expected.children().size() + " expected split core(s), each linked with its declared owner scope" : String.join("; ", problems),
                problems.isEmpty() ? expected.children().size() : null);
    }

    /** The supplied native artifact must be the frozen one; otherwise the comparison proves nothing and is unresolved. */
    private boolean sourceFrozen(ExpectedSources.Source exp, NativeArtifact art, String owner) {
        List<String> problems = new ArrayList<>();
        if (exp.sha256() != null && !exp.sha256().equals(art.sha256())) {
            problems.add("sha256 " + art.sha256() + " != frozen " + exp.sha256());
        }
        if (exp.bytes() != null && exp.bytes() != art.bytes().length) {
            problems.add("bytes " + art.bytes().length + " != frozen " + exp.bytes());
        }
        if (exp.records() != null && exp.records() != art.records().size()) {
            problems.add("records " + art.records().size() + " != frozen " + exp.records());
        }
        if (!problems.isEmpty()) {
            report.add(Category.COMPLETENESS, "source.available", owner, Outcome.UNRESOLVED,
                    "the supplied native artifact is not the frozen expected source: " + String.join("; ", problems));
            return false;
        }
        return true;
    }

    /**
     * The candidate's declared extent against the frozen one. A candidate that declares the source complete (final,
     * ended by EOF, no incomplete tail) must declare exactly the frozen artifact; a candidate that honestly declares
     * it incomplete has preserved a prefix, which is reported as unsupported for a complete run, never as verified.
     */
    private void sourceExtent(ExpectedSources.Source exp, Json source, String owner) {
        if (source == null) {
            report.add(Category.COMPLETENESS, "source.extent", owner, Outcome.DEFECTIVE, "not declared; expected " + exp.records() + " record(s), " + exp.bytes() + " bytes");
            return;
        }
        boolean isFinal = RunManifest.sourceFinal(source);
        String how = SchemaCatalog.text(source.member("ended"), "how");
        boolean tail = source.member("incomplete_tail_record") != null;
        boolean claimsComplete = isFinal && (how == null || "EOF".equals(how)) && !tail;
        String sha = SchemaCatalog.text(source, "sha256");
        Integer bytes = Event.integer(source.member("bytes"));
        Integer records = Event.integer(source.member("records"));
        boolean equal = (exp.sha256() == null || exp.sha256().equals(sha)) && (exp.bytes() == null || exp.bytes().equals(bytes)) && (exp.records() == null || exp.records().equals(records));
        if (claimsComplete) {
            report.add(Category.COMPLETENESS, "source.extent", owner, equal ? Outcome.VERIFIED : Outcome.DEFECTIVE,
                    equal ? "declared complete with the frozen extent (" + records + " record(s), " + bytes + " bytes)"
                            : "declared complete but with a different extent: " + records + " record(s), " + bytes + " bytes, sha256 " + sha + "; frozen: "
                            + exp.records() + ", " + exp.bytes() + ", " + exp.sha256());
            return;
        }
        boolean prefix = (exp.bytes() == null || bytes == null || bytes <= exp.bytes()) && (exp.records() == null || records == null || records <= exp.records());
        report.add(Category.COMPLETENESS, "source.extent", owner, prefix ? (expected.complete() ? Outcome.UNSUPPORTED : Outcome.VERIFIED) : Outcome.DEFECTIVE,
                prefix ? "declared incomplete (" + (isFinal ? "ended " + how : "open") + (tail ? ", incomplete tail" : "") + "): " + records + " of " + exp.records() + " record(s), "
                        + bytes + " of " + exp.bytes() + " bytes acquired" + (expected.complete() ? "; preserved, not a complete capture of the frozen run" : "; within the declared partial capture")
                        : "declared incomplete yet larger than the frozen extent: " + records + "/" + bytes + " vs " + exp.records() + "/" + exp.bytes());
    }

    /** Whether the candidate declares this source acquired to its natural end with its last record whole. */
    private static boolean declaredComplete(Json source) {
        String how = SchemaCatalog.text(source.member("ended"), "how");
        return RunManifest.sourceFinal(source) && (how == null || "EOF".equals(how)) && source.member("incomplete_tail_record") == null;
    }

    /**
     * For a source the candidate declares incomplete (open, interrupted or cut), the artifact prefix of the declared
     * byte length, when that prefix is what the candidate hashed; otherwise the whole artifact. The per-record
     * obligations then cover exactly what was acquired, while the extent and acquisition checks report the shortfall.
     */
    private NativeArtifact acquiredPrefix(Json source, NativeArtifact art, Path file, String owner) {
        if (declaredComplete(source)) {
            return art;
        }
        Integer bytes = Event.integer(source.member("bytes"));
        if (bytes == null || bytes >= art.bytes().length) {
            return art;
        }
        NativeArtifact prefix = NativeArtifact.ofBytes(file, java.util.Arrays.copyOf(art.bytes(), bytes));
        String sha = SchemaCatalog.text(source, "sha256");
        if (sha != null && !sha.equals(prefix.sha256())) {
            return art; // the declared extent is not a prefix of the supplied artifact: judged against the whole, which reports it
        }
        Map<String, Object> o = new LinkedHashMap<>();
        o.put("judged_prefix_bytes", bytes);
        o.put("judged_prefix_records", prefix.records().size());
        o.put("basis", "the candidate declares the source incomplete; its declared extent is a verified prefix of the supplied artifact and is what the per-record checks cover");
        report.oracle(owner + " (acquired prefix)", o);
        return prefix;
    }

    // ------------------------------------------------------------------------------------------ acquisition

    /**
     * Acquisition completeness, judged apart from storage finalization: every declared source ended by EOF with its
     * last record whole, consistent source states, and a declared acquisition status that agrees with them. An
     * honest incomplete acquisition is unsupported for a complete-run claim; inconsistent labels are defects.
     */
    private void acquisition(OcsfPackage core, String label) {
        String state = core.manifest.packageState();
        boolean finalized = core.manifest.finalized();
        Json acq = core.manifest.json().path("recording", "acquisition");
        String status = SchemaCatalog.text(acq, "status");
        List<String> problems = new ArrayList<>();
        List<String> incomplete = new ArrayList<>();
        boolean pending = false;
        List<Json> sources = core.manifest.sources();
        for (Json s : sources) {
            String a = SchemaCatalog.text(s, "artifact");
            boolean isFinal = RunManifest.sourceFinal(s);
            String st = SchemaCatalog.text(s, "state");
            String how = SchemaCatalog.text(s.member("ended"), "how");
            Json tail = s.member("incomplete_tail_record");
            if (!isFinal) {
                pending = true;
                if (finalized) {
                    problems.add(a + ": open source (state " + st + ", final false) inside a " + state + " package");
                }
                else {
                    incomplete.add(a + ": still " + (st == null ? "open" : st.toLowerCase(Locale.ROOT)));
                }
            }
            if ("INTERRUPTED".equals(how) || "INTERRUPTED".equals(st)) {
                incomplete.add(a + ": interrupted after " + Event.integer(s.member("records")) + " record(s)");
            }
            if (tail != null) {
                incomplete.add(a + ": record " + tail.scalarText() + " incomplete at source end");
            }
            if (isFinal && st != null && !("ENDED".equals(st) && "EOF".equals(how)) && !("INTERRUPTED".equals(st) && "INTERRUPTED".equals(how))) {
                problems.add(a + ": state " + st + " with ended.how " + how + " is inconsistent");
            }
        }
        String derived = sources.isEmpty() ? "no sources declared" : pending ? "pending" : incomplete.isEmpty() ? "complete" : "incomplete";
        if (status != null && !status.equals(derived)) {
            problems.add("declared acquisition status '" + status + "' but the source states derive '" + derived + "'");
        }
        if (sources.isEmpty()) {
            incomplete.add("no source declared: nothing was acquired");
        }
        if (acq == null && status == null) {
            incomplete.add("no acquisition declaration (" + core.manifest.packageVersion() + " layout)");
        }
        Outcome outcome;
        String detail;
        if (!problems.isEmpty()) {
            outcome = Outcome.DEFECTIVE;
            detail = "inconsistent: " + String.join("; ", problems) + (incomplete.isEmpty() ? "" : " (incomplete: " + String.join("; ", incomplete) + ")");
        }
        else if ("RECORDING".equals(state) || "FAILED".equals(state)) {
            outcome = Outcome.UNRESOLVED;
            detail = ("RECORDING".equals(state) ? "still recording" : "the writer failed") + (incomplete.isEmpty() ? "" : ": " + String.join("; ", incomplete));
        }
        else if (!incomplete.isEmpty() || "INTERRUPTED".equals(state)) {
            outcome = Outcome.UNSUPPORTED;
            detail = "acquisition incomplete (storage " + state + "): " + String.join("; ", incomplete) + "; the acquired evidence is judged, a complete capture is not established";
        }
        else {
            outcome = Outcome.VERIFIED;
            detail = sources.size() + " declared source(s) each ended by EOF with its last record whole; declared status 'complete' agrees";
        }
        report.add(Category.COMPLETENESS, "acquisition.complete", label, outcome, detail);
    }

    /**
     * One source's declared acquisition state against the package state, the native artifact and the events: an
     * open source cannot sit inside a finalized package, a declared incomplete tail must be the artifact's last
     * record, unterminated and not one JSON object, and must be retained as an Incomplete-at-source-end record;
     * conversely an artifact that ends inside a record cannot be declared whole.
     */
    private void sourceState(OcsfPackage core, Json source, NativeArtifact art, List<Event> events, String owner) {
        List<String> problems = new ArrayList<>();
        boolean isFinal = RunManifest.sourceFinal(source);
        String st = SchemaCatalog.text(source, "state");
        String how = SchemaCatalog.text(source.member("ended"), "how");
        Integer tail = Event.integer(source.member("incomplete_tail_record"));
        if (!isFinal && core.manifest.finalized()) {
            problems.add("open (final false, state " + st + ") inside a " + core.manifest.packageState() + " package");
        }
        if (isFinal && source.member("ended") == null && st != null) {
            problems.add("final but no ended marker");
        }
        if (!isFinal && (source.member("ended") != null || SchemaCatalog.text(source, "sha256") != null)) {
            problems.add("open yet carries " + (source.member("ended") != null ? "an ended marker" : "a sha256"));
        }
        NativeArtifact.Record last = art.records().isEmpty() ? null : art.records().get(art.records().size() - 1);
        boolean nativeCut = last != null && "none".equals(last.lineEnding()) && !last.parsed() && last.status() != NativeArtifact.Status.BLANK;
        if (tail != null) {
            if (last == null || tail != last.index()) {
                problems.add("incomplete_tail_record " + tail + " is not the artifact's last record (" + (last == null ? 0 : last.index()) + ")");
            }
            else if (!nativeCut) {
                problems.add("incomplete_tail_record " + tail + " but the artifact's last record is " + last.status() + " with ending " + last.lineEnding());
            }
            boolean retained = false;
            for (Event e : events) {
                if (e.wholeRecord() && Objects.equals(e.recordIndex(), tail) && Objects.equals(e.integer("parse_status_id"), 5)) {
                    retained = true;
                }
            }
            if (!retained && core.manifest.parent() == null) {
                // an unparsed tail has no owner path and belongs to the root core; a child core declares the same source end without owning it
                problems.add("incomplete_tail_record " + tail + " is not retained as a parse_status 5 (Incomplete at source end) record");
            }
        }
        else if (nativeCut && isFinal && "EOF".equals(how)) {
            problems.add("the artifact ends inside record " + last.index() + " (" + last.status() + ", no terminator) but the source is declared ended by EOF with no incomplete tail");
        }
        report.add(Category.COMPLETENESS, "source.state", owner, problems.isEmpty() ? Outcome.VERIFIED : Outcome.DEFECTIVE,
                problems.isEmpty() ? (isFinal ? "final, ended " + how + (tail == null ? ", last record whole" : ", incomplete tail record " + tail + " retained as such") : "open (state " + st + "), no hash or end marker")
                        : String.join("; ", problems));
    }

    // ------------------------------------------------------------------------------------------ inventory and order

    /**
     * The compatibility selections the candidate declares for this source, checked for internal honesty, never
     * re-derived from a registry (the oracle holds none). Whatever selection is in force, no record that arrived
     * under a non-normalizing selection (version_unknown, no_match, conflict) may have been normalized: a record before
     * the selection is retained whole, never interpreted provisionally (RULE-9). Every version assertion that names a
     * record of this artifact must be what that native record holds at the stated pointer. A tested or in_range
     * selection must name its entry, keep the version inside the declared range and have every normalized event of the
     * source under the selected contract; a no_match selection is UNSUPPORTED (an honest absence of normalization); a
     * conflict needs two contradicting assertions and is UNRESOLVED (ambiguous, not supported); version_unknown at the
     * end of a source and a missing declaration are UNRESOLVED; any normalization the selections did not permit is
     * DEFECTIVE. Candidates written before the registry carry none.
     */
    private void sourceCompatibility(Json source, String artifact, NativeArtifact art, CompatibilityHistory history, List<Event> events, String owner) {
        Json c = source.member("compatibility");
        if (c == null) {
            report.add(Category.COMPLETENESS, "source.compatibility", owner, Outcome.UNRESOLVED,
                    "no compatibility declaration for this source: no tested range, entry or applicability status is claimed");
            return;
        }
        String status = SchemaCatalog.text(c, "status");
        String version = SchemaCatalog.text(c, "version");
        String entry = SchemaCatalog.text(c, "entry_id");
        Integer before = Event.integer(c.member("records_before_selection"));
        String declaredContract = SchemaCatalog.text(source, "contract_uid");
        List<String> problems = new ArrayList<>();
        Set<Integer> unselected = history.unselected(art.records().size());
        int normalizedUnselected = 0;
        for (Event e : events) {
            if (!"source_record".equals(e.className) && e.recordIndex() != null && unselected.contains(e.recordIndex())) {
                normalizedUnselected++;
            }
        }
        assertionProblems(history, artifact, art, problems);
        switch (String.valueOf(status)) {
            case "tested", "in_range" -> {
                if (entry == null) {
                    problems.add("status " + status + " without an entry_id");
                }
                String min = SchemaCatalog.text(c, "min_version");
                String max = SchemaCatalog.text(c, "max_version");
                if (version == null || min == null || max == null) {
                    problems.add("status " + status + " without version, min_version and max_version");
                }
                else if (compareVersions(min, version) > 0 || compareVersions(version, max) > 0) {
                    problems.add("version " + version + " lies outside the declared range [" + min + ", " + max + "]");
                }
                int normalizedUnderOther = 0;
                for (Event e : events) {
                    if (!"source_record".equals(e.className) && declaredContract != null && !declaredContract.equals(e.contractUid())) {
                        normalizedUnderOther++;
                    }
                }
                if (normalizedUnderOther > 0) {
                    problems.add(normalizedUnderOther + " normalized event(s) carry a contract other than the source's declared " + declaredContract);
                }
                if (normalizedUnselected > 0) {
                    problems.add(normalizedUnselected + " normalized event(s) cite records that arrived before the selection (records_before_selection " + before
                            + "); a record before selection is retained whole, never interpreted provisionally");
                }
                if ("tested".equals(status) && c.member("tested") instanceof Json.JArray t) {
                    boolean named = false;
                    for (Json x : t.elements()) {
                        if (version != null && version.equals(SchemaCatalog.text(x, "version"))) {
                            named = true;
                        }
                    }
                    if (!named) {
                        problems.add("status tested but version " + version + " is not named under the declared tested evidence");
                    }
                }
                report.add(Category.COMPLETENESS, "source.compatibility", owner, problems.isEmpty() ? Outcome.VERIFIED : Outcome.DEFECTIVE,
                        problems.isEmpty() ? "entry " + entry + " selected for " + SchemaCatalog.text(c, "cli") + " " + version + " on route " + SchemaCatalog.text(c, "route")
                                + ", status " + status + ("in_range".equals(status) ? " (inside the reviewed range; not individually tested)" : "")
                                + (unselected.isEmpty() ? "" : "; " + unselected.size() + " record(s) that arrived before the selection retained whole and unnormalized, not credited")
                                : String.join("; ", problems));
            }
            case "no_match" -> {
                if (entry != null) {
                    problems.add("status no_match but an entry_id " + entry + " is named");
                }
                if (normalizedUnselected > 0) {
                    problems.add(normalizedUnselected + " record(s) that arrived before or after the no_match selection (records_before_selection " + before
                            + ") were normalized; a source with no selected entry is retained whole, not normalized");
                }
                report.add(Category.COMPLETENESS, "source.compatibility", owner, problems.isEmpty() ? Outcome.UNSUPPORTED : Outcome.DEFECTIVE,
                        problems.isEmpty() ? "no compatibility entry for " + SchemaCatalog.text(c, "cli") + " " + version + " (" + SchemaCatalog.text(c, "profile") + ", route "
                                + SchemaCatalog.text(c, "route") + "): acquired input retained and flagged untested; normalization is not claimed" : String.join("; ", problems));
            }
            case "version_unknown" -> {
                if (normalizedUnselected > 0) {
                    problems.add(normalizedUnselected + " record(s) were normalized while the version was unknown; a record before selection is retained whole, never interpreted provisionally");
                }
                report.add(Category.COMPLETENESS, "source.compatibility", owner, problems.isEmpty() ? Outcome.UNRESOLVED : Outcome.DEFECTIVE,
                        problems.isEmpty() ? "the version was never known for this source, so no entry was selected; every record is retained whole and unnormalized under contract "
                                + "agent-journal/untested-compatibility" : String.join("; ", problems));
            }
            case "conflict" -> {
                Set<String> versions = new java.util.LinkedHashSet<>();
                for (Json a : c.member("version_assertions") == null ? List.<Json>of() : c.member("version_assertions").elements()) {
                    versions.add(SchemaCatalog.text(a, "version"));
                }
                if (versions.size() < 2) {
                    problems.add("status conflict without two contradicting version assertions (" + versions + ")");
                }
                if (entry != null || version != null) {
                    problems.add("status conflict but an entry " + entry + " or version " + version + " is selected; an ambiguous selection chooses neither");
                }
                if (normalizedUnselected > 0) {
                    problems.add(normalizedUnselected + " record(s) after the conflict (records_before_selection " + before
                            + ") were normalized; normalization stops while the selection is ambiguous");
                }
                report.add(Category.COMPLETENESS, "source.compatibility", owner, problems.isEmpty() ? Outcome.UNRESOLVED : Outcome.DEFECTIVE,
                        problems.isEmpty() ? "contradictory version assertions " + versions + " for " + SchemaCatalog.text(c, "cli") + " (" + SchemaCatalog.text(c, "profile")
                                + ", route " + SchemaCatalog.text(c, "route") + "): the selection is ambiguous; normalization stopped after record " + before
                                + " and the source is not credited as supported" : String.join("; ", problems));
            }
            case "not_version_scoped" -> report.add(Category.COMPLETENESS, "source.compatibility", owner, Outcome.NOT_APPLICABLE,
                    "profile " + SchemaCatalog.text(c, "profile") + " normalizes nothing and has no compatibility entry");
            default -> report.add(Category.COMPLETENESS, "source.compatibility", owner, Outcome.UNRESOLVED, "compatibility status " + status + " is not one the oracle knows");
        }
    }

    /** Each declared version assertion that names a record of this artifact must be the string that native record holds at the stated pointer. */
    private static void assertionProblems(CompatibilityHistory history, String artifactName, NativeArtifact art, List<String> problems) {
        for (Json a : history.sourceAssertions(artifactName)) {
            Integer index = Event.integer(a.member("record_index"));
            String actual = nativeText(art, index, SchemaCatalog.text(a, "pointer"));
            if (actual == null || !actual.equals(SchemaCatalog.text(a, "version"))) {
                problems.add("version assertion " + SchemaCatalog.text(a, "version") + " cites record " + index + " at " + SchemaCatalog.text(a, "pointer")
                        + ", where the native record holds " + actual);
            }
        }
    }

    /** The text at a pointer of the native record with that index, or {@code null}. */
    private static String nativeText(NativeArtifact art, Integer index, String pointer) {
        NativeArtifact.Record r = index != null && index >= 1 && index <= art.records().size() ? art.records().get(index - 1) : null;
        Json at = r == null || !r.parsed() ? null : CompatibilityHistory.at(r.json(), pointer);
        return at == null ? null : at.text();
    }

    /** Numeric comparison per dot-separated component (the oracle's own, not the writer's). */
    static int compareVersions(String a, String b) {
        String[] x = a.split("\\.");
        String[] y = b.split("\\.");
        for (int i = 0; i < Math.max(x.length, y.length); i++) {
            String p = i < x.length ? x[i] : "0";
            String q = i < y.length ? y[i] : "0";
            int c = p.matches("\\d+") && q.matches("\\d+") ? new java.math.BigInteger(p).compareTo(new java.math.BigInteger(q)) : p.compareTo(q);
            if (c != 0) {
                return c;
            }
        }
        return 0;
    }

    private void sourceInventory(Json source, NativeArtifact art, String owner) {
        List<String> problems = new ArrayList<>();
        boolean isFinal = RunManifest.sourceFinal(source);
        String sha = SchemaCatalog.text(source, "sha256");
        if (!isFinal) {
            if (sha != null) {
                problems.add("an open source must not carry a sha256 (declared " + sha + ")");
            }
        }
        else if (!art.sha256().equals(sha)) {
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
                problems.isEmpty() ? (isFinal ? "sha256, bytes, record count, opaque count and trailing newline match the artifact"
                        : "open source (state " + SchemaCatalog.text(source, "state") + "): bytes, record count, opaque count and trailing newline so far match the supplied prefix; no hash yet")
                        : String.join("; ", problems));
    }

    /** Every normalization family of a source reported UNSUPPORTED, never VERIFIED and never silently absent, so acceptance cannot credit it. */
    private void unjudged(ExpectedFacts facts, String owner, String why, String timeWhy, String identityWhy) {
        String families = why + "; expected occurrences by family: " + facts.counts();
        for (String p : List.of("population.requests", "request.values", "population.results", "result.owner", "result.values", "population.responses", "response.values",
                "population.prompts", "prompt.values", "population.usage", "usage.values", "population.cost", "cost.values", "population.decisions", "decision.values",
                "population.operations", "operation.values", "population.turn_completed", "turn.values", "population.turn_started", "turn_started.values",
                "population.contexts", "context.values", "population.mcp", "mcp.values", "population.tool_errors", "tool_error.values")) {
            report.add(Category.NORMALIZATION, p, owner, Outcome.UNSUPPORTED, families);
        }
        String cause = why.endsWith(": normalization is not claimed for this source") ? why.substring(0, why.length() - ": normalization is not claimed for this source".length()) : why;
        report.add(Category.EXPLANATION, "time.basis", owner, Outcome.UNSUPPORTED, cause + ": the source's time fields are a mapping " + timeWhy);
        report.add(Category.NORMALIZATION, "identity.values", owner, Outcome.UNSUPPORTED, cause + ": session and native record identities are a mapping " + identityWhy);
    }

    private void order(OcsfPackage core, String label) {
        List<String> problems = new ArrayList<>();
        Integer last = null;
        for (Event e : core.events) {
            if (e.className == null) {
                continue;
            }
            if (e.sequence == null || (last != null && e.sequence <= last)) {
                problems.add("line " + e.line + " sequence " + e.sequence + " after " + last);
            }
            last = e.sequence;
        }
        problems.addAll(recordOrder(core.observations()));
        report.add(Category.NORMALIZATION, "event.order", label, problems.isEmpty() ? Outcome.VERIFIED : Outcome.DEFECTIVE,
                problems.isEmpty() ? "journal.sequence strictly increasing; record_index non-decreasing per artifact" : String.join("; ", problems));
    }

    /** Record order per artifact over each source's own observations (a consolidated event follows its primary observation). */
    private static List<String> recordOrder(List<Event> observations) {
        List<String> problems = new ArrayList<>();
        Map<String, Integer> lastRecord = new LinkedHashMap<>();
        for (Event e : observations) {
            Integer idx = e.className == null ? null : e.recordIndex();
            Integer prev = lastRecord.get(e.artifact());
            if (idx != null && prev != null && idx < prev) {
                problems.add("line " + e.line + " " + e.artifact() + " record " + idx + " after record " + prev);
            }
            if (idx != null) {
                lastRecord.put(e.artifact(), idx);
            }
        }
        return problems;
    }

    // ------------------------------------------------------------------------------------------ retention

    /** @param unselected records that arrived under a non-normalizing compatibility selection: expected as whole-record fallback, never normalized */
    private void retention(NativeArtifact art, ExpectedFacts facts, List<Event> events, String owner, OcsfPackage core, Set<Integer> unselected) {
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
            if (facts.normalized.contains(r.index()) && fallback && !unselected.contains(r.index())) {
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
        if (facts.classUnjudged) {
            report.add(Category.COMPLETENESS, "whole_record.class", owner, Outcome.UNSUPPORTED,
                    "no oracle profile for this source: which records a rule normalizes is not judged (every record's retention is)");
        }
        else {
            report.add(Category.COMPLETENESS, "whole_record.class", owner, classProblems.isEmpty() ? Outcome.VERIFIED : Outcome.DEFECTIVE,
                    classProblems.isEmpty() ? facts.normalized.size() + " normalized, " + (facts.owned.size() - facts.normalized.size()) + " fallback" : String.join("; ", classProblems));
        }
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
                        : bytesProblems.isEmpty() ? opaqueVerified + " opaque record(s) byte-equal" : String.join("; ", bytesProblems), opaqueOwned == 0 ? 0 : opaqueVerified);
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

    /** A value the profile may state: compared when stated; present on the event without a statement is a defect too. */
    private static void stated(String what, Object expected, Object actual, List<String> problems) {
        if (expected == null ? actual != null : !expected.equals(actual)) {
            problems.add(what + " " + actual + " != " + (expected == null ? "absent (the source profile states none)" : expected));
        }
    }

    private void populationCheck(String property, String owner, List<String> expected, List<String> actual) {
        List<String> problems = population(expected, actual);
        Outcome outcome = problems.isEmpty() ? (expected.isEmpty() ? Outcome.NOT_APPLICABLE : Outcome.VERIFIED) : Outcome.DEFECTIVE;
        report.add(Category.NORMALIZATION, property, owner, outcome,
                problems.isEmpty() ? expected.size() + " expected, " + actual.size() + " observed" : String.join("; ", problems) + " (expected " + expected.size() + ", observed " + actual.size() + ")",
                problems.isEmpty() ? expected.size() : null);
    }

    private void valueCheck(String property, String owner, int examined, List<String> problems, List<String> representation) {
        if (examined == 0) {
            report.add(Category.NORMALIZATION, property, owner, Outcome.NOT_APPLICABLE, "no occurrence", 0);
            return;
        }
        report.add(Category.NORMALIZATION, property, owner, problems.isEmpty() ? Outcome.VERIFIED : Outcome.DEFECTIVE,
                problems.isEmpty() ? examined + " occurrence(s) exact" : String.join("; ", problems), examined);
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
            // the first observed stage of a tool activity: the model's request (1) or a started invocation (4)
            if (e.is("tool_call", 1) || e.is("tool_call", 4)) {
                actual.add(e.recordIndex() + ":" + e.text("tool_call_uid"));
            }
        }
        populationCheck("population.requests", owner, expected, actual);
        List<String> problems = new ArrayList<>();
        List<String> representation = new ArrayList<>();
        int examined = 0;
        for (ExpectedFacts.Request r : facts.requests) {
            Event e = find(events, "tool_call", r.activityId(), r.recordIndex(), "tool_call_uid", r.id());
            String what = r.recordIndex() + ":" + r.id();
            if (e == null) {
                Event other = find(events, "tool_call", r.activityId() == 1 ? 4 : 1, r.recordIndex(), "tool_call_uid", r.id());
                if (other != null) {
                    examined++;
                    problems.add(what + " activity_id " + other.activityId + " != " + r.activityId() + (r.activityId() == 4
                            ? " (the source reports a started invocation, not the model's request)" : " (the source reports the model's request)"));
                }
                continue;
            }
            examined++;
            stated(what + " tool_family", r.family(), e.text("tool_family"), problems);
            if (!Objects.equals(r.name(), e.text("tool_name"))) {
                problems.add(what + " tool_name " + e.text("tool_name") + " != " + r.name());
            }
            compare(what + " arguments", r.arguments(), e.get("arguments"), problems, representation);
            if (r.responseUid() != null && !r.responseUid().equals(e.text("response_uid"))) {
                problems.add(what + " response_uid " + e.text("response_uid") + " != " + r.responseUid());
            }
            else if (r.responseUid() == null && r.identityBasisId() != null && e.text("response_uid") != null) {
                // a stream-local item states its identity basis; the source names no response, so none may be attached (RULE-44)
                problems.add(what + " response_uid " + e.text("response_uid") + " present, but the source names no response for this item");
            }
            if (r.reportedState() != null && !r.reportedState().equals(e.text("reported_state"))) {
                problems.add(what + " reported_state " + e.text("reported_state") + " != " + r.reportedState());
            }
            stated(what + " tool_namespace", r.namespace(), e.text("tool_namespace"), problems);
            stated(what + " identity_basis_id", r.identityBasisId(), e.integer("identity_basis_id"), problems);
            Integer kind = e.integer("tool_kind_id");
            Integer expectedKind = r.kindId() != null ? r.kindId() : toolKind(core, e.contractUid(), r.name());
            if (expectedKind != null && !expectedKind.equals(kind)) {
                problems.add(what + " tool_kind_id " + kind + " != " + expectedKind + (r.kindId() != null ? " (the profile's family or namespace reading)" : " from the embedded name table"));
            }
        }
        valueCheck("request.values", owner, examined, problems, representation);
    }

    /** MCP targets and projections (RULE-53): the profile's reading, the file's declared binding, the pinned definition. */
    private void mcp(ExpectedFacts facts, List<Event> events, String owner, OcsfPackage core) {
        populationCheck("population.mcp", owner, McpChecks.expectedKeys(facts), McpChecks.actualKeys(events));
        Map<String, McpProtocol.Binding> bindings = McpProtocol.fromDeclarations(core.declarations);
        List<String> problems = new ArrayList<>();
        int examined = 0;
        for (ExpectedFacts.Mcp m : facts.mcp) {
            Event e = McpChecks.find(events, m.recordIndex(), m.id(), m.activityId());
            if (e != null && e.get("mcp") != null) {
                examined++;
                problems.addAll(McpChecks.compare(m, e, bindings));
            }
        }
        valueCheck("mcp.values", owner, examined, problems, null);
    }

    /** Errors reported for tool results, each with the layer that reported it (JSON-RPC, tool result, CLI, process, capture). */
    private void toolErrors(ExpectedFacts facts, List<Event> events, String owner) {
        populationCheck("population.tool_errors", owner, McpChecks.expectedErrorKeys(facts), McpChecks.actualErrorKeys(events));
        List<String> problems = new ArrayList<>();
        int examined = 0;
        for (ExpectedFacts.ToolError t : facts.toolErrors) {
            Event e = find(events, "tool_call", 2, t.recordIndex(), "tool_call_uid", t.id());
            if (e != null) {
                examined++;
                problems.addAll(McpChecks.compareError(t, e));
            }
        }
        valueCheck("tool_error.values", owner, examined, problems, null);
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
                ownership.isEmpty() ? resultEvents + " result(s) owned by the id of their own record" : String.join("; ", ownership), resultEvents);
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
            stated(what + " identity_basis_id", r.identityBasisId(), e.integer("identity_basis_id"), problems);
            stated(what + " tool_family", r.family(), e.text("tool_family"), problems);
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
            expected.add(responseKey(r.recordIndex(), r.itemIdentityBasisId() != null ? null : r.id(), r.itemIdentityBasisId() != null ? r.id() : null));
        }
        List<String> actual = new ArrayList<>();
        for (Event e : events) {
            if ("model_response".equals(e.className)) {
                actual.add(responseKey(e.recordIndex(), e.text("response_uid"), e.text("item_uid")));
            }
        }
        populationCheck("population.responses", owner, expected, actual);
        List<String> problems = new ArrayList<>();
        List<String> representation = new ArrayList<>();
        int examined = 0;
        for (ExpectedFacts.Response r : facts.responses) {
            String key = expected.get(facts.responses.indexOf(r));
            Event e = null;
            for (Event c : events) {
                if ("model_response".equals(c.className) && responseKey(c.recordIndex(), c.text("response_uid"), c.text("item_uid")).equals(key)) {
                    e = c;
                }
            }
            if (e == null) {
                continue;
            }
            examined++;
            String what = r.recordIndex() + ":" + r.id();
            if (!Objects.equals(e.activityId, r.activityId())) {
                problems.add(what + " activity_id " + e.activityId + " but the record is " + (r.itemIdentityBasisId() != null ? "a completed content item of an unidentified response"
                        : r.terminal() ? "terminal" : "non-terminal"));
            }
            stated(what + " identity_basis_id", r.itemIdentityBasisId(), e.integer("identity_basis_id"), problems);
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
                stated(what + " block " + i + " reasoning_form_id", r.reasoningForm(), Event.integer(b.member("reasoning_form_id")), problems);
                stated(what + " block " + i + " text_update_id", r.textUpdate(), Event.integer(b.member("text_update_id")), problems);
            }
        }
        valueCheck("response.values", owner, examined, problems, representation);
    }

    /** A response's population key: its provider response id, or the source's content-item id when no response is identified. */
    private static String responseKey(Integer recordIndex, String responseUid, String itemUid) {
        return recordIndex + ":" + (responseUid != null ? responseUid : itemUid != null ? "item " + itemUid : null);
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

    /**
     * The observations an expected usage is judged against: those at its record, scope and model, narrowed to its
     * measurement window (aggregation) when several share them (a run's total and one unidentified response of that run
     * are both scope 4 on the result record), so one expectation is never judged against another quantity. A single
     * candidate is judged whatever its aggregation, so a wrong window remains a value problem.
     */
    private static List<Json> sameWindow(List<Json> candidates, Integer aggregation) {
        if (candidates.size() < 2) {
            return candidates;
        }
        List<Json> window = candidates.stream().filter(o -> Objects.equals(Event.integer(o.member("aggregation_id")), aggregation)).toList();
        return window.isEmpty() ? candidates : window;
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
            List<Json> candidates = new ArrayList<>();
            for (int i = 0; i < observed.size(); i++) {
                if (actual.get(i).equals(key)) {
                    candidates.add(observed.get(i).obs());
                }
            }
            for (Json o : sameWindow(candidates, u.aggregationId())) {
                examined++;
                if (!Objects.equals(Event.integer(o.member("usage_status_id")), u.statusId())) {
                    problems.add(key + " usage_status_id " + (o.member("usage_status_id") == null ? null : o.member("usage_status_id").scalarText()) + " != " + u.statusId());
                }
                if (u.scopeName() != null || u.scopeId() == 99) {
                    stated(key + " scope (the source-defined unit of scope 99 Other)", u.scopeName(), SchemaCatalog.text(o, "scope"), problems);
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
                stated(key + " measurement_status_id", u.measurementStatusId(), Event.integer(o.member("measurement_status_id")), problems);
                if (u.tranches() != null) {
                    tranches(key, u, o, problems);
                }
                String provider = SchemaCatalog.text(o.member("model_ref"), "provider_kind");
                if (u.provider() != null && !u.provider().equals(provider)) {
                    problems.add(key + " model_ref.provider_kind " + provider + " != reported " + u.provider());
                }
                else if (u.provider() == null && provider != null) {
                    problems.add(key + " model_ref.provider_kind " + provider + " present but the record reports no provider; a provider is never inferred from a model name");
                }
            }
        }
        valueCheck("usage.values", owner, examined, problems, representation);
    }

    /**
     * The canonical-tranche facets of one observation: aggregation, the reasoning relation, the cache-write tiers and
     * how each component is established (reported, derived or unavailable, with its native pointers), against the
     * profile's own reading.
     */
    private static void tranches(String key, ExpectedFacts.Usage u, Json o, List<String> problems) {
        stated(key + " aggregation_id", u.aggregationId(), Event.integer(o.member("aggregation_id")), problems);
        stated(key + " reasoning_relation_id", u.tranches().reasoningRelationId(), Event.integer(o.member("reasoning_relation_id")), problems);
        tiers(key, u.tranches().tiers(), o, problems);
        componentBases(key, u.tranches(), o, problems);
    }

    /** The cache-write tiers, in order: native tier name, exact count and the pointer of the native member. */
    private static void tiers(String key, List<ExpectedFacts.Tier> expected, Json o, List<String> problems) {
        List<String> want = new ArrayList<>();
        for (ExpectedFacts.Tier t : expected) {
            want.add(t.tier() + "=" + t.count().scalarText() + "@" + List.of(t.pointer()));
        }
        List<String> have = new ArrayList<>();
        for (Json t : o.member("cache_write_tiers") == null ? List.<Json>of() : o.member("cache_write_tiers").elements()) {
            Json count = t.member("token_count");
            have.add(SchemaCatalog.text(t, "tier") + "=" + (count == null ? null : count.scalarText()) + "@" + texts(t.member("native_pointers")));
        }
        if (!want.equals(have)) {
            problems.add(key + " cache_write_tiers " + have + " != " + want);
        }
    }

    /** components[]: exactly one entry per component the profile reads, with its basis and native pointers; an unavailable component has no value. */
    private static void componentBases(String key, ExpectedFacts.Tranches t, Json o, List<String> problems) {
        Map<String, Json> entries = new LinkedHashMap<>();
        for (Json c : o.member("components") == null ? List.<Json>of() : o.member("components").elements()) {
            if (entries.put(SchemaCatalog.text(c, "name"), c) != null) {
                problems.add(key + " components[] names " + SchemaCatalog.text(c, "name") + " twice");
            }
        }
        for (Map.Entry<String, Integer> b : t.bases().entrySet()) {
            Json c = entries.remove(b.getKey());
            if (c == null) {
                problems.add(key + " components[] has no entry for " + b.getKey() + " (expected basis " + b.getValue() + ")");
                continue;
            }
            componentStatement(key + " " + b.getKey(), b.getKey(), b.getValue(), t, c, problems);
            if (b.getValue() == TrancheReading.UNAVAILABLE && o.member(b.getKey()) != null) {
                problems.add(key + " " + b.getKey() + " present although the source does not establish it");
            }
        }
        if (!entries.isEmpty()) {
            problems.add(key + " components[] states " + entries.keySet() + ", which the source does not report");
        }
    }

    /**
     * One component entry against the profile's reading: its basis, what the source establishes about its quantity, a
     * conditional formula result kept apart from the canonical value, and the native pointers it is read from.
     */
    private static void componentStatement(String what, String name, Integer basis, ExpectedFacts.Tranches t, Json c, List<String> problems) {
        stated(what + " component_basis_id", basis, Event.integer(c.member("component_basis_id")), problems);
        stated(what + " measurement_status_id", t.statuses().get(name), Event.integer(c.member("measurement_status_id")), problems);
        Json conditional = c.member("conditional_value");
        Json wantConditional = t.conditionals().get(name);
        stated(what + " conditional_value", wantConditional == null ? null : wantConditional.scalarText(), conditional == null ? null : conditional.scalarText(), problems);
        stated(what + " native_pointers", t.pointers().get(name), texts(c.member("native_pointers")), problems);
    }

    /** The strings of a JSON array, in order (empty when absent). */
    private static List<String> texts(Json array) {
        List<String> out = new ArrayList<>();
        for (Json x : array == null ? List.<Json>of() : array.elements()) {
            out.add(x.text());
        }
        return out;
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
            if (!Objects.equals(c.basis(), SchemaCatalog.text(o, "cost_basis"))) {
                problems.add("record " + c.recordIndex() + " cost_basis " + SchemaCatalog.text(o, "cost_basis") + " != reported " + c.basis());
            }
        }
        valueCheck("cost.values", owner, examined, problems, representation);
    }

    /**
     * Session and native record identities on every normalized event: {@code session_uid} equals the session the
     * record reports (or, for a stream that states it once, inherits), and {@code metadata.original_event_uid} equals
     * the record's own identifier; a copy that disagrees with its record is a defect, and a session the profile
     * cannot read leaves the check not applicable rather than passed.
     */
    private void identity(ExpectedFacts facts, List<Event> events, String owner) {
        List<String> problems = new ArrayList<>();
        int examined = 0;
        for (Event e : events) {
            Integer idx = e.recordIndex();
            if (idx == null || !facts.owned.contains(idx) || "source_record".equals(e.className)) {
                continue;
            }
            String session = facts.sessionOf.get(idx);
            String uid = facts.nativeUid.get(idx);
            if (session == null && uid == null) {
                continue;
            }
            examined++;
            if (session != null && !session.equals(e.text("session_uid"))) {
                problems.add("record " + idx + " (" + e.className + ") session_uid " + e.text("session_uid") + " != reported " + session);
            }
            if (uid != null && !uid.equals(e.text("metadata", "original_event_uid"))) {
                problems.add("record " + idx + " (" + e.className + ") metadata.original_event_uid " + e.text("metadata", "original_event_uid") + " != record uuid " + uid);
            }
        }
        report.add(Category.NORMALIZATION, "identity.values", owner, examined == 0 ? Outcome.NOT_APPLICABLE : problems.isEmpty() ? Outcome.VERIFIED : Outcome.DEFECTIVE,
                problems.isEmpty() ? (examined == 0 ? "no normalized event of a record with a readable session or native identifier"
                        : examined + " normalized event(s): session_uid and metadata.original_event_uid equal the record's own values") : String.join("; ", problems));
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
            stated(what + " command_line", op.commandLine(), SchemaCatalog.text(o, "command_line"), problems);
            stated(what + " identity_basis_id", op.identityBasisId(), Event.integer(o.member("identity_basis_id")), problems);
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
                turnContent(t, e, problems);
            }
        }
        valueCheck("turn.values", owner, examined, problems, null);
    }

    /** The turn's response text blocks against what the record reports; none reported means none may be written. */
    private static void turnContent(ExpectedFacts.TurnCompleted t, Event e, List<String> problems) {
        if (t.texts() == null) {
            if (e.get("content_blocks") != null) {
                problems.add("record " + t.recordIndex() + " reports no turn content but content_blocks present");
            }
            return;
        }
        List<String> texts = new ArrayList<>();
        for (Json b : e.elements("content_blocks")) {
            texts.add(SchemaCatalog.text(b, "text"));
            if (b.member("text_update_id") != null) {
                // the turn's own text is whole: stated as an update it would be assembled with the items it repeats
                problems.add("record " + t.recordIndex() + " turn content block states text_update_id " + b.member("text_update_id").scalarText() + ", but a turn's own response is not an update of an item");
            }
        }
        if (!texts.equals(t.texts())) {
            problems.add("record " + t.recordIndex() + " turn content block texts differ (" + texts.size() + " vs " + t.texts().size() + " reported)");
        }
    }

    private void started(ExpectedFacts facts, List<Event> events, String owner) {
        List<String> expected = new ArrayList<>();
        for (ExpectedFacts.TurnStarted t : facts.started) {
            expected.add(Integer.toString(t.recordIndex()));
        }
        List<String> actual = new ArrayList<>();
        for (Event e : events) {
            if (e.is("prompt_turn", 3)) {
                actual.add(String.valueOf(e.recordIndex()));
            }
        }
        populationCheck("population.turn_started", owner, expected, actual);
        List<String> problems = new ArrayList<>();
        int examined = 0;
        for (ExpectedFacts.TurnStarted t : facts.started) {
            for (Event e : events) {
                if (e.is("prompt_turn", 3) && Objects.equals(e.recordIndex(), t.recordIndex())) {
                    examined++;
                    if (!Objects.equals(e.text("turn_uid"), t.turnUid())) {
                        problems.add("record " + t.recordIndex() + " turn_uid " + e.text("turn_uid") + " != " + t.turnUid());
                    }
                }
            }
        }
        valueCheck("turn_started.values", owner, examined, problems, null);
    }

    /** Settings / injected-context / accounting records: population by (record, activity, kind) and exact settings values. */
    private void contexts(ExpectedFacts facts, List<Event> events, String owner) {
        List<String> expected = new ArrayList<>();
        for (ExpectedFacts.Context c : facts.contexts) {
            expected.add(c.recordIndex() + ":" + c.activityId() + ":" + c.contextKind());
        }
        List<String> actual = new ArrayList<>();
        for (Event e : events) {
            if ("session_context".equals(e.className)) {
                actual.add(e.recordIndex() + ":" + e.activityId + ":" + e.text("context_kind"));
            }
        }
        populationCheck("population.contexts", owner, expected, actual);
        List<String> problems = new ArrayList<>();
        List<String> representation = new ArrayList<>();
        int examined = 0;
        for (ExpectedFacts.Context c : facts.contexts) {
            Event e = null;
            for (Event x : events) {
                if ("session_context".equals(x.className) && Objects.equals(x.recordIndex(), c.recordIndex()) && Objects.equals(x.activityId, c.activityId())) {
                    e = x;
                }
            }
            if (e == null) {
                continue;
            }
            examined++;
            String what = c.recordIndex() + ":" + c.contextKind();
            if (!Objects.equals(c.turnUid(), e.text("turn_uid"))) {
                problems.add(what + " turn_uid " + e.text("turn_uid") + " != " + c.turnUid());
            }
            if (!Objects.equals(c.renderedRole(), e.text("rendered_role"))) {
                problems.add(what + " rendered_role " + e.text("rendered_role") + " != " + c.renderedRole());
            }
            stated(what + " tool_call_uid (the spawning call of the child run accounted for)", facts.contextToolCalls.get(c.recordIndex() + ":" + c.contextKind()), e.text("tool_call_uid"), problems);
            if (c.blocks() == null) {
                if (e.get("content_blocks") != null) {
                    problems.add(what + " content_blocks present but the source renders nothing");
                }
            }
            else if (e.get("content_blocks") == null || e.elements("content_blocks").size() != c.blocks().size()) {
                problems.add(what + " content_blocks " + (e.get("content_blocks") == null ? "absent" : e.elements("content_blocks").size()) + " != " + c.blocks().size());
            }
            else {
                // every promised block value: index, type, the rendered text verbatim and the native element retained as data
                List<Json> blocks = e.elements("content_blocks");
                for (int i = 0; i < blocks.size(); i++) {
                    ExpectedFacts.Block want = c.blocks().get(i);
                    Json b = blocks.get(i);
                    if (!Objects.equals(b.member("block_index") == null ? null : b.member("block_index").scalarText(), Integer.toString(i))) {
                        problems.add(what + " block " + i + " block_index " + (b.member("block_index") == null ? null : b.member("block_index").scalarText()));
                    }
                    if (!Objects.equals(want.blockType(), SchemaCatalog.text(b, "block_type"))) {
                        problems.add(what + " block " + i + " block_type " + SchemaCatalog.text(b, "block_type") + " != " + want.blockType());
                    }
                    if (!Objects.equals(want.text(), SchemaCatalog.text(b, "text"))) {
                        problems.add(what + " block " + i + " text differs from the source's rendered content (" + (want.text() == null ? "none rendered" : want.text().length() + " chars")
                                + " at source, " + (SchemaCatalog.text(b, "text") == null ? "absent" : SchemaCatalog.text(b, "text").length() + " chars") + " normalized)");
                    }
                    compare(what + " block " + i + " data", want.data(), b.member("data"), problems, representation);
                }
            }
            Json settings = e.get("settings");
            for (Map.Entry<String, Json> f : c.settings().entrySet()) {
                compare(what + " settings." + f.getKey(), f.getValue(), settings == null ? null : settings.member(f.getKey()), problems, representation);
            }
            if (settings instanceof Json.JObject so) {
                for (String k : so.members().keySet()) {
                    if (!c.settings().containsKey(k)) {
                        problems.add(what + " settings." + k + " present but the record reports no such setting");
                    }
                }
            }
            else if (!c.settings().isEmpty()) {
                problems.add(what + " settings absent; the record reports " + c.settings().keySet());
            }
            if (e.get("data") == null) {
                problems.add(what + " whole record (data) not retained");
            }
        }
        valueCheck("context.values", owner, examined, problems, representation);
    }

    // ------------------------------------------------------------------------------------------ explanation

    private void time(ExpectedFacts facts, List<Event> events, String owner) {
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
