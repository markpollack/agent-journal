package io.github.markpollack.journal.tck.ocsf;

import io.github.markpollack.journal.tck.verify.Json;
import io.github.markpollack.journal.tck.verify.NeutralJson;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Scoped reconstruction of one native artifact from a core's whole-record events: parsed records are re-serialized
 * from {@code data} (equal in structure and value, with number spellings kept, but not byte-exact: whitespace and
 * key order are not retained), opaque records are their exact bytes, and the recorded line ending follows each
 * record. Records without a whole-record event are gaps the caller reports; nothing is invented to fill them.
 */
public final class Reconstruction {

    /** @param lines record index to reconstructed bytes without terminator; {@code endings} record index to LF/CRLF/none */
    public record Result(Map<Integer, byte[]> lines, Map<Integer, String> endings) {
        public byte[] bytes() {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            for (Map.Entry<Integer, byte[]> e : lines.entrySet()) {
                out.writeBytes(e.getValue());
                String ending = endings.get(e.getKey());
                if ("CRLF".equals(ending)) {
                    out.writeBytes(new byte[] {'\r', '\n'});
                }
                else if (!"none".equals(ending)) {
                    out.write('\n');
                }
            }
            return out.toByteArray();
        }
    }

    private Reconstruction() {
    }

    public static Result reconstruct(List<Event> eventsOfArtifact, int recordCount) {
        Map<Integer, byte[]> lines = new TreeMap<>();
        Map<Integer, String> endings = new TreeMap<>();
        for (Event e : eventsOfArtifact) {
            if (!e.wholeRecord() || e.recordIndex() == null) {
                continue;
            }
            byte[] line;
            if ("source_record".equals(e.className) && e.activityId != null && e.activityId == 2) {
                String b64 = e.text("raw_bytes_b64");
                String raw = e.text("raw_data");
                line = b64 != null ? Base64.getDecoder().decode(b64) : raw == null ? null : raw.getBytes(StandardCharsets.UTF_8);
            }
            else {
                Json data = e.get("data");
                line = data == null ? null : NeutralJson.write(data).getBytes(StandardCharsets.UTF_8);
            }
            if (line == null) {
                continue;
            }
            lines.put(e.recordIndex(), line);
            String ending = e.text("source_ref", "line_ending");
            endings.put(e.recordIndex(), ending == null ? "LF" : ending);
        }
        return new Result(lines, endings);
    }
}
