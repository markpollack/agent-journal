# Journal-owned native source specifications (non-production prototype)

Journal owns the description of each native source it supports, per CLI, version, launch mode and capture route.
Each specification here is an **accepted, versioned inventory** of the vendor record variants, paths and value kinds
observed on frozen complete sources, with its provenance and explicit limits. It is produced by the independent
raw-record inventory in `journal-capture-tck` (`io.github.markpollack.journal.tck.ocsf.inventory`), which reads the
native bytes with the neutral JSON model and never a typed parser, mapper or SDK, and it is changed only by review:
a drift report proposes updates; nothing widens the accepted inventory automatically.

| File | Profile | Applies to | Observed on |
|---|---|---|---|
| `claude-code/stream-json/2.1.303.json` | `claude_stream` | Claude Code 2.1.303, `claude -p --output-format stream-json`, SDK-delivered `rawJson` lines | the frozen pair A stream (39 records) |
| `claude-code/session-transcript/2.1.303.json` | `claude_transcript` | Claude Code 2.1.303, the session's `~/.claude/projects/.../<session>.jsonl` after the run | the frozen pair A transcript (56 records) |
| `codex-cli/exec-json-stream/0.160.1.json` | `codex_stream` | Codex CLI 0.160.1, `codex exec --json` stdout | the frozen pair B stream (15 records) |
| `codex-cli/rollout/0.160.1.json` | `codex_rollout` | Codex CLI 0.160.1, the `~/.codex/sessions/.../rollout-*.jsonl` after the run | the frozen pair B rollout (26 records) |
| `antigravity-cli/stream-json/1.4.6.json` | `agy_stream` | Antigravity CLI 1.4.6, `agy --print <prompt> --output-format stream-json` stdout | the four sanitized live 1.4.6 recordings (54 records) |

Beside each specification, `<version>.schema.json` is the JSON Schema (Draft 2020-12 subset) **derived** from it:
one object schema per variant, `required` for paths present in every observed record, `additionalProperties: false`
except under data-keyed and map-keyed paths. It is a shape description for readers, regenerated with
`NativeInventoryTool schema`, never hand-edited, and never the thing drift is compared to: the comparison uses the
inventory, so a new field is discovered even where a schema would be permissive.

## What a specification states

- `applies_to`: CLI, version, launch mode, source kind and capture route. The route limit is stated: the Claude stream
  is the SDK-delivered surface and cannot prove what the unfiltered stdout contained.
- `provenance`: the frozen artifacts (name, sha256, records) the inventory was observed on, the reviewer and date.
- `inventory.variants`: per variant (discriminated by the vendor's own fields, for example `type`, `attachment.type`,
  `payload.type`/`payload.role`/`payload.item.type`), the record count, the evidence record indexes, and per path the
  observed kinds (`object`, `array`, `string`, `integer`, `decimal`, `boolean`, `null`), presence and absence counts,
  null and empty-container counts and evidence indexes. Paths use `.` for members, `[]` for array elements and `{*}`
  for keys that are data.
- `inventory.data_keyed`: subtrees whose keys and values are tool or user data (tool inputs, tool-defined results,
  tool schemas); only the value kinds under `{*}` are recorded. `inventory.map_keyed`: maps keyed by data (model names)
  whose values have vendor structure, inventoried below `{*}`.
- `external_cross_checks`: vendor or community descriptions compared with the same inventory, as separate evidence.
  They inform interpretation and missing-scenario tests; they do not filter the inventory or define support.
- `limits`: what one specimen cannot establish. A missing optional field is not removal; a field seen once is not
  universal; one version and one launch mode per CLI.

## Commands (from the project root, after `./mvnw -o -pl journal-capture-tck install -DskipTests`)

```
java -cp journal-capture-tck/target/classes:<deps> io.github.markpollack.journal.tck.ocsf.inventory.NativeInventoryTool \
    inventory <profile> <artifact> [out.json]                      # the empirical inventory
    drift <spec.json> <artifact> [report.json]                      # proposals and notes against the accepted specification; exit 5 on shape drift
    external <schema.json> <discriminator> <profile> <artifact> [report.json]   # cross-check against an external JSON Schema
    schema <spec.json> [out.json]                                   # the derived JSON Schema
    draft <profile> <specification-version> <out.json> <artifact>...  # a DRAFT specification from inventories
```

A drift report's `proposed_specification_inventory` is a proposal for review. Accepting it means editing the
specification here, bumping `specification_version`, recording the new provenance and regenerating the derived
schema. A change of meaning under an unchanged shape is invisible to this inventory; the semantic oracle in
`journal-capture-tck` (profiles, `OcsfOracle`) judges values, and `InventoryDriftTest` demonstrates both halves.
