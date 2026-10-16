package io.github.markpollack.journal.claude;

import io.github.markpollack.claude.agent.sdk.parsing.MessageParser;
import io.github.markpollack.claude.agent.sdk.parsing.ParsedMessage;
import io.github.markpollack.journal.raw.RawArtifacts;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Level 0 is only worth keeping if level 1 can be rebuilt from it. These tests run real captured
 * Claude Code streams (the fixtures of {@link RealStreamTokenConformanceTest}) through a live parse
 * that keeps its raw stream, then rebuild the capture from that stream file alone.
 */
class ClaudeStreamReplayTest {

    private static final String ROOT = "/fixtures/real-streams/";

    private static List<ParsedMessage> live(String fixture) throws Exception {
        MessageParser parser = new MessageParser();
        List<ParsedMessage> messages = new ArrayList<>();
        for (String line : Files.readAllLines(Path.of(ClaudeStreamReplayTest.class.getResource(ROOT + fixture + "/stream.jsonl").toURI()))) {
            try {
                messages.add(ParsedMessage.RegularMessage.of(parser.parseMessage(line), line));
            }
            catch (RuntimeException unknownType) {
                // the SDK skips message types it does not know
            }
        }
        return messages;
    }

    @ParameterizedTest
    @ValueSource(strings = {"single-result-parallel-calls", "foreground-subagent-one-result",
            "background-subagent-two-results-a", "background-subagent-two-results-b"})
    void aCaptureRebuiltFromTheRawStreamEqualsTheLiveCapture(String fixture, @TempDir Path dir) throws Exception {
        List<ParsedMessage> messages = live(fixture);
        PhaseCapture liveCapture;
        try (RawArtifacts raw = RawArtifacts.open(dir.resolve("raw"), "claude-code", "2.1.299")) {
            liveCapture = SessionLogParser.parse(messages.iterator(), "run", "prompt", null, raw);
        }
        Path stream = dir.resolve("raw/run.stream.jsonl");

        ClaudeStreamReplay.Replay replay = ClaudeStreamReplay.read(stream);

        assertThat(replay.skipped()).isEmpty();
        assertThat(replay.messages()).hasSameSizeAs(messages);
        PhaseCapture rebuilt = SessionLogParser.parse(replay.messages().iterator(), "run", "prompt");
        // ToolResultRecord.durationMs is the parser's own clock between seeing a tool call and its result
        // (arrival latency on a live run, noise when parsing stored data), not a vendor measurement, so
        // it cannot be reproduced from the stream; every other field must be identical. Roadmap Step 12.2.
        assertThat(rebuilt).usingRecursiveComparison().ignoringFieldsMatchingRegexes("toolResults.*durationMs")
                .isEqualTo(liveCapture);
    }

    @Test
    void aRunCutOffMidLineIsRebuiltFromItsWholeLinesAndThePartialLineIsReportedNotDropped(@TempDir Path dir) throws Exception {
        List<ParsedMessage> messages = live("foreground-subagent-one-result");
        try (RawArtifacts raw = RawArtifacts.open(dir.resolve("raw"), "claude-code", "2.1.299")) {
            SessionLogParser.parse(messages.iterator(), "run", "prompt", null, raw);
        }
        Path stream = dir.resolve("raw/run.stream.jsonl");
        byte[] whole = Files.readAllBytes(stream);
        // the process "dies" in the middle of a line, about 60 per cent of the way through
        int cut = (int) (whole.length * 0.6);
        while (whole[cut - 1] == '\n') {
            cut++;
        }
        Path cutStream = dir.resolve("cut.stream.jsonl");
        Files.write(cutStream, java.util.Arrays.copyOf(whole, cut));
        String text = new String(java.util.Arrays.copyOf(whole, cut), StandardCharsets.UTF_8);
        List<String> physical = new ArrayList<>(List.of(text.split("\n", -1)));
        String partial = physical.get(physical.size() - 1);
        assertThat(partial).as("the cut falls inside a line").isNotEmpty();

        ClaudeStreamReplay.Replay replay = ClaudeStreamReplay.read(cutStream);

        assertThat(replay.skipped()).hasSize(1);
        assertThat(replay.skipped().get(0).line()).isEqualTo(partial);
        assertThat(replay.skipped().get(0).seq()).isEqualTo(physical.size());
        assertThat(replay.messages()).hasSize(physical.size() - 1);
        PhaseCapture rebuilt = SessionLogParser.parse(replay.messages().iterator(), "run", "prompt");
        assertThat(rebuilt.toolUses()).isNotNull();
    }

}
