package io.github.markpollack.journal.eval;

import java.util.stream.Stream;

/**
 * Supplies the {@link EvalSubject}s of one body of recorded data, such as the events of one run.
 * Get one from {@link EvalSubjectSources} or from a capture module, such as Claude Code's
 * {@code PhaseCaptureSources}, and pass it to {@link EvalSubjectQuery#from(EvalSubjectSource)}.
 * It is a functional interface, so a lambda or a method reference such as
 * {@code subjects::stream} also works.
 *
 * <p>Implementations must return a new, finite stream on every call to {@link #subjects()},
 * because a query calls it once for each result it computes. Whether a call reads the data again
 * is up to the implementation: {@link EvalSubjectSources#fromJournal} loads the run each time,
 * and {@link EvalSubjectSources#fromEvents} converts the events once. Thread safety is not
 * specified.
 */
@FunctionalInterface
public interface EvalSubjectSource {

	/**
	 * Returns the subjects of this source, in the order the source defines.
	 *
	 * @return a new, finite stream of subjects
	 */
	Stream<EvalSubject> subjects();

}
