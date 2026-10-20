package io.github.markpollack.journal.tck.verify;

import io.github.markpollack.journal.tck.verify.Check.Outcome;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static io.github.markpollack.journal.tck.verify.VerifyFixtures.all;
import static io.github.markpollack.journal.tck.verify.VerifyFixtures.attributes;
import static io.github.markpollack.journal.tck.verify.VerifyFixtures.childRun;
import static io.github.markpollack.journal.tck.verify.VerifyFixtures.copy;
import static io.github.markpollack.journal.tck.verify.VerifyFixtures.editEvents;
import static io.github.markpollack.journal.tck.verify.VerifyFixtures.editRun;
import static io.github.markpollack.journal.tck.verify.VerifyFixtures.editStream;
import static io.github.markpollack.journal.tck.verify.VerifyFixtures.first;
import static io.github.markpollack.journal.tck.verify.VerifyFixtures.isCustom;
import static io.github.markpollack.journal.tck.verify.VerifyFixtures.isEvent;
import static io.github.markpollack.journal.tck.verify.VerifyFixtures.num;
import static io.github.markpollack.journal.tck.verify.VerifyFixtures.obj;
import static io.github.markpollack.journal.tck.verify.VerifyFixtures.rootRun;
import static io.github.markpollack.journal.tck.verify.VerifyFixtures.str;
import static io.github.markpollack.journal.tck.verify.VerifyFixtures.toolCall;
import static io.github.markpollack.journal.tck.verify.VerifyFixtures.verify;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * JV-2: labelled good cases and their deliberate corruptions. Each test proves the control outcome on the untouched
 * fixture and the detection of one targeted corruption on a copy, on the property that owns it. The three false
 * acceptances reproduced on the Python checker ({@code 1} to {@code 1.0}, a duplicated tool-call row, a replaced
 * source payload at the same count) are the first three; the rest cover owners, numbers, structure and block order.
 */
class ClaudeStreamVerifierMutationTest {

    static final String FOREGROUND = "foreground-subagent-one-result";
    static final String BACKGROUND = "background-subagent-two-results-a";
    static final String PARALLEL = "single-result-parallel-calls";

    static final String TOOL_SEARCH = "toolu_0183ddwvMmvPzqAU5Jeu5hwZ";
    static final String AGENT = "toolu_01BnEuCUmZ2Vu1LpADzksmrJ";
    static final String CHILD = "child:" + AGENT;
    static final String CHILD_READ = "toolu_018fX6Mygf8aSE8c8bt7pyu6";
    static final String BACKGROUND_AGENT = "toolu_01SDdexyBphVNPU4MuZVnQyF";
    static final String PARALLEL_READ_A = "toolu_01V2rTiydtwGGD7bWuEP958C";

    @Nested
    class ReproducedFalseAcceptances {

        @Test
        void integerPersistedAsIntegralFloatIsReportedAsARepresentationDifferenceNeverHidden(@TempDir Path work) {
            // control: the committed child input already holds 1.0 for the stream's 1 (R13-09)
            Path fixture = copy(FOREGROUND, work);
            VerificationReport control = verify(fixture);
            assertThat(control.outcome("tool_calls.input_representation", CHILD)).isEqualTo(Outcome.REPRESENTATION_DIFFERS);
            assertThat(control.outcome("tool_calls.input_value", CHILD)).isEqualTo(Outcome.VERIFIED);
            // corrected spelling: representation verified too
            editEvents(childRun(fixture, AGENT), events -> obj(toolCall(events, CHILD_READ).path("input")).members().put("limit", num("1")));
            VerificationReport fixed = verify(fixture);
            assertThat(fixed.outcome("tool_calls.input_representation", CHILD)).isEqualTo(Outcome.VERIFIED);
            assertThat(fixed.outcome("tool_calls.input_value", CHILD)).isEqualTo(Outcome.VERIFIED);
        }

        @Test
        void duplicatedToolCallRowFailsMultiplicityBeforeAnyLookupById(@TempDir Path work) {
            Path fixture = copy(PARALLEL, work);
            assertThat(verify(fixture).outcome("tool_calls.multiplicity", "main")).isEqualTo(Outcome.VERIFIED);
            editEvents(rootRun(fixture), events -> events.add(VerifyFixtures.copy(toolCall(events, PARALLEL_READ_A))));
            VerificationReport report = verify(fixture);
            assertThat(report.outcome("tool_calls.multiplicity", "main")).isEqualTo(Outcome.DEFECTIVE);
            assertThat(report.check("tool_calls.multiplicity", "main").detail()).contains("duplicated [" + PARALLEL_READ_A + " x2]");
            assertThat(report.outcome("tool_calls.order", "main")).isEqualTo(Outcome.NOT_APPLICABLE);
        }

