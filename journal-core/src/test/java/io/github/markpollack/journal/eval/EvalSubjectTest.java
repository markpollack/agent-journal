package io.github.markpollack.journal.eval;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("EvalSubject")
class EvalSubjectTest {

	@Nested
	@DisplayName("convenience accessors")
	class ConvenienceAccessors {

		@Test
		@DisplayName("costUsd returns value when present in metadata")
		void costUsdPresent() {
			EvalSubject subject = subject(Map.of("costUsd", 0.023));
			assertThat(subject.costUsd()).hasValue(0.023);
		}

		@Test
		@DisplayName("costUsd returns empty when missing")
		void costUsdMissing() {
			EvalSubject subject = subject(Map.of());
			assertThat(subject.costUsd()).isEmpty();
		}

		@Test
		@DisplayName("costUsd handles integer values")
		void costUsdInteger() {
			EvalSubject subject = subject(Map.of("costUsd", 1));
			assertThat(subject.costUsd()).hasValue(1.0);
		}

		@Test
		@DisplayName("durationMs returns value when present")
		void durationMsPresent() {
			EvalSubject subject = subject(Map.of("durationMs", 2500L));
			assertThat(subject.durationMs()).hasValue(2500L);
		}

		@Test
		@DisplayName("durationMs returns empty when missing")
		void durationMsMissing() {
			EvalSubject subject = subject(Map.of());
			assertThat(subject.durationMs()).isEmpty();
		}

		@Test
		@DisplayName("success returns value when present")
		void successPresent() {
			EvalSubject subject = subject(Map.of("success", true));
			assertThat(subject.success()).hasValue(true);
		}

		@Test
		@DisplayName("success returns empty when missing")
		void successMissing() {
			EvalSubject subject = subject(Map.of());
			assertThat(subject.success()).isEmpty();
		}

		@Test
		@DisplayName("failed returns true when success is false")
		void failedWhenSuccessFalse() {
			EvalSubject subject = subject(Map.of("success", false));
			assertThat(subject.failed()).isTrue();
		}

		@Test
		@DisplayName("failed returns false when success is true")
		void failedWhenSuccessTrue() {
			EvalSubject subject = subject(Map.of("success", true));
			assertThat(subject.failed()).isFalse();
		}

		@Test
		@DisplayName("failed returns false when success is missing")
		void failedWhenSuccessMissing() {
			EvalSubject subject = subject(Map.of());
			assertThat(subject.failed()).isFalse();
		}

		@Test
		@DisplayName("itemIdOpt returns value when present")
		void itemIdOptPresent() {
			EvalSubject subject = new EvalSubject("id-1", EvalSubjectKind.TOOL_CALL,
					"journal", "run-1", "item-42", "goal", null, null, Map.of());
			assertThat(subject.itemIdOpt()).hasValue("item-42");
		}

		@Test
		@DisplayName("itemIdOpt returns empty when null")
		void itemIdOptNull() {
			EvalSubject subject = subject(Map.of());
			assertThat(subject.itemIdOpt()).isEmpty();
		}
	}

	@Nested
	@DisplayName("record equality")
	class RecordEquality {

		@Test
		@DisplayName("equal subjects are equal")
		void equalSubjects() {
			EvalSubject a = subject(Map.of("costUsd", 0.01));
			EvalSubject b = subject(Map.of("costUsd", 0.01));
			assertThat(a).isEqualTo(b);
		}

		@Test
		@DisplayName("different subjects are not equal")
		void differentSubjects() {
			EvalSubject a = subject(Map.of("costUsd", 0.01));
			EvalSubject b = new EvalSubject("id-2", EvalSubjectKind.LLM_CALL,
					"journal", "run-1", null, "goal", null, null, Map.of());
			assertThat(a).isNotEqualTo(b);
		}
	}

	private static EvalSubject subject(Map<String, Object> metadata) {
		return new EvalSubject("id-1", EvalSubjectKind.TOOL_CALL, "journal",
				"run-1", null, "goal", null, null, metadata);
	}

}
