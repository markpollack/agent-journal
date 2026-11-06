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
        try (Stream<Path> files = Files.walk(CASES.resolve(name).resolve("store/prepared"))) {
            return files.filter(p -> p.getFileName().toString().equals("events.jsonl") && !p.toString().contains("/children/")).findFirst().orElseThrow();
        }
    }

    private static OcsfReaderTool.Acceptance acceptance(String name) throws IOException {
        Path events = root(name);
        OcsfPackage pkg = PackageReader.open(events);
        OracleReport report = new AcquisitionVerification().verify(pkg, events.getParent().resolve("sources"), events.getParent().resolve("sources/acquisition.json"),
                CASES.resolve(name).resolve("assembly-expectation.json"));
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

    @Test void aSubAgentChildReadsWithoutDefectAndOnlyItsAssemblyAssertionsAreUnchecked() throws IOException {
        Path child = root("claude-sub-agent").getParent().resolve("children/toolu_AGENT/events.jsonl");
        assertThat(PackageReader.open(child).diagnostics).noneMatch(d -> d.severity() == Diagnostic.Severity.DEFECT || d.severity() == Diagnostic.Severity.REFUSED);
        assertThat(TerminalBindingReader.read(PackageReader.open(child).declarations).decision()).contains("\"executionId\"");
        OcsfReaderTool.Acceptance a = acceptance("claude-sub-agent");
        assertThat(a.verdict()).as(a.toString()).isEqualTo("INCOMPLETE");
        assertThat(a.reasons()).isNotEmpty().allMatch(r -> r.matches("actual assertion unchecked: child:toolu_AGENT/stdout\\.bin:line=\\d+#/assembly/(assembly_role_id|entity_uid|occurrence_uid)"));
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
