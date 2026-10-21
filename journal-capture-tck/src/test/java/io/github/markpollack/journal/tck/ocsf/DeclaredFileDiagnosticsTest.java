package io.github.markpollack.journal.tck.ocsf;

import io.github.markpollack.journal.tck.verify.Json;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static io.github.markpollack.journal.tck.ocsf.OcsfFixtures.CHILD;
import static io.github.markpollack.journal.tck.ocsf.OcsfFixtures.CODEX;
import static io.github.markpollack.journal.tck.ocsf.OcsfFixtures.copy;
import static io.github.markpollack.journal.tck.ocsf.OcsfFixtures.declaration;
import static io.github.markpollack.journal.tck.ocsf.OcsfFixtures.editDeclarations;
import static io.github.markpollack.journal.tck.ocsf.OcsfFixtures.editManifest;
import static io.github.markpollack.journal.tck.ocsf.OcsfFixtures.lines;
import static io.github.markpollack.journal.tck.ocsf.OcsfFixtures.num;
import static io.github.markpollack.journal.tck.ocsf.OcsfFixtures.obj;
import static io.github.markpollack.journal.tck.ocsf.OcsfFixtures.open;
import static io.github.markpollack.journal.tck.ocsf.OcsfFixtures.pkg;
import static io.github.markpollack.journal.tck.ocsf.OcsfFixtures.str;
import static io.github.markpollack.journal.tck.ocsf.OcsfFixtures.writeLines;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every diagnostic the standalone-layout reader can raise, each provoked by one edit of a regenerated fixture:
 * the ancillary manifest disagreeing with the declarations on each identity it is checked on, finalization
 * claimed or contradicted, a persisted prefix shorter or different than pinned, counts, an unreadable ancillary,
 * malformed, duplicate, unknown and conflicting declarations, a contract hash, an attribute used before its
 * amendment, a launch version that disagrees with the stream, and the derived acquisition states.
 */
class DeclaredFileDiagnosticsTest {

    @Test
    void theAncillaryManifestIsIgnoredWithADefectWhenItDisagreesOnEachIdentity(@TempDir Path work) {
        Path a = copy(CODEX, work.resolve("run-uid"));
        editManifest(pkg(a), run -> obj(run.get("run")).members().put("run_uid", str("someone-else")));
        OcsfPackage pa = open(a);
        assertThat(pa.diagnosticsWithCode("MANIFEST_DISAGREES").get(0).detail()).contains("run_uid someone-else vs declared syn-codex-counterexamples");
        assertThat(pa.manifest.runUid()).as("the declarations win").isEqualTo("syn-codex-counterexamples");
        assertThat(pa.manifest.text("package", "derived_from")).isNotNull();

        Path b = copy(CODEX, work.resolve("version"));
        editManifest(pkg(b), run -> obj(run.get("package")).members().put("version", str("0.2.0-prototype")));
        assertThat(open(b).diagnosticsWithCode("MANIFEST_DISAGREES").get(0).detail()).contains("package version 0.2.0-prototype vs declared 0.3.0-prototype");

        Path c = copy(CODEX, work.resolve("extension"));
        editManifest(pkg(c), run -> obj(run.get("extension")).members().put("version", str("0.9.0-prototype")));
        assertThat(open(c).diagnosticsWithCode("MANIFEST_DISAGREES").get(0).detail()).contains("extension 901 0.9.0-prototype vs declared 901 0.3.0-prototype");

        Path d = copy(CODEX, work.resolve("classes"));
        editManifest(pkg(d), run -> obj(run.path("extension", "classes")).members().remove("tool_call"));
        assertThat(open(d).diagnosticsWithCode("MANIFEST_DISAGREES").get(0).detail()).contains("class table");

        Path e = copy(CODEX, work.resolve("agree"));
        OcsfPackage pe = open(e);
        assertThat(pe.diagnosticsWithCode("MANIFEST_DISAGREES")).isEmpty();
        assertThat(pe.manifest.text("package", "derived_from")).as("the ancillary manifest is used when it agrees").isNull();
        assertThat(pe.manifest.eventsSha256()).hasSize(64);
    }

