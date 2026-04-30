package io.github.markpollack.journal.test;

import io.github.markpollack.journal.event.*;
import io.github.markpollack.journal.event.GitPatchEvent.FileChange;
import io.github.markpollack.journal.metric.Tags;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Factory for creating test event instances.
 *
 * <p>Provides realistic sample events for all event types.
 * Use these in tests to avoid boilerplate construction.
 *
 * <p>Example:
 * <pre>{@code
 * @Test
 * void shouldSerializeLLMCallEvent() {
 *     LLMCallEvent event = TestEvents.llmCall();
 *     String json = serializer.toJson(event);
 *     assertThat(json).contains("claude-opus-4.5");
 * }
 * }</pre>
 */
public final class TestEvents {

    // Standard test values
    public static final String DEFAULT_MODEL = "claude-opus-4.5";
    public static final String DEFAULT_PROVIDER = "anthropic";
    public static final int DEFAULT_INPUT_TOKENS = 1200;
    public static final int DEFAULT_OUTPUT_TOKENS = 450;
    public static final double DEFAULT_COST_USD = 0.023;
    public static final long DEFAULT_DURATION_MS = 2500;

    private TestEvents() {} // Utility class

    // ========== LLMCallEvent ==========

    /** Creates a minimal LLMCallEvent. */
    public static LLMCallEvent llmCall() {
        return LLMCallEvent.of(DEFAULT_MODEL, DEFAULT_INPUT_TOKENS, DEFAULT_OUTPUT_TOKENS, DEFAULT_COST_USD);
    }

    /** Creates an LLMCallEvent with extended thinking tokens. */
    public static LLMCallEvent llmCallWithThinking() {
        return LLMCallEvent.builder()
                .provider(DEFAULT_PROVIDER)
                .model(DEFAULT_MODEL)
                .tokenUsage(new TokenUsage(
                        DEFAULT_INPUT_TOKENS,
                        DEFAULT_OUTPUT_TOKENS,
                        500,  // thinking tokens
                        0, 0, 0))
                .cost(new CostBreakdown(0.015, 0.045, 0.010, 0.0))
                .timing(TimingInfo.of(DEFAULT_DURATION_MS))
                .finishReason("stop")
                .build();
    }

    /** Creates an LLMCallEvent with cache usage. */
    public static LLMCallEvent llmCallWithCache() {
        return LLMCallEvent.builder()
                .provider(DEFAULT_PROVIDER)
                .model(DEFAULT_MODEL)
                .tokenUsage(new TokenUsage(
                        DEFAULT_INPUT_TOKENS,
                        DEFAULT_OUTPUT_TOKENS,
                        0,
                        500,  // cache creation tokens
                        300,  // cache read tokens
                        0))
                .cost(new CostBreakdown(0.010, 0.045, 0.0, 0.005))
                .timing(TimingInfo.of(DEFAULT_DURATION_MS, 2200, 800))
                .finishReason("stop")
                .responseId("msg_test123")
                .build();
    }

    /** Creates a fully populated LLMCallEvent. */
    public static LLMCallEvent llmCallComplete() {
        return LLMCallEvent.builder()
                .provider(DEFAULT_PROVIDER)
                .model(DEFAULT_MODEL)
                .tokenUsage(new TokenUsage(
                        DEFAULT_INPUT_TOKENS,
                        DEFAULT_OUTPUT_TOKENS,
                        200,  // thinking
                        100,  // cache creation
                        50,   // cache read
                        30))  // tool use
                .cost(new CostBreakdown(0.015, 0.045, 0.010, 0.002))
                .timing(new TimingInfo(DEFAULT_DURATION_MS, 2200, 800))
                .finishReason("stop")
                .responseId("msg_complete123")
                .metadata(Map.of("service_tier", "standard", "model_version", "2024-01"))
                .build();
    }

    // ========== ToolCallEvent ==========

    /** Creates a successful Bash tool call. */
    public static ToolCallEvent bashSuccess() {
        return ToolCallEvent.success(
                "Bash",
                Map.of("command", "ls -la"),
                "file1.txt\nfile2.txt\nfile3.txt",
                150
        );
    }

    /** Creates a successful Read tool call. */
    public static ToolCallEvent readSuccess() {
        return ToolCallEvent.success(
                "Read",
                Map.of("file_path", "/home/user/project/src/Main.java"),
                "public class Main { ... }",
                50
        );
    }

    /** Creates a successful Write tool call. */
    public static ToolCallEvent writeSuccess() {
        return ToolCallEvent.success(
                "Write",
                Map.of("file_path", "/home/user/project/src/New.java", "content", "public class New {}"),
                null,
                75
        );
    }

    /** Creates a failed tool call. */
    public static ToolCallEvent toolFailure() {
        return ToolCallEvent.failure(
                "Bash",
                Map.of("command", "rm -rf /protected"),
                "Permission denied",
                100
        );
    }

    // ========== StateChangeEvent ==========

    /** Creates a state change from planning to implementing. */
    public static StateChangeEvent stateChangePlanToImplement() {
        return StateChangeEvent.of("planning", "implementing", "plan approved");
    }

    /** Creates a state change from implementing to testing. */
    public static StateChangeEvent stateChangeImplementToTest() {
        return StateChangeEvent.of("implementing", "testing", "implementation complete");
    }

    /** Creates a state change from testing to completed. */
    public static StateChangeEvent stateChangeTestToComplete() {
        return StateChangeEvent.of("testing", "completed", "all tests pass");
    }

    // ========== MetricEvent ==========

