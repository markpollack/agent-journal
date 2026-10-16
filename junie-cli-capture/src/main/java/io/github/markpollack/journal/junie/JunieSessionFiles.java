package io.github.markpollack.journal.junie;

import io.github.markpollack.journal.raw.RawArtifacts;

import java.nio.file.Path;
import java.util.Map;

/**
 * Copies Junie's own session directory for a run into the run's raw directory, as the third
 * level-0 artifact (see {@link RawArtifacts}). Junie keeps a session in
 * {@code ~/.junie/sessions/<sessionId>/}: {@code events.jsonl}, {@code state.json},
 * {@code transcript.md}, and per-task files. The whole directory is copied to
 * {@code session/<sessionId>/}, so nothing Junie wrote about the session is left behind. Take the
 * copy when the run ends.
 */
public final class JunieSessionFiles {

    private JunieSessionFiles() {
    }

    /**
     * Returns Junie's default sessions directory, {@code ~/.junie/sessions}.
     *
     * @return the directory
     */
    public static Path defaultSessionsRoot() {
        return Path.of(System.getProperty("user.home"), ".junie", "sessions");
    }

    /**
     * Copies one session directory.
     *
     * @param raw the run's raw artifacts
     * @param sessionDir the session's directory, whose name is the session id
     * @return {@code true} if the directory existed and every file in it was copied
     */
    public static boolean archive(RawArtifacts raw, Path sessionDir) {
        String sessionId = sessionDir.getFileName().toString();
        return raw.copySessionTree(sessionDir, sessionId, "session-directory", Map.of("sessionId", sessionId));
    }

}
