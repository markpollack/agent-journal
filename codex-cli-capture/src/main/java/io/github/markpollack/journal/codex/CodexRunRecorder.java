package io.github.markpollack.journal.codex;

import io.github.markpollack.journal.Journal;
import io.github.markpollack.journal.Run;
import io.github.markpollack.journal.storage.SourceRecordings;
import io.github.markpollack.journal.RunBuilder;
import io.github.markpollack.journal.RunStatus;
import io.github.markpollack.journal.derived.StepCostEvent;
import io.github.markpollack.journal.event.CostBreakdown;
import io.github.markpollack.journal.event.CustomEvent;
import io.github.markpollack.journal.event.LLMCallEvent;
import io.github.markpollack.journal.event.TimingInfo;
import io.github.markpollack.journal.event.ToolCallEvent;
import io.github.markpollack.journal.trace.JournalStep;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
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

    /** The source kind a Codex execution is recorded under; see {@link SourceRecordings}. */
    public static final String SOURCE_KIND = "codex";

    /**
     * Records a Codex execution once: a new run for the capture, with its sub-agent runs, unless a
     * run in the experiment already carries the execution's source key. The key is the root
     * thread ID ({@link CodexPhaseCapture#sessionId()}), so the same rollouts processed again
     * write nothing and return the earlier run. A different execution, with another thread ID,
     * records normally. A resumed Codex thread keeps its ID, so its later turns are not recorded
     * and the earlier run is returned; resume is not supported for sub-agent capture. If the
     * earlier run has not ended, or ended with an error recorded, this method throws instead of
     * recording, since that recording may be incomplete and is not resumed. The check reads
     * {@link Journal#storage()}, so it holds across restarts within one experiment and storage;
     * see {@link SourceRecordings} for its limits.
     *
     * @param experimentId the experiment to record into
     * @param phase the parsed execution; it must have a session ID
     * @param configure extra configuration for the new run, such as the model, or {@code null}
     * @return the run that records the execution, and whether it was written by this call
     * @throws IllegalArgumentException if the capture has no session ID
     * @throws IllegalStateException if an earlier recording of this execution has not ended or ended
     *         with an error (remove that run first)
     */
    public static SourceRecordings.Outcome recordOnce(String experimentId, CodexPhaseCapture phase,
            java.util.function.UnaryOperator<RunBuilder> configure) {
        if (phase.sessionId() == null || phase.sessionId().isBlank()) {
            throw new IllegalArgumentException("A Codex capture without a session ID cannot be recorded once");
        }
        java.util.Optional<io.github.markpollack.journal.storage.RunData> earlier =
                SourceRecordings.find(experimentId, SOURCE_KIND, phase.sessionId());
        if (earlier.isPresent()) {
            SourceRecordings.requireComplete(earlier.get());
            return new SourceRecordings.Outcome(earlier.get().id(), false);
        }
        RunBuilder builder = SourceRecordings.newRecording(experimentId, SOURCE_KIND, phase.sessionId());
        if (configure != null) {
            builder = configure.apply(builder);
        }
        Run run = builder.start();
        try {
            new CodexRunRecorder(run).recordPhase(phase);
            run.finish(phase.isError() ? RunStatus.FAILED : RunStatus.FINISHED);
        } catch (RuntimeException e) {
            run.fail(e);
            throw e;
        }
        return new SourceRecordings.Outcome(run.id(), true);
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
     * @param phase the parsed Codex session; with a {@code null} phase name, the {@code phase}
     *        attribute of the prompt event is left out
     * @throws IllegalStateException if the run has ended
     * @throws UnsupportedOperationException if the phase has tool calls and the run's storage
     *         cannot keep derived events at all; the other events are logged by then
     */
    public void recordPhase(CodexPhaseCapture phase) {
        if (phase.promptText() != null && !phase.promptText().isEmpty()) {
            run.logEvent(CustomEvent.of("prompt",
                    phaseAttributes(phase.phaseName(), "text", phase.promptText())));
        }
        List<Map<String, Object>> subagentRuns = recordSubagents(phase);

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

        // Written only with sub-agent evidence (a spawn_agent call, a started activity or a child
        // rollout), so a phase without any is recorded exactly as before.
        boolean spawned = phase.toolUses().stream().anyMatch(use -> CodexJournalSteps.isSubagentSpawn(use.name()));
        if (spawned || phase.hasSubagents() || !phase.spawnedThreadIds().isEmpty()) {
            metadata.put(META_SUBAGENT_TRACKS_AVAILABLE, phase.subagentTracksAvailable());
            if (phase.subagentTracksAvailable()) {
                List<Map<String, Object>> withoutTrack = new ArrayList<>();
                for (CodexPhaseCapture.SpawnWithoutTrack spawn : phase.subagentsWithoutTrack()) {
                    Map<String, Object> entry = new LinkedHashMap<>();
                    entry.put("callId", spawn.callId());
                    entry.put("reason", spawn.reason());
                    withoutTrack.add(entry);
                }
                metadata.put(META_SUBAGENTS, subagentRuns);
                metadata.put(META_SUBAGENTS_WITHOUT_TRACK, withoutTrack);
            }
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
            run.logEvent(toolCallEvent(tool));
        }

        Instant analyzedAt = Instant.now();
        for (JournalStep step : CodexJournalSteps.fromPhaseCapture(phase, run.id())) {
            run.logDerivedEvent(StepCostEvent.fromStep(step, analyzedAt));
        }
    }

    /** Parent {@code llm_call} metadata key: the child runs written for this phase. */
    public static final String META_SUBAGENTS = "subagents";
    /** Parent {@code llm_call} metadata key: spawns without a child run, each with a reason. */
    public static final String META_SUBAGENTS_WITHOUT_TRACK = "subagentsWithoutTrack";
    /** Parent {@code llm_call} metadata key: whether child tracks could have been captured. */
    public static final String META_SUBAGENT_TRACKS_AVAILABLE = "subagentTracksAvailable";
    /** Child run config key: the parent's {@code spawn_agent} call id. */
    public static final String CONFIG_SPAWN_TOOL_USE_ID = "subagent.spawnToolUseId";
    /** Child run tag key; its value is {@link #TRACK_SUBAGENT}. */
    public static final String TAG_TRACK = "track";
    /** Child run tag value marking a sub-agent run. */
    public static final String TRACK_SUBAGENT = "subagent";
    /** Child run summary value of {@code subagent.statusMeaning}: Codex reports no thread-terminal status. */
    public static final String STATUS_MEANING_LAST_OBSERVED_TURN = "last_observed_turn";

    /**
     * Writes one run per captured sub-agent thread, before the parent's {@code llm_call}, and
     * returns the metadata entries that name them. A child of the root gets this recorder's run as
     * its {@code parentRunId}; a deeper child gets the run of the thread that spawned it, or this
     * recorder's run when that thread was not captured. A child whose recording fails is marked
     * failed and the exception is rethrown.
     */
    private List<Map<String, Object>> recordSubagents(CodexPhaseCapture phase) {
        if (!phase.hasSubagents() || !phase.subagentTracksAvailable()) {
            return List.of();
        }
        Map<String, String> runIdBySpawnCallId = new LinkedHashMap<>();
        List<Map<String, Object>> written = new ArrayList<>();
        List<CodexSubagentCapture> pending = new ArrayList<>(phase.subagents());
        while (!pending.isEmpty()) {
            boolean progressed = false;
            for (CodexSubagentCapture subagent : new ArrayList<>(pending)) {
                String parentSpawn = subagent.parentSpawnCallId();
                if (parentSpawn == null || runIdBySpawnCallId.containsKey(parentSpawn)) {
                    String parentRunId = parentSpawn == null ? run.id() : runIdBySpawnCallId.get(parentSpawn);
                    written.add(recordSubagent(phase, subagent, parentRunId, runIdBySpawnCallId));
                    pending.remove(subagent);
                    progressed = true;
                }
            }
            if (!progressed) {
                for (CodexSubagentCapture subagent : pending) {
                    written.add(recordSubagent(phase, subagent, run.id(), runIdBySpawnCallId));
                }
                pending.clear();
            }
        }
        return written;
    }

    private Map<String, Object> recordSubagent(CodexPhaseCapture phase, CodexSubagentCapture subagent,
            String parentRunId, Map<String, String> runIdBySpawnCallId) {
        RunBuilder builder = Journal.run(run.experiment().id())
                .parentRun(parentRunId)
                .tag(TAG_TRACK, TRACK_SUBAGENT)
                .config("subagent.threadId", subagent.threadId())
                .config("subagent.depth", subagent.depth())
                .config("subagent.forked", subagent.forked());
        if (subagent.spawnCallId() != null) {
            builder.config(CONFIG_SPAWN_TOOL_USE_ID, subagent.spawnCallId());
        }
        if (subagent.agentPath() != null) {
            builder.config("subagent.agentPath", subagent.agentPath());
            builder.agent(subagent.agentPath());
        }
        if (phase.sessionId() != null) {
            builder.config("subagent.sessionId", phase.sessionId()); // the root thread id
        }
        if (subagent.model() != null) {
            builder.config("model", subagent.model());
        }
        Run child = builder.start();
        if (subagent.spawnCallId() != null) {
            runIdBySpawnCallId.put(subagent.spawnCallId(), child.id());
        }
        try {
            if (subagent.promptText() != null && !subagent.promptText().isEmpty()) {
                child.logEvent(CustomEvent.of("prompt",
                        phaseAttributes(phase.phaseName(), "text", subagent.promptText())));
            }
            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("phaseName", phase.phaseName());
            metadata.put("isError", !"completed".equals(subagent.status()));
            metadata.put("costAvailable", false);
            metadata.put("costSource", "unreported");
            if (phase.sessionId() != null) {
                metadata.put("sessionId", phase.sessionId());
            }
            if (subagent.cliVersion() != null) {
                metadata.put("cliVersion", subagent.cliVersion());
            }
            child.logEvent(LLMCallEvent.builder()
                    .provider("openai")
                    .model(subagent.model() != null ? subagent.model() : "unknown")
                    .tokenUsage(subagent.tokenUsage())
                    .cost(CostBreakdown.of(0.0))
                    .timing(TimingInfo.of(Math.max(0L, subagent.durationMs())))
                    .metadata(metadata)
                    .build());
            for (CodexToolUseRecord tool : subagent.toolUses()) {
                child.logEvent(toolCallEvent(tool));
            }
            Instant analyzedAt = Instant.now();
            for (JournalStep step : CodexJournalSteps.forSubagent(subagent, child.id())) {
                child.logDerivedEvent(StepCostEvent.fromStep(step, analyzedAt));
            }
            child.setSummary("subagent.status", subagent.status());
            child.setSummary("subagent.statusSource", subagent.statusSource());
            child.setSummary("subagent.statusMeaning", STATUS_MEANING_LAST_OBSERVED_TURN);
            child.setSummary("subagent.replayedRecordsSkipped", subagent.replayedRecordsSkipped());
            child.finish(switch (subagent.status()) {
                case "completed" -> RunStatus.FINISHED;
                case "interrupted" -> RunStatus.FAILED;
                default -> RunStatus.CRASHED; // end not observed; the status text is in the summary
            });
        } catch (RuntimeException e) {
            child.fail(e);
            throw e;
        }
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("spawnToolUseId", subagent.spawnCallId());
        entry.put("runId", child.id());
        if (subagent.agentPath() != null) {
            entry.put("agentId", subagent.agentPath());
        }
        entry.put("depth", subagent.depth());
        entry.put("status", subagent.status());
        return entry;
    }

    private static ToolCallEvent toolCallEvent(CodexToolUseRecord tool) {
        return ToolCallEvent.builder()
                .id(tool.id())
                .toolName(tool.name())
                .kind(tool.kind())
                .input(tool.input())
                .output(tool.output())
                .durationMs(-1) // not measured
                .success(!tool.isError())
                .errorMessage(tool.errorMessage())
                .build();
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
