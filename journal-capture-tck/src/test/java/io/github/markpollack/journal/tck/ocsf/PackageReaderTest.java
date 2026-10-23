package io.github.markpollack.journal.tck.ocsf;

import io.github.markpollack.journal.tck.verify.Check;
import io.github.markpollack.journal.tck.verify.Json;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static io.github.markpollack.journal.tck.ocsf.OcsfFixtures.CHILD;
import static io.github.markpollack.journal.tck.ocsf.OcsfFixtures.CODEX;
import static io.github.markpollack.journal.tck.ocsf.OcsfFixtures.HISTORICAL_0_2_0;
import static io.github.markpollack.journal.tck.ocsf.OcsfFixtures.copy;
import static io.github.markpollack.journal.tck.ocsf.OcsfFixtures.copyHistorical;
import static io.github.markpollack.journal.tck.ocsf.OcsfFixtures.declaration;
import static io.github.markpollack.journal.tck.ocsf.OcsfFixtures.editDeclarations;
import static io.github.markpollack.journal.tck.ocsf.OcsfFixtures.editEvents;
import static io.github.markpollack.journal.tck.ocsf.OcsfFixtures.editManifest;
import static io.github.markpollack.journal.tck.ocsf.OcsfFixtures.num;
import static io.github.markpollack.journal.tck.ocsf.OcsfFixtures.obj;
import static io.github.markpollack.journal.tck.ocsf.OcsfFixtures.open;
import static io.github.markpollack.journal.tck.ocsf.OcsfFixtures.pkg;
import static io.github.markpollack.journal.tck.ocsf.OcsfFixtures.str;
import static io.github.markpollack.journal.tck.ocsf.OcsfFixtures.tamperEvents;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The reader's discovery and refusal behaviour: a package is found from its events file by convention, every pin and
 * hash is verified, and each failure is an explicit diagnostic that never discards data.
 */
class PackageReaderTest {

    @Test
    void discoversTheManifestFromEventsJsonlAndFromTheDirectory(@TempDir Path work) {
        Path fixture = copy(CODEX, work);
        OcsfPackage fromEvents = PackageReader.open(pkg(fixture).resolve("events.jsonl"));
        OcsfPackage fromDir = PackageReader.open(pkg(fixture));
        assertThat(fromEvents.refused()).isFalse();
        assertThat(fromEvents.manifest.runUid()).isEqualTo("syn-codex-counterexamples");
        assertThat(fromEvents.events).hasSize(39);
        assertThat(fromDir.events).hasSize(39);
        assertThat(fromEvents.diagnostics(Diagnostic.Severity.DEFECT)).isEmpty();
        assertThat(fromEvents.manifest.packageVersion()).isEqualTo("0.3.0-prototype");
        assertThat(fromEvents.standalone()).as("read from the file's declarations; run.json is ancillary").isTrue();
        assertThat(fromEvents.manifest.packageState()).isEqualTo("COMPLETED");
        assertThat(fromEvents.manifest.extensionUid()).isEqualTo(901);
        assertThat(fromEvents.manifest.extensionVersion()).isEqualTo("0.9.0-prototype");
        assertThat(fromEvents.manifest.contracts()).containsKey("codex-cli/rollout/0.160.1/0.4.0-prototype");
    }

