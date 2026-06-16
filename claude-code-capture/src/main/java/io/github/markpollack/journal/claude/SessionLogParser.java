package io.github.markpollack.journal.claude;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.github.markpollack.claude.agent.sdk.parsing.ParsedMessage;
import io.github.markpollack.claude.agent.sdk.types.AssistantMessage;
import io.github.markpollack.claude.agent.sdk.types.ContentBlock;
import io.github.markpollack.claude.agent.sdk.types.ResultMessage;
import io.github.markpollack.claude.agent.sdk.types.TextBlock;
import io.github.markpollack.claude.agent.sdk.types.ThinkingBlock;
import io.github.markpollack.claude.agent.sdk.types.ToolResultBlock;
import io.github.markpollack.claude.agent.sdk.types.ToolUseBlock;
import io.github.markpollack.claude.agent.sdk.types.UserMessage;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Extracts full SDK data from a response iteration into a {@link PhaseCapture}.
 *
 * Consolidates the parsing logic previously duplicated across:
 * - agent/RefactoringAgent.consumeResponse()
 * - spring-upgrade-agent/SessionLogParser.parse()
 *
 * Captures everything the SDK provides: tokens, cost, timing, thinking blocks,
 * tool uses, session metadata, raw result, and prompt text.
 */
public class SessionLogParser {

    private static final Logger logger = LoggerFactory.getLogger(SessionLogParser.class);

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * Parse a Claude SDK response iterator into a PhaseCapture.
     *
     * @param response   the SDK response iterator
     * @param phaseName  the phase name for this capture ("explore", "plan", "execute", etc.)
     * @param promptText the prompt that was sent for this phase (null if not captured)
     * @return a PhaseCapture with all extracted data
     */
    public static PhaseCapture parse(Iterator<ParsedMessage> response, String phaseName, String promptText) {
        return parse(response, phaseName, promptText, null);
    }

    /**
     * Parse a Claude SDK response iterator into a PhaseCapture, optionally writing
     * a JSONL trace file for each event with the default content mode
     * ({@link TraceContentMode#TRUNCATED}).
     *
     * @param response   the SDK response iterator
     * @param phaseName  the phase name for this capture ("explore", "plan", "execute", etc.)
     * @param promptText the prompt that was sent for this phase (null if not captured)
     * @param traceFile  optional path to a JSONL trace file (null to skip tracing)
     * @return a PhaseCapture with all extracted data
     */
    public static PhaseCapture parse(Iterator<ParsedMessage> response, String phaseName, String promptText,
            Path traceFile) {
        return parse(response, phaseName, promptText, traceFile, TraceContentMode.TRUNCATED);
    }

    /**
     * Parse a Claude SDK response iterator into a PhaseCapture, optionally writing
     * a JSONL trace file for each event.
     *
     * <p>
     * Note on identity: callers (e.g. agent-client's ClaudeAgentModel) currently pass
     * their per-run trace id as {@code phaseName}, so the trace header records it as
     * both {@code runId} and {@code phase}. A first-class run-id parameter is deferred
     * to the identity cleanup (Semantic Journal Roadmap).
     *
     * @param response    the SDK response iterator
     * @param phaseName   the phase name for this capture ("explore", "plan", "execute", etc.)
     * @param promptText  the prompt that was sent for this phase (null if not captured)
     * @param traceFile   optional path to a JSONL trace file (null to skip tracing)
     * @param contentMode content capture policy for trace lines
     * @return a PhaseCapture with all extracted data
     */
    public static PhaseCapture parse(Iterator<ParsedMessage> response, String phaseName, String promptText,
            Path traceFile, TraceContentMode contentMode) {
        return parse(response, phaseName, promptText, traceFile, contentMode, TraceRawMode.NONE);
    }

