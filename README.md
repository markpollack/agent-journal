# Agent Journal

Agent Journal is a Java library for recording agent executions as durable local data.
It records messages, tool activity and reported usage so runs can be inspected, compared and evaluated.

See the [documentation](https://lab.pollack.ai/projects/agent-journal) for concepts, capture guides,
configuration and API reference.

## Maven

Released API:

```xml
<dependency>
    <groupId>io.github.markpollack</groupId>
    <artifactId>journal-core</artifactId>
    <version>1.11.0</version>
</dependency>
```

## Build

Requires a Java 21 JDK.

```bash
./mvnw clean verify
```

The OCSF writer and multi-source assembly on this development branch are opt-in prototypes;
they do not replace the released 1.11.0 format.

## License

[Business Source License 1.1](LICENSE)