    @Test
    void anEventsFileAloneIsReadFromItsDeclarationsWhileAHistoricalDetachedFileIsRefused(@TempDir Path work) throws Exception {
        Path fixture = copy(CODEX, work);
        Path alone = work.resolve("alone");
        Files.createDirectories(alone);
        Files.copy(pkg(fixture).resolve("events.jsonl"), alone.resolve("events.jsonl"));
        OcsfPackage p = PackageReader.open(alone.resolve("events.jsonl"));
        assertThat(p.refused()).as(p.diagnostics.toString()).isFalse();
        assertThat(p.diagnosticsWithCode("ANCILLARY_ABSENT")).hasSize(1);
        assertThat(p.diagnostics(Diagnostic.Severity.DEFECT)).isEmpty();
        assertThat(p.events).hasSize(39);
        assertThat(p.manifest.runUid()).isEqualTo("syn-codex-counterexamples");
        assertThat(p.manifest.packageState()).isEqualTo("COMPLETED");

        Path historical = copyHistorical(HISTORICAL_0_2_0, CODEX, work.resolve("hist"));
        Path detached = work.resolve("detached");
        Files.createDirectories(detached);
        Files.copy(pkg(historical).resolve("events.jsonl"), detached.resolve("events.jsonl"));
        OcsfPackage d = PackageReader.open(detached.resolve("events.jsonl"));
        assertThat(d.refused()).isTrue();
        assertThat(d.diagnosticsWithCode("NO_MANIFEST")).hasSize(1);
        assertThat(d.rawLines).hasSize(39);
        assertThat(d.events).isEmpty();
    }

    /**
     * A package written before cli_agent stopped extending ai_agent (extension 0.4.0) reads from its own embedded
     * definitions. Judged by today's Codex profile (the SEM-1 candidate reading of contract 0.3.0), only the changed
     * meanings differ: its usage observation states the superseded turn scope, and its operations carry no command
     * string or identity basis. Nothing is missing and nothing else is defective.
     */
    @Test
    void aPackageWrittenWithExtension030StillReadsFromItsOwnDefinitions(@TempDir Path work) {
        Path historical = copyHistorical("extension-0.3.0-prototype", OcsfFixtures.LIVE_CODEX, work);
        OcsfPackage p = open(historical);
        assertThat(p.refused()).isFalse();
        assertThat(p.manifest.extensionVersion()).isEqualTo("0.3.0-prototype");
        assertThat(p.diagnostics(Diagnostic.Severity.DEFECT)).isEmpty();
        OracleReport report = OcsfFixtures.verify(historical);
        // under the 0.6.0 reading this old package also presents its stream-local agent-message ids as response identities
        assertThat(report.defects()).extracting(Check::property).containsOnly("population.usage", "operation.values", "population.responses");
        assertThat(report.missingRequired()).isEmpty();
        Path current = copy(OcsfFixtures.LIVE_CODEX, work.resolve("current"));
        assertThat(open(current).manifest.extensionVersion()).isEqualTo("0.9.0-prototype");
    }

    /**
     * The 0.4.0 package of the live MCP recording, written before the SEM-1 candidate: its rule ids are in OCSF
     * unmapped and its MCP items are retained whole. It reads from its own definitions; today's profile, which reads
     * the MCP items as tool calls and the usage at session scope, finds exactly those meanings changed.
     */
    @Test
    void aPackageWrittenWithExtension040StillReadsAndShowsOnlyTheChangedCodexMeanings(@TempDir Path work) {
        Path historical = copyHistorical("extension-0.4.0-prototype", OcsfFixtures.LIVE_CODEX_MCP, work);
        OcsfPackage p = open(historical);
        assertThat(p.refused()).isFalse();
        assertThat(p.manifest.extensionVersion()).isEqualTo("0.4.0-prototype");
        assertThat(p.diagnostics(Diagnostic.Severity.DEFECT)).isEmpty();
        OracleReport report = OcsfFixtures.verify(historical);
        // population.responses: under the 0.6.0 reading its stream-local agent-message ids are not response identities
        assertThat(report.defects()).extracting(Check::property).containsOnly("population.usage", "population.requests", "population.results", "whole_record.class", "population.responses");
        assertThat(report.missingRequired()).isEmpty();
    }

