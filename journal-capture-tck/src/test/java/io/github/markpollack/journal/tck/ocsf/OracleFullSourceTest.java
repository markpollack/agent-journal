package io.github.markpollack.journal.tck.ocsf;

import io.github.markpollack.journal.tck.verify.Check.Outcome;
import io.github.markpollack.journal.tck.verify.Json;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static io.github.markpollack.journal.tck.ocsf.OcsfFixtures.at;
import static io.github.markpollack.journal.tck.ocsf.OcsfFixtures.copy;
import static io.github.markpollack.journal.tck.ocsf.OcsfFixtures.editEvents;
import static io.github.markpollack.journal.tck.ocsf.OcsfFixtures.editManifest;
import static io.github.markpollack.journal.tck.ocsf.OcsfFixtures.isEvent;
import static io.github.markpollack.journal.tck.ocsf.OcsfFixtures.num;
import static io.github.markpollack.journal.tck.ocsf.OcsfFixtures.obj;
import static io.github.markpollack.journal.tck.ocsf.OcsfFixtures.pkg;
import static io.github.markpollack.journal.tck.ocsf.OcsfFixtures.recordIndex;
import static io.github.markpollack.journal.tck.ocsf.OcsfFixtures.str;
import static io.github.markpollack.journal.tck.ocsf.OcsfFixtures.verify;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Full-source regressions on the two-source Claude fixture (sanitized real stream + labelled synthetic transcript,
 * package written by the incremental writer): the control verifies with complete populations, and each mutation is
 * detected on the owning property. A candidate cannot waive acquired evidence by declaration, hide a family, drop a
 * retained-only record, alter a normalized value while the raw record stays intact, or swap owners.
 */
class OracleFullSourceTest {

    static final String PAIR = "syn-claude-pair-transcript";
    static final String STREAM = "main/stream.jsonl";
    static final String TRANSCRIPT = "main/transcript.jsonl";

    @Test
    void controlVerifiesEveryFamilyOfBothSourcesWithNonEmptyPopulations(@TempDir Path work) {
        Path fixture = copy(PAIR, work);
        OracleReport report = verify(fixture);
        assertThat(report.defects()).as(report.toString()).isEmpty();
        assertThat(report.withOutcome(Outcome.UNRESOLVED)).as("only the combination: the pair is an unvalidated evidence log, never a final combined execution")
                .singleElement().satisfies(c -> assertThat(c.property()).isEqualTo("combination.policy"));
        assertThat(report.outcome("package.state", "main")).isEqualTo(Outcome.VERIFIED);
        assertThat(report.outcome("whole_record.coverage", TRANSCRIPT)).isEqualTo(Outcome.VERIFIED);
        assertThat(report.check("whole_record.coverage", TRANSCRIPT).detail()).startsWith("17 owned record(s) each retained exactly once");
        assertThat(report.check("whole_record.class", TRANSCRIPT).detail()).isEqualTo("9 normalized, 8 fallback");
        for (String property : List.of("population.requests", "population.results", "population.responses", "population.prompts", "population.usage",
                "population.cost", "population.decisions", "population.contexts", "context.values", "usage.values", "cost.values", "result.values",
                "response.values", "prompt.values", "decision.values", "reconstruction.coverage", "reconstruction.structure_value", "time.basis")) {
            assertThat(report.outcome(property, TRANSCRIPT)).as(property).isEqualTo(Outcome.VERIFIED);
        }
        // the synthetic transcript is not compact JSON: byte-exact reconstruction is observed as unsupported, value equality holds
        assertThat(report.outcome("reconstruction.bytes", TRANSCRIPT)).isEqualTo(Outcome.UNSUPPORTED);
        assertThat(report.outcome("reconstruction.bytes", STREAM)).isEqualTo(Outcome.VERIFIED);
        assertThat(report.outcome("population.contexts", STREAM)).isEqualTo(Outcome.VERIFIED);
        assertThat(report.check("population.contexts", TRANSCRIPT).detail()).isEqualTo("5 expected, 5 observed");
        // every family of both sources verifies; the file as a whole is an evidence log, not one combined execution
        assertThat(OcsfReaderTool.acceptance(report).exit()).isEqualTo(OcsfReaderTool.EXIT_INCOMPLETE);
        assertThat(OcsfReaderTool.acceptance(report.filtered(c -> !"combination.policy".equals(c.property()))).exit()).isEqualTo(OcsfReaderTool.EXIT_OK);
    }

