package io.github.markpollack.journal.eval;

import io.github.markpollack.journal.Experiment;
import io.github.markpollack.journal.event.JournalEvent;
import io.github.markpollack.journal.event.ToolCallEvent;
import io.github.markpollack.journal.storage.InMemoryStorage;
import io.github.markpollack.journal.storage.RunData;
import io.github.markpollack.journal.test.TestEvents;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("EvalSubjectSources")
class EvalSubjectSourcesTest {

	private static final String EXP_ID = "exp-1";

	private static final String RUN_ID = "run-1";

	private InMemoryStorage storage;

	@BeforeEach
	void setUp() {
		storage = new InMemoryStorage();
		storage.saveExperiment(Experiment.create(EXP_ID).build());
		storage.saveRun(RunData.builder().id(RUN_ID).experimentId(EXP_ID).build());
	}

	@Nested
	@DisplayName("fromJournal")
	class FromJournal {

		@Test
		@DisplayName("maps LLMCallEvent to LLM_CALL subject")
		void mapsLlmCall() {
			var event = TestEvents.llmCallComplete();
			storage.appendEvent(EXP_ID, RUN_ID, event);

			EvalSubjectSource source = EvalSubjectSources.fromJournal(storage, EXP_ID, RUN_ID);
			List<EvalSubject> subjects = source.subjects().toList();

			assertThat(subjects).hasSize(1);
			EvalSubject subject = subjects.get(0);
			assertThat(subject.kind()).isEqualTo(EvalSubjectKind.LLM_CALL);
			assertThat(subject.source()).isEqualTo("journal");
			assertThat(subject.runId()).isEqualTo(RUN_ID);
			assertThat(subject.goal()).contains("claude-opus-4.5");
			assertThat(subject.costUsd()).isPresent();
			assertThat(subject.durationMs()).isPresent();
			assertThat(subject.metadata()).containsKey("model");
		}

		@Test
		@DisplayName("maps ToolCallEvent to TOOL_CALL subject with input/output")
		void mapsToolCall() {
			var event = TestEvents.bashSuccess();
			storage.appendEvent(EXP_ID, RUN_ID, event);

			EvalSubjectSource source = EvalSubjectSources.fromJournal(storage, EXP_ID, RUN_ID);
			List<EvalSubject> subjects = source.subjects().toList();

			assertThat(subjects).hasSize(1);
			EvalSubject subject = subjects.get(0);
			assertThat(subject.kind()).isEqualTo(EvalSubjectKind.TOOL_CALL);
			assertThat(subject.input()).isEqualTo(Map.of("command", "ls -la"));
			assertThat(subject.output()).isEqualTo("file1.txt\nfile2.txt\nfile3.txt");
			assertThat(subject.success()).hasValue(true);
			assertThat(subject.failed()).isFalse();
			assertThat(subject.metadata()).containsEntry("toolName", "Bash");
		}

		@Test
		@DisplayName("maps failed ToolCallEvent with error metadata")
		void mapsFailedToolCall() {
			var event = TestEvents.toolFailure();
			storage.appendEvent(EXP_ID, RUN_ID, event);

			EvalSubjectSource source = EvalSubjectSources.fromJournal(storage, EXP_ID, RUN_ID);
			EvalSubject subject = source.subjects().toList().get(0);

			assertThat(subject.success()).hasValue(false);
			assertThat(subject.failed()).isTrue();
			assertThat(subject.metadata()).containsEntry("errorMessage", "Permission denied");
		}

		@Test
		@DisplayName("maps StateChangeEvent to STATE_CHANGE subject")
		void mapsStateChange() {
			var event = TestEvents.stateChangePlanToImplement();
			storage.appendEvent(EXP_ID, RUN_ID, event);

			EvalSubjectSource source = EvalSubjectSources.fromJournal(storage, EXP_ID, RUN_ID);
			EvalSubject subject = source.subjects().toList().get(0);

			assertThat(subject.kind()).isEqualTo(EvalSubjectKind.STATE_CHANGE);
			assertThat(subject.input()).isEqualTo("planning");
			assertThat(subject.output()).isEqualTo("implementing");
			assertThat(subject.metadata()).containsEntry("reason", "plan approved");
		}

		@Test
		@DisplayName("maps CustomEvent to CUSTOM subject")
		void mapsCustomEvent() {
			var event = TestEvents.checkpointEvent();
			storage.appendEvent(EXP_ID, RUN_ID, event);

			EvalSubjectSource source = EvalSubjectSources.fromJournal(storage, EXP_ID, RUN_ID);
			EvalSubject subject = source.subjects().toList().get(0);

			assertThat(subject.kind()).isEqualTo(EvalSubjectKind.CUSTOM);
			assertThat(subject.metadata()).containsEntry("eventName", "checkpoint");
			assertThat(subject.metadata()).containsEntry("phase", "implementation");
		}

