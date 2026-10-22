package io.github.markpollack.journal.tck.ocsf;

import io.github.markpollack.journal.tck.verify.Check;
import io.github.markpollack.journal.tck.verify.Json;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static io.github.markpollack.journal.tck.ocsf.OcsfFixtures.CHILD;
import static io.github.markpollack.journal.tck.ocsf.OcsfFixtures.CODEX;
import static io.github.markpollack.journal.tck.ocsf.OcsfFixtures.HISTORICAL_0_2_0;
import static io.github.markpollack.journal.tck.ocsf.OcsfFixtures.SPAWN;
import static io.github.markpollack.journal.tck.ocsf.OcsfFixtures.copy;
import static io.github.markpollack.journal.tck.ocsf.OcsfFixtures.copyHistorical;
import static io.github.markpollack.journal.tck.ocsf.OcsfFixtures.declaration;
import static io.github.markpollack.journal.tck.ocsf.OcsfFixtures.declarationLines;
import static io.github.markpollack.journal.tck.ocsf.OcsfFixtures.editDeclarations;
import static io.github.markpollack.journal.tck.ocsf.OcsfFixtures.eventLines;
import static io.github.markpollack.journal.tck.ocsf.OcsfFixtures.lines;
import static io.github.markpollack.journal.tck.ocsf.OcsfFixtures.natives;
import static io.github.markpollack.journal.tck.ocsf.OcsfFixtures.obj;
import static io.github.markpollack.journal.tck.ocsf.OcsfFixtures.pkg;
import static io.github.markpollack.journal.tck.ocsf.OcsfFixtures.str;
import static io.github.markpollack.journal.tck.ocsf.OcsfFixtures.writeLines;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The event-file portability acceptance: {@code events.jsonl} copied alone into a fresh directory, with no
 * {@code run.json}, no sibling files, no schema cache and no external resolution (the reader has none), is read
 * from its own declarations: the actual schema is discovered and enumerated (fields, types, enumerations), the
 * essential references (contracts, sources, session, parent and child identities) resolve, and the recorded
 * execution facts and vendor data are read. Removing or altering a mandatory declaration or reference target fails
 * interpretation explicitly. The same holds for a child event file alone. The historical two-file layout stays
 * readable under its own version.
 *
 * <p>Interpretation of one file is distinct from acquisition completeness of the run: the oracle judges the latter
 * against frozen native artifacts, which this test supplies only where it says so.
 */
class StandaloneEventFileTest {

