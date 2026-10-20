# ACP/OCSF worked-example prototype (non-production)

This directory is an isolated prototype. It is not a Maven module, it is not wired into the build,
and nothing in it is a production format, adapter or reader. It exists to make the Agentic CLI
extension design concrete and testable before any production code changes.

What it contains:

- `extension/agentic_cli/` — an OCSF extension authored in OCSF's own definition format: one
  category (`agent_execution`), four event classes (`prompt_turn`, `model_response`, `tool_call`,
  `source_record`), a `cli_agent` object extending the core `ai_agent`, and the supporting objects
  (`source_ref`, `content_block`, `tool_result`, `operation`, `attribution`, `permission_decision`,
  `usage_observation`, `cost_observation`, `model_ref`, `journal_provenance`). The extension uid 901
  is a provisional, unregistered prototype assignment.
- `mappings/` — one mapping contract per CLI/version/source, as data: rule ids, selectors, owners,
  derivations, attribution join keys, residual policy and what is unavailable at source.
- `specs/` — run specs naming the fixture artifacts (held privately in the steward), capture start,
  launch settings and pins. `S1`–`S3` are labelled synthetic variants.
- `expected/` — hand-labelled expected query answers citing native record indexes and the reviewed
  meaning; written from the native files, not generated from the contracts or the adapter.
- `scripts/` — `compile.sh` (pinned compiler), `adapt.py` (contract executor), `validate.sh`
  (pinned toolkit), `semantic_check.py` (independent byte-reading oracle), `queries.py` (one query
  definition applied to every package), `reader_check.py` (provisional-uid pin and refusal),
  `measure.py` (bytes), `make_negatives.py` (synthetic fixtures and mutations), `run_all.sh`.
- `toolchain.lock.json` — exact pins and checksums.

Reproduce: `scripts/run_all.sh <work-dir> <steward-plans-dir>`. Requires `uv`, `curl`, `git` and
network access for the pinned fetches; CPython 3.14 is installed by `uv`; Go is not needed because
the toolkit release binary is used and checksum-verified.

The fixtures and the generated packages contain private capture content and live in the steward
repository. Evidence, decisions and the review packet are recorded there.
