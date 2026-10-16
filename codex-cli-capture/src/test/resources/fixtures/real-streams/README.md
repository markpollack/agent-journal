# Real `codex exec --json` stream

`exec-json-ls.jsonl` is the stdout of `codex exec --json --skip-git-repo-check --sandbox read-only` (codex-cli 0.160.1, 2026-10-16) run in an empty directory holding three synthetic files, with the prompt "Run the shell command 'ls' once in the current directory, then reply with only the number of files you saw." Nothing was edited. The thread id is random.

For the same run the rollout file reports the same final usage (input 30499, cached 27264, output 82, reasoning 42) as `turn.completed.usage`; the stream carries no model name, timestamps, per-call usage or reasoning, which only the rollout has.
