# Codex rollout fixture

`codex-rollout.jsonl` is a mechanically redacted projection of the verified live rollout used
during parser development. It preserves the real envelope types, ordinals, `call_id` pairing,
outer `name: "exec"` trap, nested `tools.exec_command` structure, and token-count records. Tool
arguments and outputs that crossed the public/private repository boundary were replaced with
neutral equivalents before this public test asset was committed.

`codex-function-call-synthetic.jsonl` is written by hand, not taken from any session. It follows
the `function_call`/`function_call_output` shapes in the public Codex protocol source
(`codex-rs/protocol/src/models.rs`). No committed fixture comes from a Codex version known to
write these records. `codex-function-call-synthetic.expected-tool-events.json` holds the tool
call events the recorder must write for it, as stored in `events.jsonl` without the timestamp.
