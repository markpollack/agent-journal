package io.github.markpollack.journal.tck.verify;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The pinned declarative data the verifier interprets a Claude Code {@code stream-json} capture with. Data only:
 * names, sets and rules written down, never a transformation implementation shared with the producer.
 *
 * <p>Occurrence numbering: Journal's {@code messageNumber} is the 1-based position of a line among the lines the
 * released {@code claude-code-sdk} delivers, which excludes the {@link #DROPPED_LINE_TYPES}. The verifier derives
 * that numbering from the native stream itself and validates every emitted reference against it; it never trusts a
 * reference.
 */
public final class ClaudeStreamContract {

    private ClaudeStreamContract() {
    }

    /** The name and version of this pinned data, written into every report. */
    public static final String ID = "claude-code-stream pinned verifier data v1";

    /** The events schema major version this verifier implements; any other is refused, never guessed at. */
    public static final int SUPPORTED_SCHEMA_VERSION = 1;

    /** The stream field naming the owner of a line: absent or null on the main thread, the spawning tool-use id on a sub-agent's. */
    public static final String OWNER_FIELD = "parent_tool_use_id";

    /** Line types every occurrence of which is retained whole as a {@code source_record} on its owner's run. */
    public static final Set<String> SOURCE_RECORD_TYPES = Set.of("assistant", "user");

    /** Line types the released SDK drops before Journal sees them; the capture must declare them as a known gap. */
    public static final Set<String> DROPPED_LINE_TYPES = Set.of("rate_limit_event");

    /** Content block kinds whose names the normalized events repeat unchanged. */
    public static final Set<String> KNOWN_BLOCK_KINDS = Set.of("text", "thinking", "tool_use");

    /** The custom event names of the normalized families this verifier judges. */
    public static final String EV_SOURCE_RECORD = "source_record";
    public static final String EV_VENDOR_EVENT = "vendor_event";
    public static final String EV_ASSISTANT_TEXT = "assistant_text";
    public static final String EV_THINKING_BLOCK = "thinking_block";
    public static final String EV_MESSAGE_BLOCKS = "message_blocks";
    public static final String EV_PERMISSION_DECISION = "permission_decision";
    public static final String EV_TOOL_TIMING = "tool_timing";

    /** {@code run.json} keys. */
    public static final String CONFIG_SPAWN_TOOL_USE_ID = "subagent.spawnToolUseId";

    /** Allowed values of the declared capture statuses (DESIGN, as implemented for Claude Code, R13-06). */
    public static final Set<String> FINALIZATION_VALUES = Set.of("finished", "failed", "interrupted");
    public static final Set<String> ACQUISITION_VALUES = Set.of("complete", "incomplete");
    public static final Set<String> PRESERVATION_VALUES = Set.of("complete", "partial");

    /** {@code tool_timing.durationSource} values and their meaning. */
    public static final String DURATION_FROM_VENDOR_TIMESTAMPS = "vendor-timestamps";
    public static final String DURATION_NOT_REPORTED = "not-reported";

    /** The families this verifier judges, by category, for the coverage report. */
    public static final Map<String, List<String>> SUPPORTED_FAMILIES = Map.of(
            "COMPLETENESS", List.of("capture.invocations", "capture.statuses", "capture.known_gaps", "children.linked",
                    "children.listed_on_parent"),
            "PRESERVATION", List.of("source_record (assistant, user lines; owner, occurrence, payload)",
                    "vendor_event (other delivered lines; occurrence, payload)", "dropped lines (declared gaps)"),
            "NORMALIZATION", List.of("tool_call (multiplicity, order, name, input value, input representation, "
                    + "string result, structured result, absent result, success flag, ownership)",
                    "assistant_text (value at position)", "thinking_block (content and signature at position)",
                    "message_blocks (kind sequence per line, tool_use reference)",
                    "permission_decision (multiset with occurrence)"),
            "EXPLANATION", List.of("tool_call.turnId (copy of the message id)",
                    "tool_timing / tool_call.durationMs (declared transformation of vendor timestamps)"));

    /** What this verifier does not judge, and why; every entry stays visible in the report. */
    public static final List<Map<String, String>> UNSUPPORTED_FAMILIES = List.of(
            Map.of("family", "llm_call.tokenUsage / cost / metadata.turns / modelUsage",
                    "reason", "usage and cost rules are not pinned in this verifier version; the capture modules' "
                            + "conformance tests cover them separately"),
            Map.of("family", "tool_call.kind / turnIndex / timestamp, event timestamps",
                    "reason", "Journal-generated classification and clock values without a declared transformation rule here"),
            Map.of("family", "subagent_meta, prompt, state_change, header (beyond the schema version)",
                    "reason", "not judged by this verifier version"),
            Map.of("family", "analysis.jsonl, feedback.jsonl",
                    "reason", "optional components; never read by the candidate reader"),
            Map.of("family", "raw/ (level 0) and the session transcript channel",
                    "reason", "level 0 is never read; the transcript's own records are declared not represented in level 1"),
            Map.of("family", "rate_limit_event lines",
                    "reason", "dropped by the released SDK before Journal sees them; reported as a declared gap, not verified"));
}
