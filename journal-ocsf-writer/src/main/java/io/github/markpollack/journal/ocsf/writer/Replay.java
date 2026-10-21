package io.github.markpollack.journal.ocsf.writer;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;

/**
 * Replay drivers: feed a recorded native artifact through {@link SourceInput} exactly as a live capture would, in
 * byte chunks of a chosen size (crossing record boundaries) or as one complete record per call. Arrival times come
 * from the clock the caller supplies; a replay never carries the original capture's arrival times, which the
 * fixtures did not record. Nothing here maps or writes anything itself.
 */
public final class Replay {

    private Replay() {
    }

    /** Feeds the file's bytes in chunks of {@code chunkSize} and ends the source with EOF. */
    public static int feedBytes(SourceInput in, Path file, int chunkSize, Clock clock) {
        byte[] data = read(file);
        return feedBytes(in, data, chunkSize, clock, data.length, true);
    }

    /**
     * Feeds the first {@code limit} bytes in chunks; ends with EOF when {@code end} is true (so a limit inside a
     * record leaves an incomplete tail record), otherwise leaves the source open for more.
     */
    public static int feedBytes(SourceInput in, byte[] data, int chunkSize, Clock clock, int limit, boolean end) {
        int offset = 0;
        int chunks = 0;
        while (offset < limit) {
            int n = Math.min(chunkSize, limit - offset);
            in.bytes(data, offset, n, clock.instant());
            offset += n;
            chunks++;
        }
        if (end) {
            in.end(SourceInput.End.EOF, clock.instant());
        }
        return chunks;
    }

    /** Feeds the file one complete record per call (the capture-facing record form) and ends with EOF. */
    public static int feedRecords(SourceInput in, Path file, Clock clock) {
        List<LineFramer.Framed> lines = lines(read(file));
        for (LineFramer.Framed f : lines) {
            in.record(f.raw(), f.ending(), clock.instant());
        }
        in.end(SourceInput.End.EOF, clock.instant());
        return lines.size();
    }

    /** The first {@code count} records of the file, as framed lines (the prefix a live capture would have seen). */
    public static List<LineFramer.Framed> prefix(Path file, int count) {
        List<LineFramer.Framed> lines = lines(read(file));
        return lines.subList(0, Math.min(count, lines.size()));
    }

    /** The bytes of the first {@code count} records, terminators included. */
    public static byte[] prefixBytes(Path file, int count) {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        for (LineFramer.Framed f : prefix(file, count)) {
            out.writeBytes(f.raw());
            if ("CRLF".equals(f.ending())) {
                out.writeBytes(new byte[] {'\r', '\n'});
            }
            else if ("LF".equals(f.ending())) {
                out.write('\n');
            }
        }
        return out.toByteArray();
    }

    static List<LineFramer.Framed> lines(byte[] data) {
        LineFramer framer = new LineFramer();
        List<LineFramer.Framed> out = new ArrayList<>(framer.push(data));
        if (framer.tail().length > 0) {
            out.add(framer.end());
        }
        return out;
    }

    static byte[] read(Path file) {
        try {
            return Files.readAllBytes(file);
        }
        catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