    @Test
    void theParentEventFileAloneIsInterpretedFromItsDeclarations(@TempDir Path work) throws IOException {
        Path fixture = copy(CHILD, work.resolve("src"));
        Path alone = alone(pkg(fixture), work.resolve("alone"));
        OcsfPackage p = PackageReader.open(alone.resolve("events.jsonl"));

        assertThat(p.standalone()).isTrue();
        assertThat(p.refused()).as(p.diagnostics.toString()).isFalse();
        assertThat(p.diagnostics(Diagnostic.Severity.DEFECT)).isEmpty();
        assertThat(p.diagnosticsWithCode("ANCILLARY_ABSENT")).hasSize(1);
        assertThat(p.diagnosticsWithCode("CHILD_EXTERNAL")).as("the child is identified, not contained").hasSize(1);
        assertThat(p.children).isEmpty();
        assertThat(p.manifest.text("package", "derived_from")).contains("declarations");
        assertThat(p.manifest.packageVersion()).isEqualTo("0.3.0-prototype");
        assertThat(p.manifest.packageState()).as("the recording declaration finalizes the file").isEqualTo("COMPLETED");
        assertThat(p.manifest.runUid()).isEqualTo("claude-child-2.1.299");
        assertThat(p.manifest.sessionUid()).isEqualTo("00000000-0000-4000-8000-000000000000");
        assertThat(p.manifest.eventsSha256()).as("no whole-file hash lives inside the file").isNull();
        assertThat(p.events).hasSize(26);

        // the actual schema: classes, objects, attributes, types and enumerations, from the file alone
        String model = p.catalog.enumerate();
        assertThat(model).contains("class agentic_cli/tool_call uid 90111003").contains("class agentic_cli/model_response uid 90111002")
                .contains("object agentic_cli/tool_result").contains("object agentic_cli/usage_observation").contains("object metadata").contains("long_t");
        assertThat(p.catalog.resolve("tool_call.tool_result.data").type()).isEqualTo("json_t");
        assertThat(p.catalog.resolve("*.usage_observations[].usage_status_id").enumCaptions()).containsEntry("2", "Final");
        assertThat(p.catalog.resolve("tool_call.tool_kind_id").enumCaptions()).isNotEmpty();
        assertThat(p.catalog.types()).containsKeys("string_t", "integer_t", "json_t");
        assertThat(p.declarations.lineOf("definition:agentic_cli/tool_call")).isNotNull();
        assertThat(p.declarations.lineOf("definition:agentic_cli/tool_call#2")).as("a later core attribute was added by an amendment, not a rewrite").isNotNull();

        // essential references resolve inside the file
        assertThat(p.manifest.contracts()).containsKey("claude-code/stream-json/2.1.303/0.3.0-prototype");
        assertThat(p.manifest.contract("claude-code/stream-json/2.1.303/0.3.0-prototype").path("embedded", "rules")).isNotNull();
        Json source = p.manifest.source("stream.jsonl");
        assertThat(source.member("profile").text()).isEqualTo("claude_stream");
        assertThat(source.member("sha256").text()).as("the source end declares the final extent").hasSize(64);
        assertThat(source.member("records").scalarText()).isEqualTo("27");
        assertThat(RunManifest.sourceFinal(source)).isTrue();
        assertThat(p.manifest.children()).hasSize(1);
        assertThat(SchemaCatalog.text(p.manifest.children().get(0), "spawn_tool_call_uid")).isEqualTo(SPAWN);
        assertThat(p.manifest.sourceScope().member("owner_value").isNull()).isTrue();
        assertThat(p.manifest.text("recording", "acquisition", "status")).isEqualTo("complete");

        // representative execution facts and vendor data
        List<Event> requests = p.events("tool_call", 1);
        assertThat(requests).hasSize(2);
        assertThat(requests.get(0).text("tool_name")).isNotBlank();
        assertThat(requests.get(0).text("tool_call_uid")).startsWith("toolu_");
        assertThat(requests.get(0).get("arguments")).as("the structured arguments travel as a json value").isNotNull();
        assertThat(requests.get(0).text("tool_name")).isEqualTo("ToolSearch");
        List<Event> results = p.events("tool_call", 2);
        assertThat(results).isNotEmpty();
        assertThat(results.get(0).get("tool_result", "data")).isNotNull();
        Event settings = p.events("session_context", 1).get(0);
        assertThat(settings.text("settings", "model")).isEqualTo("claude-haiku-4-5-20251001");
        assertThat(settings.text("settings", "version")).isEqualTo("2.1.299");
        List<Event> responses = p.events("model_response");
        assertThat(responses).isNotEmpty();
        assertThat(responses.get(0).elements("usage_observations")).isNotEmpty();
        assertThat(responses.get(0).integer("usage_observations")).isNull();
        assertThat(responses.get(0).elements("usage_observations").get(0).member("uncached_input_tokens")).isNotNull();
        Event retained = p.events.stream().filter(e -> e.get("data") != null).findFirst().orElseThrow();
        assertThat(retained.get("data", "type")).as("the vendor record is retained whole beside the portable fields").isNotNull();

        // the documented record rule the upstream validator step uses: every line without a declaration is an execution event
        assertThat(eventLines(alone.resolve("events.jsonl"))).hasSize(p.events.size());
        assertThat(declarationLines(alone.resolve("events.jsonl"))).hasSize(p.declarations.declarations.size());
    }

