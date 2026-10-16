package io.github.markpollack.journal.antigravity;

import io.github.markpollack.journal.raw.RawArtifacts;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Archives Antigravity's own files for one conversation into the run's raw directory, as the third
 * level-0 artifact (see {@link RawArtifacts}). The CLI keeps a conversation under its data
 * directory ({@code ~/.gemini/antigravity-cli}) as a SQLite database
 * ({@code conversations/<id>.db}, with its {@code -wal} and {@code -shm} files if present), a
 * working directory ({@code brain/<id>/}) and an annotations file ({@code annotations/<id>.pbtxt}).
 * They go into one archive, {@code session/<id>.zip}, whose entries keep that layout
 * ({@code conversations/<id>.db}, {@code brain/<id>/...}, {@code annotations/<id>.pbtxt}). Take the archive after the CLI has exited, so the database is not being
 * written.
 */
public final class AntigravitySessionFiles {

    private AntigravitySessionFiles() {
    }

    /**
     * Returns the CLI's default data directory, {@code ~/.gemini/antigravity-cli}.
     *
     * @return the directory
     */
    public static Path defaultDataRoot() {
        return Path.of(System.getProperty("user.home"), ".gemini", "antigravity-cli");
    }

    /**
     * Archives the files of one conversation.
     *
     * @param raw the run's raw artifacts
     * @param conversationId the conversation id from the stream's {@code init} event, or {@code null}
     * @param dataRoot the CLI's data directory, such as {@link #defaultDataRoot()}
     * @return {@code true} if the database was found and everything found was archived
     */
    public static boolean archive(RawArtifacts raw, String conversationId, Path dataRoot) {
        if (conversationId == null || !conversationId.matches("[A-Za-z0-9._-]+")) {
            raw.recordSessionFileNotCaptured("conversation-database", "no usable conversation id: " + conversationId);
            return false;
        }
        Map<String, String> refs = Map.of("conversationId", conversationId);
        Path db = dataRoot.resolve("conversations").resolve(conversationId + ".db");
        if (!Files.isRegularFile(db)) {
            raw.recordSessionFileNotCaptured("conversation-database", "no database at " + db);
            return false;
        }
        List<RawArtifacts.ZipSource> sources = new ArrayList<>();
        for (String suffix : new String[] {"", "-wal", "-shm"}) {
            Path file = dataRoot.resolve("conversations").resolve(conversationId + ".db" + suffix);
            if (Files.isRegularFile(file)) {
                sources.add(new RawArtifacts.ZipSource(file, "conversations/" + conversationId + ".db" + suffix));
            }
        }
        Path brain = dataRoot.resolve("brain").resolve(conversationId);
        if (Files.isDirectory(brain)) {
            sources.add(new RawArtifacts.ZipSource(brain, "brain/" + conversationId));
        }
        Path annotations = dataRoot.resolve("annotations").resolve(conversationId + ".pbtxt");
        if (Files.isRegularFile(annotations)) {
            sources.add(new RawArtifacts.ZipSource(annotations, "annotations/" + conversationId + ".pbtxt"));
        }
        return raw.zipSessionFiles(conversationId + ".zip", "antigravity-conversation", refs, sources);
    }

}
