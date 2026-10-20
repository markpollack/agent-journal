package io.github.markpollack.journal.claude;

import io.github.markpollack.journal.raw.RawArtifacts;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Archives Claude Code's own session files for a run into the run's raw directory, as the third
 * level-0 artifact (see {@link RawArtifacts}).
 *
 * <p>Claude Code keeps a session under {@code <projects root>/<encoded working directory>/}: the
 * transcript {@code <sessionId>.jsonl}, and a directory named by the session id that holds the
 * sub-agents' transcripts ({@code subagents/}), large tool results kept out of line
 * ({@code tool-results/}) and other per-session files. The transcript alone is not the whole
 * session, so both go into one archive, {@code session/<sessionId>.zip}, whose entries mirror that
 * layout ({@code <sessionId>.jsonl}, {@code <sessionId>/subagents/...}). Take the archive when the
 * run ends: Claude Code removes sessions after its retention period.
 *
 * <p>The session id is the file name, so finding the transcript is a lookup. If no file, or more
 * than one, matches, nothing is archived and the manifest says why: a wrong file archived confidently
 * is worse than a missing one.
 */
public final class ClaudeSessionFiles {

    private ClaudeSessionFiles() {
    }

    /**
     * Returns Claude Code's default projects directory, {@code ~/.claude/projects}.
     *
     * @return the directory
     */
    public static Path defaultProjectsRoot() {
        return Path.of(System.getProperty("user.home"), ".claude", "projects");
    }

    /**
     * Archives the transcript and the session directory of one session.
     *
     * @param raw the run's raw artifacts
     * @param sessionId the Claude Code session id, or {@code null} if the run reported none
     * @param projectsRoot the directory that holds Claude Code's per-project directories, such as
     *        {@link #defaultProjectsRoot()}
     * @return {@code true} if the transcript was found and everything was archived; otherwise the
     *         manifest records what was not
     */
    /**
     * Finds the one transcript named after the session under the projects root.
     *
     * @return the transcript, or empty if none or several were found or the id is not usable as a file name
     */
    public static java.util.Optional<Path> locateTranscript(String sessionId, Path projectsRoot) {
        if (sessionId == null || sessionId.isBlank() || !sessionId.matches("[A-Za-z0-9._-]+")
                || !Files.isDirectory(projectsRoot)) {
            return java.util.Optional.empty();
        }
        List<Path> found = new ArrayList<>();
        try (Stream<Path> projects = Files.list(projectsRoot)) {
            for (Path project : (Iterable<Path>) projects::iterator) {
                Path candidate = project.resolve(sessionId + ".jsonl");
                if (Files.isRegularFile(candidate)) {
                    found.add(candidate);
                }
            }
        }
        catch (IOException ex) {
            return java.util.Optional.empty();
        }
        return found.size() == 1 ? java.util.Optional.of(found.get(0)) : java.util.Optional.empty();
    }

    public static boolean archive(RawArtifacts raw, String sessionId, Path projectsRoot) {
        if (sessionId == null || sessionId.isBlank()) {
            raw.recordSessionFileNotCaptured("transcript", "the run reported no session id, so there is nothing to look up");
            return false;
        }
        if (!sessionId.matches("[A-Za-z0-9._-]+")) {
            raw.recordSessionFileNotCaptured("transcript", "the session id is not usable as a file name: " + sessionId);
            return false;
        }
        List<Path> found = new ArrayList<>();
        if (Files.isDirectory(projectsRoot)) {
            try (Stream<Path> projects = Files.list(projectsRoot)) {
                for (Path project : (Iterable<Path>) projects::iterator) {
                    Path candidate = project.resolve(sessionId + ".jsonl");
                    if (Files.isRegularFile(candidate)) {
                        found.add(candidate);
                    }
                }
            }
            catch (IOException ex) {
                raw.recordSessionFileNotCaptured("transcript", "could not list " + projectsRoot + ": " + ex.getMessage());
                return false;
            }
        }
        if (found.size() != 1) {
            raw.recordSessionFileNotCaptured("transcript", found.isEmpty()
                    ? "no transcript named " + sessionId + ".jsonl under " + projectsRoot
                    : "ambiguous: " + found.size() + " transcripts named " + sessionId + ".jsonl under " + projectsRoot);
            return false;
        }
        Path transcript = found.get(0);
        Map<String, String> refs = Map.of("sessionId", sessionId);
        List<RawArtifacts.ZipSource> sources = new ArrayList<>();
        sources.add(new RawArtifacts.ZipSource(transcript, sessionId + ".jsonl"));
        Path sessionDir = transcript.resolveSibling(sessionId);
        if (Files.isDirectory(sessionDir)) {
            sources.add(new RawArtifacts.ZipSource(sessionDir, sessionId));
        }
        return raw.zipSessionFiles(sessionId + ".zip", "claude-session", refs, sources);
    }

}
