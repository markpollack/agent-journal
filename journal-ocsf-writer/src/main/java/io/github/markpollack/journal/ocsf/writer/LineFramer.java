package io.github.markpollack.journal.ocsf.writer;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Transport framing, separate from record interpretation: arbitrary byte chunks arrive (a chunk may end in the
 * middle of a record, or in the middle of a CRLF pair), complete lines leave. A line is the bytes up to an LF;
 * a trailing CR before the LF is removed and recorded as a CRLF ending. Whatever follows the last LF stays
 * buffered as the open tail until more bytes arrive or the source ends, when the caller takes it as the final
 * record with ending {@code none} (complete if it parses, incomplete otherwise).
 */
final class LineFramer {

    /** One framed line: its exact bytes without the terminator and the terminator seen ({@code LF} or {@code CRLF}). */
    record Framed(byte[] raw, String ending) {
    }

    private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
    private long bytesSeen;

    /** Feeds a chunk and returns the complete lines it completed, in order. */
    List<Framed> push(byte[] chunk, int offset, int length) {
        bytesSeen += length;
        List<Framed> out = new ArrayList<>();
        int start = offset;
        int end = offset + length;
        for (int i = offset; i < end; i++) {
            if (chunk[i] == '\n') {
                buffer.write(chunk, start, i - start);
                out.add(take());
                start = i + 1;
            }
        }
        buffer.write(chunk, start, end - start);
        return out;
    }

    List<Framed> push(byte[] chunk) {
        return push(chunk, 0, chunk.length);
    }

    private Framed take() {
        byte[] line = buffer.toByteArray();
        buffer.reset();
        if (line.length > 0 && line[line.length - 1] == '\r') {
            return new Framed(Arrays.copyOf(line, line.length - 1), "CRLF");
        }
        return new Framed(line, "LF");
    }

    /** The bytes after the last terminator, so far (empty when the last chunk ended on an LF). */
    byte[] tail() {
        return buffer.toByteArray();
    }

    /** Takes the open tail as the final, unterminated line and leaves the framer empty. */
    Framed end() {
        byte[] line = buffer.toByteArray();
        buffer.reset();
        return new Framed(line, "none");
    }

    long bytesSeen() {
        return bytesSeen;
    }
}
