package io.github.markpollack.journal.tck.ocsf;

import io.github.markpollack.journal.tck.verify.Check.Outcome;
import io.github.markpollack.journal.tck.verify.Json;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static io.github.markpollack.journal.tck.ocsf.OcsfFixtures.CODEX;
import static io.github.markpollack.journal.tck.ocsf.OcsfFixtures.at;
import static io.github.markpollack.journal.tck.ocsf.OcsfFixtures.copy;
import static io.github.markpollack.journal.tck.ocsf.OcsfFixtures.editEvents;
import static io.github.markpollack.journal.tck.ocsf.OcsfFixtures.editExpected;
import static io.github.markpollack.journal.tck.ocsf.OcsfFixtures.editManifest;
import static io.github.markpollack.journal.tck.ocsf.OcsfFixtures.natives;
import static io.github.markpollack.journal.tck.ocsf.OcsfFixtures.num;
import static io.github.markpollack.journal.tck.ocsf.OcsfFixtures.obj;
import static io.github.markpollack.journal.tck.ocsf.OcsfFixtures.pkg;
import static io.github.markpollack.journal.tck.ocsf.OcsfFixtures.str;
import static io.github.markpollack.journal.tck.ocsf.OcsfFixtures.verify;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regressions for the three false acceptances of the 2026-10-21 independent FULL-1 review, repaired: the verification
 * denominator comes from an independently frozen expected-source manifest (FR-01), storage finalization and
 * acquisition completeness are judged apart and must agree with the source states (FR-02), and normalized injected
 * context is checked for its exact rendered content, not only its block count (FR-03). Every mutation sits beside
 * the unmodified control and names the property that detects it; a required check cannot disappear from a report
 * without blocking acceptance.
 */
class FullOneReviewRegressionTest {

    static final String PAIR = OracleFullSourceTest.PAIR;
    static final String STREAM = "main/stream.jsonl";
    static final String TRANSCRIPT = "main/transcript.jsonl";
    static final String ROLLOUT = "main/rollout.jsonl";

    @Test
    void controlVerifiesTheFrozenDenominatorAndCompleteAcquisition(@TempDir Path work) {
        OracleReport report = verify(copy(PAIR, work));
        assertThat(report.defects()).as(report.toString()).isEmpty();
        assertThat(report.outcome("source.denominator", "main")).isEqualTo(Outcome.VERIFIED);
        assertThat(report.outcome("source.expected", STREAM)).isEqualTo(Outcome.VERIFIED);
        assertThat(report.outcome("source.expected", TRANSCRIPT)).isEqualTo(Outcome.VERIFIED);
        assertThat(report.outcome("source.extent", TRANSCRIPT)).isEqualTo(Outcome.VERIFIED);
        assertThat(report.outcome("package.children", "main")).isEqualTo(Outcome.VERIFIED);
        assertThat(report.outcome("acquisition.complete", "main")).isEqualTo(Outcome.VERIFIED);
        assertThat(report.outcome("source.state", STREAM)).isEqualTo(Outcome.VERIFIED);
        assertThat(report.outcome("context.values", TRANSCRIPT)).isEqualTo(Outcome.VERIFIED);
        assertThat(report.missingRequired()).isEmpty();
        // a two-source file without final assembly is an unvalidated evidence log: verified per source, never accepted
        // as one combined execution (CombinedSourceAdmissionTest)
        OcsfReaderTool.Acceptance acceptance = OcsfReaderTool.acceptance(report);
        assertThat(acceptance.exit()).isEqualTo(OcsfReaderTool.EXIT_INCOMPLETE);
        assertThat(acceptance.reasons()).singleElement().asString().contains("combination.policy");
    }

    @Nested
    class Fr01ExpectedSourceSet {

