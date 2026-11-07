# Real recorded runs (scrubbed)

Three real Claude Code 2.1.316 runs recorded through Client, laid out as recorded: `files/native-<executionId>/`
holds the sealed Client manifest and the native files, and `files/terminal/` the envelope Client delivered. They are
scrubbed derivatives of private recordings (see each `SCRUBBED.md`): structure, identifiers, timestamps and numbers
are the original's, and other strings are hash tokens. `RealRecordingTest` seals them through this writer, and
`regenerateTckRealRecordingCases` writes the TCK's `ocsf/terminal-real` cases from them.

| Run | What it exercises |
|---|---|
| `heartbeat` | one 40 s IntelliJ MCP call; Claude Code writes `tool_progress` heartbeats naming it |
| `mcp` | IntelliJ MCP calls whose requests and results the stream and the session file both write |
| `sub-agent` | one `Agent` call; the sub-agent's records in the stream and its own `subagents/agent-<id>.jsonl` |
