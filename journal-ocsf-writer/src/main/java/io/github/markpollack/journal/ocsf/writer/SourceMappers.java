package io.github.markpollack.journal.ocsf.writer;

import java.util.Map;
import java.util.function.Supplier;

/** The mapper of each source profile; a new instance per source, since a mapper keeps its source's lifecycle state. */
final class SourceMappers {

    static final Map<String, Supplier<SourceMapper>> MAPPERS = Map.ofEntries(
            Map.entry("claude_stream", ClaudeStreamMapper::new),
            Map.entry("claude_transcript", ClaudeTranscriptMapper::new),
            Map.entry("codex_rollout", CodexRolloutMapper::new),
            Map.entry("codex_stream", CodexStreamMapper::new),
            Map.entry("agy_stream", AntigravityStreamMapper::new),
            Map.entry("copilot_events", CopilotEventsMapper::new),
            Map.entry("copilot_otel", CopilotOtelMapper::new),
            Map.entry("copilot_usage_report", CopilotUsageReportMapper::new),
            Map.entry("junie_summary", JunieSummaryMapper::new),
            Map.entry("junie_session_events", JunieSessionMapper::new),
            Map.entry("junie_stdout", JunieStdoutMapper::new),
            Map.entry("opaque_text", OpaqueTextMapper::new));

    private SourceMappers() {
    }

    static SourceMapper forProfile(String profile) {
        Supplier<SourceMapper> m = MAPPERS.get(profile);
        if (m == null) {
            throw new IllegalArgumentException("no mapper for profile " + profile);
        }
        return m.get();
    }
}