        @Test
        void sourcePayloadReplacedAtTheSameCountFailsThePayloadCheck(@TempDir Path work) {
            Path fixture = copy(FOREGROUND, work);
            assertThat(verify(fixture).outcome("source_records.payload_value", "main")).isEqualTo(Outcome.VERIFIED);
            editEvents(rootRun(fixture), events -> {
                List<Json> records = all(events, e -> isCustom(e, "source_record"));
                // the record at occurrence 14 now carries the payload of occurrence 25: same count, same types
                attributes(records.get(4)).members().put("rawJson", attributes(records.get(8)).get("rawJson"));
            });
            VerificationReport report = verify(fixture);
            assertThat(report.outcome("source_records.multiplicity", "main")).isEqualTo(Outcome.VERIFIED);
            assertThat(report.outcome("source_records.payload_value", "main")).isEqualTo(Outcome.DEFECTIVE);
            assertThat(report.outcome("source_records.payload_text", "main")).isEqualTo(Outcome.DEFECTIVE);
        }

        @Test
        void structuredResultIsDefectiveWhileStringifiedAndVerifiedOnlyWhenTheStructureIsEqual(@TempDir Path work) {
            Path fixture = copy(FOREGROUND, work);
            assertThat(verify(fixture).outcome("tool_calls.result_structured", "main")).isEqualTo(Outcome.DEFECTIVE);
            ClaudeStreamOracle oracle = ClaudeStreamOracle.read(fixture.resolve("stream.jsonl"));
            editEvents(rootRun(fixture), events -> {
                for (ClaudeStreamOracle.ToolResult r : oracle.main().toolResults) {
                    obj(toolCall(events, r.toolUseId())).members().put("output", VerifyFixtures.copy(r.content()));
                }
            });
            assertThat(verify(fixture).outcome("tool_calls.result_structured", "main")).isEqualTo(Outcome.VERIFIED);
            // one element altered inside the structure
            editEvents(rootRun(fixture), events -> obj(toolCall(events, TOOL_SEARCH).path("output").elements().get(0))
                    .members().put("tool_name", str("Other")));
            VerificationReport altered = verify(fixture);
            assertThat(altered.outcome("tool_calls.result_structured", "main")).isEqualTo(Outcome.DEFECTIVE);
            assertThat(altered.check("tool_calls.result_structured", "main").detail()).contains(TOOL_SEARCH).contains("$[0].tool_name");
        }
    }

    @Nested
    class OccurrencesAndOwners {

        @Test
        void missingToolCallRow(@TempDir Path work) {
            Path fixture = copy(PARALLEL, work);
            editEvents(rootRun(fixture), events -> events.remove(toolCall(events, PARALLEL_READ_A)));
            VerificationReport report = verify(fixture);
            assertThat(report.outcome("tool_calls.multiplicity", "main")).isEqualTo(Outcome.DEFECTIVE);
            assertThat(report.check("tool_calls.multiplicity", "main").detail()).contains("missing [" + PARALLEL_READ_A + "]");
        }

        @Test
        void duplicatedToolCallOnAChildRun(@TempDir Path work) {
            Path fixture = copy(FOREGROUND, work);
            assertThat(verify(fixture).outcome("tool_calls.multiplicity", CHILD)).isEqualTo(Outcome.VERIFIED);
            editEvents(childRun(fixture, AGENT), events -> events.add(VerifyFixtures.copy(toolCall(events, CHILD_READ))));
            VerificationReport report = verify(fixture);
            assertThat(report.outcome("tool_calls.multiplicity", CHILD)).isEqualTo(Outcome.DEFECTIVE);
            assertThat(report.outcome("tool_calls.multiplicity", "main")).isEqualTo(Outcome.VERIFIED);
        }

