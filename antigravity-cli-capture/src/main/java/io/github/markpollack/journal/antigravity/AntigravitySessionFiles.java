package io.github.markpollack.journal.antigravity;

import io.github.markpollack.journal.raw.RawArtifacts;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * Copies Antigravity's own files for one conversation into the run's raw directory, as the third
 * level-0 artifact (see {@link RawArtifacts}). The CLI keeps a conversation under its data
 * directory ({@code ~/.gemini/antigravity-cli}) as a SQLite database
 * ({@code conversations/<id>.db}, with its {@code -wal} and {@code -shm} files if present), a
 * working directory ({@code brain/<id>/}) and an annotations file ({@code annotations/<id>.pbtxt}).
 * They are stored as {@code session/<id>.db} (and companions), {@code session/brain-<id>/} and
 * {@code session/<id>.pbtxt}. Take the copy after the CLI has exited, so the database is not being
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
     * Copies the files of one conversation.
     *
     * @param raw the run's raw artifacts
     * @param conversationId the conversation id from the stream's {@code init} event, or {@code null}
     * @param dataRoot the CLI's data directory, such as {@link #defaultDataRoot()}
     * @return {@code true} if the database was found and everything found was copied
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
        boolean ok = raw.copySessionFile(db, conversationId + ".db", "conversation-database", refs);
        for (String suffix : new String[] {"-wal", "-shm"}) {
            Path companion = dataRoot.resolve("conversations").resolve(conversationId + ".db" + suffix);
            if (Files.isRegularFile(companion)) {
                ok &= raw.copySessionFile(companion, conversationId + ".db" + suffix, "conversation-database-companion", refs);
            }
        }
        Path brain = dataRoot.resolve("brain").resolve(conversationId);
        if (Files.isDirectory(brain)) {
            ok &= raw.copySessionTree(brain, "brain-" + conversationId, "working-directory", refs);
        }
        Path annotations = dataRoot.resolve("annotations").resolve(conversationId + ".pbtxt");
        if (Files.isRegularFile(annotations)) {
            ok &= raw.copySessionFile(annotations, conversationId + ".pbtxt", "annotations", refs);
        }
        return ok;
    }

}
