package io.github.markpollack.journal.tck.venn;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static io.github.markpollack.journal.tck.venn.VennMatchingTest.c;
import static io.github.markpollack.journal.tck.venn.VennMatchingTest.n;
import static io.github.markpollack.journal.tck.venn.VennMatchingTest.num;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The tests that matter most for the tool: start from a native and canonical pair known to be complete, take away
 * one canonical value at a time, and require the report to flag exactly the native path that value came from.
 */
class VennMutationTest {

    /** A small pair shaped like a Claude call: an assistant turn with text and a tool use, a tool result, usage and cost. */
    private static final List<Leaf> NATIVE = List.of(
            n("message.content[].text", "I will read the file first."),
            n("message.content[].name", "Read"),
            n("message.content[].input.file_path", "/work/project/src/Main.java"),
            n("message.content[].tool_use_id", "toolu_01ABCDEFGH"),
            n("message.content[].content", "public class Main { }"),
            n("message.usage.input_tokens", num("10334")),
            n("message.usage.cache_read_input_tokens", num("21632")),
            n("total_cost_usd", num("0.0054706")),
            n("result", "The file declares one class."));

    private static final List<Leaf> CANONICAL = List.of(
            c("text", "I will read the file first."),
            c("toolName", "Read"),
            c("input.file_path", "/work/project/src/Main.java"),
            c("id", "toolu_01ABCDEFGH"),
            c("output", "public class Main { }"),
            c("tokenUsage.inputTokens", num("10334")),
            c("tokenUsage.cacheReadTokens", num("21632")),
            c("cost.totalCost", num("0.0054706")),
            c("metadata.result", "The file declares one class."));

    private static final Map<String, String> SOURCE_OF = Map.of(
            "text", "message.content[].text",
            "toolName", "message.content[].name",
            "input.file_path", "message.content[].input.file_path",
            "id", "message.content[].tool_use_id",
            "output", "message.content[].content",
            "tokenUsage.inputTokens", "message.usage.input_tokens",
            "tokenUsage.cacheReadTokens", "message.usage.cache_read_input_tokens",
            "cost.totalCost", "total_cost_usd",
            "metadata.result", "result");

    @Test
    void theStartingPairIsComplete() {
        assertThat(Venn.compare(NATIVE, CANONICAL).gaps()).isEmpty();
    }

    @Test
    void takingAwayAnyOneCanonicalValueFlagsExactlyItsNativePath() {
        for (int i = 0; i < CANONICAL.size(); i++) {
            List<Leaf> mutated = new ArrayList<>(CANONICAL);
            Leaf removed = mutated.remove(i);

            VennReport r = Venn.compare(NATIVE, mutated);

            assertThat(r.gaps()).as("removing %s", removed.path()).extracting(PathResult::path)
                    .containsExactly(SOURCE_OF.get(removed.path()));
            assertThat(r.count(Landing.NEITHER)).as("removing %s", removed.path()).isEqualTo(1);
        }
    }

    @Test
    void changingAnyOneCanonicalValueFlagsItToo() {
        for (int i = 0; i < CANONICAL.size(); i++) {
            List<Leaf> mutated = new ArrayList<>(CANONICAL);
            Leaf original = mutated.get(i);
            Object changed = original.value() instanceof String s ? s + "!"
                    : ((java.math.BigDecimal) original.value()).add(java.math.BigDecimal.ONE);
            if (original.value() instanceof String s) {
                // a trailing mark would still contain the original text, so alter the start instead
                changed = "X" + s.substring(1);
            }
            mutated.set(i, new Leaf(original.kind(), original.path(), changed, original.location(), false));

            VennReport r = Venn.compare(NATIVE, mutated);

            assertThat(r.gaps()).as("changing %s", original.path()).extracting(PathResult::path)
                    .containsExactly(SOURCE_OF.get(original.path()));
        }
    }

    @Test
    void anEmptyCanonicalSideLosesEveryMatchableNativeValue() {
        VennReport r = Venn.compare(NATIVE, List.of());

        assertThat(r.count(Landing.NEITHER)).isEqualTo(NATIVE.size() - r.count(Landing.LOW_INFORMATION));
        assertThat(r.count(Landing.LANDED)).isZero();
    }

    @Test
    void aSideComparedWithItselfLosesNothing() {
        List<Leaf> same = NATIVE.stream().map(l -> new Leaf("copy", l.path(), l.value(), l.location(), false)).toList();

        assertThat(Venn.compare(NATIVE, same).gaps()).isEmpty();
    }

    @Test
    void movingAValueIntoAKeptWholeEventIsNotALoss() {
        List<Leaf> mutated = new ArrayList<>(CANONICAL);
        Leaf text = mutated.remove(0);
        mutated.add(new Leaf("vendor_event", "payload.message.content[].text", text.value(), "c:9", true));

        VennReport r = Venn.compare(NATIVE, mutated);

        assertThat(r.gaps()).isEmpty();
        assertThat(r.count(Landing.KEPT_WHOLE)).isEqualTo(1);
    }
}
