# Real recorded runs (scrubbed)

Real Claude Code runs recorded through Client (2.1.316, and one 2.1.319), laid out as recorded: `files/native-<executionId>/`
holds the sealed Client manifest and the native files, and `files/terminal/` the envelope Client delivered. They are
scrubbed derivatives of private recordings (see each `SCRUBBED.md`): structure, identifiers, timestamps and numbers
are the original's, and other strings are hash tokens. `RealRecordingTest` seals them through this writer, and
`regenerateTckRealRecordingCases` writes the TCK's `ocsf/terminal-real` cases from them.

| Run | What it exercises |
|---|---|
| `heartbeat` | one 40 s IntelliJ MCP call; Claude Code writes `tool_progress` heartbeats naming it |
| `mcp` | IntelliJ MCP calls whose requests and results the stream and the session file both write |
| `sub-agent` | one `Agent` call; the sub-agent's records in the stream and its own `subagents/agent-<id>.jsonl` |
| `sub-agent-background` | one background `Agent` call; the parent's result is written while the sub-agent still runs (its meta file states no `finished`), so the archive is INCOMPLETE |
| `txbench-2.1.319` | Claude Code 2.1.319: one `Read` of `message.txt` (the retained TX Bench Stage 1 capture of 2026-11-08). Recorded before a qualifier admitted 2.1.319, so its stored envelope is evidence-only; `RealRecording.seal` states the association Client states for a qualified run. `ClaudeSdkVersionTest` reads it |
