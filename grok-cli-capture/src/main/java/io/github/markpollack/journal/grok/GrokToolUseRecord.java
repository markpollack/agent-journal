package io.github.markpollack.journal.grok;

import io.github.markpollack.journal.event.ToolKind;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One tool call made by the Grok agent during a phase: the tool's name and {@link ToolKind}, its
 * input and output, its last status, and whether it failed. {@link GrokSessionParser} builds one
 * per {@code toolCallId}, joining a {@code tool_call} line with its {@code tool_call_update}
 * lines, and {@link GrokPhaseCapture#toolUses()} holds them in the order they first appeared.
 * {@link GrokRunRecorder} logs each one as a
 * {@link io.github.markpollack.journal.event.ToolCallEvent}, and {@link GrokJournalSteps} makes
 * one step of each.
 *
 * <p>It is the Grok counterpart of Claude Code's {@code ToolUseRecord}. Unlike that record, it
 * also holds the output, the status and the error, because Grok reports them as updates to the
 * same call rather than as a separate tool result; and it has no turn ID or turn number. Grok
 * reports the kind itself, in the Agent Client Protocol's terms, so it is not worked out from
 * the tool name.
 *
 * <p>The parser sets {@code isError} when an update has the status {@code failed}, ignoring case,
 * and it stays set even if a later update reports another status; {@code errorMessage} comes from
 * that update's raw output. The record does not check {@code isError} against {@code status}. A
 * {@code null} kind becomes {@link ToolKind#OTHER}, and {@code input} is copied into an
 * unmodifiable map, with {@code null} becoming an empty map. {@code output} is not copied.
 *
 * @param id Grok's ID for the tool call (its {@code toolCallId})
 * @param name Grok's name for the tool; the parser uses {@code "unknown"} when Grok gives none
 * @param kind the tool's category, as Grok reports it; never {@code null}
 * @param input the tool's input parameters; never {@code null}
 * @param output the tool's last reported output, or {@code null} if none was reported
 * @param status the last status Grok reported, such as {@code completed} or {@code failed}, or
 *        {@code null}
 * @param isError whether the call failed
 * @param errorMessage the error text, or {@code null}
 */
public record GrokToolUseRecord(
        String id,
        String name,
        ToolKind kind,
        Map<String, Object> input,
        Object output,
        String status,
        boolean isError,
        String errorMessage
) {

    /**
     * Creates a record from all of its parts. A {@code null} kind becomes {@link ToolKind#OTHER},
     * and {@code input} is copied into an unmodifiable map, with {@code null} becoming an empty
     * map.
     */
    public GrokToolUseRecord {
        kind = kind != null ? kind : ToolKind.OTHER;
        input = input == null
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(input));
    }

    /**
     * Returns the tool's category as a lowercase string, such as {@code "read"} or
     * {@code "execute"}. It is kept for code written when the category was a string; new code
     * should use {@link #kind()}.
     *
     * @return the {@linkplain ToolKind#wireValue() wire value} of {@link #kind()}
     */
    public String classification() {
        return kind.wireValue();
    }
}
