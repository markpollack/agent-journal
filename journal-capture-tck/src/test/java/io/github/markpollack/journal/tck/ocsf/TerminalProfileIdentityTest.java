package io.github.markpollack.journal.tck.ocsf;

import io.github.markpollack.journal.tck.ocsf.inventory.ClaudeAcquisition;
import io.github.markpollack.journal.tck.ocsf.ClaudeSdkFamily;
import io.github.markpollack.journal.tck.ocsf.inventory.CodexAcquisition;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/** Stage A review F7: each qualifier's terminal wire profile is the one the TCK's terminal reader accepts. */
class TerminalProfileIdentityTest {
    @Test void eachQualifiersTerminalProfileIsTheReadersOwn() {
        assertThat(ClaudeAcquisition.qualifier("2.1.319").terminalProfile()).isEqualTo(ClaudeSdkFamily.TERMINAL_PROFILE);
        assertThat(CodexAcquisition.TERMINAL_PROFILE).isEqualTo(TerminalCodexReading.PROFILE);
    }
}
