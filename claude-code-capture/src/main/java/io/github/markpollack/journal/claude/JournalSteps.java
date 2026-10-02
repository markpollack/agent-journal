package io.github.markpollack.journal.claude;

import io.github.markpollack.journal.event.JournalEvent;
import io.github.markpollack.journal.event.LLMCallEvent;
import io.github.markpollack.journal.event.TokenUsage;
import io.github.markpollack.journal.event.ToolCallEvent;
import io.github.markpollack.journal.trace.AttributionMethod;
import io.github.markpollack.journal.trace.JournalStep;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Splits the cost of Claude Code phases across their steps and returns one {@link JournalStep}
 * per step: a tool call, or a turn that made no tool call. Use
 * {@link #fromPhaseCapture(PhaseCapture, String)} on a {@link PhaseCapture}, or
 * {@link #fromEvents(List, String)} on the events that a {@link RunRecorder} stored, to get the
 * same steps later without the capture. {@link PhaseCapture#stepCosts()}, the run recorder and
 * the trace that {@link SessionLogParser} writes all use it.
 *
 * <p>Claude Code reports only the total cost of a call, so each step's cost is an allocation.
 * When the capture has the usage of each turn, each turn gets a share of the total in proportion
 * to its output tokens (an equal share if no turn has any), divided evenly among the turn's tool
 * calls, and a turn without tool calls becomes one step whose ID is the turn ID. Only the tool
 * calls that a turn lists get a step; when some are listed by no turn, their cost goes to the
 * other steps and a warning is logged. These steps are labelled
 * {@link AttributionMethod#OUTPUT_TOKEN_PROPORTIONAL}. Without per-turn usage (claude-code-sdk
 * before 1.3.0, or a capture built in code), the total is divided equally among the tool calls,
 * the steps have no token counts, they are labelled {@link AttributionMethod#EVEN_SPLIT}, and a
 * warning is logged once per process. Either way the rounding remainder is added to the last
 * step, so that the shares add up to the total.
 *
 * <p>All turns share one total, whatever model served them. The exact cost of each model is kept
 * apart, in {@link PhaseCapture#modelCosts()}.
 *
 * <p>{@code fromEvents} reads what Claude Code's recorder stores: each phase's
 * {@link LLMCallEvent}, with the phase's total cost and, in its metadata under {@code "turns"},
 * the usage of each turn; and the {@link ToolCallEvent}s after it, with their IDs, names, errors
 * and durations. On those events it gives the same steps as {@code fromPhaseCapture} on the
 * captures. {@link #aggregateUsageFromEvents(List)} adds up token usage from the same events.
 *
 * <p>It is the reference for the {@code *JournalSteps} classes of the other capture modules. All
 * methods are static and keep no state, and they can be called from several threads.
 */
public final class JournalSteps {

    private static final Logger log = LoggerFactory.getLogger(JournalSteps.class);

    /**
     * The vendor name that steps from Claude Code carry unless another is given:
     * {@code "claude-code"}.
     */
    public static final String VENDOR_CLAUDE_CODE = "claude-code";

    /** Tools that spawn a sub-agent (whose interior steps live in {@code subagents/*.jsonl}). */
    private static final Set<String> SUBAGENT_TOOLS = Set.of("Task", "Agent");

    // --- Additive LLMCallEvent.metadata keys carrying the per-turn breakdown for offline
    // re-derivation. Package-private so BaseRunRecorder (writer) and fromEvents (reader) agree. ---
    static final String META_TURNS = "turns";
    static final String META_TURN_MESSAGE_ID = "messageId";
    static final String META_TURN_MODEL = "model";
    static final String META_TURN_INPUT_TOKENS = "inputTokens";
    static final String META_TURN_OUTPUT_TOKENS = "outputTokens";
    static final String META_TURN_CACHE_CREATION = "cacheCreationInputTokens";
    static final String META_TURN_CACHE_READ = "cacheReadInputTokens";
    static final String META_TURN_TOOL_USE_IDS = "toolUseIds";
    static final String META_TURN_THINKING_TOKENS = "thinkingTokens";
    static final String META_TURN_STOP_REASON = "stopReason";
    static final String META_TURN_INDEX = "turnIndex";

    /**
     * The {@link LLMCallEvent} metadata key under which Claude Code's recorder stores the phase's
     * stop reason, as the name of a {@link io.github.markpollack.journal.event.StopReason}:
     * {@code "stopReason"}. The recorder always stores it together with {@link #META_MAX_TURNS}.
     */
    public static final String META_STOP_REASON = "stopReason";

    /**
     * The {@link LLMCallEvent} metadata key under which Claude Code's recorder stores the turn
     * limit the phase ran against, or -1 if none was reported: {@code "maxTurns"}. The recorder
     * always stores it together with {@link #META_STOP_REASON}.
     */
    public static final String META_MAX_TURNS = "maxTurns";

    /** Logged at most once per process when attribution is coarsened (turns absent). */
    private static final AtomicBoolean COARSENED_WARNED = new AtomicBoolean(false);

    private JournalSteps() {
    }

    /**
     * Splits the cost of one phase across its steps, with vendor {@link #VENDOR_CLAUDE_CODE}.
     * Same as {@link #fromPhaseCapture(PhaseCapture, String, String)} with that vendor.
     *
     * @param phase the phase; must not be {@code null}
     * @param runId the run ID to put on each step, or {@code null}
     * @return the steps, in order; empty if the phase has no tool calls and no per-turn usage
     */
    public static List<JournalStep> fromPhaseCapture(PhaseCapture phase, String runId) {
        return fromPhaseCapture(phase, runId, VENDOR_CLAUDE_CODE);
    }

    /**
     * Splits the cost of one phase across its steps, as the class comment describes. The total is
     * the phase's {@code totalCostUsd}; its tool results give each tool step its error flag and
     * duration, and a tool call without a result has no error and a duration of -1.
     *
     * <p>A tool-call ID that a turn lists but that belongs to none of the phase's tool calls still
     * gets a step, with no tool name. A tool call with no name gets a step that is not marked as a
     * sub-agent spawn.
     *
     * @param phase the phase; must not be {@code null}
     * @param runId the run ID to put on each step, or {@code null}
     * @param vendor the vendor name to put on each step
     * @return the steps, in order; empty if the phase has no tool calls and no per-turn usage
     */
    public static List<JournalStep> fromPhaseCapture(PhaseCapture phase, String runId, String vendor) {
        Map<String, Boolean> toolErrors = new LinkedHashMap<>();
        Map<String, Long> toolDurations = new LinkedHashMap<>();
        for (ToolResultRecord tr : nullSafe(phase.toolResults())) {
            toolErrors.put(tr.toolUseId(), tr.isError());
            toolDurations.put(tr.toolUseId(), tr.durationMs());
        }
        return attribute(phase.turns(), nullSafe(phase.toolUses()), toolErrors, toolDurations,
                phase.totalCostUsd(), runId, vendor);
    }

    /**
     * Works out the steps of every phase again from a run's events, with vendor
     * {@link #VENDOR_CLAUDE_CODE}. Same as {@link #fromEvents(List, String, String)} with that
     * vendor.
     *
     * @param events the run's events, in the order they were logged, or {@code null} for none
     * @param runId the run ID to put on each step, or {@code null}
     * @return the steps of all phases, in order
     */
    public static List<JournalStep> fromEvents(List<JournalEvent> events, String runId) {
        return fromEvents(events, runId, VENDOR_CLAUDE_CODE);
    }

    /**
     * Works out the steps of every phase again from the events that Claude Code's recorder
     * stored, without the phase captures. Each {@link LLMCallEvent} starts a phase, and the
     * {@link ToolCallEvent}s after it, up to the next LLM call, are that phase's tool calls. Other
     * events, and tool calls before the first LLM call, are ignored. Each phase is split on its
     * own, as {@link #fromPhaseCapture(PhaseCapture, String, String)} does: with the LLM call's
     * total cost, and with the turns in its metadata, or an even split when it has none. A tool
     * call's error flag is the opposite of its {@code success}.
     *
     * <p>Use it on events from Claude Code's recorder. Other LLM and tool call events also give
     * steps, but these are even splits with the vendor you pass. A tool-call ID that a turn lists
     * but that no tool call event of its phase carries, or that has no tool name, gets a step with
     * no tool name.
     *
     * @param events the run's events, in the order they were logged, or {@code null} for none
     * @param runId the run ID to put on each step, or {@code null}
     * @param vendor the vendor name to put on each step
     * @return the steps of all phases, in order
     */
    public static List<JournalStep> fromEvents(List<JournalEvent> events, String runId, String vendor) {
        List<JournalStep> steps = new ArrayList<>();
        // The per-phase LLMCallEvent anchors a phase; the ToolCallEvents that follow it (until the
        // next LLMCallEvent) are that phase's tool steps. Non-execution lines are ignored.
        LLMCallEvent currentLlm = null;
        List<ToolUseRecord> phaseTools = new ArrayList<>();
        Map<String, Boolean> phaseToolErrors = new LinkedHashMap<>();
        Map<String, Long> phaseToolDurations = new LinkedHashMap<>();
        for (JournalEvent e : nullSafe(events)) {
            if (e instanceof LLMCallEvent llm) {
                if (currentLlm != null) {
                    steps.addAll(attributePhase(currentLlm, phaseTools, phaseToolErrors, phaseToolDurations,
                            runId, vendor));
                }
                currentLlm = llm;
                phaseTools = new ArrayList<>();
                phaseToolErrors = new LinkedHashMap<>();
                phaseToolDurations = new LinkedHashMap<>();
            } else if (e instanceof ToolCallEvent tc && currentLlm != null) {
                phaseTools.add(new ToolUseRecord(tc.id(), tc.kind(), tc.toolName(), Map.of(), tc.turnId(),
                        tc.turnIndex()));
                if (tc.id() != null) {
                    phaseToolErrors.put(tc.id(), !tc.success());
                    phaseToolDurations.put(tc.id(), tc.durationMs());
                }
            }
        }
        if (currentLlm != null) {
            steps.addAll(attributePhase(currentLlm, phaseTools, phaseToolErrors, phaseToolDurations, runId, vendor));
        }
        return steps;
    }

    /**
     * Adds up the token usage of all LLM calls in a run's events, by token type. For an
     * {@link LLMCallEvent} with per-turn usage in its metadata, the input, output and cache counts
     * are summed over its turns, and the thinking count is the sum of the turns' thinking tokens,
     * or the event's own thinking count when the turns have none. An LLM call without per-turn
     * usage adds its own token usage. Other events are ignored.
     *
     * <p>For events from Claude Code's recorder, each phase's part matches
     * {@link PhaseCapture#aggregateUsage()}, except for thinking tokens when the result message
     * reported its own thinking count: {@code aggregateUsage()} uses that count, and this method
     * the sum over the turns. Every LLM call counts, whichever recorder logged it.
     *
     * @param events the run's events, or {@code null} for none
     * @return the summed usage; all zeros if there are no LLM calls
     */
    public static TokenUsage aggregateUsageFromEvents(List<JournalEvent> events) {
        TokenUsage total = new TokenUsage(0, 0, 0, 0, 0, 0);
        for (JournalEvent e : nullSafe(events)) {
            if (!(e instanceof LLMCallEvent llm)) {
                continue;
            }
            List<TurnUsage> turns = turnsFromMetadata(llm.metadata() != null ? llm.metadata().get(META_TURNS) : null);
            if (turns.isEmpty()) {
                // No per-turn truth in events → use the recorded headline vector as-is.
                total = total.plus(llm.tokenUsage() != null ? llm.tokenUsage() : new TokenUsage(0, 0, 0, 0, 0, 0));
                continue;
            }
            List<TokenUsage> perTurn = new ArrayList<>(turns.size());
            for (TurnUsage t : turns) {
                perTurn.add(new TokenUsage((int) t.inputTokens(), (int) t.outputTokens(), 0,
                        (int) t.cacheCreationInputTokens(), (int) t.cacheReadInputTokens(), 0));
            }
            TokenUsage summed = TokenUsage.sum(perTurn);
            // Prefer the exact Σ per-turn thinking (1.9.0, read from the wire) over the recorded
            // headline, which may still be the pre-1.9.0 chars/4 estimate.
            long thinkingFromTurns = turns.stream().mapToLong(TurnUsage::thinkingTokens).sum();
            int thinking = thinkingFromTurns > 0
                    ? (int) thinkingFromTurns
                    : (llm.tokenUsage() != null ? llm.tokenUsage().thinkingTokens() : 0);
            total = total.plus(new TokenUsage(summed.inputTokens(), summed.outputTokens(), thinking,
                    summed.cacheCreationTokens(), summed.cacheReadTokens(), summed.toolUseTokens()));
        }
        return total;
    }

    private static List<JournalStep> attributePhase(LLMCallEvent llm, List<ToolUseRecord> tools,
            Map<String, Boolean> toolErrors, Map<String, Long> toolDurations, String runId, String vendor) {
        Object rawTurns = (llm.metadata() != null) ? llm.metadata().get(META_TURNS) : null;
        return attribute(turnsFromMetadata(rawTurns), tools, toolErrors, toolDurations, llm.totalCostUsd(), runId,
                vendor);
    }

    /**
     * The single attribution core shared by capture-time and offline derivation. Given the run total,
     * the per-turn output tokens (the split weights), the per-turn tool grouping, and the tool id→name
     * / id→error maps, it produces the per-step shares and folds the float residual into the last step.
     */
    private static List<JournalStep> attribute(List<TurnUsage> turns, List<ToolUseRecord> toolUses,
            Map<String, Boolean> toolErrors, Map<String, Long> toolDurations, double actualCost, String runId,
            String vendor) {
        Map<String, String> toolNames = new LinkedHashMap<>();
        for (ToolUseRecord tu : nullSafe(toolUses)) {
            toolNames.put(tu.id(), tu.name());
        }

        List<JournalStep> steps = new ArrayList<>();

        if (turns != null && !turns.isEmpty()) {
            // Precise: split the run total across turns by output tokens; the within-turn even split
            // among parallel tools is part of this proportional method (A1) → OUTPUT_TOKEN_PROPORTIONAL.
            final AttributionMethod method = AttributionMethod.OUTPUT_TOKEN_PROPORTIONAL;
            warnUnlistedTools(turns, toolUses);
            long totalOutput = turns.stream().mapToLong(TurnUsage::outputTokens).sum();
            for (TurnUsage turn : turns) {
                double weight = totalOutput > 0 ? (double) turn.outputTokens() / totalOutput : 1.0 / turns.size();
                double turnCost = actualCost * weight;
                List<String> tools = turn.toolUseIds();
                if (tools == null || tools.isEmpty()) {
                    // Tool-less turn (e.g. final text answer) → one turn-level step. It has no tool
                    // result, so no observed duration: -1, not 0.
                    steps.add(new JournalStep(runId, turn.messageId(), turn.messageId(), null,
                            turn.inputTokens(), turn.outputTokens(), turnCost, actualCost, method, false, null,
                            vendor, false, turn.thinkingTokens(), turn.cacheCreationInputTokens(),
                            turn.cacheReadInputTokens(), turn.turnIndex(), -1L));
                } else {
                    double perTool = turnCost / tools.size();
                    for (String toolId : tools) {
                        String toolName = toolNames.get(toolId);
                        steps.add(new JournalStep(runId, turn.messageId(), toolId, toolName,
                                turn.inputTokens(), turn.outputTokens(), perTool, actualCost, method,
                                Boolean.TRUE.equals(toolErrors.get(toolId)), null, vendor,
                                isSubagentTool(toolName), turn.thinkingTokens(),
                                turn.cacheCreationInputTokens(), turn.cacheReadInputTokens(), turn.turnIndex(),
                                durationOf(toolDurations, toolId)));
                    }
                }
            }
        } else {
            // Coarse fallback: no per-turn usage (rawJson absent / SDK < 1.3.0 / programmatic) → the whole
            // allocation degrades to a flat even split across tool calls. Stamp the distinct EVEN_SPLIT (A1)
            // so the coarseness is readable from the data after the capture-time WARN is gone; turnId and
            // per-turn tokens are unavailable.
            final AttributionMethod method = AttributionMethod.EVEN_SPLIT;
            List<ToolUseRecord> tools = nullSafe(toolUses);
            if (!tools.isEmpty()) {
                warnCoarsenedOnce();
                double perTool = actualCost / tools.size();
                for (ToolUseRecord tu : tools) {
                    // No per-turn usage means no token vector to carry, but the tool's own turn
                    // linkage and observed duration survive independently of the cost split.
                    steps.add(new JournalStep(runId, tu.turnId(), tu.id(), tu.name(), 0, 0, perTool, actualCost,
                            method, Boolean.TRUE.equals(toolErrors.get(tu.id())), null, vendor,
                            isSubagentTool(tu.name()), 0L, 0L, 0L, tu.turnIndex(),
                            durationOf(toolDurations, tu.id())));
                }
            }
        }

        return foldResidualIntoLast(steps, actualCost);
    }

    /**
     * Keeps the per-step sum exactly equal to the run total by adding the float residual to
     * the last step — so "the total is true" survives the proportional split.
     */
    private static List<JournalStep> foldResidualIntoLast(List<JournalStep> steps, double actualCost) {
        if (steps.isEmpty()) {
            return steps;
        }
        double sum = steps.stream().mapToDouble(JournalStep::attributedCostUsd).sum();
        double residual = actualCost - sum;
        if (residual != 0.0) {
            int i = steps.size() - 1;
            JournalStep last = steps.get(i);
            steps.set(i, new JournalStep(last.runId(), last.turnId(), last.stepId(), last.toolName(),
                    last.inputTokens(), last.outputTokens(), last.attributedCostUsd() + residual,
                    last.actualRunCostUsd(), last.attributionMethod(), last.isError(), last.agentState(),
                    last.vendor(), last.isSubagentSpawn(), last.thinkingTokens(), last.cacheCreationTokens(),
                    last.cacheReadTokens(), last.turnIndex(), last.durationMs()));
        }
        return steps;
    }

    // ========== Per-turn breakdown <-> LLMCallEvent.metadata (additive, for offline re-derivation) ==========

    /**
     * Projects per-turn usage into a plain {@code List<Map>} for embedding in {@link LLMCallEvent}
     * metadata. This is raw wire data (per-turn {@code message.usage}), not derived — it belongs in
     * the immutable log so {@link #fromEvents} can reconstruct the proportional split offline.
     */
    static List<Map<String, Object>> turnsToMetadata(List<TurnUsage> turns) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (TurnUsage t : nullSafe(turns)) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put(META_TURN_MESSAGE_ID, t.messageId());
            if (t.model() != null) {
                m.put(META_TURN_MODEL, t.model());
            }
            m.put(META_TURN_INPUT_TOKENS, t.inputTokens());
            m.put(META_TURN_OUTPUT_TOKENS, t.outputTokens());
            m.put(META_TURN_CACHE_CREATION, t.cacheCreationInputTokens());
            m.put(META_TURN_CACHE_READ, t.cacheReadInputTokens());
            m.put(META_TURN_TOOL_USE_IDS, new ArrayList<>(nullSafe(t.toolUseIds())));
            m.put(META_TURN_THINKING_TOKENS, t.thinkingTokens());
            if (t.stopReason() != null) {
                m.put(META_TURN_STOP_REASON, t.stopReason());
            }
            m.put(META_TURN_INDEX, t.turnIndex());
            out.add(m);
        }
        return out;
    }

    /** Reconstructs per-turn usage from the embedded metadata (Jackson reads numbers as Number). */
    static List<TurnUsage> turnsFromMetadata(Object raw) {
        if (!(raw instanceof List<?> list)) {
            return List.of();
        }
        List<TurnUsage> turns = new ArrayList<>();
        for (Object o : list) {
            if (!(o instanceof Map<?, ?> m)) {
                continue;
            }
            turns.add(new TurnUsage(
                    asString(m.get(META_TURN_MESSAGE_ID)),
                    asString(m.get(META_TURN_MODEL)),
                    asLong(m.get(META_TURN_INPUT_TOKENS)),
                    asLong(m.get(META_TURN_OUTPUT_TOKENS)),
                    asLong(m.get(META_TURN_CACHE_CREATION)),
                    asLong(m.get(META_TURN_CACHE_READ)),
                    asStringList(m.get(META_TURN_TOOL_USE_IDS)),
                    asLong(m.get(META_TURN_THINKING_TOKENS)),
                    asString(m.get(META_TURN_STOP_REASON)),
                    asInt(m.get(META_TURN_INDEX), -1)));
        }
        return turns;
    }

    /** Whether a tool name spawns a sub-agent; a tool call with no name never does. */
    private static boolean isSubagentTool(String name) {
        return name != null && SUBAGENT_TOOLS.contains(name);
    }

    /** Observed duration for a step, or -1 when none was recorded (never 0 by default). */
    private static long durationOf(Map<String, Long> toolDurations, String toolId) {
        if (toolDurations == null) {
            return -1L;
        }
        Long d = toolDurations.get(toolId);
        return d != null ? d : -1L;
    }

    /**
     * Logs one warning when some tool calls are listed by no turn. They get no step, so their
     * cost goes to the other steps; the split itself is left as it is.
     */
    private static void warnUnlistedTools(List<TurnUsage> turns, List<ToolUseRecord> toolUses) {
        Set<String> listed = new HashSet<>();
        for (TurnUsage turn : turns) {
            listed.addAll(nullSafe(turn.toolUseIds()));
        }
        List<String> unlisted = new ArrayList<>();
        for (ToolUseRecord tu : nullSafe(toolUses)) {
            if (!listed.contains(tu.id())) {
                unlisted.add(tu.id());
            }
        }
        if (!unlisted.isEmpty()) {
            log.warn("{} tool call(s) are listed by no turn and get no step; their cost is attributed to "
                    + "the other steps: {}", unlisted.size(), unlisted);
        }
    }

    private static void warnCoarsenedOnce() {
        if (COARSENED_WARNED.compareAndSet(false, true)) {
            log.warn("No per-turn usage available (turns empty); per-step cost attribution coarsened to a "
                    + "flat even split across tool calls — stamped attributionMethod={} (not {}). "
                    + "Logged once per process.", AttributionMethod.EVEN_SPLIT,
                    AttributionMethod.OUTPUT_TOKEN_PROPORTIONAL);
        }
    }

    private static long asLong(Object o) {
        return (o instanceof Number n) ? n.longValue() : 0L;
    }

    /** Reads an int with an explicit "absent" default, so a missing ordinal never reads as turn 0. */
    private static int asInt(Object o, int absent) {
        return (o instanceof Number n) ? n.intValue() : absent;
    }

    private static String asString(Object o) {
        if (o == null) {
            return null;
        }
        return (o instanceof String s) ? s : o.toString();
    }

    private static List<String> asStringList(Object o) {
        if (!(o instanceof List<?> list)) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (Object e : list) {
            if (e != null) {
                out.add(e.toString());
            }
        }
        return out;
    }

    private static <T> List<T> nullSafe(List<T> list) {
        return list != null ? list : List.of();
    }
}
