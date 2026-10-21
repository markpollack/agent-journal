package io.github.markpollack.journal.ocsf.writer;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.Instant;

/**
 * One native record (line) as the writer received it, classified before any interpretation: its 1-based occurrence
 * index within the artifact, exact bytes, line ending, parse status, decoded text and parsed value when it is one
 * JSON object under strict parsing, and the arrival time the capture reported. Nothing is dropped: a blank,
 * undecodable, malformed or cut-off line is a record like any other.
 */
public record NativeRecord(int index, byte[] raw, String ending, Status status, String text, Jv json, String reason,
        Instant arrival) {

    public enum Status {
        /** One JSON object. */
        PARSED(1, "Parsed"),
        /** Decodable text that is not one JSON object (syntax, trailing content, duplicate keys, not an object). */
        MALFORMED(2, "Malformed JSON"),
        /** Not valid UTF-8. */
        UNDECODABLE(3, "Undecodable bytes"),
        /** An empty line. */
        BLANK(4, "Blank"),
        /** The source ended without a terminator while the bytes were not yet one JSON object. */
        INCOMPLETE(5, "Incomplete at source end");

        public final int id;
        public final String caption;

        Status(int id, String caption) {
            this.id = id;
            this.caption = caption;
        }
    }

    public boolean parsed() {
        return status == Status.PARSED;
    }

    /**
     * Classifies a framed line. An unterminated line ({@code ending} {@code none}) that is not one JSON object is
     * {@link Status#INCOMPLETE} rather than malformed: the source ended before the record did.
     */
    static NativeRecord classify(int index, byte[] raw, String ending, Instant arrival) {
        if (raw.length == 0) {
            return new NativeRecord(index, raw, ending, Status.BLANK, "", null, "blank line", arrival);
        }
        String text;
        try {
            text = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(raw)).toString();
        }
        catch (CharacterCodingException e) {
            return new NativeRecord(index, raw, ending, "none".equals(ending) ? Status.INCOMPLETE : Status.UNDECODABLE, null, null,
                    "not UTF-8: " + e.getMessage(), arrival);
        }
        try {
            Jv json = Jv.parse(text);
            if (!(json instanceof Jv.Obj)) {
                return new NativeRecord(index, raw, ending, "none".equals(ending) ? Status.INCOMPLETE : Status.MALFORMED, text, null,
                        "not a JSON object", arrival);
            }
            return new NativeRecord(index, raw, ending, Status.PARSED, text, json, null, arrival);
        }
        catch (IllegalArgumentException e) {
            return new NativeRecord(index, raw, ending, "none".equals(ending) ? Status.INCOMPLETE : Status.MALFORMED, text, null,
                    "not one JSON object: " + e.getMessage(), arrival);
        }
    }
}
