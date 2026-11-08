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
 * the requalified sources' extents and its profile is the selected qualifier's terminal profile (as Client states both
 * when it records), and the envelope's decision is delivered unchanged. No CLI runs.
 */
final class RealRecording {
    /** The scrubbed real runs this module carries: derived from the private corpus, structure and numbers unchanged. */
    static final Path PUBLIC = Path.of("src/test/resources/claude-real");

    /** The sealed archive's store, root event file, qualified acquisition and the independent reader's acceptance. */
    record Sealed(Path store, Path events, Path qualified, io.github.markpollack.journal.tck.ocsf.OracleReport report, OcsfReaderTool.Acceptance acceptance) {
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
        return seal(run, work, null);
    }

    /** {@link #seal(Path, Path)}, with the envelope delivered under {@code terminalProfile} instead of the qualifier's own (null: the qualifier's). */
    static Sealed seal(Path run, Path work, String terminalProfile) throws IOException {
        Path files = run.resolve("files");
        Jv stored = Jv.parse(Files.readString(only(files.resolve("terminal"), "")));
        Jv.Obj envelope = (Jv.Obj) Jv.parse(stored.member("payload").member("terminalEnvelope").text());
        Path sealed = nativeDir(run).resolve("client-acquisition.json");
        String profile = Jv.parse(Files.readString(sealed)).member("profile").text();
        Files.createDirectories(work);
        Path qualified = work.resolve("qualified");
        AcquisitionQualifier qualifier = AcquisitionQualifier.forProfile(profile).orElseThrow();
        qualifier.qualify(sealed, qualified);
        // as Client records: the envelope names the selected qualifier's terminal profile
        envelope.put("profile", terminalProfile == null ? qualifier.terminalProfile() : terminalProfile);
        Jv association = Jv.parse(new String(Base64.getDecoder().decode(envelope.member("associationBytes").text()), StandardCharsets.UTF_8));
        if (!"COMPLETED".equals(association.member("acquisition").text())) {
            // recorded before a qualifier existed for its version: state the association Client states for a qualified one
            association = qualifiedAssociation(association, qualified);
        }
        Jv.Arr kept = new Jv.Arr();
        for (Jv source : association.member("sources").elements()) {
            Path file = qualified.resolve(source.member("relativePath").text());
            if (!Files.exists(file)) { continue; } // a source the (edited) acquisition does not hold is not associated
            byte[] bytes = Files.readAllBytes(file);
            ((Jv.Obj) source).put("sha256", SchemaPin.sha256(bytes)).put("size", bytes.length).put("end", bytes.length);
            kept.add(source);
        }
        ((Jv.Obj) association).put("sources", kept);
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
        return new Sealed(store, events, qualified, report, OcsfReaderTool.acceptance(report));
    }

    /**
     * The association Client's {@code NativeAssociation.qualified} states for a qualified acquisition: one occurrence per
     * declared source and one for the qualified manifest, each whole, under the recorded decision's identities. Keys are
     * in canonical (sorted) order.
     */
    private static Jv qualifiedAssociation(Jv recorded, Path qualified) throws IOException {
        Jv manifest = Jv.parse(Files.readString(qualified.resolve("acquisition.json")));
        String session = manifest.member("run").member("session_uid").text();
        String connection = recorded.member("connectionIds").elements().get(0).text();
        Jv.Arr sources = new Jv.Arr();
        for (Jv source : manifest.member("sources").elements()) {
            String stream = switch (source.member("role").text()) { case "stdin" -> "STDIN"; case "stream" -> "STDOUT"; case "stderr" -> "STDERR"; default -> null; };
            sources.add(occurrence(source.member("path").text(), source.member("origin").text(), stream, session, connection));
        }
        sources.add(occurrence("acquisition.json", "qualified-manifest", null, session, connection));
        return new Jv.Obj().put("acquisition", "COMPLETED").put("clientSessionId", recorded.member("clientSessionId")).put("codec", "journal-association/1")
                .put("connectionIds", recorded.member("connectionIds")).put("continuation", Jv.Null.INSTANCE).put("decisionId", recorded.member("decisionId"))
                .put("executionId", recorded.member("executionId")).put("missing", new Jv.Arr()).put("priorRevisionId", recorded.member("priorRevisionId"))
                .put("providerObservation", recorded.member("providerObservation")).put("revisionId", recorded.member("revisionId")).put("sources", sources);
    }

    /** One whole-source occurrence; its sha256, size and end are restated from the qualified bytes by {@link #seal}. */
    private static Jv.Obj occurrence(String path, String origin, String stream, String session, String connection) {
        return new Jv.Obj().put("clientConnectionId", connection).put("end", 0).put("nativeConnectionId", Jv.Null.INSTANCE).put("nativeSessionId", session)
                .put("nativeTurnId", Jv.Null.INSTANCE).put("origin", origin).put("relativePath", path).put("sha256", "").put("size", 0)
                .put("sourceId", path).put("start", 0).put("stream", stream == null ? Jv.Null.INSTANCE : new Jv.Str(stream));
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
