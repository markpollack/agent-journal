# Agent Journal 1.11.0

> Release candidate. Not yet published to Maven Central.

Captures Claude Code sub-agents as runs of their own, and fixes how runs end and how tokens are
counted. Several stored values change meaning with this version. Records written by earlier
versions are never rewritten, and from this version a file says which version wrote it, so old
and new records can be told apart.

## Read this first: values that change

Every boundary below is the same test: **the header line of `events.jsonl` and `analysis.jsonl`
now carries `producer` and `producerVersion`. A header without `producerVersion` was written by
1.10.1 or earlier.**

| Value | Up to 1.10.1 | From 1.11.0 |
|---|---|---|
| `run.json` `status` of a Claude Code `RunRecorder` run that was closed without `finish()` | `FINISHED`, `success=true`, even when an exception left the block | `CRASHED`, `success=false` |
| Claude Code `llm_call.tokenUsage` input and cache tokens | added once per stream line, so a message with several content blocks was counted several times | once per message |
| Claude Code `llm_call.tokenUsage.outputTokens` | the sum of the per-message counts on the stream, which are start-of-message figures | the count on the result message, when larger |
| Claude Code `llm_call.metadata.turns`, `tool_call.turnIndex` | one turn per stream line | one turn per message |
| Claude Code `step_cost` events | a step for every stream line, including the text and thinking lines of a message that also called a tool | one step per tool call, plus one per message without a tool call; the shares differ accordingly |
| Claude Code runs that started sub-agents | the sub-agents' tool calls, tokens and steps were recorded as the run's own | the run holds the main loop only; each sub-agent is its own run |
| Codex `llm_call.tokenUsage.inputTokens` | Codex's input count, cached input included | cached input excluded, as for every other agent. An older value converts exactly: subtract `cacheReadTokens` |
| Grok, Codex and Junie `tool_call.durationMs` | 0 | -1, "not measured" |

### Why the schema version is still 1

The schema version says what a reader must know to parse a file: keys, types, units, enum
vocabularies and each field's documented definition. It changes when a file written by the new
version could be misread by a reader that correctly implements the previous format. It does not
change when the producer writes better values under an unchanged definition; those changes are
told apart by `producerVersion`.

| Change | Kind | Why |
|---|---|---|
| `CRASHED` for a recorder closed without `finish()` | producer correction | `CRASHED` already meant "ended abnormally"; the recorder now uses it where it used to write `FINISHED` wrongly |
| Claude Code input and cache tokens once per message | producer correction | the value was counted several times; the definition ("the tokens of the call") is unchanged |
| Claude Code output tokens from the result message | producer correction | the per-message figures were start-of-message counts; same definition |
| One turn per message in `turns` and `turnIndex` | producer correction | a turn was always documented as an assistant message; lines were being counted |
| `step_cost` events and shares | producer correction | derived from the corrected turns under the same attribution method |
| Sub-agents as their own runs | producer correction plus **added keys** | a run's events were never defined to include another agent's; the new config, tag, summary and metadata keys are additive, and `parentRunId` existed |
| Codex `inputTokens` without cached input | producer correction, **narrowed definition** | `TokenUsage` said whether input included cache reads "depends on what the agent reports"; it now says input excludes them. New values satisfy both readings; old Codex values convert exactly |
| `-1` for an unmeasured tool duration | producer correction | `-1` was already documented as "not measured" |
| `producer` and `producerVersion` in the header | added keys | every reader skips the header line |

