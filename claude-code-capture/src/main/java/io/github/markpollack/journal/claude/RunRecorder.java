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
 * {@link #recordPhase(PhaseCapture)} once per phase, and end it with {@link #finish()} or
 * {@link #close()}. The run must write to storage that keeps derived events, normally
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
 * <p>When the run ends, the recorder checks the storage. If it logged derived events and
 * {@link JournalStorage#persistsDerivedEvents()} is {@code false}, as for
 * {@link io.github.markpollack.journal.storage.InMemoryStorage}, it throws
 * {@link IllegalStateException} instead of finishing the run. Call {@link #lenient()} to get a
 * logged warning instead, when losing them is intended. The recorders for other agent CLIs, such
 * as {@code GrokRunRecorder}, make no such check.
 *
 * <p>Like {@link Run#close()}, {@link #close()} ends the run {@code FINISHED} even when an
 * exception leaves the try-with-resources block. To record a failure, call
 * {@link #failRun(Throwable)} inside the block; {@code close()} does not check the storage of a
 * run that has already failed.
 *
 * <p>A recorder serves one run and is not safe for use from several threads.
 *
 * <p>Example:
 * <pre>{@code
 * Journal.configure(new JsonFileStorage(Path.of(".agent-journal")));
 * try (RunRecorder recorder = new RunRecorder(
 *         Journal.run("my-exp").config("model", model).start())) {
 *     recorder.recordPhase(capture);
 * }
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
     * Checks the storage as described in the class comment, then ends the run {@code FINISHED}.
     * If the run has already ended, its status does not change.
     *
     * @throws IllegalStateException if derived events were logged, the storage does not keep
     *         them, and {@link #lenient()} was not called; the run is then not ended
     */
    public void finish() {
        verifyDerivedDurable();
        currentRun.finish(RunStatus.FINISHED);
        finished = true;
    }

    /**
     * Ends the run {@code FINISHED} if it has not ended, after the same storage check as
     * {@link #finish()}. If the run has already ended, for example through
     * {@link #failRun(Throwable)}, the check is skipped and nothing changes, so a failed storage
     * check never hides the earlier failure. A try-with-resources block calls this method at its
     * end.
     *
     * @throws IllegalStateException if the run has not ended, derived events were logged, the
     *         storage does not keep them, and {@link #lenient()} was not called; the run is then
     *         not ended
     */
    @Override
    public void close() {
        if (currentRun == null) {
            return;
        }
        if (!finished && !currentRun.status().isTerminal()) {
            verifyDerivedDurable();
        }
        currentRun.close();
        finished = true;
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
