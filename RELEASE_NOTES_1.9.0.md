# Agent Journal 1.9.0

Closes four capture gaps. Every field added here was **already available from the provider and was
being discarded at parse time** — this release stops throwing it away.

The rule that motivates the whole release: **analysis is free to redo, capture is one-shot per
run.** A trajectory recorded without these columns is permanently missing them; there is no
backfilling a measurement that was never taken. Better columns, not bigger N.

All changes are additive. No schema key was renamed or removed, the five keys the Markov trace
loader depends on are byte-for-byte unchanged, and every extended record keeps a back-compatible
constructor, so existing captures and consumers continue to work untouched.

## What is newly captured

### Per-turn token vector, at step granularity

Per-step records previously carried input and output tokens only. They now carry the full five-field
vector — `input`, `output`, `thinking`, `cacheCreation`, `cacheRead` — on `JournalStep`,
`StepCostEvent` (`analysis.jsonl`) and the trace's `step_cost` line.

**Extended-thinking tokens are now read from the wire rather than estimated.** The provider reports
an exact per-turn count at `usage.output_tokens_details.thinking_tokens` — nested one level deeper
than the rest of the usage block, which is why it was missed. Capture previously fell back to a
`chars / 4` heuristic over captured thinking blocks. Thinking remains a documented *subset of*
output tokens and is never added to a billed total.

Without the cache components at step granularity a per-state cost cannot be priced at all, since
cache reads and cache creation are billed at different rates from fresh input.

### `stopReason` and `maxTurns`, always written together

`numTurns = 55` was uninterpretable: finished, or cut off at the limit? Absorbing-state semantics
are undefined without knowing which.

- New vendor-neutral `StopReason` enum in `journal-core` (`NATURAL_DONE`, `TOOL_USE`, `MAX_TURNS`,
  `MAX_TOKENS`, `REFUSAL`, `ERROR`, `CANCELLED`, `UNKNOWN`), with `isTruncatedRun()` for the
  right-censored cases.
- New `ClaudeStopReasons` maps Claude Code's *two* stop vocabularies onto it: the per-turn wire
  `message.stop_reason` and the run-level `ResultMessage.subtype`. The run-level outcome wins — a
  run whose last turn said `end_turn` but whose harness reported `error_max_turns` was cut off, and
  recording it as a natural finish is exactly the misreading this field exists to prevent.
- `PhaseCapture` carries `stopReason` + `maxTurns`; `BaseRunRecorder` writes both into
  `LLMCallEvent` metadata unconditionally, and the trace `result` line carries both.

Claude Code never echoes the turn ceiling back — `maxTurns` is a caller-side `QueryOptions` value —
so a new `SessionLogParser.parse(…, maxTurns)` overload takes it from the caller that set it. The
parser also probes the wire first, in case a future CLI version starts reporting it. An unreported
ceiling records as `-1` ("not reported"), never as a plausible-looking default. Hitting the ceiling
now logs a WARN naming the trajectory as right-censored.

### Per-tool `durationMs` and turn index

The one genuine regression in the set: the earliest capture format carried `phase_turns` and
`phase_duration_ms`, and later versions dropped them, taking the dwell-time half of the semi-Markov
question with them. The duration was in fact still being computed at parse time, written to a log
line, and then thrown away.

- `ToolCallEvent` gains `turnIndex` and `turnId`; its long-present `durationMs` is now actually
  populated on the production record path.
- `ToolUseRecord` gains `turnId` / `turnIndex`; `ToolResultRecord` gains `durationMs`.
- The trace's `tool_use` line carries `turnIndex` / `turnId`, and `tool_result` carries `durationMs`.

`durationMs` is the **observed** interval between a tool call and its result arriving on the SDK
stream — the provider reports no tool latency of its own. For a live capture that is the tool's
wall-clock time as seen by the consumer; for a replay of an already-recorded stream it measures the
replay. Near-zero durations across an entire run are a replay signature, not instant tools. This
caveat is documented on the field itself.

### `raw/` in the run-directory contract

The run directory now reserves `raw/` for verbatim provider artifacts, content-addressed and keyed
to the run, resolvable via the new `JournalStorage.rawDirectory(experimentId, runId)`. A backend
with no filesystem returns empty rather than inventing a path, and resolving never creates the
directory — so an absent `raw/` means "nothing was archived", not "archival failed".

**This is a location contract, not a copier, and the copier is deliberately not in this release.**
Copying the provider session file belongs to the experiment harness, which knows what it launched
and when; the journal's half is making raw findable from the run record. A test asserts the journal
archives nothing itself, so the boundary cannot drift silently.

## Known caveat, recorded rather than papered over

**`PER_TURN_INPUT_NOT_ADDITIVE`** — per-turn token fields do not sum to the run's reported totals,
and this is a property of the data, not a capture defect. `input_tokens` and the cache fields are
per-request measurements over an accumulating context window: the same prompt prefix is re-read
every turn, so summing them exceeds any notion of "the input of the run", while the result's own
usage block is a final-request snapshot that under-counts a long run. Neither is wrong; they answer
different questions, and making the numbers agree would destroy information.

The reconciliation that *does* hold is the cost identity, not a token identity: the per-model cost
decomposition sums to the run total. New `PhaseCapture.reconcilesToModelCosts()` checks exactly
that, and returns `false` when `modelUsage` was unavailable — with nothing to reconcile against the
identity is unverified, and unverified must not read as verified. Both the caveat and the check are
documented on `TurnUsage` and `PhaseCapture`.

## Guardrail

`CaptureContractTest` asserts every field above against the **production record path** end to end —
the parser, the durable `events.jsonl` / `analysis.jsonl`, and the JSONL trace — and fails if any
one stops being persisted. Three separate silent field losses have happened in this project's
history; a field that quietly stops being written is not noticed until someone asks a question of a
dataset that can no longer answer it, and by then the runs are spent.

The offline re-derivation closure (`JournalSteps.fromEvents` matching `fromPhaseCapture`) is
preserved across all the new fields, so a lost `analysis.jsonl` is still fully recoverable from the
immutable event log.
