package io.github.markpollack.journal;

import io.github.markpollack.journal.call.CallTracker;
import io.github.markpollack.journal.derived.DerivedEvent;
import io.github.markpollack.journal.event.JournalEvent;
import io.github.markpollack.journal.metric.MetricRegistry;
import io.github.markpollack.journal.metric.Tags;

import java.util.Map;

/**
 * One recorded execution of an agent, such as a single attempt at a task. Get one from
 * {@link Journal#run(String)} and {@link RunBuilder#start()}, open it in a try-with-resources
 * block, and log what happens: events such as {@code LLMCallEvent} and {@code ToolCallEvent},
 * metrics, artifacts and summary values. A run belongs to one {@link Experiment} and writes to the
 * storage that was configured on {@link Journal} when it started.
 *
 * <p>A run holds:
 * <ul>
 *   <li>its {@link Config}: the inputs, fixed when the run starts
 *   <li>events: what happened, appended in order and written to storage at once
 *   <li>derived events: conclusions computed from the events, such as the cost of each step,
 *       kept in a separate stream
 *   <li>metrics: counters, timers and gauges in a {@link MetricRegistry}
 *   <li>a {@link Summary}: the outputs, where the last value for a key wins
 *   <li>artifacts: named content such as a plan or a report
 * </ul>
 *
 * <p>A new run has status {@link RunStatus#RUNNING}. {@link #close()} ends it as
 * {@link RunStatus#FINISHED} unless {@link #fail(Throwable)} or {@link #finish(RunStatus)} ended
 * it first. An exception that leaves the try block does not mark the run as failed, because
 * {@code close()} cannot see it: catch the exception inside the block and call
 * {@link #fail(Throwable)}. Once a run has ended, the {@code log} methods and
 * {@link #setSummary(String, Object)} throw {@link IllegalStateException}.
 *
 * <pre>{@code
 * try (Run run = Journal.run("my-experiment")
 *         .config("model", "claude-opus-4.5")
 *         .start()) {
 *     try {
 *         run.logEvent(LLMCallEvent.of("claude-opus-4.5", 1200, 450, 0.023));
 *         run.logMetric("tokens.total", 1650);
 *         run.setSummary("filesChanged", 5);
 *     } catch (RuntimeException e) {
 *         run.fail(e);
 *         throw e;
 *     }
 * } // close() ends the run as FINISHED unless fail() was called
 * }</pre>
 *
 * <p>The run returned by {@link RunBuilder#start()} may be used from several threads. File
 * storage expects only one process to write each run.
 */
public interface Run extends AutoCloseable {

    /**
     * Returns the ID of this run, which names its record in storage.
     *
     * @return the run ID, never {@code null}
     */
    String id();

    /**
     * Returns the human-readable name of this run.
     *
     * @return the name set on the builder, or a generated one such as {@code run-1a2b3c4d}
     */
    String name();

    /**
     * Returns the experiment this run belongs to.
     *
     * @return the experiment
     */
    Experiment experiment();

    /**
     * Returns the current status of this run.
     *
     * @return the status, {@link RunStatus#RUNNING} until the run ends
     */
    RunStatus status();

    /**
     * Returns the inputs this run was started with.
     *
     * @return the config, which does not change after the run starts
     */
    Config config();

    /**
     * Returns the summary values set so far.
     *
     * @return the current summary, an immutable copy
     */
    Summary summary();

    /**
     * Returns the tags set on the builder.
     *
     * @return the tags
     */
    Tags tags();

    /**
     * Returns the ID of the agent that performed this run.
     *
     * @return the agent ID, or {@code null} if none was set
     */
    String agentId();

    /**
     * Returns the ID of the earlier run that this run retries.
     *
     * @return the previous run's ID, or {@code null} if none was set
     */
    String previousRunId();

    /**
     * Returns the ID of the run that this run is part of.
     *
     * @return the parent run's ID, or {@code null} if none was set
     */
    String parentRunId();

    /**
     * Appends an event to this run's event log and writes it to storage at once.
     *
     * @param event the event to log
     * @throws IllegalStateException if the run has ended
     */
    void logEvent(JournalEvent event);

