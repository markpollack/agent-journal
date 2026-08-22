# Grok streaming fixtures

Both JSONL files come from live Grok CLI 1.0.5 `streaming-json` runs. The supplied two-read fixture
is retained to verify ACP `toolCallId` pairing. The second live run supplies the required
multi-state `execute -> read` trajectory. Repeated `available_commands` inventories were removed,
and the terminal log path was neutralized, before these test assets crossed into the public repo;
tool calls, updates, usage, terminal result, and captured content are otherwise preserved.
