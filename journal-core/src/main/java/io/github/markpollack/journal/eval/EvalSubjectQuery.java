package io.github.markpollack.journal.eval;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Selects, groups and counts the {@link EvalSubject}s of an {@link EvalSubjectSource}. Start with
 * {@link #from(EvalSubjectSource)}, narrow the selection with {@link #kind(EvalSubjectKind)} and
 * {@link #where(Predicate)}, then call {@link #toSet()}, {@link #groupBy(Function)},
 * {@link #countBy(Function)} or {@link #count()}. It is plain Java streams and predicates, not a
 * query language.
 *
 * <pre>{@code
 * EvalSubjectSet failedTools = EvalSubjectQuery.from(source)
 *     .kind(EvalSubjectKind.TOOL_CALL)
 *     .where(EvalSubject::failed)
 *     .toSet();
 * }</pre>
 *
 * <p>A subject is selected if it passes every filter. The filter methods change this query and
 * return it, not a copy, so one query cannot serve as the base of several different ones. Each
 * result method reads the source again; for a source from {@link EvalSubjectSources#fromJournal}
 * that means loading the run again. Results keep the source's order. A query is not safe for use
 * from several threads.
 */
public final class EvalSubjectQuery {

	private final EvalSubjectSource source;

	private final List<Predicate<EvalSubject>> predicates = new ArrayList<>();

	private EvalSubjectQuery(EvalSubjectSource source) {
		this.source = source;
	}

	/**
	 * Starts a query over the subjects of a source. Nothing is read until a result method is
	 * called.
	 *
	 * @param source the subjects to query; must not be {@code null}
	 * @return a new query with no filters
	 */
	public static EvalSubjectQuery from(EvalSubjectSource source) {
		return new EvalSubjectQuery(source);
	}

	/**
	 * Keeps only the subjects of the given kind.
	 *
	 * @param kind the kind to keep
	 * @return this query
	 */
	public EvalSubjectQuery kind(EvalSubjectKind kind) {
		predicates.add(s -> s.kind() == kind);
		return this;
	}

	/**
	 * Keeps only the subjects for which a predicate returns {@code true}.
	 *
	 * @param predicate the test each subject must pass; must not be {@code null}
	 * @return this query
	 */
	public EvalSubjectQuery where(Predicate<EvalSubject> predicate) {
		predicates.add(predicate);
		return this;
	}

	/**
	 * Reads the source and returns the subjects that pass every filter, in the source's order.
	 *
	 * @return the matching subjects, possibly none
	 */
	public EvalSubjectSet toSet() {
		Stream<EvalSubject> stream = source.subjects();
		for (Predicate<EvalSubject> predicate : predicates) {
			stream = stream.filter(predicate);
		}
		return new EvalSubjectSet(stream.toList());
	}

	/**
	 * Reads the source and groups the matching subjects by a key.
	 *
	 * @param <K> the type of the key
	 * @param classifier gives the key of each subject; must not return {@code null}
	 * @return a map from each key to its subjects, which keep the source's order; the order of the
	 *         keys is not specified
	 */
	public <K> Map<K, EvalSubjectSet> groupBy(Function<EvalSubject, K> classifier) {
		return toSet().stream()
				.collect(Collectors.groupingBy(classifier,
						Collectors.collectingAndThen(Collectors.toList(), EvalSubjectSet::new)));
	}

	/**
	 * Reads the source and counts the matching subjects for each key.
	 *
	 * @param <K> the type of the key
	 * @param classifier gives the key of each subject; must not return {@code null}
	 * @return a map from each key to its number of subjects; the order of the keys is not
	 *         specified
	 */
	public <K> Map<K, Long> countBy(Function<EvalSubject, K> classifier) {
		return toSet().stream().collect(Collectors.groupingBy(classifier, Collectors.counting()));
	}

	/**
	 * Reads the source and counts the matching subjects.
	 *
	 * @return the number of matching subjects
	 */
	public long count() {
		return toSet().size();
	}

}