    @Test
    void anOmittedNativeArtifactIsUnresolvedAndBlocksAcceptance(@TempDir Path work) throws Exception {
        Path fixture = copy(PAIR, work);
        Files.delete(OcsfFixtures.natives(fixture).resolve("transcript.jsonl"));
        OracleReport report = verify(fixture);
        assertThat(report.outcome("source.available", TRANSCRIPT)).isEqualTo(Outcome.UNRESOLVED);
        assertThat(report.defects()).isEmpty();
        assertThat(OcsfReaderTool.acceptance(report).exit()).isEqualTo(OcsfReaderTool.EXIT_INCOMPLETE);
    }

    @Test
    void droppingARetainedOnlyTranscriptRecordIsACoverageDefect(@TempDir Path work) {
        Path fixture = copy(PAIR, work);
        editEvents(pkg(fixture), events -> events.remove(at(events, "transcript.jsonl", "source_record", 1, 9))); // api-request-blob, retained-only
        OracleReport report = verify(fixture);
        assertThat(report.check("whole_record.coverage", TRANSCRIPT).detail()).contains("record 9: 0 whole-record events");
        assertThat(report.check("reconstruction.coverage", TRANSCRIPT).detail()).contains("[9]");
        assertThat(report.outcome("population.requests", TRANSCRIPT)).as("the normalized families are untouched").isEqualTo(Outcome.VERIFIED);
    }

    @Test
    void droppingAnInjectedContextRecordIsAMissingContextAndACoverageDefect(@TempDir Path work) {
        Path fixture = copy(PAIR, work);
        editEvents(pkg(fixture), events -> events.remove(at(events, "transcript.jsonl", "session_context", 2, 4))); // attachment/environment
        OracleReport report = verify(fixture);
        assertThat(report.check("population.contexts", TRANSCRIPT).detail()).contains("missing [4:2:attachment/environment]");
        assertThat(report.outcome("whole_record.coverage", TRANSCRIPT)).isEqualTo(Outcome.DEFECTIVE);
    }

    @Test
    void hidingAFamilyBehindADeclaredExclusionDoesNotWaiveItsRecords(@TempDir Path work) {
        Path fixture = copy(PAIR, work);
        editEvents(pkg(fixture), events -> events.removeIf(e -> isEvent(e, "source_record", 1) && "last-prompt".equals(SchemaCatalog.text(e, "record_type"))));
        editManifest(pkg(fixture), run -> obj(run.member("sources").elements().get(1).path("family_inventory", "last-prompt")).members()
                .put("disposition", str("excluded: not admitted")));
        OracleReport report = verify(fixture);
        assertThat(report.check("whole_record.coverage", TRANSCRIPT).detail()).contains("record 11: 0 whole-record events").contains("record 16: 0 whole-record events");
        assertThat(report.outcome("whole_record.excluded", TRANSCRIPT)).as("no declared exclusion exists on the complete layout").isNull();
    }

    @Test
    void aCandidateDeclaredAdmissionOnTheCompleteLayoutIsADefectAndDoesNotShrinkTheDenominator(@TempDir Path work) {
        Path fixture = copy(PAIR, work);
        editEvents(pkg(fixture), events -> events.removeIf(e -> "transcript.jsonl".equals(SchemaCatalog.text(e.member("source_ref"), "artifact")) && recordIndex(e) > 14));
        editManifest(pkg(fixture), run -> obj(run.member("sources").elements().get(1)).members().put("admit_response_uid", str("msg_011Cg2aRQ1iZf8BxQVUnD2jR")));
        OracleReport report = verify(fixture);
        assertThat(report.outcome("source.admission", TRANSCRIPT)).isEqualTo(Outcome.DEFECTIVE);
        assertThat(report.check("whole_record.coverage", TRANSCRIPT).detail()).contains("record 15: 0 whole-record events").contains("record 17: 0 whole-record events");
        assertThat(report.check("population.contexts", TRANSCRIPT).detail()).contains("missing").contains("17:3:cost-state");
        assertThat(report.outcome("population.cost", TRANSCRIPT)).isEqualTo(Outcome.DEFECTIVE);
    }