        @Test
        void omittingAnEntireSourceIsADefectOnEveryObligationOfThatSource(@TempDir Path work) {
            Path fixture = copy(PAIR, work);
            omitTranscriptSource(fixture);
            OracleReport report = verify(fixture);
            assertThat(report.outcome("source.expected", TRANSCRIPT)).isEqualTo(Outcome.DEFECTIVE);
            assertThat(report.check("source.expected", TRANSCRIPT).detail()).startsWith("omitted: the frozen manifest expects transcript.jsonl (claude_transcript, 17 record(s), 7569 bytes)");
            assertThat(report.outcome("source.inventory", TRANSCRIPT)).isEqualTo(Outcome.DEFECTIVE);
            assertThat(report.outcome("source.extent", TRANSCRIPT)).isEqualTo(Outcome.DEFECTIVE);
            assertThat(report.check("whole_record.coverage", TRANSCRIPT).detail()).contains("record 1: 0 whole-record events").contains("record 17: 0 whole-record events");
            assertThat(report.check("population.contexts", TRANSCRIPT).detail()).contains("missing").contains("(expected 5, observed 0)");
            assertThat(report.check("population.cost", TRANSCRIPT).detail()).contains("(expected 1, observed 0)");
            assertThat(report.outcome("reconstruction.coverage", TRANSCRIPT)).isEqualTo(Outcome.DEFECTIVE);
            assertThat(report.missingRequired()).as("the denominator did not shrink").isEmpty();
            assertThat(OcsfReaderTool.acceptance(report).exit()).isEqualTo(OcsfReaderTool.EXIT_DEFECTS);
        }

        @Test
        void anExtraSourceOutsideTheFrozenSetIsADefect(@TempDir Path work) throws Exception {
            Path fixture = copy(PAIR, work);
            Files.writeString(natives(fixture).resolve("extra.jsonl"), "{\"type\":\"synthetic\"}\n");
            editManifest(pkg(fixture), run -> {
                Json.JObject extra = obj(OcsfFixtures.copy(run.member("sources").elements().get(0)));
                extra.members().put("artifact", str("extra.jsonl"));
                extra.members().put("profile", str("opaque_text"));
                run.member("sources").elements().add(extra);
            });
            OracleReport report = verify(fixture);
            assertThat(report.outcome("source.expected", "main/extra.jsonl")).isEqualTo(Outcome.DEFECTIVE);
            assertThat(report.check("source.expected", "main/extra.jsonl").detail()).contains("not part of the frozen expected-source manifest");
            assertThat(report.outcome("source.expected", TRANSCRIPT)).isEqualTo(Outcome.VERIFIED);
        }

        @Test
        void aDuplicatedDeclarationIsADefect(@TempDir Path work) {
            Path fixture = copy(PAIR, work);
            editManifest(pkg(fixture), run -> run.member("sources").elements().add(OcsfFixtures.copy(run.member("sources").elements().get(1))));
            OracleReport report = verify(fixture);
            assertThat(report.check("source.expected", TRANSCRIPT).detail()).isEqualTo("declared 2 times by the candidate");
            assertThat(report.outcome("source.expected", TRANSCRIPT)).isEqualTo(Outcome.DEFECTIVE);
        }

        @Test
        void aRenamedSourceIsBothAnOmissionAndAnUnexpectedSource(@TempDir Path work) throws Exception {
            Path fixture = copy(PAIR, work);
            editEvents(pkg(fixture), events -> {
                for (Json e : events) {
                    if ("transcript.jsonl".equals(SchemaCatalog.text(e.member("source_ref"), "artifact"))) {
                        obj(e.member("source_ref")).members().put("artifact", str("session.jsonl"));
                    }
                }
            });
            editManifest(pkg(fixture), run -> obj(run.member("sources").elements().get(1)).members().put("artifact", str("session.jsonl")));
            Files.copy(natives(fixture).resolve("transcript.jsonl"), natives(fixture).resolve("session.jsonl"));
            OracleReport report = verify(fixture);
            assertThat(report.check("source.expected", TRANSCRIPT).detail()).startsWith("omitted:");
            assertThat(report.check("source.expected", "main/session.jsonl").detail()).contains("not part of the frozen expected-source manifest");
            assertThat(report.outcome("whole_record.coverage", TRANSCRIPT)).as("the frozen artifact is still owed in full").isEqualTo(Outcome.DEFECTIVE);
        }

