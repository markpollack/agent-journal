# Agent Journal

Agent Journal records an agent CLI's execution as durable local data: the messages, tool
activity, reported usage and cost of a run, written as one independently portable `events.jsonl`
on the [OCSF](https://ocsf.io) schema with Journal's `agentic_cli` extension, and checked against
the native input by an independent oracle.

The [documentation site](https://lab.pollack.ai/projects/agent-journal) covers this branch under
its "Agent Journal 2.0 (on main, prototype)" group; its "Agent Journal 1.x (released)" group
describes the 1.x line (last release `1.11.0`), which is a different format and does not apply here.
In this repository: generated architecture views under
[`docs/architecture`](docs/architecture/README.md) and per-CLI support statements under
[`docs/cli-support`](docs/cli-support).

## Build

Requires a Java 21 JDK. The published artifacts target Java 17.

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

Snapshots are published on every push to `main` to the Central snapshot repository; name the
`main` commit you checked against, since snapshot content changes as `main` moves:

```xml
<repository>
    <id>central-snapshots</id>
    <url>https://central.sonatype.com/repository/maven-snapshots/</url>
    <releases><enabled>false</enabled></releases>
    <snapshots><enabled>true</enabled><updatePolicy>always</updatePolicy></snapshots>
</repository>
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

Support is per CLI and launch route, as recorded in the compatibility registry
(`journal-ocsf-writer/src/main/resources/ocsf/compatibility.json`). Most entries name one exact CLI
version. The Claude Code SDK route (stdio stream and session file) and the Codex CLI exec route
(JSON stream and rollout) are version-neutral: any CLI version selects the entry, and the observed
version is recorded, not gated. An exact entry wins over a version-neutral one. Input that matches no
entry is retained whole and not normalized.

Admitted (raw standard output of the CLI, launched and read by `CliCapture`):

- Claude Code 2.1.304, `stream-json` output
- Codex CLI 0.160.1, `exec` JSON stream
- Antigravity CLI 1.4.6, headless `stream-json` output

Experimental (native session files, OTel and usage-report files, and the multi-source assembly
profiles that combine them): Claude Code 2.1.299, 2.1.303 and 2.1.304 session transcripts;
GitHub Copilot CLI 1.0.100 stdout events, session events, OTel file and usage report; Junie 3813.7
stdout, session events and session summary; Codex CLI 0.160.1 rollout files; the version-neutral
Claude Code SDK stdio and session-file route (tested on 2.1.316 and 2.1.319); the version-neutral
Codex CLI exec stream and rollout route (tested on 0.166.0). The Claude Code 2.1.299 and 2.1.303 SDK
route is historical: its recordings replay, the route is not extended.

Admission means the route's normalization is exercised on recorded live input and checked by the
independent oracle. It is not approval of every meaning: meanings the catalogs still mark proposed
stay proposed, and the output states what the source did not establish rather than guessing it.
Known limits of this snapshot: Copilot's per-response duration and Junie's per-call time carry no
vendor-stated unit; Junie's cache counters are reported as zero without establishing whether they
are measured, so its uncached input is conditional; a Claude foreground sub-agent stream that omits
one of its messages is not yet detected as incomplete against the vendor's own counts.

The experimental fresh Codex one-shot profile uses original UTF-8 stdin, separate raw
stdout/stderr, and the exact native rollout. `CodexAcquisition.qualify(sealedManifest, newDirectory)`
in the TCK produces immutable assembly inputs after checking native N/T, original hashes/extents,
observed closure and matching cumulative terminal usage. Select
`TerminalReceiver.CODEX_PROFILE` (`client-terminal-codex-exec/1`) in the initial envelope; the Claude
Code one-shot profile is `TerminalReceiver.CLAUDE_PROFILE` (`client-terminal-claude-sdk/1`). The
earlier versioned names (`LEGACY_CODEX_PROFILE` = `client-terminal-codex-0.166.0/1`,
`LEGACY_CLAUDE_PROFILE` = `client-terminal-claude-2.1.316/1`, and the `LEGACY_*` retained names) stay
readable for recordings of that one version.
An already qualified acquisition supports an initially complete one-shot association; pending initial
acquisition can instead receive an explicit final revision. Both preserve the original accepted
Client decision and exact retry ACK. Qualification/recording belongs on an independent optional lane.
A producer need not name the CLI: `AcquisitionQualifier.forProfile(profile)` returns the qualifier for
the sealed manifest's `profile`, whose `terminalProfile()` is the envelope profile to select. It
resolves the Claude Code (`claude-code-<version>-sdk-stdio-session-file`) and Codex CLI
(`codex-cli-<version>-exec-stdio-rollout`) one-shot profiles at any version; see
[the terminal receiver](docs/terminal-receiver.md#qualifier-lookup).

See [the acquisition contract](docs/codex-acquisition.md). Deterministic synthetic tests establish
the acquisition boundary and the observed command/declaration/settings/reasoning mappings. The current
source-derived onboarding report keeps all ten functional areas visible and labels partial, unexercised,
unsupported and source-absent slices. It does not establish whole-route, provider or product qualification. Unsupported
native meanings remain unresolved, and missing observations never become zero or complete.


## License

[Business Source License 1.1](LICENSE)
