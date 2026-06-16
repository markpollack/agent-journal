package io.github.markpollack.journal.gemini;

import io.github.markpollack.journal.trace.AttributionMethod;
import io.github.markpollack.journal.trace.JournalStep;

import java.util.List;

/**
 * Builds portable {@link JournalStep}s from a {@link GeminiPhaseCapture} — the Gemini analog
 * of {@code claude-code-capture}'s {@code JournalSteps}, projecting into the <em>same</em>
 * journal-core step shape so the identical analysis pipeline runs on a Gemini trace.
 *
 * <p>
 * Gemini's typed SDK exposes no tool calls (see {@link GeminiPhaseCapture}), so a query is a
 * single <strong>turn-level step</strong> carrying the turn's tokens and the run cost. The same
 * {@link AttributionMethod#OUTPUT_TOKEN_PROPORTIONAL} split rule as the Claude path applies —
 * with one step it trivially takes 100% of the cost. When/if Gemini OTEL per-tool spans are
 * captured (future), the same proportional split fans the cost across tool steps. Cost is
 * SDK-provided (pricing-derived); {@code actualRunCostUsd} stays the ground-truth total.
 *
 * <p>
 * Gemini has no native per-step id (no tool_use id / message id in the typed model), so the
 * {@code stepId} is synthesized from the phase ({@code {phase}:turn}) — deterministic for a
 * given capture. (Contrast the Claude path, where the tool_use id is a real stable id.)
 */
public final class GeminiJournalSteps {

    /** Vendor tag for Gemini CLI captures. */
    public static final String VENDOR_GEMINI_CLI = "gemini-cli";

    private GeminiJournalSteps() {
    }

    public static List<JournalStep> fromPhaseCapture(GeminiPhaseCapture phase, String runId) {
        return fromPhaseCapture(phase, runId, VENDOR_GEMINI_CLI);
    }

    public static List<JournalStep> fromPhaseCapture(GeminiPhaseCapture phase, String runId, String vendor) {
        String stepId = runId + ":turn";
        JournalStep turn = new JournalStep(
                runId,
                stepId,
                stepId,
                null,
                phase.promptTokens(),
                phase.completionTokens(),
                phase.totalCostUsd(),
                phase.totalCostUsd(),
                AttributionMethod.OUTPUT_TOKEN_PROPORTIONAL,
                phase.isError(),
                null,
                vendor,
                false);
        return List.of(turn);
    }
}
