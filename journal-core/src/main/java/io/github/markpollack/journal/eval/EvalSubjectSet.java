package io.github.markpollack.journal.eval;

import java.util.Iterator;
import java.util.List;
import java.util.stream.Stream;

/**
 * An immutable, ordered list of {@link EvalSubject}s, as returned by
 * {@link EvalSubjectQuery#toSet()} and in the groups of {@link EvalSubjectQuery#groupBy}. Iterate
 * it, stream it, or pass it to whatever scores the subjects. Despite its name it is a list, not a
 * set: it keeps the order and can hold the same subject twice.
 *
 * <p>The constructor copies the list, so later changes to that list do not show here, and the
 * set cannot be changed. It is safe to share between threads.
 *
 * @param subjects the subjects, in order; must not be {@code null} or contain {@code null}
 */
public record EvalSubjectSet(List<EvalSubject> subjects) implements Iterable<EvalSubject> {

	/**
	 * Creates a set from an unmodifiable copy of {@code subjects}.
	 */
	public EvalSubjectSet {
		subjects = List.copyOf(subjects);
	}

	/**
	 * Returns a sequential stream of the subjects, in order.
	 *
	 * @return a stream of the subjects
	 */
	public Stream<EvalSubject> stream() {
		return subjects.stream();
	}

	/**
	 * Returns the number of subjects.
	 *
	 * @return the number of subjects
	 */
	public int size() {
		return subjects.size();
	}

	/**
	 * Returns whether there are no subjects.
	 *
	 * @return {@code true} if the set has no subjects
	 */
	public boolean isEmpty() {
		return subjects.isEmpty();
	}

	@Override
	public Iterator<EvalSubject> iterator() {
		return subjects.iterator();
	}

}
