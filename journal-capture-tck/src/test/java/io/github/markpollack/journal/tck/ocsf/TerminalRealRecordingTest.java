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

    /**
     * The payload-key case with its consolidated MCP request (the one whose arguments carry a basis key) rewritten by
     * {@code edit}, the root index restated; returns the cases directory holding the copy.
     */
    private Path editedRequest(java.util.function.Consumer<Json.JObject> edit) throws IOException {
        return edited("claude-mcp-payload-keys", e -> e.member("arguments") instanceof Json.JObject args && args.member("basis") != null,
                e -> edit.accept((Json.JObject) e.member("arguments")));
    }

    /** Case {@code name} with its first consolidated event matching {@code which} rewritten by {@code edit}, the root index restated. */
    private Path edited(String name, java.util.function.Predicate<Json.JObject> which, java.util.function.Consumer<Json.JObject> edit) throws IOException {
        Path cases = temp.resolve("edited");
        copyTree(CASES.resolve(name), cases.resolve(name));
        Path events = root(cases, name);
        List<String> lines = new java.util.ArrayList<>(Files.readAllLines(events));
        int edited = 0;
        for (int i = 0; i < lines.size() && edited == 0; i++) {
            Json.JObject e = (Json.JObject) NeutralJson.parse(lines.get(i));
            if (e.member("assembly") instanceof Json.JObject a && a.member("assembly_role_id") instanceof Json.JNumber n && n.value().intValue() == 3 && which.test(e)) {
                edit.accept(e);
                lines.set(i, NeutralJson.write(e));
                edited++;
            }
        }
        assertThat(edited).as("one consolidated event edited").isEqualTo(1);
        Files.write(events, lines);
        byte[] bytes = Files.readAllBytes(events);
        Json.JObject manifest = (Json.JObject) NeutralJson.parse(Files.readString(events.resolveSibling("run.json")));
        Json.JObject pkg = (Json.JObject) manifest.member("package");
        pkg.members().put("events_bytes", new Json.JNumber(Integer.toString(bytes.length)));
        pkg.members().put("events_jsonl_sha256", new Json.JString(NativeArtifact.sha256(bytes)));
        ((Json.JObject) pkg.member("persisted_prefix")).members().put("bytes", new Json.JNumber(Integer.toString(bytes.length)));
        ((Json.JObject) pkg.member("persisted_prefix")).members().put("sha256", new Json.JString(NativeArtifact.sha256(bytes)));
        Files.writeString(events.resolveSibling("run.json"), NeutralJson.write(manifest));
        return cases;
    }

    /**
     * Re-review of f6c54da, F1: {@code observed_values} is a declared payload slot (json_t) the earlier hard-coded list
     * missed. In the real sub-agent child's conflict record, an observed value given a provenance-shaped member no
     * observation reports is not accepted: inside the slot {@code basis} is a fact, not explanatory text.
     */
    @Test void anUnreportedProvenanceShapedMemberInADeclaredSlotIsNotAccepted() throws IOException {
        Path cases = withInventedObservedBasis();
        OcsfReaderTool.Acceptance a = acceptance(cases, "claude-sub-agent");
        assertThat(a.verdict()).isEqualTo("DEFECTIVE");
        assertThat(a.reasons()).anyMatch(r -> r.contains("assembly.evidence.association") && r.contains("invented"));
    }

    /**
     * Re-review of f6c54da, F1: assertion accounting stops at a declared payload slot. The edited child's
     * {@code observed_values} is one whole-value assertion; nothing inside it, the invented {@code basis} included, is
     * classified as explanatory text and so exempt from checking.
     */
    @Test void aDeclaredSlotIsOneWholeAssertionNotExplanatoryText() throws IOException {
        Path cases = withInventedObservedBasis();
        List<AssertionAccounting.Assertion> slots = AssertionAccounting.inspect(PackageReader.open(root(cases, "claude-sub-agent")), new OracleReport()).stream()
                .filter(x -> x.path().contains("/observed_values")).toList();
        assertThat(slots).isNotEmpty().allSatisfy(x -> {
            assertThat(x.path()).endsWith("/observed_values");
            assertThat(x.status()).isNotEqualTo("EXPLANATORY_TEXT");
        });
    }

    /**
     * Final F1 re-review: each core's own declared closure bounds its payloads. The real sub-agent's child declares
     * {@code mcp_arguments} (it made the MCP call) and the root does not; in the child that slot is one whole-value
     * assertion, never descended into as the root's boundary would.
     */
    @Test void aPayloadOnlyTheChildDeclaresIsOneWholeAssertionInTheChild() throws IOException {
        List<AssertionAccounting.Assertion> slots = AssertionAccounting.inspect(PackageReader.open(root("claude-sub-agent")), new OracleReport()).stream()
                .filter(x -> x.address().startsWith("child:") && x.path().contains("/mcp_arguments")).toList();
        assertThat(slots).isNotEmpty().allSatisfy(x -> assertThat(x.path()).endsWith("/mcp_arguments"));
    }

    /**
     * Final F1 re-review: in the real child, the consolidated MCP request with an invented basis inside the child-only
     * payload mcp_arguments is defective, naming that slot; under the root's boundary the basis was exempt as provenance.
     */
    @Test void anUnreportedMemberInAPayloadOnlyTheChildDeclaresIsDefective() throws IOException {
        Path cases = temp.resolve("child-payload");
        copyTree(CASES.resolve("claude-sub-agent"), cases.resolve("claude-sub-agent"));
        Path child = root(cases, "claude-sub-agent").getParent().resolve("children").resolve(SPAWN);
        List<String> lines = new java.util.ArrayList<>(Files.readAllLines(child.resolve("events.jsonl")));
        int edited = 0;
        for (int i = 0; i < lines.size() && edited == 0; i++) {
            Json.JObject e = (Json.JObject) NeutralJson.parse(lines.get(i));
            if (e.member("assembly") instanceof Json.JObject a && a.member("assembly_role_id") instanceof Json.JNumber n && n.value().intValue() == 3
                    && e.member("mcp") instanceof Json.JObject m && m.member("protocol_objects") instanceof Json.JArray po) {
                for (Json o : po.elements()) {
                    if (o.member("mcp_call_tool_request_params") instanceof Json.JObject params && params.member("mcp_arguments") instanceof Json.JObject args) {
                        args.members().put("basis", new Json.JString("invented"));
                        edited++;
                    }
                }
                if (edited > 0) { lines.set(i, NeutralJson.write(e)); }
            }
        }
        assertThat(edited).isEqualTo(1);
        Files.write(child.resolve("events.jsonl"), lines);
        restateIndex(child);
        OcsfReaderTool.Acceptance a = acceptance(cases, "claude-sub-agent");
        assertThat(a.verdict()).isEqualTo("DEFECTIVE");
        assertThat(a.reasons()).anyMatch(r -> r.contains("mcp_arguments"));
    }

    /** The real sub-agent case whose child conflict record's first observed value carries an invented basis member. */
    private Path withInventedObservedBasis() throws IOException {
        Path cases = temp.resolve("observed");
        copyTree(CASES.resolve("claude-sub-agent"), cases.resolve("claude-sub-agent"));
        Path child = root(cases, "claude-sub-agent").getParent().resolve("children").resolve(SPAWN);
        List<String> lines = new java.util.ArrayList<>(Files.readAllLines(child.resolve("events.jsonl")));
        int edited = 0;
        for (int i = 0; i < lines.size() && edited == 0; i++) {
            Json.JObject e = (Json.JObject) NeutralJson.parse(lines.get(i));
            if (e.member("assembly") instanceof Json.JObject a && a.member("assembly_conflicts") instanceof Json.JArray conflicts && !conflicts.elements().isEmpty()) {
                Json.JObject value = (Json.JObject) conflicts.elements().get(0).member("observed_values").elements().get(0);
                value.members().put("basis", new Json.JString("invented"));
                lines.set(i, NeutralJson.write(e));
                edited++;
            }
        }
        assertThat(edited).isEqualTo(1);
        Files.write(child.resolve("events.jsonl"), lines);
        restateIndex(child);
        return cases;
    }

    private static void restateIndex(Path core) throws IOException {
        byte[] bytes = Files.readAllBytes(core.resolve("events.jsonl"));
        Json.JObject manifest = (Json.JObject) NeutralJson.parse(Files.readString(core.resolve("run.json")));
        Json.JObject pkg = (Json.JObject) manifest.member("package");
        pkg.members().put("events_bytes", new Json.JNumber(Integer.toString(bytes.length)));
        pkg.members().put("events_jsonl_sha256", new Json.JString(NativeArtifact.sha256(bytes)));
        ((Json.JObject) pkg.member("persisted_prefix")).members().put("bytes", new Json.JNumber(Integer.toString(bytes.length)));
        ((Json.JObject) pkg.member("persisted_prefix")).members().put("sha256", new Json.JString(NativeArtifact.sha256(bytes)));
        Files.writeString(core.resolve("run.json"), NeutralJson.write(manifest));
    }

    /**
     * An event attribute its own declared definition does not define is a defect, named by event line and attribute:
     * otherwise its meaning, and whether it is a payload, is unknown to the reader. The control is the real MCP case
     * whose consolidated result carries an mcp_meta member its declared mcp_call_tool_result definition lacks.
     */
    @Test void anAttributeTheDeclaredClosureDoesNotDefineIsDefective() throws IOException {
        Path cases = edited("claude-mcp", e -> e.member("mcp") instanceof Json.JObject m && m.member("protocol_objects") instanceof Json.JArray po
                && po.elements().stream().anyMatch(o -> o.member("mcp_call_tool_result") != null), e -> {
                    for (Json o : e.member("mcp").member("protocol_objects").elements()) {
                        if (o.member("mcp_call_tool_result") instanceof Json.JObject r) {
                            r.members().put("mcp_meta", NeutralJson.parse("{\"basis\":\"invented\"}"));
                        }
                    }
                });
        OcsfReaderTool.Acceptance a = acceptance(cases, "claude-mcp");
        assertThat(a.verdict()).isEqualTo("DEFECTIVE");
        assertThat(a.reasons()).anyMatch(r -> r.contains("UNKNOWN_ATTRIBUTE") && r.matches("(?s).*line \\d+ .*mcp_call_tool_result\\.mcp_meta.*"));
    }

    /** Re-review F1: real MCP arguments whose keys are basis and data are a payload the reader judges like any fact. */
    @Test void argumentsWithBasisAndDataKeysAreAccepted() throws IOException {
        OcsfReaderTool.Acceptance a = acceptance("claude-mcp-payload-keys");
        assertThat(a.verdict()).as(a.toString()).isEqualTo("ACCEPTED");
    }

    /** Re-review F1: a consolidated request that drops its arguments' basis member is defective; the key is a fact there. */
    @Test void aConsolidatedRequestDroppingAPayloadMemberIsDefective() throws IOException {
        Path cases = editedRequest(args -> args.members().remove("basis"));
        OcsfReaderTool.Acceptance a = acceptance(cases, "claude-mcp-payload-keys");
        assertThat(a.verdict()).isEqualTo("DEFECTIVE");
        assertThat(a.reasons()).anyMatch(r -> r.contains("/arguments/basis"));
    }

    /** Re-review F1: a consolidated request whose arguments' data differs from every observation's is defective. */
    @Test void aConsolidatedRequestCarryingAnUnreportedPayloadValueIsDefective() throws IOException {
        Path cases = editedRequest(args -> args.members().put("data", new Json.JNumber("99")));
        OcsfReaderTool.Acceptance a = acceptance(cases, "claude-mcp-payload-keys");
        assertThat(a.verdict()).isEqualTo("DEFECTIVE");
        assertThat(a.reasons()).anyMatch(r -> r.contains("/arguments/data"));
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

    /**
     * Heartbeat review F2: a child core whose terminal binding is a valid-looking binding of another real run (the
     * heartbeat run's) is refused, so a child cannot be passed off under a foreign execution.
     */
    @Test void aChildCarryingAnotherRunsTerminalBindingIsRefused() throws IOException {
        Json foreign = runDeclaration(Files.readAllLines(root("claude-heartbeat"))).member("run").member("terminal_binding");
        assertThat(foreign).isNotNull();
        Path child = root("claude-sub-agent").getParent().resolve("children").resolve(SPAWN).resolve("events.jsonl");
        List<String> lines = Files.readAllLines(child);
        int at = 0;
        while (!lines.get(at).contains("\"kind\":\"run\"")) { at++; }
        Json.JObject declaration = (Json.JObject) NeutralJson.parse(lines.get(at));
        ((Json.JObject) declaration.member("run")).members().put("terminal_binding", foreign);
        lines.set(at, NeutralJson.write(declaration));
        Path detached = Files.createDirectories(temp.resolve("foreign")).resolve("events.jsonl");
        Files.write(detached, lines);
        assertThat(PackageReader.open(detached).diagnosticsWithCode("TERMINAL_BINDING_INVALID")).hasSize(1);
    }

    private static Json runDeclaration(List<String> lines) {
        return lines.stream().filter(l -> l.contains("\"kind\":\"run\"")).map(NeutralJson::parse).findFirst().orElseThrow();
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
