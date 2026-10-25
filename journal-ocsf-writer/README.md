# journal-ocsf-writer (non-production prototype)

The incremental Java writer of Agent Journal's OCSF-based run package (layout `0.2.0-prototype`, extension
`agentic_cli` 0.3.0-prototype, provisional unregistered uid 901). It is the one implementation behind both recorded
replay and the capture-facing input interface; it is not wired into any production recorder and no production default
uses it.

## What it does

- Accepts a declared source (`SourceDescriptor`) and feeds it through `SourceInput`: arbitrary byte chunks
  (`bytes`), or one complete native record at a time (`record`), each with the arrival time the caller observed, then
  `end(EOF|INTERRUPTED)`.
- Frames bytes into lines (`LineFramer`), classifies each line before interpretation (`NativeRecord`: parsed,
  malformed, undecodable, blank, incomplete-at-source-end), hashes the artifact as it arrives, and maps each record as
  it arrives (`ClaudeStreamMapper`, `ClaudeTranscriptMapper`, `CodexRolloutMapper`, `CodexStreamMapper`,
  `OpaqueTextMapper`) into OCSF-extension events or whole-record fallback, under the mapping contracts bundled in
  `src/main/resources/ocsf/mappings/`.
- Appends events to `events.jsonl` and replaces `run.json` atomically at every `flush()`; a reader opening the
  directory while the writer is open sees a consistent package in state `RECORDING` (no final hash, provisional
  counts). `complete()` finalizes (`COMPLETED`), `interrupt()` ends open sources and finalizes (`INTERRUPTED`), and a
  persistence failure leaves a `FAILED` manifest naming the confirmed persisted boundary. Finalization is idempotent.
- Routes records whose owner path (`parent_tool_use_id`) names a tool call to a child core under
  `children/<id>/`, each core independently portable with its own definition closure.
- Combines several sources of one execution only by final assembly (`OcsfRunWriter.openAssembly`, `FinalAssembly`
  with a reviewed profile), whose identity gate decides which sources belong together. A writer from
  `OcsfRunWriter.open` records one source and refuses a second; `OcsfRunWriter.openEvidenceLog` records several
  without assembly and declares the file an unvalidated evidence log in every core, which readers and the oracle
  never accept as one combined execution. A later source (a saved session file) is admitted through the same
  mechanisms; earlier persisted bytes never change.

`OcsfWriterTool replay <spec.json> <out-dir> [--chunk N] [--flush-every N] [--base DIR] [--stop-after ARTIFACT:N] [--evidence-log REASON]`
replays recorded artifacts (a spec with several sources needs `--evidence-log`); `SchemaExcerpt` regenerates the
bundled compiled-schema excerpt from the pinned compiler's output.

## What judges it

`journal-capture-tck` (reader, oracle, analysis views) reads the files this writer leaves on disk and never calls it.
This module's tests use the TCK at test scope to judge every prefix, the incomplete/interrupted/failed states, child
routing, later admission, and the record-form versus byte-form equivalence.

Mutation testing: `./mvnw -Ppit -pl journal-ocsf-writer test`.
