package io.github.markpollack.journal.trace;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.github.markpollack.journal.event.StopReason;

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
 * Writes a capture trace: a JSON Lines file with one line for each thing that happened in one
 * agent call, such as a tool call, a tool result, some text or the final result. Two capture
 * modules write traces with it when you give their session parser a trace file: Claude Code's
 * {@code SessionLogParser} and {@code GeminiSessionParser}. Use it directly only to write the same
 * format from your own capture code. A trace is separate from the journal's files: it is not an
 * event stream, and {@link io.github.markpollack.journal.storage.JournalStorage} does not read it.
 *
 * <p>The constructor creates the file and writes a {@code header} line. Each {@code write} method
 * then appends one line and flushes it, so the file can be followed while the agent runs. Every
 * line has {@code ts} (when it was written), {@code seq} (0 for the header, then counting up) and
 * {@code type}. The line types are {@code header}, {@code tool_use}, {@code tool_result},
 * {@code text}, {@code thinking}, {@code result}, {@code raw} and {@code step_cost}; each
 * method's comment lists the fields it writes. The format is schema version
 * {@value #SCHEMA_VERSION}. Fields are added to it over time but not renamed, so a reader should
 * ignore fields and line types it does not know.
 *
 * <p>The trace is flat: lines have no parent ID, so nesting is not recorded. A step that started
 * a sub-agent is only marked, with {@code subagentSpawn} on its {@code step_cost} line; the
 * sub-agent's own steps are not in the trace. The run ID given to the constructor is written on
 * the {@code header}, {@code result} and {@code step_cost} lines. The parsers write a trace before
 * any {@link io.github.markpollack.journal.Run} exists, so they pass the phase name as the run ID;
 * the {@code runId} of such a trace is not a journal run ID.
 *
 * <p>{@link TraceContentMode} sets how much text, thinking and tool-result content is kept, and
 * {@link TraceRawMode} whether {@code raw} lines are written. Content lengths are always the
 * original lengths, even when the content is shortened or left out. Traces can hold prompts, file
 * contents, command output and secrets, so treat them as sensitive files. The writer does not
 * check the path it is given.
 *
 * <p>Close the writer when you are done, for example with try-with-resources; writing after
 * {@link #close()} throws {@link IOException}. A writer is not safe for use from several threads.
 */
public class TraceWriter implements Closeable {

    /**
     * The most characters of one content item that {@link TraceContentMode#TRUNCATED} keeps:
     * 60,000. The limit applies to what is written; the whole content is still held in memory.
     */
    public static final int MAX_TRACE_CONTENT_CHARS = 60_000;

    /**
     * The schema version written on the header line of every trace: 2. The journal's event files
     * have their own version, which is unrelated.
     */
    public static final int SCHEMA_VERSION = 2;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final BufferedWriter writer;

    private final AtomicInteger seq = new AtomicInteger(0);

    private final TraceContentMode contentMode;

    private final TraceRawMode rawMode;

    private final String runId;

    /**
     * Creates the trace file, which will have no {@code raw} lines, and writes its header line.
     * Same as {@link #TraceWriter(Path, TraceContentMode, String, String, TraceRawMode)} with
     * {@link TraceRawMode#NONE}.
     *
     * @param traceFile the file to write; its path must include a parent directory
     * @param contentMode how much content to keep; {@code null} means
     *        {@link TraceContentMode#TRUNCATED}
     * @param runId the run ID to record, or {@code null}
     * @param phase the phase name to record, or {@code null}
     * @throws IOException if the directories or the file cannot be created, or the header cannot
     *         be written
     */
    public TraceWriter(Path traceFile, TraceContentMode contentMode, String runId, String phase) throws IOException {
        this(traceFile, contentMode, runId, phase, TraceRawMode.NONE);
    }

    /**
     * Creates the trace file and writes its header line. Missing parent directories are created,
     * and an existing file at {@code traceFile} is replaced. The header line has
     * {@code schemaVersion}, {@code runId} and {@code phase} when they are not {@code null}, and
     * the names of the content mode and raw mode.
     *
     * <p>The path must include a parent directory, such as {@code traces/plan.jsonl} or an
     * absolute path; a bare file name such as {@code plan.jsonl} is not accepted. If the header
     * cannot be written, the file is closed before the exception is thrown.
     *
     * @param traceFile the file to write; its path must include a parent directory
     * @param contentMode how much text, thinking and tool-result content to keep; {@code null}
     *        means {@link TraceContentMode#TRUNCATED}
     * @param runId the run ID to write on the {@code header}, {@code result} and
     *        {@code step_cost} lines, or {@code null} to leave it out
     * @param phase the phase name to write on the header line, or {@code null} to leave it out
     * @param rawMode whether {@link #writeRaw(String)} writes lines; {@code null} means
     *        {@link TraceRawMode#NONE}
     * @throws IOException if the directories or the file cannot be created, or the header cannot
     *         be written
     */
    public TraceWriter(Path traceFile, TraceContentMode contentMode, String runId, String phase, TraceRawMode rawMode)
            throws IOException {
        this.contentMode = contentMode != null ? contentMode : TraceContentMode.TRUNCATED;
        this.rawMode = rawMode != null ? rawMode : TraceRawMode.NONE;
        this.runId = runId;
        Files.createDirectories(traceFile.getParent());
        this.writer = Files.newBufferedWriter(traceFile);
        try {
            Map<String, Object> line = baseLine("header");
            line.put("schemaVersion", SCHEMA_VERSION);
            if (runId != null) {
                line.put("runId", runId);
            }
            if (phase != null) {
                line.put("phase", phase);
            }
            line.put("contentMode", this.contentMode.name());
            line.put("rawMode", this.rawMode.name());
            writeLine(line);
        } catch (IOException | RuntimeException ex) {
            // Do not leak the open file when the constructor fails
            try {
                this.writer.close();
            } catch (IOException suppressed) {
                ex.addSuppressed(suppressed);
            }
            throw ex;
        }
    }

    /**
     * Writes a {@code tool_use} line without turn details. Same as
     * {@link #writeToolUse(String, String, Map, int, String)} with a turn index of -1 and no turn
     * ID.
     *
     * @param name the vendor's name for the tool
     * @param id the vendor's ID for the tool call
     * @param input the tool's input parameters, or {@code null}
     * @throws IOException if the line cannot be written
     */
    public void writeToolUse(String name, String id, Map<String, Object> input) throws IOException {
        writeToolUse(name, id, input, -1, null);
    }

    /**
     * Writes a {@code tool_use} line for a tool call the agent made. The line has {@code name},
     * {@code id}, {@code input} (an empty object when {@code input} is {@code null}),
     * {@code turnIndex}, and {@code turnId} when it is not {@code null}. The input is written
     * whole in every {@link TraceContentMode}.
     *
     * @param name the vendor's name for the tool, such as {@code "Bash"}
     * @param id the vendor's ID for the tool call, which the matching {@code tool_result} line
     *        repeats
     * @param input the tool's input parameters, or {@code null}
     * @param turnIndex the 0-based number of the model turn that made the call, or -1 if not known
     * @param turnId the ID of that turn (the assistant message ID), or {@code null} if not known
     * @throws IOException if the line cannot be written
     */
    public void writeToolUse(String name, String id, Map<String, Object> input, int turnIndex, String turnId)
            throws IOException {
        Map<String, Object> line = baseLine("tool_use");
        line.put("name", name);
        line.put("id", id);
        line.put("input", input != null ? input : Map.of());
        line.put("turnIndex", turnIndex);
        if (turnId != null) {
            line.put("turnId", turnId);
        }
        writeLine(line);
    }

    /**
     * Writes a {@code tool_result} line without a duration. Same as
     * {@link #writeToolResult(String, boolean, String, Map, long)} with a duration of -1.
     *
     * @param id the ID of the tool call this result belongs to
     * @param isError whether the tool reported an error
     * @param content the result text, or {@code null}
     * @param source where the full content can be found, or {@code null}
     * @throws IOException if the line cannot be written
     */
    public void writeToolResult(String id, boolean isError, String content, Map<String, Object> source)
            throws IOException {
        writeToolResult(id, isError, content, source, -1L);
    }

    /**
     * Writes a {@code tool_result} line for the result of a tool call. The line has {@code id},
     * {@code isError}, {@code durationMs} and {@code contentLength} (the original length; 0 for
     * {@code null}). Unless the content mode is {@link TraceContentMode#LENGTHS}, it also has
     * {@code content} and {@code truncated}, and, when the content was shortened, {@code source}.
     *
     * @param id the ID of the tool call this result belongs to
     * @param isError whether the tool reported an error
     * @param content the result text, or {@code null}, which is written as empty content
     * @param source where the full content can be found, such as a {@code file_path} or
     *        {@code command} entry; written only when the content was shortened; may be
     *        {@code null}
     * @param durationMs the time from the tool call to this result, in milliseconds, or -1 if not
     *        measured
     * @throws IOException if the line cannot be written
     */
    public void writeToolResult(String id, boolean isError, String content, Map<String, Object> source,
            long durationMs) throws IOException {
        Map<String, Object> line = baseLine("tool_result");
        line.put("id", id);
        line.put("isError", isError);
        line.put("durationMs", durationMs);
        line.put("contentLength", content != null ? content.length() : 0);
        boolean truncated = putContent(line, content);
        if (truncated && source != null) {
            line.put("source", source);
        }
        writeLine(line);
    }

    /**
     * Writes a {@code text} line for text the agent wrote. The line has {@code length} (0 for
     * {@code null}) and, unless the content mode is {@link TraceContentMode#LENGTHS},
     * {@code content} and {@code truncated}.
     *
     * @param text the text, or {@code null}
     * @throws IOException if the line cannot be written
     */
    public void writeText(String text) throws IOException {
        Map<String, Object> line = baseLine("text");
        line.put("length", text != null ? text.length() : 0);
        putContent(line, text);
        writeLine(line);
    }

    /**
     * Writes a {@code thinking} line for a thinking block. The line has {@code length}, the
     * content as for {@link #writeText(String)}, and {@code hasSignature}. A line with length 0
     * and {@code hasSignature} {@code true} records a block that arrived empty, for example
     * because the vendor redacted it, not one that the capture dropped.
     *
     * @param thinking the thinking text, or {@code null}
     * @param hasSignature whether the block came with a signature
     * @throws IOException if the line cannot be written
     */
    public void writeThinking(String thinking, boolean hasSignature) throws IOException {
        Map<String, Object> line = baseLine("thinking");
        line.put("length", thinking != null ? thinking.length() : 0);
        putContent(line, thinking);
        line.put("hasSignature", hasSignature);
        writeLine(line);
    }

    /**
     * Writes a {@code result} line with the totals of the agent call. The line always has
     * {@code inputTokens}, {@code outputTokens}, {@code costUsd} (rounded to six decimal places),
     * {@code numTurns} and {@code durationMs}. When {@code meta} is not {@code null}, it adds
     * {@code sessionId} and {@code subtype} when they are not {@code null}, {@code isError},
     * {@code durationApiMs}, {@code thinkingTokens}, {@code cacheCreationInputTokens},
     * {@code cacheReadInputTokens}, {@code stopReason} together with {@code maxTurns}, and
     * {@code structuredOutput} when it is not {@code null}. Last comes {@code runId}, if the
     * writer has one.
     *
     * @param inputTokens the input tokens reported for the call
     * @param outputTokens the output tokens reported for the call
     * @param costUsd the total cost in US dollars; must be a finite number
     * @param numTurns the number of turns
     * @param durationMs the wall-clock time of the call, in milliseconds
     * @param meta further details, or {@code null} to write only the totals
     * @throws IOException if the line cannot be written
     */
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
            // Written as a pair, always: numTurns is uninterpretable without the ceiling it ran
            // against, and the ceiling means nothing without knowing whether it was hit. A
            // maxTurns of -1 records "no ceiling reported", which is itself information.
            line.put("stopReason", (meta.stopReason() != null ? meta.stopReason() : StopReason.UNKNOWN).name());
            line.put("maxTurns", meta.maxTurns());
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
     * Writes a {@code raw} line holding one vendor message exactly as it arrived, if the raw mode
     * is {@link TraceRawMode#FULL} and {@code rawJson} is not {@code null}; otherwise does
     * nothing. Raw lines keep fields that the other lines leave out. The message is written under
     * {@code raw}: as JSON when it parses, and otherwise as the original string, with a
     * {@code rawParseError} field giving the parse error. It is never shortened or redacted.
     *
     * @param rawJson the message's original JSON text, or {@code null} when the vendor SDK did not
     *        keep it
     * @throws IOException if the line cannot be written; text that is not valid JSON is not an
     *         error
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
     * Writes a {@code step_cost} line with one step's share of the cost. A step's share can be
     * worked out only once the total is known, so the parsers write these lines after the
     * {@code result} line. The line has {@code stepId}; {@code turnId} and {@code toolName} when
     * they are not {@code null}; {@code inputTokens}, {@code outputTokens},
     * {@code thinkingTokens}, {@code cacheCreationTokens}, {@code cacheReadTokens},
     * {@code turnIndex} and {@code durationMs}; {@code attributedCostUsd} and
     * {@code actualRunCostUsd}, rounded to six decimal places; {@code attributionMethod} when it
     * is not {@code null}; {@code isError}; {@code subagentSpawn} only when the step started a
     * sub-agent; and the writer's {@code runId}, if it has one. The step's own run ID, vendor and
     * agent state are not written.
     *
     * <p>For a tool call, {@code stepId} is the {@code id} of its {@code tool_use} line. A run
     * recorder stores the same values as a
     * {@link io.github.markpollack.journal.derived.StepCostEvent}.
     *
     * @param step the step to write; must not be {@code null}
     * @throws IOException if the line cannot be written
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
        line.put("thinkingTokens", step.thinkingTokens());
        line.put("cacheCreationTokens", step.cacheCreationTokens());
        line.put("cacheReadTokens", step.cacheReadTokens());
        line.put("turnIndex", step.turnIndex());
        line.put("durationMs", step.durationMs());
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
     * Details of an agent call that {@link TraceWriter#writeResult} adds to the {@code result}
     * line, after the totals. Each component is written under its own name; the
     * {@code writeResult} comment says which ones are left out when {@code null}. The stop reason
     * and the turn limit are always written together: a turn count means little without the limit
     * and whether it was reached.
     *
     * @param sessionId the vendor's session ID, or {@code null}
     * @param isError whether the call ended in error
     * @param subtype the vendor's result subtype or status, such as {@code "success"}, or
     *        {@code null}
     * @param durationApiMs the time spent in API requests, in milliseconds, or 0 if not reported
     * @param thinkingTokens the thinking tokens
     * @param cacheCreationInputTokens the tokens written to the prompt cache
     * @param cacheReadInputTokens the tokens read from the prompt cache
     * @param structuredOutput the structured output the vendor returned, written as JSON, or
     *        {@code null}
     * @param stopReason why the call stopped, or {@code null}, which is written as
     *        {@link StopReason#UNKNOWN}
     * @param maxTurns the turn limit the call ran against, or -1 if none was reported
     */
    public record ResultMeta(String sessionId, boolean isError, String subtype, long durationApiMs, int thinkingTokens,
            int cacheCreationInputTokens, int cacheReadInputTokens, Object structuredOutput, StopReason stopReason,
            int maxTurns) {

        /**
         * Creates details without a stop reason or turn limit, for code written before 1.9.0. The
         * stop reason becomes {@link StopReason#UNKNOWN} and {@code maxTurns} becomes -1.
         *
         * @param sessionId the session ID, or {@code null}
         * @param isError whether the call ended in error
         * @param subtype the result subtype or status, or {@code null}
         * @param durationApiMs the API time, in milliseconds
         * @param thinkingTokens the thinking tokens
         * @param cacheCreationInputTokens the tokens written to the prompt cache
         * @param cacheReadInputTokens the tokens read from the prompt cache
         * @param structuredOutput the structured output, or {@code null}
         */
        public ResultMeta(String sessionId, boolean isError, String subtype, long durationApiMs, int thinkingTokens,
                int cacheCreationInputTokens, int cacheReadInputTokens, Object structuredOutput) {
            this(sessionId, isError, subtype, durationApiMs, thinkingTokens, cacheCreationInputTokens,
                    cacheReadInputTokens, structuredOutput, StopReason.UNKNOWN, -1);
        }
    }

}
