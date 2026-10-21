package io.github.markpollack.journal.codex;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import io.github.markpollack.journal.event.ToolKind;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The pure helpers behind {@link CodexToolClassifier}: joining a {@code command} argument array,
 * classifying a shell command by its first substantive executable, reading the JSON object
 * argument out of a {@code tools.name({...})} invocation, and the small node and string helpers.
 * Every method returns a value from its arguments and has no side effects.
 */
final class CodexClassifierSupport {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Pattern LEADING_ASSIGNMENT = Pattern.compile("^[A-Za-z_][A-Za-z0-9_]*=.*$");
    private static final Set<String> NAVIGATION = Set.of("cd", "pwd", "true", "echo", "printf");
    private static final Set<String> SHELLS = Set.of("bash", "sh", "zsh");
    private static final ObjectReader STRICT_READER =
            MAPPER.readerFor(JsonNode.class).with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    private CodexClassifierSupport() {
    }

    // An argument array of strings; [bash|sh|zsh, -c|-lc, script] gives the script, anything else
    // the elements joined with single spaces. Used for classification only.
    static String joinedCommand(JsonNode command) {
        if (command == null || !command.isArray() || command.isEmpty()) {
            return null;
        }
        List<String> words = new ArrayList<>(command.size());
        for (JsonNode word : command) {
            if (!word.isTextual()) {
                return null;
            }
            words.add(word.asText());
        }
        if (words.size() == 3 && SHELLS.contains(words.get(0))
                && ("-c".equals(words.get(1)) || "-lc".equals(words.get(1)))) {
            return words.get(2);
        }
        return String.join(" ", words);
    }

    static JsonNode parseObject(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            JsonNode node = STRICT_READER.readValue(json);
            return node != null && node.isObject() ? node : null;
        } catch (Exception ignored) {
            return null;
        }
    }

    static ToolKind classifyCodexTool(String tool) {
        return switch (tool) {
            case "apply_patch" -> ToolKind.EDIT;
            case "view_image", "read_mcp_resource" -> ToolKind.READ;
            case "web__run" -> ToolKind.FETCH;
            case "write_stdin" -> ToolKind.EXECUTE;
            case "wait" -> ToolKind.THINK;
            default -> ToolKind.OTHER;
        };
    }

    static ShellClassification classifyShell(String command) {
        if (command == null || command.isBlank()) {
            return new ShellClassification(ToolKind.EXECUTE, null);
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
                    navigationFallback = new ShellClassification(ToolKind.READ, null);
                }
                continue;
            }
            return classifyExecutable(token.executable(), segment);
        }
        return navigationFallback != null ? navigationFallback : new ShellClassification(ToolKind.EXECUTE, null);
    }

    // The executable vocabulary. An executable not listed is EXECUTE, like the build tools, package
    // managers, git and interpreters listed here, which the table keeps as the known names.
    static final Map<String, ToolKind> KIND_BY_EXECUTABLE = Map.ofEntries(
            Map.entry("rg", ToolKind.SEARCH), Map.entry("grep", ToolKind.SEARCH),
            Map.entry("find", ToolKind.SEARCH), Map.entry("fd", ToolKind.SEARCH),
            Map.entry("sed", ToolKind.READ), Map.entry("cat", ToolKind.READ), Map.entry("head", ToolKind.READ),
            Map.entry("tail", ToolKind.READ), Map.entry("less", ToolKind.READ), Map.entry("bat", ToolKind.READ),
            Map.entry("nl", ToolKind.READ),
            Map.entry("wc", ToolKind.READ), Map.entry("ls", ToolKind.READ), Map.entry("tree", ToolKind.READ),
            Map.entry("stat", ToolKind.READ), Map.entry("file", ToolKind.READ),
            Map.entry("mvn", ToolKind.EXECUTE), Map.entry("mvnw", ToolKind.EXECUTE), Map.entry("gradle", ToolKind.EXECUTE),
            Map.entry("gradlew", ToolKind.EXECUTE), Map.entry("pytest", ToolKind.EXECUTE), Map.entry("ctest", ToolKind.EXECUTE),
            Map.entry("npm", ToolKind.EXECUTE), Map.entry("pnpm", ToolKind.EXECUTE), Map.entry("yarn", ToolKind.EXECUTE),
            Map.entry("cargo", ToolKind.EXECUTE), Map.entry("go", ToolKind.EXECUTE),
            Map.entry("git", ToolKind.EXECUTE), Map.entry("gh", ToolKind.EXECUTE),
            Map.entry("curl", ToolKind.FETCH), Map.entry("wget", ToolKind.FETCH),
            Map.entry("cp", ToolKind.EDIT), Map.entry("mkdir", ToolKind.EDIT), Map.entry("touch", ToolKind.EDIT),
            Map.entry("install", ToolKind.EDIT),
            Map.entry("mv", ToolKind.MOVE),
            Map.entry("rm", ToolKind.DELETE), Map.entry("rmdir", ToolKind.DELETE),
            Map.entry("python", ToolKind.EXECUTE), Map.entry("python3", ToolKind.EXECUTE), Map.entry("node", ToolKind.EXECUTE),
            Map.entry("bash", ToolKind.EXECUTE), Map.entry("sh", ToolKind.EXECUTE), Map.entry("zsh", ToolKind.EXECUTE));

    // The readers whose last argument is the file they read.
    static final Set<String> FILE_READERS = Set.of("sed", "cat", "head", "tail", "less", "bat", "nl");

    static ShellClassification classifyExecutable(String executable, String segment) {
        ToolKind kind = KIND_BY_EXECUTABLE.getOrDefault(executable, ToolKind.EXECUTE);
        String filePath = FILE_READERS.contains(executable) ? lastArgument(segment) : null;
        return new ShellClassification(kind, filePath);
    }

    static CommandToken commandToken(String segment) {
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

    static String lastArgument(String segment) {
        String[] words = segment.trim().split("\\s+");
        if (words.length < 2) {
            return null;
        }
        String candidate = stripQuotes(words[words.length - 1]);
        return candidate.startsWith("-") ? null : candidate;
    }

    static JsonNode parseObjectArgument(String input, int invocationEnd) {
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
    static Map<String, Object> asMap(JsonNode node) {
        if (node == null || !node.isObject()) {
            return new LinkedHashMap<>();
        }
        return new LinkedHashMap<>(MAPPER.convertValue(node, Map.class));
    }

    static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value != null && value.isTextual() ? value.asText() : null;
    }

    static String stripQuotes(String value) {
        if (value.length() >= 2 && ((value.startsWith("\"") && value.endsWith("\""))
                || (value.startsWith("'") && value.endsWith("'")))) {
            return value.substring(1, value.length() - 1);
        }
        return value;
    }

    static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    record CommandToken(String executable) {
    }

    record ShellClassification(ToolKind kind, String filePath) {
    }
}
