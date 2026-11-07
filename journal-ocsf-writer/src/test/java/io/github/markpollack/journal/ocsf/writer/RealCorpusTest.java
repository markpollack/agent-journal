package io.github.markpollack.journal.ocsf.writer;

import io.github.markpollack.journal.tck.ocsf.OcsfReaderTool;
import io.github.markpollack.journal.tck.ocsf.PackageReader;
import io.github.markpollack.journal.tck.ocsf.inventory.AcquisitionQualifier;
import io.github.markpollack.journal.tck.ocsf.inventory.AcquisitionVerification;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every real recording of the private corpus ({@code -Djournal.corpus=<agent-journal-corpus checkout>}), sealed again
 * offline through this writer and read by the independent reader, must be accepted. Each run's sealed Client manifest is
 * qualified again by the current qualifier, the stored terminal envelope's association is restated with the requalified
 * sources' extents (as Client restates them when it records), and the envelope's decision is delivered unchanged. No CLI
 * runs. Without the property the corpus is absent and the test is skipped, so a build never depends on private files.
 */
class RealCorpusTest {
    @TempDir Path temp;

    @TestFactory Stream<DynamicTest> everyRealRecordingIsAcceptedWhenSealedAgain() throws IOException {
        String corpus = System.getProperty("journal.corpus");
        Assumptions.assumeTrue(corpus != null, "set journal.corpus to the agent-journal-corpus checkout");
        List<Path> runs;
        try (Stream<Path> files = Files.walk(Path.of(corpus))) {
            runs = files.filter(p -> p.getFileName().toString().equals("README.md") && Files.isDirectory(p.resolveSibling("files")))
                    .map(Path::getParent).sorted().toList();
        }
        assertThat(runs).as("recordings in " + corpus).isNotEmpty();
        return runs.stream().map(run -> DynamicTest.dynamicTest(Path.of(corpus).relativize(run).toString(), () -> accepted(run)));
    }

    private void accepted(Path run) throws IOException {
        Path files = run.resolve("files");
        Path nativeDir = only(files, "native-");
        Path marker = Files.isDirectory(files.resolve("terminal")) ? only(files.resolve("terminal"), "") : null;
        Jv stored = Jv.parse(Files.readString(marker));
        Jv.Obj envelope = (Jv.Obj) Jv.parse(stored.member("payload").member("terminalEnvelope").text());
        Path sealed = nativeDir.resolve("client-acquisition.json");
        String profile = Jv.parse(Files.readString(sealed)).member("profile").text();
        Path work = Files.createDirectories(temp.resolve(run.getFileName().toString()));
        Path qualified = work.resolve("qualified");
        AcquisitionQualifier.forProfile(profile).orElseThrow().qualify(sealed, qualified);
        Jv association = Jv.parse(new String(Base64.getDecoder().decode(envelope.member("associationBytes").text()), StandardCharsets.UTF_8));
        for (Jv source : association.member("sources").elements()) {
            byte[] bytes = Files.readAllBytes(qualified.resolve(source.member("relativePath").text()));
            ((Jv.Obj) source).put("sha256", SchemaPin.sha256(bytes)).put("size", bytes.length).put("end", bytes.length);
        }
        envelope.put("associationBytes", Base64.getEncoder().encodeToString(Jv.write(association).getBytes(StandardCharsets.UTF_8)));
        Path store = work.resolve("store");
        new TerminalReceiver(store, stored.member("ack").member("producerNamespace").text(), WriterOptions.defaults())
                .receive(Jv.write(envelope).getBytes(StandardCharsets.UTF_8), qualified);
        Path events;
        try (Stream<Path> found = Files.walk(store.resolve("prepared"))) {
            events = found.filter(p -> p.getFileName().toString().equals("events.jsonl") && !p.toString().contains("/children/")).findFirst().orElseThrow();
        }
        var report = new AcquisitionVerification().verify(PackageReader.open(events), events.getParent().resolve("sources"),
                events.getParent().resolve("sources/acquisition.json"), qualified.resolve("assembly-expectation.json"));
        OcsfReaderTool.Acceptance acceptance = OcsfReaderTool.acceptance(report);
        assertThat(acceptance.verdict()).as(acceptance.toString()).isEqualTo("ACCEPTED");
    }

    private static Path only(Path dir, String prefix) {
        try (Stream<Path> entries = Files.list(dir)) {
            List<Path> found = entries.filter(p -> p.getFileName().toString().startsWith(prefix)).toList();
            assertThat(found).as(prefix + "* in " + dir).hasSize(1);
            return found.get(0);
        }
        catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