    @Test
    void theChildEventFileAloneCarriesItsOwnDeclarationsAndIsJudgedOnItsOwn(@TempDir Path work) throws IOException {
        Path fixture = copy(CHILD, work.resolve("src"));
        Path alone = alone(OcsfFixtures.child(fixture), work.resolve("child-alone"));
        OcsfPackage c = PackageReader.open(alone.resolve("events.jsonl"));

        assertThat(c.standalone()).isTrue();
        assertThat(c.refused()).as(c.diagnostics.toString()).isFalse();
        assertThat(c.diagnostics(Diagnostic.Severity.DEFECT)).isEmpty();
        assertThat(c.label()).isEqualTo("child:" + SPAWN);
        assertThat(c.manifest.runUid()).isEqualTo("claude-child-2.1.299/child/" + SPAWN);
        assertThat(c.manifest.parent().member("run_uid").text()).as("the parent identity travels with the child").isEqualTo("claude-child-2.1.299");
        assertThat(c.manifest.sourceScope().member("owner_value").text()).isEqualTo(SPAWN);
        assertThat(c.events).hasSize(4);
        assertThat(c.catalog.classes()).containsKeys("agentic_cli/tool_call", "agentic_cli/model_response");
        assertThat(c.catalog.classes()).as("a class no event of this file uses is not declared here").doesNotContainKey("agentic_cli/session_context");
        assertThat(c.manifest.contracts()).containsKey("claude-code/stream-json/2.1.303/0.3.0-prototype");
        assertThat(c.manifest.source("stream.jsonl").member("sha256").text()).hasSize(64);

        // judged on its own against the frozen native artifact (the parent's obligations are the parent's)
        OracleReport report = OcsfOracle.verify(c, natives(fixture));
        assertThat(report.defects()).as(report.toString()).isEmpty();
        assertThat(report.outcome("package.readable", "child:" + SPAWN)).isEqualTo(Check.Outcome.VERIFIED);
        assertThat(report.outcome("whole_record.coverage", "child:" + SPAWN + "/stream.jsonl")).isEqualTo(Check.Outcome.VERIFIED);
        assertThat(report.outcome("request.values", "child:" + SPAWN + "/stream.jsonl")).isEqualTo(Check.Outcome.VERIFIED);
    }

    @Test
    void theWholeRunAloneIsAcceptedByTheOracleAndTheToolWithoutAnyManifest(@TempDir Path work) throws Exception {
        Path fixture = copy(CODEX, work.resolve("src"));
        Path alone = alone(pkg(fixture), work.resolve("alone"));
        OcsfPackage p = PackageReader.open(alone.resolve("events.jsonl"));
        assertThat(p.diagnostics(Diagnostic.Severity.DEFECT)).isEmpty();
        OracleReport report = OcsfOracle.verify(p, natives(fixture));
        assertThat(report.defects()).as(report.toString()).isEmpty();
        assertThat(report.missingRequired()).isEmpty();
        assertThat(report.outcome("package.state", "main")).isEqualTo(Check.Outcome.VERIFIED);
        assertThat(report.outcome("acquisition.complete", "main")).isEqualTo(Check.Outcome.VERIFIED);
        assertThat(report.outcome("source.extent", "main/rollout.jsonl")).isEqualTo(Check.Outcome.VERIFIED);
        assertThat(OcsfReaderTool.acceptance(report).exit()).isEqualTo(OcsfReaderTool.EXIT_OK);
        Json measure = PackageMeasure.measure(p);
        assertThat(measure.member("declaration_lines")).isNotNull();
        assertThat(Integer.parseInt(measure.member("declaration_bytes").scalarText())).isPositive();
        assertThat(Integer.parseInt(measure.member("run_json_bytes").scalarText())).isZero();
    }

