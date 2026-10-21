package io.github.markpollack.journal.tck.ocsf;

import io.github.markpollack.journal.tck.verify.Json;
import io.github.markpollack.journal.tck.verify.NeutralJson;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

/**
 * A native source artifact read as bytes first. Lines are split on LF, a trailing CR is recorded as a CRLF ending,
 * and every line is kept: a line that is not UTF-8, not one JSON object under strict duplicate-key parsing, or blank
 * stays in the inventory as an opaque record with its exact bytes. A strict parser therefore never ends the inventory
 * early, and the oracle can judge structure/value, representation and bytes separately.
 */
public final class NativeArtifact {

    public enum Status {
        PARSED, MALFORMED, UNDECODABLE, BLANK
    }

    /**
     * One record (line) of the artifact.
     *
     * @param index 1-based occurrence index
     * @param raw the exact bytes without the line terminator
     * @param lineEnding {@code LF}, {@code CRLF} or {@code none} (last line without terminator)
     * @param status how far the record could be interpreted
     * @param text the decoded text, {@code null} when undecodable
     * @param json the parsed object, {@code null} unless {@link Status#PARSED}
     * @param reason the parse failure, {@code null} when parsed
     */
    @SuppressWarnings({"ArrayRecordComponent", "AvoidCommonTypeNames"}) // raw bytes are the value and records are compared by index, never by equals; the name is the TCK's public type (14 uses), kept
    public record Record(int index, byte[] raw, String lineEnding, Status status, String text, Json json, String reason) {
        public boolean parsed() {
            return status == Status.PARSED;
        }
    }

    private final Path path;
    private final byte[] bytes;
    private final String sha256;
    private final List<Record> records;
    private final boolean trailingNewline;

    private NativeArtifact(Path path, byte[] bytes) {
        this.path = path;
        this.bytes = bytes;
        this.sha256 = sha256(bytes);
        this.records = split(bytes);
        this.trailingNewline = bytes.length > 0 && bytes[bytes.length - 1] == '\n';
    }

    public static NativeArtifact read(Path path) {
        try {
            return new NativeArtifact(path, Files.readAllBytes(path));
        }
        catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static NativeArtifact ofBytes(Path label, byte[] bytes) {
        return new NativeArtifact(label, bytes);
    }

    private static List<Record> split(byte[] data) {
        List<Record> out = new ArrayList<>();
        int i = 0;
        int index = 0;
        while (i < data.length) {
            int j = indexOf(data, (byte) '\n', i);
            byte[] segment;
            String ending;
            if (j < 0) {
                segment = java.util.Arrays.copyOfRange(data, i, data.length);
                ending = "none";
                i = data.length;
            }
            else {
                segment = java.util.Arrays.copyOfRange(data, i, j);
                ending = "LF";
                if (segment.length > 0 && segment[segment.length - 1] == '\r') {
                    segment = java.util.Arrays.copyOf(segment, segment.length - 1);
                    ending = "CRLF";
                }
                i = j + 1;
            }
            out.add(classify(++index, segment, ending));
        }
        return List.copyOf(out);
    }

    private static Record classify(int index, byte[] segment, String ending) {
        if (segment.length == 0) {
            return new Record(index, segment, ending, Status.BLANK, "", null, "blank line");
        }
        String text;
        try {
            text = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(segment)).toString();
        }
        catch (CharacterCodingException e) {
            return new Record(index, segment, ending, Status.UNDECODABLE, null, null, "not UTF-8: " + e.getMessage());
        }
        try {
            Json json = NeutralJson.parse(text);
            if (!(json instanceof Json.JObject)) {
                return new Record(index, segment, ending, Status.MALFORMED, text, null, "not a JSON object");
            }
            return new Record(index, segment, ending, Status.PARSED, text, json, null);
        }
        catch (IllegalArgumentException e) {
            return new Record(index, segment, ending, Status.MALFORMED, text, null, "not one JSON object: " + e.getMessage());
        }
    }

    private static int indexOf(byte[] data, byte b, int from) {
        for (int k = from; k < data.length; k++) {
            if (data[k] == b) {
                return k;
            }
        }
        return -1;
    }

    public static String sha256(byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        }
        catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public Path path() {
        return path;
    }

    public byte[] bytes() {
        return bytes;
    }

    public String sha256() {
        return sha256;
    }

    public List<Record> records() {
        return records;
    }

    /** The record at a 1-based index, or {@code null}. */
    public Record record(int index) {
        return index >= 1 && index <= records.size() ? records.get(index - 1) : null;
    }

    public boolean trailingNewline() {
        return trailingNewline;
    }

    public int opaqueCount() {
        int n = 0;
        for (Record r : records) {
            if (!r.parsed()) {
                n++;
            }
        }
        return n;
    }
}