        @Test
        void aWrongProfileIsADefectAndTheExpectedProfileIsApplied(@TempDir Path work) {
            Path fixture = copy(PAIR, work);
            editManifest(pkg(fixture), run -> obj(run.member("sources").elements().get(1)).members().put("profile", str("claude_stream")));
            OracleReport report = verify(fixture);
            assertThat(report.check("source.expected", TRANSCRIPT).detail()).isEqualTo("declared under profile claude_stream; the frozen manifest expects claude_transcript (the expected profile is applied)");
            assertThat(report.check("population.contexts", TRANSCRIPT).detail()).as("judged with the transcript profile's expectations, not the candidate's").isEqualTo("5 expected, 5 observed");
        }

        @Test
        void aReScopedOrUnlinkedChildIsADefect(@TempDir Path work) {
            Path rescoped = copy(PAIR, work.resolve("rescoped"));
            editManifest(OcsfFixtures.child(rescoped), run -> obj(run.path("run", "source_scope")).members().put("owner_value", str("toolu_SOMEBODY_ELSE")));
            OracleReport report = verify(rescoped);
            assertThat(report.outcome("package.children", "main")).isEqualTo(Outcome.DEFECTIVE);
            assertThat(report.check("package.children", "main").detail()).contains("declares scope").contains("expected stream.jsonl.parent_tool_use_id = " + OcsfFixtures.SPAWN);

            Path unlinked = copy(PAIR, work.resolve("unlinked"));
            editManifest(pkg(unlinked), run -> obj(run.member("run")).members().put("children", new Json.JArray(List.of())));
            OracleReport r2 = verify(unlinked);
            assertThat(r2.check("package.children", "main").detail()).contains("expected child " + OcsfFixtures.SPAWN + " (stream.jsonl.parent_tool_use_id) is not linked by the candidate");

            Path unexpected = copy(PAIR, work.resolve("unexpected"));
            editExpected(unexpected, m -> m.members().put("children", new Json.JArray(List.of())));
            OracleReport r3 = verify(unexpected);
            assertThat(r3.check("package.children", "main").detail()).contains("child " + OcsfFixtures.SPAWN + " is not an expected split core");
        }

        @Test
        void aCandidateDeclaredAdmissionCannotNarrowTheFrozenDenominator(@TempDir Path work) {
            Path fixture = copy(PAIR, work);
            editEvents(pkg(fixture), events -> events.removeIf(e -> "transcript.jsonl".equals(SchemaCatalog.text(e.member("source_ref"), "artifact")) && OcsfFixtures.recordIndex(e) > 14));
            editManifest(pkg(fixture), run -> obj(run.member("sources").elements().get(1)).members().put("admit_response_uid", str("msg_011Cg2aRQ1iZf8BxQVUnD2jR")));
            OracleReport report = verify(fixture);
            assertThat(report.outcome("source.admission", TRANSCRIPT)).isEqualTo(Outcome.DEFECTIVE);
            assertThat(report.check("whole_record.coverage", TRANSCRIPT).detail()).contains("record 15: 0 whole-record events").contains("record 17: 0 whole-record events");
            assertThat(report.check("population.contexts", TRANSCRIPT).detail()).contains("17:3:cost-state");
        }

        @Test
        void aWrongRunUidIsADefect(@TempDir Path work) {
            Path fixture = copy(PAIR, work);
            editExpected(fixture, m -> m.members().put("run_uid", str("some-other-run")));
            OracleReport report = verify(fixture);
            assertThat(report.withOutcome(Outcome.DEFECTIVE)).extracting(c -> c.property()).contains("source.denominator");
        }