    @Test
    void removingOrAlteringAMandatoryDeclarationFailsInterpretationExplicitly(@TempDir Path work) throws IOException {
        // (a) a definition every event uses is removed: the closure reference, each event's shape and the order rule all fail
        Path a = copy(CODEX, work.resolve("a"));
        editDeclarations(pkg(a), decls -> decls.remove(declaration(decls, "definition:agentic_cli/source_ref")));
        OcsfPackage pa = open(alone(pkg(a), work.resolve("a-alone")));
        assertThat(pa.diagnosticsWithCode("MISSING_DEFINITION_REFERENCE").get(0).detail()).contains("source_ref refers to object agentic_cli/source_ref");
        assertThat(pa.diagnosticsWithCode("MISSING_DEFINITION").get(0).detail()).as("every event's source_ref has no definition to check against").contains("occurrence(s)");
        assertThat(pa.events).as("the events are kept, never discarded").hasSize(39);

        // (b) a definition body altered under its hash
        Path b = copy(CODEX, work.resolve("b"));
        editDeclarations(pkg(b), decls -> obj(declaration(decls, "definition:agentic_cli/tool_result").path("definition", "body", "attributes")).members()
                .put("synthetic_attribute", new Json.JObject(Map.of("type", str("string_t"), "requirement", str("optional")))));
        OcsfPackage pb = open(alone(pkg(b), work.resolve("b-alone")));
        assertThat(pb.diagnosticsWithCode("DEFINITIONS_HASH_MISMATCH")).hasSize(1);

        // (c) the contract declaration removed: every event's contract reference is unresolved
        Path c = copy(CODEX, work.resolve("c"));
        editDeclarations(pkg(c), decls -> decls.removeIf(d -> SchemaCatalog.text(d.member("declaration"), "kind").equals("contract")));
        OcsfPackage pc = open(alone(pkg(c), work.resolve("c-alone")));
        assertThat(pc.diagnosticsWithCode("CONTRACT_REF_UNRESOLVED").get(0).detail()).contains("codex-cli/rollout/0.160.1/0.3.0-prototype");
        assertThat(pc.diagnosticsWithCode("REFERENCE_BEFORE_DECLARATION")).isNotEmpty();

        // (d) the source declaration removed: every event's artifact reference is unresolved
        Path d = copy(CODEX, work.resolve("d"));
        editDeclarations(pkg(d), decls -> decls.remove(declaration(decls, "source:rollout.jsonl")));
        OcsfPackage pd = open(alone(pkg(d), work.resolve("d-alone")));
        assertThat(pd.diagnosticsWithCode("SOURCE_REF_UNRESOLVED").get(0).detail()).contains("rollout.jsonl");

        // (e) a definition moved after its first use: the order rule is checked per line, not per file
        Path e = copy(CODEX, work.resolve("e"));
        Path eFile = pkg(e).resolve("events.jsonl");
        List<Json> all = lines(eFile);
        Json moved = declaration(all, "definition:agentic_cli/source_ref");
        all.remove(moved);
        all.add(moved);
        writeLines(eFile, all);
        OcsfFixtures.repin(pkg(e));
        OcsfPackage pe = open(alone(pkg(e), work.resolve("e-alone")));
        assertThat(pe.diagnosticsWithCode("REFERENCE_BEFORE_DECLARATION").get(0).detail()).contains("source_ref");

        // (f) every definition removed: refused, events kept
        Path f = copy(CODEX, work.resolve("f"));
        editDeclarations(pkg(f), decls -> decls.removeIf(x -> SchemaCatalog.text(x.member("declaration"), "kind").equals("definition")));
        OcsfPackage pf = open(alone(pkg(f), work.resolve("f-alone")));
        assertThat(pf.refused()).isTrue();
        assertThat(pf.diagnosticsWithCode("MISSING_DEFINITIONS")).hasSize(1);
        assertThat(pf.catalog).isNull();
        assertThat(pf.events).hasSize(39);

        // (g) the schema declaration removed: nothing resolves class ids; refused
        Path g = copy(CODEX, work.resolve("g"));
        editDeclarations(pkg(g), decls -> decls.remove(declaration(decls, "schema")));
        OcsfPackage pg = open(alone(pkg(g), work.resolve("g-alone")));
        assertThat(pg.refused()).isTrue();
        assertThat(pg.diagnosticsWithCode("SCHEMA_UNDECLARED")).hasSize(1);

        // (h) the format line removed: without run.json the file is a detached file of no layout; refused
        Path h = copy(CODEX, work.resolve("h"));
        editDeclarations(pkg(h), decls -> decls.remove(declaration(decls, "format")));
        OcsfPackage ph = open(alone(pkg(h), work.resolve("h-alone")));
        assertThat(ph.refused()).isTrue();
        assertThat(ph.diagnosticsWithCode("NO_MANIFEST")).hasSize(1);
        assertThat(ph.rawLines).isNotEmpty();

        // (i) an amendment whose base is not declared earlier (the Claude fixture amends metadata and tool_call later in the file)
        Path i = copy(CHILD, work.resolve("i"));
        assertThat(declaration(declarationLines(pkg(i).resolve("events.jsonl")), "definition:metadata#2")).isNotNull();
        editDeclarations(pkg(i), decls -> decls.remove(declaration(decls, "definition:metadata")));
        OcsfPackage pi = open(alone(pkg(i), work.resolve("i-alone")));
        assertThat(pi.diagnosticsWithCode("AMENDMENT_BEFORE_DEFINITION").get(0).detail()).contains("definition:metadata#2 amends definition:metadata");
        assertThat(pi.diagnosticsWithCode("MISSING_DEFINITION")).as("metadata is required on every event and now undefined").isNotEmpty();
    }

