package io.github.markpollack.journal.tck.ocsf.inventory;

import io.github.markpollack.journal.tck.ocsf.NativeArtifact;
import io.github.markpollack.journal.tck.verify.Json;
import io.github.markpollack.journal.tck.verify.NeutralJson;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

/** Synthetic identity and integrity controls, not live source evidence. */
class ClaudeAcquisitionTest {
    @TempDir Path work;

    private Path manifest() throws IOException {
        Files.writeString(work.resolve("stdout.bin"), """
                {"type":"system","subtype":"init","session_id":"session","claude_code_version":"2.1.316"}
                {"type":"rate_limit_event","session_id":"session","rate_limit_info":{"status":"allowed"}}
                """);
        Files.writeString(work.resolve("native.jsonl"), """
                {"type":"user","sessionId":"session","version":"2.1.316","message":{"role":"user","content":"Read sentinel.txt"}}
                """);
        Json.JObject m = (Json.JObject) NeutralJson.parse("""
                {"kind":"agent-journal/acquired-execution","version":"0.1",
                "profile":"claude-code-2.1.316-sdk-stdio-session-file","profile_id":"claude-code/2.1.316/sdk-stdio+session-file",
                "acquisition_uid":"run","complete":true,
                "run":{"run_uid":"run","session_uid":"session","cli":{"name":"Claude Code","version":"2.1.316"},
                "launch_settings":{"route":"sdk_stdio"}},
                "process":{"exit_code":null,"argv":["claude","--input-format","stream-json","--output-format","stream-json"]},
                "sources":[]}
                """);
        m.members().put("sources", new Json.JArray(java.util.List.of(source("stream", "stdout.bin", "claude_stream", "sdk_stdio", 2),
                source("transcript", "native.jsonl", "claude_transcript", "native_session_file", 1))));
        Path path = work.resolve("sealed.json");
        Files.writeString(path, NeutralJson.write(m));
        return path;
    }

    private Json source(String role, String path, String profile, String route, int records) throws IOException {
        Json.JObject source = (Json.JObject) NeutralJson.parse("""
                {"state":"acquired","required":true,"origin":"observed","acquisition":"run",
                "end":"EOF","framing":"lines","owner_session_uid":"session"}
                """);
        for (var e : java.util.Map.of("role", role, "artifact", path, "path", path, "profile", profile, "route", route).entrySet()) {
            source.members().put(e.getKey(), new Json.JString(e.getValue()));
        }
        byte[] bytes = Files.readAllBytes(work.resolve(path));
        source.members().put("bytes", new Json.JNumber(Integer.toString(bytes.length)));
        source.members().put("sha256", new Json.JString(NativeArtifact.sha256(bytes)));
        source.members().put("records", new Json.JNumber(Integer.toString(records)));
        return source;
    }

    @Test void freezesSeparateBytesBindingsAndExpectationWithoutInventingExit() throws IOException {
        Path input = manifest();
        byte[] original = Files.readAllBytes(input);
        var qualified = ClaudeAcquisition.qualify(input, work.resolve("qualified"));
        assertThat(Files.readAllBytes(input)).isEqualTo(original);
        assertThat(Files.readAllBytes(qualified.manifest().getParent().resolve("client-acquisition.json"))).isEqualTo(original);
        Json m = NeutralJson.readDocument(qualified.manifest());
        assertThat(m.path("process", "exit_code").isNull()).isTrue();
        assertThat(m.member("sources").elements()).allSatisfy(s -> assertThat(s.member("binding")).isNotNull());
        assertThat(NeutralJson.readDocument(qualified.assemblyExpectation()).member("profile_id").text()).isEqualTo(ClaudeAcquisition.PROFILE_ID);
        assertThat(Files.readAllBytes(work.resolve("qualified/stdout.bin"))).isEqualTo(Files.readAllBytes(work.resolve("stdout.bin")));
        assertThatThrownBy(() -> ClaudeAcquisition.qualify(input, work.resolve("qualified"))).isInstanceOf(IOException.class);
    }

