package io.github.markpollack.journal.eval;

import java.util.stream.Stream;

/**
 * Adapter that produces {@link EvalSubject}s from a specific data source.
 *
 * <p>Each source normalizes its native records into the uniform EvalSubject shape.
 * Implementations should return finite streams backed by in-memory or
 * already-loaded records.
 */
@FunctionalInterface
public interface EvalSubjectSource {

	Stream<EvalSubject> subjects();

}
