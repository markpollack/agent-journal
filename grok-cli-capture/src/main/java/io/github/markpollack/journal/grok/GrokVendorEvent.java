package io.github.markpollack.journal.grok;

import java.util.Map;

/**
 * One line of Grok's {@code streaming-json} output that the parser does not turn into a field of the capture,
 * kept whole so that nothing Grok printed is lost at level 1: update types the parser does not know, lines
 * without a {@code type}, tool updates that name no call, and lines that are not JSON at all (such as the CLI's
 * plain-text "Not signed in").
 *
 * @param kind the line's {@code type}; {@code "unknown"} if it has none; {@code "non_json_line"} for text that
 *        is not JSON
 * @param line the 1-based line number in the output
 * @param payload the whole line as parsed JSON (maps, lists, strings, numbers, booleans); for a line that is not
 *        JSON, a map with the single key {@code text}
 */
public record GrokVendorEvent(String kind, long line, Map<String, Object> payload) {

    /** The kind of a line that is not JSON. */
    public static final String NON_JSON_LINE = "non_json_line";

    public GrokVendorEvent {
        kind = kind == null || kind.isBlank() ? "unknown" : kind;
        payload = payload == null ? Map.of() : payload;
    }
}