        @Test
        void toolCallsInADifferentOrder(@TempDir Path work) {
            Path fixture = copy(PARALLEL, work);
            assertThat(verify(fixture).outcome("tool_calls.order", "main")).isEqualTo(Outcome.VERIFIED);
            editEvents(rootRun(fixture), events -> {
                List<Json> calls = all(events, e -> isEvent(e, "tool_call"));
                int a = events.indexOf(calls.get(0));
                int b = events.indexOf(calls.get(1));
                events.set(a, calls.get(1));
                events.set(b, calls.get(0));
            });
            VerificationReport report = verify(fixture);
            assertThat(report.outcome("tool_calls.multiplicity", "main")).isEqualTo(Outcome.VERIFIED);
            assertThat(report.outcome("tool_calls.order", "main")).isEqualTo(Outcome.DEFECTIVE);
        }

        @Test
        void childToolCallMovedToTheRootIsAnOwnershipDefectOnBothRuns(@TempDir Path work) {
            Path fixture = copy(FOREGROUND, work);
            Json[] moved = new Json[1];
            editEvents(childRun(fixture, AGENT), events -> moved[0] = events.remove(events.indexOf(toolCall(events, CHILD_READ))));
            editEvents(rootRun(fixture), events -> events.add(moved[0]));
            VerificationReport report = verify(fixture);
            assertThat(report.outcome("tool_calls.ownership", "main")).isEqualTo(Outcome.DEFECTIVE);
            assertThat(report.check("tool_calls.ownership", "main").detail()).contains(CHILD_READ + " belongs to " + CHILD);
            assertThat(report.outcome("tool_calls.multiplicity", "main")).isEqualTo(Outcome.DEFECTIVE);
            assertThat(report.outcome("tool_calls.multiplicity", CHILD)).isEqualTo(Outcome.DEFECTIVE);
        }

        @Test
        void sourceRecordsSwappedBetweenOwnersKeepTheCountsAndFailOwnership(@TempDir Path work) {
            Path fixture = copy(FOREGROUND, work);
            // the child's record at occurrence 19 goes to the root, the root's record at 24 goes to the child
            Json[] fromChild = new Json[1];
            Json[] fromRoot = new Json[1];
            editEvents(childRun(fixture, AGENT), events -> {
                Json record = first(events, e -> isCustom(e, "source_record") && attributes(e).get("messageNumber").scalarText().equals("19"));
                fromChild[0] = events.remove(events.indexOf(record));
            });
            editEvents(rootRun(fixture), events -> {
                Json record = first(events, e -> isCustom(e, "source_record") && attributes(e).get("messageNumber").scalarText().equals("24"));
                fromRoot[0] = events.remove(events.indexOf(record));
                events.add(fromChild[0]);
            });
            editEvents(childRun(fixture, AGENT), events -> events.add(fromRoot[0]));
            VerificationReport report = verify(fixture);
            assertThat(report.outcome("source_records.ownership", "main")).isEqualTo(Outcome.DEFECTIVE);
            assertThat(report.outcome("source_records.ownership", CHILD)).isEqualTo(Outcome.DEFECTIVE);
            assertThat(report.outcome("source_records.multiplicity", "main")).isEqualTo(Outcome.DEFECTIVE);
            assertThat(report.outcome("source_records.multiplicity", CHILD)).isEqualTo(Outcome.DEFECTIVE);
        }

        @Test
        void sourceRecordInvocationOutsideTheInventory(@TempDir Path work) {
            Path fixture = copy(PARALLEL, work);
            assertThat(verify(fixture).outcome("source_records.invocation_reference", "main")).isEqualTo(Outcome.VERIFIED);
            editEvents(rootRun(fixture), events -> attributes(first(events, e -> isCustom(e, "source_record"))).members().put("invocation", num("7")));
            VerificationReport report = verify(fixture);
            assertThat(report.outcome("source_records.invocation_reference", "main")).isEqualTo(Outcome.DEFECTIVE);
            assertThat(report.check("source_records.invocation_reference", "main").detail()).startsWith("1 of 9");
        }

        @Test
        void sourceRecordMessageNumberPointingAtAnotherLine(@TempDir Path work) {
            Path fixture = copy(PARALLEL, work);
            // occurrence 4 renumbered to 5: a duplicate at 5, a hole at 4, and the payload no longer matches line 5
            editEvents(rootRun(fixture), events -> attributes(first(events, e -> isCustom(e, "source_record"))).members().put("messageNumber", num("5")));
            VerificationReport report = verify(fixture);
            assertThat(report.outcome("source_records.multiplicity", "main")).isEqualTo(Outcome.DEFECTIVE);
            assertThat(report.check("source_records.multiplicity", "main").detail()).contains("missing [0:4]").contains("duplicated [0:5 x2]");
            assertThat(report.outcome("source_records.payload_value", "main")).isEqualTo(Outcome.DEFECTIVE);
        }