    @Test
    void theHistoricalTwoFileLayoutStaysReadableUnderItsOwnVersionAndItsDetachedFileIsStillRefused(@TempDir Path work) throws IOException {
        Path fixture = copyHistorical(HISTORICAL_0_2_0, CODEX, work.resolve("hist"));
        OcsfPackage p = PackageReader.open(pkg(fixture).resolve("events.jsonl"));
        assertThat(p.standalone()).isFalse();
        assertThat(p.refused()).isFalse();
        assertThat(p.manifest.packageVersion()).isEqualTo("0.2.0-prototype");
        assertThat(p.diagnosticsWithCode("READER_CONTRACT_MINOR_DIFFERS")).hasSize(1);
        assertThat(p.diagnostics(Diagnostic.Severity.DEFECT)).isEmpty();
        assertThat(p.events).hasSize(39);
        OracleReport report = OcsfOracle.verify(p, natives(fixture));
        // the layout reads; under the 0.6.0 reading its usage predates the canonical tranches (no uncached tranche, no component bases)
        assertThat(report.defects()).as(report.toString()).extracting(Check::property).containsOnly("usage.values");
        assertThat(report.check("usage.values", "main/rollout.jsonl").detail()).contains("uncached_input_tokens MISSING");

        Path alone = alone(pkg(fixture), work.resolve("hist-alone"));
        OcsfPackage detached = PackageReader.open(alone.resolve("events.jsonl"));
        assertThat(detached.refused()).isTrue();
        assertThat(detached.diagnosticsWithCode("NO_MANIFEST")).hasSize(1);
        assertThat(detached.rawLines).hasSize(39);
    }

    /** Copies only {@code events.jsonl} of a core into a fresh, otherwise empty directory. */
    static Path alone(Path core, Path target) throws IOException {
        Files.createDirectories(target);
        Files.copy(core.resolve("events.jsonl"), target.resolve("events.jsonl"));
        try (Stream<Path> s = Files.list(target)) {
            assertThat(s.map(x -> x.getFileName().toString()).toList()).containsExactly("events.jsonl");
        }
        return target;
    }

    static OcsfPackage open(Path alone) {
        return PackageReader.open(alone.resolve("events.jsonl"));
    }
}
