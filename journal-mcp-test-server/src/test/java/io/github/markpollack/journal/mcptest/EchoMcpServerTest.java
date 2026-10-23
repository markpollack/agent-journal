package io.github.markpollack.journal.mcptest;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.ServerParameters;
import io.modelcontextprotocol.client.transport.StdioClientTransport;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.spec.McpError;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The test server as a CLI meets it: a separate JVM on stdio, driven by the SDK's own client, so initialization,
 * discovery, tool results, the deliberate tool error and the protocol error for an unknown tool are the wire behaviour a
 * captured CLI sees. The tool handlers are also checked in process for the argument edge cases.
 */
class EchoMcpServerTest {

    private static final McpJsonMapper JSON = McpJsonDefaults.getMapper();

    @Test
    void aSeparateProcessAnswersDiscoveryResultsTheToolErrorAndAnUnknownToolOverStdio() {
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        ServerParameters params = ServerParameters.builder(java).args("-cp", System.getProperty("java.class.path"), EchoMcpServer.class.getName()).build();
        McpSyncClient client = McpClient.sync(new StdioClientTransport(params, JSON)).requestTimeout(Duration.ofSeconds(30)).build();
        try {
            assertThat(client.initialize().serverInfo().name()).isEqualTo(EchoMcpServer.NAME);
            List<Tool> tools = client.listTools().tools();
            assertThat(tools).extracting(Tool::name).containsExactly("echo_record", "always_fails");
            assertThat(tools.get(0).inputSchema()).containsEntry("required", List.of("text", "n")).containsEntry("additionalProperties", false);
            assertThat(tools.get(0).outputSchema()).containsKey("properties");

            CallToolResult echo = client.callTool(new CallToolRequest("echo_record", Map.of("text", "alpha", "n", 1)));
            assertThat(echo.isError()).isFalse();
            assertThat(((TextContent) echo.content().get(0)).text()).isEqualTo("{\"echo\": \"alpha\", \"n\": 1, \"ok\": true}");
            assertThat(echo.structuredContent()).isEqualTo(Map.of("echo", "alpha", "n", 1, "ok", true));

            CallToolResult second = client.callTool(new CallToolRequest("echo_record", Map.of("text", "beta", "n", 2)));
            assertThat(((TextContent) second.content().get(0)).text()).as("repeated calls are distinguishable by their arguments").isEqualTo("{\"echo\": \"beta\", \"n\": 2, \"ok\": true}");

            CallToolResult failed = client.callTool(new CallToolRequest("always_fails", Map.of()));
            assertThat(failed.isError()).as("a tool error is a result, not a protocol error").isTrue();
            assertThat(((TextContent) failed.content().get(0)).text()).isEqualTo(EchoMcpServer.FAILURE_TEXT);
            assertThat(failed.structuredContent()).isNull();

            assertThatThrownBy(() -> client.callTool(new CallToolRequest("no_such_tool", Map.of()))).isInstanceOf(McpError.class);
        }
        finally {
            client.closeGracefully();
        }
    }

    @Test
    void echoRecordRefusesArgumentsOfTheWrongTypeAsAToolError() {
        Map<String, Object> bool = new LinkedHashMap<>();
        bool.put("text", "x");
        bool.put("n", true);
        for (Map<String, Object> args : java.util.Arrays.asList(null, Map.<String, Object>of(), Map.<String, Object>of("text", 1, "n", 1), Map.<String, Object>of("text", "x", "n", 1.5), bool)) {
            CallToolResult r = EchoMcpServer.echoRecord(JSON, args);
            assertThat(r.isError()).as(String.valueOf(args)).isTrue();
            assertThat(((TextContent) r.content().get(0)).text()).isEqualTo(EchoMcpServer.INVALID_TEXT);
            assertThat(r.structuredContent()).isNull();
        }
    }

    @Test
    void theEndOfStandardInputReleasesTheServer() throws Exception {
        java.util.concurrent.CountDownLatch ended = new java.util.concurrent.CountDownLatch(1);
        java.io.InputStream in = EchoMcpServer.untilEnd(new java.io.ByteArrayInputStream(new byte[] {'{', '\n'}), ended);
        assertThat(in.read()).isEqualTo('{');
        assertThat(in.read(new byte[8], 0, 8)).isEqualTo(1);
        assertThat(ended.getCount()).as("data is not the end").isEqualTo(1);
        assertThat(in.read(new byte[8], 0, 8)).isEqualTo(-1);
        assertThat(ended.getCount()).isZero();
        assertThat(EchoMcpServer.untilEnd(new java.io.ByteArrayInputStream(new byte[0]), ended).read()).isEqualTo(-1);
    }

    @Test
    void echoRecordEscapesTheTextAsJsonAndAcceptsLongIntegers() {
        CallToolResult r = EchoMcpServer.echoRecord(JSON, Map.of("text", "a \"quoted\"\nline", "n", 9_007_199_254_740_993L));
        assertThat(r.isError()).isFalse();
        assertThat(((TextContent) r.content().get(0)).text()).isEqualTo("{\"echo\": \"a \\\"quoted\\\"\\nline\", \"n\": 9007199254740993, \"ok\": true}");
        assertThat(EchoMcpServer.alwaysFails().isError()).isTrue();
        assertThat(EchoMcpServer.alwaysFailsTool().inputSchema()).containsEntry("properties", Map.of()).doesNotContainKey("required");
    }
}
