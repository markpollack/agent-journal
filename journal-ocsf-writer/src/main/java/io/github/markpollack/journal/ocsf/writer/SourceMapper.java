package io.github.markpollack.journal.ocsf.writer;

/**
 * Interprets the records of one native source as they arrive, one at a time, keeping only the scoped lifecycle state
 * the source needs (open responses, pending calls, token counts of the current turn). A mapper never sees the whole
 * artifact, never waits for the end of input, and never reads another source's state: later evidence from another
 * artifact is admitted through its own mapper and cannot change what this one already emitted.
 */
interface SourceMapper {

    /** The resource name of the mapping contract this mapper executes ({@code ocsf/mappings/<name>.json}). */
    String contractName();

    void map(NativeRecord record, SourceContext ctx);

    /** Called once when the source ends; a mapper may emit nothing, but must not invent completion. */
    default void end(SourceInput.End how, SourceContext ctx) {
    }

    static SourceMapper forProfile(String profile) {
        return switch (profile) {
            case "claude_stream" -> new ClaudeStreamMapper();
            case "claude_transcript" -> new ClaudeTranscriptMapper();
            case "codex_rollout" -> new CodexRolloutMapper();
            case "codex_stream" -> new CodexStreamMapper();
            case "agy_stream" -> new AntigravityStreamMapper();
            case "opaque_text" -> new OpaqueTextMapper();
            default -> throw new IllegalArgumentException("no mapper for profile " + profile);
        };
    }
}
