package io.github.markpollack.journal.claude;

import org.springaicommunity.claude.agent.sdk.parsing.ParsedMessage;
import org.springaicommunity.claude.agent.sdk.types.AssistantMessage;
import org.springaicommunity.claude.agent.sdk.types.ContentBlock;
import org.springaicommunity.claude.agent.sdk.types.ResultMessage;
import org.springaicommunity.claude.agent.sdk.types.TextBlock;
import org.springaicommunity.claude.agent.sdk.types.ThinkingBlock;
import org.springaicommunity.claude.agent.sdk.types.ToolResultBlock;
import org.springaicommunity.claude.agent.sdk.types.ToolUseBlock;
import org.springaicommunity.claude.agent.sdk.types.UserMessage;

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
     * a JSONL trace file for each event.
     *
     * @param response   the SDK response iterator
     * @param phaseName  the phase name for this capture ("explore", "plan", "execute", etc.)
     * @param promptText the prompt that was sent for this phase (null if not captured)
     * @param traceFile  optional path to a JSONL trace file (null to skip tracing)
     * @return a PhaseCapture with all extracted data
     */
    public static PhaseCapture parse(Iterator<ParsedMessage> response, String phaseName, String promptText,
            Path traceFile) {
        TraceWriter trace = null;
        if (traceFile != null) {
            try {
                trace = new TraceWriter(traceFile);
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

        // ResultMessage fields (populated from the last ResultMessage seen)
        int inputTokens = 0;
        int outputTokens = 0;
        int thinkingTokens = 0;
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
                }
                logger.info("[{}] Complete: {} turns, {} in + {} out tokens, ${}", phaseName, numTurns, inputTokens,
                        outputTokens, String.format("%.4f", totalCostUsd));
                final int fIn = inputTokens;
                final int fOut = outputTokens;
                final double fCost = totalCostUsd;
                final int fTurns = numTurns;
                final long fDur = durationMs;
                writeTrace(trace, phaseName, w -> w.writeResult(fIn, fOut, fCost, fTurns, fDur));
            }

            if (message instanceof AssistantMessage assistantMsg) {
                for (ContentBlock block : assistantMsg.content()) {
                    if (block instanceof TextBlock textBlock) {
                        textOutput.append(textBlock.text());
                        logger.debug("[{}] Text: {} chars", phaseName, textBlock.text().length());
                        writeTrace(trace, phaseName, w -> w.writeText(textBlock.text().length()));
                    } else if (block instanceof ThinkingBlock thinkingBlock) {
                        thinkingBlocks.add(thinkingBlock.thinking());
                        logger.debug("[{}] Thinking: {} chars", phaseName, thinkingBlock.thinking().length());
                        writeTrace(trace, phaseName, w -> w.writeThinking(thinkingBlock.thinking().length()));
                    } else if (block instanceof ToolUseBlock toolUseBlock) {
                        toolUses.add(new ToolUseRecord(
                                toolUseBlock.id(),
                                toolUseBlock.name(),
                                toolUseBlock.input()));
                        logger.info("[{}] Tool use: {} (id: {})", phaseName, toolUseBlock.name(), toolUseBlock.id());
                        writeTrace(trace, phaseName,
                                w -> w.writeToolUse(toolUseBlock.name(), toolUseBlock.id()));
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
                            logger.debug("[{}] Tool result: id={} isError={} len={}", phaseName,
                                    resultBlock.toolUseId(), Boolean.TRUE.equals(resultBlock.isError()),
                                    content != null ? content.length() : 0);
                            final int len = content != null ? content.length() : 0;
                            final boolean err = Boolean.TRUE.equals(resultBlock.isError());
                            writeTrace(trace, phaseName,
                                    w -> w.writeToolResult(resultBlock.toolUseId(), err, len));
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
                toolResults
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

    private static int getInt(Map<String, Object> map, String key) {
        Object value = map.get(key);
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        return 0;
    }
}