    /**
     * The 0.5.0 package of the live MCP recording, written before the delegated semantic closure: it reads from its own
     * embedded definitions without defect, and the current oracle names exactly the revised meanings it predates
     * (fixtures-historical/extension-0.5.0-prototype/README.md): a stream-local item presented as a response identity,
     * the started MCP invocations recorded as the model's request without their family, and usage without the
     * canonical tranches (its vendor-dependent input_tokens).
     */
    @Test
    void aPackageWrittenWithExtension050StillReadsAndShowsOnlyTheRevisedMeanings(@TempDir Path work) {
        Path historical = copyHistorical("extension-0.5.0-prototype", OcsfFixtures.LIVE_CODEX_MCP, work);
        OcsfPackage p = open(historical);
        assertThat(p.refused()).isFalse();
        assertThat(p.manifest.extensionVersion()).isEqualTo("0.5.0-prototype");
        assertThat(p.diagnostics(Diagnostic.Severity.DEFECT)).isEmpty();
        OracleReport report = OcsfFixtures.verify(historical);
        assertThat(report.defects()).extracting(Check::property).containsOnly("population.responses", "request.values", "result.values", "usage.values");
        assertThat(report.check("request.values", "main/stream.jsonl").detail()).contains("activity_id 1 != 4");
        assertThat(report.check("usage.values", "main/stream.jsonl").detail()).contains("measurement_status_id 1 != 4").contains("components[] has no entry for uncached_input_tokens");
        assertThat(report.missingRequired()).isEmpty();
    }

    /**
     * A package written before compatibility selection preceded normalization (extension 0.8.0-prototype): its
     * version_unknown declaration says that pending records were interpreted provisionally, and record 1 (the init record
     * that reported a version no entry covers) is a normalized session_context. The reader still reads it from its own
     * declarations, with the no-match warning; the independent oracle judges it by the current policy and reports the
     * provisional interpretation as a defect of the compatibility selection, not as accepted history.
     */
    @Test
    void aPackageWrittenWithProvisionalPendingInterpretationStillReadsAndIsJudgedByTheCurrentPolicy(@TempDir Path work) {
        Path historical = copyHistorical("extension-0.8.0-prototype", "syn-claude-untested-version", work);
        OcsfPackage p = open(historical);
        assertThat(p.refused()).isFalse();
        assertThat(p.manifest.extensionVersion()).isEqualTo("0.8.0-prototype");
        assertThat(p.diagnostics(Diagnostic.Severity.DEFECT)).isEmpty();
        assertThat(p.diagnosticsWithCode("COMPATIBILITY_NO_MATCH")).hasSize(1);
        List<Json> history = p.declarations.compatibility("stream.jsonl");
        assertThat(history.get(0).member("normalizes").text()).startsWith("provisionally");
        assertThat(p.events).anyMatch(e -> Integer.valueOf(1).equals(e.recordIndex()) && "session_context".equals(e.className));
        OracleReport report = OcsfFixtures.verify(historical);
        assertThat(report.outcome("source.compatibility", "main/stream.jsonl")).isEqualTo(Check.Outcome.DEFECTIVE);
        assertThat(report.check("source.compatibility", "main/stream.jsonl").detail()).contains("1 record(s)").contains("were normalized");
    }

    @Test
    void theSavedModelIsEnumeratedFromTheEmbeddedClosureAlone(@TempDir Path work) {
        OcsfPackage p = open(copy(CODEX, work));
        String model = p.catalog.enumerate();
        assertThat(model).contains("class agentic_cli/tool_call uid 90111003").contains("object agentic_cli/usage_observation")
                .contains("object agentic_cli/operation").contains("object metadata").contains("long_t");
        assertThat(p.catalog.resolve("tool_call.tool_operation.attribution.candidates[]").isArray()).isTrue();
        assertThat(p.catalog.resolve("*.usage_observations[].usage_status_id").enumCaptions()).containsEntry("2", "Final");
        assertThat(p.manifest.json().path("definitions", "extension_source", "dictionary.json")).isNotNull();
    }

