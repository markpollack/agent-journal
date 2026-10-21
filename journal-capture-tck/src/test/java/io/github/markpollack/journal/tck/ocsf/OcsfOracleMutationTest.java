package io.github.markpollack.journal.tck.ocsf;

import io.github.markpollack.journal.tck.verify.Check.Outcome;
import io.github.markpollack.journal.tck.verify.Json;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static io.github.markpollack.journal.tck.ocsf.OcsfFixtures.CHILD;
import static io.github.markpollack.journal.tck.ocsf.OcsfFixtures.CODEX;
import static io.github.markpollack.journal.tck.ocsf.OcsfFixtures.SPAWN;
import static io.github.markpollack.journal.tck.ocsf.OcsfFixtures.all;
import static io.github.markpollack.journal.tck.ocsf.OcsfFixtures.at;
import static io.github.markpollack.journal.tck.ocsf.OcsfFixtures.child;
import static io.github.markpollack.journal.tck.ocsf.OcsfFixtures.copy;
import static io.github.markpollack.journal.tck.ocsf.OcsfFixtures.editEvents;
import static io.github.markpollack.journal.tck.ocsf.OcsfFixtures.editManifest;
import static io.github.markpollack.journal.tck.ocsf.OcsfFixtures.first;
import static io.github.markpollack.journal.tck.ocsf.OcsfFixtures.isEvent;
import static io.github.markpollack.journal.tck.ocsf.OcsfFixtures.natives;
import static io.github.markpollack.journal.tck.ocsf.OcsfFixtures.num;
import static io.github.markpollack.journal.tck.ocsf.OcsfFixtures.obj;
import static io.github.markpollack.journal.tck.ocsf.OcsfFixtures.pkg;
import static io.github.markpollack.journal.tck.ocsf.OcsfFixtures.recordIndex;
import static io.github.markpollack.journal.tck.ocsf.OcsfFixtures.str;
import static io.github.markpollack.journal.tck.ocsf.OcsfFixtures.verify;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Deliberate corruptions of a known-good core, each detected on the property that owns it. The first three are the
 * false acceptances reproduced on the Python prototype oracle (JO-01 wrong normalized usage, JO-02 wrong normalized
 * cost, JO-03 retained number turned into a boolean); the rest cover ownership by the record's own id (including an
 * id merely quoted in a result's text and identical results under two owners), populations, order, status and
 * decision values, derived versus reported attribution, absence versus zero, framing, time and retention.
 */
class OcsfOracleMutationTest {

    static final String MAIN = "main/stream.jsonl";
    static final String ROLLOUT = "main/rollout.jsonl";

    @Nested
    class ReproducedFalseAcceptances {

        @Test
        void jo01WrongNormalizedUsageAmountWithUnchangedRetainedSourceIsDefective(@TempDir Path work) {
            Path fixture = copy(CHILD, work);
            assertThat(verify(fixture).outcome("usage.values", MAIN)).isEqualTo(Outcome.VERIFIED);
            // the result line (record 27) reports run output_tokens 1040; the normalized observation says 999999
            editEvents(pkg(fixture), events -> {
                Json turn = at(events, "prompt_turn", 2, 27);
                obj(turn.member("usage_observations").elements().get(0)).members().put("output_tokens", num("999999"));
            });
            OracleReport report = verify(fixture);
            assertThat(report.outcome("population.usage", MAIN)).as("the observation still exists").isEqualTo(Outcome.VERIFIED);
            assertThat(report.outcome("usage.values", MAIN)).isEqualTo(Outcome.DEFECTIVE);
            assertThat(report.check("usage.values", MAIN).detail()).contains("27:27:4:null output_tokens VALUE");
            assertThat(report.outcome("whole_record.structure_value", MAIN)).as("the retained record is untouched").isEqualTo(Outcome.VERIFIED);
        }

