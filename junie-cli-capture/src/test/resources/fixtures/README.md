# Junie session fixtures

Both files are verbatim `~/.junie/sessions/<sessionId>/events.jsonl` traces from live Junie CLI
**26.8.24 (2929.5)** (linux-amd64) runs on 2026-08-26, BYOK `--provider openai --model gpt-5.3-codex`.

| Fixture | Driven by | Lines | Tool steps | What it proves |
|---|---|---|---|---|
| `junie-acp-events.jsonl` | `junie --acp true`, over the wire | 201 | 4 | The production path. Emits `UserPromptEvent`, `TaskState` and `AgentThoughtBlockUpdatedEvent`, none of which the plain-CLI path produces. Re-emits every terminal block at end of session, so it is also the folding stress case. |
| `junie-cli-events.jsonl` | `junie --output-format json` | 240 | 7 | A genuine recovery trajectory: three commands fail (exit 127, 1, 1) before an edit and a passing test. No `TaskState`, so the outcome must come from the result block. |

Prompts: ACP — *"Fix the bug in calc.py and run python3 test_calc.py to prove it passes."*; CLI —
*"Inspect this project, fix the bug in calc.py, and run the relevant test to prove it passes."*
Junie really did edit `calc.py` and run the test in both.

## Neutralization

Line count, ordering, and every event's shape are preserved. Two substitutions were applied before
these crossed into the public repository:

1. **Absolute paths → `/workspace`.** The runs happened in a throwaway git repo under a scratchpad;
   the absolute path appeared in `CurrentDirectoryUpdatedEvent` and inside a Python traceback in
   terminal output.
2. **`EnvironmentVariablesUpdatedEvent.env` → two synthetic entries** (`PATH`, and a planted
   `FIXTURE_FAKE_API_KEY`). **This is not cosmetic.** Junie writes the agent's entire environment
   into the trace with values unredacted — the original captures contained live `OPENAI_API_KEY`,
   `E2B_API_KEY`, `ELEVENLABS_API_KEY` and session tokens. The parser drops that event by design;
   the planted fake key is what `neverCapturesEnvironmentVariables` asserts against, so the fixture
   still tests the behaviour that keeps real secrets out of a capture.

The token and cost figures are untouched, which is what lets the tests assert exact reconciliation
against the numbers Junie itself reported.
