package io.github.markpollack.journal.ocsf.writer;

/**
 * Identity of one native source as the capture declares it before feeding its bytes: the artifact name used in
 * every {@code source_ref}, whether it is live output or a saved session file, the mapping profile that interprets
 * it, the launch route the bytes came through (one of the routes the compatibility registry keys on), and a
 * description of what the bytes actually represent (for example an SDK-delivered stdout stream is not proven to be
 * every stdout line).
 *
 * @param artifact the artifact name within the run's source inventory (unique per run)
 * @param kind 1 live output (stdout, stderr), 2 saved session file, 3 telemetry export (native OTel), 4 CLI report file, 5 CLI log file
 * @param profile the mapper: {@code claude_stream}, {@code claude_transcript}, {@code codex_rollout}, {@code codex_stream}, {@code agy_stream}, {@code opaque_text}
 * @param route the launch route or mode: {@code stdout} (raw standard output), {@code sdk} (SDK-delivered lines),
 * {@code native_session_file}; {@code null} is recorded as unspecified and matches no compatibility entry
 * @param surface what the bytes represent (capture route), stated honestly
 * @param pathAtCapture where the bytes came from, for the inventory, or {@code null}
 * @param framing {@code lines} (one record per line) or {@code document} (the whole artifact is one record)
 */
public record SourceDescriptor(String artifact, int kind, String profile, String route, String surface, String pathAtCapture, String framing) {

    private static final java.util.Map<Integer, String> KINDS = java.util.Map.of(1, "Live output", 2, "Saved session file", 3, "Telemetry export", 4, "CLI report file",
            5, "CLI log file");

    /** A source framed into lines (one record per line), the usual case. */
    public SourceDescriptor(String artifact, int kind, String profile, String route, String surface, String pathAtCapture) {
        this(artifact, kind, profile, route, surface, pathAtCapture, "lines");
    }

    public String kindCaption() {
        return KINDS.getOrDefault(kind, "Unknown");
    }

    /** Whether the whole artifact is one record (a JSON document such as Junie's summary.json or a usage report). */
    public boolean document() {
        return "document".equals(framing);
    }

    /** The framing a source declaration states: {@code document}, or nothing for the usual lines. */
    String declaredFraming() {
        return document() ? "document" : null;
    }
}
