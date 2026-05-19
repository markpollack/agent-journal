package io.github.markpollack.journal.eval;

import java.util.Iterator;
import java.util.List;
import java.util.stream.Stream;

/**
 * An immutable collection of {@link EvalSubject}s — the output of a query.
 *
 * <p>Can be iterated, streamed, or passed to evaluation machinery.
 *
 * @param subjects the subjects in this set
 */
public record EvalSubjectSet(List<EvalSubject> subjects) implements Iterable<EvalSubject> {

	public EvalSubjectSet {
		subjects = List.copyOf(subjects);
	}

	public Stream<EvalSubject> stream() {
		return subjects.stream();
	}

	public int size() {
		return subjects.size();
	}

	public boolean isEmpty() {
		return subjects.isEmpty();
	}

	@Override
	public Iterator<EvalSubject> iterator() {
		return subjects.iterator();
	}

}