        @Test
        void jo02WrongNormalizedCostAmountWithUnchangedRetainedSourceIsDefective(@TempDir Path work) {
            Path fixture = copy(CHILD, work);
            assertThat(verify(fixture).outcome("cost.values", MAIN)).isEqualTo(Outcome.VERIFIED);
            editEvents(pkg(fixture), events -> obj(at(events, "prompt_turn", 2, 27).member("cost_observation")).members().put("total_cost", num("999999")));
            OracleReport report = verify(fixture);
            assertThat(report.outcome("population.cost", MAIN)).isEqualTo(Outcome.VERIFIED);
            assertThat(report.outcome("cost.values", MAIN)).isEqualTo(Outcome.DEFECTIVE);
            assertThat(report.check("cost.values", MAIN).detail()).contains("total_cost VALUE");
            // currency and scope are checked too
            editEvents(pkg(fixture), events -> {
                Json cost = at(events, "prompt_turn", 2, 27).member("cost_observation");
                obj(cost).members().put("total_cost", num("0.0477412"));
                obj(cost).members().put("currency", str("EUR"));
            });
            assertThat(verify(fixture).check("cost.values", MAIN).detail()).contains("currency EUR != USD");
        }

        @Test
        void jo03RetainedJsonNumberTurnedIntoBooleanTrueIsDefective(@TempDir Path work) {
            Path fixture = copy(CHILD, work);
            assertThat(verify(fixture).outcome("whole_record.structure_value", MAIN)).isEqualTo(Outcome.VERIFIED);
            // the result record's retained data: duration_api_ms 16254 -> true
            editEvents(pkg(fixture), events -> obj(at(events, "prompt_turn", 2, 27).member("data")).members().put("duration_api_ms", new Json.JBool(true)));
            OracleReport report = verify(fixture);
            assertThat(report.outcome("whole_record.structure_value", MAIN)).isEqualTo(Outcome.DEFECTIVE);
            assertThat(report.check("whole_record.structure_value", MAIN).detail()).contains("record 27 KIND at $.duration_api_ms: expected number, actual boolean");
            assertThat(report.outcome("reconstruction.structure_value", MAIN)).isEqualTo(Outcome.DEFECTIVE);
            // 1 -> 1.0 is a representation difference, reported separately and never hidden
            editEvents(pkg(fixture), events -> obj(at(events, "prompt_turn", 2, 27).member("data")).members().put("duration_api_ms", num("16254.0")));
            OracleReport respelled = verify(fixture);
            assertThat(respelled.outcome("whole_record.structure_value", MAIN)).isEqualTo(Outcome.VERIFIED);
            assertThat(respelled.outcome("whole_record.representation", MAIN)).isEqualTo(Outcome.REPRESENTATION_DIFFERS);
            assertThat(respelled.check("whole_record.representation", MAIN).detail()).contains("expected spelling 16254, actual 16254.0");
        }
    }

    @Nested
    class ExactNumbers {

        @Test
        void costSpellingIsRepresentationValueIsValue(@TempDir Path work) {
            Path fixture = copy(CHILD, work);
            editEvents(pkg(fixture), events -> obj(at(events, "prompt_turn", 2, 27).member("cost_observation")).members().put("total_cost", num("0.04774120")));
            OracleReport report = verify(fixture);
            assertThat(report.outcome("cost.values", MAIN)).isEqualTo(Outcome.VERIFIED);
            assertThat(report.outcome("cost.representation", MAIN)).isEqualTo(Outcome.REPRESENTATION_DIFFERS);
            editEvents(pkg(fixture), events -> obj(at(events, "prompt_turn", 2, 27).member("cost_observation")).members().put("total_cost", num("0.0477413")));
            assertThat(verify(fixture).outcome("cost.values", MAIN)).isEqualTo(Outcome.DEFECTIVE);
        }

