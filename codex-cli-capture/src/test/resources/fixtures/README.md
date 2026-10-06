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

# Codex sub-agent fixture (`codex-subagents-synthetic/`)

Written by hand, not taken from any session. Each file is one thread's rollout in the public
record shapes (`session_meta`, `response_item`, `event_msg/item_completed` with a
`SubAgentActivity` item, `event_msg/token_count`, `event_msg/task_complete`). Ids and numbers are
invented so the arithmetic below can be checked by hand.

| File | Thread id | Role |
|---|---|---|
| `root.jsonl` | `thread-root-0001` | root (`source: exec`): `spawn_agent` x4, `wait_agent` x1; `SubAgentActivity` started+completed for A, started+interrupted for B, started only for C; the fourth spawn (`call_spawn_d`) outputs a plain error string and has no activity |
| `child-a.jsonl` | `thread-child-a-0002` | non-forked child of the root, spawned by `call_spawn_a`; one `custom_tool_call` exec; `task_complete` |
| `child-b.jsonl` | `thread-child-b-0003` | forked child spawned by `call_spawn_b`: `forked_from_id`, `subagent_history_start_ordinal: 4`; ordinals 1 to 3 replay the root (a second `session_meta` with the root's id, the root's user message and the root's `task_complete`) and are skipped; one `function_call` exec_command; its own `task_complete` (900 ms), which the parent's `interrupted` activity outranks |
| `child-c.jsonl` | `thread-child-c-0005` | non-forked child spawned by `call_spawn_c`: a `started` activity but no later activity in the root and no own turn-terminal record, so status `unknown`, source `none`, run status CRASHED |
| `orphan.jsonl` | `thread-orphan-0004` | `parent_thread_id` names no supplied thread and no `started` activity names it: captured with `parentSpawnCallId=null`, `depth=-1`; its own last turn-terminal record is `turn_aborted`, so status `interrupted` from `child_last_turn` |

Token arithmetic (last `token_count` per file wins; `tokenUsage().inputTokens()` is
`input_tokens - cached_input_tokens`):

| Thread | input | cached | output | reasoning | `tokenUsage` input |
|---|---|---|---|---|---|
| root (second record) | 1200 | 500 | 300 | 80 | 700 |
| child A | 300 | 100 | 40 | 10 | 200 |
| child B | 500 | 200 | 60 | 20 | 300 |
| child C | 80 | 30 | 5 | 1 | 50 |
| orphan | 100 | 0 | 10 | 0 | 100 |

The root's tokens are its own only (1200, not 1200 + 300 + 500 + 80 + 100); no child token appears in
the parent. Durations: root 5000 ms, child A 1500 ms, child B 900 ms (its own `task_complete`, not the replayed
5000 ms), child C and orphan unknown (recorded as 0). Statuses: A `completed` and B `interrupted` from the parent's
last `SubAgentActivity` for each thread (`parent_sub_agent_activity_last`), orphan `interrupted` from
`child_last_turn`. No record in the fixture is a thread-terminal status; the status means the last observed turn.
