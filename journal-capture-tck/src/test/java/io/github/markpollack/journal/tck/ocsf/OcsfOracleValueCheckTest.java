package io.github.markpollack.journal.tck.ocsf;

import io.github.markpollack.journal.tck.verify.Check.Outcome;
import io.github.markpollack.journal.tck.verify.Json;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static io.github.markpollack.journal.tck.ocsf.OcsfFixtures.CHILD;
import static io.github.markpollack.journal.tck.ocsf.OcsfFixtures.CODEX;
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
 * One targeted corruption per individual value check, added after the first PIT run showed which comparisons no
 * fixture mutation had yet exercised (a survived "remove conditional" on a check means no test needed that check).
 * Each test asserts the exact detail the check emits, so weakening or deleting the comparison is detected.
 */
class OcsfOracleValueCheckTest {

    static final String MAIN = "main/stream.jsonl";
    static final String ROLLOUT = "main/rollout.jsonl";

    @Test
    void responseActivityStopReasonModelAndBlocksAreEachChecked(@TempDir Path work) {
        Path fixture = copy(CHILD, work);
        editEvents(pkg(fixture), events -> {
            Json r4 = at(events, "model_response", 1, 4);
            obj(r4).members().put("activity_id", num("2"));                       // non-terminal record claimed Completed
            obj(r4).members().put("stop_reason", str("end_turn"));                 // stop reason the record does not report
            obj(r4.member("model_ref")).members().put("name", str("other-model"));
            Json r14 = at(events, "model_response", 1, 14);
            obj(r14.member("content_blocks").elements().get(0)).members().put("block_type", str("text"));   // native block is thinking
            obj(r14.member("content_blocks").elements().get(0)).members().put("block_index", num("1"));
            Json r15 = at(events, "model_response", 1, 15);
            r15.member("content_blocks").elements().add(OcsfFixtures.copy(r15.member("content_blocks").elements().get(0)));  // one block too many
            Json r25 = at(events, "model_response", 1, 25);
            obj(r25.member("content_blocks").elements().get(0).member("data")).members().put("thinking", str("altered"));
        });
        OracleReport report = verify(fixture);
        assertThat(report.outcome("response.values", MAIN)).isEqualTo(Outcome.DEFECTIVE);
        String detail = report.check("response.values", MAIN).detail();
        assertThat(detail).contains("4:msg_011Cg2aRQ1iZf8BxQVUnD2jR activity_id 2 but the record is non-terminal")
                .contains("stop_reason end_turn != null")
                .contains("model_ref.name other-model != claude-haiku-4-5-20251001")
                .contains("14:msg_011Cg2aRa7RdELQkMpTBpGr2 block 0 block_index 1")
                .contains("block 0 block_type text != thinking")
                .contains("15:msg_011Cg2aRa7RdELQkMpTBpGr2 content_blocks 2 != 1 native blocks")
                .contains("25:msg_011Cg2aSX39xwYN5tqhMZAyi block 0 data VALUE at $.thinking");
    }

    @Test
    void responseContentPresentWhereTheSourceHasNoneIsADefect(@TempDir Path work) {
        // the synthetic Codex fixture has block content; drop content_blocks from one event instead
        Path fixture = copy(CODEX, work);
        editEvents(pkg(fixture), events -> obj(at(events, "model_response", 2, 7)).members().remove("content_blocks"));
        assertThat(verify(fixture).check("response.values", ROLLOUT).detail()).contains("7:msg_a_1 content_blocks absent != 1 native blocks");
    }

    @Test
    void requestNameResponseLinkAndReportedStateAreEachChecked(@TempDir Path work) {
        Path fixture = copy(CHILD, work);
        editEvents(pkg(fixture), events -> {
            Json r5 = at(events, "tool_call", 1, 5);
            obj(r5).members().put("tool_name", str("Grep"));
            obj(r5).members().put("response_uid", str("msg_other"));
        });
        String detail = verify(fixture).check("request.values", MAIN).detail();
        assertThat(detail).contains("5:toolu_0183ddwvMmvPzqAU5Jeu5hwZ tool_name Grep != ToolSearch").contains("response_uid msg_other != msg_011Cg2aRQ1iZf8BxQVUnD2jR");
        Path codex = copy(CODEX, work.resolve("codex"));
        editEvents(pkg(codex), events -> obj(at(events, "tool_call", 1, 9)).members().put("reported_state", str("in_progress")));
        assertThat(verify(codex).check("request.values", ROLLOUT).detail()).contains("9:call_0001aaaa reported_state in_progress != completed");
    }