    @Test
    void finalizationClaimedWithoutOrAgainstTheRecordingDeclarationIsADefect(@TempDir Path work) {
        Path missing = copy(CODEX, work.resolve("missing"));
        OcsfFixtures.editDeclarationsWithoutSync(pkg(missing), decls -> decls.remove(declaration(decls, "recording:end")));
        OcsfPackage pm = open(missing);
        assertThat(pm.diagnosticsWithCode("FINALIZATION_MISSING").get(0).detail()).contains("run.json says COMPLETED but events.jsonl carries no recording declaration");
        assertThat(pm.manifest.packageState()).as("the ancillary summary is still the state view when it agrees on identity").isEqualTo("COMPLETED");

        Path mismatch = copy(CODEX, work.resolve("mismatch"));
        OcsfFixtures.editDeclarationsWithoutSync(pkg(mismatch), decls -> obj(declaration(decls, "recording:end").member("recording")).members().put("state", str("INTERRUPTED")));
        assertThat(open(mismatch).diagnosticsWithCode("FINALIZATION_MISMATCH").get(0).detail()).contains("run.json says COMPLETED but the recording declaration says INTERRUPTED");

        Path consistent = copy(CODEX, work.resolve("consistent"));
        editManifest(pkg(consistent), run -> obj(run.get("package")).members().put("state", str("INTERRUPTED")));
        OcsfPackage pc = open(consistent);
        assertThat(pc.diagnosticsWithCode("FINALIZATION_MISMATCH")).as("the fixture helper keeps the declaration in step").isEmpty();
        assertThat(pc.diagnosticsWithCode("PACKAGE_INTERRUPTED")).hasSize(1);
    }

    @Test
    void thePersistedPrefixPinOfAnOpenPackageIsVerifiedAgainstTheFile(@TempDir Path work) {
        // first open the package (the helper drops the finalization record and re-pins the prefix), then break the pin
        Path shorter = copy(CODEX, work.resolve("short"));
        editManifest(pkg(shorter), run -> {
            obj(run.get("package")).members().put("state", str("RECORDING"));
            obj(run.get("package")).members().put("events_jsonl_sha256", Json.JNull.INSTANCE);
        });
        editManifest(pkg(shorter), run -> obj(run.path("package", "persisted_prefix")).members().put("bytes", num("99999999")));
        OcsfPackage ps = open(shorter);
        assertThat(ps.diagnosticsWithCode("PREFIX_SHORT").get(0).detail()).contains("99999999 bytes were persisted");
        assertThat(ps.diagnosticsWithCode("PACKAGE_OPEN")).hasSize(1);

        Path wrong = copy(CODEX, work.resolve("wrong"));
        editManifest(pkg(wrong), run -> {
            obj(run.get("package")).members().put("state", str("RECORDING"));
            obj(run.get("package")).members().put("events_jsonl_sha256", Json.JNull.INSTANCE);
        });
        editManifest(pkg(wrong), run -> obj(run.path("package", "persisted_prefix")).members().put("sha256", str("00")));
        assertThat(open(wrong).diagnosticsWithCode("PREFIX_HASH_MISMATCH").get(0).detail()).contains("not the persisted-prefix pin 00");

        Path fine = copy(CODEX, work.resolve("fine"));
        editManifest(pkg(fine), run -> {
            obj(run.get("package")).members().put("state", str("RECORDING"));
            obj(run.get("package")).members().put("events_jsonl_sha256", Json.JNull.INSTANCE);
        });
        OcsfPackage pf = open(fine);
        assertThat(pf.diagnosticsWithCode("PREFIX_HASH_MISMATCH")).isEmpty();
        assertThat(pf.diagnosticsWithCode("PREFIX_SHORT")).isEmpty();
        assertThat(pf.diagnostics(Diagnostic.Severity.DEFECT)).isEmpty();
    }

    @Test
    void eventCountsOfTheAncillaryManifestAreCheckedOpenAndClosed(@TempDir Path work) {
        Path closed = copy(CODEX, work.resolve("closed"));
        editManifest(pkg(closed), run -> obj(run.get("package")).members().put("events_count", num("38")));
        assertThat(open(closed).diagnosticsWithCode("EVENTS_COUNT_MISMATCH").get(0).detail()).contains("declares 38 event(s) but events.jsonl holds 39 event line(s)");

        Path openMore = copy(CODEX, work.resolve("open-more"));
        editManifest(pkg(openMore), run -> {
            obj(run.get("package")).members().put("state", str("RECORDING"));
            obj(run.get("package")).members().put("events_jsonl_sha256", Json.JNull.INSTANCE);
            obj(run.get("package")).members().put("events_count", num("40"));
        });
        assertThat(open(openMore).diagnosticsWithCode("EVENTS_COUNT_MISMATCH").get(0).detail()).contains("declares 40 persisted event(s) but events.jsonl holds only 39");

        Path openFewer = copy(CODEX, work.resolve("open-fewer"));
        editManifest(pkg(openFewer), run -> {
            obj(run.get("package")).members().put("state", str("RECORDING"));
            obj(run.get("package")).members().put("events_jsonl_sha256", Json.JNull.INSTANCE);
            obj(run.get("package")).members().put("events_count", num("30"));
        });
        assertThat(open(openFewer).diagnosticsWithCode("EVENTS_COUNT_MISMATCH")).as("an open package may have grown since its last flush").isEmpty();
    }