        @Test
        void quotationOfTheFinalAnswerDoesNotSatisfyAToolResult(@TempDir Path work) {
            Path fixture = copy(PARALLEL, work);
            assertThat(verify(fixture).outcome("tool_calls.result_text", "main")).isEqualTo(Outcome.VERIFIED);
            // "done" is the result line's text and the last assistant text; it is not the Read's result
            editEvents(rootRun(fixture), events -> obj(toolCall(events, PARALLEL_READ_A)).members().put("output", str("done")));
            VerificationReport report = verify(fixture);
            assertThat(report.outcome("tool_calls.result_text", "main")).isEqualTo(Outcome.DEFECTIVE);
            assertThat(report.check("tool_calls.result_text", "main").detail()).contains(PARALLEL_READ_A);
        }

        @Test
        void unlinkedChildRun(@TempDir Path work) {
            Path fixture = copy(FOREGROUND, work);
            assertThat(verify(fixture).outcome("children.linked", "main")).isEqualTo(Outcome.VERIFIED);
            editRun(childRun(fixture, AGENT), run -> run.members().put("parentRunId", str("00000000-0000-0000-0000-000000000000")));
            VerificationReport report = verify(fixture);
            assertThat(report.outcome("children.linked", "main")).isEqualTo(Outcome.DEFECTIVE);
            // the run is no longer reachable by parentRunId from the root, so the owner has no child run at all
            assertThat(report.check("children.linked", "main").detail()).contains(CHILD + ": 0 child runs");
            assertThat(report.outcome("tool_calls.multiplicity", CHILD)).as("nothing is judged for an owner without a run").isNull();
        }

        @Test
        void childRunNotListedOnTheParentCall(@TempDir Path work) {
            Path fixture = copy(FOREGROUND, work);
            editEvents(rootRun(fixture), events -> obj(first(events, e -> isEvent(e, "llm_call")).path("metadata")).members().remove("subagents"));
            assertThat(verify(fixture).outcome("children.listed_on_parent", "main")).isEqualTo(Outcome.DEFECTIVE);
        }

        @Test
        void vendorEventRemovedForADeliveredSystemLine(@TempDir Path work) {
            Path fixture = copy(PARALLEL, work);
            assertThat(verify(fixture).outcome("vendor_events.multiplicity", "main")).isEqualTo(Outcome.VERIFIED);
            editEvents(rootRun(fixture), events -> events.remove(first(events, e -> isCustom(e, "vendor_event")
                    && "init".equals(attributes(e).get("subtype").scalarText()))));
            VerificationReport report = verify(fixture);
            assertThat(report.outcome("vendor_events.multiplicity", "main")).isEqualTo(Outcome.DEFECTIVE);
            assertThat(report.check("vendor_events.multiplicity", "main").detail()).contains("missing [0:1]");
        }

        @Test
        void droppedLineTypeNotDeclaredAsAKnownGapIsADefectNotAGap(@TempDir Path work) {
            Path fixture = copy(PARALLEL, work);
            VerificationReport control = verify(fixture);
            assertThat(control.outcome("dropped_lines.rate_limit_event", "main")).isEqualTo(Outcome.UNSUPPORTED);
            assertThat(control.outcome("capture.known_gaps", "main")).isEqualTo(Outcome.VERIFIED);
            editRun(rootRun(fixture), run -> {
                for (Json source : run.path("summary", "capture", "sources").elements()) {
                    obj(source).members().remove("knownGaps");
                }
            });
            VerificationReport report = verify(fixture);
            assertThat(report.outcome("dropped_lines.rate_limit_event", "main")).isEqualTo(Outcome.DEFECTIVE);
            assertThat(report.outcome("capture.known_gaps", "main")).isEqualTo(Outcome.DEFECTIVE);
        }