        @Test
        void anUnavailableOrUnfrozenNativeArtifactIsUnresolvedAndBlocksAcceptance(@TempDir Path work) throws Exception {
            Path missing = copy(PAIR, work.resolve("missing"));
            Files.delete(natives(missing).resolve("transcript.jsonl"));
            OracleReport report = verify(missing);
            assertThat(report.outcome("source.available", TRANSCRIPT)).isEqualTo(Outcome.UNRESOLVED);
            assertThat(report.defects()).isEmpty();
            assertThat(OcsfReaderTool.acceptance(report).exit()).isEqualTo(OcsfReaderTool.EXIT_INCOMPLETE);

            Path unfrozen = copy(PAIR, work.resolve("unfrozen"));
            Files.writeString(natives(unfrozen).resolve("transcript.jsonl"), Files.readString(natives(unfrozen).resolve("transcript.jsonl")) + "{\"type\":\"late\"}\n");
            OracleReport r2 = verify(unfrozen);
            assertThat(r2.outcome("source.available", TRANSCRIPT)).isEqualTo(Outcome.UNRESOLVED);
            assertThat(r2.check("source.available", TRANSCRIPT).detail()).contains("not the frozen expected source").contains("records 18 != frozen 17");
            assertThat(r2.check("whole_record.coverage", TRANSCRIPT)).as("nothing is judged against an unfrozen artifact").isNull();
            assertThat(OcsfReaderTool.acceptance(r2).exit()).isEqualTo(OcsfReaderTool.EXIT_INCOMPLETE);
        }

        @Test
        void withoutAFrozenManifestTheCandidateDeclaredDenominatorIsUnresolvedAndNeverAccepted(@TempDir Path work) throws Exception {
            Path fixture = copy(PAIR, work);
            Files.delete(natives(fixture).resolve(ExpectedSources.FILE_NAME));
            OracleReport report = verify(fixture);
            assertThat(report.defects()).isEmpty();
            assertThat(report.outcome("source.denominator", "main")).isEqualTo(Outcome.UNRESOLVED);
            assertThat(report.check("source.denominator", "main").detail()).contains("the denominator is the candidate's own declaration");
            assertThat(report.check("source.extent", TRANSCRIPT)).isNull();
            assertThat(report.outcome("whole_record.coverage", TRANSCRIPT)).as("inspection still judges what is declared").isEqualTo(Outcome.VERIFIED);
            assertThat(OcsfReaderTool.acceptance(report).exit()).isEqualTo(OcsfReaderTool.EXIT_INCOMPLETE);
            // and the omission the review reproduced is now at least not silently accepted
            omitTranscriptSource(fixture);
            OracleReport omitted = verify(fixture);
            assertThat(omitted.check("whole_record.coverage", TRANSCRIPT)).as("without a frozen manifest the omitted source is invisible, which is why the manifest is required").isNull();
            assertThat(OcsfReaderTool.acceptance(omitted).exit()).isEqualTo(OcsfReaderTool.EXIT_INCOMPLETE);
        }