    /** Creates a token count metric. */
    public static MetricEvent tokenMetric() {
        return MetricEvent.of("tokens.total", 1650);
    }

    /** Creates a cost metric. */
    public static MetricEvent costMetric() {
        return MetricEvent.of("cost.usd", 0.023);
    }

    /** Creates a metric with tags. */
    public static MetricEvent metricWithTags() {
        return MetricEvent.of(
                "llm.latency_ms",
                DEFAULT_DURATION_MS,
                Tags.of("model", DEFAULT_MODEL, "provider", DEFAULT_PROVIDER)
        );
    }

    // ========== CustomEvent ==========

    /** Creates a custom checkpoint event. */
    public static CustomEvent checkpointEvent() {
        return CustomEvent.of("checkpoint", Map.of(
                "phase", "implementation",
                "progress", 0.5,
                "filesModified", 3
        ));
    }

    /** Creates a custom decision event. */
    public static CustomEvent decisionEvent() {
        return CustomEvent.of("decision", Map.of(
                "choice", "use_existing_api",
                "alternatives", List.of("create_new_api", "modify_interface"),
                "rationale", "Minimizes breaking changes"
        ));
    }

    // ========== GitPatchEvent ==========

    /** Creates a simple patch event with file modifications. */
    public static GitPatchEvent gitPatch() {
        return GitPatchEvent.of("main", List.of(
                FileChange.modified("src/Main.java", 25, 10),
                FileChange.added("src/NewFeature.java", 50)
        ));
    }

    /** Creates a patch event with full diff content. */
    public static GitPatchEvent gitPatchWithContent() {
        String patchContent = """
                diff --git a/src/Main.java b/src/Main.java
                --- a/src/Main.java
                +++ b/src/Main.java
                @@ -10,5 +10,10 @@
                +    // New code here
                """;
        return GitPatchEvent.withPatch("main", List.of(
                FileChange.modified("src/Main.java", 5, 0)
        ), patchContent);
    }

    /** Creates a patch event with deletions. */
    public static GitPatchEvent gitPatchWithDeletions() {
        return GitPatchEvent.of("feature/cleanup", List.of(
                FileChange.deleted("src/OldCode.java", 100),
                FileChange.modified("src/Refactored.java", 20, 80)
        ));
    }

    // ========== GitCommitEvent ==========

    /** Creates a simple commit event. */
    public static GitCommitEvent gitCommit() {
        return GitCommitEvent.of(
                "abc123def456789012345678901234567890abcd",
                "Add new feature implementation",
                "feature/new-feature"
        );
    }

    /** Creates a complete commit event with all details. */
    public static GitCommitEvent gitCommitComplete() {
        return GitCommitEvent.builder()
                .sha("abc123def456789012345678901234567890abcd")
                .message("Implement user authentication\n\nAdded login and logout endpoints")
                .branch("feature/auth")
                .filesChanged(List.of(
                        "src/auth/AuthController.java",
                        "src/auth/AuthService.java",
                        "src/model/User.java"
                ))
                .linesAdded(250)
                .linesRemoved(15)
                .build();
    }

    // ========== GitBranchEvent ==========

    /** Creates a branch creation event. */
    public static GitBranchEvent gitBranchCreated() {
        return GitBranchEvent.created("feature/new-feature", "main");
    }

    /** Creates a branch checkout event. */
    public static GitBranchEvent gitBranchCheckedOut() {
        return GitBranchEvent.checkedOut("develop");
    }

    /** Creates a branch deletion event. */
    public static GitBranchEvent gitBranchDeleted() {
        return GitBranchEvent.deleted("feature/old-feature");
    }

    // ========== GitPullRequestEvent ==========

    /** Creates a PR created event. */
    public static GitPullRequestEvent gitPrCreated() {
        return GitPullRequestEvent.created(
                "123",
                "https://github.com/org/repo/pull/123",
                "Add new feature implementation",
                "feature/new-feature",
                "main"
        );
    }

    /** Creates a PR merged event. */
    public static GitPullRequestEvent gitPrMerged() {
        return GitPullRequestEvent.merged(
                "123",
                "https://github.com/org/repo/pull/123",
                "Add new feature implementation",
                "feature/new-feature",
                "main"
        );
    }

    // ========== Event Lists ==========

    /** Returns one of each event type for comprehensive testing. */
    public static List<JournalEvent> allEventTypes() {
        return List.of(
                llmCall(),
                bashSuccess(),
                stateChangePlanToImplement(),
                tokenMetric(),
                checkpointEvent(),
                gitPatch(),
                gitCommit(),
                gitBranchCreated(),
                gitPrCreated()
        );
    }

    /** Returns all git event types for comprehensive testing. */
    public static List<GitEvent> allGitEventTypes() {
        return List.of(
                gitPatch(),
                gitCommit(),
                gitBranchCreated(),
                gitPrCreated()
        );
    }

    /** Returns a realistic sequence of events for an agent turn. */
    public static List<JournalEvent> agentTurnSequence() {
        return List.of(
                stateChangePlanToImplement(),
                llmCall(),
                bashSuccess(),
                readSuccess(),
                llmCallWithThinking(),
                writeSuccess(),
                tokenMetric(),
                costMetric(),
                stateChangeImplementToTest()
        );
    }

    /** Returns events representing a failed run. */
    public static List<JournalEvent> failedRunSequence() {
        return List.of(
                stateChangePlanToImplement(),
                llmCall(),
                toolFailure(),
                StateChangeEvent.of("implementing", "failed", "tool execution failed")
        );
    }

}
