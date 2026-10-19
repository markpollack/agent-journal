package io.github.markpollack.journal.grok;

import io.github.markpollack.journal.raw.RawArtifacts;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Archives Grok's own session directory for a run into the run's raw directory, as a level-0 artifact (see
 * {@link RawArtifacts}). Grok keeps a session in {@code ~/.grok/sessions/<url-encoded working directory>/<session id>/}:
 * {@code chat_history.jsonl}, {@code updates.jsonl}, {@code events.jsonl}, {@code rewind_points.jsonl},
 * {@code summary.json}, {@code signals.json}, {@code prompt_context.json}, {@code system_prompt.txt} and lock files. The
 * whole directory goes into {@code session/<sessionId>.zip}. The working directory's {@code prompt_history.jsonl} is
 * not archived: it holds the prompts of every session in that directory, not only this run's. Take the archive when the
 * run ends.
 *
 * <p>The session is found by its id under every working-directory folder, so the archiver does not depend on how Grok
 * encodes the folder name. Zero or several matches are recorded in the manifest with the reason, and nothing is archived.
 */
public final class GrokSessionFiles {

    private GrokSessionFiles() {
    }

    /**
     * Returns Grok's default sessions directory, {@code ~/.grok/sessions}.
     *
     * @return the directory
     */
    public static Path defaultSessionsRoot() {
        return Path.of(System.getProperty("user.home"), ".grok", "sessions");
    }

    /**
     * Archives one session directory.
     *
     * @param raw the run's raw artifacts
     * @param sessionId the Grok session id, from the {@code end} line, or {@code null} if the run reported none
     * @param sessionsRoot the directory that holds Grok's per-working-directory folders, such as
     *        {@link #defaultSessionsRoot()}
     * @return {@code true} if exactly one session directory was found and every file in it was archived; otherwise the
     *         manifest records what was not
     */
    public static boolean archive(RawArtifacts raw, String sessionId, Path sessionsRoot) {
        if (sessionId == null || sessionId.isBlank()) {
            raw.recordSessionFileNotCaptured("grok-session", "the run reported no session id, so there is nothing to look up");
            return false;
        }
        if (!sessionId.matches("[A-Za-z0-9._-]+")) {
            raw.recordSessionFileNotCaptured("grok-session", "the session id is not usable as a file name: " + sessionId);
            return false;
        }
        List<Path> found = new ArrayList<>();
        if (Files.isDirectory(sessionsRoot)) {
            try (Stream<Path> folders = Files.list(sessionsRoot)) {
                for (Path folder : (Iterable<Path>) folders::iterator) {
                    Path candidate = folder.resolve(sessionId);
                    if (Files.isDirectory(candidate)) {
                        found.add(candidate);
                    }
                }
            }
            catch (IOException ex) {
                raw.recordSessionFileNotCaptured("grok-session", "could not list " + sessionsRoot + ": " + ex.getMessage());
                return false;
            }
        }
        if (found.size() != 1) {
            raw.recordSessionFileNotCaptured("grok-session", found.isEmpty()
                    ? "no session directory named " + sessionId + " under " + sessionsRoot
                    : "ambiguous: " + found.size() + " session directories named " + sessionId + " under " + sessionsRoot);
            return false;
        }
        return raw.zipSessionFiles(sessionId + ".zip", "grok-session", Map.of("sessionId", sessionId),
                List.of(new RawArtifacts.ZipSource(found.get(0), sessionId)));
    }

}
