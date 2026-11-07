package io.github.markpollack.journal.ocsf.writer;

import io.github.markpollack.journal.tck.ocsf.inventory.AcquisitionQualifier;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/** Stage A review F7: each registered qualifier's terminal wire profile is the one the receiver delivers under. */
class TerminalProfileIdentityTest {
    @Test void eachQualifiersTerminalProfileIsTheReceiversOwn() {
        assertThat(AcquisitionQualifier.forProfile("claude-code-2.1.316-sdk-stdio-session-file").orElseThrow().terminalProfile()).isEqualTo(TerminalContract.CLAUDE_PROFILE);
        assertThat(AcquisitionQualifier.forProfile("codex-cli-0.166.0-exec-stdio-rollout").orElseThrow().terminalProfile()).isEqualTo(TerminalCodexProfile.PROFILE);
    }
}