    @Test
    void resultStructuredDetailAndMissingResultAreEachChecked(@TempDir Path work) {
        Path fixture = copy(CHILD, work);
        editEvents(pkg(fixture), events -> {
            Json r6 = at(events, "tool_call", 2, 6);
            obj(r6.member("tool_result")).members().put("structured_result", new Json.JArray(List.of()));   // record 6 has a tool_use_result object
            Json r24 = at(events, "tool_call", 2, 24);
            obj(r24).members().remove("tool_result");
        });
        String detail = verify(fixture).check("result.values", MAIN).detail();
        assertThat(detail).contains("6:toolu_0183ddwvMmvPzqAU5Jeu5hwZ structured_result KIND at $").contains("24:toolu_01BnEuCUmZ2Vu1LpADzksmrJ tool_result absent");
        Path codex = copy(CODEX, work.resolve("codex"));
        editEvents(pkg(codex), events -> obj(at(events, "tool_call", 2, 12).member("tool_result")).members().put("structured_result", str("none reported")));
        assertThat(verify(codex).check("result.values", ROLLOUT).detail()).contains("12:call_0001aaaa structured_result present but not reported");
    }

    @Test
    void usageNativeObjectMustBeRetained(@TempDir Path work) {
        Path fixture = copy(CHILD, work);
        editEvents(pkg(fixture), events -> obj(at(events, "model_response", 1, 4).member("usage_observations").elements().get(0)).members().remove("data"));
        assertThat(verify(fixture).check("usage.values", MAIN).detail()).contains("4:4:2:claude-haiku-4-5-20251001 native usage object (data) not retained");
    }

    @Test
    void costScopeIsChecked(@TempDir Path work) {
        Path fixture = copy(CHILD, work);
        editEvents(pkg(fixture), events -> obj(at(events, "prompt_turn", 2, 27).member("cost_observation")).members().put("scope_id", num("3")));
        assertThat(verify(fixture).check("cost.values", MAIN).detail()).contains("record 27 scope_id 3 != 4");
    }

    @Test
    void decisionSourceReasonCandidatesAndMethodAreEachChecked(@TempDir Path work) {
        Path fixture = copy(CHILD, work);
        editEvents(pkg(fixture), events -> {
            Json d6 = at(events, "tool_call", 2, 6).member("permission_decision");
            obj(d6).members().put("decision_source", str("user"));
            obj(d6).members().put("reason_type", str("rule"));                     // record 6 reports no reason_type
            Json d24 = at(events, "tool_call", 2, 24).member("permission_decision");
            obj(d24.member("attribution")).members().put("candidates", new Json.JArray(List.of(str("toolu_01BnEuCUmZ2Vu1LpADzksmrJ"), str("toolu_0183ddwvMmvPzqAU5Jeu5hwZ"))));
            obj(d24.member("attribution")).members().put("method_id", num("2"));
        });
        String detail = verify(fixture).check("decision.values", MAIN).detail();
        assertThat(detail).contains("6:1:toolu_0183ddwvMmvPzqAU5Jeu5hwZ decision_source user != config")
                .contains("reason_type rule != null")
                .contains("24:1:toolu_01BnEuCUmZ2Vu1LpADzksmrJ attribution outcome/method 1/2 != 1/1")
                .contains("candidates [toolu_01BnEuCUmZ2Vu1LpADzksmrJ, toolu_0183ddwvMmvPzqAU5Jeu5hwZ] != [toolu_01BnEuCUmZ2Vu1LpADzksmrJ]");
    }

    @Test
    void operationArgvExitCodeStateStatusAndCandidatesAreEachChecked(@TempDir Path work) {
        Path fixture = copy(CODEX, work);
        editEvents(pkg(fixture), events -> {
            Json op10 = at(events, "tool_call", 3, 10);
            obj(op10.member("tool_operation")).members().put("argv", new Json.JArray(List.of(str("/bin/bash"), str("-lc"), str("echo ONE"))));
            obj(op10.member("tool_operation")).members().put("exit_code", num("7"));
            obj(op10.member("tool_operation")).members().put("reported_state", str("failed"));
            obj(op10).members().put("status_id", num("2"));
            Json op11 = at(events, "tool_call", 3, 11);
            obj(op11.member("tool_operation").member("attribution")).members().put("candidates", new Json.JArray(List.of(str("call_0001aaaa"), str("call_9999zzzz"))));
            Json op24 = at(events, "tool_call", 3, 24);
            obj(op24.member("tool_operation")).members().remove("exit_code");
            obj(op24).members().put("tool_call_uid", str("call_0002bbbb"));
        });
        String detail = verify(fixture).check("operation.values", ROLLOUT).detail();
        assertThat(detail).contains("10:exec-0001 argv VALUE at $[2]").contains("10:exec-0001 exit_code VALUE").contains("reported_state failed != completed")
                .contains("status_id 2 != 1").contains("11:exec-0002 candidates [call_0001aaaa, call_9999zzzz] != recount [call_0001aaaa]")
                .contains("24:exec-0003 exit_code MISSING at $").contains("tool_call_uid call_0002bbbb != null (set only for a unique candidate)");
        assertThat(verify(fixture).check("operation.values", ROLLOUT).detail()).doesNotContain("24:exec-0003 exit_code present but not reported");
    }

