package io.github.markpollack.journal.tck.ocsf;

import io.github.markpollack.journal.tck.verify.Check.Outcome;
import io.github.markpollack.journal.tck.verify.Json;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static io.github.markpollack.journal.tck.ocsf.OcsfFixtures.CHILD;
import static io.github.markpollack.journal.tck.ocsf.OcsfFixtures.CODEX;
import static io.github.markpollack.journal.tck.ocsf.OcsfFixtures.SPAWN;
import static io.github.markpollack.journal.tck.ocsf.OcsfFixtures.copy;
import static io.github.markpollack.journal.tck.ocsf.OcsfFixtures.editDeclarations;
import static io.github.markpollack.journal.tck.ocsf.OcsfFixtures.editManifest;
import static io.github.markpollack.journal.tck.ocsf.OcsfFixtures.obj;
import static io.github.markpollack.journal.tck.ocsf.OcsfFixtures.open;
import static io.github.markpollack.journal.tck.ocsf.OcsfFixtures.pkg;
import static io.github.markpollack.journal.tck.ocsf.OcsfFixtures.str;
import static io.github.markpollack.journal.tck.ocsf.OcsfFixtures.verify;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every branch of the compatibility and identity checks, provoked by editing the compatibility declaration (and its
 * mirror in the ancillary manifest) or the recording declaration of a committed fixture: the oracle's
 * {@code source.compatibility} outcomes for each declared status and each internal inconsistency, the reader's
 * compatibility and process-outcome diagnostics, the version comparison itself, and the identity check's
 * not-applicable case on a source whose profile reads no session id. Each edit names the exact detail the check
 * emits, so weakening or deleting the comparison is detected.
 */
class CompatibilityAndIdentityOracleTest {

    static final String MAIN = "main/stream.jsonl";
    static final String UNTESTED = "syn-claude-untested-version";

    /** Edits the in-force compatibility declaration of the root file and the same object in run.json. */
    static void editCompatibility(Path fixture, Consumer<Json.JObject> edit) {
        editDeclarations(pkg(fixture), decls -> decls.forEach(d -> {
            if ("compatibility".equals(d.member("declaration").member("kind").text())) {
                edit.accept(obj(d.member("compatibility")));
            }
        }));
        editManifest(pkg(fixture), m -> {
            for (Json s : m.get("sources").elements()) {
                if (s.member("compatibility") != null) {
                    edit.accept(obj(s.member("compatibility")));
                }
            }
        });
    }

    @Test
    void aTestedSelectionIsVerifiedAndEachInternalInconsistencyIsADefect(@TempDir Path work) {
        Path control = copy(CHILD, work.resolve("control"));
        OracleReport ok = verify(control);
        assertThat(ok.outcome("source.compatibility", MAIN)).isEqualTo(Outcome.VERIFIED);
        assertThat(ok.check("source.compatibility", MAIN).detail()).contains("entry claude-code/stream-json/2.1.299-sdk selected").contains("status tested").doesNotContain("not individually tested");

        Path noEntry = copy(CHILD, work.resolve("no-entry"));
        editCompatibility(noEntry, c -> c.members().remove("entry_id"));
        assertThat(verify(noEntry).check("source.compatibility", MAIN).detail()).contains("status tested without an entry_id");

        Path outside = copy(CHILD, work.resolve("outside"));
        editCompatibility(outside, c -> c.members().put("min_version", str("2.1.300")));
        assertThat(verify(outside).check("source.compatibility", MAIN).detail()).contains("version 2.1.299 lies outside the declared range [2.1.300, 2.1.299]");

        Path aboveMax = copy(CHILD, work.resolve("above-max"));
        editCompatibility(aboveMax, c -> c.members().put("max_version", str("2.1.298")));
        assertThat(verify(aboveMax).check("source.compatibility", MAIN).detail()).contains("lies outside the declared range [2.1.299, 2.1.298]");

        Path noVersion = copy(CHILD, work.resolve("no-version"));
        editCompatibility(noVersion, c -> c.members().remove("max_version"));
        assertThat(verify(noVersion).check("source.compatibility", MAIN).detail()).contains("status tested without version, min_version and max_version");

        Path notNamed = copy(CHILD, work.resolve("not-named"));
        editCompatibility(notNamed, c -> c.members().put("tested", new Json.JArray(List.of())));
        OracleReport nn = verify(notNamed);
        assertThat(nn.outcome("source.compatibility", MAIN)).isEqualTo(Outcome.DEFECTIVE);
        assertThat(nn.check("source.compatibility", MAIN).detail()).contains("status tested but version 2.1.299 is not named under the declared tested evidence");

        // in_range: verified with the honest qualifier; no entry is still a defect
        Path inRange = copy(CHILD, work.resolve("in-range"));
        editCompatibility(inRange, c -> {
            c.members().put("status", str("in_range"));
            c.members().put("tested", new Json.JArray(List.of()));
        });
        OracleReport ir = verify(inRange);
        assertThat(ir.outcome("source.compatibility", MAIN)).isEqualTo(Outcome.VERIFIED);
        assertThat(ir.check("source.compatibility", MAIN).detail()).contains("status in_range (inside the reviewed range; not individually tested)");
        OcsfPackage irp = open(inRange);
        assertThat(irp.diagnosticsWithCode("COMPATIBILITY_IN_RANGE")).hasSize(1);
        assertThat(irp.diagnosticsWithCode("CONTRACT_APPLICABILITY")).as("an in_range selection also silences the single-version contract warning").isEmpty();
    }

