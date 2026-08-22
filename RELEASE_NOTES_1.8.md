# Agent Journal 1.8.0

Agent Journal 1.8.0 adds tool-trajectory capture adapters for Grok CLI, Codex CLI,
and Antigravity CLI. Each adapter turns its CLI's durable JSONL stream into ordered
`ToolCallEvent` and `StepCostEvent` records with stable tool-call identities.

## Grok CLI capture

The new `grok-cli-capture` artifact parses Grok's ACP-shaped `streaming-json` output.
It pairs `tool_call` and `tool_call_update` records by `toolCallId`, preserves both the
reported tool name and ACP semantic kind, and records token usage and
`end.total_cost_usd`. Because the stream has no durable turn-to-tool cost join, the
real session total is retained and attributed evenly across the captured tool steps.

## Codex CLI capture

The new `codex-cli-capture` artifact parses durable Codex rollout JSONL. Codex records
many tool invocations under the outer function name `exec`; the adapter deliberately
classifies the nested `payload.input` call instead. It recognizes nested tool methods,
extracts `exec_command` arguments without evaluating them, and assigns useful semantic
names such as `Search`, `Read`, `Inspect`, `Test`, `Build`, and `Git`.

The classifier is conservative around shell syntax. Aliases, shell functions, quoted
operators, and behavior hidden behind an interpreter may fall back to `Shell`; the raw
input and command are retained so callers can reclassify them later. Codex rollout
token counts, including cached and reasoning tokens, are preserved. Codex does not
report monetary cost in this stream, so cost records are zero-valued and explicitly
marked `costAvailable=false` with an `unreported` source.

## Antigravity CLI capture

The new `antigravity-cli-capture` artifact parses Antigravity's streaming JSON. It
pairs active and terminal updates by `step_index`, handles both `DONE` and `ERROR`
steps, records parameters, output or error text, duration, and terminal token usage,
and derives stable identities from the conversation and step positions. Antigravity
does not report monetary cost in this stream, so cost provenance is marked in the same
way as Codex.

## Common capture behavior

All three adapters expose parsed tool uses on their phase-capture records and provide
recorders that emit ordered tool and cost events. Fixture tests cover distinct tool
states for every adapter, including clean and error Antigravity sessions and the Codex
outer-`exec` classification trap.

The Claude Code SDK dependency is updated from 1.4.0 to 1.5.0.

Gemini CLI capture remains turn-level and does not currently provide a tool trajectory;
Gemini is therefore not included in the multi-CLI tool-trajectory claim for this release.

## Compatibility

This release is additive on the existing event and trace contracts. `journal-core` and
the three new capture modules target Java 17. The existing Claude Code and Gemini CLI
capture modules continue to require Java 21 because of their vendor SDK dependencies.
488 tests pass across the seven-module reactor at the release candidate commit.
