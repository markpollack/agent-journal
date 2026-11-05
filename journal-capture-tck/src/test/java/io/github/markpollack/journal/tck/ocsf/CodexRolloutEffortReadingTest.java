package io.github.markpollack.journal.tck.ocsf;

import io.github.markpollack.journal.tck.verify.Json;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

/** Follow-up review finding 3 (2026-11-05): the oracle reads reasoning_effort in the mapper's order, the reported top-level member first,
 * else collaboration_mode.settings, and reports nothing when both are absent or null; the owner is the reported thread_id. */
class CodexRolloutEffortReadingTest {
    private static ExpectedFacts facts(Path dir, String settings) throws Exception {
        Path file = dir.resolve("line.jsonl");
        Files.writeString(file, "{\"type\":\"event_msg\",\"timestamp\":\"2026-11-04T00:00:00Z\",\"payload\":{\"type\":\"thread_settings_applied\",\"thread_id\":\"T\",\"thread_settings\":" + settings + "}}\n");
        NativeArtifact.Record record = NativeArtifact.read(file).records().get(0);
        ExpectedFacts f = new ExpectedFacts("line.jsonl");
        assertThat(CodexRolloutContextReading.read(f, record, null)).isTrue();
        return f;
    }

    @Test void topLevelEffortWinsOverTheNestedOne(@TempDir Path dir) throws Exception {
        ExpectedFacts f = facts(dir, "{\"model\":\"m\",\"reasoning_effort\":\"low\",\"collaboration_mode\":{\"settings\":{\"reasoning_effort\":\"high\"}}}");
        assertThat(f.contexts.get(0).settings().get("reasoning_effort").text()).isEqualTo("low");
    }

    @Test void nestedEffortIsReadWhenTheTopLevelIsAbsent(@TempDir Path dir) throws Exception {
        ExpectedFacts f = facts(dir, "{\"model\":\"m\",\"collaboration_mode\":{\"settings\":{\"reasoning_effort\":\"high\"}}}");
        assertThat(f.contexts.get(0).settings().get("reasoning_effort").text()).isEqualTo("high");
    }

    @Test void nullEffortEverywhereReportsNothing(@TempDir Path dir) throws Exception {
        ExpectedFacts f = facts(dir, "{\"model\":\"m\",\"collaboration_mode\":{\"settings\":{\"reasoning_effort\":null}}}");
        assertThat(f.contexts.get(0).settings()).doesNotContainKey("reasoning_effort").containsEntry("model", new Json.JString("m"));
    }
}
