# SYNTHETIC two-turn Codex retained thread (0.166.0 exec/resume emulation)

Derived from the Client-frozen SYNTHETIC specimen `/home/mark/.local/state/client-retained-20261104/caller/proof-b74a262` (SHA256SUMS sha256
07cd59bed00954cb375a81cf8085abdbb7d7d02b366ad0ab32cb2db71dc57979, produced by agent-client b74a262 with its fixture
child; **no model or real Codex CLI was called**). Per-turn originals, per-turn manifests, prefix snapshots and the final
transcript are byte-identical copies; `codex-thread.json` differs from the Client original only by relativizing the
per-turn source/directory/manifest paths under this directory (argv/cwd/origin strings are unchanged data).
Thread a94e0250-…; turn 0 fresh exec, turn 1 `exec resume` by a replacement process. Qualification of this fixture is
fixture evidence only: real resume behaviour (thread.started re-emission, global flag honouring, turn-2 wire usage scope)
remains UNVERIFIED until the bounded native smoke.