    @Test
    void alteringNormalizedSessionUsageWhileTheRawRecordIsIntactIsAValueDefect(@TempDir Path work) {
        Path fixture = copy(PAIR, work);
        editEvents(pkg(fixture), events -> {
            Json cost = at(events, "transcript.jsonl", "session_context", 3, 17);
            obj(cost.member("usage_observations").elements().get(0)).members().put("output_tokens", num("9"));
            obj(cost.member("cost_observation")).members().put("total_cost", num("0.01"));
        });
        OracleReport report = verify(fixture);
        assertThat(report.check("usage.values", TRANSCRIPT).detail()).contains("17:17:5:claude-haiku-4-5-20251001 output_tokens");
        assertThat(report.check("cost.values", TRANSCRIPT).detail()).contains("record 17 total_cost");
        assertThat(report.outcome("whole_record.structure_value", TRANSCRIPT)).as("the retained record is untouched").isEqualTo(Outcome.VERIFIED);
    }

    @Test
    void alteringAReportedSettingOrAddingAnUnreportedOneIsAContextValueDefect(@TempDir Path work) {
        Path fixture = copy(PAIR, work);
        editEvents(pkg(fixture), events -> {
            Json init = at(events, "stream.jsonl", "session_context", 1, 1);
            obj(init.member("settings")).members().put("working_directory", str("/elsewhere"));
            obj(init.member("settings")).members().put("approval_policy", str("never"));
        });
        OracleReport report = verify(fixture);
        assertThat(report.check("context.values", STREAM).detail()).contains("settings.working_directory").contains("settings.approval_policy present but the record reports no such setting");
    }

    @Test
    void swappingResultOwnersBetweenRecordsIsAnOwnershipDefectOnBothSources(@TempDir Path work) {
        Path fixture = copy(PAIR, work);
        editEvents(pkg(fixture), events -> {
            Json a = at(events, "stream.jsonl", "tool_call", 2, 6);
            Json b = at(events, "stream.jsonl", "tool_call", 2, 24);
            Json owner = a.member("tool_call_uid");
            obj(a).members().put("tool_call_uid", b.member("tool_call_uid"));
            obj(b).members().put("tool_call_uid", owner);
        });
        OracleReport report = verify(fixture);
        assertThat(report.outcome("result.owner", STREAM)).isEqualTo(Outcome.DEFECTIVE);
        assertThat(report.check("result.owner", STREAM).detail()).contains("record 6 names [toolu_0183ddwvMmvPzqAU5Jeu5hwZ]");
        assertThat(report.outcome("population.results", STREAM)).isEqualTo(Outcome.DEFECTIVE);
    }

    @Test
    void changingARetainedNumberIsAStructureDefectAndRespellingItIsARepresentationDifference(@TempDir Path work) {
        Path fixture = copy(PAIR, work);
        editEvents(pkg(fixture), events -> obj(at(events, "transcript.jsonl", "source_record", 1, 10).member("data")).members().put("keep", num("1.0"))); // api-request keep 0 -> 1.0
        assertThat(verify(fixture).outcome("whole_record.structure_value", TRANSCRIPT)).isEqualTo(Outcome.DEFECTIVE);
        Path again = copy(PAIR, work.resolve("again"));
        editEvents(pkg(again), events -> obj(at(events, "transcript.jsonl", "source_record", 1, 10).member("data")).members().put("keep", num("0.0")));
        OracleReport report = verify(again);
        assertThat(report.outcome("whole_record.structure_value", TRANSCRIPT)).isEqualTo(Outcome.VERIFIED);
        assertThat(report.outcome("whole_record.representation", TRANSCRIPT)).isEqualTo(Outcome.REPRESENTATION_DIFFERS);
    }

    @Test
    void anOpenPackageIsJudgedAsAPrefixAndNeverAccepted(@TempDir Path work) {
        Path fixture = copy(PAIR, work);
        editManifest(pkg(fixture), run -> {
            obj(run.member("package")).members().put("state", str("RECORDING"));
            obj(run.member("package")).members().put("events_jsonl_sha256", Json.JNull.INSTANCE);
        });
        OracleReport report = verify(fixture);
        assertThat(report.defects()).isEmpty();
        assertThat(report.outcome("package.state", "main")).isEqualTo(Outcome.UNRESOLVED);
        assertThat(OcsfReaderTool.acceptance(report).exit()).isEqualTo(OcsfReaderTool.EXIT_INCOMPLETE);
    }
}
