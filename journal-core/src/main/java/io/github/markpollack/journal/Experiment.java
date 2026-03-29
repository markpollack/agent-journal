package io.github.markpollack.journal;

import io.github.markpollack.journal.metric.Tags;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * Groups related runs for a common goal or task.
 *
 * <p>An experiment represents a logical grouping of execution attempts,
 * such as "implement-oauth" or "sync-anthropic-sdk-v1.2". All runs within
 * an experiment share a common objective.
 *
 * <p>Example:
 * <pre>{@code
 * Experiment exp = Experiment.create("implement-oauth")
 *     .description("Add OAuth2 authentication support")
 *     .tags(Tags.of("feature", "auth"))
 *     .build();
 * }</pre>
 *
 * <p>Storage layout:
 * <pre>
 * .agent-journal/experiments/{id}/
 * ├── experiment.json
 * └── runs/
 *     └── {runId}/
 * </pre>
 */
public final class Experiment {

    private final String id;
    private final String name;
    private final Instant createdAt;
    private final String description;
    private final Tags tags;

    private Experiment(Builder builder) {
        this.id = Objects.requireNonNull(builder.id, "id is required");
        this.name = builder.name != null ? builder.name : builder.id;
        this.createdAt = builder.createdAt != null ? builder.createdAt : Instant.now();
        this.description = builder.description;
        this.tags = builder.tags != null ? builder.tags : Tags.empty();
    }

    @JsonCreator
    private Experiment(
            @JsonProperty("id") String id,
            @JsonProperty("name") String name,
            @JsonProperty("createdAt") Instant createdAt,
            @JsonProperty("description") String description,
            @JsonProperty("tags") Tags tags) {
        this.id = Objects.requireNonNull(id, "id is required");
        this.name = name != null ? name : id;
        this.createdAt = createdAt != null ? createdAt : Instant.now();
        this.description = description;
        this.tags = tags != null ? tags : Tags.empty();
    }

    /** Creates a new experiment builder with the given ID. */
    public static Builder create(String id) {
        return new Builder(id);
    }

    /** Returns the unique experiment identifier. */
    @JsonProperty("id")
    public String id() {
        return id;
    }

    /** Returns the human-readable experiment name. */
    @JsonProperty("name")
    public String name() {
        return name;
    }

    /** Returns when the experiment was created. */
    @JsonProperty("createdAt")
    public Instant createdAt() {
        return createdAt;
    }

    /** Returns the experiment description, if set. */
    @JsonProperty("description")
    public String description() {
        return description;
    }

    /** Returns the experiment description as Optional. */
    public Optional<String> descriptionOptional() {
        return Optional.ofNullable(description);
    }

    /** Returns the experiment tags. */
    @JsonProperty("tags")
    public Tags tags() {
        return tags;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Experiment that = (Experiment) o;
        return id.equals(that.id);
    }

    @Override
    public int hashCode() {
        return id.hashCode();
    }

    @Override
    public String toString() {
        return "Experiment{id='" + id + "', name='" + name + "'}";
    }

    /** Builder for Experiment. */
    public static final class Builder {
        private final String id;
        private String name;
        private Instant createdAt;
        private String description;
        private Tags tags;

        private Builder(String id) {
            this.id = id;
        }

        /** Sets the human-readable name. Defaults to ID if not set. */
        public Builder name(String name) {
            this.name = name;
            return this;
        }

        /** Sets the creation timestamp. Defaults to now. */
        public Builder createdAt(Instant createdAt) {
            this.createdAt = createdAt;
            return this;
        }

        /** Sets the experiment description. */
        public Builder description(String description) {
            this.description = description;
            return this;
        }

        /** Sets the experiment tags. */
        public Builder tags(Tags tags) {
            this.tags = tags;
            return this;
        }

        /** Builds the experiment. */
        public Experiment build() {
            return new Experiment(this);
        }
    }
}
