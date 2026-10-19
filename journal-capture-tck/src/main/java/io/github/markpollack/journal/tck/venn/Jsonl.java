package io.github.markpollack.journal.tck.venn;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Reads a JSON Lines file into leaves. A line that is not valid JSON is an error, never skipped. */
public final class Jsonl {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private Jsonl() {
    }

    /**
     * @param file the file to read
     * @param kindField the field naming each record's kind: {@code type} in a vendor file, {@code @type} in a journal file
     * @param keptWholeKind records of this kind are marked kept whole (for example {@code vendor_event}), or {@code null}
     */
    public static List<Leaf> read(Path file, String kindField, String keptWholeKind) throws IOException {
        List<Leaf> leaves = new ArrayList<>();
        int number = 0;
        for (String line : Files.readAllLines(file)) {
            number++;
            if (line.isBlank()) {
                continue;
            }
            JsonNode record;
            try {
                record = MAPPER.readTree(line);
            }
            catch (JsonProcessingException e) {
                throw new IOException("Invalid JSON at " + file + ":" + number, e);
            }
            String kind = record.path(kindField).asText("record");
            leaves.addAll(Flattener.flatten(record, kind, file.getFileName() + ":" + number,
                    keptWholeKind != null && keptWholeKind.equals(kind)));
        }
        return leaves;
    }
}