        @Test
        void aRequiredCheckMissingFromTheReportBlocksAcceptance(@TempDir Path work) {
            // the pair is an unvalidated evidence log (combination.policy UNRESOLVED, judged by CombinedSourceAdmissionTest);
            // without that check the report isolates the required-check rule this test is about
            OracleReport report = verify(copy(PAIR, work)).filtered(c -> !"combination.policy".equals(c.property()));
            assertThat(OcsfReaderTool.acceptance(report).exit()).isEqualTo(OcsfReaderTool.EXIT_OK);
            assertThat(report.required()).contains("whole_record.coverage [" + TRANSCRIPT + "]", "acquisition.complete [main]", "source.expected [" + TRANSCRIPT + "]");
            for (String property : List.of("whole_record.coverage", "population.contexts", "context.values", "reconstruction.coverage", "source.state", "source.inventory")) {
                OracleReport without = report.without(property, TRANSCRIPT);
                assertThat(without.defects()).isEmpty();
                OcsfReaderTool.Acceptance a = OcsfReaderTool.acceptance(without);
                assertThat(a.exit()).as(property).isEqualTo(OcsfReaderTool.EXIT_INCOMPLETE);
                assertThat(a.reasons()).contains("required check missing from the report: " + property + " [" + TRANSCRIPT + "]");
            }
            assertThat(OcsfReaderTool.acceptance(report.without("acquisition.complete", "main")).exit()).isEqualTo(OcsfReaderTool.EXIT_INCOMPLETE);
            assertThat(OcsfReaderTool.acceptance(report.without("package.children", "main")).exit()).as("package.children is not in the required set; the source set checks are").isEqualTo(OcsfReaderTool.EXIT_OK);
        }
    }

    @Nested
    class Fr02FinalizationVersusAcquisition {

        @Test
        void aCompletedPackageWithAnOpenSourceIsAnInconsistencyDefect(@TempDir Path work) {
            Path fixture = copy(PAIR, work);
            openStreamSourceInsideCompletedPackage(fixture);
            OracleReport report = verify(fixture);
            assertThat(report.outcome("package.state", "main")).as("storage finalization is what the label says").isEqualTo(Outcome.VERIFIED);
            assertThat(report.outcome("acquisition.complete", "main")).isEqualTo(Outcome.DEFECTIVE);
            assertThat(report.check("acquisition.complete", "main").detail()).contains("stream.jsonl: open source (state RECORDING, final false) inside a COMPLETED package");
            assertThat(report.outcome("source.state", STREAM)).isEqualTo(Outcome.DEFECTIVE);
            assertThat(report.outcome("source.extent", STREAM)).isEqualTo(Outcome.UNSUPPORTED);
            assertThat(OcsfReaderTool.acceptance(report).exit()).isEqualTo(OcsfReaderTool.EXIT_DEFECTS);
        }

        @Test
        void inconsistentStateLabelsAreDefects(@TempDir Path work) {
            Path tail = copy(PAIR, work.resolve("tail"));
            editManifest(pkg(tail), run -> obj(run.member("sources").elements().get(0)).members().put("incomplete_tail_record", num("27")));
            OracleReport report = verify(tail);
            assertThat(report.check("acquisition.complete", "main").detail()).contains("declared acquisition status 'complete' but the source states derive 'incomplete'");
            assertThat(report.check("source.state", STREAM).detail()).contains("incomplete_tail_record 27 but the artifact's last record is PARSED with ending LF")
                    .contains("is not retained as a parse_status 5");

            Path how = copy(PAIR, work.resolve("how"));
            editManifest(pkg(how), run -> obj(run.member("sources").elements().get(0).member("ended")).members().put("how", str("INTERRUPTED")));
            OracleReport r2 = verify(how);
            assertThat(r2.check("acquisition.complete", "main").detail()).contains("state ENDED with ended.how INTERRUPTED is inconsistent");
            assertThat(r2.outcome("acquisition.complete", "main")).isEqualTo(Outcome.DEFECTIVE);

            Path status = copy(PAIR, work.resolve("status"));
            editManifest(pkg(status), run -> obj(run.path("recording", "acquisition")).members().put("status", str("incomplete")));
            assertThat(verify(status).check("acquisition.complete", "main").detail()).contains("declared acquisition status 'incomplete' but the source states derive 'complete'");
        }

