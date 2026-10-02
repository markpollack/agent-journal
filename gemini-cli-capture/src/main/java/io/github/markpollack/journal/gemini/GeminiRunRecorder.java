package io.github.markpollack.journal.gemini;

import io.github.markpollack.journal.Run;
import io.github.markpollack.journal.derived.StepCostEvent;
import io.github.markpollack.journal.event.CostBreakdown;
import io.github.markpollack.journal.event.CustomEvent;
import io.github.markpollack.journal.event.LLMCallEvent;
import io.github.markpollack.journal.event.TimingInfo;
import io.github.markpollack.journal.event.TokenUsage;
import io.github.markpollack.journal.trace.JournalStep;

import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Logs Gemini CLI {@link GeminiPhaseCapture}s as events on an open {@link Run}. Use it after
 * {@link GeminiSessionParser} has parsed a query result: create one around the run and call
 * {@link #recordPhase(GeminiPhaseCapture)} once per phase. It does not end the run, so close the
 * run yourself, and call {@link Run#fail(Throwable)} first if the work failed.
 *
 * <p>Unlike Claude Code's {@code RunRecorder}, it logs no phase
 * {@link io.github.markpollack.journal.event.StateChangeEvent} and no thinking events, it never
 * ends the run, and it does not check that the storage keeps derived events. The Gemini SDK
 * reports no tool calls, so it logs no tool call events either: the whole query is one step,
 * which carries the whole cost. On
 * {@link io.github.markpollack.journal.storage.InMemoryStorage} that step's cost is kept only in
 * memory and is lost, without a warning, when the JVM exits; use
 * {@link io.github.markpollack.journal.storage.JsonFileStorage} to keep it.
 *
 * <p>A recorder serves one run and is not safe for use from several threads.
 *
 * <p>Example:
 * <pre>{@code
 * Journal.configure(new JsonFileStorage(Path.of(".agent-journal")));
 * GeminiPhaseCapture capture = GeminiSessionParser.parse(queryResult, "execute", prompt);
 * try (Run run = Journal.run("my-exp").start()) {
 *     new GeminiRunRecorder(run).recordPhase(capture);
 * }
 * }</pre>
 */
public class GeminiRunRecorder {

    private final Run run;

    /**
     * Creates a recorder that logs to the given run.
     *
     * @param run the open run to log to
     */
    public GeminiRunRecorder(Run run) {
        this.run = run;
    }

    /**
     * Logs one phase as events on the run, then logs its cost as one derived event.
     *
     * <p>It logs, in order: a {@code prompt} custom event with the phase name and the prompt, if
     * the prompt was captured; and one {@link LLMCallEvent} with the capture's model (or
     * {@code "unknown"}), its prompt and completion tokens, its total cost and its duration. The
     * event names no provider, and its metadata holds the phase name, status and error flag. Then
     * it logs one {@link StepCostEvent} for the whole query, with step ID {@code <runId>:turn}
     * and the whole cost, marked
     * {@link io.github.markpollack.journal.trace.AttributionMethod#OUTPUT_TOKEN_PROPORTIONAL}.
     *
     * @param phase the parsed Gemini query; with a {@code null} phase name, the {@code phase}
     *        attribute of the prompt event is left out
     * @throws IllegalStateException if the run has ended
     * @throws UnsupportedOperationException if the run's storage cannot keep derived events at
     *         all; the other events are logged by then
     */
    public void recordPhase(GeminiPhaseCapture phase) {
        // Prompt capture — record the exact prompt sent for reproducibility.
        if (phase.promptText() != null && !phase.promptText().isEmpty()) {
            run.logEvent(CustomEvent.of("prompt",
                    phaseAttributes(phase.phaseName(), "text", phase.promptText())));
        }

        // LLM call with token/cost/timing data. Gemini's typed model has no thinking tokens, so
        // that slot is 0, and no separate API duration, so the API time is the total, as for the
        // other recorders that know only the total.
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("phaseName", phase.phaseName());
        metadata.put("status", phase.status());
        metadata.put("isError", phase.isError());

        run.logEvent(LLMCallEvent.builder()
                .model(phase.model() != null ? phase.model() : "unknown")
                .tokenUsage(TokenUsage.of(phase.promptTokens(), phase.completionTokens(), 0))
                .cost(CostBreakdown.of(phase.totalCostUsd()))
                .timing(TimingInfo.of(phase.durationMs()))
                .metadata(metadata)
                .build());

        // Derived per-step cost → analysis.jsonl (mirrors the Claude path). One turn-level step
        // takes 100% of the run cost; the synthesized stepId ({runId}:turn) is the join key.
        Instant analyzedAt = Instant.now();
        for (JournalStep step : GeminiJournalSteps.fromPhaseCapture(phase, run.id())) {
            run.logDerivedEvent(StepCostEvent.fromStep(step, analyzedAt));
        }
    }

    /**
     * Returns the run this recorder logs to.
     *
     * @return the run given to the constructor
     */
    public Run run() {
        return run;
    }

    // Not Map.of, which throws NullPointerException for a capture with no phase name; such a
    // capture is recorded without the phase, since attributes must not hold null values.
    private static Map<String, Object> phaseAttributes(String phaseName, String key, Object value) {
        Map<String, Object> attributes = new LinkedHashMap<>();
        if (phaseName != null) {
            attributes.put("phase", phaseName);
        }
        attributes.put(key, value);
        return attributes;
    }
}
