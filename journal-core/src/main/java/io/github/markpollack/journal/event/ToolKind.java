package io.github.markpollack.journal.event;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

import java.util.Locale;

/**
 * Cross-vendor tool categories mirrored from ACP's {@code AcpSchema.ToolKind}.
 *
 * <p>The ACP dependency is deliberately absent from the main classpath. A test-scope contract
 * check compares both enum sets so an ACP vocabulary change fails the build.</p>
 */
public enum ToolKind {

    READ("read"),
    EDIT("edit"),
    DELETE("delete"),
    MOVE("move"),
    SEARCH("search"),
    EXECUTE("execute"),
    THINK("think"),
    FETCH("fetch"),
    SWITCH_MODE("switch_mode"),
    OTHER("other");

    private final String wireValue;

    ToolKind(String wireValue) {
        this.wireValue = wireValue;
    }

    /** Returns ACP's lowercase JSON spelling. */
    @JsonValue
    public String wireValue() {
        return wireValue;
    }

    /**
     * Reads an ACP wire value, returning {@link #OTHER} for absent or future values.
     *
     * @param value ACP's lowercase value
     * @return the corresponding canonical kind, or {@code OTHER}
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
