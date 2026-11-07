package io.github.markpollack.journal.ocsf.writer;

import io.github.markpollack.journal.tck.ocsf.PackageReader;
import io.github.markpollack.journal.tck.ocsf.TerminalBindingReader;
import io.github.markpollack.journal.tck.ocsf.inventory.ClaudeAcquisition;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static io.github.markpollack.journal.ocsf.writer.TerminalReceiverTest.*;
import static org.assertj.core.api.Assertions.*;

/** Labelled synthetic native records test admission boundaries; retained actual source proof is separate. */
class TerminalNativeTest {
    @TempDir Path temp;

    private Path qualified() throws IOException {
        return qualified("{\"type\":\"result\",\"subtype\":\"success\",\"session_id\":\"native-1\",\"result\":\"hello\",\"is_error\":false}");
    }

    private static final String INIT = "{\"type\":\"system\",\"subtype\":\"init\",\"session_id\":\"native-1\",\"claude_code_version\":\"2.1.316\"}";

    private Path qualified(String result) throws IOException {
        return qualifiedStream(List.of(INIT, result)).manifest().getParent();
    }

    /** A sealed one-turn acquisition whose stream is {@code lines}, qualified by the Claude one-shot qualifier. */
    private io.github.markpollack.journal.tck.ocsf.inventory.AcquisitionQualifier.Qualified qualifiedStream(List<String> lines) throws IOException {
        return qualifiedStream(temp, lines);
    }

    private static final String TRANSCRIPT_PROMPT = "{\"type\":\"user\",\"uuid\":\"u1\",\"sessionId\":\"native-1\",\"version\":\"2.1.316\",\"message\":{\"role\":\"user\",\"content\":\"hello\"}}";

    private static io.github.markpollack.journal.tck.ocsf.inventory.AcquisitionQualifier.Qualified qualifiedStream(Path temp, List<String> lines) throws IOException {
        return qualifiedStream(temp, lines, List.of(TRANSCRIPT_PROMPT));
    }

    /** As {@link #qualifiedStream(Path, List)}, with the session file's records {@code transcript}. */
    private static io.github.markpollack.journal.tck.ocsf.inventory.AcquisitionQualifier.Qualified qualifiedStream(Path temp, List<String> lines, List<String> transcript) throws IOException {
        return qualifiedStream(temp, lines, transcript, List.of());
    }

    /** A further acquired native file the Client preserves beside the session file (archive only, as Client seals it). */
    private record Extra(String path, String role, String membershipBasis, String content) { }

    /** As {@link #qualifiedStream(Path, List, List)}, with further acquired native files. */
    private static io.github.markpollack.journal.tck.ocsf.inventory.AcquisitionQualifier.Qualified qualifiedStream(Path temp, List<String> lines, List<String> transcript,
            List<Extra> extras) throws IOException {
        Path raw = Files.createDirectory(temp.resolve("raw"));
        Files.writeString(raw.resolve("stdin.bin"), "{\"type\":\"user\",\"message\":{\"role\":\"user\",\"content\":\"hello\"}}\n");
        Files.writeString(raw.resolve("stdout.bin"), String.join("\n", lines) + "\n");
        Files.writeString(raw.resolve("stderr.bin"), "");
        Files.writeString(raw.resolve("native.jsonl"), String.join("\n", transcript) + "\n");
        Jv.Obj m = (Jv.Obj) Jv.parse("""
                {"kind":"agent-journal/acquired-execution","version":"0.1","profile":"claude-code-2.1.316-sdk-stdio-session-file",
                "profile_id":"claude-code/2.1.316/sdk-stdio+session-file","acquisition_uid":"execution-1","complete":true,"capture_errors":[],
                "run":{"run_uid":"execution-1","invocation_index":0,"session_uid":"native-1","capture_start":"2026-11-02T00:00:00Z",
                "cli":{"name":"Claude Code","version":"2.1.316","vendor_name":"Anthropic"},"launch_settings":{"route":"sdk_stdio"}},
                "process":{"connection_uid":"connection-1","argv":["claude","--input-format","stream-json","--output-format","stream-json"],"exit_code":null},
                "turns":[{"invocation_uid":"execution-1","start_offsets":{"STDIN":0,"STDOUT":0,"STDERR":0},"end_offsets":{}}],"sources":[]}
                """);
        Jv.Arr sources = (Jv.Arr) m.member("sources");
        for (String role : List.of("stdin", "stream", "stderr", "transcript")) {
            String path = switch (role) { case "stream" -> "stdout.bin"; case "transcript" -> "native.jsonl"; default -> role + ".bin"; };
            byte[] content = Files.readAllBytes(raw.resolve(path));
            Jv.Obj s = new Jv.Obj().put("artifact", path).put("path", path).put("role", role).put("origin", "observed/" + path)
                    .put("state", "acquired").put("acquisition", "execution-1").put("required", true).put("end", "EOF")
                    .put("bytes", content.length).put("sha256", SchemaPin.sha256(content));
            if (role.equals("stream") || role.equals("transcript")) {
                s.put("profile", role.equals("stream") ? "claude_stream" : "claude_transcript")
                        .put("route", role.equals("stream") ? "sdk_stdio" : "native_session_file")
                        .put("framing", "lines").put("records", role.equals("stream") ? lines.size() : transcript.size()).put("owner_session_uid", "native-1");
            } else s.put("archive_only", true);
            sources.add(s);
            if (!role.equals("transcript")) ((Jv.Obj) m.member("turns").elements().get(0).member("end_offsets")).put(role.equals("stream") ? "STDOUT" : role.toUpperCase(java.util.Locale.ROOT), content.length);
        }
        for (Extra x : extras) {
            Path file = raw.resolve(x.path()); Files.createDirectories(file.getParent()); Files.writeString(file, x.content());
            byte[] content = Files.readAllBytes(file);
            sources.add(new Jv.Obj().put("artifact", x.path()).put("role", x.role()).put("path", x.path()).put("origin", "observed/" + x.path()).put("acquisition", "execution-1")
                    .put("state", "acquired").put("bytes", content.length).put("sha256", SchemaPin.sha256(content)).put("owner_session_uid", "native-1").put("end", "EOF")
                    .put("archive_only", true).put("required", true).put("membership_basis", x.membershipBasis()));
        }
        Path sealed = raw.resolve("sealed.json"); Files.writeString(sealed, Jv.write(m));
        return ClaudeAcquisition.qualify(sealed, temp.resolve("qualified"));
    }

