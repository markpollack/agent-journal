# Packages written with agentic_cli extension 0.5.0-prototype

`codex-live-0.160.1-mcp/package` is the committed package of the sanitized live Codex MCP recording as the writer wrote
it with extension 0.5.0-prototype and the Codex exec contract 0.3.0-prototype (SEM-1 candidate, 2026-10-22), before the
delegated semantic closure (extension 0.6.0-prototype, contract 0.4.0-prototype). Its native artifact is the current
fixture's (unchanged). It stays readable from its own embedded definitions and is never rewritten.

Semantic defects of that version, labelled here and corrected in 0.6.0-prototype:

- `usage_observations[].input_tokens` carried each vendor's own meaning: on Codex it includes the cached input
  (63434 here, of which 52352 cached), on Claude it excludes it. 0.6.0 replaces it with `uncached_input_tokens`
  (11082 here, derived), with each component's basis stated.
- `model_response.response_uid` held the stream-local item id (`item_0`), presenting a content item as a provider
  response identity; 0.6.0 writes `item_uid` with its identity basis and no response id.
- `item.started` of an MCP item was written as `tool_call` activity 1 Requested (the model's request); the stream
  reports a started invocation (0.6.0 activity 4 Started).
- MCP kinds came from a table over bare tool names, so an MCP tool named like a built-in would have taken its kind
  (not exercised by this recording; 0.6.0 classifies MCP tools 99 Other unless a reviewed namespace binding exists).

`PackageReaderTest#aPackageWrittenWithExtension050StillReadsAndShowsOnlyTheRevisedMeanings` proves that it reads and
states which judgments change under the revised reading.
