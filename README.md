# agent-journal

An execution ledger for agent workflows. Agent Journal records what an agent run
actually did — every LLM call, tool invocation, state transition and cost — as typed
events in an append-only log, so runs become data you can compare, replay and judge.
It is a ledger, not an observability agent: nothing is sampled, nothing is aggregated
away, and the file on disk is the record.

**📖 Documentation: [lab.pollack.ai/projects/agent-journal](https://lab.pollack.ai/projects/agent-journal)**

## Concepts

An **Experiment** groups **Runs**. A Run is one attempt at a task; it opens, emits
events, carries a Config in and a Summary out, and ends as `FINISHED`, `FAILED` or `CRASHED`.

Events are written to two streams per run. `events.jsonl` is the **immutable execution
log** — what happened. `analysis.jsonl` is the **derived analysis log** — what was
computed about it afterwards (per-step cost attribution today), linked back by step id
and regenerable from the execution log. Each file carries a schema-version header line
so a reader can version-route where it already reads.

On top of that sits `EvalSubject`, a source-neutral unit of recorded behavior that
[Agent Judge](https://lab.pollack.ai/projects/agent-judge) and other evaluators consume,
plus a human-feedback API for judge calibration and golden datasets.

## Modules

| Module | Description | Java | Key dependency |
|---|---|---|---|
| `journal-core` | Experiment/Run tracking, the extensible event model (an open `JournalEvent` interface with runtime subtype registration via `Journal.registerEventType`; only the `GitEvent` family is sealed), storage, cost and token aggregation, `EvalSubject` extraction, feedback, and the portable `TraceWriter` | 17 | Jackson only |
| `claude-code-capture` | Claude Code SDK → journal bridge: phase capture, session parsing, per-turn usage, step cost attribution | 21 | `claude-code-sdk` |
| `gemini-cli-capture` | Gemini CLI → journal bridge: a parallel vendor extractor emitting the same portable trace and cost schema | 21 | `gemini-cli-sdk` |
| `grok-cli-capture` | Grok CLI → journal bridge: parses Grok's `streaming-json` output into an ordered tool trajectory with the session's reported cost | 17 | `journal-core` |
| `codex-cli-capture` | Codex CLI → journal bridge: parses the durable rollout file, classifying each tool call from its payload rather than the outer `exec` name | 17 | `journal-core` |
| `antigravity-cli-capture` | Antigravity CLI → journal bridge: parses Antigravity's streaming JSON into an ordered tool trajectory | 17 | `journal-core` |
| `junie-cli-capture` | Junie CLI → journal bridge: parses Junie's durable session `events.jsonl` into an ordered tool trajectory with per-call cost that reconciles to the session total | 17 | `journal-core` |

`journal-core` targets Java 17 and depends on nothing but Jackson. The Claude Code and
Gemini capture modules bind vendor SDKs published as Java 21 bytecode and therefore
require a Java 21 runtime. The Grok, Codex, Antigravity and Junie capture modules parse
their CLI's own output directly, carry no vendor SDK, and target Java 17 like
`journal-core`.

## What each capture module records

Every module turns one agent call into the same journal shape: a run with a prompt event,
one `llm_call` with the call's token usage and cost, one `tool_call` per tool use with the
tool's own input map, and a `step_cost` per step in the analysis log. The differences are in
what each CLI reports.

| Provider | Source read | Tool trajectory | Cost | Sub-agents |
|---|---|---|---|---|
| Claude Code | the CLI's `stream-json` output, through `claude-code-sdk` | yes, with per-turn usage and the stable `tool_use` id as step id | reported by the CLI, split per step | **captured as linked runs** (1.11.0) |
| Codex | the durable rollout file(s) | yes; tool calls classified from their payload, `function_call` arguments kept verbatim | not reported by Codex (`costAvailable=false`) | **captured as linked runs** (1.11.0) |
| Gemini CLI | the SDK's turn-level result | turn level only | reported | no |
| Grok CLI | `streaming-json` output | yes | reported | no |
| Antigravity | streaming JSON | yes | per step where reported | no (the stream carries pointers only) |
| Junie | the session's `events.jsonl` | yes, with per-call cost reconciling to the session total | reported | no |

Each file written by the library carries a schema-version header and, since 1.11.0, the
producer version, so a reader can tell which release wrote a record.

### Sub-agent capture (Claude Code and Codex)

When the agent starts sub-agents, each sub-agent becomes a **run of its own** in the same
experiment: `parentRunId` points at the run of the agent that started it, the tag
`track=subagent` marks it, and `config["subagent.spawnToolUseId"]` holds the id of the
spawning tool call, which is also the `id` of that `tool_call` event in the parent run.
The parent's files hold the parent's own activity only; the parent's `llm_call` metadata
lists the sub-agent runs (`subagents`) and the spawns for which no sub-agent record arrived
(`subagentsWithoutTrack`). No record type gains a field for this.

| | Claude Code | Codex |
|---|---|---|
| How the records arrive | on the CLI's own stream, marked with `parent_tool_use_id`; `SessionLogParser.parse` keeps them apart | one rollout file per thread; the caller collects the root and child files (agent-client's harvester does) and passes them as `CodexRollouts` to `CodexSessionParser.parse` |
| Required configuration | start the CLI with `--forward-subagent-text` (`CLIOptions.forwardSubagentText`, `claude-code-sdk` ≥ 1.6.0) for complete capture. Without it a sub-agent's prompt, tool calls, results and tool-turn usage are captured, but not its text, thinking, or text-only turns and their usage | collect the child rollouts; the stream parser never searches the filesystem |
| Accounting | the call's reported cost includes its sub-agents and stays on the parent (`costIncludesSubagents=true`); a sub-agent's run has cost 0 with `costAvailable=false`. With the flag, input and cache tokens over the parent and its sub-agent runs equal the call's `modelUsage`. A sub-agent's output count is a lower bound | no cost for any thread (`costAvailable=false`). Each run carries its own thread's input, cached-input, output and reasoning counts; Codex's parent total excludes its children, so each track is counted once |
| Status | the status Claude Code reported (`completed`, `failed`, `killed`), else `CRASHED` ("no end observed") | the last observed turn outcome (Codex writes no thread-terminal status): `completed` → `FINISHED`, `interrupted` → `FAILED`, else `CRASHED`; `subagent.statusMeaning=last_observed_turn` |
| Recording once | `RunRecorder.recordOnce(experimentId, capture, …)` | `CodexRunRecorder.recordOnce(experimentId, capture, …)` |
| Verified | CLI 2.1.292, `claude-code-sdk` 1.7.0, depth 2 | CLI 0.160.1, headless `codex exec`, depth 1; rollouts from 0.147.0 parse |
| Not supported | transcript-file import; resume (not claimed) | `codex exec resume`; `codex app-server`; historical-session import |

`recordOnce` records an execution at most once per experiment and storage root: the parent
run carries the execution's key (`capture.sourceKey`), the recorder checks the stored run
records before writing, the same source processed again writes nothing, and an earlier
recording that has not ended, or ended with an error, makes the call fail rather than
duplicate. It is not a lock between concurrent writers. `recordPhase` on a run you create
yourself is unchanged and unguarded.

### Multi-prompt Claude Code sessions

Claude Code reports `total_cost_usd`, `modelUsage` and `duration_api_ms` on every result
line as the session's running total, while `usage`, `duration_ms` and `num_turns` cover the
query alone. A caller that records one phase per prompt in one session passes the previous
capture to `SessionLogParser.withSessionBaseline(current, previous)`; the returned capture
carries the query's own cost, keeps the running total in `sessionCost()`, and is recorded
with `costBasis=session_delta`. `parse` itself is unchanged.

### Reading the files from another language

A run directory is `experiments/<experiment>/runs/<run>/{run.json, events.jsonl,
analysis.jsonl}`. A reader that treats every run directory as one item of an experiment
should exclude directories whose `run.json` has `tags.track == "subagent"`, and only
those; `parentRunId` alone is not a sub-agent marker, since ordinary nested runs may set it.

## Usage

```xml
<dependency>
    <groupId>io.github.markpollack</groupId>
    <artifactId>journal-core</artifactId>
    <version>1.11.0</version>
</dependency>
```

```java
import io.github.markpollack.journal.Journal;
import io.github.markpollack.journal.Run;
import io.github.markpollack.journal.event.CostBreakdown;
import io.github.markpollack.journal.event.LLMCallEvent;
import io.github.markpollack.journal.event.TokenUsage;
import io.github.markpollack.journal.event.ToolCallEvent;
import io.github.markpollack.journal.storage.JsonFileStorage;

import java.nio.file.Path;
import java.util.Map;

Journal.configure(new JsonFileStorage(Path.of(".agent-journal")));

try (Run run = Journal.run("implement-feature")
        .task("issue-123")
        .agent("claude-sdk-sync")
        .config("model", "claude-opus-4-5")
        .start()) {

    run.logEvent(LLMCallEvent.builder()
            .model("claude-opus-4-5")
            .tokenUsage(TokenUsage.of(1200, 450, 300))
            .cost(CostBreakdown.of(0.015, 0.030))
            .build());

    run.logEvent(ToolCallEvent.success(
            "Bash", Map.of("command", "git status"), "clean", 250));

    run.setSummary("success", true);
    run.setSummary("filesChanged", 3);
}
```

Members of the [AgentWorks](https://lab.pollack.ai/projects) suite should import
`agentworks-bom` rather than pinning these versions individually.

## Build

Requires a Java 21 JDK (`.sdkmanrc` pins one); `journal-core` itself still compiles to a
Java 17 target.

```bash
./mvnw clean verify
```

Verify that `journal-core` and the two SDK-bound capture modules (Claude Code, Gemini)
resolve safe Jackson versions for standalone consumers without importing `agentworks-bom`:

```bash
./scripts/check-consumer-resolution.py
```

Local vulnerability scanning is a documented local path, not a CI job:

```bash
./mvnw -Powasp verify -DskipTests -Downed.cvss.threshold=11   # full inventory, never fails
./mvnw -Powasp verify -DskipTests                             # gate, fails on CVSS >= 7.0
./scripts/security-scan.sh                                    # Trivy cross-check
```

## Maturity

Stable and in production use across the AgentWorks suite; `agent-workflow`,
`agent-experiment` and `agent-client` all consume it. The capture contract evolves
additively — a consumer built against 1.5.0 keeps working on 1.11.0, and readers from
1.11.0 ignore fields they do not know. The schema version of a stream changes when a file
could be misread by a reader of the previous format (a key renamed, removed or re-used, a
type or unit changed, a stored enum given a value); a corrected value under an unchanged
definition is told apart by the producer version in the header instead. Each release's
notes list which is which.

## License

[Business Source License 1.1](LICENSE) — see the root `LICENSE` file for the Licensor,
Additional Use Grant, Change Date and Change License that apply to this project.

Agent Journal has been distributed under BSL 1.1 for its entire published history: the
first release, 0.9.0 (2026-03-29), and every release since carry these terms. No version
of this project was ever published under the Apache License 2.0, so there is no earlier
Apache grant to preserve and no historical license copy to retain. The Maven Wrapper
files (`mvnw`, `mvnw.cmd`, `.mvn/`) are third-party Apache 2.0 material and keep their
own notices.
