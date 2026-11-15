# Real-recording terminal cases

Sealed archives of real Claude Code runs (2.1.316 and 2.1.319) and one Codex CLI 0.166.0 run, recorded through Client's
SDK routes and sealed again offline by `TerminalReceiver` under the version-neutral terminal profiles. The qualifier's assembly expectation is stored beside
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
| `codex-one-read` | this revision | a real Client Codex CLI 0.166.0 one-read run: delivered under the version-neutral `client-terminal-codex-exec/1`, its own version recorded |

Regenerate them with `-Dterminal.real.regenerate=<directory>` on `RealRecordingTest` in `journal-ocsf-writer`. Every case is
written in the 1.0.0 formats (`RefusedFormatTest`); a recording sealed before 2.0.0 is refused, shown with the copy under
`../refused/`; a regeneration delivers every run under `client-terminal-claude-sdk/1`. The expectation beside each case is the current
qualifier's, the reader's input rather than part of the sealed archive.
