package io.github.markpollack.journal.tck.ocsf;

import io.github.markpollack.journal.tck.verify.Check;
import io.github.markpollack.journal.tck.verify.Json;
import io.github.markpollack.journal.tck.verify.NeutralJson;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Re-review of f6c54da, F1: the reader's relation over two observations' native {@code /arguments} values, with the real
 * MCP case's own declared payload boundary. A reader-rule control (the values are not recorded output): members named
 * like Journal provenance inside a payload are facts, also while the relation recurses into differing objects.
 */
class FinalRelationPayloadTest {
    private static int relation(String a, String b) throws IOException {
        OcsfPackage pkg = PackageReader.open(TerminalRealRecordingTest.root("claude-mcp"));
        List<Event> events = pkg.observations().stream().filter(e -> e.className != null).limit(2).toList();
        Check check = new Check(Check.Category.NORMALIZATION, "source.value", "main/stdout.jsonl", Check.Outcome.VERIFIED, "native value");
        List<FinalComparisons.Supported> values = List.of(
                new FinalComparisons.Supported("/arguments", "/arguments", NeutralJson.parse(a), check, 0, events.get(0)),
                new FinalComparisons.Supported("/arguments", "/arguments", NeutralJson.parse(b), check, 0, events.get(1)));
        return FinalRelation.relation(Map.of("/arguments", values), 2, PayloadBoundary.of(pkg.catalog));
    }

    @Test void differingProviderBasisMembersOfArgumentsConflict() throws IOException {
        assertThat(relation("{\"provider_basis\":1}", "{\"provider_basis\":2}")).isEqualTo(5);
    }

    @Test void differingBasisMembersOfArgumentsConflict() throws IOException {
        assertThat(relation("{\"basis\":\"a\"}", "{\"basis\":\"b\"}")).isEqualTo(5);
    }

    @Test void equalArgumentsAreIdentical() throws IOException {
        assertThat(relation("{\"provider_basis\":1}", "{\"provider_basis\":1}")).isEqualTo(1);
    }

    /** The boundary is the package's own declared closure: here it includes slots the old hard-coded list missed. */
    @Test void theRealCasesDeclaredPayloadsIncludeTheSlotsTheOldListMissed() throws IOException {
        PayloadBoundary b = PayloadBoundary.of(PackageReader.open(TerminalRealRecordingTest.root("claude-mcp")).catalog);
        assertThat(b.names()).contains("arguments", "mcp_structured_content", "observed_values", "sandbox_policy", "error_code").doesNotContain("data");
        assertThat(b.contains("/assembly/assembly_conflicts/0/observed_values/0/basis")).isTrue();
        assertThat(b.contains("/assembly/assembly_conflicts/0/field_pointer")).isFalse();
    }
}
