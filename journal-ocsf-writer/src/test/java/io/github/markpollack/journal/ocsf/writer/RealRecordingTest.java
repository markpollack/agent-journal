package io.github.markpollack.journal.ocsf.writer;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Real Claude Code 2.1.316 runs recorded through Client (scrubbed derivatives of the private corpus, see each case's
 * SCRUBBED.md), sealed through this writer and read by the independent reader. Every expectation is read from the run's
 * own native bytes.
 */
class RealRecordingTest {
    @TempDir Path temp;

    private RealRecording.Sealed sealed(String name) throws IOException {
        RealRecording.Sealed s = RealRecording.seal(RealRecording.PUBLIC.resolve(name), temp.resolve(name));
        assertThat(s.acceptance().verdict()).as(s.acceptance().toString()).isEqualTo("ACCEPTED");
        return s;
    }

    /** The parsed records of the run's source of {@code role}, and its artifact name. */
    private static Map.Entry<String, List<Jv>> source(String name, String role) throws IOException {
        Path dir = RealRecording.nativeDir(RealRecording.PUBLIC.resolve(name));
        Jv src = Jv.parse(Files.readString(dir.resolve("client-acquisition.json"))).member("sources").elements().stream()
                .filter(s -> role.equals(s.member("role").text())).findFirst().orElseThrow();
        return Map.entry(src.member("artifact").text(), RealRecording.lines(dir.resolve(src.member("path").text())));
    }

    private static String text(Jv v, String... path) {
        for (String p : path) { v = v == null ? null : v.member(p); }
        return v == null || v instanceof Jv.Null ? null : v.text();
    }

    private static Integer integer(Jv v, String... path) {
        for (String p : path) { v = v == null ? null : v.member(p); }
        return v instanceof Jv.Num n ? n.value().intValue() : null;
    }

    /** A real heartbeat names the MCP call in progress, not an owner: every one is in the root core, and no child core exists. */
    @Test void theRealHeartbeatsAreStoredInTheRootCore() throws IOException {
        RealRecording.Sealed s = sealed("heartbeat");
        var stream = source("heartbeat", "stream");
        List<Integer> heartbeats = new ArrayList<>();
        for (int i = 0; i < stream.getValue().size(); i++) {
            if ("tool_progress".equals(text(stream.getValue().get(i), "type"))) { heartbeats.add(i + 1); }
        }
        assertThat(heartbeats).as("the run's heartbeats").isNotEmpty();
        Set<Integer> rootRecords = RealRecording.lines(s.events()).stream().filter(e -> stream.getKey().equals(text(e, "source_ref", "artifact")))
                .map(e -> integer(e, "source_ref", "record_index")).collect(Collectors.toSet());
        assertThat(rootRecords).containsAll(heartbeats);
        assertThat(s.events().getParent().resolve("children")).doesNotExist();
    }

    /** A real MCP result's mcp member states only its basis; the consolidated result takes no field evidence for it. */
    @Test void theRealMcpResultsAreNotRicherForTheirProvenance() throws IOException {
        RealRecording.Sealed s = sealed("mcp");
        List<Jv> basisOnly = RealRecording.lines(s.events()).stream()
                .filter(e -> Integer.valueOf(3).equals(integer(e, "assembly", "assembly_role_id")) && e.member("mcp") instanceof Jv.Obj m
                        && m.members().keySet().equals(Set.of("basis"))).toList();
        assertThat(basisOnly).as("consolidated results whose mcp member is its basis alone").isNotEmpty();
        assertThat(basisOnly).allSatisfy(e -> assertThat(Jv.write(e.member("assembly"))).doesNotContain("\"field_pointer\":\"/mcp\""));
    }

