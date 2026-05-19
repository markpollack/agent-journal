package io.github.markpollack.journal.eval;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Fluent selection and grouping over {@link EvalSubject}s.
 *
 * <p>Intentionally simple: streams, predicates, groupBy, countBy.
 * Not a query engine, not a DSL, not a DataFrame.
 *
 * <p>Example:
 * <pre>{@code
 * EvalSubjectSet failedTools = EvalSubjectQuery.from(source)
 *     .kind(EvalSubjectKind.TOOL_CALL)
 *     .where(EvalSubject::failed)
 *     .toSet();
 * }</pre>
 */
public final class EvalSubjectQuery {

	private final EvalSubjectSource source;

	private final List<Predicate<EvalSubject>> predicates = new ArrayList<>();

	private EvalSubjectQuery(EvalSubjectSource source) {
		this.source = source;
	}

	public static EvalSubjectQuery from(EvalSubjectSource source) {
		return new EvalSubjectQuery(source);
	}

	public EvalSubjectQuery kind(EvalSubjectKind kind) {
		predicates.add(s -> s.kind() == kind);
		return this;
	}

	public EvalSubjectQuery where(Predicate<EvalSubject> predicate) {
		predicates.add(predicate);
		return this;
	}

	/**
	 * Execute query and collect results into an {@link EvalSubjectSet}.
	 */
	public EvalSubjectSet toSet() {
		Stream<EvalSubject> stream = source.subjects();
		for (Predicate<EvalSubject> predicate : predicates) {
			stream = stream.filter(predicate);
		}
		return new EvalSubjectSet(stream.toList());
	}

	/**
	 * Group subjects by a classifier function.
	 */
	public <K> Map<K, EvalSubjectSet> groupBy(Function<EvalSubject, K> classifier) {
		return toSet().stream()
				.collect(Collectors.groupingBy(classifier,
						Collectors.collectingAndThen(Collectors.toList(), EvalSubjectSet::new)));
	}

	/**
	 * Count subjects by a classifier function.
	 */
	public <K> Map<K, Long> countBy(Function<EvalSubject, K> classifier) {
		return toSet().stream().collect(Collectors.groupingBy(classifier, Collectors.counting()));
	}

	/**
	 * Count total matching subjects.
	 */
	public long count() {
		return toSet().size();
	}

}
