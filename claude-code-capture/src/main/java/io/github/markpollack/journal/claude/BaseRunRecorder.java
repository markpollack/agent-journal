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
import java.util.Collection;
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
     *   <li>one {@code vendor_event} custom event per message that is not an ordinary assistant or
     *       user message, whole (see {@link ClaudeVendorEvent})
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

        // The inventory of every source invocation this run represents (Stage 13 review R13-07): one entry per
        // recorded phase, never overwritten by the next, so a run of several phases, or of two invocations under
        // one phase name, names every session it drew on. Assigned before anything of this invocation is written,
        // so a sub-agent's records reference it too (R13-10: they carried -1 while this ran after the sub-agents).
        currentInvocation = invocations.size();
        invocations.add(new Invocation(currentInvocation, phase.phaseName(), phase.sessionId(), cliVersionOf(phase),
                phase.vendorEvents().stream().anyMatch(v -> "result".equals(v.type())),
                phase.sourceRecords().size(), phase.vendorEvents().size()));

        // Sub-agents next: each is written as its own run, and the LLM call below names them.
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
        // The cost's validity and scope, stated on every call (Stage 13 package 1): Claude reports one total
        // for the whole call, sub-agents included; no breakdown by input, output or thinking is reported, so
        // CostBreakdown's components are not a breakdown here (the total sits in inputCostUsd until the
        // package/event schema version policy of Stage 13 package 3 reshapes it; ACT reads that field today).
        // costAvailable is true only when a result line reported the figure (review R13-02): a stream without
        // one, or whose result line carried no total_cost_usd, has an unknown cost, and the 0.0 written in the
        // cost record is then a placeholder a reader must not take for a measurement.
        metadata.put("costAvailable", phase.costReported());
        metadata.put("costSource", phase.costSource());
        metadata.put("costScope", phase.hasSubagents() || !phase.reportedSubagentStats().isEmpty()
                ? "call-including-subagents" : "call");
        metadata.put("costBreakdownReported", false);
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

        // The cache-write split between the 5-minute and 1-hour caches (priced differently), when the
        // result lines reported it, and every model's modelUsage entry exactly as reported (running
        // totals for the session, sub-agents included): canonical name, provider, cost basis, context
        // window, per-model thinking tokens. Not-reported facts are left out, not written as 0.
        if (phase.cacheWriteTiers() != null) {
            Map<String, Object> tiers = new LinkedHashMap<>();
            tiers.put("fiveMinuteTokens", phase.cacheWriteTiers().fiveMinuteTokens());
            tiers.put("oneHourTokens", phase.cacheWriteTiers().oneHourTokens());
            metadata.put(META_CACHE_WRITE_TIERS, tiers);
        }
        if (phase.modelCosts() != null && !phase.modelCosts().isEmpty()) {
            List<Map<String, Object>> modelUsage = new ArrayList<>();
            for (ModelCost modelCost : phase.modelCosts()) {
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("model", modelCost.model());
                entry.putAll(modelCost.reported());
                modelUsage.add(entry);
            }
            metadata.put(META_MODEL_USAGE, modelUsage);
        }

        // Headline token vector = the cost-bearing per-type aggregate (Σ per-turn by type, incl.
        // cache), NOT the final-snapshot scalars (CM). The snapshot under-counts on long runs and
        // dropped cache entirely; aggregateUsage() reconciles to totalCostUsd. Same field, corrected
        // value — additive on §4. (No-turns captures fall back to the snapshot vector, now with cache.)
        currentRun.logEvent(LLMCallEvent.builder()
                .model(eventModel(phase))
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
            logToolCalls(currentRun, phase.toolUses(), phase.toolResults());
        }

        recordSourceLayer(currentRun, phase, null, phase.toolResults());

        // Messages that are not ordinary assistant or user messages, whole (system, result, ...)
        for (ClaudeVendorEvent vendorEvent : phase.vendorEvents()) {
            Map<String, Object> attributes = phaseAttributes(phase.phaseName(), "vendor", "claude");
            attributes.put("type", vendorEvent.type());
            attributes.put("invocation", currentInvocation);
            attributes.put("messageNumber", vendorEvent.messageNumber());
            if (vendorEvent.subtype() != null) {
                attributes.put("subtype", vendorEvent.subtype());
            }
            if (vendorEvent.rawJson() != null) {
                attributes.put("rawJson", vendorEvent.rawJson());
            }
            if (vendorEvent.reason() != null) {
                attributes.put("reason", vendorEvent.reason());
            }
            currentRun.logEvent(CustomEvent.of("vendor_event", attributes));
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

    /**
     * One source invocation the run represents: a recorded phase, with what its stream said about itself. The
     * index is the phase's position among the run's recorded phases and scopes the {@code messageNumber}
     * occurrence counters, which restart with every parse (Stage 13 review R13-07).
     */
    protected record Invocation(int index, String phaseName, String sessionId, String cliVersion,
            boolean streamComplete, int sourceRecords, int vendorEvents) {
    }

    /** Every recorded phase, in order; read by the finish to secure and inventory the capture. */
    protected final List<Invocation> invocations = new ArrayList<>();

    /** The index of the phase being recorded, stamped on its occurrence-bearing events. */
    protected int currentInvocation = -1;

    /**
     * The sub-agent runs recorded so far: spawn tool-use id, agent id (may be null), child run id, and the
     * session id of the phase that recorded it (may be null).
     */
    protected final List<String[]> recordedSubagents = new ArrayList<>();


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
        recordedSubagents.add(new String[] { subagent.spawnToolUseId(), subagent.agentId(), run.id(), phase.sessionId() });
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

            logToolCalls(run, subagent.toolUses(), subagent.toolResults());

            // The child's own tool results, so its durations carry the same tool_timing declaration as the main
            // loop's (R13-11: child durations were recorded without their source stated).
            recordSourceLayer(run, phase, subagent.spawnToolUseId(), subagent.toolResults());
            for (String thinking : phase.contentBlocks().stream().anyMatch(b -> Objects.equals(b.parentToolUseId(), subagent.spawnToolUseId())) ? List.<String>of() : subagent.thinkingBlocks()) {
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
     * nothing if the run has already ended or no run has been set. {@link RunRecorder} secures the
     * run's captured sources first, so a failed run keeps its level-0 archive and capture summary.
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
     * has already ended or no run has been set. {@link RunRecorder} secures the run's captured
     * sources first.
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

    /**
     * The model of the phase's LLM call: the exact model the result line reports when it reports
     * exactly one, otherwise the model the caller configured, otherwise {@code "unknown"}. A call
     * that used several models (a sub-agent on a smaller one) has them all in the
     * {@link #META_MODEL_USAGE} metadata.
     */
    private String eventModel(PhaseCapture phase) {
        List<ModelCost> reported = phase.modelCosts();
        if (reported != null && reported.size() == 1 && reported.get(0).model() != null) {
            return reported.get(0).model();
        }
        return Objects.toString(currentRun.config().values().get("model"), "unknown");
    }

    /**
     * Writes the level-1 source layer of one track (option C, Stage 13 package 1) onto {@code target}: ordered
     * content blocks, the permission decisions, the vendor-timestamp tool timing, and every assistant and user
     * line of that track whole at its occurrence. {@code owner} is {@code null} for the main loop or the spawning
     * tool-use id of a sub-agent; each record carries its owner, so a sub-agent's lines land on its own run.
     */
    private void recordSourceLayer(Run target, PhaseCapture phase, String owner, List<ToolResultRecord> toolResults) {
        // Ordered assistant content (option C, package 1): one event per text and thinking block with its
        // position, and one message_blocks event per assistant line listing every block's kind in order, so
        // the sequence of text, thinking and tool use is recoverable from level 1. Thinking carries its
        // signature verbatim. Captures built without content blocks (older convenience constructors) fall
        // back to the plain thinking_block events.
        if (!phase.contentBlocks().isEmpty()) {
            Map<Integer, List<Map<String, Object>>> blocksByLine = new java.util.TreeMap<>();
            Map<Integer, ClaudeContentBlock> firstByLine = new LinkedHashMap<>();
            for (ClaudeContentBlock block : phase.contentBlocks()) {
                if (!Objects.equals(block.parentToolUseId(), owner)) {
                    continue;
                }
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("index", block.blockIndex());
                entry.put("kind", block.kind());
                if (block.toolUseId() != null) {
                    entry.put("toolUseId", block.toolUseId());
                }
                blocksByLine.computeIfAbsent(block.messageNumber(), k -> new ArrayList<>()).add(entry);
                firstByLine.putIfAbsent(block.messageNumber(), block);
                if ("text".equals(block.kind())) {
                    Map<String, Object> attributes = blockAttributes(phase.phaseName(), block);
                    attributes.put("text", block.text() != null ? block.text() : "");
                    target.logEvent(CustomEvent.of("assistant_text", attributes));
                }
                else if ("thinking".equals(block.kind())) {
                    Map<String, Object> attributes = blockAttributes(phase.phaseName(), block);
                    attributes.put("content", block.text() != null ? block.text() : "");
                    if (block.signature() != null) {
                        attributes.put("signature", block.signature());
                    }
                    target.logEvent(CustomEvent.of("thinking_block", attributes));
                }
            }
            for (Map.Entry<Integer, List<Map<String, Object>>> line : blocksByLine.entrySet()) {
                ClaudeContentBlock first = firstByLine.get(line.getKey());
                Map<String, Object> attributes = phaseAttributes(phase.phaseName(), "messageNumber", line.getKey());
                if (first.turnId() != null) {
                    attributes.put("turnId", first.turnId());
                }
                attributes.put("turnIndex", first.turnIndex());
                if (first.parentToolUseId() != null) {
                    attributes.put("parentToolUseId", first.parentToolUseId());
                }
                attributes.put("blocks", line.getValue());
                target.logEvent(CustomEvent.of("message_blocks", attributes));
            }
        }
        else if (owner == null && phase.hasThinking()) {
            for (String thinking : phase.thinkingBlocks()) {
                target.logEvent(CustomEvent.of("thinking_block",
                        phaseAttributes(phase.phaseName(), "content", thinking)));
            }
        }

        // Permission decisions the vendor reported per tool result, as observations
        for (ClaudePermissionDecision decision : phase.permissionDecisions()) {
            if (!Objects.equals(decision.parentToolUseId(), owner)) {
                continue;
            }
            Map<String, Object> attributes = phaseAttributes(phase.phaseName(), "vendor", "claude");
            attributes.put("messageNumber", decision.messageNumber());
            if (decision.toolUseId() != null) {
                attributes.put("toolUseId", decision.toolUseId());
            }
            if (decision.decision() != null) {
                attributes.put("decision", decision.decision());
            }
            if (decision.source() != null) {
                attributes.put("source", decision.source());
            }
            if (decision.reasonType() != null) {
                attributes.put("reasonType", decision.reasonType());
            }
            if (decision.parentToolUseId() != null) {
                attributes.put("parentToolUseId", decision.parentToolUseId());
            }
            target.logEvent(CustomEvent.of("permission_decision", attributes));
        }

        // Tool timing from the vendor's own line timestamps, with its meaning stated: one declaration per result,
        // also when no line carried a timestamp (durationSource not-reported, -1), so an unstated source never
        // has to be inferred from a missing event (FB-1, R13-11)
        if (toolResults != null) {
            for (ToolResultRecord result : toolResults) {
                Map<String, Object> attributes = phaseAttributes(phase.phaseName(), "toolUseId", result.toolUseId());
                if (result.issuedAt() != null) {
                    attributes.put("issuedAt", result.issuedAt());
                }
                if (result.resultAt() != null) {
                    attributes.put("resultAt", result.resultAt());
                }
                attributes.put("durationMs", result.durationMs());
                attributes.put("durationSource", result.durationSource());
                target.logEvent(CustomEvent.of("tool_timing", attributes));
            }
        }

        // Every assistant and user line of the stream, whole, at its occurrence (option C, conservative
        // retention): the residual of the records the typed layer consumed
        for (ClaudeSourceRecord record : phase.sourceRecords()) {
            if (!Objects.equals(record.parentToolUseId(), owner)) {
                continue;
            }
            Map<String, Object> attributes = phaseAttributes(phase.phaseName(), "vendor", "claude");
            attributes.put("channel", "stream");
            attributes.put("invocation", currentInvocation);
            attributes.put("messageNumber", record.messageNumber());
            attributes.put("type", record.type());
            if (record.parentToolUseId() != null) {
                attributes.put("parentToolUseId", record.parentToolUseId());
            }
            if (record.rawJson() != null) {
                attributes.put("rawJson", record.rawJson());
            }
            if (record.reason() != null) {
                attributes.put("reason", record.reason());
            }
            target.logEvent(CustomEvent.of("source_record", attributes));
        }

    }

    /** The CLI version from the stream's init line ({@code claude_code_version}), or {@code null} if none. */
    static String cliVersionOf(PhaseCapture phase) {
        for (ClaudeVendorEvent event : phase.vendorEvents()) {
            if ("system".equals(event.type()) && "init".equals(event.subtype()) && event.rawJson() != null) {
                try {
                    com.fasterxml.jackson.databind.JsonNode init = new com.fasterxml.jackson.databind.ObjectMapper()
                            .readTree(event.rawJson());
                    if (init.hasNonNull("claude_code_version")) {
                        return init.get("claude_code_version").asText();
                    }
                }
                catch (com.fasterxml.jackson.core.JsonProcessingException ignored) {
                    return null;
                }
            }
        }
        return null;
    }

    private static Map<String, Object> blockAttributes(String phaseName, ClaudeContentBlock block) {
        Map<String, Object> attributes = phaseAttributes(phaseName, "messageNumber", block.messageNumber());
        if (block.turnId() != null) {
            attributes.put("turnId", block.turnId());
        }
        attributes.put("turnIndex", block.turnIndex());
        attributes.put("blockIndex", block.blockIndex());
        if (block.parentToolUseId() != null) {
            attributes.put("parentToolUseId", block.parentToolUseId());
        }
        return attributes;
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
    /** Metadata key on the LLM call event: {@code {fiveMinuteTokens, oneHourTokens}} cache writes, when reported. */
    public static final String META_CACHE_WRITE_TIERS = "cacheWriteTiers";

    /** Metadata key on the LLM call event: one map per model, as the result line's {@code modelUsage} reports it. */
    public static final String META_MODEL_USAGE = "modelUsage";

    /**
     * One {@link ToolCallEvent} per tool use, joined to its result by the vendor tool_use id (R2.3: the stable step
     * identity, so feedback and eval can target a step without depending on reload order). The result is carried
     * whole as the output (the error text when the call failed), its duration as the dwell time (J4) and the
     * tool_result error bit as success/failure, so isError round-trips through the execution stream. Shared by the
     * main run and each child run (R13-03: a child's successful output was once dropped although its record kept it).
     */
    private static void logToolCalls(Run target, Collection<ToolUseRecord> toolUses, Collection<ToolResultRecord> toolResults) {
        Map<String, ToolResultRecord> resultsById = new LinkedHashMap<>();
        if (toolResults != null) {
            for (ToolResultRecord tr : toolResults) {
                resultsById.put(tr.toolUseId(), tr);
            }
        }
        for (ToolUseRecord toolUse : toolUses) {
            ToolResultRecord result = resultsById.get(toolUse.id());
            boolean isError = result != null && result.isError();
            target.logEvent(ToolCallEvent.builder()
                    .id(toolUse.id())
                    .toolName(toolUse.name())
                    .kind(toolUse.kind())
                    .input(toolUse.input())
                    .output(result != null ? result.content() : null)
                    .durationMs(result != null ? result.durationMs() : -1L)
                    .turnIndex(toolUse.turnIndex())
                    .turnId(toolUse.turnId())
                    .success(!isError)
                    .errorMessage(isError ? result.content() : null)
                    .build());
        }
    }
}
