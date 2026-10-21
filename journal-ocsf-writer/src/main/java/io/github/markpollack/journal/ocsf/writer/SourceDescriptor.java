package io.github.markpollack.journal.ocsf.writer;

/**
 * Identity of one native source as the capture declares it before feeding its bytes: the artifact name used in
 * every {@code source_ref}, whether it is live output or a saved session file, the mapping profile that interprets
 * it, the launch route the bytes came through (one of the routes the compatibility registry keys on), and a
 * description of what the bytes actually represent (for example an SDK-delivered stdout stream is not proven to be
 * every stdout line).
 *
 * @param artifact the artifact name within the run's source inventory (unique per run)
 * @param kind 1 live output, 2 saved session file
 * @param profile the mapper: {@code claude_stream}, {@code claude_transcript}, {@code codex_rollout}, {@code codex_stream}, {@code opaque_text}
 * @param route the launch route or mode: {@code stdout} (raw standard output), {@code sdk} (SDK-delivered lines),
 * {@code native_session_file}; {@code null} is recorded as unspecified and matches no compatibility entry
 * @param surface what the bytes represent (capture route), stated honestly
 * @param pathAtCapture where the bytes came from, for the inventory, or {@code null}
 */
public record SourceDescriptor(String artifact, int kind, String profile, String route, String surface, String pathAtCapture) {

    public String kindCaption() {
        return kind == 1 ? "Live output" : kind == 2 ? "Saved session file" : "Unknown";
    }
}
