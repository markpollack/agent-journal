package io.github.markpollack.journal.tck.ocsf;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/** Bytes are acquired before parsing; malformed, duplicate-key, undecodable and blank lines stay in the inventory. */
class NativeArtifactTest {

    @Test
    void everyLineStaysInTheInventoryWithItsFramingAndStatus() {
        byte[] good = "{\"a\":1}\n".getBytes(StandardCharsets.UTF_8);
        byte[] dup = "{\"a\":1,\"a\":2}\r\n".getBytes(StandardCharsets.UTF_8);
        byte[] bad = new byte[] {'{', '"', 'x', '"', ':', '"', (byte) 0xff, (byte) 0xfe, '"', '}', '\n'};
        byte[] blank = "\n".getBytes(StandardCharsets.UTF_8);
        byte[] array = "[1,2]\n".getBytes(StandardCharsets.UTF_8);
        byte[] last = "{\"b\":true}".getBytes(StandardCharsets.UTF_8);
        byte[] all = concat(good, dup, bad, blank, array, last);
        NativeArtifact a = NativeArtifact.ofBytes(Path.of("synthetic"), all);
        assertThat(a.records()).hasSize(6);
        assertThat(a.record(1).status()).isEqualTo(NativeArtifact.Status.PARSED);
        assertThat(a.record(1).lineEnding()).isEqualTo("LF");
        assertThat(a.record(2).status()).isEqualTo(NativeArtifact.Status.MALFORMED);
        assertThat(a.record(2).reason()).contains("uplicate");
        assertThat(a.record(2).lineEnding()).isEqualTo("CRLF");
        assertThat(a.record(2).raw()).hasSize(dup.length - 2);
        assertThat(a.record(3).status()).isEqualTo(NativeArtifact.Status.UNDECODABLE);
        assertThat(a.record(3).text()).isNull();
        assertThat(a.record(3).raw()).hasSize(bad.length - 1);
        assertThat(a.record(4).status()).isEqualTo(NativeArtifact.Status.BLANK);
        assertThat(a.record(5).status()).isEqualTo(NativeArtifact.Status.MALFORMED);
        assertThat(a.record(5).reason()).contains("not a JSON object");
        assertThat(a.record(6).status()).isEqualTo(NativeArtifact.Status.PARSED);
        assertThat(a.record(6).lineEnding()).isEqualTo("none");
        assertThat(a.trailingNewline()).isFalse();
        assertThat(a.opaqueCount()).isEqualTo(4);
        assertThat(a.sha256()).hasSize(64);
    }

    @Test
    void numberSpellingAndValueSurviveParsing() {
        NativeArtifact a = NativeArtifact.ofBytes(Path.of("n"), "{\"n\":1.0,\"big\":9007199254740993,\"d\":0.1000000000000000055511151231257827}\n".getBytes(StandardCharsets.UTF_8));
        var n = (io.github.markpollack.journal.tck.verify.Json.JNumber) a.record(1).json().member("n");
        assertThat(n.text()).isEqualTo("1.0");
        assertThat(n.value().compareTo(java.math.BigDecimal.ONE)).isZero();
        var big = (io.github.markpollack.journal.tck.verify.Json.JNumber) a.record(1).json().member("big");
        assertThat(big.value().toPlainString()).isEqualTo("9007199254740993");
        assertThat(((io.github.markpollack.journal.tck.verify.Json.JNumber) a.record(1).json().member("d")).text()).isEqualTo("0.1000000000000000055511151231257827");
    }

    private static byte[] concat(byte[]... parts) {
        int n = 0;
        for (byte[] p : parts) {
            n += p.length;
        }
        byte[] out = new byte[n];
        int i = 0;
        for (byte[] p : parts) {
            System.arraycopy(p, 0, out, i, p.length);
            i += p.length;
        }
        return out;
    }
}
