# Junie 3813.7 live ACP capture (session-261116-181916-vwh1), sanitized

SANITIZED REAL LIVE CAPTURE (not synthetic, not test-constructed). Agent Client recorded one fresh synchronous Junie
3813.7 ACP session (local model `qwen/qwen3-coder-30b` through LM Studio; task "Create Hello Text File") with its
shipped recorder on 2026-11-16 and sealed `acquisition.json`, `summary.json` and `events.jsonl` (capture
junie-14544466668527969831). The manifest, including its ACP facts (`acp_session_id`, `acp_stop_reason`,
`native_sessions_new`) and Client's execution, decision, session and connection identifiers, is Client's own.

Sanitization (text replacements only; everything else, including record order, identifiers, timestamps and every
number, is the capture's):

- the workspace path (under the integration test's state directory) -> `/work`, in the manifest, the summary's
  `projectDir` and the session events (10 occurrences);
- the local model directory -> `/models` (1, the argv);
- the home directory -> `/home/user` (4: the CLI path in the argv, `native_sessions_directory` and the two origins);
- the manifest's `bytes`, `observed` and `sha256` restated for the two changed files; `records` unchanged (1 and 72);
  the manifest is written compactly, as Client wrote it.

The `EnvironmentVariablesUpdatedEvent` was checked by name before the capture was added: 14 variables, all on Client's
launch allowlist or LD_LIBRARY_PATH, PWD, SHLVL and _JPACKAGE_LAUNCHER, none credential-shaped. Its values are
Junie's ciphertext and are kept byte for byte; the replacements above were not applied inside them.

The session file reports the main agent's result block twice (records 63 and 69, the session in progress and then
completed), a shape the CLI recording junie-2 does not have. `archive/` is these files qualified by
`JunieAcquisition`, delivered by `TerminalReceiver` under `client-terminal-junie-acp/1` and stored with the
qualifier's `assembly-expectation.json`; regenerate it with `-Djunie.acp.live.regenerate=<this directory>/archive` on
`TerminalJunieProfileTest` in journal-ocsf-writer.
