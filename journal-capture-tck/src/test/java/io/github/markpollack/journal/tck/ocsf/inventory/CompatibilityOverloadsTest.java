package io.github.markpollack.journal.tck.ocsf.inventory;

import io.github.markpollack.journal.tck.ocsf.CodexRetainedGeneration;
import io.github.markpollack.journal.tck.ocsf.FreshRetainedGeneration;
import io.github.markpollack.journal.tck.ocsf.RetainedGeneration;
import io.github.markpollack.journal.tck.verify.Json;
import io.github.markpollack.journal.tck.verify.NeutralJson;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

/** Owner continuation C1 (2026-11-05): the public signatures that existed at af88650 compile and behave exactly as before, defaulting to the
 * frozen profiles; the mechanical counterpart is the javap public-member comparison recorded in the steward. */
class CompatibilityOverloadsTest {
    static final Path CODEX = Path.of("src/test/resources/ocsf/syn-codex-retained-0.166.0/codex-thread.json");
    static final Path CODEX3 = Path.of("src/test/resources/ocsf/syn-codex-retained-3turn-0.166.0/codex-thread.json");
    static final Path FRESH = Path.of("src/test/resources/retained-fresh-generation");
    static final Path FRESH3 = Path.of("src/test/resources/retained-fresh-generation-3turn");

    @Test void originalCodexSignaturesDefaultToTheFrozenTwoTurnProfile(@TempDir Path dir) throws Exception {
        Json manifest = NeutralJson.readDocument(CODEX);
        CodexRetainedGeneration.RetainedThread thread = CodexRetainedGeneration.validate(manifest, CODEX.toAbsolutePath().getParent()); // af88650 signature
        assertThat(thread.turns()).hasSize(2);
        assertThatThrownBy(() -> CodexRetainedGeneration.validate(NeutralJson.readDocument(CODEX3), CODEX3.toAbsolutePath().getParent()))
                .isInstanceOf(CodexRetainedGeneration.Rejection.class).hasMessageContaining("exactly two turns");
        Path out = dir.resolve("q"); CodexRetainedAcquisition.qualify(CODEX, thread.turns().get(1).executionId(), out); // af88650 signature
        assertThat(NeutralJson.readDocument(out.resolve("acquisition.json")).member("retained_profile").text()).isEqualTo(io.github.markpollack.journal.tck.ocsf.CodexExecFamily.RETAINED_TERMINAL_PROFILE)
                .satisfies(p -> assertThat(io.github.markpollack.journal.tck.ocsf.CodexExecFamily.contract(p)).isEqualTo(CodexRetainedGeneration.PROFILE)); // the frozen /1 contract, under its version-neutral name
        Path cli = dir.resolve("cli"); CodexRetainedAcquisition.main(new String[] { CODEX.toString(), thread.turns().get(0).executionId(), cli.toString() }); // three-argument command
        assertThat(NeutralJson.readDocument(cli.resolve("acquisition.json")).member("retained_profile").text()).isEqualTo(io.github.markpollack.journal.tck.ocsf.CodexExecFamily.RETAINED_TERMINAL_PROFILE)
                .satisfies(p -> assertThat(io.github.markpollack.journal.tck.ocsf.CodexExecFamily.contract(p)).isEqualTo(CodexRetainedGeneration.PROFILE)); // the frozen /1 contract, under its version-neutral name
        assertThatThrownBy(() -> CodexRetainedAcquisition.main(new String[] { CODEX.toString() })).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("usage");
    }

    @Test void originalClaudeFreshSignaturesDefaultToTheFrozenTwoTurnProfile(@TempDir Path dir) throws Exception {
        Path manifest = FRESH.resolve("retained-fresh-generation.json"); Json generation = NeutralJson.readDocument(manifest);
        FreshRetainedGeneration.validate(generation, FRESH); // af88650 signature
        assertThatThrownBy(() -> FreshRetainedGeneration.validate(NeutralJson.readDocument(FRESH3.resolve("retained-fresh-generation.json")), FRESH3))
                .isInstanceOf(FreshRetainedGeneration.Rejection.class).hasMessageContaining("exactly two turns");
        Path out = dir.resolve("q"); FreshRetainedClaudeAcquisition.qualify(manifest, "E2", out); // af88650 signature
        assertThat(NeutralJson.readDocument(out.resolve("acquisition.json")).member("retained_profile").text()).isEqualTo(io.github.markpollack.journal.tck.ocsf.ClaudeSdkFamily.FRESH_RETAINED_TERMINAL_PROFILE)
                .satisfies(p -> assertThat(io.github.markpollack.journal.tck.ocsf.ClaudeSdkFamily.contract(p)).isEqualTo(FreshRetainedGeneration.PROFILE)); // the frozen /1 contract, under its version-neutral name
        byte[] viaBoolean = RetainedGeneration.projection(generation, "E2", "TRANSCRIPT", FRESH, true); // af88650 signature
        assertThat(viaBoolean).isEqualTo(RetainedGeneration.projection(generation, "E2", "TRANSCRIPT", FRESH, FreshRetainedGeneration.PROFILE)).isEqualTo(Files.readAllBytes(out.resolve("turn/transcript.jsonl")));
        Path cli = dir.resolve("cli"); FreshRetainedClaudeAcquisition.main(new String[] { manifest.toString(), "E1", cli.toString() });
        assertThat(NeutralJson.readDocument(cli.resolve("acquisition.json")).member("retained_profile").text()).isEqualTo(io.github.markpollack.journal.tck.ocsf.ClaudeSdkFamily.FRESH_RETAINED_TERMINAL_PROFILE)
                .satisfies(p -> assertThat(io.github.markpollack.journal.tck.ocsf.ClaudeSdkFamily.contract(p)).isEqualTo(FreshRetainedGeneration.PROFILE)); // the frozen /1 contract, under its version-neutral name
        assertThat(List.of(CodexRetainedGeneration.PROFILE, FreshRetainedGeneration.PROFILE)).allMatch(p -> p.endsWith("/1"));
    }
}
