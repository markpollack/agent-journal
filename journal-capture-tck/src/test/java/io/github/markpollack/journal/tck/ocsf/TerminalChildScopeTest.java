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

/** Sealed Claude one-shot archives with a heartbeat or a sub-agent, read independently ({@code ocsf/terminal-child}). */
class TerminalChildScopeTest {
    private static final Path CASES = Path.of("src/test/resources/ocsf/terminal-child");
    @TempDir Path temp;

    private static Path root(String name) throws IOException {
        return root(CASES, name);
    }

    /** The root event file of the sealed case {@code name} under {@code cases}. */
    static Path root(Path cases, String name) throws IOException {
        try (Stream<Path> files = Files.walk(cases.resolve(name).resolve("store/prepared"))) {
            return files.filter(p -> p.getFileName().toString().equals("events.jsonl") && !p.toString().contains("/children/")).findFirst().orElseThrow();
        }
    }

    private static OcsfReaderTool.Acceptance acceptance(String name) throws IOException {
        return acceptance(CASES, name);
    }

    /** The independent reader's combined acceptance of the sealed case {@code name} under {@code cases}. */
    static OcsfReaderTool.Acceptance acceptance(Path cases, String name) throws IOException {
        Path events = root(cases, name);
        OcsfPackage pkg = PackageReader.open(events);
        OracleReport report = new AcquisitionVerification().verify(pkg, events.getParent().resolve("sources"), events.getParent().resolve("sources/acquisition.json"),
                cases.resolve(name).resolve("assembly-expectation.json"));
        return OcsfReaderTool.acceptance(report);
    }

    @Test void aHeartbeatStaysInTheRootAndTheArchiveIsAccepted() throws IOException {
        assertThat(root("claude-heartbeat").getParent().resolve("children")).doesNotExist();
        OcsfReaderTool.Acceptance a = acceptance("claude-heartbeat");
        assertThat(a.verdict()).as(a.toString()).isEqualTo("ACCEPTED");
    }

    @Test void theHeartbeatChildTheUnfixedWriterMadeIsDefectiveForItsOwnershipNotItsBinding() throws IOException {
        OcsfReaderTool.Acceptance a = acceptance("claude-heartbeat-bd04735");
        assertThat(a.verdict()).isEqualTo("DEFECTIVE");
        assertThat(a.reasons()).anyMatch(r -> r.contains("child toolu_MCP is not an expected split core"))
                .anyMatch(r -> r.contains("is not owned by this core but retained here"))
                .noneMatch(r -> r.contains("TERMINAL_BINDING_INVALID"));
    }

    /** A sub-agent's child core is partitioned with the rest of the native identities, so its assembly assertions are checked. */
    @Test void aSubAgentChildReadsWithoutDefectAndTheArchiveIsAccepted() throws IOException {
        Path child = root("claude-sub-agent").getParent().resolve("children/toolu_AGENT/events.jsonl");
        assertThat(PackageReader.open(child).diagnostics).noneMatch(d -> d.severity() == Diagnostic.Severity.DEFECT || d.severity() == Diagnostic.Severity.REFUSED);
        assertThat(TerminalBindingReader.read(PackageReader.open(child).declarations).decision()).contains("\"executionId\"");
        OcsfReaderTool.Acceptance a = acceptance("claude-sub-agent");
        assertThat(a.verdict()).as(a.toString()).isEqualTo("ACCEPTED");
    }

    /** A heartbeat of a call inside a sub-agent names no owner, so it is stored in the root core (review F1). */
    @Test void aHeartbeatInsideASubAgentIsStoredInTheRootAndTheArchiveIsAccepted() throws IOException {
        Path events = root("claude-sub-agent-heartbeat");
        assertThat(events.getParent().resolve("children/toolu_READ")).doesNotExist();
        assertThat(Files.readString(events)).contains("toolu_READ-heartbeat-0");
        OcsfReaderTool.Acceptance a = acceptance("claude-sub-agent-heartbeat");
        assertThat(a.verdict()).as(a.toString()).isEqualTo("ACCEPTED");
    }

    /** A nested sub-agent is a child core beside its spawner's; both are partitioned and both spawns are shown (review F3). */
    @Test void aNestedSubAgentIsAcceptedWithEachSpawnShown() throws IOException {
        Path events = root("claude-sub-agent-nested");
        assertThat(events.getParent().resolve("children/toolu_AGENT/events.jsonl")).isRegularFile();
        assertThat(events.getParent().resolve("children/toolu_INNER/events.jsonl")).isRegularFile();
        OcsfReaderTool.Acceptance a = acceptance("claude-sub-agent-nested");
        assertThat(a.verdict()).as(a.toString()).isEqualTo("ACCEPTED");
    }

