# Journal MCP test server

A deterministic stdio MCP server for live capture tests of agentic CLIs. It is test tooling: the writer and the TCK do
not depend on it, and it is never published.

It is built only from the [MCP Java SDK](https://github.com/modelcontextprotocol/java-sdk) 2.0.1 (`mcp-core` and its
Jackson 3 binding) in its programmatic form: `McpServer.sync` over `StdioServerTransportProvider`. The SDK performs
initialization, tool discovery, dispatch and the newline-delimited JSON-RPC transport. There is no Spring, no
dependency-injection framework, no network listener and no authentication.

## Tools

| Tool | Arguments | Result |
|---|---|---|
| `echo_record` | `{"text": string, "n": integer}` (both required, no others) | text content `{"echo": <text>, "n": <n>, "ok": true}` (keys sorted) and the same record as `structuredContent`; `isError: false`. Arguments of the wrong type return a tool error with a fixed message |
| `always_fails` | `{}` | a deliberate tool error: `isError: true`, text `always_fails: deliberate test failure` (a tool result, not a protocol or transport error) |

A call to any other tool name is answered by the SDK with a JSON-RPC error. The names, schemas and results match the
Python reference server used for the earlier Codex live MCP recording, so captures stay comparable. The server reports
itself as `journal-mcp-test-server` 1.0.0, reads no files, writes only the protocol to standard output and exits when
standard input closes.

## Build and launch

```
./mvnw -pl journal-mcp-test-server package
java -jar journal-mcp-test-server/target/journal-mcp-test-server.jar
```

The jar's manifest names its runtime closure in `target/lib/`; copy the jar together with `lib/` to keep a launch path
stable across rebuilds, and record the files' hashes beside a capture.

## Configuring a CLI for one capture

Use a configuration scoped to the launch or to a synthetic workspace, never the user's persistent settings. The
configuration name of the server (here `journal_echo`) is what CLIs use as the namespace; it is not the server's
self-reported name.

- **Claude Code** (launch-scoped): write `mcp.json` with
  `{"mcpServers": {"journal_echo": {"command": "java", "args": ["-jar", "<path>/journal-mcp-test-server.jar"]}}}` and add
  `--mcp-config <path>/mcp.json --strict-mcp-config --allowedTools mcp__journal_echo__echo_record mcp__journal_echo__always_fails`.
  Tools appear as `mcp__journal_echo__<tool>`.
- **Antigravity CLI** (workspace-scoped): put the same JSON in `<workspace>/.agents/mcp_config.json`. Calls appear as the
  dispatcher tool `call_mcp_tool` with parameters `ServerName`, `ToolName` and `Arguments`. In the recorded headless print run
  the default request-review permission mode auto-denied the `mcp` category (stderr: headless mode cannot prompt for it); a
  successful call needs an explicit permission decision by whoever owns the environment. A recorded run with
  `--dangerously-skip-permissions` ran both tools: each echo's `tool_info.output` is the server's own text content, and
  `always_fails` ends in state ERROR with `tool_info.error {type TOOL_ERROR}`.
- **Codex CLI** (launch-scoped): `-c mcp_servers.journal_echo.command="java" -c 'mcp_servers.journal_echo.args=["-jar","<path>/journal-mcp-test-server.jar"]'`.
  Calls appear as `mcp_tool_call` items with `server` and `tool`.

A stream shows what its CLI projects, not the MCP wire protocol: none of these streams reports initialization,
discovery, progress or cancellation messages, and a capture is evidence of the CLI's projection only.