        @Test
        void anHonestlyInterruptedSourceIsPreservedButNotAcceptedAsComplete(@TempDir Path work) {
            Path fixture = copy(PAIR, work);
            editManifest(pkg(fixture), run -> {
                Json.JObject stream = obj(run.member("sources").elements().get(0));
                stream.members().put("state", str("INTERRUPTED"));
                obj(stream.member("ended")).members().put("how", str("INTERRUPTED"));
                Json.JObject acquisition = obj(run.path("recording", "acquisition"));
                acquisition.members().put("status", str("incomplete"));
                acquisition.members().put("detail", new Json.JArray(List.of(str("stream.jsonl: interrupted after 27 record(s)"))));
            });
            OracleReport report = verify(fixture);
            assertThat(report.defects()).as(report.toString()).isEmpty();
            assertThat(report.outcome("whole_record.coverage", STREAM)).isEqualTo(Outcome.VERIFIED);
            assertThat(report.outcome("reconstruction.coverage", STREAM)).isEqualTo(Outcome.VERIFIED);
            assertThat(report.outcome("acquisition.complete", "main")).isEqualTo(Outcome.UNSUPPORTED);
            assertThat(report.outcome("source.extent", STREAM)).isEqualTo(Outcome.UNSUPPORTED);
            assertThat(OcsfReaderTool.acceptance(report).exit()).isEqualTo(OcsfReaderTool.EXIT_INCOMPLETE);
        }

        @Test
        void anArtifactThatEndsInsideARecordCannotBeDeclaredWholeByEof(@TempDir Path work) throws Exception {
            Path fixture = copy(PAIR, work);
            Path stream = natives(fixture).resolve("stream.jsonl");
            byte[] all = Files.readAllBytes(stream);
            Files.write(stream, java.util.Arrays.copyOf(all, all.length - 20)); // cut inside the last record, no terminator
            OcsfFixtures.refreeze(fixture);
            OracleReport report = verify(fixture);
            assertThat(report.outcome("source.state", STREAM)).isEqualTo(Outcome.DEFECTIVE);
            assertThat(report.check("source.state", STREAM).detail()).contains("the artifact ends inside record 27 (MALFORMED, no terminator) but the source is declared ended by EOF with no incomplete tail");
        }

        @Test
        void anEmptySourceInventoryIsNotACompleteCapture(@TempDir Path work) throws Exception {
            Path fixture = copy(PAIR, work);
            editEvents(pkg(fixture), List::clear);
            editManifest(pkg(fixture), run -> {
                run.members().put("sources", new Json.JArray(List.of()));
                obj(run.member("run")).members().put("children", new Json.JArray(List.of()));
                obj(run.path("recording", "acquisition")).members().put("status", str("no sources declared"));
            });
            OracleReport report = verify(fixture);
            assertThat(report.outcome("acquisition.complete", "main")).isEqualTo(Outcome.UNSUPPORTED);
            assertThat(report.check("acquisition.complete", "main").detail()).contains("no source declared: nothing was acquired");
            assertThat(report.check("source.expected", STREAM).detail()).startsWith("omitted:");
            assertThat(OcsfReaderTool.acceptance(report).exit()).isEqualTo(OcsfReaderTool.EXIT_DEFECTS);
            Files.delete(natives(fixture).resolve(ExpectedSources.FILE_NAME));
            OracleReport bare = verify(fixture);
            assertThat(bare.defects()).isEmpty();
            assertThat(bare.outcome("acquisition.complete", "main")).isEqualTo(Outcome.UNSUPPORTED);
            assertThat(OcsfReaderTool.acceptance(bare).exit()).isEqualTo(OcsfReaderTool.EXIT_INCOMPLETE);
        }

        @Test
        void anInterruptedPackageIsNotAcceptedAndAFailedOneIsUnresolved(@TempDir Path work) {
            Path interrupted = copy(PAIR, work.resolve("interrupted"));
            editManifest(pkg(interrupted), run -> {
                obj(run.member("package")).members().put("state", str("INTERRUPTED"));
                obj(run.member("recording")).members().put("state", str("INTERRUPTED"));
            });
            OracleReport report = verify(interrupted);
            assertThat(report.outcome("acquisition.complete", "main")).isEqualTo(Outcome.UNSUPPORTED);
            assertThat(OcsfReaderTool.acceptance(report).exit()).isEqualTo(OcsfReaderTool.EXIT_INCOMPLETE);
            Path failed = copy(PAIR, work.resolve("failed"));
            editManifest(pkg(failed), run -> {
                obj(run.member("package")).members().put("state", str("FAILED"));
                obj(run.member("package")).members().put("events_jsonl_sha256", Json.JNull.INSTANCE);
            });
            assertThat(verify(failed).outcome("acquisition.complete", "main")).isEqualTo(Outcome.UNRESOLVED);
        }
    }

