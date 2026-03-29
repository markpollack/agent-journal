package io.github.markpollack.journal.storage;

import io.github.markpollack.journal.Config;
import io.github.markpollack.journal.RunStatus;
import io.github.markpollack.journal.Summary;
import io.github.markpollack.journal.metric.Tags;

import java.time.Instant;
import java.util.Objects;

/**
 * Serializable representation of run metadata.
 *
 * <p>This record captures all the persistent state of a run for storage.
 * Events and artifacts are stored separately.
 *
 * <p>Example JSON:
 * <pre>{@code
 * {
 *   "id": "run-abc123",
 *   "experimentId": "implement-oauth",
 *   "name": "attempt-1",
 *   "status": "FINISHED",
 *   "config": {"model": "claude-opus"},
 *   "summary": {"success": true},
 *   ...
 * }
 * }</pre>
 */
public record RunData(
        String id,
        String experimentId,
        String name,
        RunStatus status,
        Config config,
        Summary summary,
        Tags tags,
        String agentId,
        String previousRunId,
        String parentRunId,
        Instant startTime,
        Instant endTime,
        String errorMessage,
        String errorType
) {
    public RunData {
        Objects.requireNonNull(id, "id is required");
        Objects.requireNonNull(experimentId, "experimentId is required");
        Objects.requireNonNull(status, "status is required");
        config = config != null ? config : Config.empty();
        summary = summary != null ? summary : Summary.empty();
        tags = tags != null ? tags : Tags.empty();
    }

    /**
     * Creates RunData from run components.
     *
     * <p>This is the preferred factory method when creating RunData
     * from an active Run object.
     */
    public static RunData fromRun(
            String id,
            String experimentId,
            String name,
            RunStatus status,
            Config config,
            Summary summary,
            Tags tags,
            String agentId,
            String previousRunId,
            String parentRunId,
            Instant startTime,
            Instant endTime,
            String errorMessage,
            String errorType
    ) {
        return new RunData(
                id, experimentId, name, status, config, summary, tags,
                agentId, previousRunId, parentRunId, startTime, endTime,
                errorMessage, errorType
        );
    }

    /**
     * Creates a builder for RunData.
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Builder for RunData.
     */
    public static final class Builder {
        private String id;
        private String experimentId;
        private String name;
        private RunStatus status = RunStatus.INIT;
        private Config config = Config.empty();
        private Summary summary = Summary.empty();
        private Tags tags = Tags.empty();
        private String agentId;
        private String previousRunId;
        private String parentRunId;
        private Instant startTime;
        private Instant endTime;
        private String errorMessage;
        private String errorType;

        public Builder id(String id) {
            this.id = id;
            return this;
        }

        public Builder experimentId(String experimentId) {
            this.experimentId = experimentId;
            return this;
        }

        public Builder name(String name) {
            this.name = name;
            return this;
        }

        public Builder status(RunStatus status) {
            this.status = status;
            return this;
        }

        public Builder config(Config config) {
            this.config = config;
            return this;
        }

        public Builder summary(Summary summary) {
            this.summary = summary;
            return this;
        }

        public Builder tags(Tags tags) {
            this.tags = tags;
            return this;
        }

        public Builder agentId(String agentId) {
            this.agentId = agentId;
            return this;
        }

        public Builder previousRunId(String previousRunId) {
            this.previousRunId = previousRunId;
            return this;
        }

        public Builder parentRunId(String parentRunId) {
            this.parentRunId = parentRunId;
            return this;
        }

        public Builder startTime(Instant startTime) {
            this.startTime = startTime;
            return this;
        }

        public Builder endTime(Instant endTime) {
            this.endTime = endTime;
            return this;
        }

        public Builder errorMessage(String errorMessage) {
            this.errorMessage = errorMessage;
            return this;
        }

        public Builder errorType(String errorType) {
            this.errorType = errorType;
            return this;
        }

        public RunData build() {
            return new RunData(
                    id, experimentId, name, status, config, summary, tags,
                    agentId, previousRunId, parentRunId, startTime, endTime,
                    errorMessage, errorType
            );
        }
    }
}
