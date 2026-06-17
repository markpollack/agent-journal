package io.github.markpollack.journal.trace;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.BufferedWriter;
import java.io.Closeable;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Writes one JSON line per event to a JSONL trace file (schema v2). Each line is
 * flushed immediately so {@code tail -f} works during long-running agent sessions.
 *
 * <p>
 * This is the <strong>vendor-neutral</strong> portable writer (owned by {@code journal-core},
 * R2.7): per-vendor extractors (Claude Code, and later Gemini/Codex) project their captures
 * into these line types rather than each owning a writer.
 *
 * <p>
 * Execution line types: {@code header} (first line, run identity), {@code tool_use},
 * {@code tool_result}, {@code text}, {@code thinking}, {@code result}; plus the verbatim
 * {@code raw} escape hatch ({@code rawMode}) and the derived {@code step_cost} analysis line.
 *
 * <p>
 * <strong>Additive evolution contract:</strong> the Markov analysis loader
 * (markov-agent-analysis {@code loaders.py}) depends on {@code tool_use.name},
 * {@code tool_use.input}, the last {@code result} line's
 * {@code costUsd}/{@code durationMs}/{@code inputTokens}/{@code outputTokens}, and line
 * ordering. Those keys are preserved byte-for-byte; every v2 field is an addition.
 * Never rename existing keys.
 *
 * <p>
 * Content capture is governed by {@link TraceContentMode}. The canonical length fields
 * ({@code length}, {@code contentLength}) always carry the original, pre-truncation
 * size; there is deliberately no separate {@code originalLength} field. Thinking lines
 * carry {@code hasSignature} so an upstream-redacted block ({@code length:0,
 * hasSignature:true}) is self-diagnosing — content is recorded as it arrived, never
 * fabricated.
 *
 * <p>
 * Serialization uses Jackson, which escapes all control characters correctly — the
 * hand-rolled escaper this replaced covered only {@code \ " \n \r \t} and silently
 * produced malformed lines that downstream loaders skipped. Note: the JSONL trace and
 * the journal-event metadata maps ({@code BaseRunRecorder}, {@code PhaseCaptureSources})
 * are two intentionally separate projections of a vendor capture (e.g. {@code PhaseCapture});
 * do not unify them casually.
 */
public class TraceWriter implements Closeable {

    /**
     * Per-item content cap in {@link TraceContentMode#TRUNCATED} mode — matches Claude
     * Code's own OTel 60KB-per-item truncation. A disk guarantee only; full content
     * still transits memory.
     */
    public static final int MAX_TRACE_CONTENT_CHARS = 60_000;

    public static final int SCHEMA_VERSION = 2;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final BufferedWriter writer;

    private final AtomicInteger seq = new AtomicInteger(0);

    private final TraceContentMode contentMode;

    private final TraceRawMode rawMode;

    private final String runId;

    /**
     * Back-compat constructor: raw capture disabled ({@link TraceRawMode#NONE}).
     */
    public TraceWriter(Path traceFile, TraceContentMode contentMode, String runId, String phase) throws IOException {
        this(traceFile, contentMode, runId, phase, TraceRawMode.NONE);
    }

    public TraceWriter(Path traceFile, TraceContentMode contentMode, String runId, String phase, TraceRawMode rawMode)
            throws IOException {
        Files.createDirectories(traceFile.getParent());
        this.writer = Files.newBufferedWriter(traceFile);
        this.contentMode = contentMode;
        this.rawMode = rawMode != null ? rawMode : TraceRawMode.NONE;
        this.runId = runId;
        Map<String, Object> line = baseLine("header");
        line.put("schemaVersion", SCHEMA_VERSION);
        if (runId != null) {
            line.put("runId", runId);
        }
        if (phase != null) {
            line.put("phase", phase);
        }
        line.put("contentMode", contentMode.name());
        line.put("rawMode", this.rawMode.name());
        writeLine(line);
    }

    public void writeToolUse(String name, String id, Map<String, Object> input) throws IOException {
        Map<String, Object> line = baseLine("tool_use");
        line.put("name", name);
        line.put("id", id);
        line.put("input", input != null ? input : Map.of());
        writeLine(line);
    }

    /**
     * @param source pointer to where the full content lives (file path or command),
     * written to the line only when the content is actually truncated; may be null
     */
    public void writeToolResult(String id, boolean isError, String content, Map<String, Object> source)
            throws IOException {
        Map<String, Object> line = baseLine("tool_result");
        line.put("id", id);
        line.put("isError", isError);
        line.put("contentLength", content != null ? content.length() : 0);
        boolean truncated = putContent(line, content);
        if (truncated && source != null) {
            line.put("source", source);
        }
        writeLine(line);
    }

    public void writeText(String text) throws IOException {
        Map<String, Object> line = baseLine("text");
        line.put("length", text != null ? text.length() : 0);
        putContent(line, text);
        writeLine(line);
    }

    public void writeThinking(String thinking, boolean hasSignature) throws IOException {
        Map<String, Object> line = baseLine("thinking");
        line.put("length", thinking != null ? thinking.length() : 0);
        putContent(line, thinking);
        line.put("hasSignature", hasSignature);
        writeLine(line);
    }

    public void writeResult(int inputTokens, int outputTokens, double costUsd, int numTurns, long durationMs,
            ResultMeta meta) throws IOException {
        Map<String, Object> line = baseLine("result");
        // The 5 Markov-contract keys, byte-for-byte (costUsd stays 6dp)
        line.put("inputTokens", inputTokens);
        line.put("outputTokens", outputTokens);
        line.put("costUsd", BigDecimal.valueOf(costUsd).setScale(6, RoundingMode.HALF_UP));
        line.put("numTurns", numTurns);
        line.put("durationMs", durationMs);
        if (meta != null) {
            if (meta.sessionId() != null) {
                line.put("sessionId", meta.sessionId());
            }
            line.put("isError", meta.isError());
            if (meta.subtype() != null) {
                line.put("subtype", meta.subtype());
            }
            line.put("durationApiMs", meta.durationApiMs());
            line.put("thinkingTokens", meta.thinkingTokens());
            line.put("cacheCreationInputTokens", meta.cacheCreationInputTokens());
            line.put("cacheReadInputTokens", meta.cacheReadInputTokens());
            if (meta.structuredOutput() != null) {
                line.put("structuredOutput", meta.structuredOutput());
            }
        }
        if (runId != null) {
            line.put("runId", runId);
        }
        writeLine(line);
    }

    /**
     * Emits one verbatim {@code raw} line carrying the complete original vendor wire
     * message, so unmodeled fields ({@code permission_denials}, {@code modelUsage}, the
     * {@code isSidechain}/{@code parentUuid} sub-agent envelope) are never lost. A no-op
     * unless {@link TraceRawMode#FULL} is active and {@code rawJson} is non-null
     * (programmatic SDK construction yields null). The raw payload is embedded as parsed
     * JSON when well-formed (queryable, lossless round-trip); if it fails to parse it is
     * stored as the original string under {@code raw} plus a {@code rawParseError} — still
     * lossless. The {@code type:"raw"} line is silently skipped by the Markov loader.
     *
     * @param rawJson the verbatim wire line ({@code RegularMessage.rawJson}), or null
     */
    public void writeRaw(String rawJson) throws IOException {
        if (rawMode != TraceRawMode.FULL || rawJson == null) {
            return;
        }
        Map<String, Object> line = baseLine("raw");
        try {
            JsonNode node = MAPPER.readTree(rawJson);
            line.put("raw", node);
        } catch (IOException ex) {
            // Wire JSON is expected to be valid; if it ever isn't, keep it verbatim
            // as a string rather than dropping it.
            line.put("raw", rawJson);
            line.put("rawParseError", ex.getMessage());
        }
        writeLine(line);
    }

    /**
     * Emits a {@code step_cost} line — a <strong>derived, post-execution analysis artifact</strong>,
     * not part of the immutable execution history. Per-step cost can only be computed once the run
     * finishes (the total arrives on the last {@code result} line), so these lines trail the
     * execution lines rather than mutating the streaming {@code tool_use} line. Joined back to the
     * execution stream by {@code stepId} (the tool_use id) / {@code turnId}.
     *
     * <p>
     * This is the first of an emerging derived-analysis line family (future: {@code step_score},
     * {@code markov_state}, {@code judge_verdict}), all keyed by {@code stepId}. {@code attributionMethod}
     * is embedded so a later reader knows how the cost was allocated; {@code actualRunCostUsd} is the
     * ground-truth total carried alongside the allocated {@code attributedCostUsd} so the two are never
     * confused. Additive and additive-safe — the Markov loader skips unknown line types.
     */
    public void writeStepCost(JournalStep step) throws IOException {
        Map<String, Object> line = baseLine("step_cost");
        line.put("stepId", step.stepId());
        if (step.turnId() != null) {
            line.put("turnId", step.turnId());
        }
        if (step.toolName() != null) {
            line.put("toolName", step.toolName());
        }
        line.put("inputTokens", step.inputTokens());
        line.put("outputTokens", step.outputTokens());
        line.put("attributedCostUsd", BigDecimal.valueOf(step.attributedCostUsd()).setScale(6, RoundingMode.HALF_UP));
        line.put("actualRunCostUsd", BigDecimal.valueOf(step.actualRunCostUsd()).setScale(6, RoundingMode.HALF_UP));
        if (step.attributionMethod() != null) {
            line.put("attributionMethod", step.attributionMethod().name());
        }
        line.put("isError", step.isError());
        if (step.isSubagentSpawn()) {
            // Sub-agent boundary marker (R2.5a): the interior steps are not in the stream —
            // they are archived from subagents/*.jsonl (R2.5b). Never flatten a spawn.
            line.put("subagentSpawn", true);
        }
        if (runId != null) {
            line.put("runId", runId);
        }
        writeLine(line);
    }

    /**
     * Adds {@code content}/{@code truncated} to the line per the content mode.
     * @return whether the content was truncated
     */
    private boolean putContent(Map<String, Object> line, String content) {
        if (contentMode == TraceContentMode.LENGTHS) {
            return false;
        }
        String body = content != null ? content : "";
        boolean truncate = contentMode == TraceContentMode.TRUNCATED && body.length() > MAX_TRACE_CONTENT_CHARS;
        line.put("content", truncate ? body.substring(0, MAX_TRACE_CONTENT_CHARS) : body);
        line.put("truncated", truncate);
        return truncate;
    }

    private Map<String, Object> baseLine(String type) {
        Map<String, Object> line = new LinkedHashMap<>();
        line.put("ts", Instant.now().toString());
        line.put("seq", seq.getAndIncrement());
        line.put("type", type);
        return line;
    }

    private void writeLine(Map<String, Object> line) throws IOException {
        writer.write(MAPPER.writeValueAsString(line));
        writer.newLine();
        writer.flush();
    }

    @Override
    public void close() throws IOException {
        writer.close();
    }

    /**
     * Enrichment fields for the result line beyond the 5 Markov-contract keys.
     * {@code structuredOutput} is likely null under stream-json invocation (it
     * populates with {@code --output-format json} / {@code --json-schema}) — captured
     * anyway for completeness.
     */
    public record ResultMeta(String sessionId, boolean isError, String subtype, long durationApiMs, int thinkingTokens,
            int cacheCreationInputTokens, int cacheReadInputTokens, Object structuredOutput) {
    }

}
