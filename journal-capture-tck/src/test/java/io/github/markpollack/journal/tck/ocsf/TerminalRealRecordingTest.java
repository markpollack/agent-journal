package io.github.markpollack.journal.tck.ocsf;

import io.github.markpollack.journal.tck.ocsf.inventory.AcquisitionVerification;
import io.github.markpollack.journal.tck.verify.Json;
import io.github.markpollack.journal.tck.verify.NeutralJson;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

/**
 * Sealed archives of real Claude Code 2.1.316 runs recorded through Client ({@code ocsf/terminal-real}; scrubbed
 * derivatives, see each case's SCRUBBED.md), read independently: a long MCP call with heartbeats, a run of MCP calls,
 * and one sub-agent with its own session file. Each also as an earlier writer sealed it, with the defect it had.
 */
class TerminalRealRecordingTest {
    static final Path CASES = Path.of("src/test/resources/ocsf/terminal-real");
    private static final String SPAWN = "toolu_01KQMhaSZMrxp98fqQBkmHCC";
    private static final String SESSION_FILE = "native/1e60ebc7-85e6-4b7c-bd39-897d99229066/subagents/agent-a1ca6a454512b0b01.jsonl";
    @TempDir Path temp;

    static Path root(String name) throws IOException {
        try (Stream<Path> files = Files.walk(CASES.resolve(name).resolve("store/prepared"))) {
            return files.filter(p -> p.getFileName().toString().equals("events.jsonl") && !p.toString().contains("/children/")).findFirst().orElseThrow();
        }
    }

    static OcsfReaderTool.Acceptance acceptance(String name) throws IOException {
        Path events = root(name);
        OracleReport report = new AcquisitionVerification().verify(PackageReader.open(events), events.getParent().resolve("sources"),
                events.getParent().resolve("sources/acquisition.json"), CASES.resolve(name).resolve("assembly-expectation.json"));
        return OcsfReaderTool.acceptance(report);
    }

    private static NativeArtifact stream(String name) throws IOException {
        return NativeArtifact.read(root(name).getParent().resolve("sources/stdout.bin"));
    }

    @Test void aLongMcpCallsHeartbeatsStayInTheRootAndTheArchiveIsAccepted() throws IOException {
        assertThat(root("claude-heartbeat").getParent().resolve("children")).doesNotExist();
        OcsfReaderTool.Acceptance a = acceptance("claude-heartbeat");
        assertThat(a.verdict()).as(a.toString()).isEqualTo("ACCEPTED");
    }

    @Test void theWriterBeforeTheProgressOwnerFixMadeAHeartbeatAChildCore() throws IOException {
        OcsfReaderTool.Acceptance a = acceptance("claude-heartbeat-bd04735");
        assertThat(a.verdict()).isEqualTo("DEFECTIVE");
        assertThat(a.reasons()).anyMatch(r -> r.contains("is not an expected split core"))
                .anyMatch(r -> r.contains("is not owned by this core but retained here"))
                .noneMatch(r -> r.contains("TERMINAL_BINDING_INVALID"));
    }

    @Test void aRunOfMcpCallsSeenByTheStreamAndTheSessionFileIsAccepted() throws IOException {
        OcsfReaderTool.Acceptance a = acceptance("claude-mcp");
        assertThat(a.verdict()).as(a.toString()).isEqualTo("ACCEPTED");
    }

    @Test void theWriterBeforeTheProvenanceFixCalledMcpResultsRicherForTheirBasis() throws IOException {
        OcsfReaderTool.Acceptance a = acceptance("claude-mcp-aa75c02");
        assertThat(a.verdict()).isEqualTo("DEFECTIVE");
        assertThat(a.reasons()).isNotEmpty().allMatch(r -> r.matches(
                "NORMALIZATION assembly\\.evidence\\.value \\[main/stdout\\.jsonl\\] DEFECTIVE: (\\d+)/assembly/relation_class(_id)? at main/stdout\\.jsonl:line=\\1#/assembly/relation_class(_id)?: .*"));
    }

