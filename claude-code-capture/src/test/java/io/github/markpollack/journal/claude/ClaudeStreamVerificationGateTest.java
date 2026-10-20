package io.github.markpollack.journal.claude;

import io.github.markpollack.claude.agent.sdk.parsing.MessageParser;
import io.github.markpollack.claude.agent.sdk.parsing.ParsedMessage;
import io.github.markpollack.journal.Journal;
import io.github.markpollack.journal.Run;
import io.github.markpollack.journal.storage.JsonFileStorage;
import io.github.markpollack.journal.tck.verify.CandidatePackage;
import io.github.markpollack.journal.tck.verify.Check;
import io.github.markpollack.journal.tck.verify.ClaudeStreamOracle;
import io.github.markpollack.journal.tck.verify.ClaudeStreamVerifier;
import io.github.markpollack.journal.tck.verify.VerificationReport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The offline producer gate (JV-1/JV-3): every sanitized real stream is recorded through the production recorder
 * into a file storage root, then judged by the TCK's independent verifier against the stream. The only outcomes
 * other than VERIFIED and NOT_APPLICABLE must be the known, named ones below. They are expected defects: this test
 * pins them so they cannot disappear into a green build, and it does not make any of them a conformance pass.
 * When a producer change closes one, remove it here; when a producer change adds one, this test fails.
 */
class ClaudeStreamVerificationGateTest {

    private static final String DROPPED = "dropped_lines.rate_limit_event main UNSUPPORTED";

    /** Expected non-verified outcomes per fixture, as {@code property owner OUTCOME}. */
    private static final Map<String, List<String>> EXPECTED = Map.of(
            "single-result-parallel-calls", List.of(DROPPED),
            "foreground-subagent-one-result", List.of(DROPPED,
                    "tool_calls.result_structured main DEFECTIVE", // R13-04
                    "tool_calls.input_representation child:toolu_01BnEuCUmZ2Vu1LpADzksmrJ REPRESENTATION_DIFFERS", // R13-09
                    "source_records.invocation_reference child:toolu_01BnEuCUmZ2Vu1LpADzksmrJ DEFECTIVE", // R13-10
                    "tool_timing.declared child:toolu_01BnEuCUmZ2Vu1LpADzksmrJ DEFECTIVE"), // R13-11
            "background-subagent-two-results-a", List.of(DROPPED,
                    "tool_calls.result_structured main DEFECTIVE",
                    "tool_calls.input_representation child:toolu_01SDdexyBphVNPU4MuZVnQyF REPRESENTATION_DIFFERS",
                    "source_records.invocation_reference child:toolu_01SDdexyBphVNPU4MuZVnQyF DEFECTIVE",
                    "tool_timing.declared child:toolu_01SDdexyBphVNPU4MuZVnQyF DEFECTIVE"),
            "background-subagent-two-results-b", List.of(DROPPED,
                    "tool_calls.result_structured main DEFECTIVE",
                    "source_records.invocation_reference child:toolu_01QJNEHQfLMfTMZDvFsFadt6 DEFECTIVE"));

    @AfterEach
    void resetJournal() {
        Journal.reset();
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = { "single-result-parallel-calls", "foreground-subagent-one-result",
            "background-subagent-two-results-a", "background-subagent-two-results-b" })
    void whatTheProducerPersistsVerifiesAgainstTheStreamUpToTheKnownDefects(String fixture, @TempDir Path dir) throws Exception {
        Path stream = Path.of(ClaudeStreamVerificationGateTest.class
                .getResource("/fixtures/real-streams/" + fixture + "/stream.jsonl").toURI());
        MessageParser parser = new MessageParser();
        List<ParsedMessage> messages = new ArrayList<>();
        for (String line : Files.readAllLines(stream)) {
            if (line.isBlank()) {
                continue;
            }
            try {
                messages.add(ParsedMessage.RegularMessage.of(parser.parseMessage(line), line));
            }
            catch (RuntimeException unknownType) {
                // skipped by the SDK, as in the stream reader
            }
        }
        Journal.configure(new JsonFileStorage(dir));
        Run run = Journal.run("exp").start();
        RunRecorder recorder = new RunRecorder(run).archiveSessionFiles(false);
        recorder.recordPhase(SessionLogParser.parse(messages.iterator(), "run", "prompt"));
        recorder.finish();

        // from here on, only the TCK's neutral readers touch the files
        VerificationReport report = ClaudeStreamVerifier.verify(ClaudeStreamOracle.read(stream),
                CandidatePackage.read(CandidatePackage.findRootRun(dir)));
        List<String> notVerified = report.checks().stream()
                .filter(c -> c.outcome() != Check.Outcome.VERIFIED && c.outcome() != Check.Outcome.NOT_APPLICABLE)
                .map(c -> c.property() + " " + c.owner() + " " + c.outcome())
                .toList();
        assertThat(notVerified).as("non-verified outcomes on " + fixture + "\n" + report).containsExactlyInAnyOrderElementsOf(EXPECTED.get(fixture));
        // the judged content is real: the main thread's tool exchange and whole records were verified
        assertThat(report.outcome("tool_calls.multiplicity", "main")).isEqualTo(Check.Outcome.VERIFIED);
        assertThat(report.outcome("source_records.payload_text", "main")).isEqualTo(Check.Outcome.VERIFIED);
        assertThat(report.outcome("vendor_events.payload_text", "main")).isEqualTo(Check.Outcome.VERIFIED);
        assertThat(report.outcome("capture.invocations", "main")).isEqualTo(Check.Outcome.VERIFIED);
    }
}