    @Test
    void theOtherDeclaredStatusesAreUnresolvedNotApplicableOrUnknownAndTheReaderNamesThem(@TempDir Path work) {
        Path unknownVersion = copy(CHILD, work.resolve("vu"));
        editCompatibility(unknownVersion, c -> c.members().put("status", str("version_unknown")));
        OracleReport vu = verify(unknownVersion);
        assertThat(vu.outcome("source.compatibility", MAIN)).isEqualTo(Outcome.UNRESOLVED);
        assertThat(vu.check("source.compatibility", MAIN).detail()).contains("the version was never known for this source").contains("mapped provisionally under the profile's contract claude-code/stream-json/2.1.303/0.2.0-prototype");
        OcsfPackage vup = open(unknownVersion);
        assertThat(vup.diagnosticsWithCode("COMPATIBILITY_VERSION_UNKNOWN")).hasSize(1);
        assertThat(vup.diagnosticsWithCode("CONTRACT_APPLICABILITY")).as("without a selection the contract's own single version is compared again (2.1.303 vs 2.1.299)").hasSize(1);

        Path unscoped = copy(CHILD, work.resolve("ns"));
        editCompatibility(unscoped, c -> c.members().put("status", str("not_version_scoped")));
        OracleReport ns = verify(unscoped);
        assertThat(ns.outcome("source.compatibility", MAIN)).isEqualTo(Outcome.NOT_APPLICABLE);
        assertThat(ns.check("source.compatibility", MAIN).detail()).contains("profile claude_stream normalizes nothing and has no compatibility entry");
        assertThat(open(unscoped).diagnosticsWithCode("COMPATIBILITY_STATUS_UNKNOWN")).isEmpty();

        Path bogus = copy(CHILD, work.resolve("bogus"));
        editCompatibility(bogus, c -> c.members().put("status", str("probably_fine")));
        OracleReport bg = verify(bogus);
        assertThat(bg.outcome("source.compatibility", MAIN)).isEqualTo(Outcome.UNRESOLVED);
        assertThat(bg.check("source.compatibility", MAIN).detail()).contains("compatibility status probably_fine is not one the oracle knows");
        OcsfPackage bgp = open(bogus);
        assertThat(bgp.diagnosticsWithCode("COMPATIBILITY_STATUS_UNKNOWN")).hasSize(1);
        assertThat(bgp.diagnosticsWithCode("COMPATIBILITY_STATUS_UNKNOWN").get(0).detail()).contains("probably_fine");

        // no declaration at all (a file written before the registry): unresolved, and the reader says so on a 0.3.0 file
        Path undeclared = copy(CHILD, work.resolve("undeclared"));
        editDeclarations(pkg(undeclared), decls -> decls.removeIf(d -> "compatibility".equals(d.member("declaration").member("kind").text())));
        editManifest(pkg(undeclared), m -> {
            for (Json s : m.get("sources").elements()) {
                obj(s).members().remove("compatibility");
            }
        });
        OracleReport ud = verify(undeclared);
        assertThat(ud.outcome("source.compatibility", MAIN)).isEqualTo(Outcome.UNRESOLVED);
        assertThat(ud.check("source.compatibility", MAIN).detail()).contains("no compatibility declaration for this source");
        OcsfPackage udp = open(undeclared);
        assertThat(udp.diagnosticsWithCode("COMPATIBILITY_UNDECLARED")).hasSize(1);
        assertThat(udp.diagnosticsWithCode("CONTRACT_APPLICABILITY")).hasSize(1);
    }

