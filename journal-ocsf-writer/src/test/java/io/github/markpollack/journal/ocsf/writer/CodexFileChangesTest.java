package io.github.markpollack.journal.ocsf.writer;

import io.github.markpollack.journal.tck.ocsf.OcsfReaderTool;
import io.github.markpollack.journal.tck.ocsf.PackageReader;
import io.github.markpollack.journal.tck.ocsf.inventory.AcquisitionVerification;
import io.github.markpollack.journal.tck.ocsf.inventory.CodexAcquisition;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.assertThat;

/** Synthetic source-derived FileChange cases; no live provider or private source payload. */
class CodexFileChangesTest {
    @TempDir Path work;
    private Path assemble() throws Exception {
        Path inputs = work.resolve("inputs"); Files.createDirectory(inputs);
        Path fixture = Path.of("../journal-capture-tck/src/test/resources/ocsf/syn-codex-onboarding-0.166.0");
        Jv.Obj manifest = (Jv.Obj) Jv.parse(Files.readString(fixture.resolve("codex-acquisition.json")));
        for (Jv source : manifest.member("sources").elements()) Files.copy(fixture.resolve(source.member("path").text()), inputs.resolve(source.member("path").text()));
        Path nativeFile = inputs.resolve("native.jsonl");
        List<String> rows = new ArrayList<>(Files.readAllLines(nativeFile));
        for (int i = 0; i < 4; i++) {
            String kind = List.of("add", "update", "delete", "update").get(i);
            Jv.Obj change = new Jv.Obj().put("type", kind);
            if ("update".equals(kind)) change.put("unified_diff", "@@ -1 +1 @@\n-old\n+new\n").put("move_path", i == 3 ? new Jv.Str("/synthetic/renamed.txt") : Jv.Null.INSTANCE);
            else change.put("content", "synthetic content\n");
            Jv.Obj item = new Jv.Obj().put("type", "FileChange").put("id", "patch-" + i)
                    .put("status", i == 3 ? "failed" : "completed").put("stdout", "").put("stderr", i == 3 ? "synthetic failure" : "")
                    .put("changes", new Jv.Obj().put("/synthetic/file.txt", change));
            Jv.Obj payload = new Jv.Obj().put("type", "item_completed").put("thread_id", "11111111-1111-4111-8111-111111111111")
                    .put("turn_id", "22222222-2222-4222-8222-222222222222").put("item", item);
            rows.add(rows.size() - 1, Jv.write(new Jv.Obj().put("type", "event_msg").put("timestamp", "2026-11-03T00:00:00Z").put("payload", payload)));
        }
        Files.writeString(nativeFile, String.join("\n", rows) + "\n");
        for (Jv source : manifest.member("sources").elements()) if ("transcript".equals(source.member("role").text())) {
            ((Jv.Obj) source).put("bytes", Files.size(nativeFile)).put("observed", Files.size(nativeFile))
                    .put("records", rows.size()).put("sha256", java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(nativeFile))));
        }
        Path mp = inputs.resolve("codex-acquisition.json"); Files.writeString(mp, Jv.write(manifest));
        var qualified = CodexAcquisition.qualify(mp, work.resolve("qualified"));
        Path archive = work.resolve("archive"); FinalAssembly.assemble(qualified.manifest(), archive);
        return archive.resolve("events.jsonl");
    }
    @Test void sourceDerivedEditsRetainKindsPathsMetadataOwnershipAndFailure() throws Exception {
        Path events = assemble();
        var report = new AcquisitionVerification().verify(PackageReader.open(events), work.resolve("qualified"),
                work.resolve("qualified/acquisition.json"), work.resolve("qualified/assembly-expectation.json"));
        assertThat(report.defects()).isEmpty();
        assertThat(OcsfReaderTool.acceptance(report).exit()).as(report.toMap().get("notVerified").toString()).isZero();
        
        var edits = Files.readAllLines(events).stream().map(Jv::parse).filter(e -> e.member("file_changes") != null).toList();
        assertThat(edits).hasSize(4);
        assertThat(edits.stream().map(e -> e.member("tool_call_uid").text()).distinct()).hasSize(4);
        assertThat(edits.get(3).member("status_id")).isEqualTo(new Jv.Num("2"));
    }
    @Test void omittedOrAlteredEditFactsAndCollapsedRepeatedEditsReject() throws Exception {
        Path events = assemble(); String original = Files.readString(events);
        for (String mutation : List.of("metadata", "owner", "path", "kind", "status", "diff", "collapse")) {
            var rows = new ArrayList<>(original.lines().map(Jv::parse).toList());
            Jv.Obj edit = (Jv.Obj) rows.stream().filter(e -> e.path("tool_operation", "data", "changes", "/synthetic/file.txt", "unified_diff") != null).findFirst().orElseThrow();
            Jv.Obj operation = (Jv.Obj) edit.member("tool_operation");
            if (mutation.equals("metadata")) operation.members().remove("data");
            if (mutation.equals("owner")) edit.put("tool_call_uid", "foreign");
            if (mutation.equals("path")) ((Jv.Obj) edit.member("file_changes").elements().get(0)).put("file_path", "/foreign");
            if (mutation.equals("kind")) ((Jv.Obj) edit.member("file_changes").elements().get(0)).put("change_kind", "add");
            if (mutation.equals("status")) edit.put("status_id", 2);
            if (mutation.equals("diff")) ((Jv.Obj) operation.path("data", "changes", "/synthetic/file.txt")).put("unified_diff", "foreign");
            if (mutation.equals("collapse")) rows.remove(edit);
            Files.writeString(events, rows.stream().map(Jv::write).collect(java.util.stream.Collectors.joining("\n", "", "\n")));
            var report = new AcquisitionVerification().verify(PackageReader.open(events), work.resolve("qualified"),
                    work.resolve("qualified/acquisition.json"), work.resolve("qualified/assembly-expectation.json"));
            assertThat(OcsfReaderTool.acceptance(report).exit()).as(mutation).isEqualTo(3);
        }
        Files.writeString(events, original);
    }
}