    /** The same sealed input, qualified through the registered qualifier its own profile selects. */
    private io.github.markpollack.journal.tck.ocsf.inventory.AcquisitionQualifier.Qualified qualifiedThroughTheRegistry() throws IOException {
        Path sealed = qualified("{\"type\":\"result\",\"subtype\":\"success\",\"session_id\":\"native-1\",\"result\":\"hello\",\"is_error\":false,"
                + "\"usage\":{\"input_tokens\":11,\"cache_creation_input_tokens\":0,\"cache_read_input_tokens\":3,\"output_tokens\":4}}").resolve("client-acquisition.json");
        var qualifier = io.github.markpollack.journal.tck.ocsf.inventory.AcquisitionQualifier.forProfile(Jv.parse(Files.readString(sealed)).member("profile").text()).orElseThrow();
        assertThat(qualifier.terminalProfile()).isEqualTo(TerminalReceiver.CLAUDE_PROFILE);
        Path copy = Files.createDirectory(temp.resolve("sealed-copy"));
        for (String name : List.of("stdin.bin", "stdout.bin", "stderr.bin", "native.jsonl")) Files.copy(temp.resolve("raw").resolve(name), copy.resolve(name));
        Files.copy(sealed, copy.resolve("sealed.json"));
        return qualifier.qualify(copy.resolve("sealed.json"), temp.resolve("registry-qualified"));
    }

    @Test void theRegisteredQualifierDeliversOneInitialBindingUnderItsTerminalProfile() throws Exception {
        var q = qualifiedThroughTheRegistry(); Path file = assertAccepted(q);
        assertThat(Files.readString(file)).as("the archived terminal envelope").contains("\\\"profile\\\":\\\"" + TerminalReceiver.CLAUDE_PROFILE + "\\\"");
        assertThat(Files.readAllBytes(q.manifest().getParent().resolve("client-acquisition.json"))).isEqualTo(Files.readAllBytes(temp.resolve("raw/sealed.json")));
    }

    /** Delivers {@code q} through the shipped receiver and requires the independent reader's full acceptance; returns the archive. */
    private Path assertAccepted(io.github.markpollack.journal.tck.ocsf.inventory.AcquisitionQualifier.Qualified q) throws IOException {
        Delivered d = deliver(q);
        assertThat(d.acceptance().exit()).as(d.acceptance().toString()).isZero();
        return d.file();
    }

    private record Delivered(Path file, io.github.markpollack.journal.tck.ocsf.OcsfReaderTool.Acceptance acceptance) { }

    /** Delivers {@code q} through the shipped receiver; the independent reader reports no defect and returns its acceptance. */
    private Delivered deliver(io.github.markpollack.journal.tck.ocsf.inventory.AcquisitionQualifier.Qualified q) throws IOException {
        return deliver(temp, q);
    }

    /** Seals {@code q} through the shipped receiver into {@code temp/store}; returns the root event file. */
    private static Path seal(Path temp, io.github.markpollack.journal.tck.ocsf.inventory.AcquisitionQualifier.Qualified q) throws IOException {
        Path base = q.manifest().getParent(), store = temp.resolve("store");
        TerminalReceiver receiver = new TerminalReceiver(store, "producer", WriterOptions.defaults());
        return events(store, receiver.receive(nativeEnvelope(completeAssociation(base)), base));
    }

    private static Delivered deliver(Path temp, io.github.markpollack.journal.tck.ocsf.inventory.AcquisitionQualifier.Qualified q) throws IOException {
        Path file = seal(temp, q);
        var pkg = PackageReader.open(file);
        assertThat(pkg.diagnostics).noneMatch(d -> d.severity().name().equals("DEFECT") || d.severity().name().equals("REFUSED"));
        var report = new io.github.markpollack.journal.tck.ocsf.inventory.AcquisitionVerification().verify(pkg, file.getParent().resolve("sources"),
                file.getParent().resolve("sources/acquisition.json"), q.assemblyExpectation());
        assertThat(report.defects()).isEmpty();
        return new Delivered(file, io.github.markpollack.journal.tck.ocsf.OcsfReaderTool.acceptance(report));
    }

