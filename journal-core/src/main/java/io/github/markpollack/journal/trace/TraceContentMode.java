package io.github.markpollack.journal.trace;

/**
 * How much message content a capture trace keeps: all of it, the start of each item, or only
 * lengths. Pass it to a session parser that writes a trace, such as Claude Code's
 * {@code SessionLogParser} or {@code GeminiSessionParser}; {@link TraceWriter} applies it. Use
 * {@link #LENGTHS} for small traces, and {@link #FULL} only when you need every character.
 * Whether the trace also keeps each vendor message as it arrived is set separately, by
 * {@link TraceRawMode}.
 *
 * <p>The mode applies to the content of {@code text}, {@code thinking} and {@code tool_result}
 * lines. Every mode writes the same line types, and the header line records the mode. Each of
 * those lines records the content's original length in {@code length} or {@code contentLength},
 * so a reader can tell how much was left out. Tool inputs on {@code tool_use} lines are written
 * whole in every mode.
 *
 * <p>Traces can hold prompts, file contents, command output and secrets. A {@link #LENGTHS}
 * trace still holds tool inputs, which can include file contents, so treat every trace as a
 * sensitive file.
 */
public enum TraceContentMode {

    /**
     * Keeps every content item whole. A line can be very large, for example when the agent reads
     * or writes a big file.
     */
    FULL,

    /**
     * Keeps the first {@link TraceWriter#MAX_TRACE_CONTENT_CHARS} (60,000) characters of each
     * content item and marks a shortened item with {@code "truncated": true}. A shortened tool
     * result also records where the full content can be found, such as a file path or a command,
     * when the parser supplies one. The parsers use this mode when no mode is given.
     */
    TRUNCATED,

    /**
     * Keeps no content, only each item's length.
     */
    LENGTHS

}