    @Test
    void wrongClassPinIsRefusedWithoutRemapping(@TempDir Path work) {
        Path fixture = copy(CODEX, work);
        editDeclarations(pkg(fixture), decls -> obj(declaration(decls, "schema").path("schema", "extension", "classes", "tool_call")).members().put("class_uid", num("90211003")));
        OcsfPackage p = open(fixture);
        assertThat(p.refused()).isTrue();
        assertThat(p.diagnosticsWithCode("PIN_MISMATCH").get(0).detail()).contains("tool_call uid 90211003 != definition uid 90111003");
        assertThat(p.diagnosticsWithCode("MANIFEST_DISAGREES")).as("the ancillary run.json still carries the original table").hasSize(1);
        // the events are still parsed and available; tool_call events are now outside the pin table
        assertThat(p.events).hasSize(39);
        assertThat(p.diagnosticsWithCode("UNKNOWN_CLASS")).hasSize(1);
    }

    @Test
    void unsupportedReaderContractMajorIsRefusedMinorIsAWarning(@TempDir Path work) {
        Path fixture = copy(CODEX, work);
        editDeclarations(pkg(fixture), decls -> obj(declaration(decls, "format").member("format")).members().put("reader_contract_version", str("2.0")));
        OcsfPackage major = open(fixture);
        assertThat(major.refused()).isTrue();
        assertThat(major.diagnosticsWithCode("UNSUPPORTED_READER_CONTRACT_VERSION").get(0).detail()).contains("2.0").contains("0.3");
        assertThat(major.events).hasSize(39);
        editDeclarations(pkg(fixture), decls -> obj(declaration(decls, "format").member("format")).members().put("reader_contract_version", str("0.2")));
        OcsfPackage minor = open(fixture);
        assertThat(minor.refused()).isFalse();
        assertThat(minor.diagnosticsWithCode("READER_CONTRACT_MINOR_DIFFERS")).hasSize(1);
    }

    @Test
    void unsupportedPackageVersionIsRefused(@TempDir Path work) {
        Path fixture = copy(CODEX, work);
        editDeclarations(pkg(fixture), decls -> obj(declaration(decls, "format").member("format")).members().put("package_version", str("9.0.0")));
        OcsfPackage p = open(fixture);
        assertThat(p.refused()).isTrue();
        assertThat(p.diagnosticsWithCode("UNSUPPORTED_PACKAGE_VERSION")).hasSize(1);
    }

    @Test
    void tamperedEventsAreReportedAgainstTheirHashAndStillRead(@TempDir Path work) {
        Path fixture = copy(CODEX, work);
        tamperEvents(pkg(fixture), events -> obj(events.get(0)).members().put("severity_id", num("1")));
        OcsfPackage p = open(fixture);
        assertThat(p.refused()).isFalse();
        assertThat(p.diagnosticsWithCode("EVENTS_HASH_MISMATCH")).hasSize(1);
        assertThat(p.events).hasSize(39);
    }

    @Test
    void alteredDefinitionsOrContractsAreReportedAgainstTheirHashes(@TempDir Path work) {
        Path fixture = copy(CODEX, work);
        editDeclarations(pkg(fixture), decls -> {
            obj(declaration(decls, "definition:type/long_t").path("definition", "body")).members().put("caption", str("Synthetic"));
            obj(declaration(decls, "contract:codex-cli/rollout/0.160.1/0.4.0-prototype").path("contract", "embedded")).members().put("tampered", str("yes"));
        });
        editManifest(pkg(fixture), run -> obj(run.path("semantic_contract", "declarations").elements().get(0)).members().put("tampered", str("yes")));
        OcsfPackage p = open(fixture);
        assertThat(p.diagnosticsWithCode("DEFINITIONS_HASH_MISMATCH")).hasSize(1);
        assertThat(p.diagnosticsWithCode("CONTRACT_HASH_MISMATCH")).hasSize(1);
        assertThat(p.diagnosticsWithCode("SEMANTIC_CONTRACT_HASH_MISMATCH")).hasSize(1);
        assertThat(p.refused()).isFalse();
    }