    private static final String USAGE = "\"usage\":{\"input_tokens\":11,\"cache_creation_input_tokens\":0,\"cache_read_input_tokens\":3,\"output_tokens\":4}";
    private static final String RESULT = "{\"type\":\"result\",\"subtype\":\"success\",\"session_id\":\"native-1\",\"result\":\"done\",\"is_error\":false," + USAGE + "}";

    private static String toolUse(String parent, String id, String name, String uuid) {
        return "{\"type\":\"assistant\",\"message\":{\"id\":\"msg_" + uuid + "\",\"type\":\"message\",\"role\":\"assistant\",\"model\":\"claude-opus-5-5\",\"content\":[{\"type\":\"tool_use\",\"id\":\""
                + id + "\",\"name\":\"" + name + "\",\"input\":{}}],\"stop_reason\":\"tool_use\"},\"parent_tool_use_id\":" + parent + ",\"session_id\":\"native-1\",\"uuid\":\"" + uuid + "\"}";
    }

    private static String toolResult(String parent, String id, String uuid) {
        return "{\"type\":\"user\",\"message\":{\"role\":\"user\",\"content\":[{\"tool_use_id\":\"" + id + "\",\"type\":\"tool_result\",\"content\":\"done\"}]},\"parent_tool_use_id\":"
                + parent + ",\"session_id\":\"native-1\",\"uuid\":\"" + uuid + "\"}";
    }

    private static final String HEARTBEAT = "{\"type\":\"tool_progress\",\"tool_use_id\":\"toolu_MCP-heartbeat-0\",\"tool_name\":\"mcp__slow__wait\",\"parent_tool_use_id\":\"toolu_MCP\","
            + "\"elapsed_time_seconds\":30,\"heartbeat\":true,\"session_id\":\"native-1\",\"uuid\":\"p1\"}";
    private static final String CHILD_PROMPT = "{\"type\":\"user\",\"message\":{\"role\":\"user\",\"content\":[{\"type\":\"text\",\"text\":\"read beta\"}]},\"parent_tool_use_id\":\"toolu_AGENT\","
            + "\"agent_id\":\"a1\",\"session_id\":\"native-1\",\"uuid\":\"c1\",\"subagent_type\":\"general-purpose\"}";
    private static final List<String> HEARTBEAT_STREAM = List.of(INIT, toolUse("null", "toolu_MCP", "mcp__slow__wait", "a1"), HEARTBEAT, toolResult("null", "toolu_MCP", "u1"), RESULT);
    private static final List<String> SUB_AGENT_STREAM = List.of(INIT, toolUse("null", "toolu_AGENT", "Agent", "a1"), CHILD_PROMPT, toolUse("\"toolu_AGENT\"", "toolu_READ", "Read", "c2"),
            toolResult("\"toolu_AGENT\"", "toolu_READ", "c3"), toolResult("null", "toolu_AGENT", "u1"), RESULT);

    private static final String MCP_INIT = "{\"type\":\"system\",\"subtype\":\"init\",\"session_id\":\"native-1\",\"claude_code_version\":\"2.1.316\",\"tools\":[\"mcp__ide__probe\"],"
            + "\"mcp_servers\":[{\"name\":\"ide\",\"status\":\"connected\",\"source\":\"project\"}]}";
    private static final String MCP_MESSAGE = "{\"id\":\"msg_a1\",\"type\":\"message\",\"role\":\"assistant\",\"model\":\"claude-opus-5-5\",\"content\":[{\"type\":\"tool_use\",\"id\":\"toolu_MCP\","
            + "\"name\":\"mcp__ide__probe\",\"input\":{\"symbol\":\"demo.A\"}}],\"stop_reason\":\"tool_use\"}";
    private static final String MCP_CONTENT = "[{\"tool_use_id\":\"toolu_MCP\",\"type\":\"tool_result\",\"content\":[{\"type\":\"text\",\"text\":\"demo.A calls demo.B\"}]}]";
    /** One MCP call, its request and result in both the stream and the session file, shaped as Claude Code 2.1.316 writes them. */
    private static final List<String> MCP_STREAM = List.of(MCP_INIT,
            "{\"type\":\"assistant\",\"message\":" + MCP_MESSAGE + ",\"parent_tool_use_id\":null,\"session_id\":\"native-1\",\"uuid\":\"a1\"}",
            "{\"type\":\"user\",\"message\":{\"role\":\"user\",\"content\":" + MCP_CONTENT + "},\"parent_tool_use_id\":null,\"session_id\":\"native-1\",\"uuid\":\"r1\","
                    + "\"tool_use_result\":[{\"type\":\"text\",\"text\":\"demo.A calls demo.B\"}]}",
            RESULT);
    private static final List<String> MCP_TRANSCRIPT = List.of(TRANSCRIPT_PROMPT,
            "{\"parentUuid\":\"u1\",\"type\":\"assistant\",\"uuid\":\"a1\",\"sessionId\":\"native-1\",\"version\":\"2.1.316\",\"message\":" + MCP_MESSAGE + "}",
            "{\"parentUuid\":\"a1\",\"type\":\"user\",\"uuid\":\"r1\",\"sessionId\":\"native-1\",\"version\":\"2.1.316\",\"message\":{\"role\":\"user\",\"content\":" + MCP_CONTENT + "},"
                    + "\"toolUseResult\":[{\"type\":\"text\",\"text\":\"demo.A calls demo.B\"}],\"sourceToolAssistantUUID\":\"a1\"}");

