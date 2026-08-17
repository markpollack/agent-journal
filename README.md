# agent-journal

An execution ledger for agent workflows. Agent Journal records what an agent run
actually did — every LLM call, tool invocation, state transition and cost — as typed
events in an append-only log, so runs become data you can compare, replay and judge.
It is a ledger, not an observability agent: nothing is sampled, nothing is aggregated
away, and the file on disk is the record.

**📖 Documentation: [lab.pollack.ai/projects/agent-journal](https://lab.pollack.ai/projects/agent-journal)**

## Concepts

An **Experiment** groups **Runs**. A Run is one attempt at a task; it opens, emits
events, carries a Config in and a Summary out, and closes as `FINISHED` or `FAILED`.

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
| `journal-core` | Experiment/Run tracking, the sealed event hierarchy, storage, cost and token aggregation, `EvalSubject` extraction, feedback, and the portable `TraceWriter` | 17 | Jackson only |
| `claude-code-capture` | Claude Code SDK → journal bridge: phase capture, session parsing, per-turn usage, step cost attribution | 21 | `claude-code-sdk` |
| `gemini-cli-capture` | Gemini CLI → journal bridge: a parallel vendor extractor emitting the same portable trace and cost schema | 21 | `gemini-cli-sdk` |

`journal-core` targets Java 17 and depends on nothing but Jackson. The two capture
modules bind vendor SDKs published as Java 21 bytecode and therefore require a Java 21
runtime.

## Usage

```xml
<dependency>
    <groupId>io.github.markpollack</groupId>
    <artifactId>journal-core</artifactId>
    <version>1.6.0</version>
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

Local vulnerability scanning is a documented local path, not a CI job:

```bash
./mvnw -Powasp verify -DskipTests -Downed.cvss.threshold=11   # full inventory, never fails
./mvnw -Powasp verify -DskipTests                             # gate, fails on CVSS >= 7.0
./scripts/security-scan.sh                                    # Trivy cross-check
```

## Maturity

Stable and in production use across the AgentWorks suite; `agent-workflow`,
`agent-experiment` and `agent-client` all consume it. The capture contract is frozen and
evolves additively — a consumer built against 1.5.0 keeps working on 1.6.0. Breaking
changes, when they come, arrive with a schema-version bump on the affected stream.

## License

[Business Source License 1.1](LICENSE) — see the root `LICENSE` file for the Licensor,
Additional Use Grant, Change Date and Change License that apply to this project.

Agent Journal has been distributed under BSL 1.1 for its entire published history: the
first release, 0.9.0 (2026-03-29), and every release since carry these terms. No version
of this project was ever published under the Apache License 2.0, so there is no earlier
Apache grant to preserve and no historical license copy to retain. The Maven Wrapper
files (`mvnw`, `mvnw.cmd`, `.mvn/`) are third-party Apache 2.0 material and keep their
own notices.
