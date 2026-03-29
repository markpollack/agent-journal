package io.github.markpollack.journal;

import io.github.markpollack.journal.metric.Tags;

import java.util.Objects;

/**
 * Builder for creating and configuring runs.
 *
 * <p>Example:
 * <pre>{@code
 * Run run = RunBuilder.forExperiment("my-experiment")
 *     .name("attempt-1")
 *     .config("model", "claude-opus-4.5")
 *     .config("temperature", 0.7)
 *     .tag("type", "test")
 *     .agent("code-reviewer")
 *     .start();
 * }</pre>
 *
 * <p>For retry chains, link runs together:
 * <pre>{@code
 * Run retry = RunBuilder.forExperiment("my-experiment")
 *     .previousRun(failedRun.id())
 *     .start();
 * }</pre>
 *
 * <p>For multi-agent hierarchies:
 * <pre>{@code
 * Run childRun = RunBuilder.forExperiment("my-experiment")
 *     .parentRun(supervisorRun.id())
 *     .agent("sub-agent")
 *     .start();
 * }</pre>
 */
public final class RunBuilder {

    private final String experimentId;
    private String taskId;
    private Config config = Config.empty();
    private Tags tags = Tags.empty();
    private String name;
    private String previousRunId;     // For linking sequential attempts
    private String parentRunId;       // For sub-runs (multi-agent)
    private String agentId;           // Agent identifier
    private String repositoryPath;    // Git repository path

    private RunBuilder(String experimentId) {
        this.experimentId = Objects.requireNonNull(experimentId, "experimentId is required");
    }

    /**
     * Creates a new RunBuilder for the given experiment.
     *
     * @param experimentId the experiment identifier
     * @return a new RunBuilder
     */
    public static RunBuilder forExperiment(String experimentId) {
        return new RunBuilder(experimentId);
    }

    /**
     * Sets the task identifier (e.g., issue number, ticket ID).
     *
     * @param taskId the task identifier
     * @return this builder
     */
    public RunBuilder task(String taskId) {
        this.taskId = taskId;
        return this;
    }

    /**
     * Sets the run configuration.
     *
     * @param config the configuration
     * @return this builder
     */
    public RunBuilder config(Config config) {
        this.config = config != null ? config : Config.empty();
        return this;
    }

    /**
     * Adds a configuration value.
     *
     * @param key the configuration key
     * @param value the configuration value
     * @return this builder
     */
    public RunBuilder config(String key, Object value) {
        this.config = this.config.with(key, value);
        return this;
    }

    /**
     * Sets run tags.
     *
     * @param tags the tags
     * @return this builder
     */
    public RunBuilder tags(Tags tags) {
        this.tags = tags != null ? tags : Tags.empty();
        return this;
    }

    /**
     * Adds a tag.
     *
     * @param key the tag key
     * @param value the tag value
     * @return this builder
     */
    public RunBuilder tag(String key, String value) {
        this.tags = this.tags.and(key, value);
        return this;
    }

    /**
     * Sets a human-readable name for the run.
     *
     * @param name the run name
     * @return this builder
     */
    public RunBuilder name(String name) {
        this.name = name;
        return this;
    }

    /**
     * Links this run to a previous attempt.
     * Essential for tracking sequential runs on the same task.
     *
     * <p>Example: Run 2 is a retry after Run 1 failed.
     *
     * @param runId the ID of the previous run attempt
     * @return this builder
     */
    public RunBuilder previousRun(String runId) {
        this.previousRunId = runId;
        return this;
    }

    /**
     * Links this run as a sub-run of a parent.
     * Used for multi-agent workflows where a supervisor spawns child agents.
     *
     * @param runId the ID of the parent run
     * @return this builder
     */
    public RunBuilder parentRun(String runId) {
        this.parentRunId = runId;
        return this;
    }

    /**
     * Sets the agent identifier.
     * Examples: "claude-sdk-sync", "code-reviewer", "test-runner"
     *
     * @param agentId the agent identifier
     * @return this builder
     */
    public RunBuilder agent(String agentId) {
        this.agentId = agentId;
        return this;
    }

    /**
     * Sets the git repository path for this run.
     * Enables automatic git state capture at run start/end.
     *
     * @param path the path to the git repository
     * @return this builder
     */
    public RunBuilder repository(String path) {
        this.repositoryPath = path;
        return this;
    }

    /**
     * Creates and starts the run.
     *
     * <p>The run is persisted to the configured storage backend.
     * The experiment is automatically created if it doesn't exist.
     *
     * @return the started run
     */
    public Run start() {
        Experiment experiment = ExperimentRegistry.getOrCreate(experimentId);
        return new DefaultRun(this, experiment, JournalContext.getStorage());
    }

    // Package-private getters for DefaultRun
    String experimentId() { return experimentId; }
    String taskId() { return taskId; }
    Config config() { return config; }
    Tags tags() { return tags; }
    String name() { return name; }
    String previousRunId() { return previousRunId; }
    String parentRunId() { return parentRunId; }
    String agentId() { return agentId; }
    String repositoryPath() { return repositoryPath; }
}
