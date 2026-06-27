package io.github.markpollack.journal.claude;

import io.github.markpollack.journal.Journal;
import io.github.markpollack.journal.Run;
import io.github.markpollack.journal.RunStatus;
import io.github.markpollack.journal.storage.JournalStorage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The shipped, production recorder for Claude Code captures — the non-test
 * {@code extends BaseRunRecorder} the cross-repo design (DESIGN §4) calls for. It records each
 * {@link PhaseCapture} into the run's two streams (execution {@code events.jsonl} + derived
 * {@code analysis.jsonl}, inherited from {@link BaseRunRecorder#recordPhase}) and adds the
 * <strong>fail-loud storage guarantee</strong>: a recorder that produced derived
 * {@link io.github.markpollack.journal.derived.StepCostEvent}s but is backed by a storage that
 * cannot durably persist them is silently dropping the most measurement-critical signal — exactly
 * the failure this feature exists to prevent — so it <strong>throws at finish</strong> by default.
 *
 * <p>
 * Intended storage is {@link io.github.markpollack.journal.storage.JsonFileStorage} (durable
 * {@code analysis.jsonl}); on a non-durable backend ({@link io.github.markpollack.journal.storage.InMemoryStorage}
 * or the bare default) {@link #finish()}/{@link #close()} throws unless {@link #lenient()} downgrades it
 * to a one-line WARN.
 *
 * <p>
 * The recorder owns the run's terminal lifecycle — use it as the {@code AutoCloseable}:
 * <pre>{@code
 * Journal.configure(new JsonFileStorage(Path.of(".agent-journal")));
 * try (RunRecorder rec = new RunRecorder(Journal.run("my-exp").config("model", model).start())) {
 *     rec.recordPhase(capture);
 * } // finishes the run; throws here if derived cost can't be persisted durably
 * }</pre>
 */
public class RunRecorder extends BaseRunRecorder implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(RunRecorder.class);

    private final JournalStorage storage;
    private boolean lenient = false;
    private boolean finished = false;

    /**
     * Wraps a run, inspecting the globally configured storage ({@link Journal#storage()}) — the same
     * backend the run uses ({@code RunBuilder.start()} reads the global) — for the durability check.
     *
     * @param run the open run to record into
     */
    public RunRecorder(Run run) {
        this(run, Journal.storage());
    }

    /**
     * Wraps a run with an explicit storage to inspect for durability (mainly for tests that don't
     * route through the global {@link Journal} context).
     *
     * @param run     the open run to record into
     * @param storage the storage backing this run, inspected by the fail-loud check
     */
    public RunRecorder(Run run, JournalStorage storage) {
        this.currentRun = run;
        this.storage = storage;
    }

    /**
     * Downgrades the fail-loud behavior from throw to a single WARN — for callers that knowingly
     * accept a non-durable derived layer (e.g. an ephemeral/in-memory run).
     *
     * @return this recorder
     */
    public RunRecorder lenient() {
        this.lenient = true;
        return this;
    }

    /** Returns the run this recorder writes to. */
    public Run run() {
        return currentRun;
    }

    /**
     * Finishes the run with {@link RunStatus#FINISHED} after the fail-loud durability check. Call
     * this explicitly, or rely on {@link #close()} (try-with-resources).
     */
    public void finish() {
        verifyDerivedDurable();
        currentRun.finish(RunStatus.FINISHED);
        finished = true;
    }

    /**
     * Finishes the run if it hasn't already terminated. If the run already failed, that failure is
     * surfaced as-is — the durability check does not run, so a secondary durability error never masks
     * the real cause.
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