        @Test
        void largeIntegerAndHighPrecisionDecimalAreExact(@TempDir Path work) {
            Path fixture = copy(CODEX, work);
            // the native and the candidate both change: large and precise values round-trip exactly, off-by-one does not
            OcsfFixtures.editNative(fixture, "rollout.jsonl", lines -> {
                Json total = lines.get(15).path("payload", "info", "total_token_usage");
                obj(total).members().put("input_tokens", num("9007199254740993"));
            });
            editEvents(pkg(fixture), events -> {
                Json turn = at(events, "prompt_turn", 2, 17);
                for (Json u : turn.member("usage_observations").elements()) {
                    if ("16".equals(u.path("source_ref", "record_index").scalarText())) {
                        obj(u).members().put("input_tokens", num("9007199254740993"));
                        obj(u.member("data")).members().put("input_tokens", num("9007199254740993"));
                    }
                }
                obj(at(events, "source_record", 1, 16).member("data").path("payload", "info", "total_token_usage")).members().put("input_tokens", num("9007199254740993"));
            });
            OcsfFixtures.repin(pkg(fixture));
            OracleReport exact = verify(fixture);
            assertThat(exact.outcome("usage.values", ROLLOUT)).isEqualTo(Outcome.VERIFIED);
            assertThat(exact.outcome("source.inventory", ROLLOUT)).as("the native changed: its declared hash no longer matches, and that is reported").isEqualTo(Outcome.DEFECTIVE);
            editEvents(pkg(fixture), events -> {
                Json turn = at(events, "prompt_turn", 2, 17);
                for (Json u : turn.member("usage_observations").elements()) {
                    if ("16".equals(u.path("source_ref", "record_index").scalarText())) {
                        obj(u).members().put("input_tokens", num("9007199254740992"));
                    }
                }
            });
            OracleReport off = verify(fixture);
            assertThat(off.outcome("usage.values", ROLLOUT)).isEqualTo(Outcome.DEFECTIVE);
            assertThat(off.check("usage.values", ROLLOUT).detail()).contains("input_tokens VALUE");
        }

        @Test
        void aComponentPresentWhereTheSourceReportsNoneIsADefectAbsenceIsNotZero(@TempDir Path work) {
            Path fixture = copy(CHILD, work);
            editEvents(pkg(fixture), events -> obj(at(events, "model_response", 1, 4).member("usage_observations").elements().get(0)).members().put("reasoning_tokens", num("0")));
            OracleReport report = verify(fixture);
            assertThat(report.outcome("usage.values", MAIN)).isEqualTo(Outcome.DEFECTIVE);
            assertThat(report.check("usage.values", MAIN).detail()).contains("reasoning_tokens present (0) but not reported; absence is not zero");
        }

        @Test
        void provisionalUsageMarkedFinalIsADefect(@TempDir Path work) {
            Path fixture = copy(CODEX, work);
            editEvents(pkg(fixture), events -> {
                Json turn = at(events, "prompt_turn", 2, 17);
                for (Json u : turn.member("usage_observations").elements()) {
                    if ("13".equals(u.path("source_ref", "record_index").scalarText())) {
                        obj(u).members().put("usage_status_id", num("2"));
                        obj(u).members().put("usage_status", str("Final"));
                    }
                }
            });
            OracleReport report = verify(fixture);
            assertThat(report.outcome("usage.values", ROLLOUT)).isEqualTo(Outcome.DEFECTIVE);
            assertThat(report.check("usage.values", ROLLOUT).detail()).contains("17:13:3:null usage_status_id 2 != 1");
        }
    }

    @Nested
    class OwnershipAndPopulations {

