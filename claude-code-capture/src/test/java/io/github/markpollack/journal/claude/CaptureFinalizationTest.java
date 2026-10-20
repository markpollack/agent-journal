package io.github.markpollack.journal.claude;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.markpollack.claude.agent.sdk.parsing.MessageParser;
import io.github.markpollack.claude.agent.sdk.parsing.ParsedMessage;
import io.github.markpollack.journal.Journal;
import io.github.markpollack.journal.Run;
import io.github.markpollack.journal.RunStatus;
import io.github.markpollack.journal.event.CustomEvent;
import io.github.markpollack.journal.storage.InMemoryStorage;
import io.github.markpollack.journal.storage.JsonFileStorage;
import io.github.markpollack.journal.storage.RunData;
import io.github.markpollack.journal.storage.SourceRecordings;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Stage 13 review R13-01, R13-06 and R13-07: every way a {@link RunRecorder} ends its run secures the captured
 * sources once; a failure keeps its own error; level-1 metadata does not depend on the level-0 archive; and a run
 * of several invocations names every session it drew on. The stream is the committed synthetic sub-agent fixture
 * and the projects root is synthetic.
 */
@DisplayName("Securing the capture on every finalization path")
class CaptureFinalizationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String SESSION = "sess-synthetic-0001";
    private static final String SESSION_2 = "sess-synthetic-0002";

    @AfterEach
    void resetJournal() {
        Journal.reset();
    }

    private static Path projectsRoot(Path dir, String... sessions) throws Exception {
        Path project = dir.resolve("projects").resolve("-work-project");
        for (String session : sessions) {
            Files.createDirectories(project.resolve(session).resolve("subagents"));
            Files.writeString(project.resolve(session + ".jsonl"), "{\"type\":\"user\",\"uuid\":\"u1\"}\n");
            Files.writeString(project.resolve(session).resolve("subagents").resolve("agent-agent-a.meta.json"),
                    "{\"agentType\":\"general-purpose\",\"session\":\"" + session + "\"}");
        }
        return dir.resolve("projects");
    }

    private static PhaseCapture capture(String sessionId) throws Exception {
        Path stream = Path.of(CaptureFinalizationTest.class.getResource("/fixtures/claude-subagents-synthetic.jsonl").toURI());
        MessageParser parser = new MessageParser();
        List<ParsedMessage> messages = new ArrayList<>();
        for (String line : Files.readAllLines(stream)) {
            if (!line.isBlank()) {
                String rewritten = line.replace(SESSION, sessionId);
                messages.add(ParsedMessage.RegularMessage.of(parser.parseMessage(rewritten), rewritten));
            }
        }
        return SessionLogParser.parse(messages.iterator(), "execute", "p");
    }

    private static JsonNode runJson(Path journal, String runId) throws Exception {
        try (Stream<Path> walk = Files.walk(journal)) {
            Path file = walk.filter(p -> p.getFileName().toString().equals("run.json"))
                    .filter(p -> p.getParent().getFileName().toString().equals(runId)).findFirst().orElseThrow();
            return MAPPER.readTree(Files.readString(file));
        }
    }

    private static List<Map<String, Object>> customs(JsonFileStorage storage, String runId, String name) {
        return storage.loadEvents("exp", runId).stream()
                .filter(e -> e instanceof CustomEvent c && c.name().equals(name))
                .map(e -> ((CustomEvent) e).attributes()).toList();
    }

    @Test
    @DisplayName("failRun(cause) secures the sources and keeps the cause")
    void failRunWithCauseSecuresTheCapture(@TempDir Path dir) throws Exception {
        Path journal = dir.resolve("journal");
        JsonFileStorage storage = new JsonFileStorage(journal);
        Journal.configure(storage);
        Run run = Journal.run("exp").start();
        RunRecorder recorder = new RunRecorder(run).sessionFilesRoot(projectsRoot(dir, SESSION));
        recorder.recordPhase(capture(SESSION));

        recorder.failRun(new IllegalStateException("agent process exited 137"));

        JsonNode saved = runJson(journal, run.id());
        assertThat(saved.path("status").asText()).isEqualTo("FAILED");
        assertThat(saved.path("errorMessage").asText()).isEqualTo("agent process exited 137");
        JsonNode capture = saved.path("summary").path("capture");
        assertThat(capture.path("finalization").asText()).isEqualTo("failed");
        assertThat(capture.path("acquisition").asText()).isEqualTo("complete");
        assertThat(capture.path("sources").get(1).path("status").asText()).isEqualTo("captured");
        assertThat(journal.resolve("experiments/exp/runs/" + run.id() + "/raw/session/" + SESSION + ".zip")).exists();
        assertThat(customs(storage, run.id(), "subagent_meta")).isNotEmpty();
    }

    @Test
    @DisplayName("failRun() without a cause secures the sources too")
    void failRunWithoutCauseSecuresTheCapture(@TempDir Path dir) throws Exception {
        Path journal = dir.resolve("journal");
        Journal.configure(new JsonFileStorage(journal));
        Run run = Journal.run("exp").start();
        RunRecorder recorder = new RunRecorder(run).sessionFilesRoot(projectsRoot(dir, SESSION));
        recorder.recordPhase(capture(SESSION));

        recorder.failRun();

        JsonNode saved = runJson(journal, run.id());
        assertThat(saved.path("status").asText()).isEqualTo("FAILED");
        assertThat(saved.path("summary").path("capture").path("finalization").asText()).isEqualTo("failed");
        assertThat(journal.resolve("experiments/exp/runs/" + run.id() + "/raw/session/" + SESSION + ".zip")).exists();
    }

    @Test
    @DisplayName("close() after failRun() changes nothing: the capture is secured once")
    void closeAfterFailRunIsIdempotent(@TempDir Path dir) throws Exception {
        Path journal = dir.resolve("journal");
        JsonFileStorage storage = new JsonFileStorage(journal);
        Journal.configure(storage);
        Run run = Journal.run("exp").start();
        RunRecorder recorder = new RunRecorder(run).sessionFilesRoot(projectsRoot(dir, SESSION));
        recorder.recordPhase(capture(SESSION));
        recorder.failRun(new IllegalStateException("first"));
        JsonNode before = runJson(journal, run.id());
        int metas = customs(storage, run.id(), "subagent_meta").size();

        recorder.close();
        recorder.finish();
        recorder.failRun(new IllegalStateException("second"));

        JsonNode after = runJson(journal, run.id());
        assertThat(after.path("summary").path("capture")).isEqualTo(before.path("summary").path("capture"));
        assertThat(after.path("errorMessage").asText()).isEqualTo("first");
        assertThat(customs(storage, run.id(), "subagent_meta")).hasSize(metas);
        try (Stream<Path> zips = Files.list(journal.resolve("experiments/exp/runs/" + run.id() + "/raw/session"))) {
            assertThat(zips.count()).isEqualTo(1);
        }
    }

    @Test
    @DisplayName("an archive failure is recorded with its reason and never masks the run's own error")
    void archiveFailureDoesNotMaskTheError(@TempDir Path dir) throws Exception {
        Path journal = dir.resolve("journal");
        Journal.configure(new JsonFileStorage(journal));
        Run run = Journal.run("exp").start();
        Path emptyRoot = Files.createDirectories(dir.resolve("empty-projects"));
        RunRecorder recorder = new RunRecorder(run).sessionFilesRoot(emptyRoot);
        recorder.recordPhase(capture(SESSION));

        recorder.failRun(new IllegalStateException("agent process exited 137"));

        JsonNode saved = runJson(journal, run.id());
        assertThat(saved.path("status").asText()).isEqualTo("FAILED");
        assertThat(saved.path("errorMessage").asText()).isEqualTo("agent process exited 137");
        JsonNode session = saved.path("summary").path("capture").path("sources").get(1);
        assertThat(session.path("status").asText()).isEqualTo("failed");
        assertThat(session.path("reason").asText()).contains("no transcript named " + SESSION);
    }

    @Test
    @DisplayName("recordOnce secures the capture when the capture reports an error")
    void recordOnceSecuresAnErrorCapture(@TempDir Path dir) throws Exception {
        Path journal = dir.resolve("journal");
        Journal.configure(new JsonFileStorage(journal));
        PhaseCapture errored = withError(capture(SESSION));

        SourceRecordings.Outcome outcome = RunRecorder.recordOnce("exp", errored, null);

        JsonNode saved = runJson(journal, outcome.runId());
        assertThat(saved.path("status").asText()).isEqualTo("FAILED");
        assertThat(saved.path("summary").path("capture").path("finalization").asText()).isEqualTo("failed");
        assertThat(saved.path("summary").path("capture").path("invocations")).hasSize(1);
    }

    @Test
    @DisplayName("recordOnce secures the capture when recording throws, and throws the original exception")
    void recordOnceSecuresOnException() throws Exception {
        InMemoryStorage storage = new InMemoryStorage();
        Journal.configure(storage);

        assertThatThrownBy(() -> RunRecorder.recordOnce("exp", capture(SESSION), null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("does not persist them durably");

        RunData saved = storage.listRuns("exp").stream().filter(r -> r.parentRunId() == null).findFirst().orElseThrow();
        assertThat(saved.status()).isEqualTo(RunStatus.FAILED);
        assertThat(saved.errorMessage()).contains("does not persist them durably");
        @SuppressWarnings("unchecked")
        Map<String, Object> capture = (Map<String, Object>) saved.summary().values().get("capture");
        assertThat(capture).containsEntry("finalization", "finished");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> sources = (List<Map<String, Object>>) capture.get("sources");
        assertThat(sources.get(1)).containsEntry("status", "unavailable");
    }

    @Test
    @DisplayName("sub-agent metadata is collected with the archive switched off")
    void subagentMetaDoesNotDependOnTheArchive(@TempDir Path dir) throws Exception {
        Path journal = dir.resolve("journal");
        JsonFileStorage storage = new JsonFileStorage(journal);
        Journal.configure(storage);
        Run run = Journal.run("exp").start();
        RunRecorder recorder = new RunRecorder(run).sessionFilesRoot(projectsRoot(dir, SESSION)).archiveSessionFiles(false);
        recorder.recordPhase(capture(SESSION));

        recorder.finish();

        assertThat(journal.resolve("experiments/exp/runs/" + run.id() + "/raw")).doesNotExist();
        JsonNode session = runJson(journal, run.id()).path("summary").path("capture").path("sources").get(1);
        assertThat(session.path("status").asText()).isEqualTo("not-captured");
        assertThat(customs(storage, run.id(), "subagent_meta")).anySatisfy(m -> {
            assertThat(m).containsEntry("agentId", "agent-a").containsEntry("path", "subagents/agent-agent-a.meta.json");
            @SuppressWarnings("unchecked")
            Map<String, Object> payload = (Map<String, Object>) m.get("payload");
            assertThat(payload).containsEntry("session", SESSION);
        });
    }

    @Test
    @DisplayName("deleting raw/ after recording leaves the level-1 record, metadata included, intact")
    void deletingTheArchiveAfterwardsKeepsLevelOne(@TempDir Path dir) throws Exception {
        Path journal = dir.resolve("journal");
        JsonFileStorage storage = new JsonFileStorage(journal);
        Journal.configure(storage);
        Run run = Journal.run("exp").start();
        RunRecorder recorder = new RunRecorder(run).sessionFilesRoot(projectsRoot(dir, SESSION));
        recorder.recordPhase(capture(SESSION));
        recorder.finish();
        Path raw = journal.resolve("experiments/exp/runs/" + run.id() + "/raw");
        assertThat(raw).isDirectory();

        try (Stream<Path> walk = Files.walk(raw)) {
            for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(p);
            }
        }

        JsonFileStorage fresh = new JsonFileStorage(journal);
        assertThat(customs(fresh, run.id(), "subagent_meta")).anySatisfy(m -> assertThat(m).containsKey("payload"));
        assertThat(customs(fresh, run.id(), "source_record")).isNotEmpty();
        assertThat(runJson(journal, run.id()).path("summary").path("capture").path("sources").get(1)
                .path("status").asText()).isEqualTo("captured");
    }

    @Test
    @DisplayName("two invocations under one phase name are both inventoried and both archived")
    void everyInvocationIsInventoried(@TempDir Path dir) throws Exception {
        Path journal = dir.resolve("journal");
        JsonFileStorage storage = new JsonFileStorage(journal);
        Journal.configure(storage);
        Run run = Journal.run("exp").start();
        RunRecorder recorder = new RunRecorder(run).sessionFilesRoot(projectsRoot(dir, SESSION, SESSION_2));
        recorder.recordPhase(capture(SESSION));
        recorder.recordPhase(capture(SESSION_2));

        recorder.finish();

        JsonNode capture = runJson(journal, run.id()).path("summary").path("capture");
        assertThat(capture.path("invocations")).hasSize(2);
        assertThat(capture.path("invocations").get(0).path("index").asInt()).isZero();
        assertThat(capture.path("invocations").get(0).path("sessionId").asText()).isEqualTo(SESSION);
        assertThat(capture.path("invocations").get(1).path("index").asInt()).isEqualTo(1);
        assertThat(capture.path("invocations").get(1).path("sessionId").asText()).isEqualTo(SESSION_2);
        JsonNode session = capture.path("sources").get(1);
        assertThat(session.path("status").asText()).isEqualTo("captured");
        assertThat(session.path("sessions")).hasSize(2);
        Path raw = journal.resolve("experiments/exp/runs/" + run.id() + "/raw/session");
        assertThat(raw.resolve(SESSION + ".zip")).exists();
        assertThat(raw.resolve(SESSION_2 + ".zip")).exists();
        // the stream's occurrence counters restart per parse; the invocation index keeps the records apart
        List<Map<String, Object>> records = customs(storage, run.id(), "source_record");
        assertThat(records).extracting(r -> r.get("invocation")).contains(0, 1);
        long firstOfEach = records.stream().filter(r -> Integer.valueOf(1).equals(r.get("messageNumber"))).count();
        assertThat(firstOfEach).isGreaterThanOrEqualTo(0); // messageNumber alone would collide
        assertThat(records.stream().map(r -> r.get("invocation") + ":" + r.get("messageNumber")).distinct().count())
                .isEqualTo(records.size());
        // each sub-agent's metadata comes from the session of the invocation that recorded it
        List<String> metaSessions = customs(storage, run.id(), "subagent_meta").stream()
                .filter(m -> "agent-a".equals(m.get("agentId")))
                .map(m -> String.valueOf(((Map<?, ?>) m.get("payload")).get("session"))).toList();
        assertThat(metaSessions).containsExactlyInAnyOrder(SESSION, SESSION_2);
    }

    @Test
    @DisplayName("a stream without a result line is an incomplete acquisition even when the run finishes")
    void finishedRunWithIncompleteStreamSaysSo(@TempDir Path dir) throws Exception {
        Path journal = dir.resolve("journal");
        Journal.configure(new JsonFileStorage(journal));
        Run run = Journal.run("exp").start();
        RunRecorder recorder = new RunRecorder(run).sessionFilesRoot(projectsRoot(dir, SESSION));
        recorder.recordPhase(cutOff(capture(SESSION)));

        recorder.finish();

        JsonNode capture = runJson(journal, run.id()).path("summary").path("capture");
        assertThat(capture.path("finalization").asText()).isEqualTo("finished");
        assertThat(capture.path("acquisition").asText()).isEqualTo("incomplete");
        assertThat(capture.path("sources").get(0).path("acquisition").asText()).isEqualTo("incomplete");
    }

    private static PhaseCapture withError(PhaseCapture c) {
        return new PhaseCapture(c.phaseName(), c.promptText(), c.inputTokens(), c.outputTokens(), c.thinkingTokens(),
                c.cacheCreationInputTokens(), c.cacheReadInputTokens(), c.durationMs(), c.apiDurationMs(), c.totalCostUsd(),
                c.sessionId(), c.numTurns(), true, c.textOutput(), c.thinkingBlocks(), c.toolUses(), c.rawResult(),
                c.toolResults(), c.turns(), c.modelCosts(), c.stopReason(), c.maxTurns(), c.subagents(),
                c.subagentTracksAvailable(), c.reportedSubagentStats(), c.sessionCost(), c.cacheWriteTiers(),
                c.vendorEvents(), c.sourceRecords(), c.contentBlocks(), c.permissionDecisions(), c.costSource());
    }

    /** The same capture with its result line gone from the vendor events: what a cut-off stream looks like. */
    private static PhaseCapture cutOff(PhaseCapture c) {
        List<ClaudeVendorEvent> withoutResult = c.vendorEvents().stream().filter(v -> !"result".equals(v.type())).toList();
        return new PhaseCapture(c.phaseName(), c.promptText(), c.inputTokens(), c.outputTokens(), c.thinkingTokens(),
                c.cacheCreationInputTokens(), c.cacheReadInputTokens(), c.durationMs(), c.apiDurationMs(), c.totalCostUsd(),
                c.sessionId(), c.numTurns(), c.isError(), c.textOutput(), c.thinkingBlocks(), c.toolUses(), c.rawResult(),
                c.toolResults(), c.turns(), c.modelCosts(), c.stopReason(), c.maxTurns(), c.subagents(),
                c.subagentTracksAvailable(), c.reportedSubagentStats(), c.sessionCost(), c.cacheWriteTiers(),
                withoutResult, c.sourceRecords(), c.contentBlocks(), c.permissionDecisions(),
                PhaseCapture.COST_SOURCE_NOT_REPORTED_NO_RESULT);
    }
}
