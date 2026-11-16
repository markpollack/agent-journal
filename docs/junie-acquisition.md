# Junie ACP one-shot acquisition

This is the contract between a producer that seals one fresh synchronous Junie ACP session (Agent Client) and
Journal's independent qualifier for it. `AcquisitionQualifier.forProfile(profile)` returns the qualifier for the
acquisition profile below; `qualify(sealedManifest, newDirectory)` returns
`AcquisitionQualifier.Qualified(Path manifest, Path assemblyExpectation)`: the qualified manifest for `FinalAssembly`
and the expectation the independent `AssemblyOracle` reads back against. The destination must not exist. Qualification
reads and copies files only; it launches no process and calls no model.

## Identifiers

| Identifier | Value |
|---|---|
| Acquisition profile (the manifest's `profile`) | `junie-<version>-acp-session-files`, for example `junie-3813.7-acp-session-files` |
| Profile id (the manifest's `profile_id`) | `junie/<version>/acp+summary+session-events` |
| Terminal profile (`AcquisitionQualifier.terminalProfile()`) | `client-terminal-junie-acp/1` |
| Manifest kind and version | `agent-journal/acquired-execution`, `0.1` |

`<version>` is the Junie build number the CLI reports (`junie --version` prints `26.10.20 (3813.7)`; the version is
`3813.7`). The profile names carry the observed version. A version qualifies only when Journal has source meaning
bindings for both native sources at that version; today that is `3813.7`. Any other version is refused, not mapped
by guess.

## Sealed manifest

The producer writes `acquisition.json` beside the two native files it copied, then seals the directory.

```json
{
  "kind": "agent-journal/acquired-execution",
  "version": "0.1",
  "profile": "junie-3813.7-acp-session-files",
  "profile_id": "junie/3813.7/acp+summary+session-events",
  "acquisition_uid": "E",
  "complete": true,
  "capture_errors": [],
  "run": {
    "run_uid": "E",
    "invocation_index": 0,
    "capture_start": "2026-11-16T00:00:00Z",
    "cli": { "name": "Junie", "vendor_name": "JetBrains", "version": "3813.7" },
    "launch_settings": { "route": "acp_stdio", "working_directory": "/work/project" },
    "session_uid": "session-261116-120000-abcd"
  },
  "process": {
    "argv": ["junie", "--acp", "true", "--project", "/work/project"],
    "cwd": "/work/project",
    "client_session_uid": "L",
    "connection_uid": "G",
    "acp_session_id": "session-261116-120000-abcd",
    "acp_stop_reason": "end_turn",
    "fresh_session": true,
    "native_sessions_directory": "/home/user/.junie/sessions",
    "native_sessions_new": ["session-261116-120000-abcd"],
    "observed_resources_terminated": true,
    "sealed": true
  },
  "sources": [
    {
      "role": "summary", "artifact": "summary.json", "path": "summary.json", "profile": "junie_summary",
      "route": "native_session_file", "origin": "/home/user/.junie/sessions/session-261116-120000-abcd/summary.json",
      "owner_session_uid": "session-261116-120000-abcd", "acquisition": "E", "required": true, "archive_only": false,
      "state": "acquired", "bytes": 457, "observed": 457, "dropped": 0, "end": "EOF", "framing": "document",
      "records": 1, "sha256": "<hex>"
    },
    {
      "role": "session", "artifact": "events.jsonl", "path": "events.jsonl", "profile": "junie_session_events",
      "route": "native_session_file", "origin": "/home/user/.junie/sessions/session-261116-120000-abcd/events.jsonl",
      "owner_session_uid": "session-261116-120000-abcd", "acquisition": "E", "required": true, "archive_only": false,
      "state": "acquired", "bytes": 178403, "observed": 178403, "dropped": 0, "end": "EOF", "framing": "lines",
      "records": 312, "sha256": "<hex>"
    }
  ],
  "turns": [
    { "invocation_uid": "E", "decision_uid": "D", "native_task_uid": "task-261116-120000-efgh" }
  ]
}
```

E, D, L and G are the Client execution, decision, Client session and connection identifiers, as in the Codex and
Claude one-shot manifests.

### Source roles

Exactly two sources, both required and not archive-only:

- `summary`: the native session directory's `summary.json`, profile `junie_summary`, framed as one document
  (`framing` `document`, `records` 1).
- `session`: the same directory's `events.jsonl`, profile `junie_session_events`, framed as lines (`framing`
  `lines`, `records` the line count).

The ACP stdio exchange itself is not a source of this profile. Client keeps it as its own evidence.

### Identity fields

These fields bind the ACP session to exactly one native Junie session:

- `process.acp_session_id`: the `sessionId` that ACP `session/new` returned. Junie names its native session directory
  with exactly this id.
- `run.session_uid` and each source's `owner_session_uid`: the same id.
- `process.native_sessions_new`: the session directory names that appeared under `native_sessions_directory`
  between the producer's snapshot before `session/new` and its snapshot at sealing. It must be exactly
  `[acp_session_id]`.
- each source's `origin`: a file directly inside `<native_sessions_directory>/<acp_session_id>/`.
- `launch_settings.working_directory` and `process.cwd`: the `--project` directory the session ran in.
- `turns[0].native_task_uid`: the `taskId` of the session's one `TaskStartedEvent`.

## Qualification

The qualifier reads the original bytes with the TCK's own tokenizer and never calls the writer, the Client or the
CLI. It refuses, with the reason as the `IllegalArgumentException` message, when:

1. **Missing summary or events:** a source is undeclared, is not `acquired`, or its file is absent from the sealed
   directory.
2. **More than one candidate native session:** `native_sessions_new` is not exactly `[acp_session_id]`.
3. **Identity mismatch with the ACP session:** `summary.json` `sessionId` differs from `acp_session_id`, from
   `run.session_uid` or from an `owner_session_uid`; an `origin` lies outside that session's directory; or
   `summary.json` `projectDir` differs from the working directory.
4. **Incomplete events:** a line is not newline-terminated or not one JSON object; the record count differs from
   `records`; there is not exactly one `TaskStartedEvent`, or its `taskId` differs from `native_task_uid`; no main-agent
   `ResultBlockUpdatedEvent` with `cancelled: false` is present; `summary.json` `lifecycle` is not `COMPLETED`;
   `acp_stop_reason` is not `end_turn`; or the process facts are not all true (`fresh_session`, `sealed`,
   `observed_resources_terminated`), `complete` is false, `capture_errors` is not empty, a source was `dropped` or
   did not end at `EOF`, or a source's `framing` or `records` differs from its role.
5. **Changed bytes:** a file's length or SHA-256 differs from its declaration (`bytes`, `observed`, `sha256`).
6. **Credential-shaped environment:** an `EnvironmentVariablesUpdatedEvent` in the events names a credential-shaped
   variable. A name is credential-shaped when, upper-cased, it contains `KEY`, `TOKEN`, `SECRET`, `PASSWORD`, `PASSWD`,
   `CREDENTIAL`, `AUTH`, `COOKIE` or `SESSION_ID`, except the upper-cased names `SSH_AUTH_SOCK` and `XAUTHORITY` (the same rule Agent Client
   applies when it withholds such a source). The refusal names the variables, never their values.

Junie 3813.7 writes its launch environment into `events.jsonl` (`EnvironmentVariablesUpdatedEvent`, `env` as
`key`/`value` pairs). Each value is AES-256-GCM ciphertext (Base64 of a 12-byte IV, the ciphertext and a 128-bit tag)
under a key Junie keeps in its own secure storage, so the plaintext length of every value is visible and the value is
recoverable by anyone holding that key. A producer launches Junie with only the variables the run needs.

The profile, profile id, kind and version must be exact, the CLI version must be the one both profile names carry,
and a source meaning binding must exist for both sources at that version.

## Usage and cost

The summary's `mainAgent.modelUsage` entries are taken as reported. A reported cost of `0` or `0.0` is a valid
observation, for example from a local model. A token count the entry does not report stays absent: it is never
replaced by zero or derived from cost. A reported count must be a non-negative integer and a reported cost a
non-negative number; anything else is refused. Qualification never adds usage the sources do not state.

## Output

The new directory holds the two original files, the caller's manifest unchanged as `client-acquisition.json`, the
qualified `acquisition.json` (the original members plus each source's meaning binding and a `qualification` object
naming the original manifest's SHA-256), and `assembly-expectation.json` stated under the profile id. Deliver the
binding under `client-terminal-junie-acp/1`; `TerminalReceiver` assembles it through `FinalAssembly`, and the packaged
reader verifies the archive with `AssemblyOracle` in a separate JVM using only the TCK.

Qualification is not semantic acceptance of every meaning, and a version without bindings is refused rather than
treated as qualified.
