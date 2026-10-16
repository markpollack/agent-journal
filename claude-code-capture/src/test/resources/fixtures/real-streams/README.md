# Real captured Claude Code streams (CLI 2.1.299)

Captured with `claude -p <synthetic prompt> --output-format stream-json --verbose --model claude-haiku-4-5` in an
empty directory with three tiny synthetic files. Prompts and file contents are synthetic. Nothing is
hand-edited; identifiers are replaced (UUIDs by a deterministic counter, paths by `/work`, the local socket path,
init-line lists that describe the local installation emptied).

Each directory holds:
- `stream.jsonl`: the run's `stream-json` output, the input of `SessionLogParser`.
- `oracle/main.jsonl`, `oracle/subagent-N.jsonl`: the same run's native transcript (main file and sub-agent
  files) reduced to `type`, `sessionId`, `isSidechain`, `agentId`, `message.id`, `message.model`,
  `message.usage` and `cost-state` records. The test computes its expected values from these and from the result
  lines of the stream, independently of Journal's parser.

| Fixture | What it exercises |
|---|---|
| `single-result-parallel-calls` | one result line, parallel tool calls in one message, no sub-agent |
| `foreground-subagent-one-result` | a foreground sub-agent; one result line; all-agents totals above the main thread's |
| `background-subagent-two-results-a`, `-b` | a backgrounded sub-agent: its completion starts a follow-up turn and a second result line |

Findings these fixtures encode (see `plans/learnings/particle-chamber/results/TOKEN-RECONCILIATION-2026-10-14.md`
in the private steward repository): per-message `output_tokens` on stream lines are placeholders; `result.usage`
covers the main thread of that result only; `modelUsage` and `total_cost_usd` are session running totals including
sub-agents.

Regenerate or add fixtures with the steward's `make_fixtures.py`; add a fixture for every new CLI version that
changes stream behaviour.
