package io.github.markpollack.journal.junie;

import io.github.markpollack.journal.event.ToolKind;

import java.util.Map;
import java.util.Set;

/**
 * Maps Junie's block-event kinds onto ACP's canonical tool vocabulary.
 *
 * <p>
 * The mapping is deliberately tiny because Junie's action vocabulary is tiny: it reports what a
 * step did by choosing which {@code *BlockUpdatedEvent} to emit, and the fixtures show exactly
 * three action-bearing kinds plus one prose wrapper. An unrecognised kind maps to
 * {@link ToolKind#OTHER} rather than throwing, so a future Junie release that adds a block type
 * still produces a capture.
 */
final class JunieToolClassifier {

    /** Junie's prose narration wrapper — carries a step's description but never its action. */
    static final String TOOL_BLOCK = "ToolBlockUpdatedEvent";

    static final String TERMINAL_BLOCK = "TerminalBlockUpdatedEvent";
    static final String VIEW_FILES_BLOCK = "ViewFilesBlockUpdatedEvent";
    static final String FILE_CHANGES_BLOCK = "FileChangesBlockUpdatedEvent";

    private static final Map<String, ToolKind> KINDS = Map.of(
            TERMINAL_BLOCK, ToolKind.EXECUTE,
            VIEW_FILES_BLOCK, ToolKind.READ,
            FILE_CHANGES_BLOCK, ToolKind.EDIT,
            TOOL_BLOCK, ToolKind.OTHER);

    /**
     * The block kinds that carry a real action, in the order they outrank each other when several
     * describe one {@code stepId}. Junie emits {@link #TOOL_BLOCK} <em>and</em> a structured block
     * for the same step — {@code "Open calc.py"} and {@code files:[{relativePath:"calc.py"}]} are
     * one file read, not two steps — so the structured kind must win regardless of arrival order.
     */
    private static final Set<String> STRUCTURED = Set.of(TERMINAL_BLOCK, VIEW_FILES_BLOCK, FILE_CHANGES_BLOCK);

    private JunieToolClassifier() {
    }

    static ToolKind classify(String eventKind) {
        return eventKind == null ? ToolKind.OTHER : KINDS.getOrDefault(eventKind, ToolKind.OTHER);
    }

    /** Whether this kind carries a structured payload, as opposed to prose narration only. */
    static boolean isStructured(String eventKind) {
        return eventKind != null && STRUCTURED.contains(eventKind);
    }

    /** Whether this kind describes a tool step at all (as opposed to a thought or a result). */
    static boolean isToolBlock(String eventKind) {
        return eventKind != null && KINDS.containsKey(eventKind);
    }

    static Set<String> knownToolNames() {
        return KINDS.keySet();
    }
}