    /**
     * The stream's observation of an MCP tool result states only the basis of its {@code mcp} member, which the session
     * file's observation lacks. Provenance is not a fact, so the consolidated result is not "One richer" for it.
     */
    @Test void anMcpToolResultsProvenanceDoesNotMakeItsStreamObservationRicher() throws Exception {
        Path file = assertAccepted(qualifiedStream(temp, MCP_STREAM, MCP_TRANSCRIPT));
        List<Jv> results = Files.readAllLines(file).stream().map(Jv::parse)
                .filter(e -> e.member("tool_result") != null && e.member("assembly") != null && e.member("assembly").member("relation_class_id") != null).toList();
        assertThat(results).as("one consolidated MCP tool result").hasSize(1);
        assertThat(results.get(0).member("mcp")).as("its mcp member, kept").isNotNull();
        assertThat(Jv.write(results.get(0).member("assembly"))).doesNotContain("\"field_pointer\":\"/mcp\"");
    }

    private static final String CHILD_HEARTBEAT = "{\"type\":\"tool_progress\",\"tool_use_id\":\"toolu_READ-heartbeat-0\",\"tool_name\":\"Read\",\"parent_tool_use_id\":\"toolu_READ\","
            + "\"elapsed_time_seconds\":30,\"heartbeat\":true,\"session_id\":\"native-1\",\"uuid\":\"p2\"}";
    /** A sub-agent whose Read runs long enough for a heartbeat naming it (review F1). */
    private static final List<String> SUB_AGENT_HEARTBEAT_STREAM = List.of(INIT, toolUse("null", "toolu_AGENT", "Agent", "a1"), CHILD_PROMPT, toolUse("\"toolu_AGENT\"", "toolu_READ", "Read", "c2"),
            CHILD_HEARTBEAT, toolResult("\"toolu_AGENT\"", "toolu_READ", "c3"), toolResult("null", "toolu_AGENT", "u1"), RESULT);
    private static final String INNER_PROMPT = "{\"type\":\"user\",\"message\":{\"role\":\"user\",\"content\":[{\"type\":\"text\",\"text\":\"read gamma\"}]},\"parent_tool_use_id\":\"toolu_INNER\","
            + "\"agent_id\":\"a2\",\"session_id\":\"native-1\",\"uuid\":\"n1\",\"subagent_type\":\"general-purpose\"}";
    /** A sub-agent that spawns its own sub-agent (review F3, nested). */
    private static final List<String> NESTED_SUB_AGENT_STREAM = List.of(INIT, toolUse("null", "toolu_AGENT", "Agent", "a1"), CHILD_PROMPT, toolUse("\"toolu_AGENT\"", "toolu_INNER", "Agent", "c2"),
            INNER_PROMPT, toolUse("\"toolu_INNER\"", "toolu_READ", "Read", "n2"), toolResult("\"toolu_INNER\"", "toolu_READ", "n3"), toolResult("\"toolu_AGENT\"", "toolu_INNER", "c3"),
            toolResult("null", "toolu_AGENT", "u1"), RESULT);
    /** A record naming an owner no Agent call in the stream spawned (review F3, dangling). */
    private static final List<String> DANGLING_PARENT_STREAM = List.of(INIT, toolUse("\"toolu_GHOST\"", "toolu_READ", "Read", "g1"), toolResult("\"toolu_GHOST\"", "toolu_READ", "g2"), RESULT);

    /**
     * A heartbeat of a call inside a sub-agent names that call, not an owner, so it is stored in the root core with
     * every other record that names no owner; the sub-agent's own records stay in its child core (review F1).
     */
    @Test void aHeartbeatOfACallInsideASubAgentIsStoredInTheRoot() throws Exception {
        Path file = assertAccepted(qualifiedStream(SUB_AGENT_HEARTBEAT_STREAM));
        assertThat(file.getParent().resolve("children/toolu_READ")).as("no child core for the heartbeat").doesNotExist();
        assertThat(file.getParent().resolve("children/toolu_AGENT/events.jsonl")).isRegularFile();
        assertThat(Files.readString(file)).as("the heartbeat record, in the root").contains("toolu_READ-heartbeat-0");
        assertThat(Files.readString(file.getParent().resolve("children/toolu_AGENT/events.jsonl"))).doesNotContain("toolu_READ-heartbeat-0");
    }

