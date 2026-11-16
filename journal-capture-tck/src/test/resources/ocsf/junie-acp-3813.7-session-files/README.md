# Junie 3813.7 ACP session-files acquisition: real native bytes, stand-in manifest

`summary.json` and `events.jsonl` come from the real sanitized native session files of the live Junie 3813.7 run
junie-2 (2026-10-24): `../fixtures/junie-live-3813.7-terminal/native/summary.json` and `session-events.jsonl`. That
run was a CLI launch (`junie --output-format json-stream ... --task`), not an ACP session. The source fixture is not
changed; this directory holds copies.

- `summary.json` is a byte-for-byte copy.
- `events.jsonl` is a copy with one further sanitization step: the `EnvironmentVariablesUpdatedEvent` record (line 36)
  listed 105 launch environment variables, whose values Junie stores as AES-256-GCM ciphertext under a locally held
  key. Only the variables on Agent Client's launch allowlist are kept (PATH, HOME, USER, LOGNAME, SHELL, LANG, LC_ALL,
  LC_CTYPE, TERM, TMPDIR, TZ, JAVA_HOME, XDG_CONFIG_HOME, XDG_DATA_HOME, XDG_CACHE_HOME, XDG_STATE_HOME,
  XDG_RUNTIME_DIR, DBUS_SESSION_BUS_ADDRESS): 9 of them were present and are kept with their values byte for byte, in
  their original order; the other 96 entries were removed. Every other byte of the file is the source's, and it still
  holds 65 records.

`acquisition.json` is test-constructed around them and is a labelled stand-in until Agent Client's fresh ACP fixture
is added. Its ACP facts are not observations: `acp_session_id`, `run.session_uid`, `native_sessions_new` and each
`owner_session_uid` are set to the summary's `sessionId`; `acp_stop_reason` is `end_turn`; the execution, decision,
Client session and connection identifiers are E, D, L and G; the argv, `native_sessions_directory`, origins and
`capture_start` are stated in the shape docs/junie-acquisition.md gives. The byte extents, hashes and record counts
are those of the two files here.

`archive/` is the delivered archive: these files qualified by `JunieAcquisition`, delivered by `TerminalReceiver`
under `client-terminal-junie-acp/1` and stored with the qualifier's `assembly-expectation.json`. Regenerate it with
`-Djunie.acp.archive.regenerate=<this directory>/archive` on `TerminalJunieProfileTest` in journal-ocsf-writer.

No native file here is edited by a test. Tests that need a changed native file mutate a temporary copy and say so.