    /**
     * Parse a Claude SDK response iterator into a PhaseCapture, optionally writing a JSONL
     * trace file with a content-capture policy and a raw-capture policy.
     *
     * <p>
     * When {@code rawMode} is {@link TraceRawMode#FULL}, each vendor wire message is also
     * persisted verbatim as a {@code raw} trace line ({@link TraceWriter#writeRaw}), so
     * unmodeled fields (the sub-agent envelope, {@code permission_denials},
     * {@code modelUsage}) are recoverable even though the typed {@code Message} drops them.
     * Requires {@code claude-code-sdk} &ge; 1.3.0 for {@code RegularMessage.rawJson}.
     *
     * @param response    the SDK response iterator
     * @param phaseName   the phase name for this capture
     * @param promptText  the prompt that was sent for this phase (null if not captured)
     * @param traceFile   optional path to a JSONL trace file (null to skip tracing)
     * @param contentMode content capture policy for trace lines
     * @param rawMode     verbatim raw-wire capture policy
     * @return a PhaseCapture with all extracted data
     */
    public static PhaseCapture parse(Iterator<ParsedMessage> response, String phaseName, String promptText,
            Path traceFile, TraceContentMode contentMode, TraceRawMode rawMode) {
        TraceWriter trace = null;
        if (traceFile != null) {
            try {
                trace = new TraceWriter(traceFile, contentMode, phaseName, phaseName, rawMode);
            } catch (IOException ex) {
                logger.warn("[{}] Failed to open trace file {}: {}", phaseName, traceFile, ex.getMessage());
            }
        }

        try {
            return doParse(response, phaseName, promptText, trace);
        } finally {
            if (trace != null) {
                try {
                    trace.close();
                } catch (IOException ex) {
                    logger.warn("[{}] Failed to close trace file: {}", phaseName, ex.getMessage());
                }
            }
        }
    }