    @Test void aSubAgentAndItsOwnSessionFileAreReadIntoItsChildCoreAndTheArchiveIsAccepted() throws IOException {
        Path child = root("claude-sub-agent").getParent().resolve("children").resolve(SPAWN).resolve("events.jsonl");
        assertThat(PackageReader.open(child).events).anyMatch(e -> SESSION_FILE.equals(e.artifact()));
        assertThat(PackageReader.open(root("claude-sub-agent")).events).noneMatch(e -> SESSION_FILE.equals(e.artifact()));
        OcsfReaderTool.Acceptance a = acceptance("claude-sub-agent");
        assertThat(a.verdict()).as(a.toString()).isEqualTo("ACCEPTED");
    }

    @Test void theWriterBeforeTheSessionFileFixLeftTheSubAgentsSessionFileUnread() throws IOException {
        OcsfReaderTool.Acceptance a = acceptance("claude-sub-agent-397ec12");
        assertThat(a.verdict()).isEqualTo("DEFECTIVE");
        assertThat(a.reasons()).hasSize(2).allMatch(r -> r.startsWith("COMPLETENESS acquisition.additional_native [native/1e60ebc7-85e6-4b7c-bd39-897d99229066/subagents/agent-a1ca6a454512b0b01."));
    }

    @Test void aChildNotLinkedToTheBoundExecutionIsRefused() throws IOException {
        Path child = root("claude-sub-agent").getParent().resolve("children").resolve(SPAWN).resolve("events.jsonl");
        for (String fault : List.of("parent", "run")) {
            List<String> lines = Files.readAllLines(child);
            int at = 0;
            while (!lines.get(at).contains("\"kind\":\"run\"")) { at++; }
            Json.JObject declaration = (Json.JObject) NeutralJson.parse(lines.get(at));
            Json.JObject run = (Json.JObject) declaration.member("run");
            if (fault.equals("parent")) ((Json.JObject) run.member("parent")).members().put("run_uid", new Json.JString("other"));
            else run.members().put("run_uid", new Json.JString("e52ccb3f-a65a-41b5-aaee-b8ca926d0452/child/other"));
            lines.set(at, NeutralJson.write(declaration));
            Path detached = Files.createDirectories(temp.resolve(fault)).resolve("events.jsonl");
            Files.write(detached, lines);
            assertThat(PackageReader.open(detached).diagnosticsWithCode("TERMINAL_BINDING_INVALID")).as(fault).hasSize(1);
        }
    }

    @Test void aRealHeartbeatNamesTheCallInProgressAndASubAgentsRecordsNameTheirSpawn() throws IOException {
        List<Json> heartbeats = stream("claude-heartbeat").records().stream().filter(NativeArtifact.Record::parsed).map(NativeArtifact.Record::json)
                .filter(r -> "tool_progress".equals(SourceProfile.text(r, "type"))).toList();
        assertThat(heartbeats).isNotEmpty().allSatisfy(r -> {
            assertThat(SourceProfile.text(r, ClaudeOwnerScope.OWNER_PATH)).as("the call in progress").isNotNull();
            assertThat(ClaudeOwnerScope.owner(r)).isNull();
        });
        assertThat(ClaudeOwnerScope.splitCores(stream("claude-heartbeat"))).isEmpty();
        assertThat(ClaudeOwnerScope.splitCores(stream("claude-sub-agent"))).containsExactly(SPAWN);
        assertThat(ClaudeOwnerScope.owner(null)).isNull();
    }

    @Test void aSpawnIsTheStreamsRequestOfItsToolCall() throws IOException {
        assertThat(ClaudeOwnerScope.spawned(stream("claude-sub-agent"), SPAWN)).isTrue();
        assertThat(ClaudeOwnerScope.spawned(stream("claude-sub-agent"), "1e60ebc7-85e6-4b7c-bd39-897d99229066")).as("the session id is no call").isFalse();
        assertThat(ClaudeOwnerScope.spawned(stream("claude-heartbeat"), SPAWN)).as("another run's call").isFalse();
    }
}
