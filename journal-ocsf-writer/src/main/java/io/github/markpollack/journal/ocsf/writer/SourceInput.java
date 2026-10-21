package io.github.markpollack.journal.ocsf.writer;

import java.time.Instant;

/**
 * The capture-facing input interface: the one entry point through which native observations reach the writer,
 * whether a replay driver feeds recorded bytes or a live capture hands over what it receives. Both forms carry the
 * actual arrival time the caller observed; the writer never substitutes its own clock for a source time.
 *
 * <ul>
 * <li>{@link #bytes} is transport framing: arbitrary chunks, possibly ending mid-record. The writer splits them
 * into lines itself.</li>
 * <li>{@link #record} is for captures that already hand over one complete native line at a time (for example the
 * SDK's wire line per message, or a line reader over a rollout); the terminator the capture observed is stated so
 * the artifact's bytes and framing are reconstructable.</li>
 * <li>{@link #end} declares that no more bytes will arrive, and why. An unterminated tail becomes the last record;
 * if it is not one JSON object it is retained as incomplete, never discarded or completed.</li>
 * </ul>
 *
 * Calls on one input are not thread-safe; a capture feeds a source from one thread.
 */
public interface SourceInput {

    enum End {
        /** The source reached its natural end. */
        EOF,
        /** The capture stopped the source before its end (interruption, cancellation, process loss). */
        INTERRUPTED
    }

    void bytes(byte[] chunk, int offset, int length, Instant arrival);

    default void bytes(byte[] chunk, Instant arrival) {
        bytes(chunk, 0, chunk.length, arrival);
    }

    /**
     * @param raw the record bytes without terminator
     * @param ending {@code LF}, {@code CRLF} or {@code none}
     */
    void record(byte[] raw, String ending, Instant arrival);

    void end(End how, Instant at);

    /** Records received so far, including an incomplete tail already taken. */
    int records();
}