    /**
     * A sub-agent a sub-agent spawned is a child core beside its spawner's, linked by its spawning call, whose request is
     * written in the spawner's core (review F3).
     */
    @Test void aNestedSubAgentIsAChildCoreLinkedByItsSpawningCall() throws Exception {
        Path file = assertAccepted(qualifiedStream(NESTED_SUB_AGENT_STREAM));
        Path inner = file.getParent().resolve("children/toolu_INNER/events.jsonl");
        assertThat(Files.readString(inner)).contains("\"spawn_tool_call_uid\":\"toolu_INNER\"");
        assertThat(Files.readString(file.getParent().resolve("children/toolu_AGENT/events.jsonl"))).as("the spawning request").contains("anthropic.tool_use_id:toolu_INNER");
    }

    /** Records naming an owner whose spawn the stream never requests are a child core the reader cannot accept as a spawn (review F3). */
    @Test void anOwnerNoCallSpawnedIsNotAcceptedAsASubAgent() throws Exception {
        Delivered d = deliver(qualifiedStream(DANGLING_PARENT_STREAM));
        assertThat(d.acceptance().verdict()).isEqualTo("INCOMPLETE");
        assertThat(d.acceptance().reasons()).singleElement().asString().contains("acquisition.child_spawn").contains("a sub-agent spawn is not shown");
    }

    private static final String CHILD_STREAM_REQUEST = "{\"type\":\"assistant\",\"message\":{\"id\":\"msg_c2\",\"type\":\"message\",\"role\":\"assistant\",\"model\":\"claude-opus-5-5\","
            + "\"content\":[{\"type\":\"tool_use\",\"id\":\"toolu_READ\",\"name\":\"Read\",\"input\":{}}],\"stop_reason\":null,"
            + "\"usage\":{\"input_tokens\":5,\"cache_creation_input_tokens\":0,\"cache_read_input_tokens\":2,\"output_tokens\":2}},"
            + "\"parent_tool_use_id\":\"toolu_AGENT\",\"session_id\":\"native-1\",\"uuid\":\"c2\"}";
    /** A sub-agent as Claude Code 2.1.316 reports it in the stream: its records carry the spawning call and its agent id. */
    private static final List<String> SUB_AGENT_SESSION_STREAM = List.of(INIT, toolUse("null", "toolu_AGENT", "Agent", "a1"), CHILD_PROMPT, CHILD_STREAM_REQUEST,
            toolResult("\"toolu_AGENT\"", "toolu_READ", "c3"), toolResult("null", "toolu_AGENT", "u1"), RESULT);
    /** One content block of the sub-agent's API message msg_c2, as its session file writes it: each block its own record, all with the final usage. */
    private static String childAssistant(String parent, String uuid, String block) {
        return "{\"parentUuid\":\"" + parent + "\",\"isSidechain\":true,\"agentId\":\"a1\",\"type\":\"assistant\",\"message\":{\"id\":\"msg_c2\",\"type\":\"message\",\"role\":\"assistant\","
                + "\"model\":\"claude-opus-5-5\",\"content\":[" + block + "],\"stop_reason\":\"tool_use\","
                + "\"usage\":{\"input_tokens\":5,\"cache_creation_input_tokens\":0,\"cache_read_input_tokens\":2,\"output_tokens\":9}},\"uuid\":\"" + uuid + "\",\"sessionId\":\"native-1\",\"version\":\"2.1.316\"}";
    }

    /** The same sub-agent in its own session file: the same record uuids, the final usage and the thinking record the stream omits. */
    private static final String CHILD_SESSION = String.join("\n",
            "{\"parentUuid\":null,\"isSidechain\":true,\"agentId\":\"a1\",\"type\":\"user\",\"message\":{\"role\":\"user\",\"content\":\"read beta\"},\"uuid\":\"c1\",\"sessionId\":\"native-1\",\"version\":\"2.1.316\"}",
            childAssistant("c1", "t1", "{\"type\":\"thinking\",\"thinking\":\"read it\",\"signature\":\"s1\"}"),
            childAssistant("t1", "c2", "{\"type\":\"tool_use\",\"id\":\"toolu_READ\",\"name\":\"Read\",\"input\":{}}"),
            "{\"parentUuid\":\"c2\",\"isSidechain\":true,\"agentId\":\"a1\",\"type\":\"user\",\"message\":{\"role\":\"user\",\"content\":[{\"tool_use_id\":\"toolu_READ\",\"type\":\"tool_result\",\"content\":\"done\"}]},"
                    + "\"uuid\":\"c3\",\"toolUseResult\":\"done\",\"sourceToolAssistantUUID\":\"c2\",\"sessionId\":\"native-1\",\"version\":\"2.1.316\"}") + "\n";
    private static final List<Extra> SUB_AGENT_SESSION = List.of(
            new Extra("native/native-1/subagents/agent-a1.jsonl", "child_transcript", "exact_parent_session_directory_and_sessionId", CHILD_SESSION),
            new Extra("native/native-1/subagents/agent-a1.meta.json", "sidecar", "exact_parent_session_directory",
                    "{\"agentType\":\"general-purpose\",\"isBuiltIn\":true,\"description\":\"read beta\",\"toolUseId\":\"toolu_AGENT\",\"spawnDepth\":1,"
                            + "\"requestShape\":\"foreground\",\"requestNonInteractive\":true,\"finished\":true}"));