        @Test
        void inventoryCountsAndStatusesAreCheckedAgainstTheStream(@TempDir Path work) {
            Path fixture = copy(PARALLEL, work);
            editRun(rootRun(fixture), run -> {
                Json.JObject invocation = obj(run.path("summary", "capture", "invocations").elements().get(0));
                invocation.members().put("sourceRecords", num("8"));
                invocation.members().put("streamComplete", new Json.JBool(false));
                obj(run.path("summary", "capture")).members().put("acquisition", str("incomplete"));
                obj(run.path("summary", "capture")).members().put("level1Omissions", new Json.JArray(List.of()));
            });
            VerificationReport report = verify(fixture);
            assertThat(report.outcome("capture.invocations", "main")).isEqualTo(Outcome.DEFECTIVE);
            assertThat(report.check("capture.invocations", "main").detail()).contains("sourceRecords 8, expected 9").contains("streamComplete false");
            assertThat(report.outcome("capture.statuses", "main")).isEqualTo(Outcome.DEFECTIVE);
            assertThat(report.check("capture.statuses", "main").detail()).contains("acquisition incomplete").contains("partial without level1Omissions");
        }
    }

    @Nested
    class Numbers {

        private Path childWithInput(Path work, String oracleLimit, String candidateLimit) {
            Path fixture = copy(FOREGROUND, work);
            editStream(fixture, lines -> {
                for (Json line : lines) {
                    if (!"assistant".equals(ClaudeStreamOracle.textOf(line, "type"))) {
                        continue;
                    }
                    for (Json block : line.path("message", "content").elements()) {
                        if (CHILD_READ.equals(ClaudeStreamOracle.textOf(block, "id"))) {
                            obj(block.path("input")).members().put("limit", num(oracleLimit));
                        }
                    }
                }
            });
            editEvents(childRun(fixture, AGENT), events -> obj(toolCall(events, CHILD_READ).path("input")).members().put("limit", num(candidateLimit)));
            return fixture;
        }

        @Test
        void changedNumericValue(@TempDir Path work) {
            VerificationReport report = verify(childWithInput(work, "1", "2"));
            assertThat(report.outcome("tool_calls.input_value", CHILD)).isEqualTo(Outcome.DEFECTIVE);
            assertThat(report.check("tool_calls.input_value", CHILD).detail()).contains("VALUE at $.limit");
        }

        @Test
        void integerBeyondBinary64ExactRange(@TempDir Path work) {
            VerificationReport equal = verify(childWithInput(work.resolve("eq"), "9007199254740993", "9007199254740993"));
            assertThat(equal.outcome("tool_calls.input_value", CHILD)).isEqualTo(Outcome.VERIFIED);
            assertThat(equal.outcome("tool_calls.input_representation", CHILD)).isEqualTo(Outcome.VERIFIED);
            // off by one above 2^53: equal as doubles, not equal as integers
            VerificationReport offByOne = verify(childWithInput(work.resolve("off"), "9007199254740993", "9007199254740992"));
            assertThat(offByOne.outcome("tool_calls.input_value", CHILD)).isEqualTo(Outcome.DEFECTIVE);
            // the same integer spelled as a float: value kept, spelling not
            VerificationReport spelled = verify(childWithInput(work.resolve("sp"), "9007199254740993", "9007199254740993.0"));
            assertThat(spelled.outcome("tool_calls.input_value", CHILD)).isEqualTo(Outcome.VERIFIED);
            assertThat(spelled.outcome("tool_calls.input_representation", CHILD)).isEqualTo(Outcome.REPRESENTATION_DIFFERS);
        }

        @Test
        void highPrecisionDecimal(@TempDir Path work) {
            String precise = "0.1000000000000000055511151231257827021181583404541015625";
            assertThat(verify(childWithInput(work.resolve("eq"), precise, precise)).outcome("tool_calls.input_value", CHILD)).isEqualTo(Outcome.VERIFIED);
            VerificationReport rounded = verify(childWithInput(work.resolve("r"), precise, "0.1"));
            assertThat(rounded.outcome("tool_calls.input_value", CHILD)).isEqualTo(Outcome.DEFECTIVE);
            VerificationReport scale = verify(childWithInput(work.resolve("s"), "2.50", "2.5"));
            assertThat(scale.outcome("tool_calls.input_value", CHILD)).isEqualTo(Outcome.VERIFIED);
            assertThat(scale.outcome("tool_calls.input_representation", CHILD)).isEqualTo(Outcome.REPRESENTATION_DIFFERS);
        }

