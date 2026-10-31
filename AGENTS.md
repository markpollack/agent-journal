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
capture, mapping or verification behaviour. The 1.x record format (`journal-core`) and its per-CLI
capture modules were retired from this line in 2.0; their history is on branch `1.x`, and nothing in
2.0 depends on them.

A capture is not done because a trace parses. A run that used tools but yields no tool events, or
collapses to one state, is a failure that can look like a finding; the independent checks judge
every reported occurrence, owner and value against the native input.

Capture reads the sources a reviewed source profile declares for the exact CLI version and route
(standard output, native session files and their summaries, native OTel exports, usage reports,
standard error); there is no universal stdout preference. A complete capture assembles them into one
new `events.jsonl` through the one writer: observations join only on declared identities, conflicts
stay visible, quantities are never counted twice or mixed across kinds, and the original packages
and native inputs stay unchanged. Material no profile declares is archived unchanged, with its
checksum, and never parsed into the record.

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
