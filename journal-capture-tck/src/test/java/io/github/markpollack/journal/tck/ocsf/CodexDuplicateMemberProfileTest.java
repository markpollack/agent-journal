package io.github.markpollack.journal.tck.ocsf;

import io.github.markpollack.journal.tck.verify.Json;
import io.github.markpollack.journal.tck.verify.NeutralJson;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The oracle profile's reading of repeated object members on the Codex exec stream, independently of the writer: only
 * the declared web_search form (item.id twice, the exec item id first) states a tool fact, under the first id; any
 * other repeat states nothing, every such line is owned and retained as an opaque record (never normalized), and the
 * neutral parser stays strict.
 */
class CodexDuplicateMemberProfileTest {

    // SYNTHETIC unit inputs in the vendor's key order; only the first is the form the pinned emitter writes
    static final List<String> INPUTS = List.of(
            "{\"type\":\"item.started\",\"item\":{\"id\":\"item_1\",\"type\":\"web_search\",\"id\":\"search-1\",\"query\":\"\",\"action\":{\"type\":\"other\"}}}",
            "{\"type\":\"item.started\",\"item\":{\"id\":\"item_2\",\"type\":\"web_search\",\"query\":\"a\",\"query\":\"b\",\"action\":{\"type\":\"other\"}}}",
            "{\"type\":\"item.started\",\"item\":{\"id\":\"item_3\",\"type\":\"web_search\",\"id\":\"search-3\",\"id\":\"search-3b\",\"query\":\"\",\"action\":{\"type\":\"other\"}}}",
            "{\"type\":\"item.completed\",\"item\":{\"id\":\"item_4\",\"type\":\"agent_message\",\"id\":\"msg-4\",\"text\":\"x\"}}",
            "{\"type\":\"item.started\",\"type\":\"item.completed\",\"item\":{\"id\":\"item_5\",\"type\":\"web_search\",\"query\":\"\",\"action\":{\"type\":\"other\"}}}");

    @Test
    void onlyTheDeclaredWebSearchFormStatesAToolRequestUnderTheFirstId() {
        NativeArtifact art = NativeArtifact.ofBytes(Path.of("stream.jsonl"), (String.join("\n", INPUTS) + "\n").getBytes(StandardCharsets.UTF_8));
        ExpectedFacts f = SourceProfile.forName("codex_stream").expected(art,
                new Json.JObject(Map.of("artifact", new Json.JString("stream.jsonl"), "profile", new Json.JString("codex_stream"))), null);
        assertThat(f.requests).hasSize(1);
        assertThat(f.requests.get(0).recordIndex()).isEqualTo(1);
        assertThat(f.requests.get(0).id()).as("the first member, the exec item id; never the last").isEqualTo("item_1");
        assertThat(f.requests.get(0).identityBasisId()).isEqualTo(2);
        assertThat(f.results).isEmpty();
        assertThat(f.responses).as("an agent_message with a repeated id is not read").isEmpty();
        assertThat(f.owned).containsExactly(1, 2, 3, 4, 5);
        assertThat(f.normalized).as("every repeated-member line is retained verbatim, never as a parsed record").isEmpty();
        for (String line : INPUTS) {
            assertThatThrownBy(() -> NeutralJson.parse(line)).isInstanceOf(IllegalArgumentException.class);
        }
        NeutralJson.WithRepeats w = NeutralJson.parseReportingRepeats(INPUTS.get(0));
        assertThat(w.repeated()).hasSize(1);
        assertThat(w.repeated().get(0).path()).isEqualTo("item.id");
        assertThat(w.repeated().get(0).values()).containsExactly(new Json.JString("item_1"), new Json.JString("search-1"));
    }
}
