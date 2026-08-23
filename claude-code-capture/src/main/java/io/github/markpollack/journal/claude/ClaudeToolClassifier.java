package io.github.markpollack.journal.claude;

import io.github.markpollack.journal.event.ToolKind;

import java.util.Locale;

/** Maps Claude Code's raw tool names to ACP's canonical tool vocabulary. */
final class ClaudeToolClassifier {

    private ClaudeToolClassifier() {
    }

    static ToolKind classify(String name) {
        String normalized = name == null
                ? ""
                : name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
        return switch (normalized) {
            case "read", "notebookread" -> ToolKind.READ;
            case "write", "edit", "multiedit", "notebookedit", "applypatch" -> ToolKind.EDIT;
            case "glob", "grep", "websearch" -> ToolKind.SEARCH;
            case "bash", "bashoutput", "killshell" -> ToolKind.EXECUTE;
            case "task", "agent", "taskoutput", "todowrite" -> ToolKind.THINK;
            case "webfetch" -> ToolKind.FETCH;
            case "enterplanmode", "exitplanmode" -> ToolKind.SWITCH_MODE;
            default -> ToolKind.OTHER;
        };
    }
}
