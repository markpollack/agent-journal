package io.github.markpollack.journal.tck.ocsf;

import io.github.markpollack.journal.tck.verify.Check.Outcome;
import io.github.markpollack.journal.tck.verify.Json;
import io.github.markpollack.journal.tck.verify.NeutralJson;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;

import static io.github.markpollack.journal.tck.ocsf.OcsfFixtures.CODEX_STREAM;
import static io.github.markpollack.journal.tck.ocsf.OcsfFixtures.at;
import static io.github.markpollack.journal.tck.ocsf.OcsfFixtures.copy;
import static io.github.markpollack.journal.tck.ocsf.OcsfFixtures.editEvents;
import static io.github.markpollack.journal.tck.ocsf.OcsfFixtures.num;
import static io.github.markpollack.journal.tck.ocsf.OcsfFixtures.obj;
import static io.github.markpollack.journal.tck.ocsf.OcsfFixtures.open;
import static io.github.markpollack.journal.tck.ocsf.OcsfFixtures.pkg;
import static io.github.markpollack.journal.tck.ocsf.OcsfFixtures.str;
import static io.github.markpollack.journal.tck.ocsf.OcsfFixtures.subsetDifferences;
import static io.github.markpollack.journal.tck.ocsf.OcsfFixtures.verify;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Routine committed coverage of the Codex exec-stream profile on the labelled synthetic {@code syn-codex-exec-stream}
 * fixture (FR-04/05): the control verifies every family with its stated populations and is accepted; the hand-authored
 * views match; and each mutation of an operation history, an exit code, a usage value, an identity or a retained
 * record is detected on its owning property. The count units are explicit: observations, source-scoped identities and
 * a logical count only where a supported identity rule exists.
 */
class CodexExecStreamTest {

    static final String STREAM = "main/stream.jsonl";

    @Test
    void controlVerifiesEveryFamilyWithStatedPopulationsAndIsAccepted(@TempDir Path work) {
        OracleReport report = verify(copy(CODEX_STREAM, work));
        assertThat(report.defects()).as(report.toString()).isEmpty();
        assertThat(report.check("whole_record.coverage", STREAM).detail()).startsWith("12 owned record(s) each retained exactly once");
        assertThat(report.check("whole_record.class", STREAM).detail()).isEqualTo("8 normalized, 4 fallback");
        assertThat(report.check("population.responses", STREAM).detail()).isEqualTo("2 expected, 2 observed");
        assertThat(report.check("population.operations", STREAM).detail()).isEqualTo("5 expected, 5 observed");
        assertThat(report.check("population.usage", STREAM).detail()).isEqualTo("1 expected, 1 observed");
        assertThat(report.check("population.turn_completed", STREAM).detail()).isEqualTo("1 expected, 1 observed");
        for (String property : List.of("response.values", "operation.values", "usage.values", "usage.representation", "turn.values", "reconstruction.coverage",
                "reconstruction.structure_value", "reconstruction.bytes", "time.basis", "source.state", "source.extent", "acquisition.complete")) {
            String owner = property.startsWith("acquisition") ? "main" : STREAM;
            assertThat(report.outcome(property, owner)).as(property).isEqualTo(Outcome.VERIFIED);
        }
        assertThat(report.check("time.basis", STREAM).detail()).as("the stream carries no timestamps: every event uses the fallback basis").contains("absent with fallback basis 2");
        assertThat(report.outcome("population.requests", STREAM)).isEqualTo(Outcome.NOT_APPLICABLE);
        assertThat(report.outcome("population.contexts", STREAM)).isEqualTo(Outcome.NOT_APPLICABLE);
        assertThat(OcsfReaderTool.acceptance(report).exit()).isEqualTo(OcsfReaderTool.EXIT_OK);
    }

    @Test
    void viewsMatchTheIndependentlyAuthoredAnswersAndStateTheirCountUnits(@TempDir Path work) throws Exception {
        Json views = AnalysisViews.all(open(copy(CODEX_STREAM, work)));
        Json expected = NeutralJson.parse(new String(CodexExecStreamTest.class.getResourceAsStream("/ocsf/fixtures/" + CODEX_STREAM + "/expected-views.json").readAllBytes(), StandardCharsets.UTF_8));
        assertThat(subsetDifferences(expected, views, "$")).isEmpty();
        assertThat(views.path("responses", "logical_count_basis").text()).contains("2 artifact-scoped identit(ies) are not linked");
        assertThat(views.path("associations", "operations", "logical_count_basis").text()).startsWith("single source");
    }

