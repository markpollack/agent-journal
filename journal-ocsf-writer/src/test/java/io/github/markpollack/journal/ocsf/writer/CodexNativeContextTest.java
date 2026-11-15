package io.github.markpollack.journal.ocsf.writer;

import io.github.markpollack.journal.tck.ocsf.CodexAcquisitionReading;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Edited real one-read capture: the two-block context structure was observed on Codex 0.166.0.
 * Context bodies are sanitized replacements, not a claim that this composite capture ran live. */
class CodexNativeContextTest {
    @TempDir Path temp;

    private Path capture(String name, Consumer<List<Jv>> change) throws IOException {
        Path dir = temp.resolve(name);
        RealRecordingTest.copyTree(RealRecording.nativeDir(RealRecording.PUBLIC.resolve("codex-one-read")), dir);
        Path manifest = dir.resolve("client-acquisition.json");
        Jv.Obj m = (Jv.Obj) Jv.parse(Files.readString(manifest));
        List<Jv> rows = new ArrayList<>(RealRecording.lines(dir.resolve("native.bin")));
        String turn = m.member("turns").elements().get(0).member("native_turn_uid").text();
        Jv.Obj metadata = new Jv.Obj().put("turn_id", turn)
                .put("content_item_kinds", Jv.parse("[\"agents_md.instructions\",\"environments.environment_context\"]"))
                .put("content_item_metadata", Jv.parse("[{\"provenance\":{\"type\":\"agents_md\"}},{\"provenance\":{\"type\":\"harness\",\"hook\":false}}]"));
        Jv.Obj payload = new Jv.Obj().put("type", "message").put("role", "user")
                .put("content", new Jv.Arr().add(block("# AGENTS.md instructions\n\nRead the project guidance."))
                        .add(block("<environment_context>\n<cwd>/workspace</cwd>\n</environment_context>")))
                .put("internal_chat_message_metadata_passthrough", metadata);
        rows.add(2, new Jv.Obj().put("type", "response_item").put("payload", payload));
        change.accept(rows);
        Files.writeString(dir.resolve("native.bin"), String.join("\n", rows.stream().map(Jv::write).toList()) + "\n");
        for (Jv source : m.member("sources").elements()) {
            byte[] bytes = Files.readAllBytes(dir.resolve(source.member("path").text()));
            ((Jv.Obj) source).put("sha256", SchemaPin.sha256(bytes)).put("bytes", bytes.length).put("observed", bytes.length);
            if ("transcript".equals(source.member("role").text())) ((Jv.Obj) source).put("records", rows.size());
        }
        Files.writeString(manifest, Jv.write(m));
        return manifest;
    }

    private static Jv.Obj block(String text) { return new Jv.Obj().put("type", "input_text").put("text", text); }
    private static Jv.Obj context(List<Jv> rows) { return (Jv.Obj) rows.get(2).member("payload"); }
    private static Jv.Obj metadata(List<Jv> rows) { return (Jv.Obj) context(rows).member("internal_chat_message_metadata_passthrough"); }

    static void writer(Path manifest, boolean retained) throws IOException {
        Jv m = Jv.parse(Files.readString(manifest));
        var roles = new HashMap<String, Jv>();
        m.member("sources").elements().forEach(s -> roles.put(s.member("role").text(), s));
        TerminalCodexNative.validate(manifest.getParent(), m, roles, retained, false);
    }

    private void refused(String name, Consumer<List<Jv>> edit) throws IOException {
        Path manifest = capture(name, edit);
        assertThatThrownBy(() -> CodexAcquisitionReading.validate(manifest)).as("independent reader: " + name).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> writer(manifest, false)).as("writer: " + name).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void sourceAttributedContextIsAcceptedByBothIndependentPaths() throws IOException {
        Path manifest = capture("accepted", rows -> { });
        assertThatCode(() -> CodexAcquisitionReading.validate(manifest)).doesNotThrowAnyException();
        assertThatCode(() -> writer(manifest, false)).doesNotThrowAnyException();
    }

    @Test void contextCannotBeUnattributedReorderedDuplicatedOrUsedAsExtraHistory() throws IOException {
        refused("missing-metadata", rows -> context(rows).members().remove("internal_chat_message_metadata_passthrough"));
        refused("missing-turn", rows -> metadata(rows).members().remove("turn_id"));
        refused("wrong-turn", rows -> metadata(rows).put("turn_id", "unrelated-turn"));
        refused("wrong-kind", rows -> metadata(rows).put("content_item_kinds", Jv.parse("[\"user\",\"environments.environment_context\"]")));
        refused("wrong-provenance", rows -> metadata(rows).put("content_item_metadata", Jv.parse("[{\"provenance\":{\"type\":\"user\"}},{\"provenance\":{\"type\":\"harness\",\"hook\":false}}]")));
        refused("hook", rows -> ((Jv.Obj) metadata(rows).member("content_item_metadata").elements().get(1).member("provenance")).put("hook", true));
        refused("extra-block", rows -> ((Jv.Arr) context(rows).member("content")).add(block("Another user request")));
        refused("unknown-text", rows -> ((Jv.Obj) context(rows).member("content").elements().get(0)).put("text", "Another user request"));
        refused("heading-prefix", rows -> ((Jv.Obj) context(rows).member("content").elements().get(0)).put("text", "# AGENTS.md instructions forged\nrequest"));
        refused("reordered", rows -> java.util.Collections.reverse(context(rows).member("content").elements()));
        refused("duplicate", rows -> rows.add(3, rows.get(2)));
        refused("before-start", rows -> rows.add(1, rows.remove(2)));
        refused("after-prompt", rows -> { Jv context = rows.remove(2); rows.add(rows.size() - 1, context); });
    }

    @Test void exactPromptMembershipAndCustodyStillReject() throws IOException {
        String prompt = Files.readString(RealRecording.nativeDir(RealRecording.PUBLIC.resolve("codex-one-read")).resolve("stdin.bin"));
        refused("missing-prompt", rows -> assertThat(rows.removeIf(r -> isPrompt(r, prompt))).isTrue());
        refused("duplicate-prompt", rows -> rows.add(rows.size() - 1, rows.stream().filter(r -> isPrompt(r, prompt)).findFirst().orElseThrow()));
        Path manifest = capture("tampered", rows -> { });
        Files.writeString(manifest.getParent().resolve("native.bin"), "changed");
        assertThatThrownBy(() -> CodexAcquisitionReading.validate(manifest)).hasMessage("source hash mismatch");
        assertThatThrownBy(() -> writer(manifest, false)).hasMessage("Codex original changed");
    }

    private static boolean isPrompt(Jv row, String prompt) {
        Jv p = row.member("payload");
        return p != null && p.member("content") instanceof Jv.Arr content && content.elements().size() == 1
                && prompt.equals(content.elements().get(0).member("text").text());
    }
}
