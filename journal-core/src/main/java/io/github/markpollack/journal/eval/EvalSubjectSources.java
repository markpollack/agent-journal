package io.github.markpollack.journal.eval;

import io.github.markpollack.journal.event.CustomEvent;
import io.github.markpollack.journal.event.JournalEvent;
import io.github.markpollack.journal.event.LLMCallEvent;
import io.github.markpollack.journal.event.StateChangeEvent;
import io.github.markpollack.journal.event.ToolCallEvent;
import io.github.markpollack.journal.storage.JournalStorage;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Makes {@link EvalSubjectSource}s from journal events, so that a run's recorded behaviour can be
 * judged. {@link #fromJournal} reads a stored run; {@link #fromEvents} takes events you already
 * have. Each LLM call, tool call, state change and custom event becomes one {@link EvalSubject}
 * with source {@code "journal"}, in event order; metric, Git, feedback and other events are
 * skipped. A capture module can have its own factory that reads its phase captures instead, such
 * as Claude Code's {@code PhaseCaptureSources}.
 *
 * <p>Each kind of event becomes a subject like this:
 * <ul>
 *   <li>{@link LLMCallEvent}: kind {@code LLM_CALL}, goal {@code "LLM call to <model>"}, no input
 *       or output. Metadata: {@code timestamp}, {@code model}, and when present
 *       {@code provider}, {@code inputTokens}, {@code outputTokens}, {@code totalTokens},
 *       {@code costUsd}, {@code durationMs}, {@code finishReason} and {@code responseId}; then
 *       the event's own metadata, which wins when a key is the same.
 *   <li>{@link ToolCallEvent}: kind {@code TOOL_CALL}, goal {@code "Tool call: <name>"}, the
 *       tool's input and output. Metadata: {@code timestamp}, {@code toolName},
 *       {@code durationMs}, {@code success}, and {@code errorMessage} when present.
 *   <li>{@link StateChangeEvent}: kind {@code STATE_CHANGE}, goal
 *       {@code "State: <from> → <to>"}, the old state as input and the new state as output.
 *       Metadata: {@code timestamp}, {@code fromState}, {@code toState}, and {@code reason} when
 *       present.
 *   <li>{@link CustomEvent}: kind {@code CUSTOM}, goal {@code "Custom: <name>"}, no input or
 *       output. Metadata: {@code timestamp}, {@code eventName}, and the event's attributes.
 * </ul>
 *
 * <p>An LLM call's subject ID is its response ID and a tool call's is its tool-call ID. An event
 * without such an ID, and every state change and custom event, gets {@code journal:<runId>:<n>},
 * where {@code n} is the event's 0-based position among all the events given, skipped ones
 * included. Such an ID changes if the events are filtered or reordered, so feedback should target
 * subjects with their own IDs where it can. The capture modules' recorders give each tool call
 * its vendor's ID. Claude Code's recorder gives its LLM call the message ID of the phase's last
 * turn as the response ID, when turns were captured; the other recorders set no response ID, so
 * their LLM calls get positional IDs.
 *
 * <p>Subject metadata is an unmodifiable map that keeps the order in which its keys are added:
 * the event's own fields first, then its metadata or attributes in their order. It holds no
 * {@code null} values: a {@code null} model, tool name, state or custom event name, and a
 * {@code null} value in an event's metadata or attributes, are left out. The events must have no
 * {@code null} timestamp; one such event makes the whole conversion fail.
 */
public final class EvalSubjectSources {

	private EvalSubjectSources() {
	}

	/**
	 * Returns a source of subjects for a stored run. The run's events are loaded from
	 * {@code storage}, and converted, each time {@link EvalSubjectSource#subjects()} is called, not
	 * when this method is called. So every result of a query reads the run again, and sees events
	 * logged since. A run without events gives no subjects, and so does a run that the built-in
	 * storages do not have. Exceptions from loading or converting reach the caller of
	 * {@code subjects()}.
	 *
	 * @param storage the storage to read; must not be {@code null}
	 * @param experimentId the ID of the experiment the run belongs to
	 * @param runId the ID of the run, which is also put on each subject and in positional IDs
	 * @return a source that loads the run's events on each call
	 */
	public static EvalSubjectSource fromJournal(JournalStorage storage, String experimentId, String runId) {
		return () -> {
			List<JournalEvent> events = storage.loadEvents(experimentId, runId);
			return mapEvents(events, runId).stream();
		};
	}

	/**
	 * Returns a source of subjects for events you already have. The events are converted now,
	 * once, so this call fails if one cannot be converted; every call to
	 * {@link EvalSubjectSource#subjects()} then streams the same subjects.
	 *
	 * @param events the events, in the order they were logged; must not be {@code null}
	 * @param runId the ID of the run they belong to, which is also put on each subject and in
	 *        positional IDs
	 * @return a source of the converted subjects
	 */
	public static EvalSubjectSource fromEvents(List<JournalEvent> events, String runId) {
		List<EvalSubject> subjects = mapEvents(events, runId);
		return subjects::stream;
	}

	private static List<EvalSubject> mapEvents(List<JournalEvent> events, String runId) {
		AtomicInteger index = new AtomicInteger(0);
		return events.stream()
				.map(event -> mapEvent(event, runId, index.getAndIncrement()))
				.filter(subject -> subject != null)
				.toList();
	}

	private static EvalSubject mapEvent(JournalEvent event, String runId, int index) {
		// Prefer a stable native id (R2.3): the tool_use id for tool calls, the response id
		// for LLM calls. Fall back to the legacy positional id only for events with no native
		// identity — never reuse a position as identity for a judgeable step.
		String positionalId = "journal:" + runId + ":" + index;
		String source = "journal";

		if (event instanceof LLMCallEvent llm) {
			String id = llm.responseId() != null ? llm.responseId() : positionalId;
			Map<String, Object> metadata = new LinkedHashMap<>();
			metadata.put("timestamp", llm.timestamp().toString());
			if (llm.provider() != null) {
				metadata.put("provider", llm.provider());
			}
			metadata.put("model", llm.model());
			if (llm.tokenUsage() != null) {
				metadata.put("inputTokens", llm.tokenUsage().inputTokens());
				metadata.put("outputTokens", llm.tokenUsage().outputTokens());
				metadata.put("totalTokens", llm.totalTokens());
			}
			if (llm.cost() != null) {
				metadata.put("costUsd", llm.totalCostUsd());
			}
			if (llm.timing() != null) {
				metadata.put("durationMs", llm.timing().totalDurationMs());
			}
			if (llm.finishReason() != null) {
				metadata.put("finishReason", llm.finishReason());
			}
			if (llm.responseId() != null) {
				metadata.put("responseId", llm.responseId());
			}
			if (llm.metadata() != null && !llm.metadata().isEmpty()) {
				metadata.putAll(llm.metadata());
			}

			return new EvalSubject(id, EvalSubjectKind.LLM_CALL, source, runId, null,
					"LLM call to " + llm.model(), null, null, withoutNulls(metadata));
		}

		if (event instanceof ToolCallEvent tool) {
			String id = tool.id() != null ? tool.id() : positionalId;
			Map<String, Object> metadata = new LinkedHashMap<>();
			metadata.put("timestamp", tool.timestamp().toString());
			metadata.put("toolName", tool.toolName());
			metadata.put("durationMs", tool.durationMs());
			metadata.put("success", tool.success());
			if (tool.errorMessage() != null) {
				metadata.put("errorMessage", tool.errorMessage());
			}

			return new EvalSubject(id, EvalSubjectKind.TOOL_CALL, source, runId, null,
					"Tool call: " + tool.toolName(), tool.input(), tool.output(), withoutNulls(metadata));
		}

		if (event instanceof StateChangeEvent state) {
			String id = positionalId;
			Map<String, Object> metadata = new LinkedHashMap<>();
			metadata.put("timestamp", state.timestamp().toString());
			metadata.put("fromState", state.fromState());
			metadata.put("toState", state.toState());
			if (state.reason() != null) {
				metadata.put("reason", state.reason());
			}

			return new EvalSubject(id, EvalSubjectKind.STATE_CHANGE, source, runId, null,
					"State: " + state.fromState() + " → " + state.toState(),
					state.fromState(), state.toState(), withoutNulls(metadata));
		}

		if (event instanceof CustomEvent custom) {
			String id = positionalId;
			Map<String, Object> metadata = new LinkedHashMap<>();
			metadata.put("timestamp", custom.timestamp().toString());
			metadata.put("eventName", custom.name());
			if (custom.attributes() != null) {
				metadata.putAll(custom.attributes());
			}

			return new EvalSubject(id, EvalSubjectKind.CUSTOM, source, runId, null,
					"Custom: " + custom.name(), null, null, withoutNulls(metadata));
		}

		// MetricEvent and GitEvents are not judgeable behavior units — skip
		return null;
	}

	/** An unmodifiable copy in insertion order, without the entries whose value is null. */
	private static Map<String, Object> withoutNulls(Map<String, Object> metadata) {
		Map<String, Object> copy = new LinkedHashMap<>();
		metadata.forEach((key, value) -> {
			if (value != null) {
				copy.put(key, value);
			}
		});
		return Collections.unmodifiableMap(copy);
	}

}
