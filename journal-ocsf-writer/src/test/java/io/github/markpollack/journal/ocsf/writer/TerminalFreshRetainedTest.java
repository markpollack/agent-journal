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
    private static final Path THREE = Path.of("../journal-capture-tck/src/test/resources/retained-fresh-generation-3turn/retained-fresh-generation.json");
    private static final Path FROZEN = Path.of("../journal-capture-tck/src/test/resources/retained-text-generation/retained-generation.json");

    static byte[] freshEnvelope(int turn) { return freshEnvelope(turn, TerminalReceiver.FRESH_RETAINED_PROFILE); }
    static byte[] freshEnvelope(int turn, String profile) {
        Jv.Obj p = (Jv.Obj) Jv.parse(new String(envelope(successful(turn), pending(turn)), StandardCharsets.UTF_8));
        return bytes(p.put("profile", profile));
    }
    static Jv.Obj completeFresh(int turn, Path root) throws Exception {
        Jv manifest = Jv.parse(Files.readString(root.resolve("acquisition.json"))); String session = manifest.member("run").member("session_uid").text();
        Jv.Obj a = complete(turn, root);
        for (Jv source : a.member("sources").elements()) ((Jv.Obj) source).put("nativeSessionId", session);
        return a;
    }

    /** Binds every turn's frozen decision under {@code profile}, attaches the qualified evidence in the given order and reads each archive independently. */
    private void eachTurnAttachesAndReadsBack(Path generation, String profile, List<Integer> order) throws Exception {
        Path store = temp.resolve("store-" + profile.replace('/', '-')); TerminalReceiver receiver = new TerminalReceiver(store, "producer", WriterOptions.defaults());
        String[] initial = new String[order.size()];
        for (int turn = 1; turn <= order.size(); turn++) initial[turn - 1] = receiver.receive(freshEnvelope(turn, profile), temp);
        for (int turn : order) {
            var qualified = FreshRetainedClaudeAcquisition.qualify(generation, "E" + turn, temp.resolve("q-" + profile.replace('/', '-') + turn), profile);
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
            assertThat(Files.readAllBytes(root.resolve("generation/observations.jsonl"))).isEqualTo(Files.readAllBytes(generation.resolveSibling("observations.jsonl")));
            assertThat(Files.readString(root.resolve("turn/stdin.jsonl"))).contains(List.of("first", "second", "third").get(turn - 1));
        }
    }

    @Test void freshProfileAttachesBothTurnsAndIndependentReadersAcceptExactOriginals() throws Exception { eachTurnAttachesAndReadsBack(FRESH, TerminalReceiver.FRESH_RETAINED_PROFILE, List.of(2, 1)); }

    @Test void ordinaryFreshProfileAttachesTwoTurns() throws Exception { eachTurnAttachesAndReadsBack(FRESH, TerminalReceiver.FRESH_RETAINED_ORDINARY_PROFILE, List.of(1, 2)); }

    @Test void ordinaryFreshProfileAttachesEveryTurnOfAThreeTurnGeneration() throws Exception { eachTurnAttachesAndReadsBack(THREE, TerminalReceiver.FRESH_RETAINED_ORDINARY_PROFILE, List.of(3, 1, 2)); }

    @Test void frozenFreshProfileRefusesThreeTurnEvidenceAndProfilesDoNotRelabelEachOther() throws Exception {
        var ordinary = FreshRetainedClaudeAcquisition.qualify(THREE, "E3", temp.resolve("o3"), TerminalReceiver.FRESH_RETAINED_ORDINARY_PROFILE); Path ordinaryRoot = ordinary.manifest().getParent();
        TerminalReceiver receiver = new TerminalReceiver(temp.resolve("store"), "producer", WriterOptions.defaults());
        String frozenAck = receiver.receive(freshEnvelope(3, TerminalReceiver.FRESH_RETAINED_PROFILE), temp), frozenId = Jv.parse(frozenAck).member("bindingId").text();
        assertThatThrownBy(() -> receiver.receiveRevision(frozenId, bytes(completeFresh(3, ordinaryRoot)), ordinaryRoot)).as("ordinary evidence under the frozen envelope").isInstanceOf(IllegalArgumentException.class);
        Jv.Obj relabelled = (Jv.Obj) Jv.parse(Files.readString(ordinary.manifest())); relabelled.put("retained_profile", TerminalReceiver.FRESH_RETAINED_PROFILE);
        Files.writeString(ordinary.manifest(), Jv.write(relabelled));
        assertThatThrownBy(() -> receiver.receiveRevision(frozenId, bytes(completeFresh(3, ordinaryRoot)), ordinaryRoot)).as("three-turn generation relabelled as frozen").isInstanceOf(IllegalArgumentException.class).hasMessageContaining("exactly two turns");
        var two = FreshRetainedClaudeAcquisition.qualify(FRESH, "E1", temp.resolve("two"), TerminalReceiver.FRESH_RETAINED_PROFILE); Path twoRoot = two.manifest().getParent();
        TerminalReceiver other = new TerminalReceiver(temp.resolve("store2"), "producer", WriterOptions.defaults());
        String ordinaryAck = other.receive(freshEnvelope(1, TerminalReceiver.FRESH_RETAINED_ORDINARY_PROFILE), temp), ordinaryId = Jv.parse(ordinaryAck).member("bindingId").text();
        assertThatThrownBy(() -> other.receiveRevision(ordinaryId, bytes(completeFresh(1, twoRoot)), twoRoot)).as("frozen-profile evidence under the ordinary envelope").isInstanceOf(IllegalArgumentException.class).hasMessageContaining("retained_profile");
    }

    @Test void frozenAndFreshProfilesCannotBeAttachedToEachOthersGenerations() throws Exception {
        Path store = temp.resolve("store"); TerminalReceiver receiver = new TerminalReceiver(store, "producer", WriterOptions.defaults());
        var fresh = FreshRetainedClaudeAcquisition.qualify(FRESH, "E1", temp.resolve("fresh"), TerminalReceiver.FRESH_RETAINED_PROFILE); Path freshRoot = fresh.manifest().getParent();
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
            var q = FreshRetainedClaudeAcquisition.qualify(FRESH, "E1", temp.resolve("q-" + fault), TerminalReceiver.FRESH_RETAINED_PROFILE); Path root = q.manifest().getParent();
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
