package io.github.markpollack.journal.tck.ocsf;

import io.github.markpollack.journal.tck.verify.Check.Outcome;
import io.github.markpollack.journal.tck.verify.Json;
import io.github.markpollack.journal.tck.verify.NeutralJson;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static io.github.markpollack.journal.tck.ocsf.OcsfFixtures.LIVE_AGY_DENIAL;
import static io.github.markpollack.journal.tck.ocsf.OcsfFixtures.LIVE_AGY_FEATURES;
import static io.github.markpollack.journal.tck.ocsf.OcsfFixtures.LIVE_AGY_INTERRUPT_OPEN;
import static io.github.markpollack.journal.tck.ocsf.OcsfFixtures.LIVE_AGY_SUBAGENT;
import static io.github.markpollack.journal.tck.ocsf.OcsfFixtures.at;
import static io.github.markpollack.journal.tck.ocsf.OcsfFixtures.copy;
import static io.github.markpollack.journal.tck.ocsf.OcsfFixtures.editEvents;
import static io.github.markpollack.journal.tck.ocsf.OcsfFixtures.num;
import static io.github.markpollack.journal.tck.ocsf.OcsfFixtures.obj;
import static io.github.markpollack.journal.tck.ocsf.OcsfFixtures.pkg;
import static io.github.markpollack.journal.tck.ocsf.OcsfFixtures.str;
import static io.github.markpollack.journal.tck.ocsf.OcsfFixtures.verify;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The Antigravity CLI 1.4.6 stream-json route on its four sanitized live recordings: each verifies with the population
 * its native records owe (record and step numbers below are read from the native streams), and each mutation of a
 * material reading is detected on its owning property: a soft-denied command reported as a success or tied to its
 * run-scope denial, a changed tool argument, an invented response identity, an altered text fragment or turn response,
 * a fabricated result for the open tool of the interrupted run, a changed child conversation of a subagent spawn, and
 * an uncached tranche, a wrong conditional uncached value or a reasoning relation the source does not establish.
 */
class AntigravityStreamTest {

    static final String STREAM = "main/stream.jsonl";

    @Test
    void theFourLiveRecordingsVerifyWithTheirOwedPopulations(@TempDir Path work) {
        OracleReport features = verify(copy(LIVE_AGY_FEATURES, work));
        assertThat(features.defects()).as(features.toString()).isEmpty();
        // features: tool steps 2, 4, 6, 8, 10 start at records 4, 7, 10, 13, 16 and finish at 5 (ERROR), 8, 11, 14, 17;
        // agent-response items at 3, 6, 9, 12, 15, 19 (DONE) and 18 (ACTIVE); step usage on the six DONE items plus the result's
        Map<String, String> owed = Map.of("population.requests", "5 expected, 5 observed", "population.results", "5 expected, 5 observed",
                "population.responses", "7 expected, 7 observed", "population.usage", "7 expected, 7 observed", "population.prompts", "1 expected, 1 observed",
                "population.contexts", "1 expected, 1 observed", "population.turn_completed", "1 expected, 1 observed");
        owed.forEach((property, detail) -> assertThat(features.check(property, STREAM).detail()).as(property).isEqualTo(detail));
        for (String property : List.of("request.values", "result.values", "result.owner", "response.values", "usage.values", "prompt.values", "context.values",
                "turn.values", "identity.values", "time.basis", "whole_record.coverage", "whole_record.class", "reconstruction.structure_value")) {
            assertThat(features.outcome(property, STREAM)).as(property).isEqualTo(Outcome.VERIFIED);
        }
        assertThat(features.check("whole_record.class", STREAM).detail()).isEqualTo("20 normalized, 0 fallback");
        assertThat(OcsfReaderTool.acceptance(features).exit()).isEqualTo(OcsfReaderTool.EXIT_OK);

        OracleReport denial = verify(copy(LIVE_AGY_DENIAL, work));
        assertThat(denial.defects()).as(denial.toString()).isEmpty();
        assertThat(denial.check("population.decisions", STREAM).detail()).isEqualTo("1 expected, 1 observed");
        assertThat(denial.outcome("decision.values", STREAM)).isEqualTo(Outcome.VERIFIED);
        assertThat(OcsfReaderTool.acceptance(denial).exit()).isEqualTo(OcsfReaderTool.EXIT_OK);

        OracleReport subagent = verify(copy(LIVE_AGY_SUBAGENT, work));
        assertThat(subagent.defects()).as(subagent.toString()).isEmpty();
        // the system_message step at record 17 has no rule: retained whole
        assertThat(subagent.check("whole_record.class", STREAM).detail()).isEqualTo("19 normalized, 1 fallback");
        assertThat(OcsfReaderTool.acceptance(subagent).exit()).isEqualTo(OcsfReaderTool.EXIT_OK);

        // the interrupted run: run_command step 2 started at record 4 and never finished; the CLI's own result (record 5) still arrived
        OracleReport interrupted = verify(copy(LIVE_AGY_INTERRUPT_OPEN, work));
        assertThat(interrupted.defects()).as(interrupted.toString()).isEmpty();
        assertThat(interrupted.check("population.requests", STREAM).detail()).isEqualTo("1 expected, 1 observed");
        assertThat(interrupted.outcome("population.results", STREAM)).isEqualTo(Outcome.NOT_APPLICABLE);
        assertThat(interrupted.outcome("package.state", "main")).isEqualTo(Outcome.UNSUPPORTED);
        assertThat(OcsfReaderTool.acceptance(interrupted).exit()).as("an interrupted capture is judged, never accepted as complete").isEqualTo(OcsfReaderTool.EXIT_INCOMPLETE);
    }