        @Test
        void resultOwnedByAnIdQuotedInsideItsOwnTextIsADefect(@TempDir Path work) {
            Path fixture = copy(CODEX, work);
            // record 12 is call_0001aaaa's output; its text quotes call_FOREIGN0000000000000000, which a record-wide id search would find
            editEvents(pkg(fixture), events -> obj(at(events, "tool_call", 2, 12)).members().put("tool_call_uid", str("call_FOREIGN0000000000000000")));
            OracleReport report = verify(fixture);
            assertThat(report.outcome("result.owner", ROLLOUT)).isEqualTo(Outcome.DEFECTIVE);
            assertThat(report.check("result.owner", ROLLOUT).detail()).contains("record 12 names [call_0001aaaa] at its declared path, event claims call_FOREIGN0000000000000000");
            assertThat(report.check("population.results", ROLLOUT).detail()).contains("missing [12:call_0001aaaa]").contains("extra [12:call_FOREIGN0000000000000000]");
        }

        @Test
        void identicalResultsSwappedBetweenOwnersAreADefect(@TempDir Path work) {
            Path fixture = copy(CODEX, work);
            // records 26 and 27 return byte-identical content for call_0002bbbb and call_0003cccc
            editEvents(pkg(fixture), events -> {
                obj(at(events, "tool_call", 2, 26)).members().put("tool_call_uid", str("call_0003cccc"));
                obj(at(events, "tool_call", 2, 27)).members().put("tool_call_uid", str("call_0002bbbb"));
            });
            OracleReport report = verify(fixture);
            assertThat(report.outcome("result.owner", ROLLOUT)).isEqualTo(Outcome.DEFECTIVE);
            assertThat(report.check("result.owner", ROLLOUT).detail()).contains("record 26 names [call_0002bbbb]").contains("record 27 names [call_0003cccc]");
            assertThat(report.check("population.results", ROLLOUT).detail()).as("content is equal, so a content check alone would pass; ownership is by occurrence and id")
                    .contains("missing [26:call_0002bbbb, 27:call_0003cccc]").contains("extra [26:call_0003cccc, 27:call_0002bbbb]");
        }

        @Test
        void missingDuplicatedAndReorderedRowsAreDefects(@TempDir Path work) {
            Path fixture = copy(CODEX, work);
            editEvents(pkg(fixture), events -> events.remove(at(events, "tool_call", 1, 22)));
            OracleReport missing = verify(fixture);
            assertThat(missing.outcome("population.requests", ROLLOUT)).isEqualTo(Outcome.DEFECTIVE);
            assertThat(missing.check("population.requests", ROLLOUT).detail()).contains("missing [22:call_0002bbbb]");
            assertThat(missing.outcome("whole_record.coverage", ROLLOUT)).as("the request record is no longer retained whole").isEqualTo(Outcome.DEFECTIVE);
            Path other = copy(CODEX, work.resolve("dup"));
            editEvents(pkg(other), events -> events.add(OcsfFixtures.copy(at(events, "tool_call", 1, 23))));
            OracleReport dup = verify(other);
            assertThat(dup.check("population.requests", ROLLOUT).detail()).contains("duplicated [23:call_0003cccc x2]");
            assertThat(dup.outcome("event.order", "main")).as("the appended copy repeats a sequence number").isEqualTo(Outcome.DEFECTIVE);
            Path swapped = copy(CODEX, work.resolve("swap"));
            editEvents(pkg(swapped), events -> {
                int a = events.indexOf(at(events, "tool_call", 1, 22));
                int b = events.indexOf(at(events, "tool_call", 1, 23));
                Json t = events.get(a);
                events.set(a, events.get(b));
                events.set(b, t);
            });
            assertThat(verify(swapped).outcome("event.order", "main")).isEqualTo(Outcome.DEFECTIVE);
        }

        @Test
        void normalizedRecordRetainedOnlyAsFallbackIsAMissingNormalization(@TempDir Path work) {
            Path fixture = copy(CODEX, work);
            editEvents(pkg(fixture), events -> {
                Json request = at(events, "tool_call", 1, 22);
                Json fallback = OcsfFixtures.copy(at(events, "source_record", 1, 13));
                obj(fallback.member("source_ref")).members().put("record_index", num("22"));
                obj(fallback).members().put("data", request.member("data"));
                obj(fallback.member("journal")).members().put("sequence", request.path("journal", "sequence"));
                events.set(events.indexOf(request), fallback);
            });
            OracleReport report = verify(fixture);
            assertThat(report.outcome("population.requests", ROLLOUT)).isEqualTo(Outcome.DEFECTIVE);
            assertThat(report.check("whole_record.class", ROLLOUT).detail()).contains("record 22 has a normalization rule but is retained only as a source_record fallback");
        }