    /**
     * A sub-agent's own session file is read into its child core: its records join the stream's records of the same
     * uuid there, so the child's model response carries the final usage and the thinking only the session file reports.
     */
    @Test void aSubAgentsSessionFileIsReadIntoItsChildCore() throws Exception {
        Path file = assertAccepted(qualifiedStream(temp, SUB_AGENT_SESSION_STREAM, List.of(TRANSCRIPT_PROMPT), SUB_AGENT_SESSION));
        List<Jv> child = Files.readAllLines(file.getParent().resolve("children/toolu_AGENT/events.jsonl")).stream().map(Jv::parse).toList();
        assertThat(child).as("observations of the session file, in the child core")
                .anyMatch(e -> e.member("source_ref") != null && "native/native-1/subagents/agent-a1.jsonl".equals(e.member("source_ref").member("artifact").text()));
        Jv response = child.stream().filter(e -> e.member("class_name") != null && "Model Response".equals(e.member("class_name").text())
                && e.member("assembly") != null && Integer.valueOf(3).equals(e.member("assembly").member("assembly_role_id") instanceof Jv.Num n ? n.value().intValue() : null)).findFirst().orElseThrow();
        assertThat(Jv.write(response)).as("the stream's tool request, with the session file's final usage").contains("toolu_READ").contains("\"output_tokens\":9");
        assertThat(child).as("the thinking only the session file reports").anyMatch(e -> Jv.write(e).contains("read it"));
        assertThat(Files.readAllLines(file).stream().map(Jv::parse)).as("nothing of the sub-agent in the root")
                .noneMatch(e -> e.member("source_ref") != null && e.member("source_ref").member("artifact") != null && e.member("source_ref").member("artifact").text().contains("subagents/"));
    }

    /**
     * Regenerates the TCK's sealed terminal child-scope cases ({@code -Dterminal.child.regenerate=<directory>}): each
     * case's store as the shipped receiver writes it, with the qualifier's assembly expectation beside it.
     */
    @Test void regenerateTckTerminalChildCases() throws Exception {
        regenerate("terminal.child.regenerate", java.util.Map.of("claude-heartbeat", List.of(HEARTBEAT_STREAM, List.of(TRANSCRIPT_PROMPT)),
                "claude-sub-agent", List.of(SUB_AGENT_STREAM, List.of(TRANSCRIPT_PROMPT)),
                "claude-sub-agent-heartbeat", List.of(SUB_AGENT_HEARTBEAT_STREAM, List.of(TRANSCRIPT_PROMPT)),
                "claude-sub-agent-nested", List.of(NESTED_SUB_AGENT_STREAM, List.of(TRANSCRIPT_PROMPT)),
                "claude-dangling-owner", List.of(DANGLING_PARENT_STREAM, List.of(TRANSCRIPT_PROMPT))));
        regenerate("terminal.child.regenerate", java.util.Map.of("claude-sub-agent-session", List.of(SUB_AGENT_SESSION_STREAM, List.of(TRANSCRIPT_PROMPT))), SUB_AGENT_SESSION);
    }

    /** Regenerates the TCK's sealed MCP one-shot case ({@code -Dterminal.mcp.regenerate=<directory>}), as above. */
    @Test void regenerateTckTerminalMcpCases() throws Exception {
        regenerate("terminal.mcp.regenerate", java.util.Map.of("claude-mcp", List.of(MCP_STREAM, MCP_TRANSCRIPT)));
    }

    /** Seals each case (its stream, then its session file) into the directory {@code property} names. */
    private void regenerate(String property, java.util.Map<String, List<List<String>>> cases) throws IOException {
        regenerate(property, cases, List.of());
    }

    /** As above, each case with the further acquired native files {@code extras}. */
    private void regenerate(String property, java.util.Map<String, List<List<String>>> cases, List<Extra> extras) throws IOException {
        String target = System.getProperty(property);
        org.junit.jupiter.api.Assumptions.assumeTrue(target != null, "set " + property + " to regenerate");
        for (var c : cases.entrySet()) {
            Path base = Files.createDirectory(temp.resolve(c.getKey())), out = Path.of(target, c.getKey());
            var q = qualifiedStream(base, c.getValue().get(0), c.getValue().get(1), extras);
            seal(base, q);
            copyTree(base.resolve("store"), out.resolve("store"));
            Files.copy(q.assemblyExpectation(), out.resolve("assembly-expectation.json"));
        }
    }

    private static void copyTree(Path from, Path to) throws IOException {
        try (var paths = Files.walk(from)) {
            for (Path p : paths.toList()) {
                Path t = to.resolve(from.relativize(p).toString());
                if (Files.isDirectory(p)) Files.createDirectories(t); else Files.copy(p, t);
            }
        }
    }