    @Test void theRegisteredQualifierWritesTheSameBytesAndRefusesTheSameInputsAsTheStaticEntryPoint() throws IOException {
        Path input = manifest();
        AcquisitionQualifier qualifier = AcquisitionQualifier.forProfile(NeutralJson.readDocument(input).member("profile").text()).orElseThrow();
        var viaQualifier = qualifier.qualify(input, work.resolve("via-qualifier"));
        var viaStatic = ClaudeAcquisition.qualify(input, work.resolve("via-static"));
        assertThat(AcquisitionQualifierTest.tree(work.resolve("via-qualifier"))).isNotEmpty().isEqualTo(AcquisitionQualifierTest.tree(work.resolve("via-static")));
        assertThat(work.resolve("via-qualifier").relativize(viaQualifier.manifest())).isEqualTo(work.resolve("via-static").relativize(viaStatic.manifest()));
        assertThat(work.resolve("via-qualifier").relativize(viaQualifier.assemblyExpectation())).isEqualTo(work.resolve("via-static").relativize(viaStatic.assemblyExpectation()));
        String sealed = Files.readString(input), changed = sealed.replace("\"version\":\"2.1.316\"", "\"version\":\"2.1.317\"");
        assertThat(changed).isNotEqualTo(sealed);
        Files.writeString(input, changed);
        String verdict = catchThrowable(() -> ClaudeAcquisition.qualify(input, work.resolve("rejected-static"))).getMessage();
        assertThatThrownBy(() -> qualifier.qualify(input, work.resolve("rejected"))).isInstanceOf(IllegalArgumentException.class).hasMessage(verdict);
        assertThat(work.resolve("rejected")).doesNotExist();
    }

    @Test void rejectsWrongManifestVersionRouteSessionAndHashBeforeCreatingOutput() throws IOException {
        Path input = manifest();
        String original = Files.readString(input);
        for (String changed : java.util.List.of(original.replace("2.1.316", "2.1.317"), original.replace("sdk_stdio", "sdk"),
                original.replace("session\"", "wrong\""), original.replace("\"bytes\":", "\"bytes\":9"))) {
            Files.writeString(input, changed);
            assertThatThrownBy(() -> ClaudeAcquisition.qualify(input, work.resolve("rejected"))).isInstanceOf(IllegalArgumentException.class);
            assertThat(work.resolve("rejected")).doesNotExist();
        }
        Files.writeString(input, original);
        Files.writeString(work.resolve("stdout.bin"), "tampered");
        assertThatThrownBy(() -> ClaudeAcquisition.qualify(input, work.resolve("rejected"))).hasMessageContaining("hash mismatch");
    }

    @Test void sourceAssertionsCannotOverrideDifferentNativeVersionOrSession() throws IOException {
        for (String change : java.util.List.of("2.1.317", "other-session")) {
            Path input = manifest();
            String nativeText = Files.readString(work.resolve("native.jsonl"));
            Files.writeString(work.resolve("native.jsonl"), nativeText.replace(change.startsWith("2.") ? "2.1.316" : "session\"", change.startsWith("2.") ? change : change + "\""));
            Json.JObject m = (Json.JObject) NeutralJson.readDocument(input);
            ((Json.JArray) m.member("sources")).elements().set(1, source("transcript", "native.jsonl", "claude_transcript", "native_session_file", 1));
            Files.writeString(input, NeutralJson.write(m));
            assertThatThrownBy(() -> ClaudeAcquisition.qualify(input, work.resolve("rejected"))).hasMessageContaining("native");
            assertThat(work.resolve("rejected")).doesNotExist();
        }
    }

    @Test void absentNativeFileAndUnknownProcessRemainExplicit() throws IOException {
        Path input = manifest();
        Json.JObject m = (Json.JObject) NeutralJson.readDocument(input);
        ((Json.JArray) m.member("sources")).elements().set(1, NeutralJson.parse("""
                {"artifact":"native.jsonl","role":"transcript","state":"missing","required":false,"reason":"not produced"}
                """));
        Files.writeString(input, NeutralJson.write(m));
        var qualified = ClaudeAcquisition.qualify(input, work.resolve("qualified"));
        Json saved = NeutralJson.readDocument(qualified.manifest());
        assertThat(saved.member("sources").elements().get(1).member("state").text()).isEqualTo("missing");
        assertThat(saved.path("process", "exit_code").isNull()).isTrue();
    }

    @Test void integrityAndSelectedRoleCannotBeBypassedByMetadata() throws IOException {
        Path input = manifest();
        String original = Files.readString(input);
        for (String changed : java.util.List.of(original.replace("\"framing\":\"lines\"", "\"framing\":\"document\""),
                original.replace("\"state\":\"acquired\"", "\"archive_only\":true,\"state\":\"acquired\""),
                original.replace("\"acquisition\":\"run\"", "\"acquisition\":\"other\""),
                original.replace("\"path\":\"stdout.bin\"", "\"path\":\"../stdout.bin\""),
                original.replace("\"records\":2", "\"records\":9"),
                original.replace("--input-format", "--unknown"))) {
            Files.writeString(input, changed);
            assertThatThrownBy(() -> ClaudeAcquisition.qualify(input, work.resolve("rejected"))).isInstanceOf(IllegalArgumentException.class);
            assertThat(work.resolve("rejected")).doesNotExist();
        }
    }
}