    /** An expected split core is only what records name; without the spawning request the archive is not accepted (review F3). */
    @Test void anOwnerNoCallSpawnedLeavesTheArchiveIncomplete() throws IOException {
        OcsfReaderTool.Acceptance a = acceptance("claude-dangling-owner");
        assertThat(a.verdict()).isEqualTo("INCOMPLETE");
        assertThat(a.reasons()).singleElement().asString().contains("acquisition.child_spawn").contains("child:toolu_GHOST").contains("a sub-agent spawn is not shown");
    }

    /**
     * A sub-agent's own session file is read into its child core, beside the stream's records of the sub-agent; the
     * root holds none of it, and the archive is accepted.
     */
    @Test void aSubAgentsSessionFileIsReadIntoItsChildCoreAndTheArchiveIsAccepted() throws IOException {
        Path events = root("claude-sub-agent-session");
        String artifact = "native/native-1/subagents/agent-a1.jsonl";
        assertThat(PackageReader.open(events.getParent().resolve("children/toolu_AGENT/events.jsonl")).events).anyMatch(e -> artifact.equals(e.artifact()));
        assertThat(PackageReader.open(events).events).noneMatch(e -> artifact.equals(e.artifact()));
        OcsfReaderTool.Acceptance a = acceptance("claude-sub-agent-session");
        assertThat(a.verdict()).as(a.toString()).isEqualTo("ACCEPTED");
    }

    @Test void aSpawnIsTheRequestOfItsToolCall() {
        NativeArtifact stream = NativeArtifact.ofBytes(Path.of("stdout.bin"), (String.join("\n",
                "{\"type\":\"assistant\",\"message\":{\"content\":[{\"type\":\"text\",\"text\":\"toolu_X\"},{\"type\":\"tool_use\",\"id\":\"toolu_AGENT\",\"name\":\"Agent\"}]}}",
                "{\"type\":\"user\",\"message\":{\"content\":[{\"type\":\"tool_result\",\"tool_use_id\":\"toolu_GHOST\"}]},\"parent_tool_use_id\":\"toolu_GHOST\"}",
                "{\"type\":\"user\",\"message\":\"plain\"}", "not json") + "\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertThat(ClaudeOwnerScope.spawned(stream, "toolu_AGENT")).isTrue();
        assertThat(ClaudeOwnerScope.spawned(stream, "toolu_GHOST")).as("a result is not a request").isFalse();
        assertThat(ClaudeOwnerScope.spawned(stream, "toolu_X")).as("text is not a request").isFalse();
    }

    @Test void aChildNotLinkedToTheBoundExecutionIsRefused() throws IOException {
        Path child = root("claude-sub-agent").getParent().resolve("children/toolu_AGENT/events.jsonl");
        for (String fault : List.of("parent", "run")) {
            List<String> lines = Files.readAllLines(child);
            Json.JObject declaration = (Json.JObject) NeutralJson.parse(lines.get(2));
            Json.JObject run = (Json.JObject) declaration.member("run");
            if (fault.equals("parent")) ((Json.JObject) run.member("parent")).members().put("run_uid", new Json.JString("other"));
            else run.members().put("run_uid", new Json.JString("execution-1/child/other"));
            lines.set(2, NeutralJson.write(declaration));
            Path detached = Files.createDirectories(temp.resolve(fault)).resolve("events.jsonl");
            Files.write(detached, lines);
            assertThat(PackageReader.open(detached).diagnosticsWithCode("TERMINAL_BINDING_INVALID")).as(fault).hasSize(1);
        }
    }

    @Test void aProgressRecordNamesTheCallInProgressNotAnOwner() {
        assertThat(ClaudeOwnerScope.owner(NeutralJson.parse("{\"type\":\"tool_progress\",\"parent_tool_use_id\":\"toolu_MCP\"}"))).isNull();
        assertThat(ClaudeOwnerScope.owner(NeutralJson.parse("{\"type\":\"assistant\",\"parent_tool_use_id\":\"toolu_AGENT\"}"))).isEqualTo("toolu_AGENT");
        assertThat(ClaudeOwnerScope.owner(NeutralJson.parse("{\"type\":\"assistant\",\"parent_tool_use_id\":null}"))).isNull();
        assertThat(ClaudeOwnerScope.owner(null)).isNull();
    }
}