    /**
     * Claude Code 2.1.316 writes a heartbeat every 30 s while any tool call runs, naming the running call in
     * {@code parent_tool_use_id} (record shape as in the 2.1.316 bundle's {@code tool_heartbeat} -> {@code tool_progress} emitter).
     */
    @Test void aLongMcpCallsHeartbeatKeepsTheArchiveAccepted() throws Exception {
        Path file = assertAccepted(qualifiedStream(HEARTBEAT_STREAM));
        assertThat(file.getParent().resolve("children")).as("no child core for a heartbeat").doesNotExist();
    }

    /**
     * A real sub-agent's records carry the spawning Agent call in {@code parent_tool_use_id} and become a child run that
     * reads without a defect; the assembly oracle partitions its observations with the root's, so the archive is accepted.
     */
    @Test void aSubAgentChildRunIsAccepted() throws Exception {
        Path file = assertAccepted(qualifiedStream(SUB_AGENT_STREAM));
        assertThat(file.getParent().resolve("children/toolu_AGENT/events.jsonl")).as("the sub-agent child run").isRegularFile();
    }

    private static Jv.Obj completeAssociation(Path base) throws IOException {
        Jv m = Jv.parse(Files.readString(base.resolve("acquisition.json")));
        Jv.Arr sources = new Jv.Arr();
        for (Jv s : m.member("sources").elements()) {
            String role = s.member("role").text();
            sources.add(occurrence(base, s.member("path").text(), s.member("origin").text(), switch (role) { case "stdin" -> "STDIN"; case "stream" -> "STDOUT"; case "stderr" -> "STDERR"; default -> null; }));
        }
        sources.add(occurrence(base, "acquisition.json", "qualified-manifest", null));
        return association().put("acquisition", "COMPLETED").put("missing", new Jv.Arr()).put("sources", sources);
    }

    private static Jv.Obj occurrence(Path base, String path, String origin, String stream) throws IOException {
        byte[] content = Files.readAllBytes(base.resolve(path));
        return new Jv.Obj().put("sourceId", path).put("relativePath", path).put("origin", origin).put("size", content.length)
                .put("sha256", SchemaPin.sha256(content)).put("start", 0).put("end", content.length)
                .put("stream", stream == null ? Jv.Null.INSTANCE : new Jv.Str(stream)).put("nativeSessionId", "native-1")
                .put("nativeTurnId", Jv.Null.INSTANCE).put("nativeConnectionId", Jv.Null.INSTANCE).put("clientConnectionId", "connection-1");
    }
    private static byte[] nativeEnvelope(Jv a) {
        Jv.Obj p = (Jv.Obj) Jv.parse(new String(envelope(decision(), a), StandardCharsets.UTF_8));
        return bytes(p.put("profile", TerminalReceiver.CLAUDE_PROFILE));
    }
    private static Path events(Path store, String ack) {
        var refs = Jv.parse(ack).member("artifacts").elements(); return store.resolve(refs.get(refs.size() - 1).member("relativePath").text());
    }

    @Test void nativeProfileAssemblesOneArchiveAndPreservesFrozenTimeout() throws Exception {
        Path q = qualified(), store = temp.resolve("store"); TerminalReceiver receiver = new TerminalReceiver(store, "producer", WriterOptions.defaults());
        byte[] p = nativeEnvelope(completeAssociation(q)); String ack = receiver.receive(p, q);
        assertThat(receiver.receive(p, q)).isEqualTo(ack);
        var pkg = PackageReader.open(events(store, ack));
        assertThat(pkg.diagnostics).noneMatch(d -> d.severity().name().equals("DEFECT") || d.severity().name().equals("REFUSED"));
        assertThat(pkg.manifest.runUid()).isEqualTo("execution-1");
        assertThat(TerminalBindingReader.read(pkg.declarations).decision()).contains("TIMEOUT");
        assertThat(Files.readString(events(store, ack))).contains("\"assembly\"", "claude-code/2.1.316/sdk-stdio+session-file");
        Path detached = Files.createDirectory(temp.resolve("detached")).resolve("events.jsonl"); Files.copy(events(store, ack), detached);
        assertThat(PackageReader.open(detached).diagnosticsWithCode("TERMINAL_SOURCE_UNAVAILABLE")).hasSize(5);
    }

    @Test void missingOriginalNativeSourceProducesReaderDiagnostic() throws Exception {
        Path q = qualified(), store = temp.resolve("store");
        TerminalReceiver receiver = new TerminalReceiver(store, "producer", WriterOptions.defaults());
        Path archive = events(store, receiver.receive(nativeEnvelope(completeAssociation(q)), q));
        Path source = archive.getParent().resolve("sources/native.jsonl");
        Files.move(source, temp.resolve("preserved-native.jsonl"));
        assertThat(PackageReader.open(archive).diagnosticsWithCode("TERMINAL_NATIVE_INVALID")).hasSize(1);
    }

    @Test void pendingThenNativeRevisionRetainsInitialDecisionAndAck() throws Exception {
        Path q = qualified(), store = temp.resolve("store"); TerminalReceiver receiver = new TerminalReceiver(store, "producer", WriterOptions.defaults());
        byte[] pending = nativeEnvelope(association()); String first = receiver.receive(pending, q);
        Jv.Obj a = completeAssociation(q).put("revisionId", "final-1").put("priorRevisionId", "initial-1");
        String last = receiver.receiveRevision(Jv.parse(first).member("bindingId").text(), bytes(a), q);
        assertThat(receiver.receive(pending, q)).isEqualTo(first);
        assertThat(TerminalBindingReader.read(PackageReader.open(events(store, last)).declarations).decision()).contains("TIMEOUT");
    }

