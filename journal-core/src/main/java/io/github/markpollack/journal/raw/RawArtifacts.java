package io.github.markpollack.journal.raw;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * The level-0 files of one run: what the vendor's agent sent and wrote, kept exactly as it was,
 * in a directory beside the normalized trace. Two kinds of file go here, each separate from the
 * trace and from each other:
 *
 * <ul>
 * <li>a <em>stream file</em> per phase ({@code <phase>.stream.jsonl}): the vendor's live
 * messages, one per line, byte for byte as received, written through {@link #openStream};</li>
 * <li>a <em>copy of the vendor's own session file</em> ({@code session/<name>}), taken with
 * {@link #copySessionFile} before the vendor's retention can remove it.</li>
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

    /** The subdirectory, under the raw directory, that holds session-file copies. */
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
     * Copies one vendor session file into {@code session/}, byte for byte, and records its
     * SHA-256, size, original path and modification time. A failure to copy is recorded in the
     * manifest with its reason and does not throw: a run that produced results is not lost
     * because a session file could not be archived.
     *
     * @param source the vendor's file
     * @param storedName the file name to store it under, unique within this run
     * @param role what the file is, such as {@code "transcript"}, {@code "subagent-transcript"} or
     *        {@code "rollout"}, or {@code null}
     * @param vendorRefs vendor identifiers that tie the file to the run, such as
     *        {@code sessionId} or {@code threadId}, or {@code null}
     * @return {@code true} if the copy succeeded
     */
    public synchronized boolean copySessionFile(Path source, String storedName, String role,
            Map<String, String> vendorRefs) {
        requireOpen();
        requireName(storedName);
        boolean ok = copyOne(source, SESSION_DIR + "/" + storedName, role, vendorRefs);
        writeManifestQuietly();
        return ok;
    }

    /**
     * Copies a vendor's session directory, every regular file in it at any depth, into
     * {@code session/<storedDir>/}, keeping the relative layout, byte for byte. Each file gets its
     * own manifest entry with SHA-256, size and modification time. Symbolic links are not followed:
     * each is recorded as not captured. A file that cannot be copied is recorded as failed and the
     * others are still copied; nothing is thrown.
     *
     * <p>Some vendors keep material beside the main session file that the file refers to: Claude
     * Code stores sub-agent transcripts and large tool results as separate files in a directory
     * named by the session id.
     *
     * @param sourceDir the vendor's directory
     * @param storedDir the directory name to store it under, unique within this run
     * @param role what the files are, or {@code null}
     * @param vendorRefs vendor identifiers that tie the files to the run, or {@code null}
     * @return {@code true} if the directory existed and every file in it was copied
     */
    public synchronized boolean copySessionTree(Path sourceDir, String storedDir, String role,
            Map<String, String> vendorRefs) {
        requireOpen();
        requireName(storedDir);
        if (!Files.isDirectory(sourceDir, LinkOption.NOFOLLOW_LINKS)) {
            Map<String, Object> entry = newEntry("session-file", SESSION_DIR + "/" + storedDir);
            entry.put("sourcePath", sourceDir.toAbsolutePath().toString());
            if (role != null) {
                entry.put("role", role);
            }
            fail(entry, "source directory not found");
            return false;
        }
        boolean[] all = {true};
        try {
            Files.walkFileTree(sourceDir, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    String relative = SESSION_DIR + "/" + storedDir + "/"
                            + sourceDir.relativize(file).toString().replace('\\', '/');
                    if (attrs.isSymbolicLink() || !attrs.isRegularFile()) {
                        Map<String, Object> entry = newEntry("session-file", relative);
                        entry.put("sourcePath", file.toAbsolutePath().toString());
                        entry.put("status", STATUS_FAILED);
                        entry.put("reason", attrs.isSymbolicLink() ? "symbolic link not followed" : "not a regular file");
                        entry.put("finishedAt", Instant.now().toString());
                        all[0] = false;
                    }
                    else if (!copyOne(file, relative, role, vendorRefs)) {
                        all[0] = false;
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException ex) {
                    Map<String, Object> entry = newEntry("session-file",
                            SESSION_DIR + "/" + storedDir + "/" + sourceDir.relativize(file).toString().replace('\\', '/'));
                    entry.put("sourcePath", file.toAbsolutePath().toString());
                    entry.put("status", STATUS_FAILED);
                    entry.put("reason", ex.getClass().getSimpleName() + ": " + ex.getMessage());
                    entry.put("finishedAt", Instant.now().toString());
                    all[0] = false;
                    return FileVisitResult.CONTINUE;
                }
            });
        }
        catch (IOException | RuntimeException ex) {
            Map<String, Object> entry = newEntry("session-file", SESSION_DIR + "/" + storedDir);
            fail(entry, ex.getClass().getSimpleName() + ": " + ex.getMessage());
            all[0] = false;
        }
        writeManifestQuietly();
        return all[0];
    }

    /** Copies one file to {@code relative} under the raw directory and records the entry; no manifest write. */
    private boolean copyOne(Path source, String relative, String role, Map<String, String> vendorRefs) {
        Map<String, Object> entry = newEntry("session-file", relative);
        if (role != null) {
            entry.put("role", role);
        }
        if (vendorRefs != null && !vendorRefs.isEmpty()) {
            entry.put("vendorRefs", new LinkedHashMap<>(vendorRefs));
        }
        entry.put("sourcePath", source.toAbsolutePath().toString());
        try {
            if (!Files.isRegularFile(source)) {
                return failEntry(entry, "source file not found");
            }
            Path target = rawDir.resolve(relative);
            if (Files.exists(target)) {
                return failEntry(entry, "a file is already stored under this name");
            }
            Files.createDirectories(target.getParent());
            entry.put("sourceModified", Files.getLastModifiedTime(source).toInstant().toString());
            Files.copy(source, target);
            entry.put("bytes", Files.size(target));
            entry.put("sha256", sha256(target));
            entry.put("status", STATUS_COMPLETE);
            entry.put("finishedAt", Instant.now().toString());
            return true;
        }
        catch (IOException | RuntimeException ex) {
            return failEntry(entry, ex.getClass().getSimpleName() + ": " + ex.getMessage());
        }
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
        Map<String, Object> entry = newEntry("session-file", null);
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