What **would** have required a new schema version, and did not happen here: a renamed or removed
key; a changed type or unit; a key reused for a different quantity; a new value in a stored
enum. The deferred additions (`FileChange.oldPath`, more components on `step_cost`, a "cost not
reported" attribution value) fall under that rule.

Three computed values also change, and are not stored: `TokenUsage.total()`,
`LLMCallEvent.totalTokens()` and `PhaseCapture.totalTokens()` no longer add thinking tokens on top
of output tokens, which already include them; `TokenUsage.cacheHitRatio()` is cache reads divided
by input plus cache reads and stays between 0 and 1; `TokenUsage.effectiveInputTokens()` is
deprecated and returns the input tokens.

## Sub-agent capture (Claude Code)

Claude Code sends a sub-agent's messages on the same stream as the main loop's, each marked with
the ID of the tool call that started the sub-agent. Earlier versions did not read that mark.

**What is captured.** For each sub-agent whose messages arrive: its prompt, its tool calls and
tool results with the usage of the turns that made them, and, when Claude Code is started with
`--forward-subagent-text`, its text and thinking together with its turns that called no tool.
They are in `PhaseCapture.subagents()`, one `SubagentCapture` each, and the recorder writes each
as a run:

- `parentRunId` is the run of the agent that started it: the recorder's run, or the enclosing
  sub-agent's run for a nested one.
- The tag `track=subagent` marks it.
- The config key `subagent.spawnToolUseId` is the ID of the spawning tool call. That ID is also
  the `id` of the `tool_call` event in the parent run, which is the join.
- Its files have the same kinds of events as any run. No record type gained a field and no enum
  gained a value.
- The parent's `llm_call` metadata lists the sub-agent runs under `subagents`.

**Cost.** Claude Code reports one cost for the call, sub-agents included, and none per
sub-agent. That cost stays on the main loop's run, marked `costIncludesSubagents=true`, and is
split over the main loop's steps. A sub-agent's `llm_call` has a cost of 0 with
`costAvailable=false` and `costSource=included_in_parent`, and its step costs are 0 marked
`EVEN_SPLIT`. Adding cost over a run and its sub-agent runs gives the reported total once.

**Tokens.** Each run has its own. With `--forward-subagent-text`, input and cache tokens over a
run and its sub-agent runs add up exactly to what Claude Code reports for the whole call
(verified live through agent-client: 12 / 41,544 / 151,728 against the same `modelUsage`
figures). Without the flag, a sub-agent's turns that called no tool are not on the stream, so its
recorded usage is short by those turns (in the same live check, about 58,000 cache-read tokens
across two sub-agents); the call's own total is complete either way. A sub-agent's output count
is a lower bound in both cases: the stream reports no final output figure for a sub-agent (the
same check: 536 recorded against 672 reported).

**How a sub-agent's run ends.**

| Claude Code reported | Run status | `summary` |
|---|---|---|
| `completed` | `FINISHED` | `subagent.status=completed` |
| `failed` or `killed` | `FAILED` | the status as reported |
| another status, or none | `CRASHED` | the status as reported, or `unknown`. Here `CRASHED` means that no successful end was seen, not that the sub-agent failed |

`subagent.statusSource` says where the status was read. A status is never inferred.

**What is missing is recorded as missing.**

- A spawning tool call for which no sub-agent messages arrived is listed under
  `subagentsWithoutTrack` in the parent's `llm_call` metadata, next to Claude Code's own counts
  (`subagentStats`: spawned, completed, failed, killed, refused).
- If the messages came without their wire lines, the agents cannot be told apart. The metadata
  then has `subagentTracksAvailable=false`, no sub-agent run is written, and the sub-agent's
  activity is merged into the run as before.
- A run without sub-agents has none of these keys.

### Supported, and not

| | |
|---|---|
| Observed with Claude Code **2.1.292** | Sub-agent tool calls, results, prompt and usage, with and without `--forward-subagent-text`, in `--print` mode and with the options `claude-code-sdk` 1.7.0 passes. Sub-agent text with that flag. A sub-agent started by a sub-agent. Two sub-agents started by one message |
| Covered by a hand-written test session only | Sub-agent thinking; a failed sub-agent; a refused start; a sub-agent whose end is not reported |
| Not observed, and not claimed | Other Claude Code versions; background sub-agents; nesting deeper than two; a resumed session; a capture run end to end through `claude-code-sdk` |
| Verified through the Java path | Claude CLI 2.1.292 → `claude-code-sdk` 1.7.0 → agent-client (a local branch adding a `forwardSubagentText` option) → `RunRecorder` → files → fresh reader, flag on and off: linkage, separation, status, and the exact input and cache reconciliation above |
| Needs the caller | Complete sub-agent capture needs Claude Code started with `--forward-subagent-text` (`CLIOptions.forwardSubagentText` in `claude-code-sdk` 1.6.0 and later). Without it the sub-agents' prompts, tool calls, results and tool-turn usage are captured, but not their text, thinking, or text-only turns and that usage |
| Where sub-agent text lives | In `SubagentCapture.textOutput` and in a trace file when one is written. No journal event or run file stores text, for the main loop or for a sub-agent; this is unchanged |
| Not captured | Sub-agents of Codex, Antigravity, Grok and Junie. Sub-agents from Claude Code's transcript files |

### If you read the files yourself

A sub-agent's run is a run directory like any other. A reader that treats every run directory as
one item of an experiment will count each sub-agent as an item, at a cost of 0. Skip or group runs
whose `run.json` has a `parentRunId` or the tag `track=subagent`.

The trace file is unchanged: it is still a flat record of every message, sub-agents' included.

## Run lifecycle

- `RunRecorder.close()` cannot tell a normal exit from an exception leaving the
  try-with-resources block. Only `finish()` now records a completed run; a recorder closed
  without it ends its run `CRASHED`. **Call `finish()` on the success path.** A plain `Run` is
  unchanged: `close()` still ends it `FINISHED`.
- When derived events were logged to storage that does not keep them, the recorder now ends the
  run before it throws, instead of leaving it `RUNNING` with no end time.

## Reading files from other versions

- Unknown fields in a record are ignored, so files from a later version that only adds fields
  still load. Unknown `@type` names, unknown enum values and damaged lines still fail.
- The schema version rule is corrected: it changes when a field is renamed, removed or given a
  new meaning, **and when a stored enum gains a value**, since an older reader fails on one. It
  is still 1.
- `events.jsonl` or `analysis.jsonl` with a newer schema version than the reader knows is now
  refused with an error that names both versions, instead of being half-read.
- Event types registered with `Journal.registerEventType` are kept for the whole process and
  apply to storage configured later.

## Also in this release

About thirty smaller fixes since 1.10.1, none of which changes a public signature. The ones a
reader of stored data may notice:

- Codex `function_call` tool calls are recorded under their own names, with the arguments kept
  unchanged. A Codex tool call that exited with code 0 is no longer marked failed.
- A Grok tool call is marked failed from its final status. A Grok, Antigravity or Codex stream
  with no final record is marked as an error.
- A run that finishes as failed or crashed records `success=false`. A failed run is saved when
  its exception has no message. The run record is saved when a summary value is set.
- Config and summary entries keep their insertion order.
- A Claude Code LLM call event carries its response ID.
- A custom `DerivedEvent` is rejected when written, not when the file is next read.
- A phase or a tool call without a name is recorded instead of throwing, and a trace line that
  cannot be written no longer stops parsing.
- An ended call is never left current.

The Javadoc of every public type was rewritten, and the in-repository documentation site was
removed.

## Dependencies

- Jackson 2.22.2 → **2.22.3**, and the Jackson 3 line that arrives through `claude-code-sdk`
  3.2.2 → **3.2.3**. Both clear four denial-of-service advisories published on 2026-09-22 and
  2026-09-23 against `jackson-core` and `jackson-databind` (CVE-2026-89407, CVE-2026-89425,
  CVE-2026-91776, CVE-2026-91777). 1.10.1 pins the affected versions.
- `claude-code-sdk` stays at 1.7.0 and `gemini-cli-sdk` at 0.30.0.

## Known limits

- **Cost per phase in a shared session.** Claude Code's `total_cost_usd` is the running total of
  a session. A caller that records several prompts of one session as separate phases gets each
  phase's cost as the total so far. Unchanged in this release.
- **Per-turn output tokens** on the stream are start-of-message figures. The run total is now
  right; the per-turn figures, and so the weights that split cost over steps, are not.
- **Codex reasoning text and approval decisions** are not in Codex's session files (only
  encrypted reasoning and the approval policy are), so they are not captured.
- **Claude Code permission denials** are kept only in a trace written with `TraceRawMode.FULL`.

## Upgrading

- Call `RunRecorder.finish()` where a run completes; `close()` alone now records `CRASHED`.
- Replace `TokenUsage.effectiveInputTokens()` with `inputTokens()`.
- Callers that build a `PhaseCapture` themselves need no change: every earlier constructor
  remains. A record pattern over `PhaseCapture` needs three more components.
- If you serialise `PhaseCapture`, it has three more properties: `subagents`,
  `subagentTracksAvailable` and `reportedSubagentStats`.
- Do not compare Claude Code or Codex token counts across the 1.11.0 boundary without the table
  at the top.

## Maven Central

`io.github.markpollack` version `1.11.0`: `agent-journal-parent`, `journal-core`,
`claude-code-capture`, `gemini-cli-capture`, `grok-cli-capture`, `codex-cli-capture`,
`antigravity-cli-capture`, `junie-cli-capture`.