    @Test void missingCorruptWrongAssociatedAndPartialSourcesCannotPublish() throws Exception {
        Path q = qualified(); Jv.Obj original = completeAssociation(q);
        for (String fault : List.of("missing", "connection", "session", "span", "stream", "extra", "hash")) {
            Jv.Obj a = (Jv.Obj) Jv.parse(Jv.write(original)); Jv.Arr list = (Jv.Arr) a.member("sources"); Jv.Obj s = (Jv.Obj) list.elements().get(0);
            switch (fault) {
                case "missing" -> list.elements().remove(1);
                case "connection" -> s.put("clientConnectionId", "wrong");
                case "session" -> s.put("nativeSessionId", "wrong");
                case "span" -> s.put("start", 1);
                case "stream" -> s.put("stream", "STDOUT");
                case "extra" -> list.add(occurrence(q, "assembly-expectation.json", "extra", null));
                case "hash" -> s.put("sha256", "0".repeat(64));
                default -> throw new AssertionError(fault);
            }
            Path store = temp.resolve(fault); TerminalReceiver receiver = new TerminalReceiver(store, "producer", WriterOptions.defaults());
            assertThatThrownBy(() -> receiver.receive(nativeEnvelope(a), q)).isInstanceOfAny(IllegalArgumentException.class, TerminalIntegrityException.class);
            try (var markers = Files.list(store.resolve("terminal"))) { assertThat(markers.toList()).isEmpty(); }
        }
    }

    @Test void readerIndependentlyRejectsReassignedNativeConnectionAndMissingAssembly() throws Exception {
        Path q = qualified(), store = temp.resolve("store"); TerminalReceiver receiver = new TerminalReceiver(store, "producer", WriterOptions.defaults());
        String ack = receiver.receive(nativeEnvelope(completeAssociation(q)), q);
        Path event = events(store, ack); List<String> lines = Files.readAllLines(event);
        Jv.Obj run = (Jv.Obj) Jv.parse(lines.get(2));
        ((Jv.Obj) run.path("run", "launch_settings", "acquired_execution", "process")).put("connection_uid", "other");
        lines.set(2, Jv.write(run)); Path detached = Files.createDirectory(temp.resolve("bad")).resolve("events.jsonl"); Files.write(detached, lines);
        assertThat(PackageReader.open(detached).diagnosticsWithCode("TERMINAL_BINDING_INVALID")).hasSize(1);
        ((Jv.Obj) run.path("run", "launch_settings")).members().remove("acquired_execution");
        lines.set(2, Jv.write(run)); Files.write(detached, lines);
        assertThat(PackageReader.open(detached).diagnosticsWithCode("TERMINAL_BINDING_INVALID")).hasSize(1);
    }

    @Test void interruptedMissingAndMultipleTurnManifestsCannotClaimCompletedNative() throws Exception {
        Path q = qualified(); String original = Files.readString(q.resolve("acquisition.json"));
        for (String fault : List.of("interrupted", "turn", "incomplete", "version", "wrong-run", "shape", "offset-shape", "prompt")) {
            Jv.Obj m = (Jv.Obj) Jv.parse(original);
            switch (fault) {
                case "interrupted" -> ((Jv.Obj) m.member("sources").elements().get(0)).put("end", "INTERRUPTED");
                case "turn" -> ((Jv.Arr) m.member("turns")).add(m.member("turns").elements().get(0));
                case "incomplete" -> m.put("complete", false);
                case "version" -> ((Jv.Obj) m.path("run", "cli")).put("version", "2.1.317");
                case "wrong-run" -> m.put("acquisition_uid", "other");
                case "shape" -> ((Jv.Obj) m.member("run")).members().remove("cli");
                case "offset-shape" -> ((Jv.Obj) m.member("turns").elements().get(0)).members().remove("end_offsets");
                case "prompt" -> {
                    Files.writeString(q.resolve("stdin.bin"), "{\"type\":\"user\",\"message\":{\"role\":\"user\",\"content\":\"other\"}}\n");
                    Jv.Obj s = (Jv.Obj) m.member("sources").elements().get(0); byte[] b = Files.readAllBytes(q.resolve("stdin.bin"));
                    s.put("bytes", b.length).put("sha256", SchemaPin.sha256(b));
                    ((Jv.Obj) m.member("turns").elements().get(0).member("end_offsets")).put("STDIN", b.length);
                }
                default -> throw new AssertionError(fault);
            }
            Files.writeString(q.resolve("acquisition.json"), Jv.write(m));
            TerminalReceiver receiver = new TerminalReceiver(temp.resolve(fault), "producer", WriterOptions.defaults());
            assertThatThrownBy(() -> receiver.receive(nativeEnvelope(completeAssociation(q)), q)).isInstanceOf(IllegalArgumentException.class);
        }
    }
}
