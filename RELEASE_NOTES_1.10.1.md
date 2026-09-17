# Agent Journal 1.10.1

Maintenance release aligning the capture adapters with the SDK releases the AgentWorks BOM 1.21.0 manages.

## What changed

- `claude-code-sdk` 1.5.1 → **1.7.0** for `claude-code-capture`. 1.7.0 destroys the Claude CLI process tree
  before closing its streams, so a cancelled run no longer leaves the CLI alive.
- `gemini-cli-sdk` 0.29.2 → **0.30.0** for `gemini-cli-capture`.

No source change and no schema change: `journal-core` and every capture record are unchanged from 1.10.0. All
523 tests pass against the new versions.

## Upgrading

Drop-in. Bump the `agent-journal` coordinates to 1.10.1 (the AgentWorks BOM 1.21.0 manages them).

## Maven Central

`io.github.markpollack` version `1.10.1`: `agent-journal-parent`, `journal-core`, `claude-code-capture`,
`gemini-cli-capture`, `grok-cli-capture`, `codex-cli-capture`, `antigravity-cli-capture`, `junie-cli-capture`.
Each carries its consumer-rooted CycloneDX SBOM (`-cyclonedx.json`) as a signed attachment.
