package io.github.markpollack.journal.claude;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.markpollack.claude.agent.sdk.parsing.MessageParser;
import io.github.markpollack.claude.agent.sdk.parsing.ParsedMessage;
import io.github.markpollack.journal.Journal;
import io.github.markpollack.journal.Run;
import io.github.markpollack.journal.storage.JsonFileStorage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Stage 13, package 1: when a run ends, its level-0 session files are archived by default, the sub-agents'
 * {@code .meta.json} files are kept whole, and {@code run.json} names every source with its status and checksum.
 * The projects root is synthetic; the stream is the committed synthetic sub-agent fixture.
 */
class DefaultOnArchiveTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String SESSION = "sess-synthetic-0001";

    @AfterEach
    void resetJournal() {
        Journal.reset();
    }

    private static Path projectsRoot(Path dir) throws Exception {
        Path project = dir.resolve("projects").resolve("-work-project");
        Files.createDirectories(project.resolve(SESSION).resolve("subagents"));
        Files.writeString(project.resolve(SESSION + ".jsonl"), "{\"type\":\"user\",\"uuid\":\"u1\"}\n");
        Files.writeString(project.resolve(SESSION).resolve("subagents").resolve("agent-agent-a.meta.json"),
                "{\"agentType\":\"general-purpose\",\"description\":\"read a file\",\"spawnDepth\":1,\"finished\":true}");
        return dir.resolve("projects");
    }

    private static PhaseCapture capture() throws Exception {
        Path stream = Path.of(DefaultOnArchiveTest.class.getResource("/fixtures/claude-subagents-synthetic.jsonl").toURI());
        MessageParser parser = new MessageParser();
        List<ParsedMessage> messages = new ArrayList<>();
        for (String line : Files.readAllLines(stream)) {
            if (!line.isBlank()) {
                messages.add(ParsedMessage.RegularMessage.of(parser.parseMessage(line), line));
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

    @Test
    void theSessionFilesAreArchivedAndNamedInRunJsonWhenTheRunFinishes(@TempDir Path dir) throws Exception {
        Path journal = dir.resolve("journal");
        Journal.configure(new JsonFileStorage(journal));
        Run run = Journal.run("exp").start();
        RunRecorder recorder = new RunRecorder(run).sessionFilesRoot(projectsRoot(dir));

        recorder.recordPhase(capture());
        recorder.finish();

        Path zip = journal.resolve("experiments/exp/runs/" + run.id() + "/raw/session/" + SESSION + ".zip");
        assertThat(zip).exists();
        assertThat(journal.resolve("experiments/exp/runs/" + run.id() + "/raw/manifest.json")).exists();

        JsonNode capture = runJson(journal, run.id()).path("summary").path("capture");
        assertThat(capture.path("cli").asText()).isEqualTo("claude-code");
        assertThat(capture.path("parserInput").asText()).isEqualTo("sdk-stream");
        assertThat(capture.path("status").asText()).isEqualTo("complete");
        JsonNode sources = capture.path("sources");
        assertThat(sources).hasSize(2);
        assertThat(sources.get(0).path("kind").asText()).isEqualTo("live-stream");
        assertThat(sources.get(0).path("status").asText()).isEqualTo("preserved");
        assertThat(sources.get(0).path("sourceRecords").asInt()).isPositive();
        JsonNode session = sources.get(1);
        assertThat(session.path("kind").asText()).isEqualTo("session-files");
        assertThat(session.path("status").asText()).isEqualTo("captured");
        assertThat(session.path("manifest").asText()).isEqualTo("raw/manifest.json");
        String sha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(zip)));
        assertThat(session.path("entries").get(0).path("sha256").asText()).isEqualTo(sha);
        assertThat(session.path("entries").get(0).path("path").asText()).isEqualTo("session/" + SESSION + ".zip");
    }

    @Test
    void eachSubagentsMetaFileIsKeptWholeWithItsOwner(@TempDir Path dir) throws Exception {
        Path journal = dir.resolve("journal");
        JsonFileStorage storage = new JsonFileStorage(journal);
        Journal.configure(storage);
        Run run = Journal.run("exp").start();
        RunRecorder recorder = new RunRecorder(run).sessionFilesRoot(projectsRoot(dir));

        recorder.recordPhase(capture());
        recorder.finish();

        var metas = storage.loadEvents("exp", run.id()).stream()
                .filter(e -> e instanceof io.github.markpollack.journal.event.CustomEvent c && c.name().equals("subagent_meta"))
                .map(e -> ((io.github.markpollack.journal.event.CustomEvent) e).attributes()).toList();
        assertThat(metas).isNotEmpty();
        assertThat(metas).anySatisfy(m -> {
            assertThat(m).containsEntry("agentId", "agent-a").containsEntry("path", "subagents/agent-agent-a.meta.json");
            @SuppressWarnings("unchecked")
            var payload = (java.util.Map<String, Object>) m.get("payload");
            assertThat(payload).containsEntry("agentType", "general-purpose").containsEntry("spawnDepth", 1);
        });
        // a sub-agent whose meta file is absent is recorded with the reason, never invented
        assertThat(metas).anySatisfy(m -> assertThat(m).containsEntry("agentId", "agent-b").containsKey("reason"));
    }

    @Test
    void aRunThatEndsWithoutAFinishStillArchivesAndSaysTheCaptureWasInterrupted(@TempDir Path dir) throws Exception {
        Path journal = dir.resolve("journal");
        Journal.configure(new JsonFileStorage(journal));
        Run run = Journal.run("exp").start();
        RunRecorder recorder = new RunRecorder(run).sessionFilesRoot(projectsRoot(dir));
        recorder.recordPhase(capture());

        recorder.close();

        assertThat(journal.resolve("experiments/exp/runs/" + run.id() + "/raw/session/" + SESSION + ".zip")).exists();
        JsonNode capture = runJson(journal, run.id()).path("summary").path("capture");
        assertThat(capture.path("status").asText()).isEqualTo("interrupted");
        assertThat(runJson(journal, run.id()).path("status").asText()).isEqualTo("CRASHED");
    }

    @Test
    void aMissingTranscriptIsRecordedAsFailedWithTheReasonAndTheRunStillFinishes(@TempDir Path dir) throws Exception {
        Path journal = dir.resolve("journal");
        Journal.configure(new JsonFileStorage(journal));
        Run run = Journal.run("exp").start();
        Path emptyRoot = Files.createDirectories(dir.resolve("empty-projects"));
        RunRecorder recorder = new RunRecorder(run).sessionFilesRoot(emptyRoot);

        recorder.recordPhase(capture());
        recorder.finish();

        JsonNode session = runJson(journal, run.id()).path("summary").path("capture").path("sources").get(1);
        assertThat(session.path("status").asText()).isEqualTo("failed");
        assertThat(session.path("reason").asText()).contains("no transcript named " + SESSION);
        assertThat(runJson(journal, run.id()).path("status").asText()).isEqualTo("FINISHED");
    }

    @Test
    void storageWithoutARawDirectoryIsRecordedAsUnavailable(@TempDir Path dir) throws Exception {
        io.github.markpollack.journal.storage.InMemoryStorage storage = new io.github.markpollack.journal.storage.InMemoryStorage();
        Journal.configure(storage);
        Run run = Journal.run("exp").start();
        RunRecorder recorder = new RunRecorder(run).lenient();

        recorder.recordPhase(capture());
        recorder.finish();

        @SuppressWarnings("unchecked")
        var capture = (java.util.Map<String, Object>) storage.loadRun("exp", run.id()).orElseThrow().summary().values().get("capture");
        @SuppressWarnings("unchecked")
        var sources = (List<java.util.Map<String, Object>>) capture.get("sources");
        assertThat(sources.get(1)).containsEntry("status", "unavailable");
    }
}
