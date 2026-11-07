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
import org.junit.jupiter.api.io.TempDir;
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

    /** The real heartbeat run's qualified sources, copied: a sealed acquisition the Claude qualifier accepts. */
    private static Path realSealed(Path temp) throws IOException {
        Path from;
        try (Stream<Path> walk = Files.walk(Path.of("src/test/resources/ocsf/terminal-real/claude-heartbeat/store/prepared"))) {
            from = walk.filter(p -> p.endsWith("sources/acquisition.json")).findFirst().orElseThrow().getParent();
        }
        Path to = temp.resolve("sealed");
        try (Stream<Path> walk = Files.walk(from)) {
            for (Path p : walk.toList()) {
                Path t = to.resolve(from.relativize(p).toString());
                if (Files.isDirectory(p)) Files.createDirectories(t); else Files.copy(p, t);
            }
        }
        return to.resolve("acquisition.json");
    }

    private static final AcquisitionQualifier CLAUDE = AcquisitionQualifier.forProfile(ClaudeAcquisition.PROFILE).orElseThrow();

    @Test void theRealSealedAcquisitionQualifies(@TempDir Path temp) throws IOException {
        assertThat(CLAUDE.qualify(realSealed(temp), temp.resolve("out")).manifest()).isRegularFile();
    }

    /** Stage A review F1: a fractional extent is a refusal, as the SPI says, not an ArithmeticException. */
    @Test void aFractionalExtentIsARefusal(@TempDir Path temp) throws IOException {
        Path sealed = realSealed(temp);
        String manifest = Files.readString(sealed);
        java.util.regex.Matcher bytes = java.util.regex.Pattern.compile("\"bytes\":(\\d+)").matcher(manifest);
        assertThat(bytes.find()).isTrue();
        Files.writeString(sealed, manifest.substring(0, bytes.end()) + ".5" + manifest.substring(bytes.end()));
        assertThatThrownBy(() -> CLAUDE.qualify(sealed, temp.resolve("out"))).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("not an exact integer");
    }

    /** Stage A review F5: a declared source whose file is absent is a refusal, not an I/O failure. */
    @Test void aDeclaredSourceThatIsAbsentIsARefusal(@TempDir Path temp) throws IOException {
        Path sealed = realSealed(temp);
        Files.delete(sealed.resolveSibling("stderr.bin"));
        assertThatThrownBy(() -> CLAUDE.qualify(sealed, temp.resolve("out"))).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("stderr.bin");
    }

    /** An unreadable sealed manifest is still an I/O failure. */
    @Test void anAbsentSealedManifestIsAnIoFailure(@TempDir Path temp) {
        assertThatThrownBy(() -> CLAUDE.qualify(temp.resolve("none/acquisition.json"), temp.resolve("out"))).isInstanceOf(IOException.class);
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
