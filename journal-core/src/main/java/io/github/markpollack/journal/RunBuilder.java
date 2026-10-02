package io.github.markpollack.journal;

import io.github.markpollack.journal.metric.Tags;

import java.util.Objects;

/**
 * Sets up a new {@link Run} and starts it. Get one from {@link Journal#run(String)}, set the run's
 * name, agent, config and tags, then call {@link #start()}, which saves the run with status
 * {@link RunStatus#RUNNING} and returns it. Use {@link #previousRun(String)} to link a retry to the
 * attempt before it, and {@link #parentRun(String)} to link a sub-agent's run to the run that
 * started it.
 *
 * <p>The {@link Config} holds the run's inputs, such as the model, and cannot change once the run
 * has started. {@link Tags} are string labels for finding runs later. The run record keeps the
 * name, agent ID, config, tags and the linked run IDs. {@link #task(String)} and
 * {@link #repository(String)} accept a value, but the run does not keep it: nothing reads it and
 * it is not saved.
 *
 * <p>One builder can start several runs. Each {@link #start()} makes a new run, with a new ID,
 * from the builder's settings at that moment; later changes to the builder do not affect runs
 * already started. A builder is not safe for use from several threads.
 *
 * <p>Example:
 * <pre>{@code
 * try (Run run = Journal.run("my-experiment")
 *         .name("attempt-1")
 *         .agent("code-reviewer")
 *         .config("model", "claude-opus-4.5")
 *         .config("temperature", 0.7)
 *         .tag("type", "test")
 *         .start()) {
 *     // log events
 * }
 *
 * // A retry, linked to the attempt that failed
 * Run retry = Journal.run("my-experiment").previousRun(failedRun.id()).start();
 *
 * // A sub-agent's run, linked to its supervisor's run
 * Run child = Journal.run("my-experiment")
 *         .parentRun(supervisorRun.id())
 *         .agent("sub-agent")
 *         .start();
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
     * Returns a builder for a new run in the given experiment. {@link Journal#run(String)} does the
     * same.
     *
     * @param experimentId the ID of the experiment the run belongs to, such as
     *        {@code "implement-oauth"}
     * @return a new builder with no settings
     * @throws NullPointerException if {@code experimentId} is {@code null}
     */
    public static RunBuilder forExperiment(String experimentId) {
        return new RunBuilder(experimentId);
    }

    /**
     * Accepts the ID of the task the run works on, such as an issue number, but does not keep it:
     * the run does not read the value and it is not saved, so it cannot be read back. To record
     * the task, put it in the config or a tag, for example {@code config("taskId", id)}.
     *
     * @param taskId the task ID
     * @return this builder
     */
    public RunBuilder task(String taskId) {
        this.taskId = taskId;
        return this;
    }

    /**
     * Replaces the config with the given one, dropping any values set before.
     *
     * @param config the run's inputs; {@code null} gives an empty config
     * @return this builder
     */
    public RunBuilder config(Config config) {
        this.config = config != null ? config : Config.empty();
        return this;
    }

    /**
     * Adds one value to the config, replacing any earlier value for the key.
     *
     * @param key the name of the input, such as {@code "model"}
     * @param value the value of the input
     * @return this builder
     * @throws NullPointerException if {@code key} or {@code value} is {@code null}
     */
    public RunBuilder config(String key, Object value) {
        this.config = this.config.with(key, value);
        return this;
    }

    /**
     * Replaces the tags with the given ones, dropping any tags set before.
     *
     * @param tags the run's tags; {@code null} gives no tags
     * @return this builder
     */
    public RunBuilder tags(Tags tags) {
        this.tags = tags != null ? tags : Tags.empty();
        return this;
    }

    /**
     * Adds one tag, replacing any earlier value for the key.
     *
     * @param key the tag's name; must not be {@code null}
     * @param value the tag's value; must not be {@code null}
     * @return this builder
     * @throws NullPointerException if {@code key} or {@code value} is {@code null}
     */
    public RunBuilder tag(String key, String value) {
        this.tags = this.tags.and(key, value);
        return this;
    }

    /**
     * Sets a name for people to read, such as {@code "attempt-1"}. It need not be unique; the run's
     * ID identifies it. Without a name, the run is named {@code run-} followed by the first eight
     * characters of its ID.
     *
     * @param name the run's name, or {@code null} for the default
     * @return this builder
     */
    public RunBuilder name(String name) {
        this.name = name;
        return this;
    }

    /**
     * Links this run to an earlier attempt at the same task, such as the failed run it retries.
     * The ID is saved in the run record; the library does not check that a run with that ID
     * exists.
     *
     * @param runId the ID of the earlier run, or {@code null} for none
     * @return this builder
     */
    public RunBuilder previousRun(String runId) {
        this.previousRunId = runId;
        return this;
    }

    /**
     * Marks this run as part of another run, such as the run of a sub-agent that a supervisor agent
     * started. The ID is saved in the run record; the library does not check that a run with that
     * ID exists.
     *
     * @param runId the ID of the parent run, or {@code null} for none
     * @return this builder
     */
    public RunBuilder parentRun(String runId) {
        this.parentRunId = runId;
        return this;
    }

    /**
     * Sets the ID of the agent that does the work, such as {@code "code-reviewer"} or
     * {@code "claude-code"}. It is saved in the run record.
     *
     * @param agentId the agent ID, or {@code null} for none
     * @return this builder
     */
    public RunBuilder agent(String agentId) {
        this.agentId = agentId;
        return this;
    }

    /**
     * Accepts the path of the git repository the run works in, but does not keep it: the run does
     * not read the value, it is not saved, and no git state is captured. To record the path, put
     * it in the config, for example {@code config("repository", path)}.
     *
     * @param path the repository path
     * @return this builder
     */
    public RunBuilder repository(String path) {
        this.repositoryPath = path;
        return this;
    }

    /**
     * Starts a new run and returns it with status {@link RunStatus#RUNNING}. It first gets the
     * run's {@link Experiment}, creating it with default settings if it does not exist, and saves
     * it to the current storage if that storage does not have it yet. Then it saves the run record to the storage that {@link Journal#storage()} returns
     * now; the run writes to that storage until it ends, even if {@link Journal#configure} is
     * called later.
     *
     * @return the new run, never {@code null}
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