    @Test
    void anUnreadableAncillaryManifestIsADefectAndTheFileIsReadAlone(@TempDir Path work) throws Exception {
        Path fixture = copy(CODEX, work);
        Files.writeString(pkg(fixture).resolve("run.json"), "{not json");
        OcsfPackage p = open(fixture);
        assertThat(p.refused()).isFalse();
        assertThat(p.diagnosticsWithCode("MANIFEST_UNREADABLE").get(0).detail()).contains("ancillary run.json");
        assertThat(p.diagnosticsWithCode("ANCILLARY_ABSENT")).isEmpty();
        assertThat(p.manifest.text("package", "derived_from")).isNotNull();
        assertThat(p.events).hasSize(39);
    }

    @Test
    void malformedDuplicateUnknownAndConflictingDeclarationsAreEachReported(@TempDir Path work) {
        Path shape = copy(CODEX, work.resolve("shape"));
        editDeclarations(pkg(shape), decls -> obj(declaration(decls, "source:rollout.jsonl").member("source")).members().remove("profile"));
        OcsfPackage ps = open(shape);
        assertThat(ps.diagnosticsWithCode("DECLARATION_SHAPE").get(0).detail()).contains("source:rollout.jsonl").contains("MISSING_REQUIRED").contains("profile");
        assertThat(ps.diagnosticsWithCode("REFERENCE_BEFORE_DECLARATION").get(0).detail()).as("a malformed declaration is not absorbed, so the file never declares the source").contains("source rollout.jsonl declared at null");

        Path headerless = copy(CODEX, work.resolve("headerless"));
        editDeclarations(pkg(headerless), decls -> obj(decls.stream().filter(d -> "session".equals(SchemaCatalog.text(d.member("declaration"), "kind"))).findFirst().orElseThrow().member("declaration")).members().remove("uid"));
        assertThat(open(headerless).diagnosticsWithCode("DECLARATION_SHAPE").get(0).detail()).contains("declaration without kind or uid");

        Path duplicate = copy(CODEX, work.resolve("duplicate"));
        editDeclarations(pkg(duplicate), decls -> decls.add(decls.indexOf(declaration(decls, "source:rollout.jsonl")) + 1,
                (Json) io.github.markpollack.journal.tck.verify.NeutralJson.parse(io.github.markpollack.journal.tck.verify.NeutralJson.write(declaration(decls, "source:rollout.jsonl")))));
        assertThat(open(duplicate).diagnosticsWithCode("DECLARATION_DUPLICATE").get(0).detail()).contains("source:rollout.jsonl was already declared on line");

        Path unknown = copy(CODEX, work.resolve("unknown"));
        editDeclarations(pkg(unknown), decls -> decls.add(new Json.JObject(Map.of(
                "declaration", new Json.JObject(Map.of("kind", str("future_kind"), "uid", str("future_kind:1"), "index", num("999"))),
                "future_kind", new Json.JObject(Map.of("anything", str("kept")))))));
        OcsfPackage pu = open(unknown);
        assertThat(pu.diagnosticsWithCode("UNKNOWN_DECLARATION_KIND").get(0).detail()).contains("future_kind").contains("retained uninterpreted");
        assertThat(pu.diagnostics(Diagnostic.Severity.DEFECT)).isEmpty();
        assertThat(pu.declarations.byUid).containsKey("future_kind:1");

        Path redefined = copy(CODEX, work.resolve("redefined"));
        editDeclarations(pkg(redefined), decls -> {
            Json base = declaration(decls, "definition:agentic_cli/operation");
            Json again = io.github.markpollack.journal.tck.verify.NeutralJson.parse(io.github.markpollack.journal.tck.verify.NeutralJson.write(base));
            obj(again.member("declaration")).members().put("uid", str("definition:agentic_cli/operation-again"));
            decls.add(decls.indexOf(base) + 1, again);
        });
        assertThat(open(redefined).diagnosticsWithCode("DEFINITION_CONFLICT").get(0).detail()).contains("agentic_cli/operation is defined a second time");

        Path amendedTwice = copy(CHILD, work.resolve("amended"));
        editDeclarations(pkg(amendedTwice), decls -> {
            Json amendment = declaration(decls, "definition:metadata#2");
            Json base = declaration(decls, "definition:metadata");
            String first = obj(base.path("definition", "body", "attributes")).members().keySet().iterator().next();
            obj(amendment.path("definition", "body", "attributes")).members().put(first, base.path("definition", "body", "attributes", first));
        });
        assertThat(open(amendedTwice).diagnosticsWithCode("DEFINITION_CONFLICT").get(0).detail()).contains("is redefined by definition:metadata#2");
    }

