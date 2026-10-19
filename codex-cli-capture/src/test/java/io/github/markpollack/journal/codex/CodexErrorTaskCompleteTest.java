package io.github.markpollack.journal.codex;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.markpollack.journal.Journal;
import io.github.markpollack.journal.Run;
import io.github.markpollack.journal.event.LLMCallEvent;
import io.github.markpollack.journal.storage.InMemoryStorage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A real rollout (CLI 0.160.1, scrubbed by the agent-client steward) whose only failure signal is an {@code error}
 * on the {@code task_complete} record: no tool call, no {@code turn_aborted}. It used to parse as a successful call.
 */
class CodexErrorTaskCompleteTest {

    @AfterEach
    void resetJournal() {
        Journal.reset();
    }

    private static Path fixture() throws Exception {
        return Path.of(CodexErrorTaskCompleteTest.class.getResource("/fixtures/codex-error-task-complete.jsonl").toURI());
    }

    @Test
    void aTurnThatEndsWithAnErrorIsAnErrorAndKeepsTheErrorText() throws Exception {
        // oracle: the error text as the file holds it
        String expected = null;
        for (String line : Files.readAllLines(fixture())) {
            JsonNode error = new ObjectMapper().readTree(line).path("payload").path("error");
            if (error.has("message")) {
                expected = error.get("message").asText();
            }
        }
        assertThat(expected).isNotNull();

        CodexPhaseCapture capture = CodexSessionParser.parse(fixture(), "p", "q");

        assertThat(capture.isError()).isTrue();
        assertThat(capture.toolUses()).isEmpty();
        assertThat(capture.errorMessage()).isEqualTo(expected);
        assertThat(capture.model()).isEqualTo("gpt-5.4-mini");
        assertThat(capture.cliVersion()).isEqualTo("0.160.1");
        assertThat(capture.durationMs()).isEqualTo(1880L);
    }

    @Test
    void theRecordedCallSaysItFailedAndCarriesTheError() throws Exception {
        InMemoryStorage storage = new InMemoryStorage();
        Journal.configure(storage);
        CodexPhaseCapture capture = CodexSessionParser.parse(fixture(), "p", "q");
        Run run = Journal.run("exp").start();

        new CodexRunRecorder(run).recordPhase(capture);

        assertThat(storage.loadEvents("exp", run.id())).filteredOn(e -> e instanceof LLMCallEvent).singleElement()
                .satisfies(e -> assertThat(((LLMCallEvent) e).metadata()).containsEntry("isError", true)
                        .containsEntry("errorMessage", capture.errorMessage()));
    }

    @Test
    void aCleanTurnHasNoErrorMessage() throws Exception {
        Path clean = Path.of(CodexErrorTaskCompleteTest.class.getResource("/fixtures/codex-rollout.jsonl").toURI());

        CodexPhaseCapture capture = CodexSessionParser.parse(clean, "p", "q");

        assertThat(capture.isError()).isFalse();
        assertThat(capture.errorMessage()).isNull();
    }
}
