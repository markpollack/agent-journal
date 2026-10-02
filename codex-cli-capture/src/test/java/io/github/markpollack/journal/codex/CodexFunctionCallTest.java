package io.github.markpollack.journal.codex;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.markpollack.journal.Journal;
import io.github.markpollack.journal.Run;
import io.github.markpollack.journal.event.ToolKind;
import io.github.markpollack.journal.storage.JsonFileStorage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@code function_call} records. The fixture is written by hand from the shapes in the
 * public Codex protocol source; no committed fixture comes from a Codex version known to write
 * {@code function_call} records.
 */
class CodexFunctionCallTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @AfterEach
    void resetJournal() {
        Journal.reset();
    }

    @Test
    void recordedToolEventsMatchTheExpectedFileFieldByField(@TempDir Path dir) throws Exception {
        CodexPhaseCapture capture = CodexSessionParser.parse(resource("codex-function-call-synthetic.jsonl"),
                "execute", null);
        Journal.configure(new JsonFileStorage(dir));
        try (Run run = Journal.run("codex-function-call").start()) {
            new CodexRunRecorder(run).recordPhase(capture);
        }

        List<JsonNode> actual = new ArrayList<>();
        for (String line : Files.readAllLines(eventsFile(dir))) {
            JsonNode event = MAPPER.readTree(line);
            if ("tool_call".equals(event.path("@type").asText())) {
                ((ObjectNode) event).remove("timestamp");
                actual.add(event);
            }
        }
        JsonNode expected = MAPPER.readTree(resource("codex-function-call-synthetic.expected-tool-events.json").toFile());

        assertThat(actual).hasSize(expected.size());
        for (int i = 0; i < actual.size(); i++) {
            assertThat(actual.get(i)).as("tool event %d", i + 1).isEqualTo(expected.get(i));
        }
    }

    @Test
    void argumentsStringAndArgumentArrayAreKeptVerbatim() throws Exception {
        CodexToolUseRecord shell = parseFixture().toolUses().get(1);

        String rolloutArguments = MAPPER.readTree(Files.readAllLines(resource("codex-function-call-synthetic.jsonl"))
                .get(4)).path("payload").path("arguments").asText();
        assertThat(shell.input().get("raw_input")).isEqualTo(rolloutArguments);
        @SuppressWarnings("unchecked")
        Map<String, Object> arguments = (Map<String, Object>) shell.input().get("arguments");
        assertThat(arguments.get("command")).isEqualTo(List.of("bash", "-lc", "cat README.md"));
        assertThat(shell.input()).doesNotContainKeys("command", "cmd").containsEntry("command_text", "cat README.md");
    }

    @Test
    void functionCallsKeepTheirOwnNames() throws Exception {
        assertThat(parseFixture().toolUses()).extracting(CodexToolUseRecord::name)
                .containsExactly("exec_command", "shell", "shell_command", "apply_patch", "update_plan",
                        "exec_command", "unknown", "exec");
        assertThat(parseFixture().toolUses()).extracting(CodexToolUseRecord::isError)
                .containsExactly(false, false, true, false, false, false, false, false);
    }

    @Test
    void containerExecWithoutAShellWrapperJoinsTheArguments() throws Exception {
        CodexToolUseRecord tool = single(call("container.exec", "{\"command\":[\"rg\",\"-n\",\"TODO\"]}")
                + output("Exit code: 0"));

        assertThat(tool.kind()).isEqualTo(ToolKind.SEARCH);
        assertThat(tool.input()).containsEntry("command_text", "rg -n TODO")
                .containsEntry("classification_source", "function_call.arguments.command");
    }

    @Test
    void shellNameWithACommandOfTheWrongTypeIsAMissingCommand() throws Exception {
        CodexToolUseRecord tool = single(call("shell", "{\"command\":\"ls\"}") + output("Exit code: 0"));

        assertThat(tool.kind()).isEqualTo(ToolKind.EXECUTE);
        assertThat(tool.input()).containsEntry("classification_source", "function_call.arguments.missing_command")
                .doesNotContainKey("command_text");
    }

    @Test
    void argumentsThatAreNotAJsonObjectAreUnparsed() throws Exception {
        CodexToolUseRecord tool = single(call("exec_command", "[\"ls\"]") + output("ok"));

        assertThat(tool.kind()).isEqualTo(ToolKind.OTHER);
        assertThat(tool.input()).containsEntry("classification_source", "function_call.unparsed")
                .containsEntry("raw_input", "[\"ls\"]")
                .doesNotContainKey("arguments");
    }

    @Test
    void structuredNonZeroExitCodeIsAFailure() throws Exception {
        CodexToolUseRecord tool = single(call("shell", "{\"command\":[\"false\"]}")
                + output("{\\\"output\\\":\\\"\\\",\\\"metadata\\\":{\\\"exit_code\\\":2}}"));

        assertThat(tool.isError()).isTrue();
    }

    @Test
    void structuredZeroExitCodeIsNotAFailure() throws Exception {
        CodexToolUseRecord tool = single(call("shell", "{\"command\":[\"true\"]}")
                + output("{\\\"output\\\":\\\"\\\",\\\"metadata\\\":{\\\"exit_code\\\":0}}"));

        assertThat(tool.isError()).isFalse();
    }

    @Test
    void exitCodeTextOnACustomToolCallOutputIsNotAFailure() throws Exception {
        CodexToolUseRecord tool = single("""
                {"type":"response_item","payload":{"type":"custom_tool_call","status":"completed","call_id":"c1","name":"exec","input":"tools.exec_command({\\"cmd\\":\\"ls\\"})"}}
                {"type":"response_item","payload":{"type":"custom_tool_call_output","call_id":"c1","output":"Exit code: 1"}}
                """);

        assertThat(tool.isError()).isFalse();
    }

    @Test
    void unknownRecordTypeWithACallIdIsLoggedAtDebug() throws Exception {
        Logger logger = (Logger) LoggerFactory.getLogger(CodexSessionParser.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        Level previous = logger.getLevel();
        logger.setLevel(Level.DEBUG);
        logger.addAppender(appender);
        try {
            CodexPhaseCapture capture = parse("""
                    {"type":"response_item","payload":{"type":"local_shell_call","call_id":"c9"}}
                    """);

            assertThat(capture.toolUses()).isEmpty();
            assertThat(appender.list).singleElement().satisfies(event -> {
                assertThat(event.getLevel()).isEqualTo(Level.DEBUG);
                assertThat(event.getFormattedMessage()).contains("local_shell_call").contains("c9");
            });
        } finally {
            logger.detachAppender(appender);
            logger.setLevel(previous);
        }
    }

    private static String call(String name, String arguments) throws Exception {
        return "{\"type\":\"response_item\",\"payload\":{\"type\":\"function_call\",\"name\":\"" + name
                + "\",\"arguments\":" + MAPPER.writeValueAsString(arguments) + ",\"call_id\":\"c1\"}}\n";
    }

    private static String output(String escapedText) {
        return "{\"type\":\"response_item\",\"payload\":{\"type\":\"function_call_output\",\"call_id\":\"c1\","
                + "\"output\":\"" + escapedText + "\"}}\n";
    }

    private static CodexToolUseRecord single(String rollout) throws Exception {
        List<CodexToolUseRecord> tools = parse(rollout).toolUses();
        assertThat(tools).hasSize(1);
        return tools.get(0);
    }

    private static CodexPhaseCapture parse(String rollout) throws Exception {
        return CodexSessionParser.parse(new BufferedReader(new StringReader(rollout)), "p", null);
    }

    private static CodexPhaseCapture parseFixture() throws Exception {
        return CodexSessionParser.parse(resource("codex-function-call-synthetic.jsonl"), "p", null);
    }

    private static Path eventsFile(Path dir) throws Exception {
        try (Stream<Path> files = Files.walk(dir)) {
            return files.filter(path -> path.getFileName().toString().equals("events.jsonl")).findFirst().orElseThrow();
        }
    }

    private static Path resource(String name) throws Exception {
        return Path.of(CodexFunctionCallTest.class.getResource("/fixtures/" + name).toURI());
    }
}
