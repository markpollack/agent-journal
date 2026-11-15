package io.github.markpollack.journal.tck.ocsf;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 2.0.0 reads only the 1.0.0 formats. A recording sealed before 2.0.0 is refused as an unsupported format, and each
 * refusal names the identifier the recording carries; every current case names only the current identifiers.
 */
class RefusedFormatTest {
    private static final Path BEFORE_2_0_0 = Path.of("src/test/resources/ocsf/refused/claude-txbench-2.1.319-before-2.0.0");

    @Test
    void aRecordingSealedBefore2_0_0IsRefusedAsAnUnsupportedFormat() throws IOException {
        OcsfPackage pkg = PackageReader.open(TerminalRealRecordingTest.root(BEFORE_2_0_0.getParent(), BEFORE_2_0_0.getFileName().toString()));
        assertThat(pkg.refused()).isTrue();
        assertThat(pkg.manifest.packageKind()).isNotEqualTo(Formats.PACKAGE_KIND);
        assertThat(pkg.diagnosticsWithCode("UNSUPPORTED_PACKAGE_KIND")).singleElement()
                .satisfies(d -> assertThat(d.detail()).startsWith("unsupported format: package kind " + pkg.manifest.packageKind() + ";"));
        assertThat(pkg.diagnosticsWithCode("UNSUPPORTED_PACKAGE_VERSION")).singleElement()
                .satisfies(d -> assertThat(d.detail()).startsWith("unsupported format: package layout " + pkg.manifest.packageVersion() + ";"));
        assertThat(pkg.diagnosticsWithCode("UNSUPPORTED_EXTENSION_VERSION")).singleElement()
                .satisfies(d -> assertThat(d.detail()).startsWith("unsupported format: extension version " + pkg.manifest.extensionVersion() + ";"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"claude-heartbeat", "claude-mcp", "claude-mcp-payload-keys", "claude-sub-agent", "claude-txbench-2.1.319", "codex-one-read"})
    void aRecordingSealedNowNamesOnlyTheCurrentIdentifiersAndIsAccepted(String name) throws IOException {
        Path events = TerminalRealRecordingTest.root(name);
        OcsfPackage pkg = PackageReader.open(events);
        assertThat(pkg.manifest.packageKind()).isEqualTo(Formats.PACKAGE_KIND);
        assertThat(pkg.manifest.packageVersion()).isEqualTo(Formats.TERMINAL_LAYOUT);
        assertThat(pkg.manifest.extensionVersion()).isEqualTo(Formats.EXTENSION);
        String before = PackageReader.open(TerminalRealRecordingTest.root(BEFORE_2_0_0.getParent(), BEFORE_2_0_0.getFileName().toString())).manifest.packageKind();
        String suffix = before.substring(before.indexOf('/') + 1).replace("ocsf-", "").replace("-package", "");
        try (Stream<Path> files = Files.walk(events.getParent())) {
            for (Path f : files.filter(Files::isRegularFile).filter(f -> !f.toString().contains("/sources/")).toList()) {
                assertThat(Files.readString(f)).as(f.toString()).doesNotContain(suffix).doesNotContain("non_production");
            }
        }
        OcsfReaderTool.Acceptance a = TerminalRealRecordingTest.acceptance(name);
        assertThat(a.verdict()).as(a.toString()).isEqualTo("ACCEPTED");
    }
}
