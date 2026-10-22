# Packages written with agentic_cli extension 0.3.0-prototype

`codex-live-0.160.1-commands/package` is the committed package of the sanitized live Codex recording as the writer
wrote it before extension 0.4.0-prototype (FND-1, 2026-10-22), when `cli_agent` extended the core `ai_agent` and
carried `type_id`/`type`. Its native artifact is the current fixture's (unchanged). It stays readable from its own
embedded definitions; `PackageReaderTest#aPackageWrittenWithExtension030StillReadsFromItsOwnDefinitions` proves it.
Never rewritten.
