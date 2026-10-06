# Claude Code capture fixtures

## `claude-subagents-synthetic.jsonl`

A hand-written Claude Code `stream-json` session in which the main loop starts sub-agents. It
follows the line shapes Claude Code 2.1.292 writes, and contains no data from a real session:
every ID, prompt, output, model name and number is invented.

What it contains:

| Agent | Started by | Depth | How it ends |
|---|---|---|---|
| main loop | | | `result`, cost 0.50 |
| `toolu_spawn_a` | main loop, in the same message as `b` and `refused` | 1 | end message reports `completed` |
| `toolu_spawn_b` | main loop | 1 | no end message; `completed` only in the summary on the result of its spawning call |
| `toolu_spawn_c` | sub-agent `b` | 2 | end message reports `failed` |
| `toolu_spawn_d` | main loop | 1 | no end reported, and no result for its spawning call |
| `toolu_spawn_refused` | main loop | | never started; only an error result for the spawning call |

Each line of a sub-agent carries `parent_tool_use_id`, the ID of the tool call that started it.
One assistant message is several lines, one per content block, and each repeats the message's
`usage`: `msg_main_1` is five lines.

The token counts are chosen so the arithmetic can be checked by hand, counting each message once:

| Track | input | cache creation | cache read |
|---|---|---|---|
| main loop | 36 | 1,300 | 2,200 |
| a | 11 | 550 | 500 |
| b | 12 | 660 | 600 |
| c | 9 | 440 | 400 |
| d | 3 | 300 | 0 |
| all | 71 | 3,250 | 3,700 |

The result line's `usage` is the main loop's row and its `modelUsage` is the last row, which is how
Claude Code reports them. The output counts on the assistant lines are start-of-message figures
and do not add up to the result's, also as Claude Code reports them.

`spawn_depth` on `task_started` is 1 for a sub-agent of the main loop and 2 for a sub-agent of a sub-agent,
as observed live on Claude Code 2.1.292 (runs A, line 4 and line 17 of the wire evidence). When the start
message is missing, the parser counts the chain of spawning tool calls instead and gets the same numbers;
a chain it cannot follow gives -1.
