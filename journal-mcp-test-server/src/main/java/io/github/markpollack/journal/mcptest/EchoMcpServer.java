package io.github.markpollack.journal.mcptest;

import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.ServerCapabilities;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import io.modelcontextprotocol.spec.McpSchema.ToolAnnotations;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;

/**
 * A deterministic stdio MCP server for live capture tests of agentic CLIs, built only from the MCP Java SDK's
 * programmatic API: the SDK performs initialization, tool discovery, request dispatch and the newline-delimited JSON-RPC
 * transport on standard input and output. Two tools, with the names, schemas and results of the Python server used for the
 * earlier Codex live MCP evidence, so captures stay comparable:
 *
 * <ul>
 * <li>{@code echo_record {"text": string, "n": integer}}: text content {@code {"echo": <text>, "n": <n>, "ok": true}} (keys
 * sorted, the reference server's separators) and the same record as structured content, {@code isError false}; arguments
 * of the wrong type are a tool error with a fixed message.</li>
 * <li>{@code always_fails {}}: a deliberate tool error ({@code isError true}, fixed text), which is a tool result, not a
 * protocol or transport error.</li>
 * </ul>
 * A call to an unknown tool is answered by the SDK with a JSON-RPC error. The server reads no files, opens no sockets and
 * writes nothing but the protocol to standard output; it runs until standard input closes.
 */
public final class EchoMcpServer {

    static final String NAME = "journal-mcp-test-server";
    static final String VERSION = "1.0.0";
    static final String FAILURE_TEXT = "always_fails: deliberate test failure";
    static final String INVALID_TEXT = "invalid arguments: text must be a string and n an integer";

    private EchoMcpServer() {
    }

    /** Serves until standard input ends (the CLI closed the pipe) or the process is terminated. */
    @SuppressWarnings("SystemOut") // standard input and output are the MCP stdio transport itself
    public static void main(String[] args) throws InterruptedException {
        CountDownLatch ended = new CountDownLatch(1);
        McpJsonMapper json = McpJsonDefaults.getMapper();
        McpSyncServer server = server(new StdioServerTransportProvider(json, untilEnd(System.in, ended), System.out), json);
        Runtime.getRuntime().addShutdownHook(new Thread(ended::countDown));
        ended.await();
        server.closeGracefully();
    }

    /** Standard input that counts the latch down when it reaches its end. */
    static InputStream untilEnd(InputStream in, CountDownLatch ended) {
        return new FilterInputStream(in) {
            @Override
            public int read() throws IOException {
                return ended(super.read());
            }

            @Override
            public int read(byte[] b, int off, int len) throws IOException {
                return ended(super.read(b, off, len));
            }

            private int ended(int n) {
                if (n < 0) {
                    ended.countDown();
                }
                return n;
            }
        };
    }

    /** The server on a transport: its identity, the tools capability without list changes, and the two tools. */
    static McpSyncServer server(StdioServerTransportProvider transport, McpJsonMapper json) {
        return McpServer.sync(transport).serverInfo(NAME, VERSION).capabilities(ServerCapabilities.builder().tools(false).build())
                .tools(SyncToolSpecification.builder().tool(echoRecordTool()).callHandler((exchange, request) -> echoRecord(json, request.arguments())).build(),
                        SyncToolSpecification.builder().tool(alwaysFailsTool()).callHandler((exchange, request) -> alwaysFails()).build())
                .build();
    }

    static Tool echoRecordTool() {
        Map<String, Object> input = schema(types("text", "string", "n", "integer"), List.of("text", "n"));
        input.put("additionalProperties", false);
        Map<String, Object> output = schema(types("echo", "string", "n", "integer", "ok", "boolean"), List.of("echo", "n", "ok"));
        return Tool.builder("echo_record", input).description("Echo a text and an integer back as a structured record.").outputSchema(output).annotations(annotations()).build();
    }

    static Tool alwaysFailsTool() {
        Map<String, Object> input = schema(types(), null);
        input.put("additionalProperties", false);
        return Tool.builder("always_fails", input).description("A test tool that always reports a tool error.").annotations(annotations()).build();
    }

    /** echo_record: the record as text and as structured content, or a tool error when the arguments have the wrong types. */
    static CallToolResult echoRecord(McpJsonMapper json, Map<String, Object> arguments) {
        Object text = arguments == null ? null : arguments.get("text");
        Object n = arguments == null ? null : arguments.get("n");
        if (!(text instanceof String s) || !isInteger(n)) {
            return CallToolResult.builder().content(List.of(new TextContent(INVALID_TEXT))).isError(true).build();
        }
        Map<String, Object> record = new LinkedHashMap<>();
        record.put("echo", s);
        record.put("n", n);
        record.put("ok", true);
        String rendered = "{\"echo\": " + write(json, s) + ", \"n\": " + n + ", \"ok\": true}";
        return CallToolResult.builder().content(List.of(new TextContent(rendered))).structuredContent(record).isError(false).build();
    }

    static CallToolResult alwaysFails() {
        return CallToolResult.builder().content(List.of(new TextContent(FAILURE_TEXT))).isError(true).build();
    }

    /** An integral JSON number as the SDK's mapper reads it; a boolean is never an integer. */
    private static boolean isInteger(Object n) {
        return n instanceof Integer || n instanceof Long || n instanceof java.math.BigInteger;
    }

    private static String write(McpJsonMapper json, String value) {
        try {
            return json.writeValueAsString(value);
        }
        catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Schema properties in declaration order (name, JSON type pairs), so that every launch lists the same bytes. */
    private static Map<String, Object> types(String... nameTypePairs) {
        Map<String, Object> properties = new LinkedHashMap<>();
        for (int i = 0; i < nameTypePairs.length; i += 2) {
            properties.put(nameTypePairs[i], Map.of("type", nameTypePairs[i + 1]));
        }
        return properties;
    }

    private static Map<String, Object> schema(Map<String, Object> properties, List<String> required) {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("type", "object");
        s.put("properties", properties);
        if (required != null) {
            s.put("required", required);
        }
        return s;
    }

    private static ToolAnnotations annotations() {
        return new ToolAnnotations(null, true, false, true, false, null);
    }
}
