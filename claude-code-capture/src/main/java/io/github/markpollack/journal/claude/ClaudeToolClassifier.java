package io.github.markpollack.journal.claude;

import io.github.markpollack.journal.event.ToolKind;

import java.util.Locale;
import java.util.Map;

/** Maps Claude Code's raw tool names to ACP's canonical tool vocabulary. */
final class ClaudeToolClassifier {

    /** The kind of each known tool, by its name lower-cased and stripped of everything but letters and digits. */
    private static final Map<String, ToolKind> KINDS_BY_NORMALIZED_NAME = Map.ofEntries(
            Map.entry("read", ToolKind.READ),
            Map.entry("notebookread", ToolKind.READ),
            Map.entry("write", ToolKind.EDIT),
            Map.entry("edit", ToolKind.EDIT),
            Map.entry("multiedit", ToolKind.EDIT),
            Map.entry("notebookedit", ToolKind.EDIT),
            Map.entry("applypatch", ToolKind.EDIT),
            Map.entry("glob", ToolKind.SEARCH),
            Map.entry("grep", ToolKind.SEARCH),
            Map.entry("websearch", ToolKind.SEARCH),
            Map.entry("bash", ToolKind.EXECUTE),
            Map.entry("bashoutput", ToolKind.EXECUTE),
            Map.entry("killshell", ToolKind.EXECUTE),
            Map.entry("task", ToolKind.THINK),
            Map.entry("agent", ToolKind.THINK),
            Map.entry("taskoutput", ToolKind.THINK),
            Map.entry("todowrite", ToolKind.THINK),
            Map.entry("webfetch", ToolKind.FETCH),
            Map.entry("enterplanmode", ToolKind.SWITCH_MODE),
            Map.entry("exitplanmode", ToolKind.SWITCH_MODE));

    private ClaudeToolClassifier() {
    }

    static ToolKind classify(String name) {
        String normalized = name == null
                ? ""
                : name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
        return KINDS_BY_NORMALIZED_NAME.getOrDefault(normalized, ToolKind.OTHER);
    }
}
