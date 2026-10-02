package io.github.markpollack.journal.event;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Records a Git commit made during a run: its SHA, message and branch, and, if the caller supplies
 * them, the files it changed and the lines added and removed. Nothing in the library logs
 * commits: create one with {@link #of(String, String, String)} or {@link #builder()} and log it
 * with {@link io.github.markpollack.journal.Run#logEvent(JournalEvent)}. File storage writes it
 * with {@code @type} {@code "git_commit"}, and
 * {@link io.github.markpollack.journal.storage.JournalStorage#loadEvents(String, String)} reads it
 * back as a {@code GitCommitEvent}. It is one of the {@link GitEvent}s.
 *
 * <p>{@link #of(String, String, String)} and the builder compute {@code shortSha} as the first
 * seven characters of the SHA; the canonical constructor takes it as given. The record copies
 * {@code filesChanged} into an unmodifiable list; a {@code null} list becomes an empty one, so a
 * JSON line without {@code filesChanged} reads back with no changed files, and a {@code null}
 * element throws {@link NullPointerException}. The record is immutable.
 *
 * @param timestamp when the commit was made
 * @param sha the full commit SHA
 * @param shortSha the abbreviated SHA, normally its first seven characters
 * @param message the commit message
 * @param branch the branch the commit was made on
 * @param filesChanged the paths of the changed files; may be empty
 * @param linesAdded the total lines added, or 0 if not known
 * @param linesRemoved the total lines removed, or 0 if not known
 */
public record GitCommitEvent(
        Instant timestamp,
        String sha,
        String shortSha,
        String message,
        String branch,
        List<String> filesChanged,
        int linesAdded,
        int linesRemoved
) implements GitEvent {

    /**
     * Creates a commit event, copying {@code filesChanged} into an unmodifiable list, or using an
     * empty list if it is {@code null}.
     *
     * @param timestamp when the commit was made
     * @param sha the full SHA
     * @param shortSha the abbreviated SHA
     * @param message the commit message
     * @param branch the branch
     * @param filesChanged the changed file paths
     * @param linesAdded the lines added
     * @param linesRemoved the lines removed
     * @throws NullPointerException if one of the elements of {@code filesChanged} is {@code null}
     */
    public GitCommitEvent {
        filesChanged = filesChanged == null ? List.of() : List.copyOf(filesChanged);
    }

    @Override
    public String type() {
        return "git_commit";
    }

    /**
     * Creates a commit event with the current time, no changed files and no line counts.
     *
     * @param sha the full commit SHA
     * @param message the commit message
     * @param branch the branch the commit was made on
     * @return the new event
     * @throws NullPointerException if {@code sha} is {@code null}
     */
    public static GitCommitEvent of(String sha, String message, String branch) {
        String shortSha = sha.length() > 7 ? sha.substring(0, 7) : sha;
        return new GitCommitEvent(Instant.now(), sha, shortSha, message, branch, List.of(), 0, 0);
    }

    /**
     * Returns a new builder. The SHA, message and branch must be set before
     * {@link Builder#build()}.
     *
     * @return the new builder
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * {@inheritDoc}
     *
     * <p>The keys are {@code sha}, {@code short_sha}, {@code message} and {@code branch}, then
     * {@code files_changed} and {@code files_count} when files are listed, and
     * {@code lines_added} and {@code lines_removed} when they are above 0.
     */
    @Override
    public Map<String, Object> toMap() {
        var map = new LinkedHashMap<String, Object>();
        map.put("sha", sha);
        map.put("short_sha", shortSha);
        map.put("message", message);
        map.put("branch", branch);
        if (!filesChanged.isEmpty()) {
            map.put("files_changed", filesChanged);
            map.put("files_count", filesChanged.size());
        }
        if (linesAdded > 0) {
            map.put("lines_added", linesAdded);
        }
        if (linesRemoved > 0) {
            map.put("lines_removed", linesRemoved);
        }
        return map;
    }

    /**
     * Builds a {@link GitCommitEvent}. The SHA, message and branch are required; the timestamp
     * defaults to the time {@link #build()} is called. See the {@link GitCommitEvent} components
     * for what each field means. A builder is not safe for use from several threads.
     */
    public static final class Builder {
        private Instant timestamp;
        private String sha;
        private String message;
        private String branch;
        private List<String> filesChanged = new ArrayList<>();
        private int linesAdded;
        private int linesRemoved;

        private Builder() {}

        /**
         * Sets when the commit was made.
         *
         * @param timestamp the time, or {@code null} for the time of {@link #build()}
         * @return this builder
         */
        public Builder timestamp(Instant timestamp) {
            this.timestamp = timestamp;
            return this;
        }

        /**
         * Sets the full commit SHA. Required.
         *
         * @param sha the SHA
         * @return this builder
         */
        public Builder sha(String sha) {
            this.sha = sha;
            return this;
        }

        /**
         * Sets the commit message. Required.
         *
         * @param message the message
         * @return this builder
         */
        public Builder message(String message) {
            this.message = message;
            return this;
        }

        /**
         * Sets the branch the commit was made on. Required.
         *
         * @param branch the branch
         * @return this builder
         */
        public Builder branch(String branch) {
            this.branch = branch;
            return this;
        }

        /**
         * Sets the changed file paths, replacing any added before. The list is copied.
         *
         * @param filesChanged the file paths
         * @return this builder
         * @throws NullPointerException if {@code filesChanged} is {@code null}
         */
        public Builder filesChanged(List<String> filesChanged) {
            this.filesChanged = new ArrayList<>(filesChanged);
            return this;
        }

        /**
         * Adds one changed file path.
         *
         * @param file the file path
         * @return this builder
         */
        public Builder addFile(String file) {
            this.filesChanged.add(file);
            return this;
        }

        /**
         * Sets the total lines added.
         *
         * @param linesAdded the lines added
         * @return this builder
         */
        public Builder linesAdded(int linesAdded) {
            this.linesAdded = linesAdded;
            return this;
        }

        /**
         * Sets the total lines removed.
         *
         * @param linesRemoved the lines removed
         * @return this builder
         */
        public Builder linesRemoved(int linesRemoved) {
            this.linesRemoved = linesRemoved;
            return this;
        }

        /**
         * Returns a new commit event with the values set so far. The short SHA is the first
         * seven characters of the SHA.
         *
         * @return the new event
         * @throws IllegalStateException if the SHA, message or branch is not set
         * @throws NullPointerException if a changed file path is {@code null}
         */
        public GitCommitEvent build() {
            if (sha == null) {
                throw new IllegalStateException("sha is required");
            }
            if (message == null) {
                throw new IllegalStateException("message is required");
            }
            if (branch == null) {
                throw new IllegalStateException("branch is required");
            }

            String shortSha = sha.length() > 7 ? sha.substring(0, 7) : sha;
            Instant ts = timestamp != null ? timestamp : Instant.now();

            return new GitCommitEvent(ts, sha, shortSha, message, branch, filesChanged, linesAdded, linesRemoved);
        }
    }
}
