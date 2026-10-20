package io.github.markpollack.journal.claude;

import io.github.markpollack.claude.agent.sdk.parsing.MessageParser;
import io.github.markpollack.claude.agent.sdk.parsing.ParsedMessage;
import io.github.markpollack.journal.Journal;
import io.github.markpollack.journal.Run;
import io.github.markpollack.journal.storage.JsonFileStorage;
import io.github.markpollack.journal.tck.venn.Jsonl;
import io.github.markpollack.journal.tck.venn.Leaf;
import io.github.markpollack.journal.tck.venn.PathResult;
import io.github.markpollack.journal.tck.venn.Venn;
import io.github.markpollack.journal.tck.venn.VennReport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Venn v2 on a real captured Claude Code stream: the vendor's own records against the run directory that
 * {@link RunRecorder} wrote. See {@code plans/VENN-DESIGN.md} in the steward repository.
 */
class VennOnRealStreamTest {

    private static final String FIXTURE = "single-result-parallel-calls";

    @AfterEach
    void resetJournal() {
        Journal.reset();
    }

    private List<Leaf> nativeLeaves;
    private List<Leaf> canonicalLeaves;

    private VennReport report(Path dir) throws Exception {
        Path stream = Path.of(VennOnRealStreamTest.class.getResource("/fixtures/real-streams/" + FIXTURE + "/stream.jsonl").toURI());
        MessageParser parser = new MessageParser();
        List<ParsedMessage> messages = new ArrayList<>();
        for (String line : Files.readAllLines(stream)) {
            try {
                messages.add(ParsedMessage.RegularMessage.of(parser.parseMessage(line), line));
            }
            catch (RuntimeException unknownType) {
                // skipped by the SDK, as in the stream reader
            }
        }
        PhaseCapture capture = SessionLogParser.parse(messages.iterator(), "run", "prompt");
        Journal.configure(new JsonFileStorage(dir));
        Run run = Journal.run("exp").start();
        new RunRecorder(run).recordPhase(capture);
        run.finish(io.github.markpollack.journal.RunStatus.FINISHED);

        Path runDir;
        try (Stream<Path> walk = Files.walk(dir)) {
            runDir = walk.filter(p -> p.getFileName().toString().equals("events.jsonl")).findFirst().orElseThrow().getParent();
        }
        canonicalLeaves = new ArrayList<>(Jsonl.read(runDir.resolve("events.jsonl"), "@type", "vendor_event"));
        if (Files.exists(runDir.resolve("analysis.jsonl"))) {
            canonicalLeaves.addAll(Jsonl.read(runDir.resolve("analysis.jsonl"), "@type", null));
        }
        nativeLeaves = Jsonl.read(stream, "type", null);
        return Venn.compare(nativeLeaves, canonicalLeaves);
    }

    @Test
    void theContentOfEveryToolResultOfTheStreamIsInTheRunsEvents(@TempDir Path dir) throws Exception {
        VennReport r = report(dir);

        PathResult content = r.path("user", "message.content[].content").orElseThrow();
        assertThat(content.landed()).as("tool results found as typed fields").isPositive();
        assertThat(content.neither()).as("tool results missing from the events").isZero();
    }

    @Test
    void theToolResultCheckFlagsTheEventsAsTheyWereBeforeStep12_12a(@TempDir Path dir) throws Exception {
        report(dir);
        List<Leaf> withoutOutput = canonicalLeaves.stream()
                .filter(l -> !(l.kind().equals("tool_call") && l.path().equals("output"))).toList();

        VennReport r = Venn.compare(nativeLeaves, withoutOutput);

        // no typed field holds the content any more; the kept-whole source line still does (preservation, not
        // normalization), so the typed landing is what must drop to zero
        assertThat(r.path("user", "message.content[].content").orElseThrow().landed()).isZero();
    }

    /** Closed 2026-10-19 (Stage 13, package 1): the assistant's text is an {@code assistant_text} event. */
    @Test
    void theAssistantTextIsInTheEventsAsATypedField(@TempDir Path dir) throws Exception {
        report(dir);

        assertThat(canonicalLeaves).anySatisfy(l -> {
            assertThat(l.kind()).isEqualTo("custom");
            assertThat(l.path()).isEqualTo("attributes.text");
        });
    }

    /** Closed 2026-10-19 (Stage 13, package 1): the thinking signature is on the {@code thinking_block} event. */
    @Test
    void theThinkingSignatureIsInTheEventsAsATypedField(@TempDir Path dir) throws Exception {
        VennReport r = report(dir);

        PathResult signature = r.path("assistant", "message.content[].signature").orElseThrow();
        assertThat(signature.landed()).isEqualTo(signature.total());
        assertThat(signature.landedAt()).containsKey("custom attributes.signature");
    }
}
