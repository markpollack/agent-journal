# agent-journal

An execution ledger for agent workflows. Agent Journal records what an agent run
actually did, every LLM call, tool invocation, state transition and cost, as typed
events in an append-only log, so runs become data you can compare, replay and judge.
It is a ledger, not an observability agent: nothing is sampled, nothing is aggregated
away, and the file on disk is the record.

**📖 Documentation: [lab.pollack.ai/projects/agent-journal](https://lab.pollack.ai/projects/agent-journal)**

## Concepts

An **Experiment** groups **Runs**. A Run is one attempt at a task; it opens, emits
events, carries a Config in and a Summary out, and ends as `FINISHED`, `FAILED` or `CRASHED`.

Each run has two streams. `events.jsonl` is the immutable execution log: what happened.
`analysis.jsonl` is the derived analysis log: what was computed about it afterwards (per-step
cost attribution today), linked back by step id and regenerable from the execution log. Both
start with a header line that carries the schema version and, since 1.11.0, the producer and
its version; `feedback.jsonl` has no header. A portable trace file, when a capture writes one,
has its own header and its own schema version (2).

On top of that sits `EvalSubject`, a source-neutral unit of recorded behavior that
[Agent Judge](https://lab.pollack.ai/projects/agent-judge) and other evaluators consume,
plus a human-feedback API for judge calibration and golden datasets.

## Modules

| Module | Description | Java | Key dependency |
|---|---|---|---|
| `journal-core` | Experiment/Run tracking, the extensible event model (an open `JournalEvent` interface with runtime subtype registration via `Journal.registerEventType`; only the `GitEvent` family is sealed), storage, cost and token aggregation, `EvalSubject` extraction, feedback, and the portable `TraceWriter` | 17 | Jackson only |
| `claude-code-capture` | Claude Code SDK → journal bridge: phase capture, session parsing, per-turn usage, step cost attribution, sub-agent runs | 21 | `claude-code-sdk` |
| `gemini-cli-capture` | Gemini CLI → journal bridge: a parallel vendor extractor emitting the same portable trace and cost schema | 21 | `gemini-cli-sdk` |
| `grok-cli-capture` | Grok CLI → journal bridge: parses Grok's `streaming-json` output into an ordered tool trajectory with the session's reported cost | 17 | `journal-core` |
| `codex-cli-capture` | Codex CLI → journal bridge: parses the durable rollout file, classifying each tool call from its payload rather than the outer `exec` name; sub-agent runs from child rollouts | 17 | `journal-core` |
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

| Provider | Source read | Tool trajectory | Monetary cost | Sub-agents |
|---|---|---|---|---|
| Claude Code | the CLI's `stream-json` output, through `claude-code-sdk` | yes, with per-turn usage and the stable `tool_use` id as step id | reported by the CLI for the whole call, split per step | captured as linked runs (1.11.0) |
| Codex | the durable rollout file(s) | yes; tool calls classified from their payload, `function_call` arguments kept verbatim | not reported (`costAvailable=false`); tokens per thread are recorded | captured as linked runs (1.11.0) |
| Gemini CLI | the SDK's turn-level result | turn level only | reported | no |
| Grok CLI | `streaming-json` output | yes | reported as a session total, split evenly over steps | no |
| Antigravity | streaming JSON | yes, with per-step duration and terminal token usage | not reported (`costAvailable=false`) | no (the stream carries pointers only) |
| Junie | the session's `events.jsonl` | yes, with per-call cost reconciling to the session total | reported | no |

A cost of 0 with `costAvailable=false` in the `llm_call` metadata means the CLI reported no
cost; it does not mean the call was free.

### Sub-agent capture (Claude Code and Codex)

When the agent starts sub-agents, each sub-agent becomes a run of its own in the same
experiment: `parentRunId` points at the run of the agent that started it, the tag
`track=subagent` marks it, and `config["subagent.spawnToolUseId"]` holds the id of the
spawning tool call, which is also the `id` of that `tool_call` event in the parent run. The
parent's files hold the parent's own activity only.

- **Claude Code**: sub-agent messages arrive on the CLI's own stream. Start the CLI with
  `--forward-subagent-text` for complete capture; without it a sub-agent's text, thinking and
  text-only turns are missing. The call's cost stays on the parent (`costIncludesSubagents=true`).
- **Codex**: each sub-agent is its own rollout file. The caller collects the root and child
  files and passes them as `CodexRollouts` to `CodexSessionParser.parse`; Journal does not
  search the filesystem. Codex reports no cost for any thread.

`RunRecorder.recordOnce` and `CodexRunRecorder.recordOnce` record an execution at most once
per experiment and storage root. `SessionLogParser.withSessionBaseline` gives a later prompt of
a multi-prompt Claude Code session its own cost instead of the session's running total; the
caller applies it.

The [sub-agent capture guide](https://lab.pollack.ai/docs/agent-journal/subagent-capture) has
the walkthrough, the accounting rules and the limits. The
[agent-client](https://lab.pollack.ai/projects/agent-client) release that applies these on its
own paths is tracked there; as of agent-client 0.31.0 none of them is wired in.

### Reading the files from another language

A run directory is `experiments/<experiment>/runs/<run>/{run.json, events.jsonl,
analysis.jsonl}`. A reader that counts root runs as experiment items excludes directories
whose `run.json` has `tags.track == "subagent"`, and only those; a reader analysing a whole
execution keeps them and joins through `parentRunId`. `parentRunId` alone is not a sub-agent
marker, since ordinary nested runs may set it. See
[Analyze runs](https://lab.pollack.ai/docs/agent-journal/analyzing-runs).

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
additively: every earlier constructor of a capture record is kept, files written by 1.11.0
load in 1.10.1 readers, and readers from 1.11.0 ignore fields they do not know. A record
pattern that destructures `PhaseCapture` or `CodexPhaseCapture` names every component and
needs updating when components are added (four and three in 1.11.0). The schema version of a stream changes when a file
could be misread by a reader of the previous format (a key renamed, removed or re-used, a
type or unit changed, a stored enum given a value); a corrected value under an unchanged
definition is told apart by the producer version in the header instead. Each release's
notes list which is which.

## License

[Business Source License 1.1](LICENSE). See the root `LICENSE` file for the Licensor,
Additional Use Grant, Change Date and Change License that apply to this project.

Agent Journal has been distributed under BSL 1.1 for its entire published history: the
first release, 0.9.0 (2026-03-29), and every release since carry these terms. No version
of this project was ever published under the Apache License 2.0, so there is no earlier
Apache grant to preserve and no historical license copy to retain. The Maven Wrapper
files (`mvnw`, `mvnw.cmd`, `.mvn/`) are third-party Apache 2.0 material and keep their
own notices.
