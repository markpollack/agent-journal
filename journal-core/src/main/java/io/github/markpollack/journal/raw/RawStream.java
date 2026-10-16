package io.github.markpollack.journal.raw;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One phase's verbatim stream file: the vendor's live messages, one per line, exactly as received,
 * written as they arrive. Obtained from {@link RawArtifacts#openStream}.
 *
 * <p>Each call to {@link #append} writes the line, a line feed, and flushes, so a crash leaves
 * every whole line that was appended. Lines are written as UTF-8 and never parsed, so a line that
 * is not valid JSON (a malformed or truncated message) is kept like any other. The returned
 * sequence number (1 for the first line) is the stable reference that normalized trace lines
 * carry back to the message they came from.
 *
 * <p>Some adapters cannot obtain every message verbatim (the Claude SDK hands over the original
 * text of ordinary messages only). {@link #noteUnavailable} counts those messages by type in the
 * manifest, so the stream's completeness is stated rather than assumed.
 */
public final class RawStream implements AutoCloseable {

    private final RawArtifacts owner;

    private final Map<String, Object> entry;

    private final OutputStream out;

    private final MessageDigest digest = RawArtifacts.sha256Digest();

    private final Map<String, Long> unavailable = new LinkedHashMap<>();

    private long lines;

    private long bytes;

    private long multiLine;

    private boolean closed;

    RawStream(RawArtifacts owner, Path file, Map<String, Object> entry) throws IOException {
        this.owner = owner;
        this.entry = entry;
        this.out = Files.newOutputStream(file);
    }

    /**
     * Writes one message line.
     *
     * @param line the message exactly as the vendor sent it, without its line terminator
     * @return the line's sequence number, starting at 1
     * @throws IOException if the line cannot be written
     */
    public synchronized long append(String line) throws IOException {
        if (closed) {
            throw new IllegalStateException("stream is closed");
        }
        byte[] data = line.getBytes(StandardCharsets.UTF_8);
        out.write(data);
        out.write('\n');
        out.flush();
        digest.update(data);
        digest.update((byte) '\n');
        bytes += data.length + 1L;
        if (line.indexOf('\n') >= 0 || line.indexOf('\r') >= 0) {
            multiLine++;
        }
        return ++lines;
    }

    /**
     * Writes every remaining line of a reader to this stream, in order, and returns when the
     * reader ends. Use it to keep a CLI's live output while the process runs: read the process's
     * standard output with it (on its own thread if something else must run meanwhile). The reader
     * is not closed. Line terminators are written as a single line feed.
     *
     * @param reader the vendor's live output
     * @return the number of lines written
     * @throws IOException if reading or writing fails; the lines before the failure are kept
     */
    public long copyFrom(java.io.BufferedReader reader) throws IOException {
        long n = 0;
        String line;
        while ((line = reader.readLine()) != null) {
            append(line);
            n++;
        }
        return n;
    }

    /**
     * Counts a message that the adapter received but could not keep verbatim, by message type.
     * The count appears in the manifest as {@code unavailable}.
     *
     * @param messageType the vendor's type for the message, such as {@code "rate_limit_event"}
     */
    public synchronized void noteUnavailable(String messageType) {
        unavailable.merge(messageType, 1L, Long::sum);
    }

    /** Returns the number of lines written so far. */
    public synchronized long lines() {
        return lines;
    }

    @Override
    public void close() throws IOException {
        Map<String, Object> finished = new LinkedHashMap<>();
        IOException failure = null;
        synchronized (this) {
            if (closed) {
                return;
            }
            closed = true;
            try {
                out.close();
            }
            catch (IOException ex) {
                failure = ex;
            }
            finished.put("lines", lines);
            finished.put("bytes", bytes);
            finished.put("sha256", RawArtifacts.hex(digest));
            if (multiLine > 0) {
                finished.put("linesWithEmbeddedLineBreaks", multiLine);
            }
            if (!unavailable.isEmpty()) {
                finished.put("unavailable", new LinkedHashMap<>(unavailable));
            }
        }
        // the owner's lock is taken after this stream's lock is released, never inside it
        owner.streamFinished(entry, finished, failure == null);
        if (failure != null) {
            throw failure;
        }
    }

}
