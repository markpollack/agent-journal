package io.github.markpollack.journal.ocsf.writer;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Byte framing separate from record interpretation, and the lexically exact JSON tree. */
class FramingAndJsonTest {

    @Test
    void chunksEndingMidRecordAndMidCrlfAreReassembledExactly() {
        byte[] data = "{\"a\":1}\r\n{\"b\":2}\n\n{\"c\":3}".getBytes(StandardCharsets.UTF_8);
        for (int chunk = 1; chunk <= data.length; chunk++) {
            LineFramer framer = new LineFramer();
            List<LineFramer.Framed> out = new ArrayList<>();
            for (int i = 0; i < data.length; i += chunk) {
                out.addAll(framer.push(data, i, Math.min(chunk, data.length - i)));
            }
            assertThat(out).as("chunk size " + chunk).hasSize(3);
            assertThat(new String(out.get(0).raw(), StandardCharsets.UTF_8)).isEqualTo("{\"a\":1}");
            assertThat(out.get(0).ending()).isEqualTo("CRLF");
            assertThat(out.get(1).ending()).isEqualTo("LF");
            assertThat(out.get(2).raw()).isEmpty();
            assertThat(new String(framer.tail(), StandardCharsets.UTF_8)).isEqualTo("{\"c\":3}");
            LineFramer.Framed tail = framer.end();
            assertThat(tail.ending()).isEqualTo("none");
            assertThat(framer.tail()).isEmpty();
        }
    }

    @Test
    void recordsAreClassifiedWithoutLosingBytes() {
        Instant t = Instant.EPOCH;
        assertThat(NativeRecord.classify(1, "{\"a\":1}".getBytes(StandardCharsets.UTF_8), "LF", t).status()).isEqualTo(NativeRecord.Status.PARSED);
        assertThat(NativeRecord.classify(2, new byte[0], "LF", t).status()).isEqualTo(NativeRecord.Status.BLANK);
        NativeRecord dup = NativeRecord.classify(3, "{\"a\":1,\"a\":2}".getBytes(StandardCharsets.UTF_8), "LF", t);
        assertThat(dup.status()).isEqualTo(NativeRecord.Status.MALFORMED);
        assertThat(dup.reason()).contains("Duplicate");
        NativeRecord bad = NativeRecord.classify(4, new byte[] {'{', (byte) 0xff, '}'}, "LF", t);
        assertThat(bad.status()).isEqualTo(NativeRecord.Status.UNDECODABLE);
        assertThat(bad.raw()).containsExactly('{', (byte) 0xff, '}');
        NativeRecord cut = NativeRecord.classify(5, "{\"a\":[1,2".getBytes(StandardCharsets.UTF_8), "none", t);
        assertThat(cut.status()).isEqualTo(NativeRecord.Status.INCOMPLETE);
        NativeRecord lastWhole = NativeRecord.classify(6, "{\"a\":[1,2]}".getBytes(StandardCharsets.UTF_8), "none", t);
        assertThat(lastWhole.status()).isEqualTo(NativeRecord.Status.PARSED);
        assertThat(NativeRecord.classify(7, "[1]".getBytes(StandardCharsets.UTF_8), "LF", t).status()).isEqualTo(NativeRecord.Status.MALFORMED);
    }

    @Test
    void numbersKeepTheirSpellingAndObjectsKeepTheirOrder() {
        String text = "{\"z\":1.0,\"a\":1e5,\"big\":9007199254740993,\"d\":2.50,\"n\":null,\"e\":{},\"l\":[],\"f\":false,\"s\":\"\"}";
        Jv v = Jv.parse(text);
        assertThat(Jv.write(v)).isEqualTo(text);
        assertThat(((Jv.Num) v.member("big")).value().toPlainString()).isEqualTo("9007199254740993");
        assertThat(((Jv.Num) v.member("z")).integral()).isTrue();
        assertThat(((Jv.Num) v.member("d")).integral()).isFalse();
        assertThat(Jv.write(Jv.copy(v))).isEqualTo(text);
        assertThatThrownBy(() -> Jv.parse("{\"a\":1} {\"b\":2}")).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("trailing");
        assertThatThrownBy(() -> Jv.parse("")).isInstanceOf(IllegalArgumentException.class);
        assertThat(Jv.write(Jv.parse("\"tab\\there \\u0001 \\\"q\\\" \\\\ \\n\""))).isEqualTo("\"tab\\there \\u0001 \\\"q\\\" \\\\ \\n\"");
    }
}
