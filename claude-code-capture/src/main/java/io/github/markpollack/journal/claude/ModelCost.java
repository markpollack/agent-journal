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
 * The parser reads a missing value as 0.
 *
 * @param model the model name as Claude Code reports it, such as {@code claude-opus-4-8[1m]}
 * @param inputTokens the input tokens billed for this model over the whole call
 * @param outputTokens the output tokens billed for this model over the whole call
 * @param cacheReadInputTokens the tokens read from the prompt cache for this model
 * @param cacheCreationInputTokens the tokens written to the prompt cache for this model
 * @param costUsd this model's cost, in US dollars
 */
public record ModelCost(
        String model,
        long inputTokens,
        long outputTokens,
        long cacheReadInputTokens,
        long cacheCreationInputTokens,
        double costUsd
) {
}
