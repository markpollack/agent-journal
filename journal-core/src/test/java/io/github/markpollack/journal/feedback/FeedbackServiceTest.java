package io.github.markpollack.journal.feedback;

import io.github.markpollack.journal.Config;
import io.github.markpollack.journal.Experiment;
import io.github.markpollack.journal.Summary;
import io.github.markpollack.journal.event.FeedbackEvent;
import io.github.markpollack.journal.event.FeedbackScore;
import io.github.markpollack.journal.event.FeedbackTarget;
import io.github.markpollack.journal.storage.InMemoryStorage;
import io.github.markpollack.journal.storage.RunData;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("FeedbackService")
class FeedbackServiceTest {

	private static final String EXP_ID = "exp-1";

	private static final String RUN_ID = "run-1";

	private InMemoryStorage storage;

	private DefaultFeedbackService service;

	@BeforeEach
	void setUp() {
		storage = new InMemoryStorage();
		service = new DefaultFeedbackService(storage);
		storage.saveExperiment(Experiment.create(EXP_ID).build());
		storage.saveRun(RunData.builder()
				.id(RUN_ID)
				.experimentId(EXP_ID)
				.config(Config.of("model", "claude-opus-4.5"))
				.summary(Summary.of("success", true, "totalCostUsd", 0.15))
				.build());
	}

	@Nested
	@DisplayName("recordFeedback and getFeedback")
	class RecordAndGet {

		@Test
		@DisplayName("records and retrieves feedback")
		void recordAndRetrieve() {
			service.recordFeedback(EXP_ID, RUN_ID,
					FeedbackEvent.thumbsUp("item-1", "reviewer-1"));
			service.recordFeedback(EXP_ID, RUN_ID,
					FeedbackEvent.thumbsDown("item-2", "reviewer-1", "Bad output"));

			List<FeedbackEvent> feedback = service.getFeedback(EXP_ID, RUN_ID);
			assertThat(feedback).hasSize(2);
			assertThat(feedback.get(0).target().itemId()).isEqualTo("item-1");
			assertThat(feedback.get(1).target().itemId()).isEqualTo("item-2");
		}

		@Test
		@DisplayName("returns empty for run with no feedback")
		void emptyFeedback() {
			assertThat(service.getFeedback(EXP_ID, RUN_ID)).isEmpty();
		}

		@Test
		@DisplayName("records sub-item feedback")
		void subItemFeedback() {
			var event = new FeedbackEvent(null,
					FeedbackTarget.subject("item-1", "tool-42", "TOOL_CALL"),
					FeedbackScore.binary(false), "wrong params", "reviewer-1", Map.of());

			service.recordFeedback(EXP_ID, RUN_ID, event);

			FeedbackEvent loaded = service.getFeedback(EXP_ID, RUN_ID).get(0);
			assertThat(loaded.target().subjectId()).isEqualTo("tool-42");
			assertThat(loaded.target().subjectKind()).isEqualTo("TOOL_CALL");
		}
	}

	@Nested
	@DisplayName("getFeedbackForItem")
	class GetForItem {

		@Test
		@DisplayName("finds feedback across runs for a specific item")
		void acrossRuns() {
			String run2 = "run-2";
			storage.saveRun(RunData.builder().id(run2).experimentId(EXP_ID).build());

			service.recordFeedback(EXP_ID, RUN_ID,
					FeedbackEvent.thumbsUp("item-1", "reviewer-1"));
			service.recordFeedback(EXP_ID, RUN_ID,
					FeedbackEvent.thumbsDown("item-2", "reviewer-1", "bad"));
			service.recordFeedback(EXP_ID, run2,
					FeedbackEvent.rated("item-1", 4, 5, "reviewer-2", "good"));

			List<FeedbackEvent> item1Feedback = service.getFeedbackForItem(EXP_ID, "item-1");
			assertThat(item1Feedback).hasSize(2);
		}

		@Test
		@DisplayName("returns empty when no matching items")
		void noMatches() {
			service.recordFeedback(EXP_ID, RUN_ID,
					FeedbackEvent.thumbsUp("item-1", "reviewer-1"));

			assertThat(service.getFeedbackForItem(EXP_ID, "item-99")).isEmpty();
		}
	}

	@Nested
	@DisplayName("exportReviewedItems")
	class ExportReviewedItems {

		@Test
		@DisplayName("exports reviewed items with run metadata")
		void exportsWithMetadata() {
			service.recordFeedback(EXP_ID, RUN_ID,
					FeedbackEvent.thumbsUp("item-1", "reviewer-1"));
			service.recordFeedback(EXP_ID, RUN_ID,
					FeedbackEvent.rated("item-2", 3, 5, "reviewer-1", "meh"));

			List<ReviewedItem> items = service.exportReviewedItems(EXP_ID);

			assertThat(items).hasSize(2);

			ReviewedItem first = items.get(0);
			assertThat(first.itemId()).isEqualTo("item-1");
			assertThat(first.runId()).isEqualTo(RUN_ID);
			assertThat(first.feedback().score().kind()).isEqualTo(FeedbackScore.ScoreKind.BINARY);
			// Run metadata includes config + summary
			assertThat(first.itemMetadata()).containsEntry("model", "claude-opus-4.5");
			assertThat(first.itemMetadata()).containsEntry("success", true);
			assertThat(first.itemMetadata()).containsEntry("totalCostUsd", 0.15);
		}

