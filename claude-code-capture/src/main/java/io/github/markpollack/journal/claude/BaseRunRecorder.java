package io.github.markpollack.journal.claude;

import io.github.markpollack.journal.Run;
import io.github.markpollack.journal.RunStatus;
import io.github.markpollack.journal.derived.StepCostEvent;
import io.github.markpollack.journal.event.CostBreakdown;
import io.github.markpollack.journal.event.CustomEvent;
import io.github.markpollack.journal.event.LLMCallEvent;
import io.github.markpollack.journal.event.StateChangeEvent;
import io.github.markpollack.journal.event.StopReason;
import io.github.markpollack.journal.event.TimingInfo;
import io.github.markpollack.journal.event.ToolCallEvent;
import io.github.markpollack.journal.trace.JournalStep;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The phase-logging logic that Claude Code's {@link RunRecorder} inherits. Use
 * {@code RunRecorder} rather than this class: it adds the check that the storage keeps derived
 * events, and it ends the run. Extend this class only to build your own recorder for Claude Code
 * {@link PhaseCapture}s, for example one with extra methods for your own events.
 *
 * <p>{@link #recordPhase(PhaseCapture)} logs one phase as events on the run and its per-step
 * costs as derived events; its comment lists them. {@link #failRun(Throwable)} and
 * {@link #failRun()} end the run as failed. Nothing in this class ends a run as finished, so a
 * subclass or its caller must do that, for example with {@link Run#close()}.
 *
 * <p>Subclasses must set {@link #currentRun} before {@code recordPhase} is called, normally in
 * their constructor; this class has no constructor that takes a run. The other protected fields
 * hold the name of the previous phase and the number of derived events logged so far, which
 * {@code RunRecorder} uses for its storage check.
 *
 * <p>A recorder serves one run and is not safe for use from several threads.
 */
public abstract class BaseRunRecorder {

    /**
     * The run that {@link #recordPhase(PhaseCapture)} and the {@code failRun} methods act on.
     * Subclasses set it, normally in their constructor; it is {@code null} until then.
     */
    protected Run currentRun;

    /**
     * The name of the last phase recorded, which the next phase's {@link StateChangeEvent} starts
     * from. It is {@code "init"} before the first phase.
     */
    protected String previousPhase = "init";

    /**
     * The number of {@link StepCostEvent}s that {@link #recordPhase(PhaseCapture)} has logged so
     * far, over all phases. {@link RunRecorder} reads it to decide whether the storage must keep
     * derived events.
     */
    protected int derivedEventsEmitted = 0;

    /**
     * Logs one phase as events on the run, then logs its per-step costs as derived events.
     *
     * <p>It logs, in order:
     * <ul>
     *   <li>a {@link StateChangeEvent} from the previous phase to this one; the first phase starts
     *       from {@code "init"}
     *   <li>a {@code prompt} custom event with the phase name and the prompt, if the prompt was
     *       captured
     *   <li>one {@link LLMCallEvent} with the phase's token usage summed over turns
     *       ({@link PhaseCapture#aggregateUsage()}), its total cost and its timing. Its model is
     *       the run's {@code model} config value as text, or {@code "unknown"} if there is none.
     *       Its response ID is the message ID of the phase's last turn, or {@code null} when no
     *       turns were captured. Its metadata holds the phase name, session ID, turn count, error
     *       flag, stop reason, turn limit and, when captured, the usage of each turn
     *   <li>one {@link ToolCallEvent} per tool call, with the tool call's ID, name, kind and input,
     *       its turn, and the duration and error of its result
     *   <li>one {@code thinking_block} custom event per thinking block
     * </ul>
     *
     * <p>Then it logs one {@link StepCostEvent} per step as a derived event, with the cost split as
     * in {@link PhaseCapture#stepCosts()}, and adds their number to
     * {@link #derivedEventsEmitted}.
     *
     * @param phase the parsed Claude Code call; with a {@code null} phase name, the {@code phase}
     *        attribute of the prompt and thinking events is left out
     * @throws NullPointerException if no run has been set
     * @throws IllegalStateException if the run has ended
     * @throws UnsupportedOperationException if the phase has steps and the run's storage cannot
     *         keep derived events at all; the other events are logged by then
     */
    public void recordPhase(PhaseCapture phase) {
        // State transition
        currentRun.logEvent(StateChangeEvent.of(previousPhase, phase.phaseName(), "phase transition"));
        previousPhase = phase.phaseName();

        // Prompt capture — record the exact prompt sent for reproducibility
        if (phase.promptText() != null && !phase.promptText().isEmpty()) {
            currentRun.logEvent(CustomEvent.of("prompt",
                    phaseAttributes(phase.phaseName(), "text", phase.promptText())));
        }

        // LLM call with full token/cost/timing data. The per-turn breakdown rides as additive
        // metadata (JournalSteps.META_TURNS) — raw wire data, not derived — so the immutable log
        // alone regenerates per-step cost offline via JournalSteps.fromEvents (DESIGN §2.2 closure).
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("phaseName", phase.phaseName());
        if (phase.sessionId() != null) {
            metadata.put("sessionId", phase.sessionId());
        }
        metadata.put("numTurns", phase.numTurns());
        metadata.put("isError", phase.isError());
        // J3: the stop reason and the ceiling it ran against, written together and
        // unconditionally. numTurns alone cannot say whether a run finished or was cut off, and a
        // ceiling with no outcome answers as little — so neither is ever omitted, not even when
        // unknown. maxTurns = -1 records "no ceiling reported", which is itself a fact about the
        // run; leaving the key out would make "not captured" indistinguishable from "not looked
        // for" once the WARN has scrolled away.
        metadata.put(JournalSteps.META_STOP_REASON,
                (phase.stopReason() != null ? phase.stopReason() : StopReason.UNKNOWN).name());
        metadata.put(JournalSteps.META_MAX_TURNS, phase.maxTurns());
        if (phase.hasTurns()) {
            metadata.put(JournalSteps.META_TURNS, JournalSteps.turnsToMetadata(phase.turns()));
        }

        // Headline token vector = the cost-bearing per-type aggregate (Σ per-turn by type, incl.
        // cache), NOT the final-snapshot scalars (CM). The snapshot under-counts on long runs and
        // dropped cache entirely; aggregateUsage() reconciles to totalCostUsd. Same field, corrected
        // value — additive on §4. (No-turns captures fall back to the snapshot vector, now with cache.)
        currentRun.logEvent(LLMCallEvent.builder()
                .model(Objects.toString(currentRun.config().values().get("model"), "unknown"))
                .tokenUsage(phase.aggregateUsage())
                .cost(CostBreakdown.of(phase.totalCostUsd()))
                .timing(TimingInfo.of(phase.durationMs(), phase.apiDurationMs()))
                .responseId(lastMessageId(phase))
                .metadata(metadata)
                .build());

        // Tool call events — carry the vendor tool_use id as the stable step identity
        // (R2.3) so feedback/eval can target a step without depending on reload order. The
        // tool_result error bit is recorded as success/failure so isError round-trips through the
        // execution stream (so fromEvents matches fromPhaseCapture on the error flag).
        if (phase.hasToolUses()) {
            Map<String, ToolResultRecord> resultsById = new LinkedHashMap<>();
            if (phase.toolResults() != null) {
                for (ToolResultRecord tr : phase.toolResults()) {
                    resultsById.put(tr.toolUseId(), tr);
                }
            }
            for (ToolUseRecord toolUse : phase.toolUses()) {
                ToolResultRecord result = resultsById.get(toolUse.id());
                boolean isError = result != null && result.isError();
                currentRun.logEvent(ToolCallEvent.builder()
                        .id(toolUse.id())
                        .toolName(toolUse.name())
                        .kind(toolUse.kind())
                        .input(toolUse.input())
                        // J4: the dwell-time pair. The duration was previously computed at parse
                        // time, written to a log line, and dropped; the turn ordinal was never
                        // captured at all after v1. Without both, a semi-Markov model has no
                        // holding time and no way to order the trajectory.
                        .durationMs(result != null ? result.durationMs() : -1L)
                        .turnIndex(toolUse.turnIndex())
                        .turnId(toolUse.turnId())
                        .success(!isError)
                        .errorMessage(isError ? result.content() : null)
                        .build());
            }
        }

        // Thinking block events
        if (phase.hasThinking()) {
            for (String thinking : phase.thinkingBlocks()) {
                currentRun.logEvent(CustomEvent.of("thinking_block",
                        phaseAttributes(phase.phaseName(), "content", thinking)));
            }
        }

        // Derived per-step cost → analysis.jsonl (R2.10 emission). The run reports only a
        // total cost; JournalSteps attributes a fair share per step (joined to the execution
        // ToolCallEvent above by the shared tool_use id). This is inferred interpretation, so it
        // is a DerivedEvent in the analysis log — never mixed into the execution event stream.
        Instant analyzedAt = Instant.now();
        List<JournalStep> steps = JournalSteps.fromPhaseCapture(phase, currentRun.id());
        for (JournalStep step : steps) {
            currentRun.logDerivedEvent(StepCostEvent.fromStep(step, analyzedAt));
        }
        derivedEventsEmitted += steps.size();
    }

    /**
     * Ends the run {@code FAILED} and records the error, as {@link Run#fail(Throwable)} does: the
     * summary gets {@code success=false}, the error message and the error's class name. Does
     * nothing if the run has already ended or no run has been set.
     *
     * @param error the cause of the failure
     */
    public void failRun(Throwable error) {
        if (currentRun != null) {
            currentRun.fail(error);
        }
    }

    /**
     * Ends the run {@code FAILED} without an error, through {@link Run#finish(RunStatus)}. The
     * summary gets {@code success=false} unless it already has a {@code success} value; unlike
     * {@link #failRun(Throwable)}, no error message or class is recorded. Does nothing if the run
     * has already ended or no run has been set.
     */
    public void failRun() {
        if (currentRun != null) {
            currentRun.finish(RunStatus.FAILED);
        }
    }

    /**
     * Returns the run this recorder logs to.
     *
     * @return the run, or {@code null} if no run has been set
     */
    public Run getCurrentRun() {
        return currentRun;
    }

    // The message ID of the phase's last turn: the API's ID for the response that ended the phase,
    // which gives the LLM call a stable evaluation subject ID instead of a positional one.
    private static String lastMessageId(PhaseCapture phase) {
        String id = null;
        if (phase.hasTurns()) {
            for (TurnUsage turn : phase.turns()) {
                if (turn.messageId() != null) {
                    id = turn.messageId();
                }
            }
        }
        return id;
    }

    // Not Map.of, which throws NullPointerException for a capture with no phase name; such a
    // capture is recorded without the phase, since attributes must not hold null values.
    private static Map<String, Object> phaseAttributes(String phaseName, String key, Object value) {
        Map<String, Object> attributes = new LinkedHashMap<>();
        if (phaseName != null) {
            attributes.put("phase", phaseName);
        }
        attributes.put(key, value);
        return attributes;
    }
}
