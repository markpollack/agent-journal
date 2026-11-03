package io.github.markpollack.journal.tck.ocsf.inventory;

import io.github.markpollack.journal.tck.ocsf.NativeArtifact;
import io.github.markpollack.journal.tck.verify.Json;
import io.github.markpollack.journal.tck.verify.NeutralJson;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Labelled synthetic acquisition: no native/model execution. */
class CodexAcquisitionTest {
    @TempDir Path work;
    private Path fixture() throws Exception {
        Path root = Path.of(getClass().getResource("/ocsf/syn-codex-acquisition-0.166.0").toURI());
        for (String name : List.of("codex-acquisition.json", "stdin.bin", "stdout.jsonl", "stderr.bin", "native.jsonl")) Files.copy(root.resolve(name), work.resolve(name), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        return work.resolve("codex-acquisition.json");
    }
    @Test void qualifiesExactOriginalsWithoutInventingUsageOrChangingInput() throws Exception {
        Path input = fixture(); byte[] before = Files.readAllBytes(input);
        var q = CodexAcquisition.qualify(input, work.resolve("qualified"));
        assertThat(Files.readAllBytes(input)).isEqualTo(before);
        assertThat(Files.readAllBytes(q.manifest().getParent().resolve("client-acquisition.json"))).isEqualTo(before);
        CodexAcquisition.validate(q.manifest());
        Json m = NeutralJson.readDocument(q.manifest());
        assertThat(m.member("profile_id").text()).isEqualTo(CodexAcquisition.PROFILE_ID);
        for (Json s : m.member("sources").elements()) {
            assertThat(Files.readAllBytes(q.manifest().getParent().resolve(s.member("path").text()))).isEqualTo(Files.readAllBytes(work.resolve(s.member("path").text())));
        }
        assertThatThrownBy(() -> CodexAcquisition.qualify(input, work.resolve("qualified"))).isInstanceOf(IOException.class);
    }
    @Test void refusesMissingClosureWrongRouteIdentityAndExtentsBeforeOutput() throws Exception {
        Path input = fixture(); String original = Files.readString(input);
        for (String[] change : List.of(new String[]{"\"sealed\":true", "\"sealed\":false"},
                new String[]{"\"stdout_natural_eof\":true", "\"stdout_natural_eof\":false"},
                new String[]{"\"native_path_preexisting\":false", "\"native_path_preexisting\":true"},
                new String[]{"\"overflow\":false", "\"overflow\":true"}, new String[]{"\"exit_code\":0", "\"exit_code\":1"},
                new String[]{"\"dropped\":0", "\"dropped\":1"}, new String[]{"exec_stdio", "sdk"},
                new String[]{"0.166.0", "0.166.1"}, new String[]{"--no-daemon", "--ephemeral"},
                new String[]{"\"STDIN\":0", "\"STDIN\":1"})) {
            String changed = original.replace(change[0], change[1]); assertThat(changed).isNotEqualTo(original); Files.writeString(input, changed);
            assertThatThrownBy(() -> CodexAcquisition.qualify(input, work.resolve("rejected"))).isInstanceOf(IllegalArgumentException.class);
            assertThat(work.resolve("rejected")).doesNotExist();
        }
    }
    @Test void rehashedNativeContradictionsCannotBorrowCallerIdentity() throws Exception {
        for (String[] change : List.of(new String[]{"11111111-1111-4111-8111-111111111111", "33333333-3333-4333-8333-333333333333"},
                new String[]{"22222222-2222-4222-8222-222222222222", "44444444-4444-4444-8444-444444444444"},
                new String[]{"0.166.0", "0.166.1"}, new String[]{"Reply exactly:", "Forged prompt:"},
                new String[]{"\"output_tokens\":3", "\"output_tokens\":4"}, new String[]{"task_complete", "turn_aborted"})) {
            Path input = fixture(); Path nativeFile = work.resolve("native.jsonl");
            Files.writeString(nativeFile, Files.readString(nativeFile).replace(change[0], change[1])); rehash(input, nativeFile);
            assertThatThrownBy(() -> CodexAcquisition.qualify(input, work.resolve("rejected"))).isInstanceOf(IllegalArgumentException.class);
            assertThat(work.resolve("rejected")).doesNotExist();
        }
    }
    @Test void historicalAndCurrentExpectationsRequireExactProfileSchemaPairings() throws Exception {
        Json historical = CodexAcquisition.expectation("0.1.0-prototype", "0.15.0-prototype");
        assertThat(historical.member("entities").elements()).isEmpty();
        assertThat(CodexAcquisition.expectation("0.2.0-prototype", "0.16.0-prototype").member("entities").elements()).hasSize(1);
        Path exact = Path.of(getClass().getResource("/ocsf/qualification/codex-cli-0.166.0-profile-0.1.json").toURI());
        assertThat(NativeArtifact.sha256(Files.readAllBytes(exact))).isEqualTo("c96631cd56fc5bc31b66af9d0d1a4b5a6bc7036a860f8cc8004d0fe73f5311dc");
        for (String[] pair : List.of(new String[]{"0.1.0-prototype", "0.16.0-prototype"},
                new String[]{"0.2.0-prototype", "0.15.0-prototype"}, new String[]{"unknown", "0.16.0-prototype"})) {
            assertThatThrownBy(() -> CodexAcquisition.expectation(pair[0], pair[1])).isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("profile/schema combination");
        }
    }
    private static void rehash(Path input, Path file) throws IOException {
        Json m = NeutralJson.readDocument(input); byte[] bytes = Files.readAllBytes(file);
        Json.JObject source = (Json.JObject) m.member("sources").elements().stream().filter(s -> s.member("role").text().equals("transcript")).findFirst().orElseThrow();
        source.members().put("sha256", new Json.JString(NativeArtifact.sha256(bytes)));
        for (String key : List.of("bytes", "observed")) source.members().put(key, new Json.JNumber(Integer.toString(bytes.length)));
        Files.writeString(input, NeutralJson.write(m));
    }
}
