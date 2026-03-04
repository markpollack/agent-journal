/*
 * Copyright (c) 2026 Mark Pollack
 *
 * Licensed under the Business Source License 1.1 (BSL).
 * See the LICENSE file in the repository root for the full license text.
 */

package io.github.markpollack.journal.event;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Records a git commit made by an agent.
 *
 * @param timestamp when the commit was made
 * @param sha full commit SHA
 * @param shortSha short SHA (7 chars)
 * @param message commit message
 * @param branch branch committed to
 * @param filesChanged list of file paths changed
 * @param linesAdded total lines added
 * @param linesRemoved total lines removed
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

    /** Compact constructor for defensive copy. */
    public GitCommitEvent {
        filesChanged = List.copyOf(filesChanged);
    }

    @Override
    public String type() {
        return "git_commit";
    }

    /**
     * Creates a simple commit event with minimal info.
     *
     * @param sha full commit SHA
     * @param message commit message
     * @param branch branch name
     * @return a new GitCommitEvent
     */
    public static GitCommitEvent of(String sha, String message, String branch) {
        String shortSha = sha.length() > 7 ? sha.substring(0, 7) : sha;
        return new GitCommitEvent(Instant.now(), sha, shortSha, message, branch, List.of(), 0, 0);
    }

    /** Returns a builder for constructing commit events. */
    public static Builder builder() {
        return new Builder();
    }

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

    /** Builder for GitCommitEvent. */
    public static final class Builder {
        private Instant timestamp;
        private String sha;
        private String message;
        private String branch;
        private List<String> filesChanged = new ArrayList<>();
        private int linesAdded;
        private int linesRemoved;

        private Builder() {}

        public Builder timestamp(Instant timestamp) {
            this.timestamp = timestamp;
            return this;
        }

        public Builder sha(String sha) {
            this.sha = sha;
            return this;
        }

        public Builder message(String message) {
            this.message = message;
            return this;
        }

        public Builder branch(String branch) {
            this.branch = branch;
            return this;
        }

        public Builder filesChanged(List<String> filesChanged) {
            this.filesChanged = new ArrayList<>(filesChanged);
            return this;
        }

        public Builder addFile(String file) {
            this.filesChanged.add(file);
            return this;
        }

        public Builder linesAdded(int linesAdded) {
            this.linesAdded = linesAdded;
            return this;
        }

        public Builder linesRemoved(int linesRemoved) {
            this.linesRemoved = linesRemoved;
            return this;
        }

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
