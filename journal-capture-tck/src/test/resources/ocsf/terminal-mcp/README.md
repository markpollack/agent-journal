# Terminal MCP cases (labelled synthetic)

These are sealed Claude Code 2.1.316 one-shot archives. Each was written by the shipped `TerminalReceiver` under
`client-terminal-claude-2.1.316/1`, with the qualifier's assembly expectation stored beside it. The native records
are synthetic. Their shapes follow a 2.1.316 one-shot with an IntelliJ MCP server: the request and result of one MCP
call appear in both the stream and the session file.

The stream's observation of an MCP tool result carries an `mcp` member that states only its `basis`, and the
session file's observation has no `mcp`. Provenance is not a fact, so the consolidated result is not "One richer"
for that member.

| Case | Writer | Consolidated MCP result |
|---|---|---|
| `claude-mcp` | this revision | relation from facts only |
| `claude-mcp-aa75c02` | `aa75c02` (before the fix) | "One richer" for the basis-only `mcp` member; the oracle disagrees |

Regenerate the first with `-Dterminal.mcp.regenerate=<directory>` on `TerminalNativeTest` in
`journal-ocsf-writer`. The second is historical evidence and must not be regenerated.
