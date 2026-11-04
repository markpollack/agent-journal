package io.github.markpollack.journal.ocsf.writer;

import io.github.markpollack.journal.tck.ocsf.OcsfReaderTool;
import io.github.markpollack.journal.tck.ocsf.PackageReader;
import io.github.markpollack.journal.tck.ocsf.TerminalBindingReader;
import io.github.markpollack.journal.tck.ocsf.inventory.AcquisitionVerification;
import io.github.markpollack.journal.tck.ocsf.inventory.FreshRetainedClaudeAcquisition;
import io.github.markpollack.journal.tck.ocsf.inventory.RetainedClaudeAcquisition;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static io.github.markpollack.journal.ocsf.writer.TerminalReceiverTest.*;
import static io.github.markpollack.journal.ocsf.writer.TerminalRetainedTest.*;
import static org.assertj.core.api.Assertions.*;

/** Synthetic fresh-transcript-start integration through the receiver; no native/provider qualification claim. */
class TerminalFreshRetainedTest {
    @TempDir Path temp;
    private static final Path FRESH = Path.of("../journal-capture-tck/src/test/resources/retained-fresh-generation/retained-fresh-generation.json");
    private static final Path FROZEN = Path.of("../journal-capture-tck/src/test/resources/retained-text-generation/retained-generation.json");

    static byte[] freshEnvelope(int turn) {
        Jv.Obj p = (Jv.Obj) Jv.parse(new String(envelope(successful(turn), pending(turn)), StandardCharsets.UTF_8));
        return bytes(p.put("profile", TerminalReceiver.FRESH_RETAINED_PROFILE));
    }
    static Jv.Obj completeFresh(int turn, Path root) throws Exception {
        Jv manifest = Jv.parse(Files.readString(root.resolve("acquisition.json"))); String session = manifest.member("run").member("session_uid").text();
        Jv.Obj a = complete(turn, root);
        for (Jv source : a.member("sources").elements()) ((Jv.Obj) source).put("nativeSessionId", session);
        return a;
    }

    @Test void freshProfileAttachesBothTurnsAndIndependentReadersAcceptExactOriginals() throws Exception {
        Path store = temp.resolve("store"); TerminalReceiver receiver = new TerminalReceiver(store, "producer", WriterOptions.defaults());
        String[] initial = { receiver.receive(freshEnvelope(1), temp), receiver.receive(freshEnvelope(2), temp) };
        for (int turn : List.of(2, 1)) {
            var qualified = FreshRetainedClaudeAcquisition.qualify(FRESH, "E" + turn, temp.resolve("q" + turn));
            Path root = qualified.manifest().getParent(); Jv.Obj a = completeFresh(turn, root);
            String id = Jv.parse(initial[turn - 1]).member("bindingId").text();
            String ack = receiver.receiveRevision(id, bytes(a), root);
            assertThat(receiver.receiveRevision(id, bytes(a), root)).isEqualTo(ack);
            var pkg = PackageReader.open(events(store, ack));
            assertThat(pkg.diagnostics).noneMatch(d -> d.severity().name().equals("DEFECT") || d.severity().name().equals("REFUSED"));
            var binding = TerminalBindingReader.read(pkg.declarations);
            assertThat(binding.decision()).isEqualTo(TerminalJson.write(successful(turn)));
            var report = new AcquisitionVerification().verify(pkg, root, qualified.manifest(), qualified.assemblyExpectation());
            assertThat(report.defects()).isEmpty();
            assertThat(OcsfReaderTool.acceptance(report).exit()).as(OcsfReaderTool.acceptance(report).toString()).isZero();
            assertThat(Files.readAllBytes(root.resolve("generation/observations.jsonl"))).isEqualTo(Files.readAllBytes(FRESH.resolveSibling("observations.jsonl")));
        }
    }

    @Test void frozenAndFreshProfilesCannotBeAttachedToEachOthersGenerations() throws Exception {
        Path store = temp.resolve("store"); TerminalReceiver receiver = new TerminalReceiver(store, "producer", WriterOptions.defaults());
        var fresh = FreshRetainedClaudeAcquisition.qualify(FRESH, "E1", temp.resolve("fresh")); Path freshRoot = fresh.manifest().getParent();
        var frozen = RetainedClaudeAcquisition.qualify(FROZEN, "E1", temp.resolve("frozen")); Path frozenRoot = frozen.manifest().getParent();
        String frozenAck = receiver.receive(retainedEnvelope(1), temp), frozenId = Jv.parse(frozenAck).member("bindingId").text();
        assertThatThrownBy(() -> receiver.receiveRevision(frozenId, bytes(completeFresh(1, freshRoot)), freshRoot)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> receiver.receive(freshEnvelope(1), temp)).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("conflict");
        TerminalReceiver other = new TerminalReceiver(temp.resolve("store2"), "producer", WriterOptions.defaults());
        String freshAck = other.receive(freshEnvelope(1), temp), freshId = Jv.parse(freshAck).member("bindingId").text();
        assertThatThrownBy(() -> other.receiveRevision(freshId, bytes(complete(1, frozenRoot)), frozenRoot)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void writerRefusesRelabelledZeroPrefixMissingIdentityAndChangedJournalWithFreshHashes() throws Exception {
        for (String fault : List.of("zero-prefix", "identity", "journal", "profile")) {
            var q = FreshRetainedClaudeAcquisition.qualify(FRESH, "E1", temp.resolve("q-" + fault)); Path root = q.manifest().getParent();
            Jv.Obj m = (Jv.Obj) Jv.parse(Files.readString(q.manifest())); Jv.Obj generation = (Jv.Obj) m.member("retained_generation");
            switch (fault) {
                case "zero-prefix" -> ((Jv.Obj) generation.member("turns").elements().get(0)).put("nativePrefixBefore",
                        Jv.parse("{\"bytes\":0,\"sha256\":\"e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855\"}"));
                case "identity" -> generation.put("observedNativeIdentity", Jv.Null.INSTANCE);
                case "journal" -> Files.writeString(root.resolve("generation/observations.jsonl"), Files.readString(root.resolve("generation/observations.jsonl")).replace("ENOENT", "EXISTS"));
                case "profile" -> m.put("retained_profile", TerminalReceiver.RETAINED_PROFILE);
                default -> throw new AssertionError(fault);
            }
            Files.writeString(root.resolve("generation/retained-fresh-generation.json"), Jv.write(generation));
            for (Jv source : m.member("sources").elements()) {
                byte[] actual = Files.readAllBytes(root.resolve(source.member("path").text()));
                ((Jv.Obj) source).put("sha256", SchemaPin.sha256(actual)).put("bytes", actual.length);
            }
            Files.writeString(q.manifest(), Jv.write(m));
            TerminalReceiver receiver = new TerminalReceiver(temp.resolve("store-" + fault), "producer", WriterOptions.defaults());
            String ack = receiver.receive(freshEnvelope(1), temp); Jv.Obj a = completeFresh(1, root);
            assertThatThrownBy(() -> receiver.receiveRevision(Jv.parse(ack).member("bindingId").text(), bytes(a), root)).as(fault).isInstanceOf(IllegalArgumentException.class);
        }
    }
}
