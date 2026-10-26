package io.github.markpollack.journal.tck.ocsf;

import java.util.Map;
import java.util.function.Supplier;

/**
 * Explicit reader dispatch. Exact version and route bindings are checked separately against frozen
 * manifests.
 */
final class SourceReaders {
    private static final Map<String, Supplier<SourceProfile>> READERS =
            Map.ofEntries(
                    Map.entry("claude_stream", ClaudeStreamProfile::new),
                    Map.entry("claude_transcript", ClaudeTranscriptProfile::new),
                    Map.entry("codex_rollout", CodexRolloutProfile::new),
                    Map.entry("codex_stream", CodexStreamProfile::new),
                    Map.entry("agy_stream", AntigravityStreamProfile::new),
                    Map.entry("opaque_text", OpaqueTextProfile::new),
                    Map.entry("junie_summary", () -> new JunieProfile("junie_summary")),
                    Map.entry(
                            "junie_session_events", () -> new JunieProfile("junie_session_events")),
                    Map.entry("junie_stdout", () -> new JunieProfile("junie_stdout")),
                    Map.entry("copilot_events", () -> new CopilotProfile("copilot_events")),
                    Map.entry("copilot_otel", () -> new CopilotProfile("copilot_otel")),
                    Map.entry(
                            "copilot_usage_report",
                            () -> new CopilotProfile("copilot_usage_report")));

    private SourceReaders() {}

    static SourceProfile forName(String name) {
        Supplier<SourceProfile> reader = READERS.get(String.valueOf(name));
        return reader == null ? null : reader.get();
    }
}
