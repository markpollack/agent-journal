package io.github.markpollack.journal.junie;

import io.github.markpollack.journal.event.StopReason;
import io.github.markpollack.journal.event.TokenUsage;

import java.util.List;

/**
 * Captures one Junie CLI session, parsed from its durable
 * {@code ~/.junie/sessions/<sessionId>/events.jsonl} trace.
 *
 * <p>
 * <strong>Two facts about Junie that shape this record.</strong>
 * <ol>
 *   <li><strong>Cost is real and reconciles.</strong> Junie prices every LLM call and reports a
 *       session total. Σ {@link JunieModelCost#costUsd()} equals {@code totalCostUsd} bit-for-bit
 *       on all three captured fixtures, so {@link #reconcilesToModelCosts()} is a genuine closure
 *       check. This is the first non-Claude adapter in the portfolio with a vendor cost anchor
 *       rather than {@code costAvailable=false}.</li>
 *   <li><strong>Thinking tokens do not exist.</strong> Junie emits no thinking, reasoning or
 *       equivalent token count anywhere in the trace — verified by enumerating every leaf path in
 *       both the CLI and ACP captures. {@code thinkingTokens} is therefore deliberately absent
 *       from this record rather than present-and-zero, so nothing downstream can mistake "the
 *       provider does not report it" for "the run did none". Thinking <em>content</em> is a
 *       separate matter and <em>is</em> captured: see {@link #thinkingBlocks()}.</li>
 * </ol>
 *
 * @param phaseName        caller-supplied phase identifier
 * @param promptText       the task prompt. Read from the trace's own {@code UserPromptEvent} when
 *                         present (the ACP path emits one), falling back to the caller's argument
 *                         — the trace is the better source because it is what Junie actually ran.
 * @param model            the model the task was launched with, from the prompt's
 *                         {@code ModelForLaunchAttachment}; falls back to the model that carried
 *                         the most cost when the trace does not say
 * @param taskId           Junie's {@code TaskStartedEvent.taskId} (e.g. {@code task-260826-171831-hx9l})
 * @param taskName         the name Junie generated for the task
 * @param inputTokens      Σ fresh input tokens across every LLM call
 * @param outputTokens     Σ output tokens across every LLM call
 * @param cacheReadTokens  Σ {@code cacheInputTokens} — prompt-cache reads, billed but excluded
 *                         from {@code inputTokens} by Junie's own accounting
 * @param cacheCreationTokens Σ {@code cacheCreateTokens}; zero in every fixture so far
 * @param totalCostUsd     the session's own {@code completion.taskCostUsd}
 * @param durationMs       wall-clock duration of the task
 * @param numLlmCalls      count of {@code LlmResponseMetadataEvent} lines. Deliberately <em>not</em>
 *                         called {@code numTurns}: Junie has no turn concept, and one task fans out
 *                         across a main model plus internal helper models, so this counts model
 *                         calls and nothing more.
 * @param isError          whether the session ended other than by submitting a result
 * @param taskState        the ACP {@code TaskState.state}, or null on the plain-CLI path
 * @param errorCode        the result block's {@code errorCode} ({@code Submit} when the agent
 *                         finished by handing back a result)
 * @param cancelled        the result block's {@code cancelled} flag
 * @param textOutput       the agent's final markdown result
 * @param patch            the unified diff Junie produced for the session, when it created one
 * @param thinkingBlocks   {@code AgentThoughtBlockUpdatedEvent} texts, in order. Emitted on the ACP
 *                         path only — empty for a plain-CLI capture.
 * @param contextWindowUsed last reported context-window occupancy in tokens, or -1 if never reported
 * @param contextWindowSize the model's context-window size, or -1 if never reported
 * @param stopReason       normalised terminal reason; never null
 * @param maxTurns         always -1 — Junie enforces and reports no turn ceiling. Carried anyway so
 *                         the stop reason is never recorded without the ceiling it ran against.
 * @param modelCosts       per-call cost decomposition, one entry per {@code modelUsage} element
 * @param toolUses         one entry per distinct {@code stepId}, folded across its updates
 */
public record JuniePhaseCapture(
        String phaseName,
        String promptText,
        String model,
        String taskId,
        String taskName,
        int inputTokens,
        int outputTokens,
        int cacheReadTokens,
        int cacheCreationTokens,
        double totalCostUsd,
        long durationMs,
        int numLlmCalls,
        boolean isError,
        String taskState,
        String errorCode,
        boolean cancelled,
        String textOutput,
        String patch,
        List<String> thinkingBlocks,
        long contextWindowUsed,
        long contextWindowSize,
        StopReason stopReason,
        int maxTurns,
        List<JunieModelCost> modelCosts,
        List<JunieToolUseRecord> toolUses
) {

    /** Tolerance for the cost identity — a tenth of a cent, matching the Claude adapter. */
    public static final double COST_RECONCILIATION_TOLERANCE_USD = 1e-4;

    public JuniePhaseCapture {
        stopReason = stopReason != null ? stopReason : StopReason.UNKNOWN;
        thinkingBlocks = thinkingBlocks == null ? List.of() : List.copyOf(thinkingBlocks);
        modelCosts = modelCosts == null ? List.of() : List.copyOf(modelCosts);
        toolUses = toolUses == null ? List.of() : List.copyOf(toolUses);
    }

    public boolean hasToolUses() {
        return !toolUses.isEmpty();
    }

    public boolean hasThinking() {
        return !thinkingBlocks.isEmpty();
    }

    public boolean hasModelCosts() {
        return !modelCosts.isEmpty();
    }

    /**
     * The token vector for this session.
     *
     * <p>
     * {@code thinkingTokens} is passed as 0 because Junie reports no such field — see the class
     * note. The mapping is {@code cacheInputTokens -> cacheReadTokens} and
     * {@code cacheCreateTokens -> cacheCreationTokens}; folding all four fields across every call
     * reproduces the ACP {@code session/prompt} usage block exactly.
     *
     * <p>
     * Note that {@link TokenUsage#total()} is input+output+thinking and so <em>excludes</em> cache
     * reads, whereas Junie's own ACP {@code totalTokens} <em>includes</em> them
     * ({@code 24639 + 1187 + 63616 = 89442} on the ACP fixture). Neither is wrong; they are
     * different questions, and this adapter keeps journal-core's definition rather than
     * reinterpreting it.
     */
    public TokenUsage tokenUsage() {
        return new TokenUsage(inputTokens, outputTokens, 0, cacheCreationTokens, cacheReadTokens, 0);
    }

    /** Σ per-call {@code costUsd} from {@link #modelCosts()}. */
    public double modelCostSum() {
        return modelCosts.stream().mapToDouble(JunieModelCost::costUsd).sum();
    }

    /**
     * Whether the per-call cost decomposition reconciles to the session total.
     *
     * <p>
     * False when {@code modelUsage} was never seen — with nothing to reconcile against the identity
     * is unverified, and unverified must not read as verified. Check {@link #hasModelCosts()} to
     * tell the two apart.
     */
    public boolean reconcilesToModelCosts() {
        if (!hasModelCosts()) {
            return false;
        }
        return Math.abs(modelCostSum() - totalCostUsd) <= COST_RECONCILIATION_TOLERANCE_USD;
    }

    /** Whether this session was cut short rather than finishing on its own. */
    public boolean wasTruncated() {
        return stopReason != null && stopReason.isTruncatedRun();
    }
}