        @Test
        void booleanForANumberAndStringForANumber(@TempDir Path work) {
            Path fixture = copy(FOREGROUND, work);
            editEvents(childRun(fixture, AGENT), events -> obj(toolCall(events, CHILD_READ).path("input")).members().put("limit", new Json.JBool(true)));
            VerificationReport bool = verify(fixture);
            assertThat(bool.outcome("tool_calls.input_value", CHILD)).isEqualTo(Outcome.DEFECTIVE);
            assertThat(bool.check("tool_calls.input_value", CHILD).detail()).contains("KIND at $.limit");
            editEvents(childRun(fixture, AGENT), events -> obj(toolCall(events, CHILD_READ).path("input")).members().put("limit", str("1")));
            assertThat(verify(fixture).check("tool_calls.input_value", CHILD).detail()).contains("KIND at $.limit");
        }

        @Test
        void numberRespelledInsideARetainedPayloadIsRepresentationNotLoss(@TempDir Path work) {
            Path fixture = copy(FOREGROUND, work);
            editEvents(childRun(fixture, AGENT), events -> {
                Json record = first(events, e -> isCustom(e, "source_record") && attributes(e).get("messageNumber").scalarText().equals("19"));
                String raw = attributes(record).get("rawJson").text();
                assertThat(raw).contains("\"limit\":1}");
                attributes(record).members().put("rawJson", str(raw.replace("\"limit\":1}", "\"limit\":1.0}")));
            });
            VerificationReport report = verify(fixture);
            assertThat(report.outcome("source_records.payload_value", CHILD)).isEqualTo(Outcome.VERIFIED);
            assertThat(report.outcome("source_records.payload_text", CHILD)).isEqualTo(Outcome.REPRESENTATION_DIFFERS);
            editEvents(childRun(fixture, AGENT), events -> {
                Json record = first(events, e -> isCustom(e, "source_record") && attributes(e).get("messageNumber").scalarText().equals("19"));
                attributes(record).members().put("rawJson", str(attributes(record).get("rawJson").text().replace("\"limit\":1.0}", "\"limit\":10}")));
            });
            assertThat(verify(fixture).outcome("source_records.payload_value", CHILD)).isEqualTo(Outcome.DEFECTIVE);
        }
    }

    @Nested
    class Structure {

        @Test
        void unknownFieldAddedOrDroppedFromAToolInput(@TempDir Path work) {
            Path fixture = copy(PARALLEL, work);
            editEvents(rootRun(fixture), events -> obj(toolCall(events, PARALLEL_READ_A).path("input")).members().put("unknown", new Json.JBool(true)));
            VerificationReport extra = verify(fixture);
            assertThat(extra.outcome("tool_calls.input_value", "main")).isEqualTo(Outcome.DEFECTIVE);
            assertThat(extra.check("tool_calls.input_value", "main").detail()).contains("EXTRA at $.unknown");
            Path other = copy(PARALLEL, work.resolve("drop"));
            editEvents(rootRun(other), events -> obj(toolCall(events, PARALLEL_READ_A).path("input")).members().remove("file_path"));
            assertThat(verify(other).check("tool_calls.input_value", "main").detail()).contains("MISSING at $.file_path");
        }

        @Test
        void nullIsNotMissing(@TempDir Path work) {
            Path fixture = copy(PARALLEL, work);
            editEvents(rootRun(fixture), events -> obj(toolCall(events, PARALLEL_READ_A).path("input")).members().put("file_path", Json.JNull.INSTANCE));
            assertThat(verify(fixture).check("tool_calls.input_value", "main").detail()).contains("NULL_VS_VALUE at $.file_path");
        }

        @Test
        void emptyArrayIsNotAnEmptyObjectInAStructuredResult(@TempDir Path work) {
            Path fixture = copy(FOREGROUND, work);
            // the oracle's ToolSearch result becomes an empty array; the candidate stores an empty object
            editStream(fixture, lines -> {
                for (Json line : lines) {
                    if (!"user".equals(ClaudeStreamOracle.textOf(line, "type"))) {
                        continue;
                    }
                    for (Json block : line.path("message", "content").elements()) {
                        if (TOOL_SEARCH.equals(ClaudeStreamOracle.textOf(block, "tool_use_id"))) {
                            obj(block).members().put("content", new Json.JArray(List.of()));
                        }
                    }
                }
            });
            editEvents(rootRun(fixture), events -> {
                obj(toolCall(events, TOOL_SEARCH)).members().put("output", new Json.JObject(java.util.Map.of()));
                obj(toolCall(events, AGENT)).members().put("output", VerifyFixtures.copy(
                        ClaudeStreamOracle.read(fixture.resolve("stream.jsonl")).main().toolResults.get(1).content()));
            });
            VerificationReport report = verify(fixture);
            assertThat(report.outcome("tool_calls.result_structured", "main")).isEqualTo(Outcome.DEFECTIVE);
            assertThat(report.check("tool_calls.result_structured", "main").detail()).contains("KIND at $");
            editEvents(rootRun(fixture), events -> obj(toolCall(events, TOOL_SEARCH)).members().put("output", new Json.JArray(List.of())));
            assertThat(verify(fixture).outcome("tool_calls.result_structured", "main")).isEqualTo(Outcome.VERIFIED);
        }

