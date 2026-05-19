package io.github.markpollack.journal.feedback;

import io.github.markpollack.journal.Experiment;
import io.github.markpollack.journal.event.FeedbackEvent;
import io.github.markpollack.journal.event.FeedbackScore;
import io.github.markpollack.journal.event.FeedbackTarget;
import io.github.markpollack.journal.storage.JsonFileStorage;
import io.github.markpollack.journal.storage.RunData;
import io.github.markpollack.journal.test.BaseTrackingTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Feedback storage (JsonFileStorage)")
class FeedbackStorageTest extends BaseTrackingTest {

	private static final String EXP_ID = "exp-1";

	private static final String RUN_ID = "run-1";

	private JsonFileStorage storage;

	@BeforeEach
	void setUp() {
		storage = new JsonFileStorage(getStoragePath());
		storage.saveExperiment(Experiment.create(EXP_ID).build());
		storage.saveRun(RunData.builder().id(RUN_ID).experimentId(EXP_ID).build());
	}

	@Test
	@DisplayName("persists feedback to feedback.jsonl sidecar file")
	void persistsToFile() {
		storage.appendFeedback(EXP_ID, RUN_ID,
				FeedbackEvent.thumbsUp("item-1", "reviewer-1"));

		Path feedbackFile = getStoragePath()
				.resolve("experiments").resolve(EXP_ID)
				.resolve("runs").resolve(RUN_ID)
				.resolve("feedback.jsonl");

		assertThat(feedbackFile).exists();
		assertThat(feedbackFile).isNotEmptyFile();
	}

	@Test
	@DisplayName("feedback.jsonl is separate from events.jsonl")
	void separateFromEvents() {
		storage.appendFeedback(EXP_ID, RUN_ID,
				FeedbackEvent.thumbsUp("item-1", "reviewer-1"));

		// Execution events should be empty
		assertThat(storage.loadEvents(EXP_ID, RUN_ID)).isEmpty();

		// Feedback should have one entry
		assertThat(storage.loadFeedback(EXP_ID, RUN_ID)).hasSize(1);
	}

	@Test
	@DisplayName("round-trips thumbsUp feedback through JSONL")
	void roundTripThumbsUp() {
		storage.appendFeedback(EXP_ID, RUN_ID,
				FeedbackEvent.thumbsUp("item-1", "reviewer-1"));

		List<FeedbackEvent> loaded = storage.loadFeedback(EXP_ID, RUN_ID);

		assertThat(loaded).hasSize(1);
		FeedbackEvent fb = loaded.get(0);
		assertThat(fb.target().itemId()).isEqualTo("item-1");
		assertThat(fb.score().kind()).isEqualTo(FeedbackScore.ScoreKind.BINARY);
		assertThat(fb.score().value()).isEqualTo(1.0);
		assertThat(fb.reviewer()).isEqualTo("reviewer-1");
	}

	@Test
	@DisplayName("round-trips numerical rated feedback")
	void roundTripRated() {
		storage.appendFeedback(EXP_ID, RUN_ID,
				FeedbackEvent.rated("item-2", 4, 5, "reviewer-1", "Pretty good"));

		FeedbackEvent fb = storage.loadFeedback(EXP_ID, RUN_ID).get(0);

		assertThat(fb.score().kind()).isEqualTo(FeedbackScore.ScoreKind.NUMERICAL);
		assertThat(fb.score().value()).isEqualTo(4.0);
		assertThat(fb.score().max()).isEqualTo(5.0);
		assertThat(fb.comment()).isEqualTo("Pretty good");
	}

	@Test
	@DisplayName("round-trips subject-level feedback")
	void roundTripSubjectLevel() {
		var event = new FeedbackEvent(Instant.now(),
				FeedbackTarget.subject("item-1", "journal:run-1:3", "TOOL_CALL"),
				FeedbackScore.binary(false), "wrong params", "reviewer-1",
				Map.of("severity", "high"));

		storage.appendFeedback(EXP_ID, RUN_ID, event);

		FeedbackEvent fb = storage.loadFeedback(EXP_ID, RUN_ID).get(0);
		assertThat(fb.target().subjectId()).isEqualTo("journal:run-1:3");
		assertThat(fb.target().subjectKind()).isEqualTo("TOOL_CALL");
		assertThat(fb.labels()).containsEntry("severity", "high");
	}

	@Test
	@DisplayName("appends multiple feedback entries")
	void appendMultiple() {
		storage.appendFeedback(EXP_ID, RUN_ID,
				FeedbackEvent.thumbsUp("item-1", "reviewer-1"));
		storage.appendFeedback(EXP_ID, RUN_ID,
				FeedbackEvent.thumbsDown("item-2", "reviewer-1", "bad"));
		storage.appendFeedback(EXP_ID, RUN_ID,
				FeedbackEvent.rated("item-3", 3, 5, "reviewer-2", "ok"));

		List<FeedbackEvent> loaded = storage.loadFeedback(EXP_ID, RUN_ID);
		assertThat(loaded).hasSize(3);
	}

	@Test
	@DisplayName("returns empty for run with no feedback file")
	void noFeedbackFile() {
		assertThat(storage.loadFeedback(EXP_ID, RUN_ID)).isEmpty();
	}

}
