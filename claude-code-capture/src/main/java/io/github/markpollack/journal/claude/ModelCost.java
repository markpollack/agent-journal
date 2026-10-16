package io.github.markpollack.journal.claude;

/**
 * The cost and token usage of one model during a Claude Code call. A call can use more than one
 * model, such as a main model and a smaller, faster one, each billed at its own rate.
 * {@link SessionLogParser} reads one per model from the {@code modelUsage} object of the result
 * message, and {@link PhaseCapture#modelCosts()} holds them.
 *
 * <p>This is the exact split of the call's cost that Claude Code reports: the {@code costUsd}
 * values add up to the call's total cost, up to rounding, which
 * {@link PhaseCapture#reconcilesToModelCosts()} checks. {@link JournalSteps} does not use them;
 * it splits the total across turns whatever their model. A model name here can differ from the
 * name on the turns ({@link TurnUsage#model()}), for example by a suffix such as {@code [1m]}.
 * The parser reads a missing value as 0; {@code reported} holds the entry exactly as the result
 * line carried it, so a missing value can be told from a 0 there.
 *
 * @param model the model name as Claude Code reports it, such as {@code claude-opus-4-8[1m]}
 * @param inputTokens the input tokens billed for this model over the whole call
 * @param outputTokens the output tokens billed for this model over the whole call
 * @param cacheReadInputTokens the tokens read from the prompt cache for this model
 * @param cacheCreationInputTokens the tokens written to the prompt cache for this model
 * @param costUsd this model's cost, in US dollars
 * @param reported the model's whole {@code modelUsage} entry as the result line carried it (session running totals
 *        when the capture was rebased with {@link SessionLogParser#withSessionBaseline}): every field the CLI
 *        reported, such as {@code canonicalModel}, {@code provider}, {@code costBasis}, {@code contextWindow},
 *        {@code maxOutputTokens}, {@code thinkingTokens} and {@code webSearchRequests}; empty if none
 */
public record ModelCost(
        String model,
        long inputTokens,
        long outputTokens,
        long cacheReadInputTokens,
        long cacheCreationInputTokens,
        double costUsd,
        java.util.Map<String, Object> reported
) {

    public ModelCost {
        reported = reported == null ? java.util.Map.of()
                : java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(reported));
    }

    /** The model's canonical name as the CLI reports it, such as {@code claude-haiku-4-5}, or {@code null} if it did not. */
    public String canonicalModel() {
        return reported.get("canonicalModel") instanceof String name ? name : null;
    }

    /** The provider as the CLI reports it, such as {@code firstParty}, or {@code null} if it did not. */
    public String provider() {
        return reported.get("provider") instanceof String name ? name : null;
    }

    /** How the CLI says it priced this model, such as {@code list}, or {@code null} if it did not. */
    public String costBasis() {
        return reported.get("costBasis") instanceof String name ? name : null;
    }
}