    @Test
    void anAlteredExitCodeStateOrStatusIsAnOperationValueDefect(@TempDir Path work) {
        Path fixture = copy(CODEX_STREAM, work);
        editEvents(pkg(fixture), events -> {
            obj(at(events, "tool_call", 3, 7).member("tool_operation")).members().put("exit_code", num("0"));
            obj(at(events, "tool_call", 3, 5).member("tool_operation")).members().put("reported_state", str("failed"));
            obj(at(events, "tool_call", 3, 4)).members().put("status_id", num("1"));
        });
        String detail = verify(fixture).check("operation.values", STREAM).detail();
        assertThat(detail).contains("7:item_2 exit_code VALUE").contains("5:item_1 reported_state failed != completed").contains("4:item_1 status_id 1 != 0");
    }

    @Test
    void aDroppedStartOrASwappedIdentityIsAPopulationDefect(@TempDir Path work) {
        Path dropped = copy(CODEX_STREAM, work.resolve("dropped"));
        editEvents(pkg(dropped), events -> events.remove(at(events, "tool_call", 3, 4)));
        OracleReport report = verify(dropped);
        assertThat(report.check("population.operations", STREAM).detail()).contains("missing [4:item_1]");
        assertThat(report.check("whole_record.coverage", STREAM).detail()).contains("record 4: 0 whole-record events");

        Path swapped = copy(CODEX_STREAM, work.resolve("swapped"));
        editEvents(pkg(swapped), events -> {
            obj(at(events, "tool_call", 3, 5).member("tool_operation")).members().put("uid", str("item_2"));
            obj(at(events, "tool_call", 3, 7).member("tool_operation")).members().put("uid", str("item_1"));
        });
        assertThat(verify(swapped).check("population.operations", STREAM).detail()).contains("missing [5:item_1, 7:item_2]").contains("extra [5:item_2, 7:item_1]");
    }

    @Test
    void anOffByOneUsageValueOrAMissingComponentIsAUsageValueDefect(@TempDir Path work) {
        Path fixture = copy(CODEX_STREAM, work);
        editEvents(pkg(fixture), events -> {
            Json u = at(events, "prompt_turn", 2, 12).member("usage_observations").elements().get(0);
            obj(u).members().put("input_tokens", num("9007199254740992"));
            obj(u).members().remove("reasoning_tokens");
        });
        String detail = verify(fixture).check("usage.values", STREAM).detail();
        assertThat(detail).contains("input_tokens VALUE").contains("reasoning_tokens MISSING");
    }

    @Test
    void anAgentMessageTextChangedOrAnUnknownRecordDroppedIsDetected(@TempDir Path work) {
        Path fixture = copy(CODEX_STREAM, work);
        editEvents(pkg(fixture), events -> {
            Json r = at(events, "model_response", 2, 11);
            obj(r.member("content_blocks").elements().get(0)).members().put("data", str("changed"));
            events.remove(at(events, "source_record", 1, 10)); // the synthetic unknown event, retained whole
        });
        OracleReport report = verify(fixture);
        assertThat(report.check("response.values", STREAM).detail()).contains("11:item_5 block 0 data VALUE");
        assertThat(report.check("whole_record.coverage", STREAM).detail()).contains("record 10: 0 whole-record events");
        assertThat(report.check("reconstruction.coverage", STREAM).detail()).contains("[10]");
    }

    @Test
    void claudePairCountsRunScopedIdentitiesLogicallyAndSeparatesRepeatsFromIdenticalContent(@TempDir Path work) {
        Json views = AnalysisViews.all(open(copy(OracleFullSourceTest.PAIR, work)));
        Json responses = views.member("responses");
        assertThat(responses.member("identity_count").scalarText()).isEqualTo(responses.member("logical_count").scalarText());
        assertThat(responses.member("logical_count_basis").text()).startsWith("every identity is a run-scoped reported id");
        assertThat(responses.member("observation_count").scalarText()).isEqualTo("9"); // 7 stream + 2 transcript occurrences
        Json exchanges = views.member("tool_exchanges");
        List<Json> repeated = exchanges.member("repeated_result_observations").elements();
        assertThat(repeated).hasSize(1);
        assertThat(repeated.get(0).member("tool_call_uid").text()).isEqualTo("toolu_0183ddwvMmvPzqAU5Jeu5hwZ");
        assertThat(repeated.get(0).member("occurrences").elements()).extracting(Json::text).containsExactly("stream.jsonl:6", "transcript.jsonl:14");
        assertThat(repeated.get(0).member("content_equal")).as("the synthetic transcript's result content differs from the stream's").isEqualTo(new Json.JBool(false));
        assertThat(exchanges.member("identical_content_distinct_owners").elements()).isEmpty();
        assertThat(views.path("associations", "operations", "observation_count").scalarText()).isEqualTo("0");
    }
}
