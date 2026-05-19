package io.github.markpollack.journal.eval;

import io.github.markpollack.journal.test.TestEvents;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("EvalSubjectQuery")
class EvalSubjectQueryTest {

	private EvalSubjectSource source;

	@BeforeEach
	void setUp() {
		// Create a mixed source from agentTurnSequence events
		// agentTurnSequence: stateChange, llmCall, bash, read, llmCallThinking, write,
		// tokenMetric, costMetric, stateChange
		// After filtering: 2 state changes, 2 llm calls, 3 tool calls = 7 subjects
		source = EvalSubjectSources.fromEvents(TestEvents.agentTurnSequence(), "run-1");
	}

	@Nested
	@DisplayName("kind filter")
	class KindFilter {

		@Test
		@DisplayName("filters by TOOL_CALL kind")
		void filterToolCalls() {
			EvalSubjectSet result = EvalSubjectQuery.from(source)
					.kind(EvalSubjectKind.TOOL_CALL)
					.toSet();

			assertThat(result.size()).isEqualTo(3);
			assertThat(result.stream()).allMatch(s -> s.kind() == EvalSubjectKind.TOOL_CALL);
		}

		@Test
		@DisplayName("filters by LLM_CALL kind")
		void filterLlmCalls() {
			EvalSubjectSet result = EvalSubjectQuery.from(source)
					.kind(EvalSubjectKind.LLM_CALL)
					.toSet();

			assertThat(result.size()).isEqualTo(2);
		}

		@Test
		@DisplayName("filters by STATE_CHANGE kind")
		void filterStateChanges() {
			EvalSubjectSet result = EvalSubjectQuery.from(source)
					.kind(EvalSubjectKind.STATE_CHANGE)
					.toSet();

			assertThat(result.size()).isEqualTo(2);
		}

		@Test
		@DisplayName("returns empty for kind with no matches")
		void noMatches() {
			EvalSubjectSet result = EvalSubjectQuery.from(source)
					.kind(EvalSubjectKind.ROUTER_DECISION)
					.toSet();

			assertThat(result.isEmpty()).isTrue();
		}
	}

	@Nested
	@DisplayName("where predicate")
	class WherePredicate {

		@Test
		@DisplayName("filters with custom predicate")
		void customPredicate() {
			// Filter tool calls with "Bash" tool name
			EvalSubjectSet result = EvalSubjectQuery.from(source)
					.kind(EvalSubjectKind.TOOL_CALL)
					.where(s -> "Bash".equals(s.metadata().get("toolName")))
					.toSet();

			assertThat(result.size()).isEqualTo(1);
			assertThat(result.subjects().get(0).metadata()).containsEntry("toolName", "Bash");
		}

		@Test
		@DisplayName("composes multiple predicates")
		void multiplePredicates() {
			// Add a failed event source
			EvalSubjectSource failedSource = EvalSubjectSources.fromEvents(
					TestEvents.failedRunSequence(), "run-1");

			EvalSubjectSet result = EvalSubjectQuery.from(failedSource)
					.kind(EvalSubjectKind.TOOL_CALL)
					.where(EvalSubject::failed)
					.toSet();

			assertThat(result.size()).isEqualTo(1);
		}
	}

	@Nested
	@DisplayName("toSet")
	class ToSet {

		@Test
		@DisplayName("returns all subjects with no filters")
		void noFilters() {
			EvalSubjectSet result = EvalSubjectQuery.from(source).toSet();
			assertThat(result.size()).isEqualTo(7);
		}

		@Test
		@DisplayName("returned set is immutable")
		void immutableSet() {
			EvalSubjectSet result = EvalSubjectQuery.from(source).toSet();
			assertThat(result.subjects()).isUnmodifiable();
		}
	}

	@Nested
	@DisplayName("groupBy")
	class GroupBy {

		@Test
		@DisplayName("groups by kind")
		void groupByKind() {
			Map<EvalSubjectKind, EvalSubjectSet> groups = EvalSubjectQuery.from(source)
					.groupBy(EvalSubject::kind);

			assertThat(groups).containsKeys(EvalSubjectKind.TOOL_CALL, EvalSubjectKind.LLM_CALL,
					EvalSubjectKind.STATE_CHANGE);
			assertThat(groups.get(EvalSubjectKind.TOOL_CALL).size()).isEqualTo(3);
			assertThat(groups.get(EvalSubjectKind.LLM_CALL).size()).isEqualTo(2);
			assertThat(groups.get(EvalSubjectKind.STATE_CHANGE).size()).isEqualTo(2);
		}

		@Test
		@DisplayName("groups by tool name within TOOL_CALL filter")
		void groupByToolName() {
			Map<Object, EvalSubjectSet> groups = EvalSubjectQuery.from(source)
					.kind(EvalSubjectKind.TOOL_CALL)
					.groupBy(s -> s.metadata().get("toolName"));

			assertThat(groups).containsKeys("Bash", "Read", "Write");
			assertThat(groups.get("Bash").size()).isEqualTo(1);
			assertThat(groups.get("Read").size()).isEqualTo(1);
			assertThat(groups.get("Write").size()).isEqualTo(1);
		}
	}

	@Nested
	@DisplayName("countBy")
	class CountBy {

		@Test
		@DisplayName("counts by kind")
		void countByKind() {
			Map<EvalSubjectKind, Long> counts = EvalSubjectQuery.from(source)
					.countBy(EvalSubject::kind);

			assertThat(counts).containsEntry(EvalSubjectKind.TOOL_CALL, 3L);
			assertThat(counts).containsEntry(EvalSubjectKind.LLM_CALL, 2L);
			assertThat(counts).containsEntry(EvalSubjectKind.STATE_CHANGE, 2L);
		}
	}

	@Nested
	@DisplayName("count")
	class Count {

		@Test
		@DisplayName("counts all matching subjects")
		void countAll() {
			assertThat(EvalSubjectQuery.from(source).count()).isEqualTo(7);
		}

		@Test
		@DisplayName("counts filtered subjects")
		void countFiltered() {
			long toolCallCount = EvalSubjectQuery.from(source)
					.kind(EvalSubjectKind.TOOL_CALL)
					.count();
			assertThat(toolCallCount).isEqualTo(3);
		}
	}

	@Nested
	@DisplayName("EvalSubjectSet")
	class SetBehavior {

		@Test
		@DisplayName("supports iteration via for-each")
		void iteration() {
			EvalSubjectSet set = EvalSubjectQuery.from(source)
					.kind(EvalSubjectKind.TOOL_CALL)
					.toSet();

			int count = 0;
			for (EvalSubject subject : set) {
				assertThat(subject.kind()).isEqualTo(EvalSubjectKind.TOOL_CALL);
				count++;
			}
			assertThat(count).isEqualTo(3);
		}

		@Test
		@DisplayName("supports streaming")
		void streaming() {
			EvalSubjectSet set = EvalSubjectQuery.from(source)
					.kind(EvalSubjectKind.TOOL_CALL)
					.toSet();

			List<String> toolNames = set.stream()
					.map(s -> (String) s.metadata().get("toolName"))
					.toList();

			assertThat(toolNames).containsExactly("Bash", "Read", "Write");
		}

		@Test
		@DisplayName("empty set reports isEmpty")
		void emptySet() {
			EvalSubjectSet set = EvalSubjectQuery.from(source)
					.kind(EvalSubjectKind.ROUTER_DECISION)
					.toSet();

			assertThat(set.isEmpty()).isTrue();
			assertThat(set.size()).isZero();
		}
	}

}
