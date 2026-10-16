package io.github.markpollack.journal.raw;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * The level-0 files of one run: what the vendor's agent sent and wrote, kept exactly as it was,
 * in a directory beside the normalized trace. Two kinds of file go here, each separate from the
 * trace and from each other:
 *
 * <ul>
 * <li>a <em>stream file</em> per phase ({@code <phase>.stream.jsonl}): the vendor's live
 * messages, one per line, byte for byte as received, written through {@link #openStream};</li>
 * <li>an <em>archive of the vendor's own session files</em> ({@code session/<name>.zip}), taken with
 * {@link #zipSessionFiles} before the vendor's retention can remove them.</li>
 * </ul>
 *
 * <p>A {@code manifest.json} in the same directory lists every artifact with its relative path,
 * phase, status, size, line count and SHA-256, and says when an attempt failed and why, so a
 * missing artifact is always explained. The manifest is rewritten (atomically) each time an
 * artifact starts or finishes, so a run that dies mid-write still has a manifest naming every
 * artifact it began; a stream file that was never closed has status {@code open}, and its content
 * up to the last whole line is intact because each line is flushed as it is written.
 *
 * <p>Nothing here parses, shortens, redacts or reorders what it is given. These files hold
 * prompts, file contents, tool results and whatever else the vendor sent; treat them as sensitive.
 *
 * <p>An instance is safe to use from several threads. Close it when the run ends; closing closes
 * any stream still open and writes the final manifest.
 */
public final class RawArtifacts implements AutoCloseable {

    /** The manifest's file name, in the raw directory. */
    public static final String MANIFEST_FILE = "manifest.json";

    /** The suffix of a stream file; the phase name comes before it. */
    public static final String STREAM_SUFFIX = ".stream.jsonl";

    /** The subdirectory, under the raw directory, that holds session archives. */
    public static final String SESSION_DIR = "session";

    /** The manifest's own schema version. */
    public static final int MANIFEST_SCHEMA_VERSION = 1;

    /** An artifact's status: the artifact is still being written. */
    public static final String STATUS_OPEN = "open";

    /** An artifact's status: the artifact is whole and its checksum is final. */
    public static final String STATUS_COMPLETE = "complete";

    /** An artifact's status: the attempt was made and failed; {@code reason} says why. */
    public static final String STATUS_FAILED = "failed";

    static final ObjectMapper MAPPER = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9._-]+");

    private final Path rawDir;

    private final String vendor;

    private final String vendorVersion;

    private final Instant createdAt = Instant.now();

    private final List<Map<String, Object>> entries = new ArrayList<>();

    private final List<RawStream> streams = new ArrayList<>();

    private boolean closed;

    private RawArtifacts(Path rawDir, String vendor, String vendorVersion) {
        this.rawDir = rawDir;
        this.vendor = vendor;
        this.vendorVersion = vendorVersion;
    }

    /**
     * Opens the raw directory for a run, creating it, and writes the first manifest.
     *
     * @param rawDir the directory, such as the one {@code JournalStorage.rawDirectory} names
     * @param vendor the vendor's agent, such as {@code "claude-code"} or {@code "codex"}
     * @param vendorVersion the agent CLI's version if known, or {@code null}; written as
     *        {@code null} (not reported) when unknown
     * @return the open artifacts, never {@code null}
     * @throws IOException if the directory or the manifest cannot be written
     */
    public static RawArtifacts open(Path rawDir, String vendor, String vendorVersion) throws IOException {
        if (rawDir == null || vendor == null || vendor.isBlank()) {
            throw new IllegalArgumentException("rawDir and vendor are required");
        }
        Files.createDirectories(rawDir);
        RawArtifacts artifacts = new RawArtifacts(rawDir, vendor, vendorVersion);
        artifacts.writeManifest();
        return artifacts;
    }

    /** Returns the raw directory. */
    public Path directory() {
        return rawDir;
    }

    /**
     * Starts the stream file of a phase.
     *
     * @param phase the phase name; letters, digits, dot, underscore and hyphen only, so it is a
     *        safe file name
     * @return the writer for the phase's stream
     * @throws IOException if the file cannot be created
     * @throws IllegalArgumentException if the name is not a safe file name
     * @throws IllegalStateException if the phase already has a stream, or this is closed
     */
    public synchronized RawStream openStream(String phase) throws IOException {
        requireOpen();
        requireName(phase);
        String relative = phase + STREAM_SUFFIX;
        for (Map<String, Object> entry : entries) {
            if (relative.equals(entry.get("path"))) {
                throw new IllegalStateException("phase already has a stream: " + phase);
            }
        }
        Map<String, Object> entry = newEntry("stream", relative);
        entry.put("phase", phase);
        RawStream stream = new RawStream(this, rawDir.resolve(relative), entry);
        streams.add(stream);
        writeManifest();
        return stream;
    }

    /**
     * One thing to put in a session archive: a file, or a directory with everything under it.
     *
     * @param path the file or directory on disk
     * @param entryName the name it gets in the archive; a directory's files are stored under it,
     *        keeping their relative layout, so naming the sources as the vendor lays them out makes
     *        the archive mirror the vendor's own directory
     * @param refs vendor identifiers that tie this source to the run, such as a thread id, or
     *        {@code null}
     */
    public record ZipSource(Path path, String entryName, Map<String, String> refs) {

        /** A source with no vendor identifiers. */
        public ZipSource(Path path, String entryName) {
            this(path, entryName, null);
        }
    }

    /**
     * Archives a vendor's own session files into one zip, {@code session/<zipName>}: the third
     * level-0 artifact, kept separate from the trace and from the stream files. File contents are
     * stored exactly (compressed, byte for byte on extraction). The manifest records the zip's size
     * and SHA-256 and lists every entry with its uncompressed size, SHA-256 and modification time,
     * so the contents can be checked without unpacking. Symbolic links are not followed, and
     * anything that cannot be read or does not exist is listed under {@code skipped} with the
     * reason; the rest is still archived. Nothing is thrown: a run that produced results is not
     * lost because its session files could not be archived.
     *
     * <p>Take the archive when the run ends, before the vendor's retention removes the files, and
     * when the vendor is no longer writing them (a file that grows while it is read is archived as
     * far as it had grown).
     *
     * @param zipName the file name, ending in {@code .zip}, unique within this run
     * @param role what the archive holds, such as {@code "claude-session"}, or {@code null}
     * @param vendorRefs vendor identifiers for the whole archive, such as {@code sessionId}, or
     *        {@code null}
     * @param sources the files and directories to archive
     * @return {@code true} if every source was archived in full
     */
    public synchronized boolean zipSessionFiles(String zipName, String role, Map<String, String> vendorRefs,
            List<ZipSource> sources) {
        requireOpen();
        requireName(zipName);
        if (!zipName.endsWith(".zip")) {
            throw new IllegalArgumentException("not a zip file name: " + zipName);
        }
        String relative = SESSION_DIR + "/" + zipName;
        Map<String, Object> entry = newEntry("session-archive", relative);
        if (role != null) {
            entry.put("role", role);
        }
        if (vendorRefs != null && !vendorRefs.isEmpty()) {
            entry.put("vendorRefs", new LinkedHashMap<>(vendorRefs));
        }
        List<Map<String, Object>> listed = new ArrayList<>();
        List<Map<String, Object>> skipped = new ArrayList<>();
        entry.put("entries", listed);
        entry.put("skipped", skipped);
        Path target = rawDir.resolve(relative);
        try {
            if (Files.exists(target)) {
                failEntry(entry, "a file is already stored under this name");
                writeManifestQuietly();
                return false;
            }
            Files.createDirectories(target.getParent());
            try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(target))) {
                for (ZipSource source : sources) {
                    addSource(zip, source, listed, skipped);
                }
            }
            entry.put("bytes", Files.size(target));
            entry.put("sha256", sha256(target));
            if (listed.isEmpty()) {
                failEntry(entry, "nothing to archive");
            }
            else {
                entry.put("status", STATUS_COMPLETE);
                entry.put("finishedAt", Instant.now().toString());
            }
        }
        catch (IOException | RuntimeException ex) {
            failEntry(entry, ex.getClass().getSimpleName() + ": " + ex.getMessage());
        }
        writeManifestQuietly();
        return STATUS_COMPLETE.equals(entry.get("status")) && skipped.isEmpty();
    }

    private void addSource(ZipOutputStream zip, ZipSource source, List<Map<String, Object>> listed,
            List<Map<String, Object>> skipped) throws IOException {
        Path path = source.path();
        if (Files.isSymbolicLink(path)) {
            skip(skipped, source.entryName(), path, "symbolic link not followed");
        }
        else if (Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            addFile(zip, path, source.entryName(), source.refs(), listed, skipped);
        }
        else if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
            Files.walkFileTree(path, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    String name = source.entryName() + "/" + path.relativize(file).toString().replace('\\', '/');
                    if (attrs.isSymbolicLink()) {
                        skip(skipped, name, file, "symbolic link not followed");
                    }
                    else if (!attrs.isRegularFile()) {
                        skip(skipped, name, file, "not a regular file");
                    }
                    else {
                        addFile(zip, file, name, source.refs(), listed, skipped);
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException ex) {
                    skip(skipped, source.entryName() + "/" + path.relativize(file).toString().replace('\\', '/'), file,
                            ex.getClass().getSimpleName() + ": " + ex.getMessage());
                    return FileVisitResult.CONTINUE;
                }
            });
        }
        else {
            skip(skipped, source.entryName(), path, "not found");
        }
    }

    private void addFile(ZipOutputStream zip, Path file, String entryName, Map<String, String> refs,
            List<Map<String, Object>> listed, List<Map<String, Object>> skipped) throws IOException {
        ZipEntry zipEntry = new ZipEntry(entryName);
        FileTime modified;
        try {
            modified = Files.getLastModifiedTime(file);
        }
        catch (IOException ex) {
            skip(skipped, entryName, file, ex.getClass().getSimpleName() + ": " + ex.getMessage());
            return;
        }
        zipEntry.setLastModifiedTime(modified);
        MessageDigest digest = sha256Digest();
        long bytes = 0;
        // open before writing the entry header, so an unreadable file leaves no half entry
        try (InputStream in = Files.newInputStream(file)) {
            zip.putNextEntry(zipEntry);
            byte[] buffer = new byte[65536];
            int n;
            while ((n = in.read(buffer)) > 0) {
                zip.write(buffer, 0, n);
                digest.update(buffer, 0, n);
                bytes += n;
            }
            zip.closeEntry();
        }
        catch (java.nio.file.NoSuchFileException | java.nio.file.AccessDeniedException ex) {
            skip(skipped, entryName, file, ex.getClass().getSimpleName() + ": " + ex.getMessage());
            return;
        }
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("name", entryName);
        item.put("bytes", bytes);
        item.put("sha256", hex(digest));
        item.put("modified", modified.toInstant().toString());
        item.put("sourcePath", file.toAbsolutePath().toString());
        if (refs != null && !refs.isEmpty()) {
            item.put("refs", new LinkedHashMap<>(refs));
        }
        listed.add(item);
    }

    private static void skip(List<Map<String, Object>> skipped, String name, Path path, String reason) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("name", name);
        item.put("sourcePath", path.toAbsolutePath().toString());
        item.put("reason", reason);
        skipped.add(item);
    }

    /**
     * Records that a session file was looked for and could not be found or chosen, so the
     * manifest explains the gap.
     *
     * @param role what was looked for
     * @param reason why nothing was copied
     */
    public synchronized void recordSessionFileNotCaptured(String role, String reason) {
        requireOpen();
        Map<String, Object> entry = newEntry("session-archive", null);
        entry.put("role", role);
        fail(entry, reason);
    }

    @Override
    public synchronized void close() throws IOException {
        if (closed) {
            return;
        }
        IOException first = null;
        for (RawStream stream : streams) {
            try {
                stream.close();
            }
            catch (IOException ex) {
                if (first == null) {
                    first = ex;
                }
            }
        }
        closed = true;
        try {
            writeManifest();
        }
        catch (IOException ex) {
            if (first == null) {
                first = ex;
            }
        }
        if (first != null) {
            throw first;
        }
    }

    synchronized void streamFinished(Map<String, Object> entry, Map<String, Object> finished, boolean ok)
            throws IOException {
        entry.putAll(finished);
        entry.put("status", ok ? STATUS_COMPLETE : STATUS_FAILED);
        if (!ok) {
            entry.put("reason", "the stream file could not be closed cleanly");
        }
        entry.put("finishedAt", Instant.now().toString());
        writeManifest();
    }

    private boolean fail(Map<String, Object> entry, String reason) {
        failEntry(entry, reason);
        writeManifestQuietly();
        return false;
    }

    private static boolean failEntry(Map<String, Object> entry, String reason) {
        entry.put("status", STATUS_FAILED);
        entry.put("reason", reason);
        entry.put("finishedAt", Instant.now().toString());
        return false;
    }

    private void writeManifestQuietly() {
        try {
            writeManifest();
        }
        catch (IOException ex) {
            // the entries are in memory; the next successful manifest write carries them
        }
    }

    private Map<String, Object> newEntry(String kind, String relativePath) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("kind", kind);
        entry.put("path", relativePath);
        entry.put("status", STATUS_OPEN);
        entry.put("startedAt", Instant.now().toString());
        entries.add(entry);
        return entry;
    }

    private void writeManifest() throws IOException {
        Map<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("manifestSchemaVersion", MANIFEST_SCHEMA_VERSION);
        manifest.put("vendor", vendor);
        manifest.put("vendorVersion", vendorVersion);
        manifest.put("createdAt", createdAt.toString());
        manifest.put("closed", closed);
        manifest.put("artifacts", entries);
        Path target = rawDir.resolve(MANIFEST_FILE);
        Path tmp = rawDir.resolve(MANIFEST_FILE + ".tmp");
        MAPPER.writeValue(tmp.toFile(), manifest);
        Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    private void requireOpen() {
        if (closed) {
            throw new IllegalStateException("closed");
        }
    }

    private static void requireName(String name) {
        if (name == null || !NAME.matcher(name).matches() || name.equals(".") || name.equals("..")) {
            throw new IllegalArgumentException("not a safe file name: " + name);
        }
    }

    static MessageDigest sha256Digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        }
        catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }

    static String hex(MessageDigest digest) {
        return HexFormat.of().formatHex(digest.digest());
    }

    private static String sha256(Path file) throws IOException {
        MessageDigest digest = sha256Digest();
        try (var in = Files.newInputStream(file)) {
            byte[] buffer = new byte[65536];
            int n;
            while ((n = in.read(buffer)) > 0) {
                digest.update(buffer, 0, n);
            }
        }
        return hex(digest);
    }

}