    @Nested
    class Fr03ContextContent {

        @Test
        void changedRenderedTextIsAContextValueDefectWhileTheRawRecordIsIntact(@TempDir Path work) {
            Path fixture = copy(PAIR, work);
            corruptContextText(fixture);
            OracleReport report = verify(fixture);
            assertThat(report.outcome("context.values", TRANSCRIPT)).isEqualTo(Outcome.DEFECTIVE);
            assertThat(report.check("context.values", TRANSCRIPT).detail()).contains("4:attachment/environment block 0 text differs from the source's rendered content");
            assertThat(report.outcome("whole_record.structure_value", TRANSCRIPT)).isEqualTo(Outcome.VERIFIED);
            assertThat(OcsfReaderTool.acceptance(report).exit()).isEqualTo(OcsfReaderTool.EXIT_DEFECTS);
        }

        @Test
        void absentExtraWrongKindAndWrongRoleBlocksAreEachNamed(@TempDir Path work) {
            Path fixture = copy(PAIR, work);
            editEvents(pkg(fixture), events -> {
                Json env = at(events, "transcript.jsonl", "session_context", 2, 4);
                obj(env.member("content_blocks").elements().get(0)).members().put("block_type", str("string"));
                obj(env.member("content_blocks").elements().get(0)).members().remove("text");
                Json model = at(events, "transcript.jsonl", "session_context", 2, 5);
                model.member("content_blocks").elements().add(OcsfFixtures.copy(model.member("content_blocks").elements().get(0)));
                Json reminder = at(events, "transcript.jsonl", "session_context", 2, 15);
                obj(reminder).members().put("rendered_role", str("user"));
                obj(reminder.member("content_blocks").elements().get(0).member("data")).members().put("content", str("changed inside data"));
            });
            String detail = verify(fixture).check("context.values", TRANSCRIPT).detail();
            assertThat(detail).contains("4:attachment/environment block 0 block_type string != rendered")
                    .contains("4:attachment/environment block 0 text differs from the source's rendered content (83 chars at source, absent normalized)")
                    .contains("5:attachment/model content_blocks 2 != 1")
                    .contains("15:attachment/total_tokens_reminder rendered_role user != system")
                    .contains("15:attachment/total_tokens_reminder block 0 data VALUE at $.content");
        }

        @Test
        void crossOwnerSwapOfEqualCountBlocksIsDetectedOnBothOwners(@TempDir Path work) {
            Path fixture = copy(PAIR, work);
            editEvents(pkg(fixture), events -> {
                Json env = at(events, "transcript.jsonl", "session_context", 2, 4);
                Json model = at(events, "transcript.jsonl", "session_context", 2, 5);
                Json blocks = env.member("content_blocks");
                obj(env).members().put("content_blocks", model.member("content_blocks"));
                obj(model).members().put("content_blocks", blocks);
            });
            String detail = verify(fixture).check("context.values", TRANSCRIPT).detail();
            assertThat(detail).contains("4:attachment/environment block 0 text differs").contains("5:attachment/model block 0 text differs");
        }

