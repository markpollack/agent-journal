package io.github.markpollack.journal.ocsf.writer;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * UC1-AC4 / RULE-6: a fact the selected stream does not report is a stated limitation of that source, carried by the
 * event file itself, and is not filled from another artifact. The Claude stream contract declares what its stream does
 * not report (final per-response usage and stop reason, the main thread's prompt); the committed live recording embeds
 * that declaration once, and its per-message usage stays provisional rather than being completed from the native
 * session file (whose archive the capture never parses: CliCaptureTest#theArchiveContentsCannotChangeTheNormalizedStreamOutput).
 * The Codex exec contract declares no such list yet; its limitations are stated in the catalog (for example the
 * response identity the stream omits) and in each event's basis.
 */
class StreamLimitationTest {

    @Test
    void theClaudeStreamContractDeclaresWhatTheStreamDoesNotReport() {
        Jv unavailable = new MappingContracts().contract(ClaudeStreamMapper.CONTRACT).member("unavailable_at_source");
        List<String> facts = new ArrayList<>();
        unavailable.elements().forEach(f -> facts.add(f.text()));
        assertThat(facts).anyMatch(f -> f.startsWith("final per-response usage and stop_reason")).anyMatch(f -> f.startsWith("the main thread's user prompt content"));
    }

    @Test
    void theLiveRecordingCarriesTheLimitationAndKeepsItsUsageProvisional() throws IOException {
        Path events = WriterTestSupport.FIXTURES.resolve(WriterTestSupport.LIVE_FEATURES).resolve("package").resolve("events.jsonl");
        int declared = 0;
        int responsesWithUsage = 0;
        for (String line : java.nio.file.Files.readAllLines(events, java.nio.charset.StandardCharsets.UTF_8)) {
            Jv o = Jv.parse(line);
            Jv d = o.member("declaration");
            if (d != null && "contract".equals(d.member("kind").text()) && o.path("contract", "embedded", "unavailable_at_source") != null) {
                declared++;
                assertThat(Jv.write(o.path("contract", "embedded", "unavailable_at_source"))).contains("final per-response usage");
            }
            if (d == null && "Model Response".equals(o.member("class_name") == null ? null : o.member("class_name").text()) && o.member("usage_observations") != null) {
                responsesWithUsage++;
                for (Jv u : o.member("usage_observations").elements()) {
                    assertThat(u.member("usage_status").text()).as("never completed from another artifact").isEqualTo("Provisional");
                }
            }
        }
        assertThat(declared).as("the limitation is declared once, in the event file").isEqualTo(1);
        assertThat(responsesWithUsage).isPositive();
    }
}
