# Agent Journal

Agent Journal records an agent CLI's execution as durable local data: the messages, tool
activity, reported usage and cost of a run, written as one independently portable `events.jsonl`
on the [OCSF](https://ocsf.io) schema with Journal's `agentic_cli` extension, and checked against
the native input by an independent oracle.

See the [documentation](https://lab.pollack.ai/projects/agent-journal) for concepts, capture guides,
configuration and API reference. In this repository: generated architecture views under
[`docs/architecture`](docs/architecture/README.md), per-CLI support statements under
[`docs/cli-support`](docs/cli-support), and [how canonical conformance is checked](docs/canonical-conformance.md).

## Build

Requires a Java 21 JDK.

```bash
./mvnw clean verify
```

## Use

Two artifacts, group `io.github.markpollack`, version `2.0.0-SNAPSHOT`:

- `journal-ocsf-writer`: captures a CLI (launching it, reading its standard output, or replaying a
  recording) and writes the run package; `OcsfWriterTool launch|capture|replay` is its command line,
  `CliCapture` and `OcsfRunWriter` its API. It depends on the JDK and `jackson-core` only.
- `journal-capture-tck`: reads a package (`PackageReader`), computes the declared analysis views, and
  judges a package against its native input with the independent oracle; `OcsfReaderTool
  inspect|views|measure|reconstruct|self-contained|verify` is its command line. Consumers that only
  read packages need this artifact alone.

```xml
<dependency>
    <groupId>io.github.markpollack</groupId>
    <artifactId>journal-ocsf-writer</artifactId>
    <version>2.0.0-SNAPSHOT</version>
</dependency>
```

Replay an existing recording and verify the result:

```bash
java -cp journal-ocsf-writer.jar:jackson-core.jar io.github.markpollack.journal.ocsf.writer.OcsfWriterTool \
  replay <spec.json> <out-dir>          # the spec names the run and its native source files
java -cp journal-capture-tck.jar:<its dependencies> io.github.markpollack.journal.tck.ocsf.OcsfReaderTool \
  verify <out-dir> <native-dir> report.json --expected <native-dir>/expected-sources.json
```

The 1.x line (`journal-core`, last release `1.11.0`) is a different record format with no upgrade
path to 2.0; it lives on branch `1.x`.

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
are measured, so its uncached input is conditional; a Claude foreground sub-agent stream that omits
one of its messages is not yet detected as incomplete against the vendor's own counts.

## License

[Business Source License 1.1](LICENSE)
