package io.github.markpollack.journal.ocsf.writer;

import io.github.markpollack.journal.tck.ocsf.CanonicalExport;
import io.github.markpollack.journal.tck.ocsf.Diagnostic;
import io.github.markpollack.journal.tck.ocsf.Event;
import io.github.markpollack.journal.tck.ocsf.OcsfPackage;
import io.github.markpollack.journal.tck.ocsf.OcsfOracle;
import io.github.markpollack.journal.tck.verify.Check;
import io.github.markpollack.journal.tck.ocsf.PackageReader;
import io.github.markpollack.journal.tck.ocsf.inventory.ClaudeAcquisition;
import java.io.IOException;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.assertThat;

/** Synthetic per-turn evidence examples. Caller identity metadata is a proposed seam, not a live session qualification. */
class SessionCanonicalReaderTest {
    @TempDir Path work;

    @Test
    void twoTurnsShareProviderSessionAndConnectionWithoutSharingInvocationOrToolOwnership() throws IOException {
        OcsfPackage first = turn("first", "connection-1", true);
        OcsfPackage second = turn("second", "connection-1", true);
        checkTurn(first, "first", "connection-1");
        checkTurn(second, "second", "connection-1");
        assertThat(first.manifest.runUid()).isNotEqualTo(second.manifest.runUid());
        // Reusing a native tool id across invocations does not merge their requests or results.
        assertThat(first.events("tool_call", 1)).hasSize(1);
        assertThat(second.events("tool_call", 1)).hasSize(1);
        assertThat(first.events("tool_call", 2)).hasSize(1);
        assertThat(second.events("tool_call", 2)).hasSize(1);
        assertThat(first.manifest.sessionUid()).isEqualTo(second.manifest.sessionUid());
    }

    @Test
    void resumedConnectionRetainsItsIdentityAndSuccessDoesNotCompleteInterruptedCapture() throws IOException {
        OcsfPackage interrupted = turn("first", "connection-1", false);
        OcsfPackage resumed = turn("second", "connection-2", true);
        checkTurn(interrupted, "first", "connection-1");
        checkTurn(resumed, "second", "connection-2");
        assertThat(interrupted.events("prompt_turn", 2)).singleElement().satisfies(e ->
                assertThat(e.get("status_id").scalarText()).isEqualTo("1"));
        assertThat(interrupted.declarations.recording().path("acquisition", "status").text()).isEqualTo("incomplete");
        assertThat(interrupted.manifest.packageState()).isEqualTo("INTERRUPTED");
        assertThat(resumed.manifest.packageState()).isEqualTo("COMPLETED");
    }

    @Test
    void copiedFileKeepsExactPromptTurnContentAndScopedMeasurements() throws IOException {
        OcsfPackage pkg = turn("first", "connection-1", true);
        checkTurn(pkg, "first", "connection-1");
        Event submitted = pkg.events("prompt_turn", 1).get(0);
        assertThat(submitted.get("turn_uid").text()).isEqualTo("native-prompt-first");
        assertThat(submitted.get("content_blocks").elements().get(0).member("text").text()).isEqualTo("prompt first Ω");
        Event terminal = pkg.events("prompt_turn", 2).get(0);
        assertThat(terminal.get("usage_observations").elements().get(0).member("scope_id").scalarText()).isEqualTo("4");
        assertThat(terminal.get("cost_observation", "scope_id").scalarText()).isEqualTo("4");
        // Native prompt identity and caller/Bud turn identity have separate provenance.
        assertThat(submitted.get("turn_uid").text()).isNotEqualTo("bud-first");
        StringWriter exported = new StringWriter();
        CanonicalExport.write(pkg, exported);
        assertThat(exported.toString()).contains("native-prompt-first", "bud-first", "connection-1", "prompt first Ω");
    }

    @Test
    void detachedReaderRejectsAValidJsonEventMovedToAnotherInvocation() throws IOException {
        OcsfPackage pkg = turn("first", "connection-1", true);
        Path tampered = Files.createDirectory(work.resolve("wrong-invocation")).resolve("events.jsonl");
        List<String> lines = Files.readAllLines(pkg.dir.resolve("events.jsonl"));
        List<String> changed = lines.stream().map(line -> {
            Jv value = Jv.parse(line);
            if (value.member("declaration") == null && value.member("journal") instanceof Jv.Obj journal) {
                journal.put("run_uid", "invocation-second");
            }
            return Jv.write(value);
        }).toList();
        assertThat(changed).isNotEqualTo(lines);
        Files.write(tampered, changed);
        assertThat(PackageReader.open(tampered).diagnosticsWithCode("RUN_UID_MISMATCH")).isNotEmpty();
    }

    @Test
    void independentNativeReaderRejectsChangedAndOmittedPromptOccurrences() throws IOException {
        OcsfPackage pkg = turn("first", "connection-1", true);
        Path nativeDir = pkg.dir.getParent().resolve("qualified");
        var control = OcsfOracle.verify(pkg, nativeDir);
        assertThat(control.outcome("prompt.values", "main/native.jsonl")).as(control.toString()).isEqualTo(Check.Outcome.VERIFIED);
        List<String> lines = Files.readAllLines(pkg.dir.resolve("events.jsonl"));
        Path wrong = Files.createDirectory(work.resolve("wrong-prompt")).resolve("events.jsonl");
        Files.write(wrong, lines.stream().map(line -> {
            Jv value = Jv.parse(line);
            if (value.member("declaration") == null && value.member("turn_uid") != null) {
                ((Jv.Obj) value).put("turn_uid", "native-prompt-second");
            }
            return Jv.write(value);
        }).toList());
        assertThat(OcsfOracle.verify(PackageReader.open(wrong), nativeDir).outcome("prompt.values", "main/native.jsonl"))
                .isEqualTo(Check.Outcome.DEFECTIVE);
        Path omitted = Files.createDirectory(work.resolve("omitted-prompt")).resolve("events.jsonl");
        Files.write(omitted, lines.stream().filter(line -> {
            Jv value = Jv.parse(line);
            return value.member("declaration") != null || value.member("turn_uid") == null;
        }).toList());
        assertThat(OcsfOracle.verify(PackageReader.open(omitted), nativeDir).outcome("population.prompts", "main/native.jsonl"))
                .isEqualTo(Check.Outcome.DEFECTIVE);
    }

