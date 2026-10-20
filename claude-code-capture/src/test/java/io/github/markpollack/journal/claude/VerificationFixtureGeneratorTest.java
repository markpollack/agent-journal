package io.github.markpollack.journal.claude;

import io.github.markpollack.claude.agent.sdk.parsing.MessageParser;
import io.github.markpollack.claude.agent.sdk.parsing.ParsedMessage;
import io.github.markpollack.journal.Journal;
import io.github.markpollack.journal.Run;
import io.github.markpollack.journal.storage.JsonFileStorage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

/**
 * Generates the committed candidate packages the TCK's offline verification tests read
 * ({@code journal-capture-tck/src/test/resources/verify/<fixture>/journal/}): each sanitized real stream under
 * {@code fixtures/real-streams/} recorded through the production recorder into a {@link JsonFileStorage} root, with
 * the session archive off because the fixtures carry no transcript. Gated by {@code -Dverify.generate.out=<dir>};
 * rerun it whenever the producer changes what it persists, and record the producer commit in the fixture README.
 * Generation may use production code; the checks that read the result never do.
 */
class VerificationFixtureGeneratorTest {

    static final List<String> FIXTURES = List.of("single-result-parallel-calls", "foreground-subagent-one-result",
            "background-subagent-two-results-a", "background-subagent-two-results-b");

    @AfterEach
    void resetJournal() {
        Journal.reset();
    }

    @Test
    void generate() throws Exception {
        String out = System.getProperty("verify.generate.out");
        Assumptions.assumeTrue(out != null && !out.isBlank(), "no -Dverify.generate.out");
        for (String fixture : FIXTURES) {
            Journal.reset();
            Path stream = Path.of(VerificationFixtureGeneratorTest.class
                    .getResource("/fixtures/real-streams/" + fixture + "/stream.jsonl").toURI());
            Path target = Path.of(out).resolve(fixture);
            Files.createDirectories(target);
            Files.copy(stream, target.resolve("stream.jsonl"), StandardCopyOption.REPLACE_EXISTING);
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
            Journal.configure(new JsonFileStorage(target.resolve("journal")));
            Run run = Journal.run("exp").start();
            RunRecorder recorder = new RunRecorder(run).archiveSessionFiles(false);
            recorder.recordPhase(SessionLogParser.parse(messages.iterator(), "run", "prompt"));
            recorder.finish();
            System.out.println("GENERATED " + fixture + " run " + run.id() + " under " + target);
        }
        // repeated-invocation: one stream recorded twice under the same phase name into one run, so the package
        // declares two invocations with the same session and spawn ids; the verifier must refuse it, not judge it
        // as one stream
        Journal.reset();
        String fixture = "foreground-subagent-one-result";
        Path stream = Path.of(VerificationFixtureGeneratorTest.class
                .getResource("/fixtures/real-streams/" + fixture + "/stream.jsonl").toURI());
        Path target = Path.of(out).resolve("repeated-invocation");
        Files.createDirectories(target);
        Files.copy(stream, target.resolve("stream.jsonl"), StandardCopyOption.REPLACE_EXISTING);
        Journal.configure(new JsonFileStorage(target.resolve("journal")));
        Run run = Journal.run("exp").start();
        RunRecorder recorder = new RunRecorder(run).archiveSessionFiles(false);
        for (int i = 0; i < 2; i++) {
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
            recorder.recordPhase(SessionLogParser.parse(messages.iterator(), "run", "prompt"));
        }
        recorder.finish();
        System.out.println("GENERATED repeated-invocation run " + run.id() + " under " + target);
    }
}