        @Test
        void codexDeveloperContextTextOrderAndCountAreExact(@TempDir Path work) {
            Path control = copy(CODEX, work.resolve("control"));
            OracleReport c = verify(control);
            assertThat(c.outcome("context.values", ROLLOUT)).isEqualTo(Outcome.VERIFIED);
            assertThat(c.check("population.contexts", ROLLOUT).detail()).isEqualTo("7 expected, 7 observed");

            Path reordered = copy(CODEX, work.resolve("reordered"));
            editEvents(pkg(reordered), events -> {
                Json dev = at(events, "session_context", 2, 32); // two input_text blocks
                List<Json> blocks = new ArrayList<>(dev.member("content_blocks").elements());
                Json first = blocks.get(0);
                Json second = blocks.get(1);
                obj(first).members().put("block_index", num("1"));
                obj(second).members().put("block_index", num("0"));
                obj(dev).members().put("content_blocks", new Json.JArray(List.of(second, first)));
            });
            String detail = verify(reordered).check("context.values", ROLLOUT).detail();
            assertThat(detail).contains("32:response_item/message/developer block 0 text differs").contains("32:response_item/message/developer block 1 text differs");

            Path swapped = copy(CODEX, work.resolve("swapped"));
            editEvents(pkg(swapped), events -> {
                Json a = at(events, "session_context", 2, 32);
                Json b = at(events, "session_context", 2, 33);
                Json keep = a.member("content_blocks");
                obj(a).members().put("content_blocks", b.member("content_blocks"));
                obj(b).members().put("content_blocks", keep);
            });
            String swappedDetail = verify(swapped).check("context.values", ROLLOUT).detail();
            assertThat(swappedDetail).contains("32:response_item/message/developer content_blocks 1 != 2").contains("33:response_item/message/developer content_blocks 2 != 1");

            Path wrongKind = copy(CODEX, work.resolve("kind"));
            editEvents(pkg(wrongKind), events -> obj(at(events, "session_context", 2, 33).member("content_blocks").elements().get(0)).members().put("block_type", str("output_text")));
            assertThat(verify(wrongKind).check("context.values", ROLLOUT).detail()).contains("33:response_item/message/developer block 0 block_type output_text != input_text");
        }
    }

    // ------------------------------------------------------------------------------------------ the mutations

    static void omitTranscriptSource(Path fixture) {
        editEvents(pkg(fixture), events -> events.removeIf(e -> "transcript.jsonl".equals(SchemaCatalog.text(e.member("source_ref"), "artifact"))));
        OcsfFixtures.editDeclarations(pkg(fixture), decls -> decls.removeIf(d -> {
            String uid = SchemaCatalog.text(d.member("declaration"), "uid");
            return "source:transcript.jsonl".equals(uid) || "source_end:transcript.jsonl".equals(uid) || uid.startsWith("contract:claude-code/session-transcript");
        }));
        editManifest(pkg(fixture), run -> {
            List<Json> sources = run.member("sources").elements();
            sources.removeIf(s -> "transcript.jsonl".equals(SchemaCatalog.text(s, "artifact")));
            Map<String, Json> contracts = obj(run.path("shared", "contracts")).members();
            contracts.keySet().removeIf(k -> k.contains("session-transcript"));
        });
    }

    static void openStreamSourceInsideCompletedPackage(Path fixture) {
        editManifest(pkg(fixture), run -> {
            Json.JObject stream = obj(run.member("sources").elements().get(0));
            stream.members().put("final", new Json.JBool(false));
            stream.members().put("state", str("RECORDING"));
            stream.members().remove("sha256");
            stream.members().remove("ended");
            Json.JObject acquisition = obj(run.path("recording", "acquisition"));
            acquisition.members().put("status", str("incomplete"));
            acquisition.members().put("detail", new Json.JArray(List.of(str("stream.jsonl: recording"))));
        });
    }

    static void corruptContextText(Path fixture) {
        editEvents(pkg(fixture), events -> {
            Json env = at(events, "transcript.jsonl", "session_context", 2, 4); // attachment/environment, one rendered block
            obj(env.member("content_blocks").elements().get(0)).members().put("text", str("UNRELATED TEXT THAT THE SOURCE NEVER RENDERED"));
        });
    }
}
