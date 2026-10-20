package io.github.markpollack.journal.tck.verify;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The neutral JSON model keeps exact numbers and their spelling, and its difference finder reports value,
 * representation, kind, null-versus-value, missing and extra separately (JV-2 numeric and structural cases).
 */
class NeutralJsonTest {

    private static List<Difference> diff(String expected, String actual) {
        return Json.differences(NeutralJson.parse(expected), NeutralJson.parse(actual));
    }

    @Test
    void integerAndIntegralFloatAreTheSameValueWithDifferentRepresentation() {
        List<Difference> d = diff("{\"limit\":1}", "{\"limit\":1.0}");
        assertThat(d).extracting(Difference::kind).containsExactly(Difference.Kind.REPRESENTATION);
        assertThat(d.get(0).path()).isEqualTo("$.limit");
        assertThat(Json.sameValue(NeutralJson.parse("1"), NeutralJson.parse("1.0"))).isTrue();
        assertThat(diff("1", "1e0")).extracting(Difference::kind).containsExactly(Difference.Kind.REPRESENTATION);
        assertThat(diff("2.50", "2.5")).extracting(Difference::kind).containsExactly(Difference.Kind.REPRESENTATION);
        assertThat(diff("2.5", "2.5")).isEmpty();
    }

    @Test
    void changedNumericValueIsAValueDifferenceNotARepresentationOne() {
        assertThat(diff("{\"limit\":1}", "{\"limit\":2}")).extracting(Difference::kind).containsExactly(Difference.Kind.VALUE);
        assertThat(diff("1.0", "1.5")).extracting(Difference::kind).containsExactly(Difference.Kind.VALUE);
    }

    @Test
    void integersBeyondBinary64ExactRangeAreComparedExactly() {
        // 2^53 + 1 is not representable as a double; a double-based comparison would call these equal
        assertThat(diff("9007199254740993", "9007199254740992")).extracting(Difference::kind).containsExactly(Difference.Kind.VALUE);
        assertThat(diff("9007199254740993", "9007199254740993")).isEmpty();
        assertThat(diff("123456789012345678901234567890", "123456789012345678901234567890")).isEmpty();
        Json.JNumber big = (Json.JNumber) NeutralJson.parse("123456789012345678901234567890");
        assertThat(big.value()).isEqualTo(new BigDecimal("123456789012345678901234567890"));
        assertThat(big.text()).isEqualTo("123456789012345678901234567890");
    }

    @Test
    void highPrecisionDecimalsKeepEveryDigit() {
        String precise = "0.1000000000000000055511151231257827021181583404541015625";
        assertThat(diff(precise, precise)).isEmpty();
        assertThat(diff(precise, "0.1")).extracting(Difference::kind).containsExactly(Difference.Kind.VALUE);
        assertThat(diff("0.30000000000000004", "0.3")).extracting(Difference::kind).containsExactly(Difference.Kind.VALUE);
    }

    @Test
    void booleanIsNeverANumberAndStringIsNeverANumber() {
        assertThat(diff("true", "1")).extracting(Difference::kind).containsExactly(Difference.Kind.KIND);
        assertThat(diff("1", "true")).extracting(Difference::kind).containsExactly(Difference.Kind.KIND);
        assertThat(diff("\"1\"", "1")).extracting(Difference::kind).containsExactly(Difference.Kind.KIND);
        assertThat(diff("true", "false")).extracting(Difference::kind).containsExactly(Difference.Kind.VALUE);
    }

    @Test
    void nullIsNotMissingAndEmptyContainersKeepTheirKind() {
        assertThat(diff("{\"a\":null}", "{}")).extracting(Difference::kind).containsExactly(Difference.Kind.MISSING);
        assertThat(diff("{}", "{\"a\":null}")).extracting(Difference::kind).containsExactly(Difference.Kind.EXTRA);
        assertThat(diff("{\"a\":null}", "{\"a\":1}")).extracting(Difference::kind).containsExactly(Difference.Kind.NULL_VS_VALUE);
        assertThat(diff("{\"a\":1}", "{\"a\":null}")).extracting(Difference::kind).containsExactly(Difference.Kind.NULL_VS_VALUE);
        assertThat(diff("[]", "{}")).extracting(Difference::kind).containsExactly(Difference.Kind.KIND);
        assertThat(diff("[]", "[]")).isEmpty();
        assertThat(diff("{}", "{}")).isEmpty();
        assertThat(diff("{\"a\":[]}", "{\"a\":[null]}")).extracting(Difference::kind).containsExactly(Difference.Kind.EXTRA);
    }

    @Test
    void structureDifferencesNameThePath() {
        List<Difference> d = diff("[{\"type\":\"text\",\"text\":\"a\"},{\"type\":\"text\",\"text\":\"b\"}]",
                "[{\"type\":\"text\",\"text\":\"a\"},{\"type\":\"text\",\"text\":\"c\"}]");
        assertThat(d).hasSize(1);
        assertThat(d.get(0).path()).isEqualTo("$[1].text");
        assertThat(d.get(0).kind()).isEqualTo(Difference.Kind.VALUE);
        assertThat(d.get(0).detail()).doesNotContain("c"); // never content
        assertThat(diff("[1,2]", "[1]")).extracting(Difference::kind).containsExactly(Difference.Kind.MISSING);
        assertThat(diff("[1]", "[1,2]")).extracting(Difference::kind).containsExactly(Difference.Kind.EXTRA);
        assertThat(diff("{\"a\":1}", "{\"a\":1,\"unknown\":true}")).extracting(Difference::kind).containsExactly(Difference.Kind.EXTRA);
        assertThat(diff("{\"a\":1,\"unknown\":true}", "{\"a\":1}")).extracting(Difference::kind).containsExactly(Difference.Kind.MISSING);
    }

    @Test
    void memberOrderAndWhitespaceAreNotSemantic() {
        assertThat(diff("{\"a\":1,\"b\":2}", "{ \"b\" : 2 , \"a\" : 1 }")).isEmpty();
        assertThat(diff("\"caf\\u00e9\"", "\"café\"")).isEmpty(); // escape spelling is not semantic
    }

    @Test
    void duplicateKeysAndTrailingContentAreRefusedNotCollapsed() {
        assertThatThrownBy(() -> NeutralJson.parse("{\"a\":1,\"a\":2}")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> NeutralJson.parse("{} {}")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> NeutralJson.parse("")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void writingKeepsNumberSpellingAndRoundTrips() {
        String text = "{\"n\":1.0,\"m\":2.50,\"big\":9007199254740993,\"s\":\"a\\nb\\\"c\",\"t\":true,\"z\":null,\"l\":[1,{}]}";
        Json parsed = NeutralJson.parse(text);
        assertThat(NeutralJson.write(parsed)).isEqualTo(text);
        assertThat(Json.differences(parsed, NeutralJson.parse(NeutralJson.write(parsed)))).isEmpty();
    }
}
