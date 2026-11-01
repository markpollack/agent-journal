package io.github.markpollack.journal.ocsf.writer;

import io.github.markpollack.journal.tck.ocsf.NativeArtifact;
import io.github.markpollack.journal.tck.ocsf.OwnedContents;
import io.github.markpollack.journal.tck.ocsf.PackageReader;
import io.github.markpollack.journal.tck.ocsf.OracleReport;
import io.github.markpollack.journal.tck.ocsf.inventory.AcquisitionVerification;
import io.github.markpollack.journal.tck.ocsf.inventory.ClaudeAcquisition;
import io.github.markpollack.journal.tck.verify.Check;
import io.github.markpollack.journal.tck.verify.Json;
import io.github.markpollack.journal.tck.verify.NeutralJson;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;
import static org.assertj.core.api.Assertions.assertThat;

/** Synthetic regression for opaque document framing, common ownership and independent semantic rejection. */
class PersistedToolOutputTest {
    @TempDir Path work;
    private static final String FULL = "first\nsecond"; // no final LF: a frozen document, not a partial JSON line
    private static final String RESULT = """
            {"type":"user","uuid":"result","sessionId":"session","session_id":"session","version":"2.1.316","sourceToolAssistantUUID":"request",
            "toolUseResult":{"persistedOutputPath":"/native/tool-results/output.txt","persistedOutputSize":12,"stdout":"first"},
            "message":{"content":[{"type":"tool_result","tool_use_id":"call","content":"<persisted-output>\\nFull output saved to: /native/tool-results/output.txt\\nPreview (first 2KB):\\nfirst\\n</persisted-output>"}]}}
            """;
    private static final String REQUEST = """
            {"type":"assistant","uuid":"request","sessionId":"session","session_id":"session","version":"2.1.316",
            "message":{"id":"message","role":"assistant","model":"claude-sonnet-4-6","stop_reason":"tool_use",
            "usage":{"input_tokens":7,"cache_creation_input_tokens":2,"cache_read_input_tokens":3,"output_tokens":5},"content":[{"type":"tool_use","id":"call","name":"Bash","input":{"command":"printf output"}}]}}
            """;

    private ClaudeAcquisition.Qualified assemble() throws Exception {
        String request = NeutralJson.write(NeutralJson.parse(REQUEST));
        String result = NeutralJson.write(NeutralJson.parse(RESULT));
        Files.writeString(work.resolve("stream"), "{\"type\":\"system\",\"subtype\":\"init\",\"session_id\":\"session\",\"claude_code_version\":\"2.1.316\"}\n" + request + "\n" + result + "\n");
        Files.writeString(work.resolve("native"), request + "\n" + result + "\n");
        Files.writeString(work.resolve("output"), FULL);
        Json.JObject manifest = (Json.JObject) NeutralJson.parse("""
                {"kind":"agent-journal/acquired-execution","version":"0.1","profile":"claude-code-2.1.316-sdk-stdio-session-file",
                "profile_id":"claude-code/2.1.316/sdk-stdio+session-file","acquisition_uid":"run","complete":true,
                "run":{"run_uid":"run","session_uid":"session","capture_start":"2026-01-01T00:00:00Z","cli":{"name":"Claude Code","vendor_name":"Anthropic","version":"2.1.316"},"launch_settings":{"route":"sdk_stdio"}},
                "process":{"exit_code":null,"argv":["claude","--input-format","stream-json","--output-format","stream-json"]}}
                """);
        manifest.members().put("sources", new Json.JArray(List.of(source("stream", "stream", "claude_stream", "sdk_stdio", 3),
                source("native", "transcript", "claude_transcript", "native_session_file", 2), source("output", "sidecar", null, null, 0))));
        Path input = work.resolve("acquisition.json");
        Files.writeString(input, NeutralJson.write(manifest));
        var qualified = ClaudeAcquisition.qualify(input, work.resolve("qualified"));
        FinalAssembly.assemble(qualified.manifest(), work.resolve("package"));
        return qualified;
    }

