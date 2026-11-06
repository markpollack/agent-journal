package io.github.markpollack.journal.tck.ocsf.inventory;

import io.github.markpollack.journal.tck.ocsf.NativeArtifact;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The fixed registry of one-shot qualifiers: exact profiles in, exact wire identifiers out, nothing discovered. */
class AcquisitionQualifierTest {

    @Test void eachRegisteredOneShotProfileSelectsItsOwnQualifierAndWireProfile() {
        AcquisitionQualifier claude = AcquisitionQualifier.forProfile("claude-code-2.1.316-sdk-stdio-session-file").orElseThrow();
        AcquisitionQualifier codex = AcquisitionQualifier.forProfile("codex-cli-0.166.0-exec-stdio-rollout").orElseThrow();
        assertThat(claude).isSameAs(ClaudeAcquisition.QUALIFIER);
        assertThat(codex).isSameAs(CodexAcquisition.QUALIFIER);
        assertThat(List.of(claude.acquisitionProfile(), claude.terminalProfile()))
                .containsExactly("claude-code-2.1.316-sdk-stdio-session-file", "client-terminal-claude-2.1.316/1");
        assertThat(List.of(codex.acquisitionProfile(), codex.terminalProfile()))
                .containsExactly("codex-cli-0.166.0-exec-stdio-rollout", "client-terminal-codex-0.166.0/1");
        assertThat(claude).hasToString("claude-code-2.1.316-sdk-stdio-session-file -> client-terminal-claude-2.1.316/1");
    }

    @Test void anyOtherProfileHasNoQualifierAndNullIsRefused() {
        for (String profile : List.of("", "claude-code-2.1.317-sdk-stdio-session-file", "codex-cli-0.166.1-exec-stdio-rollout",
                "CODEX-CLI-0.166.0-EXEC-STDIO-ROLLOUT", "codex-cli/0.166.0/exec-stdio+rollout", "client-terminal-codex-0.166.0/1",
                "client-terminal-claude-retained-fresh-2.1.316/3", "client-terminal-codex-retained-0.166.0/2", "antigravity")) {
            assertThat(AcquisitionQualifier.forProfile(profile)).as(profile).isEmpty();
        }
        assertThatThrownBy(() -> AcquisitionQualifier.forProfile(null)).isInstanceOf(NullPointerException.class);
    }

    /** Every file a qualification wrote, by relative path, with its sha256. */
    static Map<String, String> tree(Path directory) throws IOException {
        Map<String, String> files = new TreeMap<>();
        try (Stream<Path> walk = Files.walk(directory)) {
            for (Path file : walk.filter(Files::isRegularFile).toList()) {
                files.put(directory.relativize(file).toString(), NativeArtifact.sha256(Files.readAllBytes(file)));
            }
        }
        return files;
    }
}
