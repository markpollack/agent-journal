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

/**
 * One real recorded run, laid out as the corpus files it ({@code files/native-<executionId>/} with the sealed Client
 * manifest, {@code files/terminal/<key>.json} with the delivered envelope), sealed again offline through this writer. Its
 * sealed Client manifest is qualified again by the current qualifier, the stored envelope's association is restated with
 * the requalified sources' extents (as Client restates them when it records), and the envelope's decision is delivered
 * unchanged. No CLI runs.
 */
final class RealRecording {
    /** The scrubbed real runs this module carries: derived from the private corpus, structure and numbers unchanged. */
    static final Path PUBLIC = Path.of("src/test/resources/claude-real");

    /** The sealed archive's store, root event file, qualified acquisition and the independent reader's acceptance. */
    record Sealed(Path store, Path events, Path qualified, OcsfReaderTool.Acceptance acceptance) {
        Path child(String spawn) {
            return events.getParent().resolve("children").resolve(spawn).resolve("events.jsonl");
        }
    }

    private RealRecording() { }

    /** The run's native directory: its stream, session files and sealed Client manifest. */
    static Path nativeDir(Path run) {
        return only(run.resolve("files"), "native-");
    }

    static Sealed seal(Path run, Path work) throws IOException {
        Path files = run.resolve("files");
        Jv stored = Jv.parse(Files.readString(only(files.resolve("terminal"), "")));
        Jv.Obj envelope = (Jv.Obj) Jv.parse(stored.member("payload").member("terminalEnvelope").text());
        Path sealed = nativeDir(run).resolve("client-acquisition.json");
        String profile = Jv.parse(Files.readString(sealed)).member("profile").text();
        Files.createDirectories(work);
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
        return new Sealed(store, events, qualified, OcsfReaderTool.acceptance(report));
    }

    /** The parsed JSON lines of {@code file}. */
    static List<Jv> lines(Path file) throws IOException {
        return Files.readAllLines(file).stream().filter(l -> !l.isBlank()).map(Jv::parse).toList();
    }

    private static Path only(Path dir, String prefix) {
        try (Stream<Path> entries = Files.list(dir)) {
            List<Path> found = entries.filter(p -> p.getFileName().toString().startsWith(prefix)).toList();
            if (found.size() != 1) { throw new IllegalStateException("expected one " + prefix + "* in " + dir + ", found " + found); }
            return found.get(0);
        }
        catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