		@Test
		@DisplayName("skips MetricEvent (not judgeable)")
		void skipsMetricEvent() {
			storage.appendEvent(EXP_ID, RUN_ID, TestEvents.tokenMetric());

			EvalSubjectSource source = EvalSubjectSources.fromJournal(storage, EXP_ID, RUN_ID);
			assertThat(source.subjects().toList()).isEmpty();
		}

		@Test
		@DisplayName("skips GitEvents (not judgeable in v1)")
		void skipsGitEvents() {
			storage.appendEvent(EXP_ID, RUN_ID, TestEvents.gitCommit());
			storage.appendEvent(EXP_ID, RUN_ID, TestEvents.gitPatch());

			EvalSubjectSource source = EvalSubjectSources.fromJournal(storage, EXP_ID, RUN_ID);
			assertThat(source.subjects().toList()).isEmpty();
		}

		@Test
		@DisplayName("handles mixed event types from agent turn sequence")
		void handlesMixedEvents() {
			for (var event : TestEvents.agentTurnSequence()) {
				storage.appendEvent(EXP_ID, RUN_ID, event);
			}

			EvalSubjectSource source = EvalSubjectSources.fromJournal(storage, EXP_ID, RUN_ID);
			List<EvalSubject> subjects = source.subjects().toList();

			// agentTurnSequence: 2 state changes, 2 llm calls, 3 tool calls, 2 metrics
			// Metrics are skipped → 7 subjects
			assertThat(subjects).hasSize(7);
			assertThat(subjects).extracting(EvalSubject::kind)
					.containsExactly(EvalSubjectKind.STATE_CHANGE, EvalSubjectKind.LLM_CALL,
							EvalSubjectKind.TOOL_CALL, EvalSubjectKind.TOOL_CALL,
							EvalSubjectKind.LLM_CALL, EvalSubjectKind.TOOL_CALL,
							EvalSubjectKind.STATE_CHANGE);
		}

		@Test
		@DisplayName("falls back to positional IDs only when no native id is present")
		void generatesSequentialIds() {
			storage.appendEvent(EXP_ID, RUN_ID, TestEvents.llmCall());
			storage.appendEvent(EXP_ID, RUN_ID, TestEvents.bashSuccess());

			EvalSubjectSource source = EvalSubjectSources.fromJournal(storage, EXP_ID, RUN_ID);
			List<EvalSubject> subjects = source.subjects().toList();

			assertThat(subjects.get(0).id()).isEqualTo("journal:" + RUN_ID + ":0");
			assertThat(subjects.get(1).id()).isEqualTo("journal:" + RUN_ID + ":1");
		}

		@Test
		@DisplayName("uses the tool_use id as the stable subject id, not a list position (R2.3)")
		void usesToolUseIdAsStableSubjectId() {
			var event = ToolCallEvent.success("toolu_abc123", "Bash", Map.of("command", "ls"), "out", 5);
			storage.appendEvent(EXP_ID, RUN_ID, event);

			EvalSubjectSource source = EvalSubjectSources.fromJournal(storage, EXP_ID, RUN_ID);
			EvalSubject subject = source.subjects().toList().get(0);

			// Order-independent identity: feedback keyed to this resolves regardless of reload order
			assertThat(subject.id()).isEqualTo("toolu_abc123");
			assertThat(subject.kind()).isEqualTo(EvalSubjectKind.TOOL_CALL);
		}
	}

	@Nested
	@DisplayName("fromEvents")
	class FromEvents {

		@Test
		@DisplayName("converts pre-loaded event list")
		void convertsPreLoadedEvents() {
			List<JournalEvent> events = List.of(TestEvents.llmCall(), TestEvents.bashSuccess());

			EvalSubjectSource source = EvalSubjectSources.fromEvents(events, RUN_ID);
			List<EvalSubject> subjects = source.subjects().toList();

			assertThat(subjects).hasSize(2);
			assertThat(subjects.get(0).kind()).isEqualTo(EvalSubjectKind.LLM_CALL);
			assertThat(subjects.get(1).kind()).isEqualTo(EvalSubjectKind.TOOL_CALL);
		}

		@Test
		@DisplayName("returns empty for empty event list")
		void emptyEvents() {
			EvalSubjectSource source = EvalSubjectSources.fromEvents(List.of(), RUN_ID);
			assertThat(source.subjects().toList()).isEmpty();
		}
	}

}
