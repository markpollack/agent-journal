# Frozen acquisition and canonical consumption

The writer accepts immutable acquired files through
`FinalAssembly.assemble(Path acquisitionManifest, Path newOutputDirectory)` or
`io.github.markpollack.journal.ocsf.writer.OcsfWriterTool assemble acquisition.json output`.
This runs no process, tool or callback. The output must not exist.

The manifest has `kind: agent-journal/acquired-execution`, `version: 0.1`, the bundled
assembly resource key in `profile`, its declared `profile_id`, `acquisition_uid`, explicit
`complete`, existing RunDescriptor JSON in `run`, caller `process` outcome, and `sources`.
The run includes `run_uid`, `invocation_index`, `capture_start`, `cli`, optional `session_uid`
and `launch_settings`. Caller prompt text belongs in `launch_settings.caller_input` with
explicit caller provenance. Extra acquisition metadata, including turn/offset observations,
is preserved as caller-supplied launch context in the portable file.

Every source names `artifact`, `state` (`acquired` or `missing`) and explicit `required`.
Missing sources state `reason`; acquired sources state relative `path`, `origin`,
`acquisition`, `sha256`, `bytes`, and `end` (`EOF` or `INTERRUPTED`). Assembly inputs additionally
name `role`, `profile`, `route`, `framing` and `records`; these must match the selected profile.
`copy_of` declares an actual copy lineage. `archive_only: true` checks the original bytes and
records their metadata without interpreting them or adding them to the canonical denominator.
Original files stay at the caller's immutable acquisition location; the output does not copy
archive-only files. Paths cannot escape the manifest directory, including through symlinks.
A wrong hash, extent or binding is rejected before opening output. Missing required acquisition
or interruption prevents complete capture; a nonzero process exit with complete acquisition
remains separate from storage finalization and domain task success.

Existing bundled Claude assembly is `claude-code-2.1.304-stdout-session-file`, whose ID is
`claude-code/2.1.304/stdout+session-file`. It covers exactly its recorded stdout/native-session
pair. SDK byte routes and other versions need their own qualification, not a relabelled manifest.
The programmatic typed FinalAssembly API remains available.

The packaged reader class is `io.github.markpollack.journal.tck.ocsf.OcsfReaderTool`:

```text
inspect events.jsonl
canonical events.jsonl canonical.jsonl
verify output acquisition-dir report.json --acquisition acquisition-dir/acquisition.json --assembly assembly-expectation.json
```

Combined verification independently reads acquired bytes and their frozen metadata, then calls
`AssemblyOracle.verifyAll`. The native directory must be the acquisition manifest directory.
`assembly-expectation.json` is an independently authored existing assembly-expectation document,
not the writer's profile. Per-source `binding` and optional manifest `children` use the existing
expected-sources shapes. Each selected source needs its exact reviewed binding (entry_id, version,
route, source_specification and specification_sha256) for acceptance; absent bindings remain incomplete. The old `verify ... --expected expected-sources.json` remains source-only;
it does not accept a combined execution without the assembly checks. Exit codes are 0 accepted,
2 refused, 3 defective, 4 incomplete and 64 usage. An export or an inspect success is not verification.

`CanonicalExport.write(OcsfPackage, Writer)` uses `PackageReader.open` and canonical events from
each available linked core. Its JSONL envelope version 0.1 contains a `agent-journal/canonical-core`
row (run context, recording/acquisition status, child links, diagnostics, combination and assembly)
then `agent-journal/canonical-event` rows with `core`, `run_uid`, symbolic `class`, `event` and
`source_observations`. The event retains all common fields, complete arguments/results/content,
usage/cost scopes and source references. Supporting observations resolve within their owning core
and are nested evidence, not additional rows. No optional views contract or native files are needed.
Child files must be supplied for their content; missing links remain diagnostic, never fabricated.

Consumers use `event.tool_call_uid`, `event.tool_name`, `event.arguments`, `event.tool_result`, `event.content_blocks`,
`event.journal.sequence`, `event.response_uid`, `event.session_uid`, `event.usage_observations[]`
and `event.cost_observations[]` as
saved. Request/result/update stages are observations of a call, not extra calls. Core/run/invocation
and source-scoped identity prevent concatenating trajectories. Do not sum overlapping usage windows
or turn absent cost into zero or measured per-tool cost. This is a lossless export of the common
model, not a classifier or a new persisted execution format.

Each usage observation retains `scope_id`/`scope`, `usage_status_id`/`usage_status`,
`aggregation_id`/`aggregation`, `model_ref`, reported token components, component bases and
`source_ref`. Each cost observation retains `total_cost`, `currency`, `quantity_kind_id`, scope,
aggregation, `cost_basis`, `cost_basis_window` and its own `source_ref` when reported. Read these
fields per observation; a final model response and a cumulative invocation total may overlap.
