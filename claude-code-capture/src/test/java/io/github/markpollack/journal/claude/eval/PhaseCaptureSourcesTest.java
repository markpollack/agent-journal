package io.github.markpollack.journal.claude.eval;

import io.github.markpollack.journal.claude.PhaseCapture;
import io.github.markpollack.journal.claude.ToolResultRecord;
import io.github.markpollack.journal.claude.ToolUseRecord;
import io.github.markpollack.journal.eval.EvalSubject;
import io.github.markpollack.journal.eval.EvalSubjectKind;
import io.github.markpollack.journal.eval.EvalSubjectQuery;
import io.github.markpollack.journal.eval.EvalSubjectSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("PhaseCaptureSources")
class PhaseCaptureSourcesTest {

	@Nested
	@DisplayName("fromPhaseCaptures")
	class FromPhaseCaptures {

		@Test
		@DisplayName("maps phase to LLM_CALL subject")
		void mapsPhaseToLlmCall() {
			PhaseCapture phase = new PhaseCapture("explore", "What files exist?",
					100, 50, 0, 1000, 800, 0.01, "sess-1", 2, false,
					"Found 3 files", List.of(), List.of(), null);

			EvalSubjectSource source = PhaseCaptureSources.fromPhaseCaptures(List.of(phase));
			List<EvalSubject> subjects = source.subjects().toList();

			assertThat(subjects).hasSize(1);
			EvalSubject subject = subjects.get(0);
			assertThat(subject.kind()).isEqualTo(EvalSubjectKind.LLM_CALL);
			assertThat(subject.source()).isEqualTo("phase_capture");
			assertThat(subject.goal()).isEqualTo("Phase: explore");
			assertThat(subject.input()).isEqualTo("What files exist?");
			assertThat(subject.output()).isEqualTo("Found 3 files");
			assertThat(subject.costUsd()).hasValue(0.01);
			assertThat(subject.durationMs()).hasValue(1000L);
			assertThat(subject.metadata()).containsEntry("phaseName", "explore");
			assertThat(subject.metadata()).containsEntry("sessionId", "sess-1");
		}

		@Test
		@DisplayName("maps tool uses within phase to TOOL_CALL subjects")
		void mapsToolUsesToToolCalls() {
			ToolUseRecord toolUse = new ToolUseRecord("t-1", "Read",
					Map.of("file_path", "/pom.xml"));
			ToolResultRecord toolResult = new ToolResultRecord("t-1", "<project>...</project>", false);

			PhaseCapture phase = new PhaseCapture("act", "Read the pom",
					100, 50, 0, 0, 0, 1000, 800, 0.01, "sess-1", 2, false,
					"output", List.of(), List.of(toolUse), null,
					List.of(toolResult));

			EvalSubjectSource source = PhaseCaptureSources.fromPhaseCaptures(List.of(phase));
			List<EvalSubject> subjects = source.subjects().toList();

			// 1 phase-level LLM_CALL + 1 tool-level TOOL_CALL
			assertThat(subjects).hasSize(2);

			EvalSubject toolSubject = subjects.get(1);
			assertThat(toolSubject.kind()).isEqualTo(EvalSubjectKind.TOOL_CALL);
			assertThat(toolSubject.goal()).isEqualTo("Tool call: Read");
			assertThat(toolSubject.input()).isEqualTo(Map.of("file_path", "/pom.xml"));
			assertThat(toolSubject.output()).isEqualTo("<project>...</project>");
			assertThat(toolSubject.success()).hasValue(true);
			assertThat(toolSubject.metadata()).containsEntry("toolName", "Read");
		}

		@Test
		@DisplayName("marks failed tool results correctly")
		void marksFailedToolResults() {
			ToolUseRecord toolUse = new ToolUseRecord("t-1", "Bash",
					Map.of("command", "rm -rf /"));
			ToolResultRecord toolResult = new ToolResultRecord("t-1", "Permission denied", true);

			PhaseCapture phase = new PhaseCapture("act", "Run command",
					100, 50, 0, 0, 0, 1000, 800, 0.01, "sess-1", 2, false,
					"output", List.of(), List.of(toolUse), null,
					List.of(toolResult));

			EvalSubjectSource source = PhaseCaptureSources.fromPhaseCaptures(List.of(phase));
			List<EvalSubject> subjects = source.subjects().toList();

			EvalSubject toolSubject = subjects.get(1);
			assertThat(toolSubject.success()).hasValue(false);
			assertThat(toolSubject.failed()).isTrue();
		}

		@Test
		@DisplayName("handles error phase")
		void handlesErrorPhase() {
			PhaseCapture phase = new PhaseCapture("execute", "Run the plan",
					100, 50, 0, 5000, 4000, 0.05, "sess-1", 3, true,
					"Error occurred", List.of(), List.of(), null);

			EvalSubjectSource source = PhaseCaptureSources.fromPhaseCaptures(List.of(phase));
			EvalSubject subject = source.subjects().toList().get(0);

			assertThat(subject.success()).hasValue(false);
			assertThat(subject.failed()).isTrue();
		}

		@Test
		@DisplayName("handles multiple phases with sequential IDs")
		void handlesMultiplePhases() {
			PhaseCapture phase1 = new PhaseCapture("explore", null,
					100, 50, 0, 1000, 800, 0.01, "sess-1", 1, false,
					"output1", List.of(), List.of(), null);
			PhaseCapture phase2 = new PhaseCapture("act", null,
					200, 100, 25, 2000, 1500, 0.02, "sess-1", 2, false,
					"output2", List.of(), List.of(), null);

			EvalSubjectSource source = PhaseCaptureSources.fromPhaseCaptures(
					List.of(phase1, phase2));
			List<EvalSubject> subjects = source.subjects().toList();

			assertThat(subjects).hasSize(2);
			assertThat(subjects.get(0).id()).isEqualTo("phase:sess-1:0");
			assertThat(subjects.get(1).id()).isEqualTo("phase:sess-1:1");
		}

		@Test
		@DisplayName("handles empty phase list")
		void handlesEmptyPhases() {
			EvalSubjectSource source = PhaseCaptureSources.fromPhaseCaptures(List.of());
			assertThat(source.subjects().toList()).isEmpty();
		}

		@Test
		@DisplayName("works with EvalSubjectQuery for end-to-end filtering")
		void endToEndWithQuery() {
			ToolUseRecord read = new ToolUseRecord("t-1", "Read", Map.of());
			ToolUseRecord bash = new ToolUseRecord("t-2", "Bash", Map.of("command", "ls"));
			ToolResultRecord readResult = new ToolResultRecord("t-1", "contents", false);
			ToolResultRecord bashResult = new ToolResultRecord("t-2", "error", true);

			PhaseCapture phase = new PhaseCapture("act", "Do work",
					100, 50, 0, 0, 0, 1000, 800, 0.01, "sess-1", 2, false,
					"output", List.of(), List.of(read, bash), null,
					List.of(readResult, bashResult));

			EvalSubjectSource source = PhaseCaptureSources.fromPhaseCaptures(List.of(phase));

			long failedTools = EvalSubjectQuery.from(source)
					.kind(EvalSubjectKind.TOOL_CALL)
					.where(EvalSubject::failed)
					.count();

			assertThat(failedTools).isEqualTo(1);
		}
	}

}