    @Test
    void aContractDeclarationAlteredUnderItsHashAndAnAttributeUsedBeforeItsAmendmentAreDefects(@TempDir Path work) {
        Path contract = copy(CODEX, work.resolve("contract"));
        editDeclarations(pkg(contract), decls -> obj(declaration(decls, "contract:codex-cli/rollout/0.160.1/0.2.0-prototype").path("contract", "embedded")).members().put("tampered", str("yes")));
        List<Diagnostic> d = open(contract).diagnosticsWithCode("CONTRACT_HASH_MISMATCH");
        assertThat(d).as("reported once, by the declaration pass").hasSize(1);
        assertThat(d.get(0).detail()).contains("line ").contains("differs from its pin");

        Path early = copy(CHILD, work.resolve("early"));
        Path file = pkg(early).resolve("events.jsonl");
        List<Json> all = lines(file);
        Json amendment = declaration(all, "definition:metadata#2");
        all.remove(amendment);
        all.add(amendment); // after every event that uses the attribute it added
        writeLines(file, all);
        OcsfFixtures.repin(pkg(early));
        OcsfPackage pe = open(early);
        assertThat(pe.diagnosticsWithCode("REFERENCE_BEFORE_DECLARATION").get(0).detail()).contains("of metadata declared at");
        assertThat(pe.diagnosticsWithCode("AMENDMENT_BEFORE_DEFINITION")).isEmpty();
    }

    @Test
    void aLaunchVersionThatDisagreesWithTheStreamIsAWarningAndTheFileAloneDerivesAcquisition(@TempDir Path work) throws Exception {
        Path differs = copy(CHILD, work.resolve("differs"));
        editDeclarations(pkg(differs), decls -> obj(declaration(decls, "run:claude-child-2.1.299").path("run", "cli")).members().put("version", str("2.1.300")));
        OcsfPackage pd = open(differs);
        assertThat(pd.diagnosticsWithCode("CLI_VERSION_DIFFERS").get(0).detail()).contains("declared CLI version 2.1.300 at launch but the selected stream reported 2.1.299");

        Path noVersion = copy(CHILD, work.resolve("no-version"));
        editDeclarations(pkg(noVersion), decls -> obj(declaration(decls, "run:claude-child-2.1.299").path("run", "cli")).members().remove("version"));
        Path alone = work.resolve("alone");
        Files.createDirectories(alone);
        Files.copy(pkg(noVersion).resolve("events.jsonl"), alone.resolve("events.jsonl"));
        OcsfPackage pa = PackageReader.open(alone.resolve("events.jsonl"));
        assertThat(pa.diagnosticsWithCode("CLI_VERSION_DIFFERS")).isEmpty();
        assertThat(pa.manifest.cliVersion()).as("the derived manifest takes the stream-reported version").isEqualTo("2.1.299");
        assertThat(pa.manifest.text("run", "cli", "version_basis")).contains("reported by the selected source");

        // derived acquisition without a recording declaration: pending while a source is open, incomplete after an interruption
        Path pending = copy(CHILD, work.resolve("pending"));
        editDeclarations(pkg(pending), decls -> {
            decls.remove(declaration(decls, "recording:end"));
            decls.remove(declaration(decls, "source_end:stream.jsonl"));
        });
        Path pendingAlone = work.resolve("pending-alone");
        Files.createDirectories(pendingAlone);
        Files.copy(pkg(pending).resolve("events.jsonl"), pendingAlone.resolve("events.jsonl"));
        OcsfPackage pp = PackageReader.open(pendingAlone.resolve("events.jsonl"));
        assertThat(pp.manifest.packageState()).isEqualTo("RECORDING");
        assertThat(pp.manifest.text("recording", "acquisition", "status")).isEqualTo("pending");
        assertThat(pp.manifest.source("stream.jsonl").member("note").text()).contains("still open");
        assertThat(pp.diagnosticsWithCode("ANCILLARY_ABSENT").get(0).detail()).contains("open prefix");

        Path interrupted = copy(CHILD, work.resolve("interrupted"));
        editDeclarations(pkg(interrupted), decls -> {
            decls.remove(declaration(decls, "recording:end"));
            obj(declaration(decls, "source_end:stream.jsonl").member("source_end")).members().put("how", str("INTERRUPTED"));
        });
        Path interruptedAlone = work.resolve("interrupted-alone");
        Files.createDirectories(interruptedAlone);
        Files.copy(pkg(interrupted).resolve("events.jsonl"), interruptedAlone.resolve("events.jsonl"));
        OcsfPackage pi = PackageReader.open(interruptedAlone.resolve("events.jsonl"));
        assertThat(pi.manifest.text("recording", "acquisition", "status")).isEqualTo("incomplete");
        assertThat(pi.manifest.source("stream.jsonl").member("state").text()).isEqualTo("INTERRUPTED");
        assertThat(pi.manifest.text("recording", "acquisition", "meaning")).contains("unfinalized file");
    }
}