        @Test
        void childEventMovedIntoTheParentCoreFailsOwnershipOnBothCores(@TempDir Path work) {
            Path fixture = copy(CHILD, work);
            Json[] moved = new Json[1];
            editEvents(child(fixture), events -> moved[0] = events.remove(events.indexOf(at(events, "tool_call", 2, 21))));
            editEvents(pkg(fixture), events -> {
                obj(moved[0].member("journal")).members().put("run_uid", str("claude-child-2.1.299"));
                events.add(moved[0]);
            });
            OracleReport report = verify(fixture);
            String child = "child:" + SPAWN + "/stream.jsonl";
            assertThat(report.check("whole_record.coverage", MAIN).detail()).contains("record 21 is not owned by this core but retained here");
            assertThat(report.check("whole_record.coverage", child).detail()).contains("record 21: 0 whole-record events");
            assertThat(report.outcome("population.results", child)).isEqualTo(Outcome.DEFECTIVE);
            assertThat(report.check("population.results", MAIN).detail()).contains("extra [21:toolu_018fX6Mygf8aSE8c8bt7pyu6]");
        }
    }

    @Nested
    class ValuesAndAttribution {

        @Test
        void resultContentErrorFlagAndStatusAreExact(@TempDir Path work) {
            Path fixture = copy(CHILD, work);
            editEvents(pkg(fixture), events -> obj(at(events, "tool_call", 2, 6).member("tool_result")).members().put("data", str("done")));
            OracleReport content = verify(fixture);
            assertThat(content.outcome("result.values", MAIN)).isEqualTo(Outcome.DEFECTIVE);
            assertThat(content.check("result.values", MAIN).detail()).contains("6:toolu_0183ddwvMmvPzqAU5Jeu5hwZ content");
            Path other = copy(CHILD, work.resolve("status"));
            editEvents(pkg(other), events -> {
                Json result = at(events, "tool_call", 2, 6);
                obj(result).members().put("status_id", num("1"));
                obj(result).members().put("status_basis_id", num("1"));
                obj(result.member("tool_result")).members().put("error_reported", new Json.JBool(false));
            });
            OracleReport status = verify(other);
            assertThat(status.check("result.values", MAIN).detail()).contains("error_reported present but is_error not reported").contains("status_id/status_basis_id 1/1 != 0/3");
        }

        @Test
        void requestArgumentsAndToolKindAreExact(@TempDir Path work) {
            Path fixture = copy(CHILD, work);
            editEvents(pkg(fixture), events -> {
                Json request = at(events, "tool_call", 1, 5);
                obj(request.member("arguments")).members().put("extra", new Json.JBool(true));
                obj(request).members().put("tool_kind_id", num("6"));
            });
            OracleReport report = verify(fixture);
            assertThat(report.check("request.values", MAIN).detail()).contains("arguments EXTRA at $.extra").contains("tool_kind_id 6 !=");
        }

