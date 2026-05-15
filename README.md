# agent-journal

Execution ledger for agent workflows — structured Experiment/Run tracking, not observability.

## Coordinates

```xml
<dependency>
    <groupId>io.github.markpollack</groupId>
    <artifactId>journal-core</artifactId>
    <version>0.9.0</version>
</dependency>
```

## Quick Example

```java
try (Run run = Journal.run("implement-feature")
        .task("issue-123")
        .agent("claude-sdk-sync")
        .config(Config.of("model", "claude-opus-4.5"))
        .start()) {

    run.logEvent(LLMCallEvent.builder()
        .model("claude-opus-4.5")
        .tokens(TokenUsage.of(1200, 450, 300))
        .cost(CostBreakdown.of(0.015, 0.030, 0.008))
        .build());

    run.logEvent(ToolCallEvent.of("Bash", "git status", 250, true));

    run.summary()
        .set("success", true)
        .set("totalCostUsd", 0.053);
}
```

## Modules

| Module | Description |
|--------|-------------|
| `journal-core` | Experiment/Run tracking API, events, cost/token aggregation |
| `claude-code-capture` | Captures Claude Code execution traces into journal runs. Depends on `io.github.markpollack:claude-code-sdk` |

## Build

```bash
./mvnw compile
./mvnw test        # 279 tests
./mvnw install
```

## License

Business Source License 1.1