    @Test
    void missingDefinitionsRefuseInterpretationButKeepTheEvents(@TempDir Path work) {
        Path fixture = copy(CODEX, work);
        editDeclarations(pkg(fixture), decls -> decls.removeIf(d -> "definition".equals(SchemaCatalog.text(d.member("declaration"), "kind"))));
        OcsfPackage p = open(fixture);
        assertThat(p.refused()).isTrue();
        assertThat(p.diagnosticsWithCode("MISSING_DEFINITIONS")).hasSize(1);
        assertThat(p.catalog).isNull();
        assertThat(p.events).hasSize(39);
    }

    @Test
    void missingDefinitionReferenceIsADefect(@TempDir Path work) {
        Path fixture = copy(CODEX, work);
        editDeclarations(pkg(fixture), decls -> decls.remove(declaration(decls, "definition:agentic_cli/operation")));
        OcsfPackage p = open(fixture);
        assertThat(p.diagnosticsWithCode("MISSING_DEFINITION_REFERENCE").get(0).detail()).contains("tool_operation refers to object agentic_cli/operation");
        assertThat(p.diagnosticsWithCode("SEMANTIC_TARGET_UNRESOLVED")).isNotEmpty();
    }

    @Test
    void unknownClassAndUnknownAttributeAreRetainedAndReported(@TempDir Path work) {
        Path fixture = copy(CODEX, work);
        editEvents(pkg(fixture), events -> {
            obj(events.get(0)).members().put("class_uid", num("90111099"));
            obj(events.get(1)).members().put("future_attribute", str("kept"));
        });
        OcsfPackage p = open(fixture);
        assertThat(p.refused()).isFalse();
        assertThat(p.events).hasSize(39);
        assertThat(p.events.get(0).className).isNull();
        assertThat(p.diagnosticsWithCode("UNKNOWN_CLASS").get(0).detail()).contains("90111099=1");
        assertThat(p.diagnosticsWithCode("UNKNOWN_ATTRIBUTE").get(0).detail()).contains("future_attribute");
        assertThat(p.events.get(1).text("future_attribute")).isEqualTo("kept");
    }

    @Test
    void typeMismatchAndMissingRequiredAreDefects(@TempDir Path work) {
        Path fixture = copy(CODEX, work);
        editEvents(pkg(fixture), events -> {
            obj(events.get(0)).members().put("time", str("2026-10-20T12:00:00Z"));
            obj(events.get(1)).members().remove("source_ref");
            obj(events.get(2)).members().put("severity_id", num("42"));
        });
        OcsfPackage p = open(fixture);
        assertThat(p.diagnosticsWithCode("TYPE_MISMATCH").get(0).detail()).contains("$.time");
        assertThat(p.diagnosticsWithCode("MISSING_REQUIRED").get(0).detail()).contains("$.source_ref");
        assertThat(p.diagnosticsWithCode("ENUM_VALUE_UNKNOWN").get(0).detail()).contains("$.severity_id");
    }

    @Test
    void unresolvedSharedReferencesAreDefects(@TempDir Path work) {
        Path fixture = copy(CODEX, work);
        editEvents(pkg(fixture), events -> {
            obj(events.get(0).member("journal")).members().put("contract_uid", str("codex-cli/rollout/9.9.9/none"));
            obj(events.get(1).member("source_ref")).members().put("artifact", str("elsewhere.jsonl"));
        });
        OcsfPackage p = open(fixture);
        assertThat(p.diagnosticsWithCode("CONTRACT_REF_UNRESOLVED").get(0).detail()).contains("codex-cli/rollout/9.9.9/none");
        assertThat(p.diagnosticsWithCode("SOURCE_REF_UNRESOLVED").get(0).detail()).contains("elsewhere.jsonl");
    }

    @Test
    void semanticDeclarationWithAWrongTargetOrOperationIsADefect(@TempDir Path work) {
        Path fixture = copy(CODEX, work);
        editManifest(pkg(fixture), run -> {
            List<Json> decls = run.path("semantic_contract", "declarations").elements();
            obj(decls.get(0).member("targets")).members().put("turn", str("prompt_turn.turn_identifier"));
            obj(decls.get(1)).members().put("operation", str("MAGIC_INFERENCE"));
            obj(run.get("semantic_contract")).members().remove("sha256");
        });
        OcsfPackage p = open(fixture);
        List<Diagnostic> d = p.diagnosticsWithCode("SEMANTIC_TARGET_UNRESOLVED");
        assertThat(d).extracting(Diagnostic::detail).anyMatch(s -> s.contains("unknown attribute turn_identifier"))
                .anyMatch(s -> s.contains("MAGIC_INFERENCE is not implemented"));
    }