    /**
     * A real sub-agent's own session file is read into the spawning call's child core and nowhere else, and a response
     * both sources write carries the session file's final output tokens, not the stream's provisional ones.
     */
    @Test void theRealSubAgentsSessionFileIsReadIntoItsChildCore() throws IOException {
        RealRecording.Sealed s = sealed("sub-agent");
        var stream = source("sub-agent", "stream");
        var session = source("sub-agent", "child_transcript");
        String spawn = stream.getValue().stream().filter(r -> "assistant".equals(text(r, "type")))
                .flatMap(r -> r.member("message").member("content").elements().stream())
                .filter(b -> "tool_use".equals(text(b, "type")) && "Agent".equals(text(b, "name"))).map(b -> text(b, "id")).findFirst().orElseThrow();
        List<Jv> child = RealRecording.lines(s.child(spawn));
        assertThat(child).anyMatch(e -> session.getKey().equals(text(e, "source_ref", "artifact")));
        assertThat(RealRecording.lines(s.events())).noneMatch(e -> session.getKey().equals(text(e, "source_ref", "artifact")));
        Map<String, Integer> finalOutput = session.getValue().stream().filter(r -> "assistant".equals(text(r, "type")) && integer(r, "message", "usage", "output_tokens") != null)
                .collect(Collectors.toMap(r -> text(r, "uuid"), r -> integer(r, "message", "usage", "output_tokens"), (a, b) -> a));
        Jv shared = stream.getValue().stream().filter(r -> spawn.equals(text(r, "parent_tool_use_id")) && "assistant".equals(text(r, "type"))
                && finalOutput.containsKey(text(r, "uuid")) && !finalOutput.get(text(r, "uuid")).equals(integer(r, "message", "usage", "output_tokens"))).findFirst().orElseThrow();
        String uuid = text(shared, "uuid");
        Jv response = child.stream().filter(e -> Integer.valueOf(3).equals(integer(e, "assembly", "assembly_role_id"))
                && String.valueOf(text(e, "assembly", "entity_uid")).endsWith("claude.record_uuid:" + uuid)).findFirst().orElseThrow();
        assertThat(response.member("usage_observations").elements()).extracting(u -> integer(u, "output_tokens")).contains(finalOutput.get(uuid));
    }

    /**
     * The real MCP run with one tool request's input replaced, identically in the stream and the session file, by
     * arguments whose keys are {@code basis} and {@code data} (an edited real run: an MCP tool may take parameters of
     * those names). The sealed manifest's extents are restated. Returns the edited run and the call's id.
     */
    Map.Entry<Path, String> withPayloadKeyArguments(Path into) throws IOException {
        Jv input = Jv.parse("{\"basis\":\"business input\",\"data\":42}");
        copyTree(RealRecording.PUBLIC.resolve("mcp"), into);
        Path dir = RealRecording.nativeDir(into);
        Jv.Obj manifest = (Jv.Obj) Jv.parse(Files.readString(dir.resolve("client-acquisition.json")));
        Path stdout = null, session = null;
        for (Jv src : manifest.member("sources").elements()) {
            if ("stream".equals(text(src, "role"))) { stdout = dir.resolve(text(src, "path")); }
            if ("transcript".equals(text(src, "role"))) { session = dir.resolve(text(src, "path")); }
        }
        List<Jv> sessionRecords = RealRecording.lines(session);
        Set<String> sessionUuids = sessionRecords.stream().map(r -> text(r, "uuid")).filter(java.util.Objects::nonNull).collect(Collectors.toSet());
        List<Jv> streamRecords = RealRecording.lines(stdout);
        Jv request = streamRecords.stream().filter(r -> "assistant".equals(text(r, "type")) && sessionUuids.contains(text(r, "uuid"))
                && r.member("message").member("content").elements().stream().anyMatch(b -> "tool_use".equals(text(b, "type")))).findFirst().orElseThrow();
        String uuid = text(request, "uuid");
        String id = null;
        for (Jv r : java.util.stream.Stream.concat(streamRecords.stream(), sessionRecords.stream()).toList()) {
            if (!uuid.equals(text(r, "uuid"))) { continue; }
            for (Jv b : r.member("message").member("content").elements()) {
                if ("tool_use".equals(text(b, "type"))) { id = text(b, "id"); ((Jv.Obj) b).put("input", input); }
            }
            for (String wire : List.of("wire_tool_inputs", "wireToolInputs")) {
                if (r.member(wire) instanceof Jv.Obj w) { w.members().replaceAll((k, v) -> input); }
            }
        }
        Files.writeString(stdout, streamRecords.stream().map(Jv::write).collect(Collectors.joining("\n", "", "\n")));
        Files.writeString(session, sessionRecords.stream().map(Jv::write).collect(Collectors.joining("\n", "", "\n")));
        for (Jv src : manifest.member("sources").elements()) {
            byte[] bytes = Files.readAllBytes(dir.resolve(text(src, "path")));
            ((Jv.Obj) src).put("bytes", bytes.length).put("sha256", SchemaPin.sha256(bytes));
            if (src.member("records") != null) { ((Jv.Obj) src).put("records", (int) new String(bytes, java.nio.charset.StandardCharsets.UTF_8).lines().filter(l -> !l.isBlank()).count()); }
            if ("stream".equals(text(src, "role"))) { ((Jv.Obj) manifest.member("turns").elements().get(0).member("end_offsets")).put("STDOUT", bytes.length); }
        }
        Files.writeString(dir.resolve("client-acquisition.json"), Jv.write(manifest));
        return Map.entry(into, id);
    }

