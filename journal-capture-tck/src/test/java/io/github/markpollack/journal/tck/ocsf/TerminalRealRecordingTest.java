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
        return root(CASES, name);
    }

    static Path root(Path cases, String name) throws IOException {
        try (Stream<Path> files = Files.walk(cases.resolve(name).resolve("store/prepared"))) {
            return files.filter(p -> p.getFileName().toString().equals("events.jsonl") && !p.toString().contains("/children/")).findFirst().orElseThrow();
        }
    }

    static OcsfReaderTool.Acceptance acceptance(String name) throws IOException {
        return acceptance(CASES, name);
    }

    static OcsfReaderTool.Acceptance acceptance(Path cases, String name) throws IOException {
        Path events = root(cases, name);
        OracleReport report = new AcquisitionVerification().verify(PackageReader.open(events), events.getParent().resolve("sources"),
                events.getParent().resolve("sources/acquisition.json"), cases.resolve(name).resolve("assembly-expectation.json"));
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

    /**
     * Review F2 (main aa75c02..11e3714): the real sub-agent child with one consolidated event written twice, the following
     * events' sequence numbers shifted so the file stays consistent, is not accepted: consolidation is checked in every
     * core, and a supporting occurrence named by two consolidated events is a defect of the child.
     */
    @Test void aChildConsolidatedEventWrittenTwiceIsDefective() throws IOException {
        Path cases = temp.resolve("cases");
        copyTree(CASES.resolve("claude-sub-agent"), cases.resolve("claude-sub-agent"));
        Path child = root(cases, "claude-sub-agent").getParent().resolve("children").resolve(SPAWN);
        List<String> lines = new java.util.ArrayList<>(Files.readAllLines(child.resolve("events.jsonl")));
        int at = 0;
        while (!lines.get(at).contains("\"assembly_role_id\":3")) { at++; }
        lines.add(at + 1, lines.get(at));
        for (int i = at + 1; i < lines.size(); i++) {
            Json.JObject e = (Json.JObject) NeutralJson.parse(lines.get(i));
            if (e.member("journal") instanceof Json.JObject j && j.member("sequence") instanceof Json.JNumber n) {
                j.members().put("sequence", new Json.JNumber(Integer.toString(n.value().intValueExact() + 1)));
                lines.set(i, NeutralJson.write(e));
            }
        }
        Files.write(child.resolve("events.jsonl"), lines);
        byte[] bytes = Files.readAllBytes(child.resolve("events.jsonl"));
        Json.JObject manifest = (Json.JObject) NeutralJson.parse(Files.readString(child.resolve("run.json")));
        Json.JObject pkg = (Json.JObject) manifest.member("package");
        pkg.members().put("events_count", new Json.JNumber(Integer.toString(((Json.JNumber) pkg.member("events_count")).value().intValueExact() + 1)));
        pkg.members().put("events_bytes", new Json.JNumber(Integer.toString(bytes.length)));
        pkg.members().put("events_jsonl_sha256", new Json.JString(NativeArtifact.sha256(bytes)));
        ((Json.JObject) pkg.member("persisted_prefix")).members().put("bytes", new Json.JNumber(Integer.toString(bytes.length)));
        ((Json.JObject) pkg.member("persisted_prefix")).members().put("sha256", new Json.JString(NativeArtifact.sha256(bytes)));
        Files.writeString(child.resolve("run.json"), NeutralJson.write(manifest));
        OcsfReaderTool.Acceptance a = acceptance(cases, "claude-sub-agent");
        assertThat(a.verdict()).isEqualTo("DEFECTIVE");
        assertThat(a.reasons()).anyMatch(r -> r.contains("assembly.references [assembly/child:" + SPAWN + "]") && r.contains("named by 2"));
    }

    private static void copyTree(Path from, Path to) throws IOException {
        try (Stream<Path> paths = Files.walk(from)) {
            for (Path p : paths.toList()) {
                Path t = to.resolve(from.relativize(p).toString());
                if (Files.isDirectory(p)) Files.createDirectories(t); else Files.copy(p, t);
            }
        }
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
