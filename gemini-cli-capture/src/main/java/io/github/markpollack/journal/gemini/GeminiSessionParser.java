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
 * Extracts a Gemini CLI {@link QueryResult} into a {@link GeminiPhaseCapture}, optionally
 * writing the shared portable JSONL trace. The Gemini analog of {@code claude-code-capture}'s
 * {@code SessionLogParser} — same pipeline shape (parse → capture → trace + trailing
 * {@code step_cost}), feeding the <em>same</em> journal-core {@link TraceWriter}.
 *
 * <p>
 * Differences from the Claude parser, driven by Gemini's coarser SDK model (see
 * {@link GeminiPhaseCapture}):
 * <ul>
 *   <li>input is a synchronous {@link QueryResult} (a {@code List<Message>} + {@code Metadata}),
 *       not a streaming message iterator;</li>
 *   <li>no {@code thinking}/{@code tool_use}/{@code tool_result} lines — Gemini's typed model has
 *       only text messages + result-level usage/cost; the trace is {@code header} +
 *       {@code text}(s) + {@code result} + {@code step_cost};</li>
 *   <li>no {@code rawMode} — the typed {@code Message} carries no verbatim wire envelope.</li>
 * </ul>
 * The {@code result} line still carries the loader-contract keys
 * ({@code inputTokens}/{@code outputTokens}/{@code costUsd}/{@code durationMs}), so the same
 * Markov/ACT loader reads a Gemini trace.
 */
public final class GeminiSessionParser {

    private static final Logger logger = LoggerFactory.getLogger(GeminiSessionParser.class);

    private GeminiSessionParser() {
    }

    public static GeminiPhaseCapture parse(QueryResult result, String phaseName, String promptText) {
        return parse(result, phaseName, promptText, null);
    }

    public static GeminiPhaseCapture parse(QueryResult result, String phaseName, String promptText, Path traceFile) {
        return parse(result, phaseName, promptText, traceFile, TraceContentMode.TRUNCATED);
    }

    public static GeminiPhaseCapture parse(QueryResult result, String phaseName, String promptText, Path traceFile,
            TraceContentMode contentMode) {
        TraceWriter trace = null;
        if (traceFile != null) {
            try {
                trace = new TraceWriter(traceFile, contentMode, phaseName, phaseName);
            } catch (IOException ex) {
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
