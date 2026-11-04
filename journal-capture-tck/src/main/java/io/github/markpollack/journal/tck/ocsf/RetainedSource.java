package io.github.markpollack.journal.tck.ocsf;

import io.github.markpollack.journal.tck.verify.Json;
import io.github.markpollack.journal.tck.verify.NeutralJson;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import static io.github.markpollack.journal.tck.ocsf.TerminalReading.*;

/** Independent byte boundaries of a frozen retained-generation original. */
final class RetainedSource {
    private final String name;
    private final byte[] bytes;
    private final Map<Long, Long> records = new LinkedHashMap<>();

    RetainedSource(Json declaration, Path base) throws IOException {
        name = string(declaration, "source");
        Path relative = Path.of(string(declaration, "path"));
        require(!relative.isAbsolute() && relative.normalize().equals(relative) && !relative.startsWith(".."), "unsafe retained path");
        Path path = base.resolve(relative);
        require(path.toRealPath().equals(path.toAbsolutePath().normalize()), "symlink retained source");
        bytes = Files.readAllBytes(path);
        require(number(declaration, "size") == bytes.length && number(declaration, "observed") == bytes.length
                && number(declaration, "dropped") == 0, "retained extent/drop mismatch");
        expect(declaration, "sha256", NativeArtifact.sha256(bytes));
        string(declaration, "origin");
        frame();
    }

    private void frame() {
        if (name.equals("STDERR")) return;
        long start = 0;
        for (int index = 0; index < bytes.length; index++) {
            if (bytes[index] == '\n') { records.put(start, (long) index + 1); start = index + 1L; }
        }
        require(start == bytes.length, "retained JSONL has incomplete tail");
    }

    byte[] extent(Json locator) {
        fields(locator, "source start end sha256");
        expect(locator, "source", name);
        long start = number(locator, "start"), end = number(locator, "end");
        require(start >= 0 && start < end && end <= bytes.length, "invalid retained locator extent");
        if (!name.equals("STDERR")) require(Long.valueOf(end).equals(records.get(start)), "locator is not one whole record");
        byte[] selected = Arrays.copyOfRange(bytes, (int) start, (int) end);
        expect(locator, "sha256", NativeArtifact.sha256(selected));
        return selected;
    }

    Json record(Json locator) { return NeutralJson.parse(new String(extent(locator), StandardCharsets.UTF_8)); }
    long size() { return bytes.length; }
    String sha256() { return NativeArtifact.sha256(bytes); }

    long prefix(Json observation) {
        fields(observation, "bytes sha256");
        long count = number(observation, "bytes");
        require(count >= 0 && count <= bytes.length, "native prefix outside original");
        expect(observation, "sha256", NativeArtifact.sha256(Arrays.copyOf(bytes, (int) count)));
        require(count == 0 || records.containsValue(count), "native prefix ends inside record");
        return count;
    }

    static void require(boolean value, String reason) { if (!value) throw new IllegalArgumentException(reason); }
}
