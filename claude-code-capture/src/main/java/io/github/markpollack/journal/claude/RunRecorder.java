package io.github.markpollack.journal.claude;

import io.github.markpollack.journal.Journal;
import io.github.markpollack.journal.Run;
import io.github.markpollack.journal.RunStatus;
import io.github.markpollack.journal.storage.JournalStorage;

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
    public void finish() {
        if (!currentRun.status().isTerminal()) {
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
        currentRun.finish(RunStatus.CRASHED);
        finished = true;
        verifyDerivedDurable();
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