    @Test
    void aSoftDeniedCommandIsNeitherASuccessNorAttributedToItsDenial(@TempDir Path work) {
        Path fixture = copy(LIVE_AGY_DENIAL, work);
        editEvents(pkg(fixture), events -> {
            // record 8: run_command step 4 DONE with neither output nor error; the run's denied_actions (record 9) names no step
            Json.JObject result = obj(at(events, "tool_call", 2, 8));
            result.members().put("status_id", num("1"));
            result.members().put("status_basis_id", num("1"));
            Json decision = at(events, "prompt_turn", 2, 9).member("permission_decisions").elements().get(0);
            Json.JObject attribution = obj(decision.member("attribution"));
            attribution.members().put("outcome_id", num("1"));
            attribution.members().put("method_id", num("1"));
            attribution.members().put("candidates", new Json.JArray(List.of(str("4"))));
        });
        OracleReport report = verify(fixture);
        assertThat(report.check("result.values", STREAM).detail()).contains("8:4 status_id/status_basis_id 1/1 != 0/3");
        assertThat(report.check("decision.values", STREAM).detail()).contains("attribution outcome/method 1/1 != 3/2").contains("candidates [4] != []");
    }

    @Test
    void aChangedArgumentAnInventedResponseIdOrAnAlteredFragmentIsDetected(@TempDir Path work) {
        Path fixture = copy(LIVE_AGY_FEATURES, work);
        editEvents(pkg(fixture), events -> {
            obj(at(events, "tool_call", 4, 10).member("arguments")).members().put("CommandLine", str("wc -l src/a.txt"));
            obj(at(events, "model_response", 3, 3)).members().put("response_uid", str("resp-1"));
            obj(at(events, "model_response", 1, 18).member("content_blocks").elements().get(0)).members().put("data", str("5"));
            obj(at(events, "prompt_turn", 2, 20).member("content_blocks").elements().get(0)).members().put("text", str("5\n"));
        });
        OracleReport report = verify(fixture);
        assertThat(report.check("request.values", STREAM).detail()).contains("10:6 arguments");
        assertThat(report.check("population.responses", STREAM).detail()).contains("3:item 1").contains("3:resp-1");
        assertThat(report.check("response.values", STREAM).detail()).contains("18:11 block 0 data VALUE");
        assertThat(report.check("turn.values", STREAM).detail()).contains("record 20 turn content block texts differ");
    }

    @Test
    void aFabricatedResultForTheOpenToolOfTheInterruptedRunIsDetected(@TempDir Path work) {
        Path fixture = copy(LIVE_AGY_INTERRUPT_OPEN, work);
        editEvents(pkg(fixture), events -> {
            Json started = at(events, "tool_call", 4, 4);
            Json result = NeutralJson.parse(NeutralJson.write(started));
            obj(result).members().put("activity_id", num("2"));
            obj(result.member("source_ref")).members().put("is_whole_record", new Json.JBool(false));
            events.add(events.indexOf(started) + 1, result);
        });
        assertThat(verify(fixture).check("population.results", STREAM).detail()).contains("extra [4:2] (expected 0, observed 1)");
    }

    @Test
    void aChangedChildConversationOfTheSubagentSpawnIsDetected(@TempDir Path work) {
        Path fixture = copy(LIVE_AGY_SUBAGENT, work);
        editEvents(pkg(fixture), events -> {
            // record 5: the subagent step completing invoke_subagent step 2; its result content is the reported subagent_info
            Json child = at(events, "tool_call", 2, 5).path("tool_result", "data", "subagents").elements().get(0);
            obj(child).members().put("conversation_id", str("00000000-0000-4000-8000-000000000999"));
        });
        assertThat(verify(fixture).check("result.values", STREAM).detail()).contains("5:2 content VALUE");
    }

    @Test
    void anUncachedTrancheOrAReasoningRelationTheSourceDoesNotEstablishIsAUsageDefect(@TempDir Path work) {
        Path fixture = copy(LIVE_AGY_FEATURES, work);
        editEvents(pkg(fixture), events -> {
            Json.JObject u = obj(at(events, "prompt_turn", 2, 20).member("usage_observations").elements().get(0));
            u.members().put("uncached_input_tokens", u.path("data", "input_tokens"));
            u.members().put("reasoning_relation_id", num("1"));
            // input 18690 excludes cache reads but may hold unreported cache writes: it is only the component's conditional value
            obj(u.member("components").elements().get(0)).members().put("conditional_value", num("18691"));
        });
        String detail = verify(fixture).check("usage.values", STREAM).detail();
        assertThat(detail).contains("uncached_input_tokens present").contains("reasoning_relation_id").contains("uncached_input_tokens conditional_value 18691 != 18690");
    }
}