    @Test
    void aNoMatchSelectionIsHonestOnlyWhenNothingAfterItWasNormalizedAndNoEntryIsNamed(@TempDir Path work) {
        Path control = copy(UNTESTED, work.resolve("control"));
        OracleReport ok = verify(control);
        assertThat(ok.defects()).as(ok.toString()).isEmpty();
        assertThat(ok.outcome("source.compatibility", MAIN)).isEqualTo(Outcome.UNSUPPORTED);
        assertThat(ok.check("source.compatibility", MAIN).detail()).contains("no compatibility entry for Claude Code 2.1.999 (claude_stream, route stdout)").contains("retained and flagged untested");
        for (String p : List.of("population.requests", "request.values", "usage.values", "identity.values")) {
            assertThat(ok.outcome(p, MAIN)).as(p).isEqualTo(Outcome.UNSUPPORTED);
            assertThat(ok.check(p, MAIN).detail()).contains("compatibility no_match declared");
        }
        assertThat(ok.outcome("time.basis", MAIN)).isEqualTo(Outcome.UNSUPPORTED);
        assertThat(ok.check("time.basis", MAIN).detail()).contains("retained records carry the fallback basis");
        assertThat(ok.outcome("whole_record.class", MAIN)).as("records after the selection are expected as fallback").isEqualTo(Outcome.VERIFIED);
        assertThat(ok.check("whole_record.coverage", MAIN).detail()).startsWith("27 owned record(s)");

        // an entry named beside no_match
        Path named = copy(UNTESTED, work.resolve("named"));
        editCompatibility(named, c -> c.members().put("entry_id", str("claude-code/stream-json/2.1.299-sdk")));
        OracleReport nm = verify(named);
        assertThat(nm.outcome("source.compatibility", MAIN)).isEqualTo(Outcome.DEFECTIVE);
        assertThat(nm.check("source.compatibility", MAIN).detail()).contains("status no_match but an entry_id claude-code/stream-json/2.1.299-sdk is named");

        // the selection claimed earlier than it was: the init record (1) was normalized, so a records_before_selection of 0 is a lie
        Path earlier = copy(UNTESTED, work.resolve("earlier"));
        editCompatibility(earlier, c -> c.members().put("records_before_selection", new Json.JNumber("0")));
        OracleReport er = verify(earlier);
        assertThat(er.outcome("source.compatibility", MAIN)).isEqualTo(Outcome.DEFECTIVE);
        assertThat(er.check("source.compatibility", MAIN).detail()).contains("1 record(s) after the no_match selection (records_before_selection 0) were normalized");

        // the selection claimed later than it was: records 2..27 are then expected normalized but are fallback, a class defect
        Path later = copy(UNTESTED, work.resolve("later"));
        editCompatibility(later, c -> c.members().put("records_before_selection", new Json.JNumber("27")));
        OracleReport lt = verify(later);
        assertThat(lt.outcome("whole_record.class", MAIN)).isEqualTo(Outcome.DEFECTIVE);
        assertThat(lt.check("whole_record.class", MAIN).detail()).contains("record 4 has a normalization rule but is retained only as a source_record fallback");

        // a tested fixture relabelled no_match: its normalized events betray it (the writer-side forge, repeated here on the committed package)
        Path forged = copy(CHILD, work.resolve("forged"));
        editCompatibility(forged, c -> {
            c.members().put("status", str("no_match"));
            c.members().remove("entry_id");
            c.members().put("records_before_selection", new Json.JNumber("1"));
        });
        OracleReport fg = verify(forged);
        assertThat(fg.outcome("source.compatibility", MAIN)).isEqualTo(Outcome.DEFECTIVE);
        assertThat(fg.check("source.compatibility", MAIN).detail()).contains("were normalized; a no_match source is retained whole, not normalized");
        assertThat(open(forged).diagnosticsWithCode("COMPATIBILITY_NO_MATCH")).hasSize(1);
    }

