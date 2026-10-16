package io.github.markpollack.journal.claude;

/**
 * How a call's prompt-cache writes split between the two cache lifetimes, as the result line's
 * {@code usage.cache_creation} reports them. The tiers are priced differently, so a cost worked out
 * from the combined {@code cacheCreationInputTokens} cannot be exact. On Claude Code 2.1.x most
 * cache writes are in the one-hour tier (87% in the frozen corpus of 45,061 messages).
 *
 * <p>A capture has none of these ({@link PhaseCapture#cacheWriteTiers()} is {@code null}) when no
 * result line reported the split; a 0 here is a reported 0. Like the other usage figures they cover
 * the main thread and are summed over the stream's result lines.
 *
 * @param fiveMinuteTokens the tokens written to the 5-minute cache ({@code ephemeral_5m_input_tokens})
 * @param oneHourTokens the tokens written to the 1-hour cache ({@code ephemeral_1h_input_tokens})
 */
public record CacheWriteTiers(long fiveMinuteTokens, long oneHourTokens) {
}
