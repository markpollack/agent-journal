# Terminal child-scope cases (labelled synthetic)

These are sealed Claude Code 2.1.316 one-shot archives. Each was written by the shipped `TerminalReceiver` under
`client-terminal-claude-2.1.316/1`, with the qualifier's assembly expectation stored beside it. The native records
are synthetic, but their shapes match 2.1.316:

- The `tool_progress` heartbeat follows the bundle's `tool_heartbeat` -> `tool_progress` emitter. It names the
  running call in `parent_tool_use_id`.
- The sub-agent's records carry the spawning `Agent` call in `parent_tool_use_id`, as in
  `fixtures/claude-live-2.1.304-features`.

| Case | Writer | Stream |
|---|---|---|
| `claude-heartbeat` | this revision | one MCP call, one heartbeat naming it, its result |
| `claude-sub-agent` | this revision | one `Agent` call whose sub-agent runs one `Read` |
| `claude-heartbeat-bd04735` | `bd04735` (before the fix) | as `claude-heartbeat`; the writer made the heartbeat a child core |

Regenerate the first two with `-Dterminal.child.regenerate=<directory>` on `TerminalNativeTest` in
`journal-ocsf-writer`. The third is historical evidence and must not be regenerated.
