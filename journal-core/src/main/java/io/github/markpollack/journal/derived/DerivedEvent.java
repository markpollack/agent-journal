package io.github.markpollack.journal.derived;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

import java.time.Instant;
import java.util.Map;

/**
 * A conclusion computed about a run, such as one step's share of the cost, as opposed to an
 * event that records something that happened. Log one with
 * {@link io.github.markpollack.journal.Run#logDerivedEvent(DerivedEvent)} while the run is open,
 * or append it with {@link io.github.markpollack.journal.storage.JournalStorage#appendDerivedEvent}
 * later; read them back with
 * {@link io.github.markpollack.journal.storage.JournalStorage#loadDerivedEvents}. There are two
 * kinds: {@link StepCostEvent}, which the capture modules log for each step, and
 * {@link StepOutcomeEvent}, which carries outcome values the caller supplies.
 *
 * <p>Derived events are kept apart from the run's events
 * ({@link io.github.markpollack.journal.event.JournalEvent}), so a computed value is never
 * mistaken for a recorded fact: file storage writes them to {@code analysis.jsonl}, not
 * {@code events.jsonl}. They point back to what they describe by {@link #runId()} and
 * {@link #stepId()}; for a tool step the step ID is the
 * {@link io.github.markpollack.journal.event.ToolCallEvent#id()} of the matching tool call event.
 *
 * <p>File storage writes each derived event as JSON with its record components and an
 * {@code @type} name: {@code step_cost} or {@code step_outcome}. It does not use
 * {@link #toMap()}.
 *
 * <p>Implementations other than the two built-in records are not supported by file storage:
 * there is no way to register a new {@code @type} name, so
 * {@link io.github.markpollack.journal.storage.JsonFileStorage} rejects one at append with
 * {@link IllegalArgumentException}, and writes nothing. They work with
 * {@link io.github.markpollack.journal.storage.InMemoryStorage}. An implementation's
 * {@link #timestamp()} must not be {@code null}.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "@type")
@JsonSubTypes({
        @JsonSubTypes.Type(value = StepCostEvent.class, name = "step_cost"),
        @JsonSubTypes.Type(value = StepOutcomeEvent.class, name = "step_outcome")
})
public interface DerivedEvent {

    /**
     * Returns when this conclusion was computed, not when the step it describes ran.
     *
     * @return the time of the analysis
     */
    Instant timestamp();

    /**
     * Returns the name of this kind of derived event, the same as its {@code @type} name in JSON.
     *
     * @return the type name, such as {@code "step_cost"} or {@code "step_outcome"}
     */
    String type();

    /**
     * Returns the ID of the run this conclusion is about.
     *
     * @return the run ID
     */
    String runId();

    /**
     * Returns the ID of the step this conclusion is about. For a tool step it is the ID of the
     * matching {@link io.github.markpollack.journal.event.ToolCallEvent}.
     *
     * @return the step ID, or {@code null} for a conclusion about the whole run
     */
    String stepId();

    /**
     * Returns this event's fields as a map, for code that does not want to depend on the record
     * types. In the built-in records, the map holds {@code @type} and leaves out fields that are
     * {@code null}.
     *
     * @return the fields by name
     */
    Map<String, Object> toMap();
}