    private static PhaseCapture doParse(Iterator<ParsedMessage> response, String phaseName, String promptText,
            TraceWriter trace) {
        StringBuilder textOutput = new StringBuilder();
        List<String> thinkingBlocks = new ArrayList<>();
        List<ToolUseRecord> toolUses = new ArrayList<>();
        List<ToolResultRecord> toolResults = new ArrayList<>();
        // R2.2: per-turn usage (one per assistant message) + per-model cost decomposition
        List<TurnUsage> turns = new ArrayList<>();
        List<ModelCost> modelCosts = new ArrayList<>();
        Map<String, String> toolUseNames = new java.util.HashMap<>();
        Map<String, Map<String, Object>> toolUseInputs = new java.util.HashMap<>();
        Map<String, Long> toolUseStartMs = new java.util.HashMap<>();

        // ResultMessage fields (populated from the last ResultMessage seen)
        int inputTokens = 0;
        int outputTokens = 0;
        int thinkingTokens = 0;
        int cacheCreationInputTokens = 0;
        int cacheReadInputTokens = 0;
        long durationMs = 0;
        long apiDurationMs = 0;
        double totalCostUsd = 0.0;
        String sessionId = null;
        int numTurns = 0;
        boolean isError = false;
        String rawResult = null;

        while (response.hasNext()) {
            ParsedMessage parsed = response.next();
            if (!parsed.isRegularMessage()) {
                continue;
            }

            // R2.1: persist the verbatim wire message before its typed decomposition, so
            // the sub-agent envelope (isSidechain/parentUuid/parent_tool_use_id) and other
            // unmodeled fields survive. No-op unless rawMode=FULL; rawJson is null for
            // programmatically-constructed messages and SDK < 1.3.0.
            final String rawJson = parsed instanceof ParsedMessage.RegularMessage regular ? regular.rawJson() : null;
            writeTrace(trace, phaseName, w -> w.writeRaw(rawJson));
            JsonNode rawRoot = parseRaw(rawJson);

            var message = parsed.asMessage();

            if (message instanceof ResultMessage resultMsg) {
                totalCostUsd = resultMsg.totalCostUsd() != null ? resultMsg.totalCostUsd() : 0.0;
                durationMs = resultMsg.durationMs();
                apiDurationMs = resultMsg.durationApiMs();
                numTurns = resultMsg.numTurns();
                sessionId = resultMsg.sessionId();
                isError = resultMsg.isError();
                rawResult = resultMsg.result();

                // Extract token counts from usage map
                Map<String, Object> usage = resultMsg.usage();
                if (usage != null) {
                    inputTokens = getInt(usage, "input_tokens");
                    outputTokens = getInt(usage, "output_tokens");
                    thinkingTokens = getInt(usage, "thinking_tokens");
                    cacheCreationInputTokens = getInt(usage, "cache_creation_input_tokens");
                    cacheReadInputTokens = getInt(usage, "cache_read_input_tokens");
                }
                logger.info("[{}] Complete: {} turns, {} in + {} out tokens, ${}", phaseName, numTurns, inputTokens,
                        outputTokens, String.format("%.4f", totalCostUsd));
                final int fIn = inputTokens;
                final int fOut = outputTokens;
                final double fCost = totalCostUsd;
                final int fTurns = numTurns;
                final long fDur = durationMs;
                final TraceWriter.ResultMeta fMeta = new TraceWriter.ResultMeta(sessionId, isError,
                        resultMsg.subtype(), apiDurationMs, thinkingTokens, cacheCreationInputTokens,
                        cacheReadInputTokens, resultMsg.structuredOutput());
                writeTrace(trace, phaseName, w -> w.writeResult(fIn, fOut, fCost, fTurns, fDur, fMeta));
                // R2.2: the exact per-model cost decomposition (sums to totalCostUsd) lives in
                // the result wire's modelUsage sibling — not on the typed ResultMessage. Last
                // result wins.
                List<ModelCost> parsed2 = parseModelCosts(rawRoot);
                if (!parsed2.isEmpty()) {
                    modelCosts.clear();
                    modelCosts.addAll(parsed2);
                }
            }

            if (message instanceof AssistantMessage assistantMsg) {
                // R2.2: per-turn usage from message.usage / message.model (wire-only; the typed
                // AssistantMessage carries content alone).
                TurnUsage turn = parseTurnUsage(rawRoot);
                if (turn != null) {
                    turns.add(turn);
                }
                for (ContentBlock block : assistantMsg.content()) {
                    if (block instanceof TextBlock textBlock) {
                        textOutput.append(textBlock.text());
                        logger.debug("[{}] Text: {} chars", phaseName, textBlock.text().length());
                        writeTrace(trace, phaseName, w -> w.writeText(textBlock.text()));
                    } else if (block instanceof ThinkingBlock thinkingBlock) {
                        String thinking = thinkingBlock.thinking() != null ? thinkingBlock.thinking() : "";
                        thinkingBlocks.add(thinking);
                        logger.debug("[{}] Thinking: {} chars", phaseName, thinking.length());
                        // hasSignature makes upstream redaction self-diagnosing:
                        // length:0 + hasSignature:true means the block arrived empty,
                        // not that capture dropped it.
                        final boolean hasSignature = thinkingBlock.signature() != null
                                && !thinkingBlock.signature().isEmpty();
                        writeTrace(trace, phaseName, w -> w.writeThinking(thinking, hasSignature));
                    } else if (block instanceof ToolUseBlock toolUseBlock) {
                        toolUses.add(new ToolUseRecord(
                                toolUseBlock.id(),
                                toolUseBlock.name(),
                                toolUseBlock.input()));
                        toolUseNames.put(toolUseBlock.id(), toolUseBlock.name());
                        toolUseInputs.put(toolUseBlock.id(), toolUseBlock.input());
                        toolUseStartMs.put(toolUseBlock.id(), System.currentTimeMillis());
                        String target = toolTarget(toolUseBlock.name(), toolUseBlock.input());
                        logger.info("[{}] Tool use: {} {} (id: {})", phaseName, toolUseBlock.name(), target, toolUseBlock.id());
                        writeTrace(trace, phaseName,
                                w -> w.writeToolUse(toolUseBlock.name(), toolUseBlock.id(), toolUseBlock.input()));
                    }
                }
            }

            if (message instanceof UserMessage userMsg) {
                List<ContentBlock> blocks = userMsg.getContentAsBlocks();
                if (blocks != null) {
                    for (ContentBlock block : blocks) {
                        if (block instanceof ToolResultBlock resultBlock) {
                            String content = resultBlock.getContentAsString();
                            if (content == null && resultBlock.content() != null) {
                                content = resultBlock.content().toString();
                            }
                            toolResults.add(new ToolResultRecord(
                                    resultBlock.toolUseId(),
                                    content,
                                    Boolean.TRUE.equals(resultBlock.isError())));
                            String resultToolName = toolUseNames.getOrDefault(resultBlock.toolUseId(), "?");
                            Long startMs = toolUseStartMs.get(resultBlock.toolUseId());
                            long elapsedMs = startMs != null ? System.currentTimeMillis() - startMs : -1;
                            final String fContent = content;
                            final int len = content != null ? content.length() : 0;
                            final boolean err = Boolean.TRUE.equals(resultBlock.isError());
                            if (elapsedMs >= 0) {
                                logger.info("[{}] Tool result: {} {}ms isError={} len={}", phaseName,
                                        resultToolName, elapsedMs, err, len);
                            } else {
                                logger.info("[{}] Tool result: {} isError={} len={}", phaseName,
                                        resultToolName, err, len);
                            }
                            final Map<String, Object> source = sourceRef(resultToolName,
                                    toolUseInputs.get(resultBlock.toolUseId()));
                            writeTrace(trace, phaseName,
                                    w -> w.writeToolResult(resultBlock.toolUseId(), err, fContent, source));
                        }
                    }
                }
            }
        }

        // If thinking_tokens not in usage map, estimate from captured thinking blocks
        // (~4 chars/token is a standard heuristic for English text)
        if (thinkingTokens == 0 && !thinkingBlocks.isEmpty()) {
            int totalChars = thinkingBlocks.stream().mapToInt(String::length).sum();
            thinkingTokens = totalChars / 4;
        }

        return new PhaseCapture(
                phaseName,
                promptText,
                inputTokens,
                outputTokens,
                thinkingTokens,
                cacheCreationInputTokens,
                cacheReadInputTokens,
                durationMs,
                apiDurationMs,
                totalCostUsd,
                sessionId,
                numTurns,
                isError,
                textOutput.toString(),
                thinkingBlocks,
                toolUses,
                rawResult,
                toolResults,
                turns,
                modelCosts
        );
    }

