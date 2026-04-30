package io.github.markpollack.journal.event;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

import java.time.Instant;
import java.util.Map;

/**
 * Base interface for all journal events.
 *
 * <p>Core event types registered via {@code @JsonSubTypes}:
 * <ul>
 *   <li>{@link LLMCallEvent} - LLM API call with token/cost/timing metrics</li>
 *   <li>{@link ToolCallEvent} - Tool execution record</li>
 *   <li>{@link StateChangeEvent} - State transition record</li>
 *   <li>{@link MetricEvent} - Metric data point</li>
 *   <li>{@link CustomEvent} - User-defined events</li>
 *   <li>{@link GitEvent} - Git operations (patch, commit, branch, PR)</li>
 * </ul>
 *
 * <p>Domain-specific event types (e.g., {@code WorkflowStepEvent} from workflow-journal)
 * can be registered at startup via {@link io.github.markpollack.journal.Journal#registerEventType}.
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
        @JsonSubTypes.Type(value = GitPullRequestEvent.class, name = "git_pr")
})
public interface JournalEvent {

    /** Returns the event timestamp. */
    Instant timestamp();

    /** Returns the event type name (e.g., "llm_call", "tool_call"). */
    String type();

    /** Returns event attributes as a map for serialization. */
    Map<String, Object> toMap();
}
