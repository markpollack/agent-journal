package io.github.markpollack.journal.codex;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
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
 */
final class CodexToolClassifier {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Pattern TOOL_INVOCATION = Pattern.compile("tools\\.([A-Za-z0-9_]+)\\s*\\(");
    private static final Pattern LEADING_ASSIGNMENT = Pattern.compile("^[A-Za-z_][A-Za-z0-9_]*=.*$");
    private static final Set<String> NAVIGATION = Set.of("cd", "pwd", "true", "echo", "printf");

    private CodexToolClassifier() {
    }

    static Classification classify(String rawName, String rawInput) {
        Matcher invocation = TOOL_INVOCATION.matcher(rawInput != null ? rawInput : "");
        if (!invocation.find()) {
            return new Classification(fallbackName(rawName), Map.of("raw_input", nullToEmpty(rawInput)));
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
            ShellClassification shell = classifyShell(command);
            if (shell.filePath() != null) {
                normalized.put("file_path", shell.filePath());
            }
            normalized.put("classification_source", "input.tools.exec_command.cmd");
            return new Classification(shell.name(), normalized);
        }

        normalized.put("classification_source", "input.tools." + codexTool);
        return new Classification(classifyCodexTool(codexTool), normalized);
    }

    private static String classifyCodexTool(String tool) {
        return switch (tool) {
            case "apply_patch" -> "Edit";
            case "view_image", "read_mcp_resource" -> "Read";
            case "web__run" -> "Fetch";
            case "write_stdin", "wait" -> "Process";
            default -> tool;
        };
    }

    private static ShellClassification classifyShell(String command) {
        if (command == null || command.isBlank()) {
            return new ShellClassification("Shell", null);
        }

        String[] segments = command.split("\\s*(?:&&|\\|\\||;|\\|)\\s*");
        ShellClassification navigationFallback = null;
        for (String segment : segments) {
            CommandToken token = commandToken(segment);
            if (token == null) {
                continue;
            }
            if (NAVIGATION.contains(token.executable())) {
                if (navigationFallback == null) {
                    navigationFallback = new ShellClassification("Inspect", null);
                }
                continue;
            }
            return classifyExecutable(token.executable(), segment);
        }
        return navigationFallback != null ? navigationFallback : new ShellClassification("Shell", null);
    }

    private static ShellClassification classifyExecutable(String executable, String segment) {
        return switch (executable) {
            case "rg", "grep", "find", "fd" -> new ShellClassification("Search", null);
            case "sed", "cat", "head", "tail", "less", "bat", "nl" ->
                    new ShellClassification("Read", lastArgument(segment));
            case "wc", "ls", "tree", "stat", "file" -> new ShellClassification("Inspect", null);
            case "mvn", "mvnw", "gradle", "gradlew", "pytest", "ctest" ->
                    new ShellClassification(isVerification(segment) ? "Test" : "Build", null);
            case "npm", "pnpm", "yarn", "cargo", "go" ->
                    new ShellClassification(isVerification(segment) ? "Test" : "Build", null);
            case "git", "gh" -> new ShellClassification("Git", null);
            case "curl", "wget" -> new ShellClassification("Fetch", null);
            case "cp", "mv", "mkdir", "touch", "install" -> new ShellClassification("Edit", null);
            case "rm", "rmdir" -> new ShellClassification("Delete", null);
            case "python", "python3", "node", "bash", "sh", "zsh" ->
                    new ShellClassification("Script", null);
            default -> new ShellClassification("Shell", null);
        };
    }

    private static boolean isVerification(String segment) {
        String lower = segment.toLowerCase(Locale.ROOT);
        return lower.matches(".*\\b(test|tests|verify|check)\\b.*");
    }

    private static CommandToken commandToken(String segment) {
        String cleaned = segment.trim();
        if (cleaned.isEmpty()) {
            return null;
        }
        String[] words = cleaned.split("\\s+");
        int index = 0;
        while (index < words.length && ("env".equals(words[index]) || LEADING_ASSIGNMENT.matcher(words[index]).matches())) {
            index++;
        }
        if (index < words.length && "sudo".equals(words[index])) {
            index++;
        }
        if (index >= words.length) {
            return null;
        }
        String token = stripQuotes(words[index]);
        int slash = token.lastIndexOf('/');
        String executable = slash >= 0 ? token.substring(slash + 1) : token;
        return new CommandToken(executable);
    }

    private static String lastArgument(String segment) {
        String[] words = segment.trim().split("\\s+");
        if (words.length < 2) {
            return null;
        }
        String candidate = stripQuotes(words[words.length - 1]);
        return candidate.startsWith("-") ? null : candidate;
    }

    private static JsonNode parseObjectArgument(String input, int invocationEnd) {
        if (input == null) {
            return null;
        }
        int start = input.indexOf('{', invocationEnd);
        if (start < 0) {
            return null;
        }
        boolean inString = false;
        boolean escaped = false;
        int depth = 0;
        for (int i = start; i < input.length(); i++) {
            char c = input.charAt(i);
            if (inString) {
                if (escaped) {
                    escaped = false;
                } else if (c == '\\') {
                    escaped = true;
                } else if (c == '"') {
                    inString = false;
                }
                continue;
            }
            if (c == '"') {
                inString = true;
            } else if (c == '{') {
                depth++;
            } else if (c == '}' && --depth == 0) {
                try {
                    return MAPPER.readTree(input.substring(start, i + 1));
                } catch (Exception ignored) {
                    return null;
                }
            }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(JsonNode node) {
        if (node == null || !node.isObject()) {
            return new LinkedHashMap<>();
        }
        return new LinkedHashMap<>(MAPPER.convertValue(node, Map.class));
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value != null && value.isTextual() ? value.asText() : null;
    }

    private static String fallbackName(String rawName) {
        return rawName == null || rawName.isBlank() || "exec".equals(rawName) ? "Shell" : rawName;
    }

    private static String stripQuotes(String value) {
        if (value.length() >= 2 && ((value.startsWith("\"") && value.endsWith("\""))
                || (value.startsWith("'") && value.endsWith("'")))) {
            return value.substring(1, value.length() - 1);
        }
        return value;
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    record Classification(String name, Map<String, Object> input) {
    }

    private record CommandToken(String executable) {
    }

    private record ShellClassification(String name, String filePath) {
    }
}
