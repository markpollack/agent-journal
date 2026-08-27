# Agent Journal 1.10.0

Additive release. **One new module: `junie-cli-capture`.** Nothing else changes.

## What is new

`junie-cli-capture` records trajectories from the Junie CLI, joining the existing adapters for
Claude Code, Gemini, Grok, Codex and Antigravity.

| Class | Role |
|---|---|
| `JunieSessionParser` | parses Junie's own durable event stream into journal records |
| `JuniePhaseCapture` | the per-phase capture record |
| `JunieRunRecorder` | writes captures into a journal `Run` |
| `JunieToolUseRecord` / `JunieToolClassifier` | tool calls and their classification |
| `JunieStopReasons` | maps Junie's stop vocabulary onto the vendor-neutral `StopReason` |
| `JunieModelCost` / `JunieJournalSteps` | cost and step metadata |

Junie speaks the Agent Client Protocol, so the adapter is tested against **both** shapes it can
emit — `junie-acp-events.jsonl` and `junie-cli-events.jsonl` fixtures, 288 lines of parser tests.

## Compatibility

**No existing module changed.** `journal-core` and the five existing capture adapters are
byte-identical in behaviour to 1.9.0. There is no migration, and nothing to do if you do not use
Junie.

`junie-cli-capture` is a new coordinate:

```xml
<dependency>
    <groupId>io.github.markpollack</groupId>
    <artifactId>junie-cli-capture</artifactId>
    <version>1.10.0</version>
</dependency>
```

## Why this release exists

`agent-client`'s Junie provider cannot resolve without it. That is the whole reason for the version:
this is not a feature cycle, it is the missing half of a two-repository change. The
`1.10.0-SNAPSHOT` pin that has been sitting in `agent-client` was correctly anticipating this release
rather than pointing at a mistake.

## Carried forward from 1.9.0, unresolved

**Per-turn `outputTokens` and `thinkingTokens` do not reconcile with phase totals.** On a measured
run, per-turn `outputTokens` summed to 388 against a phase total of 35,416, and per-turn
`thinkingTokens` read 0 against a phase-reported 8,501. Input and both cache figures reconcile
exactly after de-duplicating by `messageId`.

The consequence is unchanged and worth restating rather than assuming it is known: **a per-tool
dollar figure cannot be computed correctly yet**, because the reference cost is driven by output and
thinking tokens. The tool-to-turn linkage itself is sound — 31 of 56 `TurnUsage` rows carried
`toolUseIds`, exactly matching the 31 tool calls in that run.

This is not a regression in 1.10.0; it is a known limit of the per-turn cost vector introduced in
1.9.0, restated here so nobody builds pricing on it in the meantime.
