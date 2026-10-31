# Agent Journal

Agent Journal is a Java library for recording agent executions as durable local data.
It records the messages, tool activity, reported usage and cost of a run as one independently
portable `events.jsonl` on the [OCSF](https://ocsf.io) schema with Journal's `agentic_cli`
extension, and checks that record against the native input with an independent oracle.

See the [documentation](https://lab.pollack.ai/projects/agent-journal) for concepts, capture guides,
configuration and API reference. Generated architecture views are under
[`docs/architecture`](docs/architecture/README.md) and per-CLI support statements under
[`docs/cli-support`](docs/cli-support).

## Build

Requires a Java 21 JDK.

```bash
./mvnw clean verify
```

This branch builds `2.0.0-SNAPSHOT`, a development snapshot of the OCSF writer
(`journal-ocsf-writer`), its reader and oracle (`journal-capture-tck`) and the launching capture.
The latest release is `1.11.0`:

```xml
<dependency>
    <groupId>io.github.markpollack</groupId>
    <artifactId>journal-core</artifactId>
    <version>1.11.0</version>
</dependency>
```

## Support in 2.0.0-SNAPSHOT

Support is per exact CLI version and launch route, as recorded in the compatibility registry
(`journal-ocsf-writer/src/main/resources/ocsf/compatibility.json`). Input from any other version or
route is retained whole and not normalized; newer CLI versions than those listed are not claimed.

Admitted (raw standard output of the CLI, launched and read by `CliCapture`):

- Claude Code 2.1.304, `stream-json` output
- Codex CLI 0.160.1, `exec` JSON stream
- Antigravity CLI 1.4.6, headless `stream-json` output

Experimental (native session files, OTel and usage-report files, and the multi-source assembly
profiles that combine them): Claude Code 2.1.299, 2.1.303 and 2.1.304 session transcripts;
GitHub Copilot CLI 1.0.100 stdout events, session events, OTel file and usage report; Junie 3813.7
stdout, session events and session summary; Codex CLI 0.160.1 rollout files. The Claude Code
2.1.299 and 2.1.303 SDK route is historical: its recordings replay, the route is not extended.

Admission means the route's normalization is exercised on recorded live input and checked by the
independent oracle. It is not approval of every meaning: meanings the catalogs still mark proposed
stay proposed, and the output states what the source did not establish rather than guessing it.
Known limits of this snapshot: Copilot's per-response duration and Junie's per-call time carry no
vendor-stated unit; Junie's cache counters are reported as zero without establishing whether they
are measured, so its uncached input is conditional; the legacy Claude capture does not yet detect an
incomplete sub-agent interior stream (its check is disabled).

The 1.x `journal-core` record format and the per-CLI `<cli>-capture` modules remain in this build
until they are explicitly retired; they are not the model for new work.

## License

[Business Source License 1.1](LICENSE)
