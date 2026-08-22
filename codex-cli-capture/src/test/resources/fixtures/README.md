# Codex rollout fixture

`codex-rollout.jsonl` is a mechanically redacted projection of the verified live rollout used
during parser development. It preserves the real envelope types, ordinals, `call_id` pairing,
outer `name: "exec"` trap, nested `tools.exec_command` structure, and token-count records. Tool
arguments and outputs that crossed the public/private repository boundary were replaced with
neutral equivalents before this public test asset was committed.
