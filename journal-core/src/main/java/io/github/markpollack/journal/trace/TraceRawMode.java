package io.github.markpollack.journal.trace;

/**
 * Raw-capture policy for the JSONL trace files written by {@code TraceWriter}
 * (schema v2), orthogonal to {@link TraceContentMode}.
 *
 * <p>
 * Where {@link TraceContentMode} governs the <em>modeled</em> lines (the portable subset
 * journal extracts and the Markov loader reads), this governs whether the
 * <strong>verbatim vendor wire</strong> is also preserved. The principle (per the
 * per-turn-cost spec): capture-everything-raw + model-the-portable-intersection. The
 * modeled lines are queryable and stable; the raw line is the future-proof escape hatch
 * for anything the typed parse drops — {@code permission_denials}, {@code modelUsage},
 * structured {@code tool_use_result}, and the sub-agent envelope ({@code isSidechain},
 * {@code parentUuid}, {@code parent_tool_use_id}). These live only on
 * {@code RegularMessage.rawJson} (SDK &ge; 1.3.0), never on the typed {@code Message}.
 *
 * <p>
 * A {@code raw} line is additive and additive-safe: the Markov loader keys off
 * {@code type == "tool_use"} / {@code "result"} and silently skips every other line type,
 * so emitting {@code type:"raw"} lines never perturbs existing consumers. The header
 * records the active {@code rawMode} so a trace is self-describing.
 *
 * <p>
 * <strong>Sensitivity:</strong> a {@link #FULL} raw line contains the complete, unredacted
 * vendor message — at least as sensitive as a {@link TraceContentMode#FULL} content body.
 * Treat raw-capturing traces as sensitive local artifacts.
 */
public enum TraceRawMode {

    /**
     * Default. No raw escape-hatch lines — only the modeled (content-mode-governed) lines
     * are written. The pre-existing schema-v2 behaviour.
     */
    NONE,

    /**
     * Emit one verbatim {@code raw} line per vendor wire message, carrying the complete
     * original JSON ({@code RegularMessage.rawJson}) so no unmodeled field is ever lost.
     */
    FULL

}
