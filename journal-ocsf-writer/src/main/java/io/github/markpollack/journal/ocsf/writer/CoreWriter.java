package io.github.markpollack.journal.ocsf.writer;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Function;

/**
 * Persistence of one core ({@code run.json} + {@code events.jsonl}, the root or a linked child). Events are
 * serialized as they are appended and buffered; {@link #flush} hands the buffered bytes to the file and replaces the
 * manifest atomically, so a reader opening the directory at any moment sees a consistent provisional package.
 * Finalization forces the file to storage, hashes what is actually on disk and writes the final manifest once.
 *
 * <p>Visibility/durability as implemented and tested: after {@code flush()} the appended bytes are readable by other
 * processes on this host (OS page cache); {@code fsync} ({@link FileChannel#force}) is requested only at
 * finalization and failure. No claim is made about power loss between a flush and finalization.
 */
final class CoreWriter {

    final Path dir;
    final String runUid;
    private final Path eventsFile;
    private final FileChannel channel;
    private final WriterOptions options;
    private final ByteArrayOutputStream pending = new ByteArrayOutputStream();
    private final DefinitionClosure.Collector collector;
    private int sequence;
    private int pendingEvents;
    private int persistedEvents;
    private long persistedBytes;
    private int flushCount;
    private final Map<String, Integer> byType = new TreeMap<>();
    private boolean finalized;
    private String finalSha256;
    private Jv.Obj failure;

    CoreWriter(Path dir, String runUid, Jv compiled, WriterOptions options) throws IOException {
        this.dir = dir;
        this.runUid = runUid;
        this.options = options;
        this.collector = new DefinitionClosure.Collector(compiled);
        Files.createDirectories(dir);
        this.eventsFile = dir.resolve("events.jsonl");
        this.channel = FileChannel.open(eventsFile, StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING);
    }

    /** Assigns the next sequence (and this core's run uid) and buffers the event; nothing reaches the file until a flush. */
    void append(Jv.Obj event) {
        if (finalized || failure != null) {
            throw new WriterFailedException("core " + runUid + " is " + (finalized ? "finalized" : "failed") + "; no more events can be appended");
        }
        Jv.Obj journal = (Jv.Obj) event.get("journal");
        journal.members().put("sequence", Jv.Num.of(++sequence));
        journal.members().put("run_uid", new Jv.Str(runUid));
        collector.collect(event);
        byType.merge(event.get("type_name").text(), 1, Integer::sum);
        pending.writeBytes((Jv.write(event) + "\n").getBytes(StandardCharsets.UTF_8));
        pendingEvents++;
    }

    int pendingEvents() {
        return pendingEvents;
    }

    /** Writes the buffered events to the file and replaces the manifest; returns the number of events now persisted. */
    int flush(Function<CoreWriter, Jv> manifest) throws IOException {
        if (finalized) {
            return persistedEvents;
        }
        if (failure != null) {
            throw new WriterFailedException("core " + runUid + " failed earlier: " + failure.get("detail").text());
        }
        try {
            if (pendingEvents > 0) {
                byte[] bytes = pending.toByteArray();
                options.faults().before("append", runUid + " " + pendingEvents + " event(s), " + bytes.length + " bytes");
                ByteBuffer buf = ByteBuffer.wrap(bytes);
                while (buf.hasRemaining()) {
                    channel.write(buf);
                }
                pending.reset();
                persistedEvents += pendingEvents;
                persistedBytes += bytes.length;
                pendingEvents = 0;
            }
            flushCount++;
            writeManifest(manifest.apply(this));
            return persistedEvents;
        }
        catch (IOException e) {
            fail("flush", e);
            throw e;
        }
    }

    /** Forces the events to storage, hashes the file as it is on disk and writes the final manifest; idempotent. */
    void finish(Function<CoreWriter, Jv> manifest) throws IOException {
        if (finalized) {
            return;
        }
        if (failure != null) {
            return; // the failure manifest already describes the persisted boundary
        }
        try {
            flush(manifest);
            options.faults().before("force", runUid);
            channel.force(true);
            finalSha256 = SchemaPin.sha256(Files.readAllBytes(eventsFile));
            finalized = true;
            writeManifest(manifest.apply(this));
            channel.close();
        }
        catch (IOException e) {
            fail("finish", e);
            throw e;
        }
    }

    /** Records the failure, keeps what the file holds, writes a FAILED manifest when it can, and closes the channel. */
    void fail(String operation, Exception cause) {
        if (failure != null || finalized) {
            return;
        }
        long onDisk;
        try {
            onDisk = channel.size();
        }
        catch (IOException e) {
            onDisk = -1;
        }
        failure = new Jv.Obj().put("operation", operation).put("exception", cause.getClass().getName())
                .put("detail", cause.getMessage() == null ? cause.toString() : cause.getMessage())
                .put("events_confirmed_persisted", persistedEvents)
                .put("events_not_confirmed", pendingEvents)
                .put("bytes_confirmed_persisted", persistedBytes)
                .put("bytes_on_disk_at_failure", onDisk)
                .put("note", "events_not_confirmed may be partly on disk; the reader judges the file as it is and the manifest carries no final hash");
        try {
            channel.force(true);
        }
        catch (IOException ignored) {
            // the failure is already recorded; nothing more can be promised
        }
        try {
            channel.close();
        }
        catch (IOException ignored) {
            // same
        }
    }

    /** Writes the failure manifest (best effort; a second failure here is reported to the caller). */
    void writeFailureManifest(Function<CoreWriter, Jv> manifest) throws IOException {
        writeManifest(manifest.apply(this));
    }

    private void writeManifest(Jv manifest) throws IOException {
        options.faults().before("manifest", runUid);
        Path tmp = dir.resolve("run.json.tmp");
        Files.writeString(tmp, Jv.write(manifest) + "\n", StandardCharsets.UTF_8);
        Files.move(tmp, dir.resolve("run.json"), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    // ------------------------------------------------------------------------------------------ manifest inputs

    boolean finalized() {
        return finalized;
    }

    Jv.Obj failure() {
        return failure;
    }

    int persistedEvents() {
        return persistedEvents;
    }

    long persistedBytes() {
        return persistedBytes;
    }

    int flushCount() {
        return flushCount;
    }

    String finalSha256() {
        return finalSha256;
    }

    Jv eventsByType() {
        Jv.Obj o = new Jv.Obj();
        byType.forEach(o::put);
        return o;
    }

    int eventsAppended() {
        return sequence;
    }

    Jv closure(Map<String, Integer> classUids, DefinitionClosure.Policy policy) {
        return collector.build(classUids, policy);
    }

    /** Thrown when a writer or core that failed or was finalized is used again. */
    static final class WriterFailedException extends IllegalStateException {
        WriterFailedException(String message) {
            super(message);
        }
    }
}