    /**
     * Appends a derived event to this run's analysis log. A derived event is a conclusion
     * computed from the run, such as a
     * {@link io.github.markpollack.journal.derived.StepCostEvent} giving one step's share of the
     * cost. The analysis log is kept apart from the event log, so a computed value is never
     * mistaken for something that happened; file storage writes it to {@code analysis.jsonl}, not
     * {@code events.jsonl}. Derived events go to storage only and are not kept with the run's
     * events in memory.
     *
     * @param event the derived event to log
     * @throws IllegalStateException if the run has ended
     */
    void logDerivedEvent(DerivedEvent event);

    /**
     * Records a metric value without tags. Sets the gauge of that name in {@link #metrics()} and
     * appends a {@code MetricEvent} to the event log.
     *
     * @param name the metric name
     * @param value the value
     * @throws IllegalStateException if the run has ended
     */
    void logMetric(String name, double value);

    /**
     * Records a metric value with tags. Sets the gauge of that name and tags in
     * {@link #metrics()} and appends a {@code MetricEvent} to the event log.
     *
     * @param name the metric name
     * @param value the value
     * @param tags the tags for the metric
     * @throws IllegalStateException if the run has ended
     */
    void logMetric(String name, double value, Tags tags);

    /**
     * Saves text content as an artifact of this run, such as a plan or a final report. The text is
     * encoded as UTF-8, whatever the platform's default charset.
     *
     * @param name the artifact name, such as {@code "plan.md"}; file storage uses it as the file
     *        name
     * @param content the text to save
     * @throws IllegalStateException if the run has ended
     */
    void logArtifact(String name, String content);

    /**
     * Saves binary content with metadata as an artifact of this run.
     *
     * @param name the artifact name; file storage uses it as the file name
     * @param content the bytes to save
     * @param metadata information about the artifact
     * @throws IllegalStateException if the run has ended
     */
    void logArtifact(String name, byte[] content, Map<String, Object> metadata);

    /**
     * Sets a summary value, replacing any earlier value for the key, and saves the run record so
     * the value is in storage at once. Use the summary for the run's outputs, such as
     * {@code filesChanged}.
     *
     * @param key the key
     * @param value the value
     * @throws NullPointerException if {@code key} or {@code value} is {@code null}
     * @throws IllegalStateException if the run has ended
     */
    void setSummary(String key, Object value);

    /**
     * Returns this run's metric registry, for counters, timers and gauges you update directly.
     *
     * @return the metric registry
     */
    MetricRegistry metrics();

    /**
     * Returns this run's call tracker, which records nested named operations and how long they
     * took. The calls are kept in memory only.
     *
     * <p>Example:
     * <pre>{@code
     * try (Call loop = run.calls().startCall("agent-loop")) {
     *     try (Call turn = loop.child("turn-1")) {
     *         // Do work
     *     }
     * }
     * }</pre>
     *
     * @return the call tracker
     */
    CallTracker calls();

    /**
     * Ends this run as {@link RunStatus#FAILED}, records the error in the summary
     * ({@code success=false}, {@code error} and {@code errorType}) and saves the run. An exception
     * with no message, such as many {@link NullPointerException}s, gets no {@code error} value,
     * only {@code success} and {@code errorType}. Does nothing
     * if the run has already ended. It is not called for you: when an exception leaves a
     * try-with-resources block, the run is already closed, so call this inside the block.
     *
     * @param error the cause of the failure
     */
    void fail(Throwable error);

    /**
     * Ends this run with the given status and saves it. If the status is
     * {@link RunStatus#FINISHED} and the summary has no {@code success} key, sets
     * {@code success=true}. Does nothing if the run has already ended.
     *
     * @param status the final status, such as {@link RunStatus#FINISHED} or
     *        {@link RunStatus#CRASHED}
     * @throws IllegalArgumentException if {@code status} is not terminal, that is
     *         {@link RunStatus#INIT} or {@link RunStatus#RUNNING}; the run is left as it was
     */
    void finish(RunStatus status);

    /**
     * Ends this run as {@link RunStatus#FINISHED} and saves it, unless it has already ended.
     * Try-with-resources calls this at the end of the block.
     */
    @Override
    void close();
}
