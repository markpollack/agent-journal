package io.github.markpollack.journal.trace;

/**
 * Whether a capture trace also keeps each vendor message exactly as it arrived, next to the lines
 * the parser builds from it. Only Claude Code's {@code SessionLogParser} takes this setting; the
 * other parsers never write raw lines. It is independent of {@link TraceContentMode}, which
 * applies only to the parsed lines. {@link TraceWriter} applies it.
 *
 * <p>Raw lines keep fields that the parsed lines and the SDK's typed messages drop, such as
 * {@code permission_denials} and {@code modelUsage}, so they can be recovered later. Each one is a
 * line of type {@code raw}, so a reader that looks only for other line types is not affected. The
 * header line records the mode. A message is kept as parsed JSON, or, if it is not valid JSON, as
 * a string with a {@code rawParseError} field. Raw lines need claude-code-sdk 1.3.0 or later; with
 * older versions, messages carry no original JSON and no raw lines are written.
 *
 * <p>A raw line holds the whole message, unredacted and never shortened. Treat traces written with
 * {@link #FULL} as sensitive files.
 */
public enum TraceRawMode {

    /**
     * Writes no raw lines. The parsers use this mode when no mode is given.
     */
    NONE,

    /**
     * Writes one {@code raw} line for each message that carries its original JSON.
     */
    FULL

}
