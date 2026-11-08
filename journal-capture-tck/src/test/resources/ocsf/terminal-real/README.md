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
| `claude-mcp-payload-keys` | this revision | the MCP run with one request's input set, in both sources, to arguments named like Journal members (`basis`, `data`, `content_blocks`, `missing_required`; an edited real run, see EDITED.md) |
| `claude-sub-agent-background` | this revision | a background sub-agent still running when the parent's result was written: INCOMPLETE (`acquisition.child_session`) |
| `claude-txbench-2.1.319` | this revision | Claude Code 2.1.319, one `Read` (the retained TX Bench capture): delivered under the version-neutral `client-terminal-claude-sdk/1`, its own version recorded |
| `claude-heartbeat-bd04735` | `bd04735` | the heartbeat run: the writer made the heartbeat a child core |
| `claude-mcp-aa75c02` | `aa75c02` | the MCP run: MCP results called "One richer" for a basis-only member |
| `claude-sub-agent-397ec12` | `397ec12` | the sub-agent run: its own session file preserved but unread |

Regenerate them with `-Dterminal.real.regenerate=<directory>` on `RealRecordingTest` in `journal-ocsf-writer`. The
2.1.316 cases among them were delivered under the versioned `client-terminal-claude-2.1.316/1` and are kept as sealed,
so they show that such recordings stay readable; a regeneration delivers every run under `client-terminal-claude-sdk/1`. The other three are historical evidence and must not be regenerated; the expectation beside
each is the current qualifier's, the reader's input rather than part of the sealed archive.
