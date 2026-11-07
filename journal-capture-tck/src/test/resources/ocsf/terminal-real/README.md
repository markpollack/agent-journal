# Real-recording terminal cases

Sealed archives of real Claude Code 2.1.316 runs, recorded through Client's Java SDK route and delivered to
`TerminalReceiver` under `client-terminal-claude-2.1.316/1`. The qualifier's assembly expectation is stored beside
each one. Nothing here is hand-written.

The native bytes are scrubbed derivatives of private recordings: keys, record order, record types, identifiers,
enumerations, timestamps and every number are the original's, and other strings are hash tokens (equal strings stay
equal). Each scrubbed run was sealed beside its original and gave identical cores, events, usage, field evidence,
conflicts and MCP targets. Each case's `SCRUBBED.md` names its original files by sha256. The scrubbed inputs are in
`journal-ocsf-writer/src/test/resources/claude-real`.

| Case | Writer | Run |
|---|---|---|
| `claude-heartbeat` | this revision | one 40 s IntelliJ MCP call with heartbeats |
| `claude-mcp` | this revision | a run of IntelliJ MCP calls, seen by the stream and the session file |
| `claude-sub-agent` | this revision | one general-purpose sub-agent, with its own session file and meta file |
| `claude-heartbeat-bd04735` | `bd04735` | the heartbeat run: the writer made the heartbeat a child core |
| `claude-mcp-aa75c02` | `aa75c02` | the MCP run: MCP results called "One richer" for a basis-only member |
| `claude-sub-agent-397ec12` | `397ec12` | the sub-agent run: its own session file preserved but unread |

Regenerate the first three with `-Dterminal.real.regenerate=<directory>` on `RealRecordingTest` in
`journal-ocsf-writer`. The other three are historical evidence and must not be regenerated; the expectation beside
each is the current qualifier's, the reader's input rather than part of the sealed archive.