    @Test
    void childCoresAreLinkedAndJudgedSeparately(@TempDir Path work) {
        Path fixture = copy(CHILD, work);
        OcsfPackage p = open(fixture);
        assertThat(p.children).hasSize(1);
        OcsfPackage child = p.children.get(0);
        assertThat(child.label()).isEqualTo("child:" + OcsfFixtures.SPAWN);
        assertThat(child.manifest.parent().member("run_uid").text()).isEqualTo("claude-child-2.1.299");
        assertThat(child.events).hasSize(4);
        assertThat(p.events).hasSize(26);
        assertThat(child.manifest.sourceScope().member("owner_value").text()).isEqualTo(OcsfFixtures.SPAWN);
        assertThat(p.manifest.sourceScope().member("owner_value").isNull()).isTrue();
        // applicability comes from the compatibility declaration: 2.1.299 on route sdk is a tested version of the selected entry, so no
        // CONTRACT_APPLICABILITY warning is raised even though the contract document names 2.1.303 as its observed baseline
        assertThat(p.diagnosticsWithCode("CONTRACT_APPLICABILITY")).isEmpty();
        assertThat(p.diagnosticsWithCode("COMPATIBILITY_NO_MATCH")).isEmpty();
        assertThat(p.manifest.compatibility("stream.jsonl").member("status").text()).isEqualTo("tested");
        assertThat(p.manifest.compatibility("stream.jsonl").member("entry_id").text()).isEqualTo("claude-code/stream-json/2.1.299-sdk");
        assertThat(child.manifest.compatibility("stream.jsonl").member("status").text()).as("the child file declares the selection too").isEqualTo("tested");
        // each core carries its own complete closure
        assertThat(child.catalog.classes()).containsKeys("agentic_cli/tool_call", "agentic_cli/model_response");
    }

    @Test
    void missingOrMislinkedChildIsADefect(@TempDir Path work) throws Exception {
        Path fixture = copy(CHILD, work);
        editManifest(OcsfFixtures.child(fixture), run -> obj(run.path("run", "parent")).members().put("run_uid", str("someone-else")));
        assertThat(open(fixture).children.get(0).diagnosticsWithCode("CHILD_PARENT_MISMATCH")).as("the child core reports its own mislink").hasSize(1);
        Files.delete(OcsfFixtures.child(fixture).resolve("run.json"));
        assertThat(open(fixture).children).as("a child without its ancillary run.json is still read from its own file").hasSize(1);
        Files.delete(OcsfFixtures.child(fixture).resolve("events.jsonl"));
        OcsfPackage p = open(fixture);
        assertThat(p.diagnosticsWithCode("CHILD_MISSING")).hasSize(1);
        assertThat(p.children).isEmpty();
    }

    @Test
    void selfContainedExportJoinsTheSharedFactsBackOntoAnEvent(@TempDir Path work) {
        OcsfPackage p = open(copy(CODEX, work));
        Event e = p.events.get(0);
        assertThat(e.get("cli_agent")).isNull();
        assertThat(e.get("journal", "producer")).isNull();
        Json full = PackageReader.selfContained(p, e);
        assertThat(full.path("cli_agent", "name").text()).isEqualTo("Codex CLI");
        assertThat(full.path("journal", "producer", "name").text()).isEqualTo("agent-journal");
        assertThat(full.path("journal", "contract_sha256")).isNotNull();
        assertThat(full.path("source_ref", "artifact_sha256").text()).hasSize(64);
        assertThat(e.get("cli_agent")).as("the saved event is not modified").isNull();
    }
}
