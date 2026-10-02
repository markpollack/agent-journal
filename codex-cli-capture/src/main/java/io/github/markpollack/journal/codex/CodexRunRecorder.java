package io.github.markpollack.journal.codex;

import io.github.markpollack.journal.Run;
import io.github.markpollack.journal.derived.StepCostEvent;
import io.github.markpollack.journal.event.CostBreakdown;
import io.github.markpollack.journal.event.CustomEvent;
import io.github.markpollack.journal.event.LLMCallEvent;
import io.github.markpollack.journal.event.TimingInfo;
import io.github.markpollack.journal.event.ToolCallEvent;
import io.github.markpollack.journal.trace.JournalStep;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Logs Codex CLI {@link CodexPhaseCapture}s as events on an open {@link Run}. Use it after
 * {@link CodexSessionParser} has parsed a rollout file: create one around the run and call
 * {@link #recordPhase(CodexPhaseCapture)} once per phase. It does not end the run, so close the
 * run yourself, and call {@link Run#fail(Throwable)} first if the work failed.
 *
 * <p>Unlike Claude Code's {@code RunRecorder}, it logs no phase
 * {@link io.github.markpollack.journal.event.StateChangeEvent} and no thinking events, it never
 * ends the run, and it does not check that the storage keeps derived events. It also has no
 * method that returns the run, so keep your own reference. Codex reports no cost, so every cost
 * it logs is 0 and is marked as not available. On
 * {@link io.github.markpollack.journal.storage.InMemoryStorage} the per-step records are kept only
 * in memory and are lost, without a warning, when the JVM exits; use
 * {@link io.github.markpollack.journal.storage.JsonFileStorage} to keep them.
 *
 * <p>A recorder serves one run and is not safe for use from several threads.
 *
 * <p>Example:
 * <pre>{@code
 * Journal.configure(new JsonFileStorage(Path.of(".agent-journal")));
 * CodexPhaseCapture capture = CodexSessionParser.parse(rolloutFile, "execute", prompt);
 * try (Run run = Journal.run("my-exp").start()) {
 *     new CodexRunRecorder(run).recordPhase(capture);
 * }
 * }</pre>
 */
public final class CodexRunRecorder {

    private final Run run;

    /**
     * Creates a recorder that logs to the given run.
     *
     * @param run the open run to log to
     */
    public CodexRunRecorder(Run run) {
        this.run = run;
    }

    /**
     * Logs one phase as events on the run, then logs its per-step records as derived events.
     *
     * <p>It logs, in order: a {@code prompt} custom event with the phase name and the prompt, if
     * the prompt was captured; one {@link LLMCallEvent} with provider {@code "openai"}, the
     * capture's model (or {@code "unknown"}), its token usage, a cost of 0 and its duration; and
     * one {@link ToolCallEvent} per tool call, with its ID, name, kind, input, output and error.
     * The metadata of the LLM call event holds the phase name, error flag, session ID and CLI
     * version, and marks the cost as unreported ({@code costAvailable=false}). Then it logs one
     * {@link StepCostEvent} per tool call, with a cost of 0, marked
     * {@link io.github.markpollack.journal.trace.AttributionMethod#EVEN_SPLIT}. A phase with no
     * tool calls has no derived events.
     *
     * @param phase the parsed Codex session; a {@code null} phase name is recorded as {@code null}
     * @throws IllegalStateException if the run has ended
     * @throws UnsupportedOperationException if the phase has tool calls and the run's storage
     *         cannot keep derived events at all; the other events are logged by then
     */
    public void recordPhase(CodexPhaseCapture phase) {
        if (phase.promptText() != null && !phase.promptText().isEmpty()) {
            run.logEvent(CustomEvent.of("prompt",
                    phaseAttributes(phase.phaseName(), "text", phase.promptText())));
        }

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("phaseName", phase.phaseName());
        metadata.put("isError", phase.isError());
        metadata.put("costAvailable", false);
        metadata.put("costSource", "unreported");
        if (phase.sessionId() != null) {
            metadata.put("sessionId", phase.sessionId());
        }
        if (phase.cliVersion() != null) {
            metadata.put("cliVersion", phase.cliVersion());
        }

        run.logEvent(LLMCallEvent.builder()
                .provider("openai")
                .model(phase.model() != null ? phase.model() : "unknown")
                .tokenUsage(phase.tokenUsage())
                .cost(CostBreakdown.of(0.0))
                .timing(TimingInfo.of(phase.durationMs()))
                .metadata(metadata)
                .build());

        for (CodexToolUseRecord tool : phase.toolUses()) {
            run.logEvent(ToolCallEvent.builder()
                    .id(tool.id())
                    .toolName(tool.name())
                    .kind(tool.kind())
                    .input(tool.input())
                    .output(tool.output())
                    .success(!tool.isError())
                    .errorMessage(tool.errorMessage())
                    .build());
        }

        Instant analyzedAt = Instant.now();
        for (JournalStep step : CodexJournalSteps.fromPhaseCapture(phase, run.id())) {
            run.logDerivedEvent(StepCostEvent.fromStep(step, analyzedAt));
        }
    }

    // A LinkedHashMap, not Map.of, so a capture with no phase name is recorded rather than
    // throwing NullPointerException.
    private static Map<String, Object> phaseAttributes(String phaseName, String key, Object value) {
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("phase", phaseName);
        attributes.put(key, value);
        return attributes;
    }
}