        @Test
        void permissionDecisionValuesAndScopeAreExact(@TempDir Path work) {
            Path fixture = copy(CHILD, work);
            editEvents(pkg(fixture), events -> {
                Json decision = at(events, "tool_call", 2, 6).member("permission_decision");
                obj(decision).members().put("decision_id", num("2"));
                obj(decision).members().put("decision", str("Denied"));
                obj(decision).members().put("reported_decision", str("deny"));
            });
            OracleReport report = verify(fixture);
            assertThat(report.outcome("decision.values", MAIN)).isEqualTo(Outcome.DEFECTIVE);
            assertThat(report.check("decision.values", MAIN).detail()).contains("decision_id 2 != 1").contains("reported_decision deny != accept");
            Path other = copy(CHILD, work.resolve("scope"));
            editEvents(pkg(other), events -> {
                Json decision = at(events, "tool_call", 2, 6).member("permission_decision");
                obj(decision).members().put("scope_id", num("4"));
                obj(decision).members().put("scope", str("Run"));
            });
            assertThat(verify(other).check("population.decisions", MAIN).detail()).contains("missing [6:1:toolu_0183ddwvMmvPzqAU5Jeu5hwZ]");
        }

        @Test
        void ambiguousOperationAttributedToOneCallIsADefect(@TempDir Path work) {
            Path fixture = copy(CODEX, work);
            // exec-0003 has two candidate calls with identical input; attributing it to the first by order is W5
            editEvents(pkg(fixture), events -> {
                Json op = at(events, "tool_call", 3, 24);
                obj(op).members().put("tool_call_uid", str("call_0002bbbb"));
                Json att = op.path("tool_operation", "attribution");
                obj(att).members().put("outcome_id", num("1"));
                obj(att).members().put("outcome", str("Unique"));
                obj(att).members().put("candidates", new Json.JArray(List.of(str("call_0002bbbb"))));
            });
            OracleReport report = verify(fixture);
            assertThat(report.outcome("operation.values", ROLLOUT)).isEqualTo(Outcome.DEFECTIVE);
            assertThat(report.check("operation.values", ROLLOUT).detail()).contains("recount: 2 candidate(s) [call_0002bbbb, call_0003cccc]");
        }

        @Test
        void derivedAssociationPresentedAsReportedIsADefect(@TempDir Path work) {
            Path fixture = copy(CODEX, work);
            editEvents(pkg(fixture), events -> {
                Json att = at(events, "tool_call", 3, 10).path("tool_operation", "attribution");
                obj(att).members().put("method_id", num("1"));
                obj(att).members().put("method", str("Reported identifier"));
            });
            OracleReport report = verify(fixture);
            assertThat(report.check("operation.values", ROLLOUT).detail()).contains("attribution outcome/method 1/1 != 1/2");
        }

        @Test
        void changedOriginalTimeOrFramingIsADefect(@TempDir Path work) {
            Path fixture = copy(CHILD, work);
            editEvents(pkg(fixture), events -> {
                obj(at(events, "model_response", 1, 4).member("metadata")).members().put("original_time", str("2026-01-01T00:00:00.000Z"));
                obj(at(events, "model_response", 1, 5).member("source_ref")).members().put("record_bytes", num("1"));
            });
            OracleReport report = verify(fixture);
            assertThat(report.outcome("time.basis", MAIN)).isEqualTo(Outcome.DEFECTIVE);
            assertThat(report.check("time.basis", MAIN).detail()).contains("record 4 reports");
            assertThat(report.check("whole_record.framing", MAIN).detail()).contains("record 5: record_bytes 1/");
        }

        @Test
        void turnStatusAndStopReasonAreExact(@TempDir Path work) {
            Path fixture = copy(CHILD, work);
            editEvents(pkg(fixture), events -> {
                Json turn = at(events, "prompt_turn", 2, 27);
                obj(turn).members().put("status_id", num("2"));
                obj(turn).members().put("stop_reason", str("max_tokens"));
            });
            assertThat(verify(fixture).check("turn.values", MAIN).detail()).contains("status_id 2 != 1").contains("stop_reason max_tokens != end_turn");
        }
    }

    @Nested
    class RetentionAndFraming {

