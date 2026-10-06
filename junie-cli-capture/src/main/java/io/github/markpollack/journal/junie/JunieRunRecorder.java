package io.github.markpollack.journal.junie;

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
 * Logs Junie CLI {@link JuniePhaseCapture}s as events on an open {@link Run}. Use it after
 * {@link JunieSessionParser} has parsed a session file: create one around the run and call
 * {@link #recordPhase(JuniePhaseCapture)} once per phase. It does not end the run, so close the
 * run yourself, and call {@link Run#fail(Throwable)} first if the work failed.
 *
 * <p>Unlike Claude Code's {@code RunRecorder}, it logs no phase
 * {@link io.github.markpollack.journal.event.StateChangeEvent}, it never ends the run, and it
 * does not check that the storage keeps derived events. Like it, it logs one
 * {@code thinking_block} event per thinking block, and it logs the cost Junie reported together
 * with the stop reason and the turn limit, which for Junie is always -1. It has no method that
 * returns the run, so keep your own reference. On
 * {@link io.github.markpollack.journal.storage.InMemoryStorage} the per-step costs are kept only
 * in memory and are lost, without a warning, when the JVM exits; use
 * {@link io.github.markpollack.journal.storage.JsonFileStorage} to keep them.
 *
 * <p>A recorder serves one run and is not safe for use from several threads.
 *
 * <p>Example:
 * <pre>{@code
 * Journal.configure(new JsonFileStorage(Path.of(".agent-journal")));
 * JuniePhaseCapture capture = JunieSessionParser.parse(eventsFile, "execute", prompt);
 * try (Run run = Journal.run("my-exp").start()) {
 *     new JunieRunRecorder(run).recordPhase(capture);
 * }
 * }</pre>
 */
public final class JunieRunRecorder {

    private final Run run;

    /**
     * Creates a recorder that logs to the given run.
     *
     * @param run the open run to log to
     */
    public JunieRunRecorder(Run run) {
        this.run = run;
    }

    /**
     * Logs one phase as events on the run, then logs its per-step costs as derived events.
     *
     * <p>It logs, in order: a {@code prompt} custom event with the phase name and the prompt, if
     * the prompt was captured; one {@link LLMCallEvent} with provider {@code "jetbrains"}, the
     * capture's model (or {@code "unknown"}), its token usage, total cost and duration, and
     * Junie's error code (such as {@code "Submit"}) as the finish reason; one
     * {@link ToolCallEvent} per tool step, with its ID, name, kind, input, output and error; and
     * one {@code thinking_block} custom event per thinking block.
     *
     * <p>The metadata of the LLM call event holds the phase name, the number of model calls, the
     * error flag, the stop reason, the turn limit (-1), and the task ID, task state and error code
     * when they are known. It holds the context-window use and size when Junie reported a size.
     * {@code costAvailable} is {@code true} when Junie reported per-call costs or a non-zero total
     * cost, and {@code costReconciles} says whether per-call costs were reported and add up to the
     * total; see
     * {@link JuniePhaseCapture#reconcilesToModelCosts()}. {@code agentKind} is always
     * {@code "MainAgent"}.
     *
     * <p>Then it logs one {@link StepCostEvent} per tool step, each with an equal share of the
     * total cost, marked {@link io.github.markpollack.journal.trace.AttributionMethod#EVEN_SPLIT}.
     * Any rounding remainder goes to the last step, so the shares add up to the total. A phase with
     * no tool steps has no derived events.
     *
     * @param phase the parsed Junie session; with a {@code null} phase name, the {@code phase}
     *        attribute of the prompt and thinking events is left out
     * @throws IllegalStateException if the run has ended
     * @throws UnsupportedOperationException if the phase has tool steps and the run's storage
     *         cannot keep derived events at all; the other events are logged by then
     */
    public void recordPhase(JuniePhaseCapture phase) {
        if (phase.promptText() != null && !phase.promptText().isEmpty()) {
            run.logEvent(CustomEvent.of("prompt",
                    phaseAttributes(phase.phaseName(), "text", phase.promptText())));
        }

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("phaseName", phase.phaseName());
        metadata.put("numLlmCalls", phase.numLlmCalls());
        metadata.put("isError", phase.isError());
        // Junie prices every LLM call and its per-call costs reconcile to the session total, so
        // unlike the Grok/Codex/Antigravity adapters this is a reported cost, not an absence. A
        // non-zero total can only have come from Junie, so it counts as reported even when the
        // per-call costs are missing; costReconciles still says whether those were present.
        boolean costReported = phase.hasModelCosts() || phase.totalCostUsd() != 0.0;
        metadata.put("costAvailable", costReported);
        metadata.put("costSource", costReported ? "reported" : "unreported");
        metadata.put("costReconciles", phase.reconcilesToModelCosts());
        // The stop reason and the ceiling it ran against, written together and unconditionally
        // (1.9.0 rule). Junie enforces no ceiling, so maxTurns is always -1 = "not reported".
        metadata.put("stopReason", phase.stopReason().name());
        metadata.put("maxTurns", phase.maxTurns());
        if (phase.taskId() != null) {
            metadata.put("taskId", phase.taskId());
        }
        if (phase.taskState() != null) {
            metadata.put("taskState", phase.taskState());
        }
        if (phase.errorCode() != null) {
            metadata.put("errorCode", phase.errorCode());
        }
        if (phase.contextWindowSize() > 0) {
            metadata.put("contextWindowUsed", phase.contextWindowUsed());
            metadata.put("contextWindowSize", phase.contextWindowSize());
        }
        // Carried through so a later subagent model has the evidence it needs. Every event in
        // every captured trace reports the main agent; nothing here builds a subagent model.
        metadata.put("agentKind", "MainAgent");

        run.logEvent(LLMCallEvent.builder()
                .provider("jetbrains")
                .model(phase.model() != null ? phase.model() : "unknown")
                .tokenUsage(phase.tokenUsage())
                .cost(CostBreakdown.of(phase.totalCostUsd()))
                .timing(TimingInfo.of(phase.durationMs()))
                .finishReason(phase.errorCode())
                .metadata(metadata)
                .build());

        for (JunieToolUseRecord tool : phase.toolUses()) {
            run.logEvent(ToolCallEvent.builder()
                    .id(tool.id())
                    .toolName(tool.name())
                    .kind(tool.kind())
                    .input(tool.input())
                    .output(tool.output())
                    .durationMs(-1) // not measured
                    .success(!tool.isError())
                    .errorMessage(tool.errorMessage())
                    .build());
        }

        for (String thinking : phase.thinkingBlocks()) {
            run.logEvent(CustomEvent.of("thinking_block",
                    phaseAttributes(phase.phaseName(), "content", thinking)));
        }

        Instant analyzedAt = Instant.now();
        for (JournalStep step : JunieJournalSteps.fromPhaseCapture(phase, run.id())) {
            run.logDerivedEvent(StepCostEvent.fromStep(step, analyzedAt));
        }
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
