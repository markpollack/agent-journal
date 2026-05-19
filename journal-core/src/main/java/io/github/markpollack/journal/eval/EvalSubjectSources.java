package io.github.markpollack.journal.eval;

import io.github.markpollack.journal.event.CustomEvent;
import io.github.markpollack.journal.event.JournalEvent;
import io.github.markpollack.journal.event.LLMCallEvent;
import io.github.markpollack.journal.event.StateChangeEvent;
import io.github.markpollack.journal.event.ToolCallEvent;
import io.github.markpollack.journal.storage.JournalStorage;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Factory for creating {@link EvalSubjectSource} instances backed by
 * journal-core data sources.
 *
 * <p>Source adapters for other modules:
 * <ul>
 *   <li>{@code PhaseCaptureSources.fromPhaseCaptures()} in claude-code-capture</li>
 *   <li>{@code fromStepTransitions()} deferred — StepTransition lives in agent-workflow</li>
 * </ul>
 */
public final class EvalSubjectSources {

	private EvalSubjectSources() {
	}

	/**
	 * Extract subjects from Journal events for a specific run.
	 * @param storage the journal storage backend
	 * @param experimentId the experiment containing the run
	 * @param runId the run to extract subjects from
	 * @return a source producing EvalSubjects from the run's events
	 */
	public static EvalSubjectSource fromJournal(JournalStorage storage, String experimentId, String runId) {
		return () -> {
			List<JournalEvent> events = storage.loadEvents(experimentId, runId);
			return mapEvents(events, runId).stream();
		};
	}

	/**
	 * Extract subjects from a pre-loaded list of Journal events.
	 * @param events the events to convert
	 * @param runId the run these events belong to
	 * @return a source producing EvalSubjects from the events
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
		String id = "journal:" + runId + ":" + index;
		String source = "journal";

		if (event instanceof LLMCallEvent llm) {
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
					"LLM call to " + llm.model(), null, null, Map.copyOf(metadata));
		}

		if (event instanceof ToolCallEvent tool) {
			Map<String, Object> metadata = new LinkedHashMap<>();
			metadata.put("timestamp", tool.timestamp().toString());
			metadata.put("toolName", tool.toolName());
			metadata.put("durationMs", tool.durationMs());
			metadata.put("success", tool.success());
			if (tool.errorMessage() != null) {
				metadata.put("errorMessage", tool.errorMessage());
			}

			return new EvalSubject(id, EvalSubjectKind.TOOL_CALL, source, runId, null,
					"Tool call: " + tool.toolName(), tool.input(), tool.output(), Map.copyOf(metadata));
		}

		if (event instanceof StateChangeEvent state) {
			Map<String, Object> metadata = new LinkedHashMap<>();
			metadata.put("timestamp", state.timestamp().toString());
			metadata.put("fromState", state.fromState());
			metadata.put("toState", state.toState());
			if (state.reason() != null) {
				metadata.put("reason", state.reason());
			}

			return new EvalSubject(id, EvalSubjectKind.STATE_CHANGE, source, runId, null,
					"State: " + state.fromState() + " → " + state.toState(),
					state.fromState(), state.toState(), Map.copyOf(metadata));
		}

		if (event instanceof CustomEvent custom) {
			Map<String, Object> metadata = new LinkedHashMap<>();
			metadata.put("timestamp", custom.timestamp().toString());
			metadata.put("eventName", custom.name());
			if (custom.attributes() != null) {
				metadata.putAll(custom.attributes());
			}

			return new EvalSubject(id, EvalSubjectKind.CUSTOM, source, runId, null,
					"Custom: " + custom.name(), null, null, Map.copyOf(metadata));
		}

		// MetricEvent and GitEvents are not judgeable behavior units — skip
		return null;
	}

}