        @Test
        void removedWholeRecordIsAGapInCoverageAndReconstruction(@TempDir Path work) {
            Path fixture = copy(CHILD, work);
            editEvents(pkg(fixture), events -> events.remove(first(events, e -> isEvent(e, "source_record", 1) && recordIndex(e) == 7)));
            OracleReport report = verify(fixture);
            assertThat(report.check("whole_record.coverage", MAIN).detail()).contains("record 7: 0 whole-record events");
            assertThat(report.check("reconstruction.coverage", MAIN).detail()).contains("no whole-record event for record(s) [7]");
        }

        @Test
        void malformedAndUndecodableNativeLinesStayInTheInventoryAndMustBeRetained(@TempDir Path work) throws Exception {
            Path fixture = copy(CHILD, work);
            Path stream = natives(fixture).resolve("stream.jsonl");
            byte[] original = Files.readAllBytes(stream);
            byte[] extra = "{\"type\":\"system\",\"subtype\":\"dup\",\"subtype\":\"again\"}\n\n".getBytes(StandardCharsets.UTF_8);
            byte[] bad = new byte[] {'{', '"', 'x', '"', ':', '"', (byte) 0xff, '"', '}', '\n'};
            byte[] all = new byte[original.length + extra.length + bad.length];
            System.arraycopy(original, 0, all, 0, original.length);
            System.arraycopy(extra, 0, all, original.length, extra.length);
            System.arraycopy(bad, 0, all, original.length + extra.length, bad.length);
            Files.write(stream, all);
            OcsfFixtures.refreeze(fixture); // the test's own labelled input is the frozen denominator
            OracleReport report = verify(fixture);
            // the strict parser did not end the inventory: records 28 (duplicate key), 29 (blank) and 30 (not UTF-8) are counted and owed
            assertThat(report.check("source.inventory", MAIN).detail()).contains("records 27 declared, artifact has 30").contains("opaque_records 0 declared, artifact has 3");
            assertThat(report.check("whole_record.coverage", MAIN).detail()).contains("record 28: 0 whole-record events").contains("record 30: 0 whole-record events");
            assertThat(report.outcome("whole_record.bytes", MAIN)).as("opaque records exist and none is retained").isEqualTo(Outcome.DEFECTIVE);
            assertThat(report.check("whole_record.bytes", MAIN).detail()).contains("record 28 (MALFORMED) not retained").contains("record 30 (UNDECODABLE) not retained");
            // the parsed records before them are still judged normally
            assertThat(report.outcome("usage.values", MAIN)).isEqualTo(Outcome.VERIFIED);
        }

        @Test
        void tamperedEventsFileIsReportedAsAReaderDefect(@TempDir Path work) {
            Path fixture = copy(CODEX, work);
            OcsfFixtures.tamperEvents(pkg(fixture), events -> obj(events.get(0)).members().put("severity_id", num("1")));
            OracleReport report = verify(fixture);
            assertThat(report.outcome("package.readable", "main")).isEqualTo(Outcome.DEFECTIVE);
            assertThat(report.check("package.readable", "main").detail()).contains("EVENTS_HASH_MISMATCH");
        }

        @Test
        void refusedPackageIsUnsupportedNotVerified(@TempDir Path work) {
            Path fixture = copy(CODEX, work);
            editManifest(pkg(fixture), run -> obj(run.get("package")).members().put("version", str("9.0.0")));
            OracleReport report = verify(fixture);
            assertThat(report.outcome("package.readable", "main")).isEqualTo(Outcome.UNSUPPORTED);
            assertThat(report.checks()).as("nothing else is judged on a refused package").hasSize(1);
        }

        @Test
        void sourceInventoryHashIsChecked(@TempDir Path work) {
            Path fixture = copy(CODEX, work);
            editManifest(pkg(fixture), run -> obj(run.member("sources").elements().get(0)).members().put("sha256", str("00")));
            assertThat(verify(fixture).check("source.inventory", ROLLOUT).detail()).contains("sha256 00 declared");
            assertThat(all(OcsfFixtures.lines(pkg(fixture).resolve("events.jsonl")), e -> true)).hasSize(39);
        }
    }
}
