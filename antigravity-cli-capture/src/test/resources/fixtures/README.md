# Antigravity streaming fixtures

`antigravity-stream-json.jsonl` is the supplied live denial/error capture.
`antigravity-clean-stream-json.jsonl` is a follow-up live Antigravity CLI 1.1.17 run with exactly
two successful tool states (`list_dir -> view_file`). Local working-directory values on `init`
records were neutralized before the fixtures crossed into the public repo; step and result records
are preserved.
