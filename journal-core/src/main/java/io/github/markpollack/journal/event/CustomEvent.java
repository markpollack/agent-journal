package io.github.markpollack.journal.event;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Records something that no built-in event type covers: a name and a map of attributes, both
 * chosen by the caller. Log one with
 * {@link io.github.markpollack.journal.Run#logEvent(JournalEvent)}, built with
 * {@link #of(String)} or {@link #of(String, Map)}. The run recorders use it for the prompt of each
 * phase, when it is known (name {@code "prompt"}, attributes {@code phase} and {@code text}), and
 * the Claude Code and Junie recorders for each thinking block (name {@code "thinking_block"},
 * attributes {@code phase} and {@code content}). Read it back with
 * {@link io.github.markpollack.journal.storage.JournalStorage#loadEvents(String, String)};
 * {@link io.github.markpollack.journal.eval.EvalSubjectSources} turns it into a {@code CUSTOM}
 * evaluation subject with the attributes as metadata.
 *
 * <p>File storage writes every custom event with {@code @type} {@code "custom"} and the name in a
 * {@code name} field, but {@link #type()} returns the name. Attribute values must be types that
 * Jackson can write, and they come back as plain JSON values: strings, numbers, booleans, lists
 * and maps. For an event with typed fields of its own, implement {@link JournalEvent} instead.
 *
 * <p>The record does not copy {@code attributes} and accepts {@code null}, but {@link #toMap()}
 * then throws {@link NullPointerException}.
 *
 * @param timestamp when the event happened
 * @param name the event's name, such as {@code "prompt"}
 * @param attributes the event's data, keyed by name
 */
public record CustomEvent(
        Instant timestamp,
        String name,
        Map<String, Object> attributes
) implements JournalEvent {

    /**
     * Returns the event's name, such as {@code "prompt"}. Unlike the other built-in events, a
     * custom event's type name is not the {@code @type} that file storage writes, which is always
     * {@code "custom"}.
     *
     * @return the event's name
     */
    @Override
    public String type() {
        return name;
    }

    /**
     * Creates a custom event with the current time. The map is not copied.
     *
     * @param name the event's name
     * @param attributes the event's data, keyed by name
     * @return the new event
     */
    public static CustomEvent of(String name, Map<String, Object> attributes) {
        return new CustomEvent(Instant.now(), name, attributes);
    }

    /**
     * Creates a custom event with the current time and no attributes.
     *
     * @param name the event's name
     * @return the new event
     */
    public static CustomEvent of(String name) {
        return new CustomEvent(Instant.now(), name, Map.of());
    }

    /**
     * {@inheritDoc}
     *
     * <p>The keys are {@code type} (the name) and {@code timestamp}, then every attribute at the
     * top level. An attribute named {@code type} or {@code timestamp} replaces that key.
     *
     * @throws NullPointerException if {@link #timestamp()} or {@link #attributes()} is
     *         {@code null}
     */
    @Override
    public Map<String, Object> toMap() {
        var map = new LinkedHashMap<String, Object>();
        map.put("type", name);
        map.put("timestamp", timestamp.toString());
        map.putAll(attributes);
        return map;
    }
}
