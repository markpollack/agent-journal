package io.github.markpollack.journal.event;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Records a set of code changes made during a run against a base branch or commit: the files
 * changed, the lines added and removed, and, if the caller supplies it, the unified diff. Nothing
 * in the library logs patches: create one with {@link #of(String, List)} or
 * {@link #withPatch(String, List, String)} and log it with
 * {@link io.github.markpollack.journal.Run#logEvent(JournalEvent)}. File storage writes it with
 * {@code @type} {@code "git_patch"}, and
 * {@link io.github.markpollack.journal.storage.JournalStorage#loadEvents(String, String)} reads it
 * back as a {@code GitPatchEvent}. It is one of the {@link GitEvent}s.
 *
 * <p>Unlike {@link GitCommitEvent}, it needs no commit: it can record an agent's changes before
 * they are committed, or changes that never are. It has a {@link FileChange} per file, and no
 * SHA. The factory methods add up the line counts of the file changes; the canonical constructor
 * takes the totals as given and does not check them. The record copies {@code fileChanges} into
 * an unmodifiable list; a {@code null} list becomes an empty one, so a JSON line without
 * {@code fileChanges} reads back with no file changes, and a {@code null} element throws
 * {@link NullPointerException}. A
 * diff is written whole into {@code events.jsonl}, so it can make the file large. The record is
 * immutable.
 *
 * @param timestamp when the patch was made
 * @param baseBranch the branch or commit the patch applies to, such as {@code "main"}
 * @param fileChanges the change to each file
 * @param linesAdded the total lines added across all files
 * @param linesRemoved the total lines removed across all files
 * @param patchContent the unified diff, or {@code null} if it was not kept
 */
public record GitPatchEvent(
        Instant timestamp,
        String baseBranch,
        List<FileChange> fileChanges,
        int linesAdded,
        int linesRemoved,
        String patchContent
) implements GitEvent {

    /**
     * Creates a patch event, copying {@code fileChanges} into an unmodifiable list, or using an
     * empty list if it is {@code null}.
     *
     * @param timestamp when the patch was made
     * @param baseBranch the base branch or commit
     * @param fileChanges the file changes
     * @param linesAdded the total lines added
     * @param linesRemoved the total lines removed
     * @param patchContent the unified diff
     * @throws NullPointerException if one of the elements of {@code fileChanges} is {@code null}
     */
    public GitPatchEvent {
        fileChanges = fileChanges == null ? List.of() : List.copyOf(fileChanges);
    }

    @Override
    public String type() {
        return "git_patch";
    }

    /**
     * Creates a patch event with the current time and no diff. The line totals are the sums over
     * {@code changes}.
     *
     * @param baseBranch the branch or commit the patch applies to
     * @param changes the change to each file
     * @return the new event
     * @throws NullPointerException if {@code changes} or one of its elements is {@code null}
     */
    public static GitPatchEvent of(String baseBranch, List<FileChange> changes) {
        int added = changes.stream().mapToInt(FileChange::linesAdded).sum();
        int removed = changes.stream().mapToInt(FileChange::linesRemoved).sum();
        return new GitPatchEvent(Instant.now(), baseBranch, changes, added, removed, null);
    }

    /**
     * Creates a patch event with the current time and the unified diff. The line totals are the
     * sums over {@code changes}; they are not taken from the diff.
     *
     * @param baseBranch the branch or commit the patch applies to
     * @param changes the change to each file
     * @param patchContent the unified diff
     * @return the new event
     * @throws NullPointerException if {@code changes} or one of its elements is {@code null}
     */
    public static GitPatchEvent withPatch(String baseBranch, List<FileChange> changes, String patchContent) {
        int added = changes.stream().mapToInt(FileChange::linesAdded).sum();
        int removed = changes.stream().mapToInt(FileChange::linesRemoved).sum();
        return new GitPatchEvent(Instant.now(), baseBranch, changes, added, removed, patchContent);
    }

    /**
     * {@inheritDoc}
     *
     * <p>The keys are {@code base_branch}, {@code files_changed} (the number of files),
     * {@code lines_added} and {@code lines_removed}, then {@code patch_content} when a diff is
     * set. The file paths are left out.
     */
    @Override
    public Map<String, Object> toMap() {
        var map = new LinkedHashMap<String, Object>();
        map.put("base_branch", baseBranch);
        map.put("files_changed", fileChanges.size());
        map.put("lines_added", linesAdded);
        map.put("lines_removed", linesRemoved);
        if (patchContent != null) {
            map.put("patch_content", patchContent);
        }
        return map;
    }

    /**
     * The change to one file in a {@link GitPatchEvent}: its path, the kind of change, and the
     * lines added and removed. A rename records one path only; there is no field for the other
     * name. The record is immutable.
     *
     * @param path the file's path, relative to the repository root
     * @param changeType whether the file was added, modified, deleted or renamed
     * @param linesAdded the lines added to this file
     * @param linesRemoved the lines removed from this file
     */
    public record FileChange(
            String path,
            ChangeType changeType,
            int linesAdded,
            int linesRemoved
    ) {
        /** The kind of change made to a file. */
        public enum ChangeType {
            /** The file is new. */
            ADDED,
            /** The file's content changed. */
            MODIFIED,
            /** The file was removed. */
            DELETED,
            /** The file was renamed or moved. */
            RENAMED
        }

        /**
         * Creates the change for a new file.
         *
         * @param path the file's path
         * @param lines the lines in the new file, counted as added
         * @return the new file change
         */
        public static FileChange added(String path, int lines) {
            return new FileChange(path, ChangeType.ADDED, lines, 0);
        }

        /**
         * Creates the change for a modified file.
         *
         * @param path the file's path
         * @param added the lines added
         * @param removed the lines removed
         * @return the new file change
         */
        public static FileChange modified(String path, int added, int removed) {
            return new FileChange(path, ChangeType.MODIFIED, added, removed);
        }

        /**
         * Creates the change for a deleted file.
         *
         * @param path the file's path
         * @param lines the lines in the deleted file, counted as removed
         * @return the new file change
         */
        public static FileChange deleted(String path, int lines) {
            return new FileChange(path, ChangeType.DELETED, 0, lines);
        }

        /**
         * Creates the change for a renamed file, with no line counts.
         *
         * @param path the file's path
         * @return the new file change
         */
        public static FileChange renamed(String path) {
            return new FileChange(path, ChangeType.RENAMED, 0, 0);
        }
    }
}
