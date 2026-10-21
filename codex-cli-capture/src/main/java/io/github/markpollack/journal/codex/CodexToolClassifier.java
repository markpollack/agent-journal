package io.github.markpollack.journal.codex;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.markpollack.journal.event.ToolKind;

import static io.github.markpollack.journal.codex.CodexClassifierSupport.asMap;
import static io.github.markpollack.journal.codex.CodexClassifierSupport.classifyCodexTool;
import static io.github.markpollack.journal.codex.CodexClassifierSupport.classifyShell;
import static io.github.markpollack.journal.codex.CodexClassifierSupport.joinedCommand;
import static io.github.markpollack.journal.codex.CodexClassifierSupport.nullToEmpty;
import static io.github.markpollack.journal.codex.CodexClassifierSupport.parseObject;
import static io.github.markpollack.journal.codex.CodexClassifierSupport.parseObjectArgument;
import static io.github.markpollack.journal.codex.CodexClassifierSupport.text;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Classifies a Codex rollout tool from its nested {@code input}, not the outer {@code name}.
 *
 * <p>Current Codex rollouts record every tool call as {@code name:"exec"}; the useful action is a
 * JavaScript expression such as {@code tools.exec_command({"cmd":"rg ..."})}. This classifier
 * extracts that invocation without evaluating it, parses the JSON argument, and maps the first
 * substantive shell executable to a small semantic vocabulary. Navigation commands such as
 * {@code pwd} and {@code cd} are skipped when a compound command contains a later action.</p>
 *
 * <p>The shell split is intentionally heuristic: quoted {@code &&}, pipes, shell functions,
 * aliases, and scripts whose real behavior is hidden behind an interpreter may fall back to
 * {@code Shell}. The raw input and command remain in the normalized input map so a future
 * classifier can reprocess the immutable event.</p>
 *
 * <p>A {@code function_call} record is classified by {@link #classifyFunctionCall}, from its real
 * name and its JSON {@code arguments}. Its arguments are nested unchanged under
 * {@code arguments}, and the command it derives goes under {@code command_text}, so a derived
 * value never takes the place of an argument.</p>
 */
final class CodexToolClassifier {

    private static final Pattern TOOL_INVOCATION = Pattern.compile("tools\\.([A-Za-z0-9_]+)\\s*\\(");

    private CodexToolClassifier() {
    }

    static Classification classify(String rawName, String rawInput) {
        Matcher invocation = TOOL_INVOCATION.matcher(rawInput != null ? rawInput : "");
        if (!invocation.find()) {
            return new Classification(ToolKind.OTHER, Map.of("raw_input", nullToEmpty(rawInput)));
        }

        String codexTool = invocation.group(1);
        JsonNode arguments = parseObjectArgument(rawInput, invocation.end());
        Map<String, Object> normalized = asMap(arguments);
        normalized.put("codex_tool", codexTool);
        normalized.put("raw_input", nullToEmpty(rawInput));

        if ("exec_command".equals(codexTool)) {
            String command = arguments != null ? text(arguments, "cmd") : null;
            if (command != null) {
                normalized.put("command", command);
            }
            CodexClassifierSupport.ShellClassification shell = classifyShell(command);
            if (shell.filePath() != null) {
                normalized.put("file_path", shell.filePath());
            }
            normalized.put("classification_source", "input.tools.exec_command.cmd");
            return new Classification(shell.kind(), normalized);
        }

        normalized.put("classification_source", "input.tools." + codexTool);
        return new Classification(classifyCodexTool(codexTool), normalized);
    }

    /**
     * Classifies a {@code function_call} record from its name and its {@code arguments} string.
     *
     * @param name the record's name, kept as {@code codex_tool}
     * @param argumentsJson the record's {@code arguments} string, kept as {@code raw_input};
     *        {@code ""} if absent
     */
    static Classification classifyFunctionCall(String name, String argumentsJson) {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("codex_record_type", "function_call");
        input.put("codex_tool", name);
        input.put("raw_input", nullToEmpty(argumentsJson));

        JsonNode arguments = parseObject(argumentsJson);
        if (arguments == null) {
            input.put("classification_source", "function_call.unparsed");
            return new Classification(ToolKind.OTHER, input);
        }
        input.put("arguments", asMap(arguments));

        String command;
        String source;
        switch (name) {
            case "exec_command" -> {
                command = text(arguments, "cmd");
                source = "function_call.arguments.cmd";
            }
            case "shell", "container.exec" -> {
                command = joinedCommand(arguments.get("command"));
                source = "function_call.arguments.command";
            }
            case "shell_command" -> {
                command = text(arguments, "command");
                source = "function_call.arguments.command";
            }
            default -> {
                input.put("classification_source", "function_call.name");
                return new Classification(classifyCodexTool(name), input);
            }
        }

        if (command == null || command.isBlank()) {
            input.put("classification_source", "function_call.arguments.missing_command");
            return new Classification(classifyShell(null).kind(), input);
        }
        CodexClassifierSupport.ShellClassification shell = classifyShell(command);
        input.put("command_text", command);
        if (shell.filePath() != null) {
            input.put("file_path", shell.filePath());
        }
        input.put("classification_source", source);
        return new Classification(shell.kind(), input);
    }

    record Classification(ToolKind kind, Map<String, Object> input) {
    }
}