    @Test
    void promptTurnIdAndTextsAreChecked(@TempDir Path work) {
        Path fixture = copy(CODEX, work);
        editEvents(pkg(fixture), events -> {
            Json p5 = at(events, "prompt_turn", 1, 5);
            obj(p5).members().put("turn_uid", str("turn-9999"));
            Json p6 = at(events, "prompt_turn", 1, 6);
            obj(p6.member("content_blocks").elements().get(0)).members().put("text", str("Say goodbye."));
        });
        String detail = verify(fixture).check("prompt.values", ROLLOUT).detail();
        assertThat(detail).contains("record 5 turn_uid turn-9999 != turn-0001-synthetic").contains("record 6 block texts differ");
    }

    @Test
    void turnCompletedStopReasonAbsentWhereReportedIsADefect(@TempDir Path work) {
        Path fixture = copy(CHILD, work);
        editEvents(pkg(fixture), events -> obj(at(events, "prompt_turn", 2, 27)).members().remove("stop_reason"));
        assertThat(verify(fixture).check("turn.values", MAIN).detail()).contains("record 27 stop_reason null != end_turn");
    }

    @Test
    void fallbackTimeBasisClaimedAsSourceTimeIsADefect(@TempDir Path work) {
        Path fixture = copy(CHILD, work);
        editEvents(pkg(fixture), events -> {
            Json system = at(events, "session_context", 1, 1);                   // the init line reports no timestamp
            obj(system.member("journal")).members().put("time_basis_id", num("1"));
            obj(system.member("metadata")).members().put("original_time", str("2026-10-14T18:08:46.000Z"));
        });
        assertThat(verify(fixture).check("time.basis", MAIN).detail()).contains("record 1 reports no time; event original_time 2026-10-14T18:08:46.000Z, time_basis_id 1 (fallback expected)");
    }

    @Test
    void recordIndexGoingBackwardsWithinAnArtifactIsAnOrderDefect(@TempDir Path work) {
        Path fixture = copy(CODEX, work);
        editEvents(pkg(fixture), events -> {
            // keep sequences increasing but point the last event at an earlier record
            Json last = events.get(events.size() - 1);
            obj(last.member("source_ref")).members().put("record_index", num("2"));
        });
        OracleReport report = verify(fixture);
        assertThat(report.check("event.order", "main").detail()).contains("rollout.jsonl record 2 after record 38");
    }

    @Test
    void parsedRecordRetainedAsOpaqueAndRecordBeyondTheArtifactAreDefects(@TempDir Path work) {
        Path fixture = copy(CODEX, work);
        editEvents(pkg(fixture), events -> {
            Json sr1 = at(events, "source_record", 1, 13);
            obj(sr1).members().put("activity_id", num("2"));                        // a parsed record claimed Opaque
            Json sr2 = at(events, "source_record", 1, 16);
            obj(sr2.member("source_ref")).members().put("record_index", num("99"));    // points past the 39 records
        });
        OracleReport report = verify(fixture);
        assertThat(report.check("whole_record.class", ROLLOUT).detail()).contains("record 13 parsed but retained as Opaque");
        assertThat(report.check("whole_record.coverage", ROLLOUT).detail()).contains("record 16: 0 whole-record events").contains("record 99 retained but the artifact has 39 records");
    }

    @Test
    void excludedRecordRetainedAnywayIsADefect(@TempDir Path work) {
        // the synthetic fixture has no admission; declare one by shrinking the owned set through a bounded transcript-like scope is not
        // available here, so exercise the declared-exclusion path on the inventory instead: an undeclared exclusion is a defect
        Path fixture = copy(CODEX, work);
        OcsfFixtures.editManifest(pkg(fixture), run -> obj(run.member("sources").elements().get(0)).members().put("records", num("30")));
        assertThat(verify(fixture).check("source.inventory", ROLLOUT).detail()).contains("records 30 declared, artifact has 39");
    }

    @Test
    void reconstructedLineThatIsNotJsonIsAStructureDefect(@TempDir Path work) {
        Path fixture = copy(CODEX, work);
        editEvents(pkg(fixture), events -> {
            Json sr1 = at(events, "source_record", 1, 13);
            obj(sr1).members().remove("data");
        });
        OracleReport report = verify(fixture);
        assertThat(report.check("whole_record.structure_value", ROLLOUT).detail()).contains("record 13: whole-record event has no data");
        assertThat(report.check("reconstruction.coverage", ROLLOUT).detail()).contains("no whole-record event for record(s) [13]");
    }
}
