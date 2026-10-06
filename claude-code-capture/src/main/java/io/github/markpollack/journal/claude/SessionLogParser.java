package io.github.markpollack.journal.claude;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.github.markpollack.journal.event.StopReason;
import io.github.markpollack.journal.trace.JournalStep;
import io.github.markpollack.journal.trace.TraceContentMode;
import io.github.markpollack.journal.trace.TraceRawMode;
import io.github.markpollack.journal.trace.TraceWriter;

import io.github.markpollack.claude.agent.sdk.parsing.ParsedMessage;
import io.github.markpollack.claude.agent.sdk.types.AssistantMessage;
import io.github.markpollack.claude.agent.sdk.types.ContentBlock;
import io.github.markpollack.claude.agent.sdk.types.ResultMessage;
import io.github.markpollack.claude.agent.sdk.types.SystemMessage;
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
 * Reads the messages of one Claude Code call and returns a {@link PhaseCapture}: the agent's
 * text, thinking, tool calls and tool results, its token usage and cost, and why it stopped. Use
 * it after sending a prompt through the Claude SDK: pass the SDK's
 * {@code Iterator<ParsedMessage>} to a {@code parse} method. To store the result as a journal
 * run, pass the capture to a {@link RunRecorder}.
 *
 * <p>Despite its name, it reads the live SDK message stream, not a session log file. If given a
 * trace file, it also writes the messages there as JSON Lines with {@link TraceWriter}.
 *
 * <p>When the agent starts sub-agents, Claude Code sends their messages on the same stream, each
 * marked with the ID of the tool call that started the sub-agent. The parser keeps each
 * sub-agent's activity apart, as a {@link SubagentCapture} in {@link PhaseCapture#subagents()},
 * so the capture's own text, tool calls, turns and tokens are the main loop's only. This needs
 * the wire line of each message, which claude-code-sdk supplies from 1.3.0; without it the
 * messages cannot be told apart and {@link PhaseCapture#subagentTracksAvailable()} is
 * {@code false}. Up to 1.10.1 a sub-agent's tool calls and usage were recorded as the main
 * loop's. Verified against Claude Code 2.1.292, including through claude-code-sdk 1.7.0 and
 * agent-client. Without {@code --forward-subagent-text}, Claude Code sends only a sub-agent's
 * turns that call a tool, so its text and the usage of its text-only turns are then missing from
 * its capture; see {@link SubagentCapture}. A trace file is still a flat record of every message,
 * sub-agents' included.
 *
 * <p>Claude Code sends one assistant message as several lines, one per content block, each
 * repeating the message's ID and usage. The parser makes one turn of them and counts the usage
 * once; up to 1.10.1 it made a turn of each line and counted the usage once per line.
 *
 * <p>{@code parse} reads the iterator to the end. Exceptions thrown by the iterator reach the
 * caller. A trace file that cannot be opened, for any reason, including a bare file name with no
 * parent directory, is logged as a warning and does not stop parsing, and neither does an
 * error while writing a line, such as an I/O error or a cost that is not a finite number; the
 * capture is the same with or without a trace. A {@code null}
 * content mode means {@link TraceContentMode#TRUNCATED}.
 *
 * <p>All methods are static and keep no state between calls. Calls from several threads are safe
 * if each has its own iterator and trace file.
 */
public class SessionLogParser {

    private static final Logger logger = LoggerFactory.getLogger(SessionLogParser.class);

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * The turn limit value that means no limit was reported: -1. It is different from a limit of
     * 0.
     */
    public static final int UNKNOWN_MAX_TURNS = -1;

    /**
     * Parses the messages of one call into a capture, without writing a trace.
     *
     * @param response the SDK's messages for one call, read to the end
     * @param phaseName the caller's name for this call, such as {@code "plan"} or
     *        {@code "execute"}
     * @param promptText the prompt that was sent, or {@code null} if not captured
     * @return the capture, never {@code null}
     */
    public static PhaseCapture parse(Iterator<ParsedMessage> response, String phaseName, String promptText) {
        return parse(response, phaseName, promptText, null);
    }

    /**
     * Parses the messages of one call into a capture and, if {@code traceFile} is not
     * {@code null}, writes a trace using {@link TraceContentMode#TRUNCATED}.
     *
     * @param response the SDK's messages for one call, read to the end
     * @param phaseName the caller's name for this call, such as {@code "plan"} or
     *        {@code "execute"}
     * @param promptText the prompt that was sent, or {@code null} if not captured
     * @param traceFile the trace file to write, or {@code null} for no trace
     * @return the capture, never {@code null}
     */
    public static PhaseCapture parse(Iterator<ParsedMessage> response, String phaseName, String promptText,
            Path traceFile) {
        return parse(response, phaseName, promptText, traceFile, TraceContentMode.TRUNCATED);
    }

    /**
     * Parses the messages of one call into a capture and, if {@code traceFile} is not
     * {@code null}, writes a trace with the given content mode and no raw messages.
     *
     * @param response the SDK's messages for one call, read to the end
     * @param phaseName the caller's name for this call, such as {@code "plan"} or
     *        {@code "execute"}
     * @param promptText the prompt that was sent, or {@code null} if not captured
     * @param traceFile the trace file to write, or {@code null} for no trace
     * @param contentMode how much message content the trace keeps
     * @return the capture, never {@code null}
     */
    public static PhaseCapture parse(Iterator<ParsedMessage> response, String phaseName, String promptText,
            Path traceFile, TraceContentMode contentMode) {
        return parse(response, phaseName, promptText, traceFile, contentMode, TraceRawMode.NONE);
    }

    /**
     * Parses the messages of one call into a capture and, if {@code traceFile} is not
     * {@code null}, writes a trace with the given content and raw modes.
     *
     * <p>With {@link TraceRawMode#FULL}, the trace also keeps each message exactly as Claude Code
     * sent it, as a {@code raw} line. Fields that the SDK's typed messages drop, such as
     * {@code permission_denials} and {@code modelUsage}, can then be recovered. Raw lines need
     * claude-code-sdk 1.3.0 or later.
     *
     * @param response the SDK's messages for one call, read to the end
     * @param phaseName the caller's name for this call
     * @param promptText the prompt that was sent, or {@code null} if not captured
     * @param traceFile the trace file to write, or {@code null} for no trace
     * @param contentMode how much message content the trace keeps
     * @param rawMode whether the trace also keeps the messages as sent
     * @return the capture, never {@code null}
     */
    public static PhaseCapture parse(Iterator<ParsedMessage> response, String phaseName, String promptText,
            Path traceFile, TraceContentMode contentMode, TraceRawMode rawMode) {
        return parse(response, phaseName, promptText, traceFile, contentMode, rawMode, UNKNOWN_MAX_TURNS);
    }

    /**
     * Parses the messages of one call into a capture, records the turn limit the caller set, and,
     * if {@code traceFile} is not {@code null}, writes a trace.
     *
     * <p>Pass the turn limit you gave the SDK. Claude Code does not report it back, and without it
     * a turn count cannot show whether the agent finished or was cut off at its limit. Pass
     * {@link #UNKNOWN_MAX_TURNS} if you set no limit. If a message ever reports a limit, it is used
     * only when the caller passed {@link #UNKNOWN_MAX_TURNS}.
     *
     * <p>A trace ends with one {@code step_cost} line per step, split as in
     * {@link PhaseCapture#stepCosts()}. The trace header records {@code phaseName} as both the run
     * ID and the phase; there is no separate run ID parameter.
     *
     * @param response the SDK's messages for one call, read to the end
     * @param phaseName the caller's name for this call
     * @param promptText the prompt that was sent, or {@code null} if not captured
     * @param traceFile the trace file to write, or {@code null} for no trace
     * @param contentMode how much message content the trace keeps
     * @param rawMode whether the trace also keeps the messages as sent
     * @param maxTurns the turn limit set for this call, or {@link #UNKNOWN_MAX_TURNS}
     * @return the capture, never {@code null}
     */
    public static PhaseCapture parse(Iterator<ParsedMessage> response, String phaseName, String promptText,
            Path traceFile, TraceContentMode contentMode, TraceRawMode rawMode, int maxTurns) {
        TraceWriter trace = null;
        if (traceFile != null) {
            try {
                trace = new TraceWriter(traceFile, contentMode, phaseName, phaseName, rawMode);
            } catch (IOException | RuntimeException ex) {
                logger.warn("[{}] Failed to open trace file {}: {}", phaseName, traceFile, ex.getMessage());
            }
        }

        try {
            PhaseCapture capture = doParse(response, phaseName, promptText, trace, maxTurns);
            if (trace != null) {
                // R2.4: emit derived per-step cost as trailing step_cost lines. Cost is only
                // knowable post-run (the total arrives on the last result line), so attribution
                // happens here, after doParse, rather than on the streaming tool_use line.
                for (JournalStep step : JournalSteps.fromPhaseCapture(capture, phaseName)) {
                    writeTrace(trace, phaseName, w -> w.writeStepCost(step));
                }
            }
            return capture;
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

    /**
     * What one agent did during the call: the main loop, or one sub-agent. Claude Code marks each
     * message of a sub-agent with the ID of the tool call that started it, and the parser keeps
     * one of these per ID so that a sub-agent's activity never lands in the main loop's.
     */
    private static final class Track {
        /** The ID of the spawning tool call; {@code null} for the main loop. */
        final String spawnToolUseId;
        final StringBuilder text = new StringBuilder();
        final List<String> thinkingBlocks = new ArrayList<>();
        final List<ToolUseRecord> toolUses = new ArrayList<>();
        final List<ToolResultRecord> toolResults = new ArrayList<>();
        final List<TurnUsage> turns = new ArrayList<>();
        // One turn per API message: Claude Code sends a message as one line per content block,
        // each repeating the message ID and the same usage.
        final Map<String, Integer> turnIndexByMessageId = new java.util.HashMap<>();
        final Map<String, Integer> turnPositionByMessageId = new java.util.HashMap<>();
        int nextTurnIndex = 0;
        String lastTurnStopReason;
        // Sub-agents only
        /** Whether a message of the sub-agent itself arrived, not only a message about it. */
        boolean sawOwnMessage;
        String agentId;
        String subagentType;
        String description;
        String promptText;
        String status;
        String statusSource;
        boolean backgrounded;
        int reportedDepth = -1;
        long reportedTotalTokens = -1;
        long reportedToolUses = -1;
        long reportedDurationMs = -1;

        Track(String spawnToolUseId) {
            this.spawnToolUseId = spawnToolUseId;
        }

        boolean isMainLoop() {
            return spawnToolUseId == null;
        }
    }

    private static PhaseCapture doParse(Iterator<ParsedMessage> response, String phaseName, String promptText,
            TraceWriter trace, int callerMaxTurns) {
        Track main = new Track(null);
        // Sub-agent tracks by the ID of the tool call that started each, in the order first seen.
        Map<String, Track> subagentTracks = new java.util.LinkedHashMap<>();
        // Claude Code names a sub-agent's task in some lifecycle messages by task ID only.
        Map<String, String> spawnToolUseIdByTaskId = new java.util.HashMap<>();
        // R2.2: per-model cost decomposition
        List<ModelCost> modelCosts = new ArrayList<>();
        Map<String, String> toolUseNames = new java.util.HashMap<>();
        Map<String, Map<String, Object>> toolUseInputs = new java.util.HashMap<>();
        Map<String, Long> toolUseStartMs = new java.util.HashMap<>();
        boolean sawResult = false;
        String resultSubtype = null;
        int wireMaxTurns = UNKNOWN_MAX_TURNS;
        Map<String, Object> reportedSubagentStats = Map.of();
        // Whether every assistant and user message came with its wire line, which is the only
        // place Claude Code says which agent a message belongs to.
        boolean tracksAvailable = true;

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

            // A future CLI may report the turn ceiling on the wire (an init/system envelope or the
            // result itself). Probe for it on every message; the caller-supplied value still wins.
            int probed = parseMaxTurns(rawRoot);
            if (probed != UNKNOWN_MAX_TURNS) {
                wireMaxTurns = probed;
            }

            // The track this message belongs to. Without the raw wire line the sub-agent marker
            // is not available, and the message counts as the main loop's.
            String parentToolUseId = rawRoot == null ? null : textOrNull(rawRoot, "parent_tool_use_id");
            final Track track = parentToolUseId == null || parentToolUseId.isEmpty()
                    ? main
                    : subagentTracks.computeIfAbsent(parentToolUseId, Track::new);
            if (!track.isMainLoop()) {
                track.sawOwnMessage = true;
                if (track.agentId == null) {
                    track.agentId = textOrNull(rawRoot, "agent_id");
                }
            }

            if (message instanceof SystemMessage systemMsg) {
                readSubagentLifecycle(systemMsg, subagentTracks, spawnToolUseIdByTaskId);
            }

            if (message instanceof ResultMessage resultMsg) {
                sawResult = true;
                resultSubtype = resultMsg.subtype();
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
                // J3: the stop reason and the ceiling it ran against go onto the result line
                // together — either alone leaves numTurns uninterpretable.
                final StopReason fStop = ClaudeStopReasons.resolveRunStopReason(resultMsg.subtype(), isError,
                        true, main.lastTurnStopReason);
                final int fMax = callerMaxTurns != UNKNOWN_MAX_TURNS ? callerMaxTurns : wireMaxTurns;
                final TraceWriter.ResultMeta fMeta = new TraceWriter.ResultMeta(sessionId, isError,
                        resultMsg.subtype(), apiDurationMs, thinkingTokens, cacheCreationInputTokens,
                        cacheReadInputTokens, resultMsg.structuredOutput(), fStop, fMax);
                writeTrace(trace, phaseName, w -> w.writeResult(fIn, fOut, fCost, fTurns, fDur, fMeta));
                if (fStop == StopReason.MAX_TURNS) {
                    logger.warn("[{}] Run hit its turn ceiling (maxTurns={}, numTurns={}) — this trajectory is "
                            + "right-censored: its step count is a lower bound, not a measurement.",
                            phaseName, fMax, numTurns);
                }
                // R2.2: the exact per-model cost decomposition (sums to totalCostUsd) lives in
                // the result wire's modelUsage sibling — not on the typed ResultMessage. Last
                // result wins.
                List<ModelCost> parsed2 = parseModelCosts(rawRoot);
                if (!parsed2.isEmpty()) {
                    modelCosts.clear();
                    modelCosts.addAll(parsed2);
                }
                if (rawRoot != null && rawRoot.path("subagent_stats").isObject()) {
                    reportedSubagentStats = MAPPER.convertValue(rawRoot.path("subagent_stats"),
                            new com.fasterxml.jackson.core.type.TypeReference<java.util.LinkedHashMap<String, Object>>() { });
                }
            }

            if (rawRoot == null && (message instanceof AssistantMessage || message instanceof UserMessage)) {
                tracksAvailable = false;
            }

            if (message instanceof AssistantMessage assistantMsg) {
                // R2.3: collect this line's tool_use ids so per-step cost can be attributed
                // to the tool calls that belong to the same assistant message.
                List<String> turnToolIds = new ArrayList<>();
                // J4: the turn ordinal and identity every tool call in this turn is stamped with.
                // A message sent as several lines is one turn, so a line that repeats a message
                // ID continues that message's turn.
                final String currentTurnId = turnIdOf(rawRoot);
                Integer knownTurnIndex = currentTurnId == null ? null : track.turnIndexByMessageId.get(currentTurnId);
                final int currentTurnIndex = knownTurnIndex != null ? knownTurnIndex : track.nextTurnIndex++;
                if (currentTurnId != null && knownTurnIndex == null) {
                    track.turnIndexByMessageId.put(currentTurnId, currentTurnIndex);
                }
                for (ContentBlock block : assistantMsg.content()) {
                    if (block instanceof TextBlock textBlock) {
                        track.text.append(textBlock.text());
                        logger.debug("[{}] Text: {} chars", phaseName, textBlock.text().length());
                        writeTrace(trace, phaseName, w -> w.writeText(textBlock.text()));
                    } else if (block instanceof ThinkingBlock thinkingBlock) {
                        String thinking = thinkingBlock.thinking() != null ? thinkingBlock.thinking() : "";
                        track.thinkingBlocks.add(thinking);
                        logger.debug("[{}] Thinking: {} chars", phaseName, thinking.length());
                        // hasSignature makes upstream redaction self-diagnosing:
                        // length:0 + hasSignature:true means the block arrived empty,
                        // not that capture dropped it.
                        final boolean hasSignature = thinkingBlock.signature() != null
                                && !thinkingBlock.signature().isEmpty();
                        writeTrace(trace, phaseName, w -> w.writeThinking(thinking, hasSignature));
                    } else if (block instanceof ToolUseBlock toolUseBlock) {
                        track.toolUses.add(new ToolUseRecord(
                                toolUseBlock.id(),
                                ClaudeToolClassifier.classify(toolUseBlock.name()),
                                toolUseBlock.name(),
                                toolUseBlock.input(),
                                currentTurnId,
                                currentTurnIndex));
                        turnToolIds.add(toolUseBlock.id());
                        toolUseNames.put(toolUseBlock.id(), toolUseBlock.name());
                        toolUseInputs.put(toolUseBlock.id(), toolUseBlock.input());
                        toolUseStartMs.put(toolUseBlock.id(), System.currentTimeMillis());
                        String target = toolTarget(toolUseBlock.name(), toolUseBlock.input());
                        logger.info("[{}] Tool use: {} {} (id: {})", phaseName, toolUseBlock.name(), target, toolUseBlock.id());
                        // The trace is a flat record of every line, sub-agents' included, as it
                        // always was; it has no field for the agent a line belongs to.
                        writeTrace(trace, phaseName, w -> w.writeToolUse(toolUseBlock.name(), toolUseBlock.id(),
                                toolUseBlock.input(), currentTurnIndex, currentTurnId));
                    }
                }
                // R2.2/R2.3: per-turn usage (wire-only) carrying this turn's tool_use ids, and
                // (1.9.0) its thinking tokens, wire stop_reason and ordinal.
                TurnUsage turn = parseTurnUsage(rawRoot, turnToolIds, currentTurnIndex);
                if (turn != null) {
                    Integer position = turn.messageId() == null ? null
                            : track.turnPositionByMessageId.get(turn.messageId());
                    if (position == null) {
                        if (turn.messageId() != null) {
                            track.turnPositionByMessageId.put(turn.messageId(), track.turns.size());
                        }
                        track.turns.add(turn);
                    } else {
                        track.turns.set(position, continueTurn(track.turns.get(position), turn));
                    }
                    if (turn.stopReason() != null) {
                        track.lastTurnStopReason = turn.stopReason();
                    }
                }
            }

            if (message instanceof UserMessage userMsg) {
                if (!track.isMainLoop() && track.promptText == null) {
                    // The first text a sub-agent receives is the prompt it was started with.
                    track.promptText = userText(userMsg);
                }
                List<ContentBlock> blocks = userMsg.getContentAsBlocks();
                if (blocks != null) {
                    for (ContentBlock block : blocks) {
                        if (block instanceof ToolResultBlock resultBlock) {
                            String content = resultBlock.getContentAsString();
                            if (content == null && resultBlock.content() != null) {
                                content = resultBlock.content().toString();
                            }
                            String resultToolName = toolUseNames.getOrDefault(resultBlock.toolUseId(), "?");
                            Long startMs = toolUseStartMs.get(resultBlock.toolUseId());
                            // J4: this was already being computed and thrown away after the log
                            // line. It is the dwell time — persist it.
                            long elapsedMs = startMs != null ? System.currentTimeMillis() - startMs : -1;
                            track.toolResults.add(new ToolResultRecord(
                                    resultBlock.toolUseId(),
                                    content,
                                    Boolean.TRUE.equals(resultBlock.isError()),
                                    elapsedMs));
                            // The result of a spawning tool call can carry the sub-agent's final
                            // status and totals; lifecycle messages, when present, take precedence.
                            readSpawnResult(rawRoot, subagentTracks.get(resultBlock.toolUseId()));
                            final String fContent = content;
                            final long fElapsed = elapsedMs;
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
                                    w -> w.writeToolResult(resultBlock.toolUseId(), err, fContent, source, fElapsed));
                        }
                    }
                }
            }
        }

        // Thinking tokens, best source first:
        //   1. the result's own usage.thinking_tokens, when the CLI reports one;
        //   2. Σ per-turn usage.output_tokens_details.thinking_tokens — EXACT, and on the wire all
        //      along. Before 1.9.0 this was skipped straight to the estimate below;
        //   3. only then the ~4 chars/token estimate over captured thinking blocks.
        if (thinkingTokens == 0 && !main.turns.isEmpty()) {
            long fromTurns = main.turns.stream().mapToLong(TurnUsage::thinkingTokens).sum();
            if (fromTurns > 0) {
                thinkingTokens = (int) fromTurns;
            }
        }
        if (thinkingTokens == 0 && !main.thinkingBlocks.isEmpty()) {
            int totalChars = main.thinkingBlocks.stream().mapToInt(String::length).sum();
            thinkingTokens = totalChars / 4;
        }

        // J3: resolve the stop reason even when no terminal ResultMessage arrived (an aborted or
        // truncated stream), falling back to the last turn's own wire stop_reason.
        StopReason stopReason = ClaudeStopReasons.resolveRunStopReason(resultSubtype, isError, sawResult,
                main.lastTurnStopReason);
        int effectiveMaxTurns = callerMaxTurns != UNKNOWN_MAX_TURNS ? callerMaxTurns : wireMaxTurns;

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
                main.text.toString(),
                main.thinkingBlocks,
                main.toolUses,
                rawResult,
                main.toolResults,
                main.turns,
                modelCosts,
                stopReason,
                effectiveMaxTurns,
                toSubagentCaptures(main, subagentTracks),
                tracksAvailable,
                reportedSubagentStats
        );
    }

    /**
     * Joins a later line of an assistant message onto the turn its earlier lines made: the tool
     * calls are added, and the usage is the later line's, since each line repeats the message's
     * usage and adding them up would count it once per content block.
     */
    private static TurnUsage continueTurn(TurnUsage earlier, TurnUsage later) {
        List<String> toolUseIds = new ArrayList<>(earlier.toolUseIds() != null ? earlier.toolUseIds() : List.of());
        if (later.toolUseIds() != null) {
            toolUseIds.addAll(later.toolUseIds());
        }
        return new TurnUsage(
                earlier.messageId(),
                later.model() != null ? later.model() : earlier.model(),
                later.inputTokens(),
                later.outputTokens(),
                later.cacheCreationInputTokens(),
                later.cacheReadInputTokens(),
                toolUseIds,
                later.thinkingTokens(),
                later.stopReason() != null ? later.stopReason() : earlier.stopReason(),
                earlier.turnIndex());
    }

    /** The text of a user message: the string itself, or its text blocks joined. */
    private static String userText(UserMessage userMsg) {
        String text = userMsg.getContentAsString();
        if (text != null) {
            return text;
        }
        List<ContentBlock> blocks = userMsg.getContentAsBlocks();
        if (blocks == null) {
            return null;
        }
        StringBuilder joined = new StringBuilder();
        for (ContentBlock block : blocks) {
            if (block instanceof TextBlock textBlock) {
                joined.append(textBlock.text());
            }
        }
        return joined.length() > 0 ? joined.toString() : null;
    }

    /**
     * Reads what Claude Code's task lifecycle messages say about a sub-agent: its kind and
     * description when it starts, and its status and totals when it ends. A message about a
     * sub-agent none of whose own messages arrived is ignored, so it creates no empty capture.
     */
    private static void readSubagentLifecycle(SystemMessage systemMsg, Map<String, Track> subagentTracks,
            Map<String, String> spawnToolUseIdByTaskId) {
        String subtype = systemMsg.subtype();
        Map<String, Object> data = systemMsg.data();
        if (subtype == null || data == null || !subtype.startsWith("task_")) {
            return;
        }
        String taskId = asText(data.get("task_id"));
        String spawnToolUseId = asText(data.get("tool_use_id"));
        if (spawnToolUseId != null && taskId != null) {
            spawnToolUseIdByTaskId.put(taskId, spawnToolUseId);
        } else if (spawnToolUseId == null && taskId != null) {
            spawnToolUseId = spawnToolUseIdByTaskId.get(taskId);
        }
        if (spawnToolUseId == null) {
            return;
        }
        if ("task_started".equals(subtype)) {
            // Its own messages follow; keep what it was started as for when they arrive.
            Track track = subagentTracks.computeIfAbsent(spawnToolUseId, Track::new);
            track.subagentType = asText(data.get("subagent_type"));
            track.description = asText(data.get("description"));
            if (data.get("spawn_depth") instanceof Number depth) {
                track.reportedDepth = depth.intValue();
            }
            track.backgrounded = Boolean.TRUE.equals(data.get("is_backgrounded"));
            if (track.promptText == null) {
                track.promptText = asText(data.get("prompt"));
            }
            return;
        }
        Track track = subagentTracks.get(spawnToolUseId);
        if (track == null) {
            return;
        }
        if ("task_notification".equals(subtype)) {
            String status = asText(data.get("status"));
            if (status != null) {
                track.status = status;
                track.statusSource = "task_notification";
            }
            if (data.get("usage") instanceof Map<?, ?> usage) {
                track.reportedTotalTokens = asLong(usage.get("total_tokens"), track.reportedTotalTokens);
                track.reportedToolUses = asLong(usage.get("tool_uses"), track.reportedToolUses);
                track.reportedDurationMs = asLong(usage.get("duration_ms"), track.reportedDurationMs);
            }
        }
    }

    /**
     * Reads the sub-agent summary that Claude Code attaches to the result of a spawning tool
     * call. It fills only what the lifecycle messages left unknown.
     */
    private static void readSpawnResult(JsonNode rawRoot, Track subagent) {
        if (rawRoot == null || subagent == null) {
            return;
        }
        JsonNode summary = rawRoot.path("tool_use_result");
        if (!summary.isObject()) {
            return;
        }
        if (subagent.status == null && textOrNull(summary, "status") != null) {
            subagent.status = textOrNull(summary, "status");
            subagent.statusSource = "tool_use_result";
        }
        if (subagent.agentId == null) {
            subagent.agentId = textOrNull(summary, "agentId");
        }
        if (subagent.subagentType == null) {
            subagent.subagentType = textOrNull(summary, "agentType");
        }
        if (subagent.reportedTotalTokens < 0 && summary.path("totalTokens").isNumber()) {
            subagent.reportedTotalTokens = summary.path("totalTokens").asLong();
        }
        if (subagent.reportedToolUses < 0 && summary.path("totalToolUseCount").isNumber()) {
            subagent.reportedToolUses = summary.path("totalToolUseCount").asLong();
        }
        if (subagent.reportedDurationMs < 0 && summary.path("totalDurationMs").isNumber()) {
            subagent.reportedDurationMs = summary.path("totalDurationMs").asLong();
        }
    }

    /**
     * Turns the sub-agent tracks into captures, in the order the sub-agents were first seen, and
     * places each under the agent whose tool call started it.
     */
    private static List<SubagentCapture> toSubagentCaptures(Track main, Map<String, Track> subagentTracks) {
        if (subagentTracks.isEmpty()) {
            return List.of();
        }
        // Which track issued each tool call, to find a sub-agent's parent.
        Map<String, Track> issuerByToolUseId = new java.util.HashMap<>();
        List<Track> all = new ArrayList<>(subagentTracks.values());
        all.add(0, main);
        for (Track track : all) {
            for (ToolUseRecord use : track.toolUses) {
                issuerByToolUseId.put(use.id(), track);
            }
        }
        List<SubagentCapture> captures = new ArrayList<>();
        for (Track track : subagentTracks.values()) {
            if (!track.sawOwnMessage) {
                // Announced by a lifecycle message, but none of its own messages arrived. The
                // spawning tool call is then reported by PhaseCapture.subagentsWithoutTrack().
                continue;
            }
            Track issuer = issuerByToolUseId.get(track.spawnToolUseId);
            captures.add(new SubagentCapture(
                    track.spawnToolUseId,
                    issuer == null || issuer.isMainLoop() ? null : issuer.spawnToolUseId,
                    track.reportedDepth > 0 ? track.reportedDepth : depthOf(track, issuerByToolUseId),
                    track.agentId,
                    track.subagentType,
                    track.description,
                    track.promptText,
                    track.status,
                    track.statusSource,
                    track.backgrounded,
                    track.reportedTotalTokens,
                    track.reportedToolUses,
                    track.reportedDurationMs,
                    track.text.toString(),
                    track.thinkingBlocks,
                    track.toolUses,
                    track.toolResults,
                    track.turns));
        }
        return captures;
    }

    /** Counts the spawning tool calls between a sub-agent and the main loop; -1 if the chain is broken. */
    private static int depthOf(Track track, Map<String, Track> issuerByToolUseId) {
        int depth = 0;
        Track current = track;
        while (current != null && !current.isMainLoop()) {
            depth++;
            if (depth > issuerByToolUseId.size() + 1) {
                return -1;
            }
            current = issuerByToolUseId.get(current.spawnToolUseId);
        }
        return current == null ? -1 : depth;
    }

    private static String asText(Object value) {
        return value instanceof String text && !text.isEmpty() ? text : null;
    }

    private static long asLong(Object value, long fallback) {
        return value instanceof Number number ? number.longValue() : fallback;
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
        } catch (IOException | RuntimeException ex) {
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
    private static TurnUsage parseTurnUsage(JsonNode rawRoot, List<String> toolUseIds, int turnIndex) {
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
                usage.path("cache_read_input_tokens").asLong(0),
                List.copyOf(toolUseIds),
                // Nested one level deeper than the rest of the vector, which is why it was
                // missed: usage.output_tokens_details.thinking_tokens. A subset of output_tokens.
                usage.path("output_tokens_details").path("thinking_tokens").asLong(0),
                textOrNull(msg, "stop_reason"),
                turnIndex);
    }

    /**
     * This turn's identity for stamping onto its tool calls — the wire {@code message.id}.
     * Null when the raw wire is unavailable (programmatic construction / SDK &lt; 1.3.0).
     */
    private static String turnIdOf(JsonNode rawRoot) {
        return rawRoot == null ? null : textOrNull(rawRoot.path("message"), "id");
    }

    /**
     * Probes the raw wire for a reported turn ceiling.
     *
     * <p>
     * Claude Code does <strong>not</strong> currently emit one — {@code maxTurns} is a caller-side
     * {@code QueryOptions} value and is never echoed back — so in practice this returns
     * {@link #UNKNOWN_MAX_TURNS} and the caller-supplied value is what gets recorded. It probes
     * the handful of places a future CLI version would plausibly put it (the init/system envelope
     * carries session options) so that if the CLI ever starts reporting the ceiling, capture picks
     * it up without a code change. It never guesses: an absent field yields "unknown", not a
     * default.
     *
     * @param rawRoot the parsed wire line, or null
     * @return the reported ceiling, or {@link #UNKNOWN_MAX_TURNS}
     */
    private static int parseMaxTurns(JsonNode rawRoot) {
        if (rawRoot == null) {
            return UNKNOWN_MAX_TURNS;
        }
        for (JsonNode scope : new JsonNode[] { rawRoot, rawRoot.path("options"), rawRoot.path("data") }) {
            if (scope == null || scope.isMissingNode()) {
                continue;
            }
            for (String key : new String[] { "max_turns", "maxTurns" }) {
                JsonNode v = scope.path(key);
                if (v.isIntegralNumber()) {
                    return v.asInt();
                }
            }
        }
        return UNKNOWN_MAX_TURNS;
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
