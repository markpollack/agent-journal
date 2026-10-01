package io.github.markpollack.journal.event;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

import java.util.Locale;

/**
 * The vendor-neutral category of a tool call, such as {@link #READ}, {@link #EDIT} or
 * {@link #EXECUTE}. The capture modules set it on every {@link ToolCallEvent} they log, from the
 * vendor's tool name or the vendor's own category, and keep the vendor's name in
 * {@link ToolCallEvent#toolName()}, so tool calls from different agents can be grouped and
 * compared. A tool the capture module does not know is {@link #OTHER}.
 *
 * <p>The values are those of the Agent Client Protocol (ACP) {@code ToolKind}, copied so that
 * journal-core does not depend on ACP; a test checks that both lists still match. JSON uses the
 * lowercase {@linkplain #wireValue() wire value}, such as {@code "switch_mode"}, and reading a
 * missing or unknown value gives {@link #OTHER}, so events written with a newer list still load.
 */
public enum ToolKind {

    /** Reads a file or other data without changing it, such as Claude Code's {@code Read}. */
    READ("read"),
    /** Writes or changes the content of a file, such as Claude Code's {@code Edit}. */
    EDIT("edit"),
    /** Deletes a file or other data. */
    DELETE("delete"),
    /** Moves or renames a file. */
    MOVE("move"),
    /** Searches files or the web, such as Claude Code's {@code Grep} or {@code WebSearch}. */
    SEARCH("search"),
    /** Runs a command or code, such as Claude Code's {@code Bash}. */
    EXECUTE("execute"),
    /**
     * Plans, reasons or hands work to a sub-agent, such as Claude Code's {@code TodoWrite} or
     * {@code Task}.
     */
    THINK("think"),
    /** Retrieves outside data, such as a web page with Claude Code's {@code WebFetch}. */
    FETCH("fetch"),
    /** Switches the agent's mode, such as entering or leaving Claude Code's plan mode. */
    SWITCH_MODE("switch_mode"),
    /** Any other tool, or one whose category is not known. */
    OTHER("other");

    private final String wireValue;

    ToolKind(String wireValue) {
        this.wireValue = wireValue;
    }

    /**
     * Returns the lowercase name that JSON and ACP use for this kind, such as
     * {@code "switch_mode"}.
     *
     * @return the wire value
     */
    @JsonValue
    public String wireValue() {
        return wireValue;
    }

    /**
     * Returns the kind for a wire value. Case and surrounding spaces are ignored, so the constant
     * name, such as {@code "READ"}, works too. JSON reading uses this method.
     *
     * @param value the wire value, such as {@code "read"}
     * @return the matching kind, or {@link #OTHER} if {@code value} is {@code null}, blank or not
     *         known
     */
    @JsonCreator
    public static ToolKind fromWireValue(String value) {
        if (value == null || value.isBlank()) {
            return OTHER;
        }
        try {
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return OTHER;
        }
    }
}