    @FunctionalInterface
    private interface TraceAction {
        void execute(TraceWriter writer) throws IOException;
    }

    private static void writeTrace(TraceWriter trace, String phaseName, TraceAction action) {
        if (trace == null) {
            return;
        }
        try {
            action.execute(trace);
        } catch (IOException ex) {
            logger.warn("[{}] Trace write failed: {}", phaseName, ex.getMessage());
        }
    }

    /**
     * Derives a pointer to where truncated tool_result content can be re-read: the
     * file for path-bearing tools (Read/Write/Edit/Glob), the command for Bash.
     * Written to the trace only when the content is actually truncated — a truncated
     * item is then self-describing: head(60KB) + canonical length + source. Note the
     * source file may have been edited by analysis time; the transcript archive is
     * the reliable backstop.
     */
    private static Map<String, Object> sourceRef(String toolName, Map<String, Object> input) {
        Map<String, Object> source = new java.util.LinkedHashMap<>();
        if (input != null) {
            Object path = input.get("file_path");
            if (path == null) {
                path = input.get("path");
            }
            if (path instanceof String s && !s.isBlank()) {
                source.put("kind", "file_path");
                source.put("value", s);
                return source;
            }
            Object cmd = input.get("command");
            if (cmd instanceof String s && !s.isBlank()) {
                source.put("kind", "command");
                source.put("value", s);
                return source;
            }
        }
        source.put("kind", "unknown");
        source.put("value", toolName != null ? toolName : "");
        return source;
    }

