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
        assertThat(w.repeated().get(0).container()).containsExactly(NeutralJson.Step.member("item", 1));
        assertThat(w.repeated().get(0).member()).isEqualTo("id");
        assertThat(w.repeated().get(0).pointer()).isEqualTo("/item/id");
        assertThat(w.repeated().get(0).values()).containsExactly(new Json.JString("item_1"), new Json.JString("search-1"));
    }

    /** SYNTHETIC (review finding): a normal nested item.id plus two root members literally named "item.id"; and the root pair alone. */
    static final List<String> DOTTED_ROOT_LITERALS = List.of(
            "{\"type\":\"item.started\",\"item\":{\"id\":\"item_1\",\"type\":\"web_search\",\"query\":\"\",\"action\":{\"type\":\"other\"}},\"item.id\":\"a\",\"item.id\":\"b\"}",
            "{\"type\":\"item.started\",\"item\":{\"type\":\"web_search\",\"query\":\"\",\"action\":{\"type\":\"other\"}},\"item.id\":\"a\",\"item.id\":\"b\"}");

    /**
     * A root member literally named {@code item.id} is the location {@code /item.id}, not the search item's
     * {@code /item/id}: a repeat of it is not the declared form, whether or not the item also carries its own id.
     */
    @Test
    void rootMembersLiterallyNamedItemIdAreNotTheDeclaredForm() {
        NativeArtifact art = NativeArtifact.ofBytes(Path.of("stream.jsonl"), (String.join("\n", DOTTED_ROOT_LITERALS) + "\n").getBytes(StandardCharsets.UTF_8));
        ExpectedFacts f = SourceProfile.forName("codex_stream").expected(art,
                new Json.JObject(Map.of("artifact", new Json.JString("stream.jsonl"), "profile", new Json.JString("codex_stream"))), null);
        assertThat(f.requests).as("no tool fact from a root member that only looks like item.id").isEmpty();
        assertThat(f.owned).containsExactly(1, 2);
        assertThat(f.normalized).isEmpty();
    }

    /** One SYNTHETIC line, the first id the profile must read when it is the declared form (else {@code null}), and the pointer of a repeat in it. */
    record Case(String name, String line, String firstId, String pointer) {
    }

    static final List<Case> CASES = List.of(
            new Case("declared, started", INPUTS.get(0), "item_1", "/item/id"),
            new Case("declared, completed", "{\"type\":\"item.completed\",\"item\":{\"id\":\"item_2\",\"type\":\"web_search\",\"id\":\"search-2\",\"query\":\"q\",\"action\":{\"type\":\"search\",\"query\":\"q\"}}}", "item_2", "/item/id"),
            new Case("root members literally named item.id, with a nested id", DOTTED_ROOT_LITERALS.get(0), null, "/item.id"),
            new Case("root members literally named item.id, no nested id", DOTTED_ROOT_LITERALS.get(1), null, "/item.id"),
            new Case("a member of item literally named item.id", "{\"type\":\"item.started\",\"item\":{\"id\":\"item_3\",\"type\":\"web_search\",\"item.id\":\"x\",\"item.id\":\"y\"}}", null, "/item/item.id"),
            new Case("a name with a slash", "{\"type\":\"item.started\",\"item\":{\"id\":\"item_4\",\"type\":\"web_search\",\"a/b\":\"x\",\"a/b\":\"y\"}}", null, "/item/a~1b"),
            new Case("a name with a tilde", "{\"type\":\"item.started\",\"item\":{\"id\":\"item_5\",\"type\":\"web_search\",\"i~d\":\"x\",\"i~d\":\"y\"}}", null, "/item/i~0d"),
            new Case("id repeated at the root", "{\"type\":\"item.started\",\"id\":\"x\",\"id\":\"y\",\"item\":{\"id\":\"item_6\",\"type\":\"web_search\"}}", null, "/id"),
            new Case("id repeated inside the action", "{\"type\":\"item.started\",\"item\":{\"id\":\"item_7\",\"type\":\"web_search\",\"action\":{\"id\":\"x\",\"id\":\"y\"}}}", null, "/item/action/id"),
            new Case("id repeated inside an array element", "{\"type\":\"item.completed\",\"item\":{\"id\":\"item_8\",\"type\":\"web_search\",\"results\":[{\"id\":\"x\",\"id\":\"y\"}]}}", null, "/item/results/0/id"),
            new Case("three ids", INPUTS.get(2), null, "/item/id"),
            new Case("a second id that is not a string", "{\"type\":\"item.started\",\"item\":{\"id\":\"item_10\",\"type\":\"web_search\",\"id\":10}}", null, "/item/id"),
            new Case("another item type", INPUTS.get(3), null, "/item/id"),
            new Case("another record type", "{\"type\":\"turn.completed\",\"item\":{\"id\":\"item_12\",\"type\":\"web_search\",\"id\":\"s\"}}", null, "/item/id"),
            new Case("an unrelated repeat beside the declared one", "{\"type\":\"item.started\",\"item\":{\"id\":\"item_13\",\"type\":\"web_search\",\"id\":\"s\",\"query\":\"a\",\"query\":\"b\"}}", null, "/item/query"),
            new Case("the item object itself repeated", "{\"type\":\"item.started\",\"item\":{\"id\":\"item_14\",\"type\":\"web_search\"},\"item\":{\"id\":\"item_15\",\"type\":\"web_search\",\"id\":\"s\"}}", null, "/item"));

    /**
     * The profile's own reading of every case, line by line: a request only for the two declared lines, under the first
     * id; every line owned and never normalized; every repeat reported at its exact RFC 6901 location.
     */
    @Test
    void onlyTheExactDeclaredLocationStatesARequestAndEveryRepeatHasItsExactLocation() {
        for (Case c : CASES) {
            NativeArtifact art = NativeArtifact.ofBytes(Path.of("stream.jsonl"), (c.line() + "\n").getBytes(StandardCharsets.UTF_8));
            ExpectedFacts f = SourceProfile.forName("codex_stream").expected(art,
                    new Json.JObject(Map.of("artifact", new Json.JString("stream.jsonl"), "profile", new Json.JString("codex_stream"))), null);
            assertThat(art.record(1).parsed()).as(c.name() + ": strict parsing").isFalse();
            if (c.firstId() == null) {
                assertThat(f.requests).as(c.name()).isEmpty();
                assertThat(f.results).as(c.name()).isEmpty();
            }
            else {
                assertThat(f.requests.size() + f.results.size()).as(c.name()).isEqualTo(1);
                String id = f.requests.isEmpty() ? f.results.get(0).id() : f.requests.get(0).id();
                assertThat(id).as(c.name() + ": the first id").isEqualTo(c.firstId());
            }
            assertThat(f.owned).containsExactly(1);
            assertThat(f.normalized).isEmpty();
            assertThat(NeutralJson.parseReportingRepeats(c.line()).repeated().stream().map(NeutralJson.Repeated::pointer).toList()).as(c.name()).contains(c.pointer());
        }
        NeutralJson.WithRepeats twice = NeutralJson.parseReportingRepeats(CASES.get(15).line());
        assertThat(twice.repeated()).extracting(NeutralJson.Repeated::container)
                .contains(List.of(NeutralJson.Step.member("item", 2)), List.of());
    }
}
