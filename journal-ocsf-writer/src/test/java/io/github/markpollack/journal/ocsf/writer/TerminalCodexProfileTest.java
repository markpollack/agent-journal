package io.github.markpollack.journal.ocsf.writer;

import io.github.markpollack.journal.tck.ocsf.OcsfPackage;
import io.github.markpollack.journal.tck.ocsf.PackageReader;
import io.github.markpollack.journal.tck.ocsf.TerminalBindingReader;
import io.github.markpollack.journal.tck.ocsf.inventory.CodexAcquisition;
import io.github.markpollack.journal.tck.ocsf.inventory.AcquisitionVerification;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Independent packaged semantics of the labelled synthetic agreed fresh text case. */
class TerminalCodexProfileTest {
    @TempDir Path work;
    private Path fixture() { return Path.of("../journal-capture-tck/src/test/resources/ocsf/syn-codex-acquisition-0.166.0"); }
    private Jv.Obj association(io.github.markpollack.journal.tck.ocsf.inventory.AcquisitionQualifier.Qualified qualified) throws Exception {
        Path root = qualified.manifest().getParent(); Jv m = Jv.parse(Files.readString(qualified.manifest()));
        Jv.Obj a = (Jv.Obj) Jv.parse(Files.readString(fixture().resolve("A-initial.json")));
        a.put("acquisition", "COMPLETED").put("missing", new Jv.Arr()).put("revisionId", "final").put("priorRevisionId", "initial");
        Jv.Arr sources = new Jv.Arr();
        for (Jv s : m.member("sources").elements()) sources.add(occurrence(root, s, m));
        sources.add(occurrence(root, new Jv.Obj().put("path", "acquisition.json").put("role", "manifest").put("origin", "qualified-codex-manifest"), m));
        return a.put("sources", sources);
    }
    private Jv occurrence(Path root, Jv s, Jv m) throws Exception {
        String path = s.member("path").text(); byte[] bytes = Files.readAllBytes(root.resolve(path));
        return new Jv.Obj().put("sourceId", path).put("relativePath", path).put("origin", s.member("origin"))
                .put("sha256", SchemaPin.sha256(bytes)).put("size", bytes.length).put("start", 0).put("end", bytes.length)
                .put("stream", value(Map.of("stdin", "STDIN", "stream", "STDOUT", "stderr", "STDERR").get(s.member("role").text())))
                .put("nativeSessionId", m.path("run", "session_uid")).put("nativeTurnId", Jv.Null.INSTANCE).put("nativeConnectionId", Jv.Null.INSTANCE).put("clientConnectionId", "G");
    }
    private static Jv value(String s) { return s == null ? Jv.Null.INSTANCE : new Jv.Str(s); }
    private static Path events(Path root, String ack) {
        return Jv.parse(ack).member("artifacts").elements().stream().map(a -> a.member("relativePath").text()).filter(s -> s.endsWith("/events.jsonl")).map(root::resolve).findFirst().orElseThrow();
    }
    @Test void pendingOriginalAndQualifiedRevisionPreserveDecisionAndReadIndependently() throws Exception {
        var q = CodexAcquisition.qualify(fixture().resolve("codex-acquisition.json"), work.resolve("qualified"));
        Path store = work.resolve("store"); var receiver = new TerminalReceiver(store, "codex-example", WriterOptions.defaults());
        byte[] envelope = Files.readAllBytes(fixture().resolve("P.json")); String initial = receiver.receive(envelope, fixture());
        assertThat(receiver.receive(envelope, fixture())).isEqualTo(initial);
        String ack = receiver.receiveRevision(Jv.parse(initial).member("bindingId").text(), TerminalJson.write(association(q)).getBytes(java.nio.charset.StandardCharsets.UTF_8), q.manifest().getParent());
        Path events = events(store, ack); OcsfPackage pkg = PackageReader.open(events);
        assertThat(pkg.diagnostics).noneMatch(d -> d.severity().name().equals("DEFECT") || d.severity().name().equals("REFUSED"));
        assertThat(TerminalBindingReader.read(pkg.declarations).decision()).isEqualTo(Files.readString(fixture().resolve("D.json")));
        var report = new AcquisitionVerification().verify(pkg, events.getParent().resolve("sources"), events.getParent().resolve("sources/acquisition.json"), q.assemblyExpectation());
        assertThat(report.defects()).isEmpty();
        assertThat(io.github.markpollack.journal.tck.ocsf.OcsfReaderTool.acceptance(report).exit()).as(io.github.markpollack.journal.tck.ocsf.OcsfReaderTool.acceptance(report).toString()).isZero();
        assertThat(receiver.receive(envelope, fixture())).isEqualTo(initial);
    }
    @Test void initiallyCompleteOneShotBindsOnceAndRetriesWithoutChangingDecision() throws Exception {
        var q = CodexAcquisition.qualify(fixture().resolve("codex-acquisition.json"), work.resolve("qualified"));
        Jv.Obj a = association(q).put("priorRevisionId", Jv.Null.INSTANCE).put("revisionId", "initial");
        Jv.Obj p = (Jv.Obj) Jv.parse(Files.readString(fixture().resolve("P.json")));
        p.put("associationBytes", java.util.Base64.getEncoder().encodeToString(TerminalJson.write(a).getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        byte[] frozen = TerminalJson.write(p).getBytes(java.nio.charset.StandardCharsets.UTF_8);
        Path store = work.resolve("store"); var receiver = new TerminalReceiver(store, "codex-example", WriterOptions.defaults());
        String ack = receiver.receive(frozen, q.manifest().getParent());
        assertThat(receiver.receive(frozen, q.manifest().getParent())).isEqualTo(ack);
        Path file = events(store, ack); var pkg = PackageReader.open(file);
        var binding = TerminalBindingReader.read(pkg.declarations);
        assertThat(binding.originalBindingId()).isNull(); assertThat(binding.decision()).isEqualTo(Files.readString(fixture().resolve("D.json")));
        var report = new AcquisitionVerification().verify(pkg, file.getParent().resolve("sources"), file.getParent().resolve("sources/acquisition.json"), q.assemblyExpectation());
        assertThat(report.defects()).isEmpty();
        assertThat(io.github.markpollack.journal.tck.ocsf.OcsfReaderTool.acceptance(report).exit()).as(io.github.markpollack.journal.tck.ocsf.OcsfReaderTool.acceptance(report).toString()).isZero();
    }
    @Test void theRegisteredQualifierDeliversOneInitialBindingUnderItsTerminalProfile() throws Exception {
        Path sealed = fixture().resolve("codex-acquisition.json");
        var qualifier = io.github.markpollack.journal.tck.ocsf.inventory.AcquisitionQualifier.forProfile(Jv.parse(Files.readString(sealed)).member("profile").text()).orElseThrow();
        assertThat(qualifier.terminalProfile()).isEqualTo(TerminalReceiver.CODEX_PROFILE);
        var q = qualifier.qualify(sealed, work.resolve("qualified"));
        Jv.Obj p = (Jv.Obj) Jv.parse(Files.readString(fixture().resolve("P.json")));
        p.put("profile", qualifier.terminalProfile()).put("associationBytes", java.util.Base64.getEncoder().encodeToString(
                TerminalJson.write(association(q).put("priorRevisionId", Jv.Null.INSTANCE).put("revisionId", "initial")).getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        Path store = work.resolve("store"); var receiver = new TerminalReceiver(store, "codex-example", WriterOptions.defaults());
        Path file = events(store, receiver.receive(TerminalJson.write(p).getBytes(java.nio.charset.StandardCharsets.UTF_8), q.manifest().getParent()));
        assertThat(Files.readString(file)).as("the archived terminal envelope").contains("\\\"profile\\\":\\\"" + qualifier.terminalProfile() + "\\\"");
        var pkg = PackageReader.open(file);
        assertThat(TerminalBindingReader.read(pkg.declarations).decision()).isEqualTo(Files.readString(fixture().resolve("D.json")));
        var report = new AcquisitionVerification().verify(pkg, file.getParent().resolve("sources"), file.getParent().resolve("sources/acquisition.json"), q.assemblyExpectation());
        assertThat(report.defects()).isEmpty();
        assertThat(io.github.markpollack.journal.tck.ocsf.OcsfReaderTool.acceptance(report).exit()).as(io.github.markpollack.journal.tck.ocsf.OcsfReaderTool.acceptance(report).toString()).isZero();
    }
    @Test void originalIdentityAndWholeExtentsCannotBeRewrittenForFinalAttachment() throws Exception {
        var q = CodexAcquisition.qualify(fixture().resolve("codex-acquisition.json"), work.resolve("qualified"));
        var receiver = new TerminalReceiver(work.resolve("store"), "codex-example", WriterOptions.defaults());
        String initial = receiver.receive(Files.readAllBytes(fixture().resolve("P.json")), fixture()); String id = Jv.parse(initial).member("bindingId").text();
        for (String field : List.of("nativeSessionId", "clientConnectionId", "nativeTurnId", "start")) {
            Jv.Obj a = association(q); Jv.Obj s = (Jv.Obj) a.member("sources").elements().get(0);
            s.put(field, field.equals("start") ? new Jv.Num("1") : new Jv.Str("wrong"));
            assertThatThrownBy(() -> receiver.receiveRevision(id, TerminalJson.write(a).getBytes(java.nio.charset.StandardCharsets.UTF_8), q.manifest().getParent())).isInstanceOf(IllegalArgumentException.class);
        }
    }
}