    private Json source(String name, String role, String profile, String route, int records) throws Exception {
        Map<String, Json> fields = new LinkedHashMap<>();
        for (var e : Map.of("artifact", name, "path", name, "role", role, "state", "acquired", "end", "EOF",
                "origin", "sidecar".equals(role) ? "/native/tool-results/output.txt" : name, "owner_session_uid", "session", "acquisition", "run").entrySet()) {
            fields.put(e.getKey(), new Json.JString(e.getValue()));
        }
        fields.put("required", new Json.JBool(true));
        fields.put("sha256", new Json.JString(NativeArtifact.sha256(Files.readAllBytes(work.resolve(name)))));
        fields.put("bytes", new Json.JNumber(Long.toString(Files.size(work.resolve(name)))));
        if (profile == null) { fields.put("archive_only", new Json.JBool(true)); }
        else {
            fields.put("profile", new Json.JString(profile)); fields.put("route", new Json.JString(route));
            fields.put("framing", new Json.JString("lines")); fields.put("records", new Json.JNumber(Integer.toString(records)));
        }
        return new Json.JObject(fields);
    }

    private OracleReport verify(Path file, ClaudeAcquisition.Qualified q) throws Exception {
        return new AcquisitionVerification().verify(PackageReader.open(file), q.manifest().getParent(), q.manifest(), q.assemblyExpectation());
    }

    @Test void wholeDocumentWithoutLfIsCompleteAndOwnedByOneResult() throws Exception {
        var q = assemble();
        Path events = work.resolve("package/events.jsonl");
        OracleReport report = verify(events, q);
        assertThat(report.defects()).isEmpty();
        assertThat(io.github.markpollack.journal.tck.ocsf.OcsfReaderTool.acceptance(report).exit()).isZero();
        assertThat(report.outcome("acquisition.additional_native", "output")).isEqualTo(Check.Outcome.VERIFIED);
        assertThat(report.outcome("acquisition.complete", "main")).isEqualTo(Check.Outcome.VERIFIED);
        var pkg = PackageReader.open(events);
        var result = pkg.canonical().stream().filter(e -> "call".equals(e.text("tool_call_uid")) && Integer.valueOf(2).equals(e.activityId)).findFirst().orElseThrow();
        assertThat(OwnedContents.of(pkg, result)).hasSize(1);
        assertThat(OwnedContents.of(pkg, result).get(0).path("source", "raw_data").text()).isEqualTo(FULL);
        assertThat(pkg.canonical().stream().filter(e -> "tool_call".equals(e.className))).hasSize(2);
    }

    @Test void corruptedOmittedAndWronglyOwnedContentFailsSemanticPredicates() throws Exception {
        var q = assemble();
        List<Json> original = Files.readAllLines(work.resolve("package/events.jsonl")).stream().map(NeutralJson::parse).toList();
        for (String mode : List.of("corrupt", "omit", "wrong-owner")) {
            List<Json> rows = original.stream().map(r -> NeutralJson.parse(NeutralJson.write(r))).collect(java.util.stream.Collectors.toList());
            if ("wrong-owner".equals(mode)) {
                // Valid JSON declaration mutation; raw source/manifest hashes remain original.
                for (Json row : rows) { alterBinding(row); }
            }
            else {
                Json content = rows.stream().filter(r -> r.member("raw_data") != null && FULL.equals(r.member("raw_data").text())).findFirst().orElseThrow();
                if ("omit".equals(mode)) { rows.remove(content); }
                else { ((Json.JObject) content).members().put("raw_data", new Json.JString("changed bytes")); }
            }
            Path dir = work.resolve(mode); Files.createDirectory(dir);
            Path file = dir.resolve("events.jsonl");
            Files.writeString(file, String.join("\n", rows.stream().map(NeutralJson::write).toList()) + "\n");
            OracleReport report = verify(file, q);
            assertThat(report.outcome("acquisition.additional_native", "output")).as(mode).isEqualTo(Check.Outcome.DEFECTIVE);
            assertThat(report.outcome("wrong-owner".equals(mode) ? "acquisition.tool_output_binding" : "acquisition.tool_output_bytes", "output")).isEqualTo(Check.Outcome.DEFECTIVE);
        }
    }

    private static void alterBinding(Json row) {
        if (row instanceof Json.JObject obj) {
            if (obj.member("tool_result_binding") instanceof Json.JObject binding) { binding.members().put("tool_call_uid", new Json.JString("other-call")); }
            obj.members().values().forEach(PersistedToolOutputTest::alterBinding);
        }
        else if (row instanceof Json.JArray array) { array.elements().forEach(PersistedToolOutputTest::alterBinding); }
    }
}
