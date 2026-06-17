package io.github.markpollack.journal.derived;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

import java.time.Instant;
import java.util.Map;

/**
 * Base interface for <strong>derived analysis events</strong> — facts <em>inferred after a run
 * completes</em> (cost attribution, outcomes, Markov state, judge verdicts), as opposed to the
 * immutable execution history modeled by {@code JournalEvent}.
 *
 * <p>
 * The distinction is deliberate and physical: execution events live in {@code events.jsonl}
 * (what happened); derived events live in the {@code analysis.jsonl} sidecar (what we learned
 * later). A tool call <em>happened</em>; a cost attribution was <em>inferred</em> — they must not
 * look equivalent. This is the Path-A counterpart of the trace's derived {@code step_cost} line
 * (R2.4); see {@code plans/design.md} "Derived analysis stream".
 *
 * <p>
 * Both streams are joined by stable ids — {@code runId} and {@code stepId} (the tool_use id from
 * R2.3) — so loaders can assemble one analytical view without conflating the two record kinds.
 * This hierarchy is intentionally separate from {@code JournalEvent}; if a single physical stream
 * proves better later, the two can be merged, but mixing them early is hard to undo.
 *
 * <p>
 * Implemented: {@link StepCostEvent} (R2.4/R2.10), {@link StepOutcomeEvent} (R2.6). Planned
 * siblings (not yet built): {@code MarkovStateEvent}, {@code JudgeVerdictEvent}.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "@type")
@JsonSubTypes({
        @JsonSubTypes.Type(value = StepCostEvent.class, name = "step_cost"),
        @JsonSubTypes.Type(value = StepOutcomeEvent.class, name = "step_outcome")
})
public interface DerivedEvent {

    /** When the analysis was computed (post-run), not when the underlying step happened. */
    Instant timestamp();

    /** Derived-event type name (e.g. "step_cost"). */
    String type();

    /** The run this analysis belongs to — primary join key. */
    String runId();

    /** The execution step this analysis attaches to (the tool_use id); null for run-level. */
    String stepId();

    /** Attributes as a map for serialization-agnostic consumers. */
    Map<String, Object> toMap();
}
