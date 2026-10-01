package io.github.markpollack.journal.event;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

import java.time.Instant;
import java.util.Map;

/**
 * A fact about what happened during a {@link io.github.markpollack.journal.Run}, such as an LLM
 * call, a tool call or a state change. Log one with
 * {@link io.github.markpollack.journal.Run#logEvent(JournalEvent)}: the run appends it to its
 * event log and writes it to storage at once. Read a run's events back, in the order they were
 * logged, with
 * {@link io.github.markpollack.journal.storage.JournalStorage#loadEvents(String, String)}. The run
 * recorders in the capture modules, such as Claude Code's {@code RunRecorder}, log events for you
 * from a parsed agent call.
 *
 * <p>The built-in types are {@link LLMCallEvent}, {@link ToolCallEvent},
 * {@link StateChangeEvent}, {@link MetricEvent}, {@link CustomEvent}, {@link FeedbackEvent}, and
 * the four {@link GitEvent}s. {@link io.github.markpollack.journal.storage.JsonFileStorage} writes
 * each event as one JSON line in {@code events.jsonl}: the record's components as fields, plus an
 * {@code @type} field that names the type. The built-in names are {@code llm_call},
 * {@code tool_call}, {@code state_change}, {@code metric}, {@code custom}, {@code feedback},
 * {@code git_patch}, {@code git_commit}, {@code git_branch} and {@code git_pr}. Conclusions
 * computed after the run, such as the cost of each step, are not events but
 * {@link io.github.markpollack.journal.derived.DerivedEvent}s, kept in a separate stream.
 *
 * <p>The set of types is open: you can define your own event type, such as a workflow step. File
 * storage can read it back only if it is registered with
 * {@link io.github.markpollack.journal.Journal#registerEventType(String, Class)}. Register it
 * right after {@link io.github.markpollack.journal.Journal#configure}, before any run logs or
 * loads events. File storage settles its list of types the first time it reads events, so a
 * later registration is not seen. An event logged before its type is registered is written with
 * its simple class name as {@code @type}, as are later events of that type, and those lines
 * cannot be read back under the registered name. Loading fails for the whole file if any line
 * has an unknown {@code @type} or a field that its class does not have.
 * {@link io.github.markpollack.journal.storage.InMemoryStorage} keeps the event objects
 * themselves and needs no registration.
 *
 * <p>Implementations must be types that Jackson can write as a JSON object and read back, such as
 * a record with simple components; file storage writes the components, not {@link #toMap()}.
 * They should be immutable, because a logged event is kept in memory and in storage, and should
 * return a non-null {@link #timestamp()}. Return the registered type name from {@link #type()}.
 * The interface says nothing about thread safety. The built-in types are records, but
 * {@link LLMCallEvent}, {@link ToolCallEvent} and {@link CustomEvent} do not copy the maps passed
 * to them, so do not change such a map after logging the event that holds it.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "@type")
@JsonSubTypes({
        @JsonSubTypes.Type(value = LLMCallEvent.class, name = "llm_call"),
        @JsonSubTypes.Type(value = ToolCallEvent.class, name = "tool_call"),
        @JsonSubTypes.Type(value = StateChangeEvent.class, name = "state_change"),
        @JsonSubTypes.Type(value = MetricEvent.class, name = "metric"),
        @JsonSubTypes.Type(value = CustomEvent.class, name = "custom"),
        @JsonSubTypes.Type(value = GitPatchEvent.class, name = "git_patch"),
        @JsonSubTypes.Type(value = GitCommitEvent.class, name = "git_commit"),
        @JsonSubTypes.Type(value = GitBranchEvent.class, name = "git_branch"),
        @JsonSubTypes.Type(value = GitPullRequestEvent.class, name = "git_pr"),
        @JsonSubTypes.Type(value = FeedbackEvent.class, name = "feedback")
})
public interface JournalEvent {

    /**
     * Returns when the event happened. The built-in factory methods and builders use the time the
     * event or builder was created.
     *
     * @return the event's time
     */
    Instant timestamp();

    /**
     * Returns a short name for the kind of event, such as {@code "llm_call"}. For most built-in
     * types it is the {@code @type} that file storage writes, with two exceptions:
     * {@link CustomEvent} returns its own name, and {@link GitPullRequestEvent} returns
     * {@code "git_pull_request"} where file storage writes {@code "git_pr"}. File storage does not
     * call this method; the {@code @type} comes from the built-in list or the registered name.
     *
     * @return the event's type name
     */
    String type();

    /**
     * Returns the event's fields as a map with snake_case keys, such as {@code "token_usage"}, for
     * code that wants a map instead of the record. Each type chooses its keys and may leave out
     * fields, such as a tool call's input and output. File storage does not use this map.
     *
     * @return a new map of the event's fields
     */
    Map<String, Object> toMap();
}
