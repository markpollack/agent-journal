package io.github.markpollack.journal.tck.ocsf.inventory;

import io.github.markpollack.journal.tck.verify.Json;
import io.github.markpollack.journal.tck.verify.NeutralJson;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.nio.file.Files;
import java.io.IOException;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

/** Synthetic controls: native links and content agree independently of filename proximity or copied metadata. */
class ClaudeToolOutputTest {
    @TempDir Path work;
    private static final String REQUEST = """
            {"type":"assistant","uuid":"request","sessionId":"session","message":{"content":[{"type":"tool_use","id":"call","name":"Bash","input":{"command":"printf output"}}]}}
            """;
    private static final String RESULT = """
            {"type":"user","uuid":"result","sessionId":"session","sourceToolAssistantUUID":"request",
            "toolUseResult":{"persistedOutputPath":"/native/session/tool-results/output.txt","persistedOutputSize":12,"stdout":"first"},
            "message":{"content":[{"type":"tool_result","tool_use_id":"call","content":"<persisted-output>\\nFull output saved to: /native/session/tool-results/output.txt\\nPreview (first 2KB):\\nfirst\\n</persisted-output>"}]}}
            """;

    private Json manifest(String nativeResult) throws IOException {
        Files.writeString(work.resolve("out.txt"), "first\nsecond");
        Files.writeString(work.resolve("native.jsonl"), REQUEST.strip() + "\n" + NeutralJson.write(NeutralJson.parse(nativeResult)) + "\n");
        Files.writeString(work.resolve("stream.jsonl"), NeutralJson.write(NeutralJson.parse(RESULT)) + "\n");
        return NeutralJson.parse("""
                {"acquisition_uid":"run","run":{"run_uid":"run","session_uid":"session"},"sources":[
                {"state":"acquired","role":"transcript","artifact":"native","path":"native.jsonl"},
                {"state":"acquired","role":"stream","artifact":"stream","path":"stream.jsonl"},
                {"state":"acquired","role":"sidecar","artifact":"output","path":"out.txt","origin":"/native/session/tool-results/output.txt",
                "owner_session_uid":"session","acquisition":"run"}]}
                """);
    }

    @Test void bindsUniqueNativeRequestResultAndWholeTextInsteadOfThePrefix() throws IOException {
        Json m = manifest(RESULT);
        Json binding = ClaudeToolOutput.bindings(m, work).get("output");
        assertThat(binding.path("tool_call_uid").text()).isEqualTo("call");
        assertThat(binding.path("request_ref", "record_index")).isEqualTo(new Json.JNumber("1"));
        assertThat(binding.path("result_ref", "record_index")).isEqualTo(new Json.JNumber("2"));
        assertThat(ClaudeToolOutput.references(m, work)).containsExactly("/native/session/tool-results/output.txt");
    }

    @Test void rejectsWrongIdentityExtentPrefixAndAmbiguousResults() throws IOException {
        for (String bad : List.of(RESULT.replace("\"sourceToolAssistantUUID\":\"request\"", "\"sourceToolAssistantUUID\":\"foreign\""),
                RESULT.replace("\"tool_use_id\":\"call\"", "\"tool_use_id\":\"other\""),
                RESULT.replace("\"persistedOutputSize\":12", "\"persistedOutputSize\":11"),
                RESULT.replace("\"stdout\":\"first\"", "\"stdout\":\"other\""),
                RESULT.replace("\"sessionId\":\"session\"", "\"sessionId\":\"other\""))) {
            assertThat(ClaudeToolOutput.bindings(manifest(bad), work)).as(bad).isEmpty();
        }
        Json m = manifest(RESULT);
        Files.writeString(work.resolve("native.jsonl"), Files.readString(work.resolve("native.jsonl")) + NeutralJson.write(NeutralJson.parse(RESULT)) + "\n");
        assertThat(ClaudeToolOutput.bindings(m, work)).isEmpty();
    }

    @Test void copiedApiMetadataAndOtherSidecarsAreNotNewResults() throws IOException {
        Json m = manifest(RESULT.replace("\"type\":\"user\"", "\"type\":\"api-request-blob\""));
        assertThat(ClaudeToolOutput.bindings(m, work)).isEmpty();
        m = manifest(RESULT);
        Json.JObject sidecar = (Json.JObject) m.member("sources").elements().get(2);
        sidecar.members().put("origin", new Json.JString("/native/session/tool-results/unrelated.txt"));
        assertThat(ClaudeToolOutput.bindings(m, work)).isEmpty();
    }
}