    /** A real MCP request whose arguments' keys are basis and data seals, is read without defect, and keeps them both. */
    @Test void argumentsWithBasisAndDataKeysAreKeptAndAccepted() throws IOException {
        var edited = withPayloadKeyArguments(temp.resolve("payload-keys"));
        RealRecording.Sealed s = RealRecording.seal(edited.getKey(), temp.resolve("payload-keys-sealed"));
        assertThat(s.acceptance().verdict()).as(s.acceptance().toString()).isEqualTo("ACCEPTED");
        Jv request = RealRecording.lines(s.events()).stream().filter(e -> Integer.valueOf(3).equals(integer(e, "assembly", "assembly_role_id"))
                && Integer.valueOf(1).equals(integer(e, "activity_id")) && edited.getValue().equals(text(e, "tool_call_uid"))).findFirst().orElseThrow();
        assertThat(Jv.write(request.member("arguments"))).isEqualTo("{\"basis\":\"business input\",\"data\":42}");
    }

    /**
     * The archived envelope carries the terminal profile of the qualifier the acquisition's own profile selects (Stage A
     * review F4: read from the qualifier, not the receiver's constant), and qualification leaves the Client's sealed
     * manifest byte-identical.
     */
    @Test void theRegisteredQualifiersTerminalProfileIsArchivedAndTheSealedManifestIsKept() throws IOException {
        RealRecording.Sealed s = sealed("heartbeat");
        Path sealedManifest = RealRecording.nativeDir(RealRecording.PUBLIC.resolve("heartbeat")).resolve("client-acquisition.json");
        var qualifier = io.github.markpollack.journal.tck.ocsf.inventory.AcquisitionQualifier.forProfile(Jv.parse(Files.readString(sealedManifest)).member("profile").text()).orElseThrow();
        assertThat(Files.readString(s.events())).as("the archived terminal envelope").contains("\\\"profile\\\":\\\"" + qualifier.terminalProfile() + "\\\"");
        assertThat(Files.readAllBytes(s.qualified().resolve("client-acquisition.json"))).isEqualTo(Files.readAllBytes(sealedManifest));
    }

    /**
     * Regenerates the TCK's sealed real-recording cases ({@code -Dterminal.real.regenerate=<directory>}): each scrubbed
     * run's store as the shipped receiver writes it, with the qualifier's assembly expectation beside it.
     */
    @Test void regenerateTckRealRecordingCases() throws IOException {
        String target = System.getProperty("terminal.real.regenerate");
        Assumptions.assumeTrue(target != null, "set terminal.real.regenerate to regenerate");
        for (String name : List.of("heartbeat", "mcp", "sub-agent")) {
            RealRecording.Sealed s = sealed(name);
            Path out = Path.of(target, "claude-" + name);
            copyTree(s.store(), out.resolve("store"));
            Files.copy(s.qualified().resolve("assembly-expectation.json"), out.resolve("assembly-expectation.json"));
            Files.copy(RealRecording.PUBLIC.resolve(name).resolve("SCRUBBED.md"), out.resolve("SCRUBBED.md"));
        }
        var edited = withPayloadKeyArguments(temp.resolve("payload-keys"));
        RealRecording.Sealed s = RealRecording.seal(edited.getKey(), temp.resolve("payload-keys-sealed"));
        assertThat(s.acceptance().verdict()).as(s.acceptance().toString()).isEqualTo("ACCEPTED");
        Path out = Path.of(target, "claude-mcp-payload-keys");
        copyTree(s.store(), out.resolve("store"));
        Files.copy(s.qualified().resolve("assembly-expectation.json"), out.resolve("assembly-expectation.json"));
        Files.writeString(out.resolve("EDITED.md"), "# Edited real run\n\nThe scrubbed real MCP run (`claude-mcp`) with tool request `" + edited.getValue()
                + "`'s input replaced, identically in the stream and the session file, by `{\"basis\":\"business input\",\"data\":42}`; "
                + "the sealed manifest's extents restated. Made by `RealRecordingTest.withPayloadKeyArguments`.\n");
    }

    static void copyTree(Path from, Path to) throws IOException {
        Files.createDirectories(to);
        try (Stream<Path> paths = Files.walk(from)) {
            for (Path p : paths.toList()) {
                Path t = to.resolve(from.relativize(p).toString());
                if (Files.isDirectory(p)) { Files.createDirectories(t); } else { Files.copy(p, t); }
            }
        }
    }
}
