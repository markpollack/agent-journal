package io.github.markpollack.journal.eval;

import java.util.Map;
import java.util.Optional;

/**
 * One piece of recorded agent behaviour that a judge can score, such as an LLM call or a tool
 * call, in the same shape whatever it was recorded from. Get subjects from an
 * {@link EvalSubjectSource}, such as {@link EvalSubjectSources#fromJournal} for a stored run, and
 * select them with {@link EvalSubjectQuery}. To record a verdict on a subject, target its
 * {@link #id()} with
 * {@link io.github.markpollack.journal.event.FeedbackTarget#subject(String, String, String)}.
 *
 * <p>{@link #kind()} says what the behaviour was, and {@link #source()} what it was read from:
 * {@code "journal"} for subjects made by {@link EvalSubjectSources}; other sources use their own
 * names. What {@code goal}, {@code input} and {@code output} hold depends on the kind and the
 * source; {@link EvalSubjectSources} describes them for journal events. {@link #metadata()} holds
 * the other details, and {@link #costUsd()}, {@link #durationMs()} and {@link #success()} read its
 * {@code costUsd}, {@code durationMs} and {@code success} keys.
 *
 * <p>{@code metadata} must not be {@code null}. The record does not copy it; the sources in this
 * library give it an unmodifiable map.
 *
 * @param id the subject's ID, unique among the subjects of one source
 * @param kind what kind of behaviour this is
 * @param source what the subject was read from, such as {@code "journal"}
 * @param runId the ID of the run it came from, or {@code null} if not known
 * @param itemId the ID of the dataset item it belongs to, or {@code null}; the sources in this
 *        library leave it {@code null}
 * @param goal a short description of what was attempted, such as {@code "Tool call: Bash"}
 * @param input the input to the behaviour, such as a tool's parameters, or {@code null}
 * @param output the result of the behaviour, or {@code null}
 * @param metadata other details, keyed by name; never {@code null}
 */
public record EvalSubject(
		String id,
		EvalSubjectKind kind,
		String source,
		String runId,
		String itemId,
		String goal,
		Object input,
		Object output,
		Map<String, Object> metadata
) {

	/**
	 * Returns the {@code costUsd} metadata value, if it is a number.
	 *
	 * @return the cost in US dollars, or empty if the key is missing or not a number
	 */
	public Optional<Double> costUsd() {
		Object value = metadata.get("costUsd");
		return value instanceof Number n ? Optional.of(n.doubleValue()) : Optional.empty();
	}

	/**
	 * Returns the {@code durationMs} metadata value, if it is a number. A fractional value is cut
	 * to a whole number. A negative value, such as -1 for a duration that was not measured, is
	 * returned as it is.
	 *
	 * @return the duration in milliseconds, or empty if the key is missing or not a number
	 */
	public Optional<Long> durationMs() {
		Object value = metadata.get("durationMs");
		return value instanceof Number n ? Optional.of(n.longValue()) : Optional.empty();
	}

	/**
	 * Returns the {@code success} metadata value, if it is a {@link Boolean}. Other types, such as
	 * the string {@code "true"}, give an empty result.
	 *
	 * @return whether the behaviour succeeded, or empty if not known
	 */
	public Optional<Boolean> success() {
		Object value = metadata.get("success");
		return value instanceof Boolean b ? Optional.of(b) : Optional.empty();
	}

	/**
	 * Returns whether the behaviour is known to have failed. A subject without a
	 * {@link #success()} value has not failed.
	 *
	 * @return {@code true} only if {@link #success()} is present and {@code false}
	 */
	public boolean failed() {
		return success().map(s -> !s).orElse(false);
	}

	/**
	 * Returns {@link #itemId()} as an {@link Optional}.
	 *
	 * @return the dataset item ID, or empty if it is {@code null}
	 */
	public Optional<String> itemIdOpt() {
		return Optional.ofNullable(itemId);
	}

}
