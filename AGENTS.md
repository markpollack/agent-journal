# Agent Journal Agent Instructions

This public repository owns code, tests, Maven builds, releases, shipped contracts, and public
documentation. Private planning and control state are authoritative in
`/home/mark/projects/agent-journal-steward`; read its `BINDING.md` before planning or executing
work.

Use `./mvnw`, never `mvn`. The normal gate is `./mvnw clean verify`.

This project owns two things: the portable capture format, and one adapter per agentic CLI.
`journal-core` owns the `JournalEvent` contract and the `events.jsonl` / `analysis.jsonl` writers.
Each `<cli>-capture` module maps that CLI's own output into `PhaseCapture`. Adapters produce the
frozen contracts; they do not change them.

**An adapter is done when its events carry a state sequence** that `agent-control-theory` can build
a transition matrix from — not when a trace parses. `gemini-cli-capture` is the cautionary case: it
ships, it parses, and it emits no tool events at all, so the Markov model has nothing to model.
Gemini is therefore excluded from the trajectory claim. A run yielding one state and a self-loop is
a failure, not a pass — and it looks like a finding, which is what makes it dangerous.

Classify from the payload, not the label. Codex names every tool call `exec` and nests the real
action inside `input`; classifying on the name alone collapses every Codex run to a single state.

Prefer a capture surface that is a public contract with an audience over private implementation
detail. Where the public contract is itself the unstable one, prefer the complete artifact the CLI
writes unconditionally — this is why Codex is harvested from its rollout file rather than
`exec --json`.

Fixtures are verbatim captures from live runs. Do not hand-write one; a fixture you invented tests
the shape you imagined, not the shape the vendor emits.

Follow `/home/mark/projects/agento-forge/guides/java-library-quality.md`. The project uses a
customized source license; see `LICENSE`. Commit messages contain no AI attribution.

Do not copy private planning, roadmap, checkpoint, or dirty-tree state into public files.
