package io.github.markpollack.journal.grok;

import io.github.markpollack.journal.Run;
import io.github.markpollack.journal.derived.StepCostEvent;
import io.github.markpollack.journal.event.CostBreakdown;
import io.github.markpollack.journal.event.CustomEvent;
import io.github.markpollack.journal.event.LLMCallEvent;
import io.github.markpollack.journal.event.ToolCallEvent;
import io.github.markpollack.journal.trace.JournalStep;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Logs Grok CLI {@link GrokPhaseCapture}s as events on an open {@link Run}. Use it after
 * {@link GrokSessionParser} has parsed a call: create one around the run and call
 * {@link #recordPhase(GrokPhaseCapture)} once per phase. It does not end the run, so close the run
 * yourself, and call {@link Run#fail(Throwable)} first if the work failed.
 *
 * <p>Unlike Claude Code's {@code RunRecorder}, it logs no phase
 * {@link io.github.markpollack.journal.event.StateChangeEvent} and no thinking events, it never
 * ends the run, and it does not check that the storage keeps derived events. On
 * {@link io.github.markpollack.journal.storage.InMemoryStorage} the per-step costs are kept only
 * in memory and are lost, without a warning, when the JVM exits; use
 * {@link io.github.markpollack.journal.storage.JsonFileStorage} to keep them.
 *
 * <p>A recorder serves one run and is not safe for use from several threads.
 *
 * <p>Example:
 * <pre>{@code
 * Journal.configure(new JsonFileStorage(Path.of(".agent-journal")));
 * GrokPhaseCapture capture = GrokSessionParser.parse(outputFile, "execute", prompt);
 * try (Run run = Journal.run("my-exp").start()) {
 *     new GrokRunRecorder(run).recordPhase(capture);
 * }
 * }</pre>
 */
public final class GrokRunRecorder {

    private final Run run;

    /**
     * Creates a recorder that logs to the given run.
     *
     * @param run the open run to log to
     */
    public GrokRunRecorder(Run run) {
        this.run = run;
    }

    /**
     * Logs one phase as events on the run, then logs its per-step costs as derived events.
     *
     * <p>It logs, in order: a {@code prompt} custom event with the phase name and the prompt, if
     * the prompt was captured; one {@link LLMCallEvent} with provider {@code "xai"}, the capture's
     * model (or {@code "unknown"}), its token usage and total cost, and Grok's stop reason as the
     * finish reason; and one {@link ToolCallEvent} per tool call, with its ID, name, kind, input,
     * output and error. The metadata of the LLM call event holds the phase name, turn count, error
     * flag and session ID, and marks the cost as reported by Grok ({@code costAvailable=true}).
     * Then it logs one {@link StepCostEvent} per tool call, each with an equal share of the total
     * cost, marked {@link io.github.markpollack.journal.trace.AttributionMethod#EVEN_SPLIT}. Any
     * rounding remainder goes to the last step, so the shares add up to the total. A phase with no
     * tool calls has no derived events.
     *
     * @param phase the parsed Grok call; with a {@code null} phase name, the {@code phase}
     *        attribute of the prompt event is left out
     * @throws IllegalStateException if the run has ended
     * @throws UnsupportedOperationException if the phase has tool calls and the run's storage
     *         cannot keep derived events at all; the other events are logged by then
     */
    public void recordPhase(GrokPhaseCapture phase) {
        if (phase.promptText() != null && !phase.promptText().isEmpty()) {
            run.logEvent(CustomEvent.of("prompt",
                    phaseAttributes(phase.phaseName(), "text", phase.promptText())));
        }

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("phaseName", phase.phaseName());
        metadata.put("numTurns", phase.numTurns());
        metadata.put("isError", phase.isError());
        metadata.put("costAvailable", true);
        metadata.put("costSource", "end.total_cost_usd");
        if (phase.sessionId() != null) {
            metadata.put("sessionId", phase.sessionId());
        }

        run.logEvent(LLMCallEvent.builder()
                .provider("xai")
                .model(phase.model() != null ? phase.model() : "unknown")
                .tokenUsage(phase.tokenUsage())
                .cost(CostBreakdown.of(phase.totalCostUsd()))
                .finishReason(phase.stopReason())
                .metadata(metadata)
                .build());

        for (GrokToolUseRecord tool : phase.toolUses()) {
            ToolCallEvent event = ToolCallEvent.builder()
                    .id(tool.id())
                    .toolName(tool.name())
                    .kind(tool.kind())
                    .input(tool.input())
                    .output(tool.output())
                    .durationMs(-1) // not measured
                    .success(!tool.isError())
                    .errorMessage(tool.errorMessage())
                    .build();
            run.logEvent(event);
        }

        Instant analyzedAt = Instant.now();
        for (JournalStep step : GrokJournalSteps.fromPhaseCapture(phase, run.id())) {
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