    @Test
    void aNormalizedEventUnderAnotherContractThanTheSourcesIsADefectOfTheSelection(@TempDir Path work) {
        Path fixture = copy(CHILD, work);
        // give one normalized event the opaque-text contract (declared in no file: the reader also reports the reference), keeping the selection tested
        OcsfFixtures.editEvents(pkg(fixture), events -> obj(OcsfFixtures.at(events, "model_response", 1, 4).member("journal")).members().put("contract_uid", str("common/opaque-text/0.2.0-prototype")));
        OracleReport r = verify(fixture);
        assertThat(r.outcome("source.compatibility", MAIN)).isEqualTo(Outcome.DEFECTIVE);
        assertThat(r.check("source.compatibility", MAIN).detail()).contains("1 normalized event(s) carry a contract other than the source's declared claude-code/stream-json/2.1.303/0.2.0-prototype");
    }

    @Test
    void theProcessOutcomeOfALaunchingCaptureIsReadFromTheFileAndWarnedAbout(@TempDir Path work) throws Exception {
        for (Map.Entry<String, String> c : expectedProcessWarnings().entrySet()) {
            Path fixture = copy(CHILD, work.resolve(c.getKey().toLowerCase().replace('_', '-')));
            Json.JObject process = process(c.getKey());
            editDeclarations(pkg(fixture), decls -> decls.forEach(d -> {
                if ("recording".equals(d.member("declaration").member("kind").text())) {
                    obj(d.member("recording")).members().put("process", process);
                }
            }));
            editManifest(pkg(fixture), m -> obj(m.get("recording")).members().put("process", process));
            OcsfPackage p = open(fixture);
            assertThat(p.diagnosticsWithCode(c.getKey())).as(c.getKey()).hasSize(1);
            assertThat(p.diagnosticsWithCode(c.getKey()).get(0).detail()).contains(c.getValue());
            assertThat(p.declarations.recording().member("process").member("how")).isNotNull();
            // the file alone carries it too
            Path alone = work.resolve("alone-" + c.getKey());
            Files.createDirectories(alone);
            Files.copy(pkg(fixture).resolve("events.jsonl"), alone.resolve("events.jsonl"));
            OcsfPackage a = PackageReader.open(alone.resolve("events.jsonl"));
            assertThat(a.diagnosticsWithCode(c.getKey())).as(c.getKey() + " from the file alone").hasSize(1);
            assertThat(a.manifest.text("recording", "process", "how")).isEqualTo(process.get("how").text());
        }
        // a successful exit is silent
        Path quiet = copy(CHILD, work.resolve("quiet"));
        Json.JObject exited = process("CLI_EXIT_ZERO");
        editDeclarations(pkg(quiet), decls -> decls.forEach(d -> {
            if ("recording".equals(d.member("declaration").member("kind").text())) {
                obj(d.member("recording")).members().put("process", exited);
            }
        }));
        editManifest(pkg(quiet), m -> obj(m.get("recording")).members().put("process", exited));
        OcsfPackage q = open(quiet);
        for (String code : List.of("CLI_EXIT_NONZERO", "CLI_CANCELLED", "PROCESS_OUTCOME_UNKNOWN")) {
            assertThat(q.diagnosticsWithCode(code)).isEmpty();
        }
        // and a replay package carries no process outcome at all
        assertThat(open(copy(CHILD, work.resolve("plain"))).manifest.text("recording", "process", "how")).isNull();
    }

