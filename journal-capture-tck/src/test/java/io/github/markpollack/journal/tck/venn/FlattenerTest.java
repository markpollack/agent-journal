package io.github.markpollack.journal.tck.venn;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class FlattenerTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static List<Leaf> flatten(String json) throws Exception {
        return Flattener.flatten(MAPPER.readTree(json), "k", "f:1", false);
    }

    @Test
    void nestedObjectsJoinKeysWithDots() throws Exception {
        assertThat(flatten("{\"a\":{\"b\":{\"c\":\"deep value\"}}}")).singleElement()
                .satisfies(l -> assertThat(l.path()).isEqualTo("a.b.c"));
    }

    @Test
    void everyArrayLevelIsWrittenAsBrackets() throws Exception {
        List<Leaf> leaves = flatten("{\"m\":{\"content\":[{\"text\":\"first one\"},{\"text\":\"second one\"}]},\"g\":[[\"x1\",\"x2\"]]}");
        assertThat(leaves).extracting(Leaf::path).containsExactly("m.content[].text", "m.content[].text", "g[][]", "g[][]");
    }

    @Test
    void nullsEmptyStringsAndEmptyContainersCarryNoValueAndAreDropped() throws Exception {
        assertThat(flatten("{\"a\":null,\"b\":[],\"c\":{},\"e\":\"\",\"d\":\"kept value\"}")).extracting(Leaf::path)
                .containsExactly("d");
    }

    @Test
    void numbersAreNormalizedSoOneAndOnePointZeroAreTheSame() throws Exception {
        List<Leaf> leaves = flatten("{\"a\":1,\"b\":1.0,\"c\":1e0,\"d\":100,\"e\":1.50,\"f\":0,\"g\":0.0}");
        assertThat(leaves).extracting(Leaf::value).containsExactly(new BigDecimal("1"), new BigDecimal("1"),
                new BigDecimal("1"), new BigDecimal("1E+2"), new BigDecimal("1.5"), BigDecimal.ZERO, BigDecimal.ZERO);
    }

    @Test
    void booleansAreLeavesAndKeepTheirValue() throws Exception {
        assertThat(flatten("{\"ok\":true,\"bad\":false}")).extracting(Leaf::value).containsExactly(true, false);
    }

    @Test
    void unicodeAndEscapesAreDecodedOnce() throws Exception {
        assertThat(flatten("{\"t\":\"caf\\u00e9 \\\"quoted\\\"\\nline\"}")).singleElement()
                .satisfies(l -> assertThat(l.value()).isEqualTo("café \"quoted\"\nline"));
    }

    @Test
    void aVeryLongStringIsOneLeaf() throws Exception {
        String big = "x".repeat(2_000_000);
        assertThat(flatten("{\"t\":\"" + big + "\"}")).singleElement()
                .satisfies(l -> assertThat(((String) l.value()).length()).isEqualTo(2_000_000));
    }

    @Test
    void locationAndKeptWholeAreCarried() throws Exception {
        List<Leaf> leaves = Flattener.flatten(MAPPER.readTree("{\"a\":\"some value\"}"), "vendor_event", "events.jsonl:7", true);
        assertThat(leaves).singleElement().satisfies(l -> {
            assertThat(l.location()).isEqualTo("events.jsonl:7");
            assertThat(l.keptWhole()).isTrue();
            assertThat(l.kind()).isEqualTo("vendor_event");
        });
    }

    @Test
    void aTopLevelScalarHasAnEmptyPath() throws Exception {
        assertThat(flatten("\"just a string\"")).singleElement().satisfies(l -> assertThat(l.path()).isEmpty());
    }
}
