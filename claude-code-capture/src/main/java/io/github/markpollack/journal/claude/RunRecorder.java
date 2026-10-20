package io.github.markpollack.journal.claude;

import io.github.markpollack.journal.Journal;
import io.github.markpollack.journal.Run;
import io.github.markpollack.journal.storage.SourceRecordings;
import io.github.markpollack.journal.RunBuilder;
import io.github.markpollack.journal.RunStatus;
import io.github.markpollack.journal.storage.JournalStorage;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Logs Claude Code {@link PhaseCapture}s as events on an open {@link Run}, then ends the run,
 * refusing to finish if the per-step costs it derived would be lost. Use it after
 * {@link SessionLogParser} has parsed a call: create one around the run, call
 * {@link #recordPhase(PhaseCapture)} once per phase, and end it with {@link #finish()}. The run
 * must write to storage that keeps derived events, normally
 * {@link io.github.markpollack.journal.storage.JsonFileStorage}.
 *
 * <p>For each phase, {@code recordPhase} logs a
 * {@link io.github.markpollack.journal.event.StateChangeEvent} from the previous phase (the first
 * starts from {@code "init"}), a {@code prompt} custom event if the prompt was captured, one
 * {@link io.github.markpollack.journal.event.LLMCallEvent} with the phase's token usage summed
 * over turns and its cost, one {@link io.github.markpollack.journal.event.ToolCallEvent} per tool
 * call, and one {@code thinking_block} custom event per thinking block. The model on the LLM call
 * event is the run's {@code model} config value, or {@code "unknown"}. It then logs one
 * {@link io.github.markpollack.journal.derived.StepCostEvent} per step as a derived event, with
 * the cost split as in {@link PhaseCapture#stepCosts()}.
 *
 * <p>Only {@link #finish()} records a completed run. {@link #close()} cannot tell a normal exit
 * from an exception leaving the try-with-resources block, so a recorder closed without
 * {@code finish()} ends its run {@code CRASHED} with {@code success=false}. This differs from
 * {@link Run#close()}, which ends a plain run {@code FINISHED}. To record a failure with its
 * cause, call {@link #failRun(Throwable)} inside the block. Up to 1.10.1 {@code close()} ended
 * the run {@code FINISHED} with {@code success=true} in every case, so in records written by
 * those versions {@code FINISHED} does not show that the recording completed.
 *
 * <p>When the recorder ends a run, it checks the storage. If it logged derived events and
 * {@link JournalStorage#persistsDerivedEvents()} is {@code false}, as for
 * {@link io.github.markpollack.journal.storage.InMemoryStorage}, it ends the run as not
 * successful and then throws {@link IllegalStateException}. Call {@link #lenient()} to get a
 * logged warning instead, when losing them is intended. A run that has already ended is not
 * checked. The recorders for other agent CLIs, such as {@code GrokRunRecorder}, make no such
 * check.
 *
 * <p>A recorder serves one run and is not safe for use from several threads.
 *
 * <p>Example:
 * <pre>{@code
 * Journal.configure(new JsonFileStorage(Path.of(".agent-journal")));
 * try (RunRecorder recorder = new RunRecorder(
 *         Journal.run("my-exp").config("model", model).start())) {
 *     recorder.recordPhase(capture);
 *     recorder.finish();
 * } // without finish(), close() ends the run as CRASHED
 * }</pre>
 */
public class RunRecorder extends BaseRunRecorder implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(RunRecorder.class);

    private final JournalStorage storage;
    private boolean lenient = false;
    private boolean finished = false;
    // Level 0 is taken by default when the run ends (Stage 13 package 1); a caller can point the recorder at
    // another projects root (tests) or switch the archive off.
    private java.nio.file.Path sessionFilesRoot = ClaudeSessionFiles.defaultProjectsRoot();
    private boolean archiveSessionFiles = true;

    /**
     * Creates a recorder for the run that checks the storage {@link Journal#storage()} returns
     * now. That is the storage the run writes to, unless {@link Journal#configure} was called
     * after the run started; in that case use {@link #RunRecorder(Run, JournalStorage)}.
     *
     * @param run the open run to log to
     */
    public RunRecorder(Run run) {
        this(run, Journal.storage());
    }

    /**
     * Creates a recorder for the run that checks the given storage. Pass the storage the run
     * writes to.
     *
     * @param run the open run to log to
     * @param storage the storage the run writes to; {@code null} counts as storage that does not
     *        keep derived events
     */
    public RunRecorder(Run run, JournalStorage storage) {
        this.currentRun = run;
        this.storage = storage;
    }

    /** The source kind a Claude Code execution is recorded under; see {@link SourceRecordings}. */
    public static final String SOURCE_KIND = "claude-code";

    /**
     * Returns the key under which {@link #recordOnce} records a capture: the session ID and the
     * ID of the final assistant message, joined by a colon. A later prompt in the same session
     * has another final message, so it is another execution.
     *
     * @param phase the capture
     * @return the key, or {@code null} if the capture has no session ID or no message ID
     */
    public static String sourceKeyOf(PhaseCapture phase) {
        String last = null;
        if (phase.hasTurns()) {
            for (TurnUsage turn : phase.turns()) {
                if (turn.messageId() != null) {
                    last = turn.messageId();
                }
            }
        }
        return phase.sessionId() == null || last == null ? null : phase.sessionId() + ":" + last;
    }

    /**
     * Records a Claude Code execution once: a new run for the capture, with its sub-agent runs,
     * unless a run in the experiment already carries the capture's source key
     * ({@link #sourceKeyOf}). The same stream processed again writes nothing and returns the
     * earlier run; another execution records normally. If the earlier run has not ended, this
     * method throws instead of recording. The check reads {@link Journal#storage()}, so it holds
     * across restarts within one experiment and storage; see {@link SourceRecordings} for its
     * limits. The run is ended with {@link #finish()}, or {@code FAILED} when the capture
     * reports an error.
     *
     * @param experimentId the experiment to record into
     * @param phase the capture; it needs a session ID and per-turn message IDs (a wire capture)
     * @param configure extra configuration for the new run, such as the model, or {@code null}
     * @return the run that records the execution, and whether it was written by this call
     * @throws IllegalArgumentException if {@link #sourceKeyOf} is {@code null} for the capture
     * @throws IllegalStateException if an earlier recording of this execution has not ended or ended
     *         with an error (remove that run first), or the storage check of {@link #finish()} fails
     */
    public static SourceRecordings.Outcome recordOnce(String experimentId, PhaseCapture phase,
            java.util.function.UnaryOperator<RunBuilder> configure) {
        String key = sourceKeyOf(phase);
        if (key == null) {
            throw new IllegalArgumentException(
                    "A capture without a session ID and a final message ID cannot be recorded once");
        }
        java.util.Optional<io.github.markpollack.journal.storage.RunData> earlier =
                SourceRecordings.find(experimentId, SOURCE_KIND, key);
        if (earlier.isPresent()) {
            SourceRecordings.requireComplete(earlier.get());
            return new SourceRecordings.Outcome(earlier.get().id(), false);
        }
        RunBuilder builder = SourceRecordings.newRecording(experimentId, SOURCE_KIND, key);
        if (configure != null) {
            builder = configure.apply(builder);
        }
        Run run = builder.start();
        RunRecorder recorder = new RunRecorder(run);
        try {
            recorder.recordPhase(phase);
            if (phase.isError()) {
                recorder.failRun();
            } else {
                recorder.finish();
            }
        } catch (RuntimeException e) {
            // Through the recorder, so the sources captured before the exception are secured with the failure
            // (Stage 13 review R13-01); the original exception is what the run records and what is thrown.
            recorder.failRun(e);
            throw e;
        }
        return new SourceRecordings.Outcome(run.id(), true);
    }

    /**
     * Secures the captured sources, then ends the run {@code FAILED} with the error, as
     * {@link BaseRunRecorder#failRun(Throwable)} does. A run that has already ended is left as it is; securing
     * never throws, so the error recorded is always this one.
     */
    @Override
    public void failRun(Throwable error) {
        if (currentRun != null && !currentRun.status().isTerminal()) {
            secureCapture(FINALIZATION_FAILED);
        }
        super.failRun(error);
        finished = true;
    }

    /** Secures the captured sources, then ends the run {@code FAILED}, as {@link BaseRunRecorder#failRun()} does. */
    @Override
    public void failRun() {
        if (currentRun != null && !currentRun.status().isTerminal()) {
            secureCapture(FINALIZATION_FAILED);
        }
        super.failRun();
        finished = true;
    }

    /**
     * Makes the storage check log a warning instead of throwing. Call it when the run is meant to
     * use storage that does not keep derived events, such as in-memory storage in a test.
     *
     * @return this recorder
     */
    public RunRecorder lenient() {
        this.lenient = true;
        return this;
    }

    /**
     * Returns the run this recorder logs to.
     *
     * @return the run given to the constructor
     */
    public Run run() {
        return currentRun;
    }

    /**
     * Ends the run {@code FINISHED}, after the storage check described in the class comment. If
     * the run has already ended, nothing is checked and its status does not change.
     *
     * @throws IllegalStateException if derived events were logged, the storage does not keep
     *         them, and {@link #lenient()} was not called; the run has then been ended
     *         {@code FAILED} with this exception as its error
     */
    /** Points the archive at another Claude Code projects root (the default is {@code ~/.claude/projects}). */
    public RunRecorder sessionFilesRoot(java.nio.file.Path root) {
        this.sessionFilesRoot = root;
        return this;
    }

    /** Switches the level-0 archive off; the run record then says the session files were not captured and why. */
    public RunRecorder archiveSessionFiles(boolean archive) {
        this.archiveSessionFiles = archive;
        return this;
    }

    public void finish() {
        if (!currentRun.status().isTerminal()) {
            secureCapture(FINALIZATION_FINISHED);
            try {
                verifyDerivedDurable();
            } catch (IllegalStateException e) {
                // End the run before reporting: a run left RUNNING is never saved with an end time.
                currentRun.fail(e);
                finished = true;
                throw e;
            }
            currentRun.finish(RunStatus.FINISHED);
        }
        finished = true;
    }

    /**
     * Ends the run {@code CRASHED} with {@code success=false} if it has not ended, then makes the
     * same storage check as {@link #finish()}. A {@code success} value the caller has already set
     * is kept. If the run has already ended, through {@link #finish()} or
     * {@link #failRun(Throwable)}, nothing is checked and nothing changes. A try-with-resources
     * block calls this method at its end.
     *
     * @throws IllegalStateException if the run had not ended, derived events were logged, the
     *         storage does not keep them, and {@link #lenient()} was not called; the run has then
     *         been ended {@code CRASHED}
     */
    @Override
    public void close() {
        if (currentRun == null) {
            return;
        }
        if (finished || currentRun.status().isTerminal()) {
            finished = true;
            return;
        }
        // The run ends without a finish: keep what was captured and say the recording was interrupted.
        secureCapture(FINALIZATION_INTERRUPTED);
        currentRun.finish(RunStatus.CRASHED);
        finished = true;
        verifyDerivedDurable();
    }

    /** {@code summary.capture.finalization}: the recorder's {@link #finish()} ended the run. */
    public static final String FINALIZATION_FINISHED = "finished";
    /** {@code summary.capture.finalization}: a {@code failRun} ended the run (a reported error or an exception). */
    public static final String FINALIZATION_FAILED = "failed";
    /** {@code summary.capture.finalization}: {@link #close()} ended the run without a finish. */
    public static final String FINALIZATION_INTERRUPTED = "interrupted";

    /** The level-1 omissions every Claude Code recording has today, stated on the capture summary (R13-06). */
    static final List<String> LEVEL1_OMISSIONS = List.of(
            "rate_limit_event lines are dropped by the released claude-code-sdk before Journal sees them",
            "the session transcript's own records (api-request, attachment, cost-state, ...) are archived at level 0"
                    + " only; they are not represented in level 1");

    private boolean secured = false;

    /**
     * Secures the run's sources before it ends (Stage 13 package 1), once: archives the vendor's session files of
     * every recorded invocation into the run's raw directory, keeps each sub-agent's {@code .meta.json} whole as a
     * {@code subagent_meta} event (whether or not the archive is on), and writes a {@code capture} summary into
     * {@code run.json} that keeps three things apart (review R13-06): how the recording ended
     * ({@code finalization}), whether every invocation's stream was acquired to its result line
     * ({@code acquisition}), and what level 1 is known not to hold ({@code preservation} with
     * {@code level1Omissions}). A finished recording can still be an incomplete acquisition. Nothing here throws:
     * a source that could not be secured is recorded with the reason, and a second call does nothing.
     */
    private void secureCapture(String finalization) {
        if (secured) {
            return;
        }
        secured = true;
        Map<String, Object> capture = new LinkedHashMap<>();
        capture.put("cli", "claude-code");
        capture.put("cliVersion", lastReported(Invocation::cliVersion)); // null when no stream had an init line
        capture.put("parserInput", "sdk-stream");
        capture.put("finalization", finalization);
        boolean allComplete = !invocations.isEmpty() && invocations.stream().allMatch(Invocation::streamComplete);
        capture.put("acquisition", allComplete ? "complete" : "incomplete");
        // Never "complete": the omissions below hold for every recording until the SDK release and the
        // package-3 transcript slice close them.
        capture.put("preservation", "partial");
        capture.put("level1Omissions", LEVEL1_OMISSIONS);
        List<Map<String, Object>> inventory = new ArrayList<>();
        for (Invocation invocation : invocations) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("index", invocation.index());
            entry.put("phase", invocation.phaseName());
            entry.put("sessionId", invocation.sessionId());
            entry.put("cliVersion", invocation.cliVersion());
            entry.put("streamComplete", invocation.streamComplete());
            entry.put("sourceRecords", invocation.sourceRecords());
            entry.put("vendorEvents", invocation.vendorEvents());
            inventory.add(entry);
        }
        capture.put("invocations", inventory);

        List<Map<String, Object>> sources = new ArrayList<>();
        Map<String, Object> stream = new LinkedHashMap<>();
        stream.put("kind", "live-stream");
        stream.put("location", "events.jsonl (source_record, vendor_event; scoped by invocation and messageNumber)");
        stream.put("acquisition", allComplete ? "complete" : "incomplete");
        stream.put("preservation", "partial");
        stream.put("sourceRecords", invocations.stream().mapToInt(Invocation::sourceRecords).sum());
        stream.put("vendorEvents", invocations.stream().mapToInt(Invocation::vendorEvents).sum());
        // What this channel cannot carry today, stated so a reader never takes the stream for complete
        stream.put("knownGaps", List.of(LEVEL1_OMISSIONS.get(0)));
        sources.add(stream);

        Map<String, Object> session = new LinkedHashMap<>();
        session.put("kind", "session-files");
        session.put("level1", "not-represented");
        List<String> sessionIds = invocations.stream().map(Invocation::sessionId).filter(Objects::nonNull).distinct().toList();
        try {
            java.util.Optional<java.nio.file.Path> rawDir = storage == null ? java.util.Optional.empty()
                    : storage.rawDirectory(currentRun.experiment().id(), currentRun.id());
            if (!archiveSessionFiles) {
                session.put("status", "not-captured");
                session.put("reason", "archiving switched off by the caller");
            }
            else if (rawDir.isEmpty()) {
                session.put("status", "unavailable");
                session.put("reason", "the storage has no raw directory");
            }
            else if (sessionIds.isEmpty()) {
                session.put("status", "unavailable");
                session.put("reason", "no recorded phase reported a session id");
            }
            else {
                List<Map<String, Object>> sessions = new ArrayList<>();
                int captured = 0;
                try (io.github.markpollack.journal.raw.RawArtifacts raw = io.github.markpollack.journal.raw.RawArtifacts
                        .open(rawDir.get(), "claude-code", lastReported(Invocation::cliVersion))) {
                    for (String sessionId : sessionIds) {
                        int before = raw.entries().size();
                        boolean ok = ClaudeSessionFiles.archive(raw, sessionId, sessionFilesRoot);
                        Map<String, Object> one = new LinkedHashMap<>();
                        one.put("sessionId", sessionId);
                        one.put("status", ok ? "captured" : "failed");
                        if (!ok) {
                            for (Map<String, Object> entry : raw.entries().subList(before, raw.entries().size())) {
                                if (entry.get("reason") != null) {
                                    one.put("reason", entry.get("reason"));
                                }
                            }
                        }
                        sessions.add(one);
                        captured += ok ? 1 : 0;
                    }
                    session.put("status", captured == sessionIds.size() ? "captured" : captured == 0 ? "failed" : "partial");
                    session.put("sessions", sessions);
                    session.put("manifest", "raw/" + io.github.markpollack.journal.raw.RawArtifacts.MANIFEST_FILE);
                    session.put("entries", raw.entries());
                    if (captured < sessionIds.size()) {
                        session.put("reason", sessions.stream().filter(s -> s.get("reason") != null)
                                .map(s -> s.get("sessionId") + ": " + s.get("reason")).collect(java.util.stream.Collectors.joining("; ")));
                    }
                }
            }
        }
        catch (RuntimeException | java.io.IOException ex) {
            session.put("status", "failed");
            session.put("reason", ex.getClass().getSimpleName() + ": " + ex.getMessage());
            log.warn("Level-0 archive for run {} failed: {}", currentRun.id(), ex.toString());
        }
        sources.add(session);
        capture.put("sources", sources);
        try {
            // Level-1 metadata, collected whether or not level 0 was archived (R13-01 coupling)
            recordSubagentMeta();
        }
        catch (RuntimeException ex) {
            log.warn("Sub-agent metadata for run {} not collected: {}", currentRun.id(), ex.toString());
        }
        currentRun.setSummary("capture", capture);
    }

    private String lastReported(java.util.function.Function<Invocation, String> field) {
        String value = null;
        for (Invocation invocation : invocations) {
            if (field.apply(invocation) != null) {
                value = field.apply(invocation);
            }
        }
        return value;
    }

    /**
     * Keeps each recorded sub-agent's sidechain {@code .meta.json} whole on this run, with its owner. The file is
     * read from the vendor's own session directory of the phase that recorded the sub-agent; this does not depend
     * on the level-0 archive. A sub-agent whose file cannot be found is recorded with the reason.
     */
    private void recordSubagentMeta() {
        for (String[] child : recordedSubagents) {
            Map<String, Object> attributes = new LinkedHashMap<>();
            attributes.put("vendor", "claude");
            attributes.put("spawnToolUseId", child[0]);
            if (child[1] != null) {
                attributes.put("agentId", child[1]);
            }
            attributes.put("childRunId", child[2]);
            String sessionId = child[3];
            java.util.Optional<java.nio.file.Path> transcript = sessionId == null ? java.util.Optional.empty()
                    : ClaudeSessionFiles.locateTranscript(sessionId, sessionFilesRoot);
            java.nio.file.Path meta = child[1] == null || transcript.isEmpty() ? null
                    : transcript.get().resolveSibling(sessionId).resolve("subagents").resolve("agent-" + child[1] + ".meta.json");
            if (meta != null && java.nio.file.Files.isRegularFile(meta)) {
                try {
                    attributes.put("path", "subagents/" + meta.getFileName());
                    attributes.put("payload", new com.fasterxml.jackson.databind.ObjectMapper().readValue(
                            java.nio.file.Files.readString(meta),
                            new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() { }));
                }
                catch (java.io.IOException ex) {
                    attributes.put("reason", "could not read " + meta.getFileName() + ": " + ex.getMessage());
                }
            }
            else {
                attributes.put("reason", child[1] == null ? "the stream named no agent id"
                        : sessionId == null ? "the phase reported no session id"
                        : transcript.isEmpty() ? "no transcript named " + sessionId + ".jsonl under " + sessionFilesRoot
                        : "no meta file at " + meta);
            }
            currentRun.logEvent(io.github.markpollack.journal.event.CustomEvent.of("subagent_meta", attributes));
        }
    }

    /**
     * Fail-loud check (DESIGN §4): if derived events were produced but the storage can't durably
     * persist them, throw (default) or WARN ({@link #lenient()}).
     */
    private void verifyDerivedDurable() {
        if (derivedEventsEmitted > 0 && (storage == null || !storage.persistsDerivedEvents())) {
            String message = "Recorded " + derivedEventsEmitted + " derived StepCostEvent(s) but storage "
                    + (storage == null ? "<none>" : storage.getClass().getSimpleName())
                    + " does not persist them durably — the derived analysis layer (analysis.jsonl) would be "
                    + "lost on exit. Configure JsonFileStorage for a production run, or call lenient() to "
                    + "accept a non-durable derived layer.";
            if (lenient) {
                log.warn(message);
            } else {
                throw new IllegalStateException(message);
            }
        }
    }
}