    private void checkTurn(OcsfPackage pkg, String id, String connection) {
        assertThat(pkg.refused()).isFalse();
        assertThat(pkg.diagnostics(Diagnostic.Severity.DEFECT)).isEmpty();
        assertThat(pkg.manifest.runUid()).isEqualTo("invocation-" + id);
        assertThat(pkg.manifest.sessionUid()).isEqualTo("provider-session");
        var evidence = pkg.manifest.json().path("run", "launch_settings", "session_evidence");
        assertThat(evidence.member("conversation_uid").text()).isEqualTo("conversation");
        assertThat(evidence.member("turn_uid").text()).isEqualTo("bud-" + id);
        assertThat(evidence.member("connection_uid").text()).isEqualTo(connection);
        assertThat(pkg.events("prompt_turn", 1)).singleElement().satisfies(e ->
                assertThat(e.get("turn_uid").text()).isEqualTo("native-prompt-" + id));
    }

    private OcsfPackage turn(String id, String connection, boolean complete) throws IOException {
        Path root = Files.createDirectory(work.resolve(id));
        String stream = """
                {"type":"system","subtype":"init","session_id":"provider-session","claude_code_version":"2.1.316"}
                {"type":"result","subtype":"success","is_error":false,"session_id":"provider-session","uuid":"result-ID","result":"done ID","usage":{"input_tokens":10,"output_tokens":2},"total_cost_usd":0.01}
                """.replace("ID", id);
        String nativeText = """
                {"type":"user","uuid":"prompt-ID","promptId":"native-prompt-ID","sessionId":"provider-session","version":"2.1.316","entrypoint":"sdk-java","message":{"role":"user","content":"prompt ID Ω"}}
                {"type":"assistant","uuid":"assistant-ID","sessionId":"provider-session","version":"2.1.316","entrypoint":"sdk-java","message":{"id":"response-ID","role":"assistant","model":"synthetic-model","content":[{"type":"tool_use","id":"reused-tool","name":"Read","input":{"file_path":"ID.txt"}}]}}
                {"type":"user","uuid":"tool-result-ID","sessionId":"provider-session","version":"2.1.316","entrypoint":"sdk-java","message":{"role":"user","content":[{"type":"tool_result","tool_use_id":"reused-tool","content":"content ID"}]}}
                """.replace("ID", id);
        Files.writeString(root.resolve("stdout.bin"), stream);
        Files.writeString(root.resolve("native.jsonl"), nativeText);
        Jv.Obj run = new Jv.Obj().put("run_uid", "invocation-" + id).put("session_uid", "provider-session")
                .put("capture_start", "2026-01-01T00:00:00Z")
                .put("cli", new Jv.Obj().put("name", "Claude Code").put("version", "2.1.316").put("vendor_name", "Anthropic"))
                .put("launch_settings", new Jv.Obj().put("route", "sdk_stdio").put("session_evidence", new Jv.Obj()
                        .put("conversation_uid", "conversation").put("turn_uid", "bud-" + id).put("connection_uid", connection)))
                .put("synthetic", new Jv.Obj().put("label", "deterministic per-turn identity example; not Client evidence"));
        Jv.Obj manifest = new Jv.Obj().put("kind", "agent-journal/acquired-execution").put("version", "0.1")
                .put("profile", ClaudeAcquisition.PROFILE).put("profile_id", ClaudeAcquisition.PROFILE_ID)
                .put("acquisition_uid", "invocation-" + id).put("complete", complete).put("run", run)
                .put("process", new Jv.Obj().put("status", "unknown").put("exit_code", Jv.Null.INSTANCE)
                        .put("argv", Jv.arr("claude", "--input-format", "stream-json", "--output-format", "stream-json")))
                .put("sources", new Jv.Arr().add(source(root, "stdout.bin", "stream", "claude_stream", "sdk_stdio", id, 2))
                        .add(source(root, "native.jsonl", "transcript", "claude_transcript", "native_session_file", id, 3)));
        Path input = root.resolve("acquisition.json");
        Files.writeString(input, Jv.write(manifest));
        var qualified = ClaudeAcquisition.qualify(input, root.resolve("qualified"));
        Path output = root.resolve("package");
        FinalAssembly.assemble(qualified.manifest(), output);
        Path detached = Files.createDirectories(root.resolve("detached"));
        Files.copy(output.resolve("events.jsonl"), detached.resolve("events.jsonl"));
        return PackageReader.open(detached.resolve("events.jsonl"));
    }

    private Jv.Obj source(Path root, String file, String role, String profile, String route, String id, int records) throws IOException {
        byte[] bytes = Files.readAllBytes(root.resolve(file));
        return new Jv.Obj().put("artifact", file).put("path", file).put("role", role).put("profile", profile).put("route", route)
                .put("framing", "lines").put("state", "acquired").put("required", true).put("end", "EOF")
                .put("origin", "synthetic invocation-" + id).put("acquisition", "invocation-" + id)
                .put("owner_session_uid", "provider-session").put("records", records).put("bytes", bytes.length).put("sha256", SchemaPin.sha256(bytes));
    }
}
