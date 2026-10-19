package io.github.markpollack.journal.tck.venn;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Reads a JSON Lines file into leaves. A line that is not valid JSON is an error, never skipped.
 *
 * <p>A journal {@code custom} event named {@code vendor_event} is a vendor record kept whole, so its leaves are marked
 * kept whole, and a string attribute that itself holds a JSON document (Claude's {@code rawJson}, the line as the SDK
 * gave it) is read as that document, under the same path, so its values can be matched one by one rather than as one
 * long string.
 */
public final class Jsonl {

    /** The journal event name of a vendor record kept whole. */
    public static final String VENDOR_EVENT = "vendor_event";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private Jsonl() {
    }

    /** Replaces a string {@code attributes.rawJson} that parses as JSON with the parsed document. */
    private static JsonNode expandRawJson(JsonNode record) {
        JsonNode attributes = record.path("attributes");
        JsonNode raw = attributes.path("rawJson");
        if (!attributes.isObject() || !raw.isTextual()) {
            return record;
        }
        try {
            JsonNode parsed = MAPPER.readTree(raw.asText());
            if (parsed == null || !parsed.isContainerNode()) {
                return record;
            }
            com.fasterxml.jackson.databind.node.ObjectNode copy = record.deepCopy();
            ((com.fasterxml.jackson.databind.node.ObjectNode) copy.get("attributes")).set("rawJson", parsed);
            return copy;
        }
        catch (JsonProcessingException notJson) {
            return record;
        }
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
            boolean vendorEvent = "custom".equals(kind) && VENDOR_EVENT.equals(record.path("name").asText(null));
            if (vendorEvent) {
                kind = VENDOR_EVENT;
                record = expandRawJson(record);
            }
            boolean keptWhole = vendorEvent || (keptWholeKind != null && keptWholeKind.equals(kind));
            leaves.addAll(Flattener.flatten(record, kind, file.getFileName() + ":" + number, keptWhole));
        }
        return leaves;
    }
}