    static Map<String, String> expectedProcessWarnings() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("CLI_EXIT_NONZERO", "exited with 2 (2: the CLI reported failure");
        m.put("CLI_CANCELLED", "cancelled the CLI process (deliberate cancellation after 6 record(s)); exit code 143");
        m.put("PROCESS_OUTCOME_UNKNOWN", "could not establish how the CLI process ended (the process did not end within the grace period");
        return m;
    }

    static Json.JObject process(String code) {
        Map<String, Json> p = new LinkedHashMap<>();
        p.put("pid", new Json.JNumber("4242"));
        p.put("started", str("2026-10-21T15:00:00Z"));
        p.put("ended", str("2026-10-21T15:00:05Z"));
        switch (code) {
            case "CLI_EXIT_NONZERO" -> {
                p.put("how", str("exited"));
                p.put("exit_code", new Json.JNumber("2"));
                p.put("exit_meaning", str("2: the CLI reported failure; whether its stream was completely acquired is judged separately"));
            }
            case "CLI_CANCELLED" -> {
                p.put("how", str("cancelled"));
                p.put("exit_code", new Json.JNumber("143"));
                p.put("cancel_reason", str("deliberate cancellation after 6 record(s)"));
            }
            case "PROCESS_OUTCOME_UNKNOWN" -> {
                p.put("how", str("unknown"));
                p.put("exit_code", Json.JNull.INSTANCE);
                p.put("detail", str("the process did not end within the grace period after termination was requested"));
            }
            default -> {
                p.put("how", str("exited"));
                p.put("exit_code", new Json.JNumber("0"));
                p.put("exit_meaning", str("0: the CLI reported success"));
            }
        }
        return new Json.JObject(p);
    }

    @Test
    void versionsCompareNumericallyPerComponentInTheOracleToo() {
        assertThat(OcsfOracle.compareVersions("2.1.299", "2.1.304")).isNegative();
        assertThat(OcsfOracle.compareVersions("2.1.304", "2.1.299")).isPositive();
        assertThat(OcsfOracle.compareVersions("2.1.304", "2.1.304")).isZero();
        assertThat(OcsfOracle.compareVersions("2.1.1000", "2.1.999")).as("numeric, not lexical").isPositive();
        assertThat(OcsfOracle.compareVersions("2.1", "2.1.0")).as("a missing component is zero").isZero();
        assertThat(OcsfOracle.compareVersions("2.1.0", "2.1")).isZero();
        assertThat(OcsfOracle.compareVersions("2.2", "2.1.99")).isPositive();
        assertThat(OcsfOracle.compareVersions("1.9.9", "0.160.1")).isPositive();
        assertThat(OcsfOracle.compareVersions("2.1.299-beta", "2.1.299-alpha")).as("non-numeric components compare as text").isPositive();
        assertThat(OcsfOracle.compareVersions("2.1.299", "2.1.299-alpha")).isNegative();
    }

    @Test
    void identityIsNotApplicableWhereTheProfileReadsNoSessionOrRecordIdentifier(@TempDir Path work) {
        OracleReport rollout = verify(copy(CODEX, work.resolve("rollout")));
        assertThat(rollout.outcome("identity.values", "main/rollout.jsonl")).isEqualTo(Outcome.NOT_APPLICABLE);
        assertThat(rollout.check("identity.values", "main/rollout.jsonl").detail()).contains("no normalized event of a record with a readable session or native identifier");
        // on the Claude child core the check examines the child's own normalized events
        OracleReport claude = verify(copy(CHILD, work.resolve("claude")));
        assertThat(claude.outcome("identity.values", "child:" + SPAWN + "/stream.jsonl")).isEqualTo(Outcome.VERIFIED);
        assertThat(claude.check("identity.values", "child:" + SPAWN + "/stream.jsonl").detail()).startsWith("4 normalized event(s)");
        assertThat(claude.check("identity.values", MAIN).detail()).startsWith("13 normalized event(s)");
    }
}
