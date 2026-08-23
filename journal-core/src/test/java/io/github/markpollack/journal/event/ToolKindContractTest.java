package io.github.markpollack.journal.event;

import com.agentclientprotocol.sdk.spec.AcpSchema;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class ToolKindContractTest {

    @Test
    void mirrorsTheAcpToolKindVocabulary() {
        Set<String> journalKinds = names(ToolKind.values());
        Set<String> acpKinds = names(AcpSchema.ToolKind.values());

        assertThat(journalKinds).containsExactlyInAnyOrderElementsOf(acpKinds);
    }

    @Test
    void everyCanonicalKindRoundTripsItsAcpWireValue() {
        assertThat(ToolKind.values()).allSatisfy(kind ->
                assertThat(ToolKind.fromWireValue(kind.wireValue())).isEqualTo(kind));
    }

    private static Set<String> names(Enum<?>[] values) {
        return Arrays.stream(values).map(Enum::name).collect(Collectors.toSet());
    }
}
