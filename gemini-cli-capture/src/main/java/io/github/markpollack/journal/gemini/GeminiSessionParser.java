package io.github.markpollack.journal.gemini;

import io.github.markpollack.agents.geminisdk.types.Cost;
import io.github.markpollack.agents.geminisdk.types.Message;
import io.github.markpollack.agents.geminisdk.types.MessageType;
import io.github.markpollack.agents.geminisdk.types.Metadata;
import io.github.markpollack.agents.geminisdk.types.QueryResult;
import io.github.markpollack.agents.geminisdk.types.ResultStatus;
import io.github.markpollack.agents.geminisdk.types.Usage;
import io.github.markpollack.journal.trace.JournalStep;
import io.github.markpollack.journal.trace.TraceContentMode;
import io.github.markpollack.journal.trace.TraceWriter;

import java.io.IOException;
import java.nio.file.Path;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Reads the result of one Gemini CLI query and returns a {@link GeminiPhaseCapture}: the agent's
 * text, its token usage and cost, how long it took, and its status. Use it after running a query
 * through the Gemini CLI SDK: pass the SDK's {@link QueryResult} to a {@code parse} method. To
 * store the result as a journal run, pass the capture to a {@link GeminiRunRecorder}.
 *
 * <p>Gemini is unlike the other non-Claude parsers: like Claude Code's {@code SessionLogParser},
 * it reads SDK objects, not JSON Lines text, and it can write a trace with {@link TraceWriter}.
 * Unlike Claude Code's parser, it gets one finished result, not a stream of messages, and that
 * result holds only text messages and totals: no tool calls, no thinking and no per-turn usage.
 * So a Gemini trace has only a {@code header} line, one {@code text} line per assistant message,
 * a {@code result} line and one {@code step_cost} line, and there is no raw mode.
 *
 * <p>The parser joins the text of the assistant messages, with nothing between them, and skips
 * all other messages, including error messages. It takes the model, duration, token counts and
 * cost from the result's metadata, and the status from the result. A trace's header records
 * {@code phaseName} as both the run ID and the phase, its result line records one turn, and its
 * {@code step_cost} line gives the whole cost to one step with ID {@code <phaseName>:turn}. A
 * trace file that cannot be opened, for any reason, including a bare file name with no parent
 * directory, is logged as a warning and does not stop parsing, and neither does an I/O error
 * while writing it; the capture is the same with or without a trace.
 *
 * <p>All methods are static and keep no state between calls. Calls from several threads are safe
 * if each has its own trace file.
 */
public final class GeminiSessionParser {

    private static final Logger logger = LoggerFactory.getLogger(GeminiSessionParser.class);

    private GeminiSessionParser() {
    }

    /**
     * Parses a query result into a capture, without writing a trace.
     *
     * @param result the SDK's result of the query
     * @param phaseName the caller's name for this query, such as {@code "plan"} or
     *        {@code "execute"}
     * @param promptText the prompt that was sent, or {@code null} if not captured
     * @return the capture, never {@code null}
     */
    public static GeminiPhaseCapture parse(QueryResult result, String phaseName, String promptText) {
        return parse(result, phaseName, promptText, null);
    }

    /**
     * Parses a query result into a capture and, if {@code traceFile} is not {@code null}, writes a
     * trace using {@link TraceContentMode#TRUNCATED}.
     *
     * @param result the SDK's result of the query
     * @param phaseName the caller's name for this query, such as {@code "plan"} or
     *        {@code "execute"}
     * @param promptText the prompt that was sent, or {@code null} if not captured
     * @param traceFile the trace file to write, or {@code null} for no trace
     * @return the capture, never {@code null}
     */
    public static GeminiPhaseCapture parse(QueryResult result, String phaseName, String promptText, Path traceFile) {
        return parse(result, phaseName, promptText, traceFile, TraceContentMode.TRUNCATED);
    }

    /**
     * Parses a query result into a capture and, if {@code traceFile} is not {@code null}, writes a
     * trace with the given content mode.
     *
     * @param result the SDK's result of the query
     * @param phaseName the caller's name for this query
     * @param promptText the prompt that was sent, or {@code null} if not captured
     * @param traceFile the trace file to write, or {@code null} for no trace
     * @param contentMode how much message content the trace keeps; {@code null} means
     *        {@link TraceContentMode#TRUNCATED}
     * @return the capture, never {@code null}
     */
    public static GeminiPhaseCapture parse(QueryResult result, String phaseName, String promptText, Path traceFile,
            TraceContentMode contentMode) {
        TraceWriter trace = null;
        if (traceFile != null) {
            try {
                trace = new TraceWriter(traceFile, contentMode, phaseName, phaseName);
            } catch (IOException | RuntimeException ex) {
                logger.warn("[{}] Failed to open trace file {}: {}", phaseName, traceFile, ex.getMessage());
            }
        }
        try {
            return doParse(result, phaseName, promptText, trace);
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

    private static GeminiPhaseCapture doParse(QueryResult result, String phaseName, String promptText,
            TraceWriter trace) {
        StringBuilder textOutput = new StringBuilder();

        if (result.messages() != null) {
            for (Message message : result.messages()) {
                if (message.getType() == MessageType.ASSISTANT) {
                    String content = message.getContent() != null ? message.getContent() : "";
                    textOutput.append(content);
                    writeTrace(trace, phaseName, w -> w.writeText(content));
                }
            }
        }

        Metadata metadata = result.metadata();
        Usage usage = metadata.usage();
        Cost cost = metadata.cost();
        ResultStatus status = result.status();
        boolean isError = status != ResultStatus.SUCCESS;
        int promptTokens = usage.promptTokens();
        int completionTokens = usage.completionTokens();
        int totalTokens = usage.totalTokens();
        long durationMs = metadata.duration() != null ? metadata.duration().toMillis() : 0L;
        double totalCostUsd = cost.totalCost() != null ? cost.totalCost().doubleValue() : 0.0;

        logger.info("[{}] Gemini complete: model={} {} in + {} out tokens, ${}, status={}", phaseName,
                metadata.model(), promptTokens, completionTokens, String.format("%.6f", totalCostUsd), status);

        final TraceWriter.ResultMeta meta = new TraceWriter.ResultMeta(null, isError, status.name(), 0L, 0, 0, 0, null);
        writeTrace(trace, phaseName,
                w -> w.writeResult(promptTokens, completionTokens, totalCostUsd, 1, durationMs, meta));

        GeminiPhaseCapture capture = new GeminiPhaseCapture(phaseName, promptText, metadata.model(), promptTokens,
                completionTokens, totalTokens, durationMs, totalCostUsd, isError, status.name(), textOutput.toString());

        // R2.4-parallel: emit trailing derived step_cost lines.
        if (trace != null) {
            for (JournalStep step : GeminiJournalSteps.fromPhaseCapture(capture, phaseName)) {
                writeTrace(trace, phaseName, w -> w.writeStepCost(step));
            }
        }

        return capture;
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
}
