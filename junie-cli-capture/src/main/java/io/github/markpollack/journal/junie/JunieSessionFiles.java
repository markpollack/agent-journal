package io.github.markpollack.journal.junie;

import io.github.markpollack.journal.raw.RawArtifacts;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Archives Junie's own session directory for a run into the run's raw directory, as the third
 * level-0 artifact (see {@link RawArtifacts}). Junie keeps a session in
 * {@code ~/.junie/sessions/<sessionId>/}: {@code events.jsonl}, {@code state.json},
 * {@code transcript.md}, and per-task files. The whole directory goes into
 * {@code session/<sessionId>.zip}, so nothing Junie wrote about the session is left behind. Take the
 * archive when the run ends.
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
     * Archives one session directory.
     *
     * @param raw the run's raw artifacts
     * @param sessionDir the session's directory, whose name is the session id
     * @return {@code true} if the directory existed and every file in it was archived
     */
    public static boolean archive(RawArtifacts raw, Path sessionDir) {
        String sessionId = sessionDir.getFileName().toString();
        return raw.zipSessionFiles(sessionId + ".zip", "junie-session", Map.of("sessionId", sessionId),
                List.of(new RawArtifacts.ZipSource(sessionDir, sessionId)));
    }

}
