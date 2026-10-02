package io.github.markpollack.journal;

import io.github.markpollack.journal.metric.Tags;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * A named group of runs that answer one question, such as {@code "implement-oauth"}; every
 * {@link Run} belongs to one experiment. You rarely build one yourself:
 * {@link RunBuilder#start()} creates the experiment and saves it the first time its ID is used.
 * Build one with {@link #create(String)} only to give a new experiment a name, description or
 * tags, and pass the builder to {@link Journal#experiment(String, Builder)}.
 *
 * <p>An experiment is identified by its ID alone: two experiments with the same ID are equal,
 * whatever their other fields. Once an experiment exists, its fields do not change; a builder
 * passed later for the same ID is ignored. File storage keeps it as {@code experiment.json} in
 * the experiment's directory, next to its runs; see
 * {@link io.github.markpollack.journal.storage.JsonFileStorage}.
 *
 * <p>An experiment is immutable and can be shared between threads. A {@link Builder} is not safe
 * for use from several threads.
 *
 * <p>Example (use the same ID in both places):
 * <pre>{@code
 * Experiment exp = Journal.experiment("implement-oauth",
 *     Experiment.create("implement-oauth")
 *         .description("Add OAuth2 authentication support")
 *         .tags(Tags.of("feature", "auth")));
 * }</pre>
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

    /**
     * Returns a builder for a new experiment with the given ID. The ID is checked when
     * {@link Builder#build()} is called, not here.
     *
     * @param id the experiment ID; it must not be {@code null} when the builder is built
     * @return a new builder
     */
    public static Builder create(String id) {
        return new Builder(id);
    }

    /**
     * Returns the ID that identifies this experiment. File storage also uses it as the name of the
     * experiment's directory.
     *
     * @return the ID, never {@code null}
     */
    @JsonProperty("id")
    public String id() {
        return id;
    }

    /**
     * Returns the name to show to people.
     *
     * @return the name, or the ID if no name was set; never {@code null}
     */
    @JsonProperty("name")
    public String name() {
        return name;
    }

    /**
     * Returns when the experiment was created.
     *
     * @return the creation time, by default the time the experiment was built; never
     *         {@code null}
     */
    @JsonProperty("createdAt")
    public Instant createdAt() {
        return createdAt;
    }

    /**
     * Returns the description.
     *
     * @return the description, or {@code null} if none was set
     */
    @JsonProperty("description")
    public String description() {
        return description;
    }

    /**
     * Returns the description as an {@link Optional}.
     *
     * @return the description, or an empty {@code Optional} if none was set
     */
    public Optional<String> descriptionOptional() {
        return Optional.ofNullable(description);
    }

    /**
     * Returns the tags.
     *
     * @return the tags, empty if none were set; never {@code null}
     */
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

    /**
     * Collects the fields of a new {@link Experiment}. Get one from
     * {@link Experiment#create(String)}; each setter returns this builder. Fields left unset get
     * defaults when {@link #build()} is called.
     */
    public static final class Builder {
        private final String id;
        private String name;
        private Instant createdAt;
        private String description;
        private Tags tags;

        private Builder(String id) {
            this.id = id;
        }

        String id() {
            return id;
        }

        /**
         * Sets the name to show to people.
         *
         * @param name the name, or {@code null} to use the ID
         * @return this builder
         */
        public Builder name(String name) {
            this.name = name;
            return this;
        }

        /**
         * Sets the creation time.
         *
         * @param createdAt the creation time, or {@code null} to use the time of
         *        {@link #build()}
         * @return this builder
         */
        public Builder createdAt(Instant createdAt) {
            this.createdAt = createdAt;
            return this;
        }

        /**
         * Sets a free-text description.
         *
         * @param description the description, or {@code null} for none
         * @return this builder
         */
        public Builder description(String description) {
            this.description = description;
            return this;
        }

        /**
         * Sets the tags.
         *
         * @param tags the tags, or {@code null} for none
         * @return this builder
         */
        public Builder tags(Tags tags) {
            this.tags = tags;
            return this;
        }

        /**
         * Returns a new experiment with the fields set so far. The builder can be used again, and
         * each call returns a new experiment.
         *
         * @return the new experiment
         * @throws NullPointerException if the ID given to {@link Experiment#create(String)} is
         *         {@code null}
         */
        public Experiment build() {
            return new Experiment(this);
        }
    }
}