        @Test
        void successFlagAndErrorMessageAreChecked(@TempDir Path work) {
            Path fixture = copy(PARALLEL, work);
            editEvents(rootRun(fixture), events -> {
                obj(toolCall(events, PARALLEL_READ_A)).members().put("success", new Json.JBool(false));
                obj(toolCall(events, PARALLEL_READ_A)).members().put("errorMessage", str("boom"));
            });
            VerificationReport report = verify(fixture);
            assertThat(report.outcome("tool_calls.success_flag", "main")).isEqualTo(Outcome.DEFECTIVE);
            assertThat(report.outcome("tool_calls.result_text", "main")).isEqualTo(Outcome.DEFECTIVE);
        }
    }

    @Nested
    class BlockOrderAndContent {

        @Test
        void equalKindTextBlocksSwappedBetweenMessagesOfOneOwner(@TempDir Path work) {
            Path fixture = copy(FOREGROUND, work);
            assertThat(verify(fixture).outcome("assistant_text.value_at_position", "main")).isEqualTo(Outcome.VERIFIED);
            editEvents(rootRun(fixture), events -> {
                List<Json> texts = all(events, e -> isCustom(e, "assistant_text"));
                Json a = attributes(texts.get(0)).get("text");
                attributes(texts.get(0)).members().put("text", attributes(texts.get(1)).get("text"));
                attributes(texts.get(1)).members().put("text", a);
            });
            VerificationReport report = verify(fixture);
            // the kind sequence per line is unchanged, and so is the multiset of texts: only the position check sees it
            assertThat(report.outcome("message_blocks.kind_sequence_per_line", "main")).isEqualTo(Outcome.VERIFIED);
            assertThat(report.outcome("assistant_text.value_at_position", "main")).isEqualTo(Outcome.DEFECTIVE);
            assertThat(report.check("assistant_text.value_at_position", "main").detail()).contains("14:0").contains("25:0");
        }

        @Test
        void textBlocksSwappedBetweenOwners(@TempDir Path work) {
            Path fixture = copy(BACKGROUND, work);
            String child = "child:" + BACKGROUND_AGENT;
            Json[] childText = new Json[1];
            editEvents(childRun(fixture, BACKGROUND_AGENT), events -> childText[0] = attributes(first(events, e -> isCustom(e, "assistant_text"))).get("text"));
            Json[] rootText = new Json[1];
            editEvents(rootRun(fixture), events -> {
                Json.JObject a = attributes(first(events, e -> isCustom(e, "assistant_text")));
                rootText[0] = a.get("text");
                a.members().put("text", childText[0]);
            });
            editEvents(childRun(fixture, BACKGROUND_AGENT), events -> attributes(first(events, e -> isCustom(e, "assistant_text"))).members().put("text", rootText[0]));
            VerificationReport report = verify(fixture);
            assertThat(report.outcome("assistant_text.value_at_position", "main")).isEqualTo(Outcome.DEFECTIVE);
            assertThat(report.outcome("assistant_text.value_at_position", child)).isEqualTo(Outcome.DEFECTIVE);
        }

        @Test
        void thinkingSignaturesSwappedBetweenMessages(@TempDir Path work) {
            Path fixture = copy(FOREGROUND, work);
            editEvents(rootRun(fixture), events -> {
                List<Json> thinking = all(events, e -> isCustom(e, "thinking_block"));
                Json a = attributes(thinking.get(0)).get("signature");
                attributes(thinking.get(0)).members().put("signature", attributes(thinking.get(2)).get("signature"));
                attributes(thinking.get(2)).members().put("signature", a);
            });
            VerificationReport report = verify(fixture);
            assertThat(report.outcome("thinking.value_at_position", "main")).isEqualTo(Outcome.DEFECTIVE);
            assertThat(report.outcome("message_blocks.kind_sequence_per_line", "main")).isEqualTo(Outcome.VERIFIED);
        }

