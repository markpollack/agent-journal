package io.github.markpollack.journal.claude;

import io.github.markpollack.journal.Journal;
import io.github.markpollack.journal.Run;
import io.github.markpollack.journal.RunBuilder;
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
import java.util.ArrayList;
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
     * <p>If the phase has {@link PhaseCapture#subagents() sub-agents}, each is written as a run
     * of its own in the same experiment, before the phase's LLM call event. Such a run has the
     * run of the agent that started it as its {@code parentRunId}, the tag {@code track=subagent},
     * the ID of the spawning tool call under the config key {@link #CONFIG_SPAWN_TOOL_USE_ID},
     * and the same kinds of events as this run. Its LLM call has a cost of 0 marked
     * {@code costAvailable=false}, because Claude Code reports one cost for the call, which is
     * on this run and includes the sub-agents. It ends {@code FINISHED} if Claude Code reported
     * the sub-agent completed, {@code FAILED} if it reported it failed or was killed, and
     * {@code CRASHED} otherwise, with the reported status in its summary. The phase's LLM call
     * event then lists the sub-agent runs under {@link #META_SUBAGENTS}. The runs are written
     * through {@link Journal#storage()}. A phase without sub-agents is recorded as before.
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

        // Sub-agents first: each is written as its own run, and the LLM call below names them.
        List<Map<String, Object>> subagentRuns = recordSubagents(phase);

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
        // A capture rebased onto its own query by SessionLogParser.withSessionBaseline says so, and
        // keeps the session's running total beside the per-query cost. A capture taken from the
        // result line verbatim writes neither key, as before.
        if (phase.sessionCost() != null) {
            metadata.put(SessionCost.COST_BASIS_KEY, "session_delta");
            metadata.put(SessionCost.SESSION_CUMULATIVE_COST_KEY, phase.sessionCost().cumulativeCostUsd());
        }
        // Written only for a call with sub-agent evidence, so a call without any is recorded
        // exactly as before.
        List<String> subagentsWithoutTrack = phase.subagentsWithoutTrack();
        boolean spawned = phase.toolUses() != null && phase.toolUses().stream()
                .anyMatch(use -> use != null && JournalSteps.isSubagentTool(use.name()));
        if (!subagentRuns.isEmpty() || spawned || phase.reportedSubagentsSpawned() > 0) {
            metadata.put(META_SUBAGENT_TRACKS_AVAILABLE, phase.subagentTracksAvailable());
            if (phase.subagentTracksAvailable()) {
                metadata.put(META_SUBAGENTS, subagentRuns);
                metadata.put(META_SUBAGENTS_WITHOUT_TRACK, subagentsWithoutTrack);
            }
            if (!phase.reportedSubagentStats().isEmpty()) {
                metadata.put(META_SUBAGENT_STATS, phase.reportedSubagentStats());
            }
            // Claude Code reports one cost for the call, sub-agents included.
            metadata.put(META_COST_INCLUDES_SUBAGENTS, true);
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
     * The key of the list, in the metadata of a phase's LLM call event, of the sub-agents that
     * were written as runs of their own. Each entry is a map with {@code spawnToolUseId},
     * {@code runId}, {@code depth} and {@code status}, and {@code agentId} when reported.
     */
    public static final String META_SUBAGENTS = "subagents";

    /**
     * The key of the list of spawning tool-call IDs for which no sub-agent messages arrived; see
     * {@link PhaseCapture#subagentsWithoutTrack()}.
     */
    public static final String META_SUBAGENTS_WITHOUT_TRACK = "subagentsWithoutTrack";

    /**
     * The key of the sub-agent counts Claude Code reported for the call ({@code subagent_stats}),
     * unchanged; written when it reported any.
     */
    public static final String META_SUBAGENT_STATS = "subagentStats";

    /**
     * The key that says whether sub-agent activity could be kept apart from the main loop's; see
     * {@link PhaseCapture#subagentTracksAvailable()}. When it is {@code false}, the sub-agents'
     * tool calls and usage are among this run's own, as in every record written up to 1.10.1.
     */
    public static final String META_SUBAGENT_TRACKS_AVAILABLE = "subagentTracksAvailable";

    /** The key that marks the event's cost as including the cost of the call's sub-agents. */
    public static final String META_COST_INCLUDES_SUBAGENTS = "costIncludesSubagents";

    /** The config key, on a sub-agent's run, of the ID of the tool call that started it. */
    public static final String CONFIG_SPAWN_TOOL_USE_ID = "subagent.spawnToolUseId";

    /** The tag key that marks a run as a sub-agent's; its value is {@code "subagent"}. */
    public static final String TAG_TRACK = "track";

    /**
     * Writes each sub-agent of the phase as a run of its own in the same experiment, linked by
     * {@code parentRunId} to the run of the agent that started it: this recorder's run for a
     * sub-agent of the main loop, the enclosing sub-agent's run for a nested one. Each run is
     * started, written and ended here, before the phase's own LLM call event, so that the event
     * can name the runs; they are written through {@link Journal#storage()}.
     *
     * @return one entry per sub-agent written, for the phase's LLM call metadata
     */
    private List<Map<String, Object>> recordSubagents(PhaseCapture phase) {
        List<Map<String, Object>> written = new ArrayList<>();
        // Without the wire lines the tracks are not trustworthy; the capture then reports no sub-agents
        // and whatever a sub-agent did is in the main loop's components, as before.
        if (!phase.hasSubagents() || !phase.subagentTracksAvailable()) {
            return written;
        }
        Map<String, String> runIdBySpawnToolUseId = new LinkedHashMap<>();
        List<SubagentCapture> pending = new ArrayList<>();
        for (SubagentCapture subagent : phase.subagents()) {
            if (subagent.spawnToolUseId() != null) {
                pending.add(subagent);
            }
        }
        // A nested sub-agent needs its parent's run ID, so parents go first. One whose parent
        // never gets a run, because the chain is broken, is linked to this recorder's run.
        while (!pending.isEmpty()) {
            boolean progressed = false;
            for (SubagentCapture subagent : new ArrayList<>(pending)) {
                String parent = subagent.parentSpawnToolUseId();
                if (parent == null || runIdBySpawnToolUseId.containsKey(parent)) {
                    String parentRunId = parent == null ? currentRun.id() : runIdBySpawnToolUseId.get(parent);
                    written.add(recordSubagent(phase, subagent, parentRunId, runIdBySpawnToolUseId));
                    pending.remove(subagent);
                    progressed = true;
                }
            }
            if (!progressed) {
                for (SubagentCapture subagent : pending) {
                    written.add(recordSubagent(phase, subagent, currentRun.id(), runIdBySpawnToolUseId));
                }
                pending.clear();
            }
        }
        return written;
    }

    private Map<String, Object> recordSubagent(PhaseCapture phase, SubagentCapture subagent, String parentRunId,
            Map<String, String> runIdBySpawnToolUseId) {
        String status = subagent.status() != null ? subagent.status() : "unknown";
        RunBuilder builder = Journal.run(currentRun.experiment().id())
                .parentRun(parentRunId)
                .tag(TAG_TRACK, "subagent")
                .config(CONFIG_SPAWN_TOOL_USE_ID, subagent.spawnToolUseId())
                .config("subagent.depth", subagent.depth());
        if (subagent.subagentType() != null) {
            builder.config("subagent.type", subagent.subagentType());
        }
        if (subagent.description() != null) {
            builder.config("subagent.description", subagent.description());
        }
        if (phase.sessionId() != null) {
            builder.config("subagent.sessionId", phase.sessionId());
        }
        if (subagent.model() != null) {
            builder.config("model", subagent.model());
        }
        if (subagent.backgrounded()) {
            builder.config("subagent.backgrounded", true);
        }
        if (subagent.agentId() != null) {
            builder.agent(subagent.agentId());
        }
        Run run = builder.start();
        runIdBySpawnToolUseId.put(subagent.spawnToolUseId(), run.id());
        try {
            if (subagent.promptText() != null && !subagent.promptText().isEmpty()) {
                run.logEvent(CustomEvent.of("prompt",
                        phaseAttributes(phase.phaseName(), "text", subagent.promptText())));
            }

            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("phaseName", phase.phaseName());
            if (phase.sessionId() != null) {
                metadata.put("sessionId", phase.sessionId());
            }
            metadata.put("numTurns", subagent.turns().size());
            metadata.put("isError", subagent.reportedFailed());
            // Claude Code reports no cost for a sub-agent; it is inside the spawning call's total.
            metadata.put("costAvailable", false);
            metadata.put("costSource", "included_in_parent");
            if (!subagent.turns().isEmpty()) {
                metadata.put(JournalSteps.META_TURNS, JournalSteps.turnsToMetadata(subagent.turns()));
            }
            String lastMessageId = null;
            for (TurnUsage turn : subagent.turns()) {
                if (turn.messageId() != null) {
                    lastMessageId = turn.messageId();
                }
            }
            run.logEvent(LLMCallEvent.builder()
                    .model(subagent.model() != null ? subagent.model() : "unknown")
                    .tokenUsage(subagent.aggregateUsage())
                    .cost(CostBreakdown.of(0.0))
                    .timing(TimingInfo.of(Math.max(0L, subagent.reportedDurationMs())))
                    .responseId(lastMessageId)
                    .metadata(metadata)
                    .build());

            Map<String, ToolResultRecord> resultsById = new LinkedHashMap<>();
            for (ToolResultRecord result : subagent.toolResults()) {
                resultsById.put(result.toolUseId(), result);
            }
            for (ToolUseRecord toolUse : subagent.toolUses()) {
                ToolResultRecord result = resultsById.get(toolUse.id());
                boolean isError = result != null && result.isError();
                run.logEvent(ToolCallEvent.builder()
                        .id(toolUse.id())
                        .toolName(toolUse.name())
                        .kind(toolUse.kind())
                        .input(toolUse.input())
                        .durationMs(result != null ? result.durationMs() : -1L)
                        .turnIndex(toolUse.turnIndex())
                        .turnId(toolUse.turnId())
                        .success(!isError)
                        .errorMessage(isError ? result.content() : null)
                        .build());
            }

            for (String thinking : subagent.thinkingBlocks()) {
                run.logEvent(CustomEvent.of("thinking_block",
                        phaseAttributes(phase.phaseName(), "content", thinking)));
            }

            Instant analyzedAt = Instant.now();
            List<JournalStep> steps = JournalSteps.forSubagent(subagent, run.id());
            for (JournalStep step : steps) {
                run.logDerivedEvent(StepCostEvent.fromStep(step, analyzedAt));
            }
            derivedEventsEmitted += steps.size();

            run.setSummary("subagent.status", status);
            run.setSummary("subagent.statusSource", subagent.statusSource());
            if (subagent.reportedTotalTokens() >= 0) {
                run.setSummary("subagent.reportedTotalTokens", subagent.reportedTotalTokens());
            }
            if (subagent.reportedToolUses() >= 0) {
                run.setSummary("subagent.reportedToolUses", subagent.reportedToolUses());
            }
            if (subagent.reportedDurationMs() >= 0) {
                run.setSummary("subagent.reportedDurationMs", subagent.reportedDurationMs());
            }
            // Only a reported "completed" is a finished sub-agent, and only a reported "failed"
            // or "killed" a failed one. With no status, or one this version does not know, the
            // stream did not show how the sub-agent ended; the status text is in the summary.
            run.finish("completed".equals(status) ? RunStatus.FINISHED
                    : subagent.reportedFailed() ? RunStatus.FAILED : RunStatus.CRASHED);
        } catch (RuntimeException e) {
            run.fail(e);
            throw e;
        }

        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("spawnToolUseId", subagent.spawnToolUseId());
        entry.put("runId", run.id());
        if (subagent.agentId() != null) {
            entry.put("agentId", subagent.agentId());
        }
        entry.put("depth", subagent.depth());
        entry.put("status", status);
        return entry;
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
