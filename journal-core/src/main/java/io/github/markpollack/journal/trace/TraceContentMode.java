package io.github.markpollack.journal.trace;

/**
 * Content capture policy for the JSONL trace files written by {@code TraceWriter}
 * (schema v2).
 *
 * <p>
 * All modes emit schema v2: a {@code header} first line (run identity, schema version,
 * content mode) and an enriched {@code result} line. The mode controls only whether
 * large content bodies (thinking text, assistant text, tool_result content) are written.
 * v1 compatibility is contractual at the loader level — existing keys and line types are
 * preserved byte-for-byte and all v2 fields are additive — not byte-for-byte file
 * identity. In particular, {@link #LENGTHS} is <strong>not</strong> the v1 format.
 *
 * <p>
 * <strong>Sensitivity:</strong> content-carrying traces may include prompts, file
 * contents, command output, secrets, and customer data. {@link #TRUNCATED} (the
 * default) is intended for local/dev analysis; treat {@link #FULL} traces as sensitive
 * artifacts. A future {@code REDACTED}/{@code FILTERED} mode is reserved for
 * privacy-preserving capture but is not implemented in this round.
 */
public enum TraceContentMode {

    /**
     * Full content bodies, never truncated. Trace lines can grow to megabytes (large
     * file reads/writes); treat the resulting files as sensitive artifacts.
     */
    FULL,

    /**
     * Default. Content bodies are truncated at 60,000 characters per item (matching
     * Claude Code's own OTel per-item cap). The canonical length fields
     * ({@code length}, {@code contentLength}) always carry the original pre-truncation
     * size, and truncated tool results carry a {@code source} pointer to where the
     * full content lives (file path or command).
     */
    TRUNCATED,

    /**
     * No content bodies — lengths only. Pins the pre-content-capture disk footprint
     * for cost-budget consumers. Still schema v2: the header line and enriched result
     * fields are written.
     */
    LENGTHS

}