        @Test
        void blockKindChangedAndToolUseReferenceChanged(@TempDir Path work) {
            Path fixture = copy(PARALLEL, work);
            editEvents(rootRun(fixture), events -> {
                Json blocks = first(events, e -> isCustom(e, "message_blocks") && attributes(e).get("messageNumber").scalarText().equals("5"));
                obj(attributes(blocks).get("blocks").elements().get(0)).members().put("toolUseId", str("toolu_other"));
                Json line4 = first(events, e -> isCustom(e, "message_blocks") && attributes(e).get("messageNumber").scalarText().equals("4"));
                obj(attributes(line4).get("blocks").elements().get(0)).members().put("kind", str("text"));
            });
            VerificationReport report = verify(fixture);
            assertThat(report.outcome("message_blocks.tool_use_reference", "main")).isEqualTo(Outcome.DEFECTIVE);
            assertThat(report.check("message_blocks.tool_use_reference", "main").detail()).contains("position 5:0");
            assertThat(report.outcome("message_blocks.kind_sequence_per_line", "main")).isEqualTo(Outcome.DEFECTIVE);
            assertThat(report.check("message_blocks.kind_sequence_per_line", "main").detail()).contains("messageNumber 4 kinds [text], expected [thinking]");
        }

        @Test
        void permissionDecisionDuplicatedOrChanged(@TempDir Path work) {
            Path fixture = copy(PARALLEL, work);
            assertThat(verify(fixture).outcome("permission_decisions.multiset", "main")).isEqualTo(Outcome.VERIFIED);
            editEvents(rootRun(fixture), events -> events.add(VerifyFixtures.copy(first(events, e -> isCustom(e, "permission_decision")))));
            assertThat(verify(fixture).check("permission_decisions.multiset", "main").detail()).contains("duplicated");
            Path other = copy(PARALLEL, work.resolve("changed"));
            editEvents(rootRun(other), events -> attributes(first(events, e -> isCustom(e, "permission_decision"))).members().put("decision", str("deny")));
            assertThat(verify(other).outcome("permission_decisions.multiset", "main")).isEqualTo(Outcome.DEFECTIVE);
        }
    }

    @Nested
    class Explanation {

        @Test
        void turnReferenceAndDurationsAreCheckedAgainstTheStream(@TempDir Path work) {
            Path fixture = copy(PARALLEL, work);
            VerificationReport control = verify(fixture);
            assertThat(control.outcome("tool_calls.turn_reference", "main")).isEqualTo(Outcome.VERIFIED);
            assertThat(control.outcome("tool_calls.duration_value", "main")).isEqualTo(Outcome.VERIFIED);
            assertThat(control.outcome("tool_timing.declared", "main")).isEqualTo(Outcome.VERIFIED);
            editEvents(rootRun(fixture), events -> {
                obj(toolCall(events, PARALLEL_READ_A)).members().put("turnId", str("msg_other"));
                obj(toolCall(events, PARALLEL_READ_A)).members().put("durationMs", num("99999"));
                Json timing = first(events, e -> isCustom(e, "tool_timing") && PARALLEL_READ_A.equals(attributes(e).get("toolUseId").text()));
                attributes(timing).members().put("durationMs", num("99999"));
            });
            VerificationReport report = verify(fixture);
            assertThat(report.outcome("tool_calls.turn_reference", "main")).isEqualTo(Outcome.DEFECTIVE);
            assertThat(report.outcome("tool_calls.duration_value", "main")).isEqualTo(Outcome.DEFECTIVE);
            assertThat(report.outcome("tool_timing.declared", "main")).isEqualTo(Outcome.DEFECTIVE);
        }

        @Test
        void aToolTimingDeclarationRemovedLeavesTheDurationUnexplained(@TempDir Path work) {
            Path fixture = copy(PARALLEL, work);
            editEvents(rootRun(fixture), events -> events.remove(first(events, e -> isCustom(e, "tool_timing"))));
            VerificationReport report = verify(fixture);
            assertThat(report.outcome("tool_calls.duration_value", "main")).isEqualTo(Outcome.VERIFIED);
            assertThat(report.outcome("tool_timing.declared", "main")).isEqualTo(Outcome.DEFECTIVE);
            assertThat(report.check("tool_timing.declared", "main").detail()).contains("1 tool call(s) with a result have no tool_timing declaration");
        }
    }
}