    /**
     * Extract a short human-readable target from tool input for log readability.
     */
    private static String toolTarget(String toolName, Map<String, Object> input) {
        if (input == null) {
            return "";
        }
        return switch (toolName) {
            case "Read", "Write", "Edit" -> {
                Object path = input.get("file_path");
                if (path instanceof String s) {
                    // Show last 2 path segments
                    String[] parts = s.split("/");
                    yield parts.length > 1
                            ? "— " + parts[parts.length - 2] + "/" + parts[parts.length - 1]
                            : "— " + s;
                }
                yield "";
            }
            case "Bash" -> {
                Object cmd = input.get("command");
                if (cmd instanceof String s) {
                    String trimmed = s.trim();
                    if (trimmed.length() > 60) {
                        trimmed = trimmed.substring(0, 57) + "...";
                    }
                    yield "— " + trimmed;
                }
                yield "";
            }
            case "Glob" -> {
                Object pattern = input.get("pattern");
                yield pattern instanceof String s ? "— " + s : "";
            }
            case "Grep" -> {
                Object pattern = input.get("pattern");
                yield pattern instanceof String s ? "— /" + s + "/" : "";
            }
            // Skill invocation — show which skill was called
            case "Skill" -> {
                Object skill = input.get("skill");
                Object args = input.get("args");
                if (skill instanceof String s) {
                    yield args instanceof String a && !a.isBlank()
                            ? "— " + s + " (" + a + ")"
                            : "— " + s;
                }
                yield "";
            }
            // Subagent spawn — show its description (the 3-5 word purpose summary),
            // not the prompt text (which is the implementation detail, not the intent)
            case "Agent" -> {
                Object desc = input.get("description");
                if (desc instanceof String s && !s.isBlank()) {
                    yield "— [subagent] " + s;
                }
                // Fall back to first line of prompt if no description
                Object prompt = input.get("prompt");
                if (prompt instanceof String s) {
                    String firstLine = s.lines().filter(l -> !l.isBlank()).findFirst().orElse("").trim();
                    if (firstLine.length() > 60) firstLine = firstLine.substring(0, 57) + "...";
                    yield firstLine.isBlank() ? "" : "— [subagent] " + firstLine;
                }
                yield "";
            }
            default -> "";
        };
    }

    /**
     * Parses a raw wire line into a JsonNode, or null when absent/malformed. Wire JSON is
     * expected to be valid; a malformed line simply yields no per-turn record rather than
     * failing the parse.
     */
    private static JsonNode parseRaw(String rawJson) {
        if (rawJson == null) {
            return null;
        }
        try {
            return MAPPER.readTree(rawJson);
        } catch (IOException ex) {
            logger.debug("Could not parse raw wire line for per-turn usage: {}", ex.getMessage());
            return null;
        }
    }

    /**
     * Per-turn usage from an assistant wire message's {@code message.usage} block (snake_case
     * keys). Returns null when the raw wire is unavailable or carries no usage object.
     */
    private static TurnUsage parseTurnUsage(JsonNode rawRoot) {
        if (rawRoot == null) {
            return null;
        }
        JsonNode msg = rawRoot.path("message");
        JsonNode usage = msg.path("usage");
        if (!usage.isObject()) {
            return null;
        }
        return new TurnUsage(
                textOrNull(msg, "id"),
                textOrNull(msg, "model"),
                usage.path("input_tokens").asLong(0),
                usage.path("output_tokens").asLong(0),
                usage.path("cache_creation_input_tokens").asLong(0),
                usage.path("cache_read_input_tokens").asLong(0));
    }

    /**
     * Per-model cost decomposition from the result wire's {@code modelUsage} object
     * (camelCase keys, {@code costUSD}). Empty list when absent.
     */
    private static List<ModelCost> parseModelCosts(JsonNode rawRoot) {
        if (rawRoot == null) {
            return List.of();
        }
        JsonNode modelUsage = rawRoot.path("modelUsage");
        if (!modelUsage.isObject()) {
            return List.of();
        }
        List<ModelCost> costs = new ArrayList<>();
        modelUsage.fields().forEachRemaining(entry -> {
            JsonNode m = entry.getValue();
            costs.add(new ModelCost(
                    entry.getKey(),
                    m.path("inputTokens").asLong(0),
                    m.path("outputTokens").asLong(0),
                    m.path("cacheReadInputTokens").asLong(0),
                    m.path("cacheCreationInputTokens").asLong(0),
                    m.path("costUSD").asDouble(0.0)));
        });
        return costs;
    }

    private static String textOrNull(JsonNode node, String field) {
        JsonNode v = node.path(field);
        return v.isMissingNode() || v.isNull() ? null : v.asText();
    }

    private static int getInt(Map<String, Object> map, String key) {
        Object value = map.get(key);
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        return 0;
    }
}
