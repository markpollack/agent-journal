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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Builds attributed per-step {@link JournalStep}s from a {@link PhaseCapture} (capture-time) or,
 * equivalently, from the persisted immutable event log (offline). Both entry points run the
 * <em>same</em> {@link #attribute} core, so {@link #fromEvents(List, String)} is provably equal to
 * {@link #fromPhaseCapture(PhaseCapture, String)} — the closure that makes a lost
 * {@code analysis.jsonl} recoverable (DESIGN §2.2).
 *
 * <p>
 * Claude Code reports no per-step cost — only a run total. {@link AttributionMethod#OUTPUT_TOKEN_PROPORTIONAL}
 * splits that real total across steps: each model turn gets a share proportional to its
 * output tokens (the dominant cost driver), and a turn with several parallel tool calls
 * splits its share evenly among them. A tool-less turn becomes one turn-level step. The
 * per-step shares sum to the run total exactly — the float residual is folded into the last
 * step — so {@code actualRunCostUsd} stays ground truth and the per-step number is an
 * auditable allocation, never mistaken for a measured cost.
 *
 * <p>
 * Single-pool by design: the run total is split across all turns regardless of model. This
 * sidesteps the model-id mismatch between the per-turn {@code message.model}
 * ({@code claude-opus-4-8}) and the {@code modelUsage} key ({@code claude-opus-4-8[1m]}) and
 * guarantees the sum stays true; the exact per-model decomposition is preserved separately
 * in {@link PhaseCapture#modelCosts()} for finer analysis.
 *
 * <p>
 * <strong>Offline re-derivation ({@link #fromEvents}).</strong> The immutable execution log carries
 * everything the split needs: the per-phase {@link LLMCallEvent} carries the run total (its cost) and
 * the per-turn breakdown (additive {@code metadata.turns}, written by {@code BaseRunRecorder}); the
 * trailing {@link ToolCallEvent}s carry each step's stable id, tool name, and error bit. So a persisted
 * journal regenerates per-step cost with no access to the transient {@link PhaseCapture}.
 */
public final class JournalSteps {

    private static final Logger log = LoggerFactory.getLogger(JournalSteps.class);

    /** Vendor tag for Claude Code captures. */
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

    /** Logged at most once per process when attribution is coarsened (turns absent). */
    private static final AtomicBoolean COARSENED_WARNED = new AtomicBoolean(false);

    private JournalSteps() {
    }

    public static List<JournalStep> fromPhaseCapture(PhaseCapture phase, String runId) {
        return fromPhaseCapture(phase, runId, VENDOR_CLAUDE_CODE);
    }

    public static List<JournalStep> fromPhaseCapture(PhaseCapture phase, String runId, String vendor) {
        Map<String, Boolean> toolErrors = new LinkedHashMap<>();
        for (ToolResultRecord tr : nullSafe(phase.toolResults())) {
            toolErrors.put(tr.toolUseId(), tr.isError());
        }
        return attribute(phase.turns(), nullSafe(phase.toolUses()), toolErrors, phase.totalCostUsd(), runId, vendor);
    }

    /**
     * Offline re-derivation from the persisted immutable execution log. Reconstructs each phase's
     * {@code (turns, toolUses, toolErrors, runTotal)} from the {@link LLMCallEvent}/{@link ToolCallEvent}
     * stream and runs the same {@link #attribute} core as {@link #fromPhaseCapture}, so the result is
     * equal step-for-step. A run with several phases re-derives each phase independently (residual
     * folded per phase), concatenated in stream order.
     *
     * @param events the run's execution events ({@code events.jsonl}), in order
     * @param runId  the run these steps belong to
     * @return the per-step attributed costs, equal to {@code fromPhaseCapture} on the same capture
     */
    public static List<JournalStep> fromEvents(List<JournalEvent> events, String runId) {
        return fromEvents(events, runId, VENDOR_CLAUDE_CODE);
    }

    public static List<JournalStep> fromEvents(List<JournalEvent> events, String runId, String vendor) {
        List<JournalStep> steps = new ArrayList<>();
        // The per-phase LLMCallEvent anchors a phase; the ToolCallEvents that follow it (until the
        // next LLMCallEvent) are that phase's tool steps. Non-execution lines are ignored.
        LLMCallEvent currentLlm = null;
        List<ToolUseRecord> phaseTools = new ArrayList<>();
        Map<String, Boolean> phaseToolErrors = new LinkedHashMap<>();
        for (JournalEvent e : nullSafe(events)) {
            if (e instanceof LLMCallEvent llm) {
                if (currentLlm != null) {
                    steps.addAll(attributePhase(currentLlm, phaseTools, phaseToolErrors, runId, vendor));
                }
                currentLlm = llm;
                phaseTools = new ArrayList<>();
                phaseToolErrors = new LinkedHashMap<>();
            } else if (e instanceof ToolCallEvent tc && currentLlm != null) {
                phaseTools.add(new ToolUseRecord(tc.id(), tc.kind(), tc.toolName(), Map.of()));
                if (tc.id() != null) {
                    phaseToolErrors.put(tc.id(), !tc.success());
                }
            }
        }
        if (currentLlm != null) {
            steps.addAll(attributePhase(currentLlm, phaseTools, phaseToolErrors, runId, vendor));
        }
        return steps;
    }

    /**
     * Offline re-derivation of the cost-bearing token aggregate from the persisted immutable log —
     * the usage analog of {@link #fromEvents} (the slice-1 closure, now for usage). Sums each
     * per-phase {@link LLMCallEvent}'s {@code META_TURNS} by token type across the run; per phase this
     * equals {@link PhaseCapture#aggregateUsage()} (thinking read back from the recorded headline,
     * since the per-turn wire carries none). An {@link LLMCallEvent} without {@code META_TURNS}
     * (no per-turn truth) contributes its recorded headline vector as-is.
     *
     * @param events the run's execution events ({@code events.jsonl})
     * @return the run-level cost-bearing aggregate, regenerable from {@code events.jsonl} alone
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
            int thinking = llm.tokenUsage() != null ? llm.tokenUsage().thinkingTokens() : 0;
            total = total.plus(new TokenUsage(summed.inputTokens(), summed.outputTokens(), thinking,
                    summed.cacheCreationTokens(), summed.cacheReadTokens(), summed.toolUseTokens()));
        }
        return total;
    }

    private static List<JournalStep> attributePhase(LLMCallEvent llm, List<ToolUseRecord> tools,
            Map<String, Boolean> toolErrors, String runId, String vendor) {
        Object rawTurns = (llm.metadata() != null) ? llm.metadata().get(META_TURNS) : null;
        return attribute(turnsFromMetadata(rawTurns), tools, toolErrors, llm.totalCostUsd(), runId, vendor);
    }

    /**
     * The single attribution core shared by capture-time and offline derivation. Given the run total,
     * the per-turn output tokens (the split weights), the per-turn tool grouping, and the tool id→name
     * / id→error maps, it produces the per-step shares and folds the float residual into the last step.
     */
    private static List<JournalStep> attribute(List<TurnUsage> turns, List<ToolUseRecord> toolUses,
            Map<String, Boolean> toolErrors, double actualCost, String runId, String vendor) {
        Map<String, String> toolNames = new LinkedHashMap<>();
        for (ToolUseRecord tu : nullSafe(toolUses)) {
            toolNames.put(tu.id(), tu.name());
        }

        List<JournalStep> steps = new ArrayList<>();

        if (turns != null && !turns.isEmpty()) {
            // Precise: split the run total across turns by output tokens; the within-turn even split
            // among parallel tools is part of this proportional method (A1) → OUTPUT_TOKEN_PROPORTIONAL.
            final AttributionMethod method = AttributionMethod.OUTPUT_TOKEN_PROPORTIONAL;
            long totalOutput = turns.stream().mapToLong(TurnUsage::outputTokens).sum();
            for (TurnUsage turn : turns) {
                double weight = totalOutput > 0 ? (double) turn.outputTokens() / totalOutput : 1.0 / turns.size();
                double turnCost = actualCost * weight;
                List<String> tools = turn.toolUseIds();
                if (tools == null || tools.isEmpty()) {
                    // Tool-less turn (e.g. final text answer) → one turn-level step.
                    steps.add(new JournalStep(runId, turn.messageId(), turn.messageId(), null,
                            turn.inputTokens(), turn.outputTokens(), turnCost, actualCost, method, false, null,
                            vendor, false));
                } else {
                    double perTool = turnCost / tools.size();
                    for (String toolId : tools) {
                        String toolName = toolNames.get(toolId);
                        steps.add(new JournalStep(runId, turn.messageId(), toolId, toolName,
                                turn.inputTokens(), turn.outputTokens(), perTool, actualCost, method,
                                Boolean.TRUE.equals(toolErrors.get(toolId)), null, vendor,
                                SUBAGENT_TOOLS.contains(toolName)));
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
                    steps.add(new JournalStep(runId, null, tu.id(), tu.name(), 0, 0, perTool, actualCost, method,
                            Boolean.TRUE.equals(toolErrors.get(tu.id())), null, vendor,
                            SUBAGENT_TOOLS.contains(tu.name())));
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
                    last.vendor(), last.isSubagentSpawn()));
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
                    asStringList(m.get(META_TURN_TOOL_USE_IDS))));
        }
        return turns;
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