		@Test
		@DisplayName("skips run-level feedback (no itemId)")
		void skipsRunLevel() {
			service.recordFeedback(EXP_ID, RUN_ID,
					new FeedbackEvent(null, FeedbackTarget.run(),
							FeedbackScore.binary(true), null, "reviewer-1", Map.of()));
			service.recordFeedback(EXP_ID, RUN_ID,
					FeedbackEvent.thumbsUp("item-1", "reviewer-1"));

			List<ReviewedItem> items = service.exportReviewedItems(EXP_ID);
			assertThat(items).hasSize(1);
			assertThat(items.get(0).itemId()).isEqualTo("item-1");
		}

		@Test
		@DisplayName("collects from multiple runs")
		void multipleRuns() {
			String run2 = "run-2";
			storage.saveRun(RunData.builder().id(run2).experimentId(EXP_ID).build());

			service.recordFeedback(EXP_ID, RUN_ID,
					FeedbackEvent.thumbsUp("item-1", "reviewer-1"));
			service.recordFeedback(EXP_ID, run2,
					FeedbackEvent.thumbsDown("item-2", "reviewer-1", "bad"));

			List<ReviewedItem> items = service.exportReviewedItems(EXP_ID);
			assertThat(items).hasSize(2);
			assertThat(items).extracting(ReviewedItem::runId)
					.containsExactlyInAnyOrder(RUN_ID, run2);
		}

		@Test
		@DisplayName("returns empty for experiment with no feedback")
		void emptyExport() {
			assertThat(service.exportReviewedItems(EXP_ID)).isEmpty();
		}
	}

	@Nested
	@DisplayName("computeJudgeAgreement")
	class JudgeAgreement {

		private FeedbackEvent verdict(String subjectId, String reviewer, boolean pass) {
			return new FeedbackEvent(java.time.Instant.now(),
					FeedbackTarget.subject(null, subjectId, "TOOL_CALL"),
					FeedbackScore.binary(pass), null, reviewer, Map.of());
		}

		@Test
		@DisplayName("computes per-judge agreement over targets both scored")
		void computesAgreement() {
			// human vs judge-A: agree on s1 (pass/pass) and s3 (pass/pass), disagree on s2 → 2/3
			service.recordFeedback(EXP_ID, RUN_ID, verdict("s1", "human", true));
			service.recordFeedback(EXP_ID, RUN_ID, verdict("s2", "human", false));
			service.recordFeedback(EXP_ID, RUN_ID, verdict("s3", "human", true));
			service.recordFeedback(EXP_ID, RUN_ID, verdict("s1", "judge-A", true));
			service.recordFeedback(EXP_ID, RUN_ID, verdict("s2", "judge-A", true));
			service.recordFeedback(EXP_ID, RUN_ID, verdict("s3", "judge-A", true));
			// judge-B disagrees on its only shared target s1 → 0/1
			service.recordFeedback(EXP_ID, RUN_ID, verdict("s1", "judge-B", false));

			Map<String, Double> agreement = service.computeJudgeAgreement(EXP_ID, RUN_ID, "human", 0.5);

			assertThat(agreement).containsOnlyKeys("judge-A", "judge-B");
			assertThat(agreement.get("judge-A")).isEqualTo(2.0 / 3.0);
			assertThat(agreement.get("judge-B")).isEqualTo(0.0);
		}

		@Test
		@DisplayName("latest verdict wins and only shared targets count")
		void latestWinsAndOnlyShared() {
			service.recordFeedback(EXP_ID, RUN_ID, verdict("s1", "human", true));
			// judge scored s1 twice — latest (pass) wins → agrees with human
			service.recordFeedback(EXP_ID, RUN_ID, verdict("s1", "judge", false));
			service.recordFeedback(EXP_ID, RUN_ID, verdict("s1", "judge", true));
			// judge also scored s9 the human never touched — not counted
			service.recordFeedback(EXP_ID, RUN_ID, verdict("s9", "judge", false));

			Map<String, Double> agreement = service.computeJudgeAgreement(EXP_ID, RUN_ID, "human", 0.5);
			assertThat(agreement.get("judge")).isEqualTo(1.0);
		}

		@Test
		@DisplayName("empty when the human gave no usable feedback")
		void emptyWithoutHumanBaseline() {
			service.recordFeedback(EXP_ID, RUN_ID, verdict("s1", "judge-A", true));
			assertThat(service.computeJudgeAgreement(EXP_ID, RUN_ID, "human", 0.5)).isEmpty();
		}
	}

}
