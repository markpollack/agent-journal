package io.github.markpollack.journal.tck.venn;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JsonlTest {

    @Test
    void recordsAreReadWithTheirKindAndLine(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("events.jsonl");
        Files.writeString(file, "{\"@type\":\"tool_call\",\"output\":\"result text\"}\n\n{\"@type\":\"vendor_event\",\"payload\":{\"x\":\"kept text\"}}\n");

        List<Leaf> leaves = Jsonl.read(file, "@type", "vendor_event");

        assertThat(leaves).extracting(Leaf::kind).containsExactly("tool_call", "tool_call", "vendor_event", "vendor_event");
        assertThat(leaves).filteredOn(Leaf::keptWhole).extracting(Leaf::path).containsExactly("@type", "payload.x");
        assertThat(leaves.get(1).location()).isEqualTo("events.jsonl:1");
        assertThat(leaves.get(3).location()).isEqualTo("events.jsonl:3");
    }

    @Test
    void aBadLineIsAnErrorWithItsLocationNeverSkipped(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("native.jsonl");
        Files.writeString(file, "{\"type\":\"a\"}\n{not json}\n");

        assertThatThrownBy(() -> Jsonl.read(file, "type", null)).isInstanceOf(IOException.class)
                .hasMessageContaining("native.jsonl:2");
    }

    @Test
    void aRecordWithoutTheKindFieldGetsTheKindRecord(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("x.jsonl");
        Files.writeString(file, "{\"a\":\"some value\"}\n");

        assertThat(Jsonl.read(file, "type", null)).singleElement().satisfies(l -> assertThat(l.kind()).isEqualTo("record"));
    }
}
