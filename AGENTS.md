# Agent Journal Agent Instructions

This public repository owns code, tests, Maven builds, releases, shipped contracts, and public
documentation. Private planning and control state are authoritative in
`/home/mark/projects/agent-journal-steward`; read its `BINDING.md` before planning or executing
work.

Use `./mvnw`, never `mvn`. The normal gate is `./mvnw clean verify`.

The product is the OCSF-based writer (`journal-ocsf-writer`) and its independently portable
`events.jsonl`, checked by the independent Java oracle in `journal-capture-tck`. The normative
requirements are the RFC 2119 rules and EARS criteria under
`journal-capture-tck/src/test/resources/requirements/agent-journal/`; read them before changing
capture, mapping or verification behaviour. The older `journal-core` contract and the per-CLI
`<cli>-capture` modules remain until they are explicitly retired; they are not the model for new
work.

A capture is not done because a trace parses. A run that used tools but yields no tool events, or
collapses to one state, is a failure that can look like a finding; the independent checks judge
every reported occurrence, owner and value against the native input.

Live capture normalizes the stream a CLI offers (`stream-json`, `exec --json`). Archive the CLI's
native session file unchanged, with its checksum, and never parse it to supplement, reconcile or
enrich the stream. Importing a saved native session is a separate, explicitly requested offline
mode with its own provenance.

Record what the source reports. Classify a tool from its declared identity: the reported name in a
declared exact-name table, the source's item family, or a reviewed binding of an MCP tool's exact
namespace and name. Never derive extra tool calls, operations, argument vectors or operating-system
actions from command text: a Bash command containing `grep`, Python or a pipe is one invocation, and
interpreting what it ran belongs to consumers. Keep source-reported values, declared derivations,
Journal-generated values and caller-supplied values distinguishable; a value that holds only under an
assumption is stated as conditional, never as an established quantity.

Every fixture states its evidence class: a sanitized recording of a live run (with its provenance
manifest), a sanitized real stream recorded elsewhere, or labelled synthetic input. Never present one
class as another, and never let a synthetic or replayed fixture stand in for live evidence. Public
fixtures are reviewed and sanitized before they are committed.

Follow `/home/mark/projects/agento-forge/guides/java-library-quality.md`. The project uses a
customized source license; see `LICENSE`. Commit messages contain no AI attribution.

Do not copy private planning, roadmap, checkpoint, or dirty-tree state into public files.
