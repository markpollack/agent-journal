package io.github.markpollack.journal.event;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("FeedbackEvent")
class FeedbackEventTest {

	@Nested
	@DisplayName("factory methods")
	class FactoryMethods {

		@Test
		@DisplayName("thumbsUp creates binary positive feedback")
		void thumbsUp() {
			FeedbackEvent event = FeedbackEvent.thumbsUp("item-1", "reviewer-1");

			assertThat(event.target().itemId()).isEqualTo("item-1");
			assertThat(event.score().kind()).isEqualTo(FeedbackScore.ScoreKind.BINARY);
			assertThat(event.score().value()).isEqualTo(1.0);
			assertThat(event.reviewer()).isEqualTo("reviewer-1");
			assertThat(event.comment()).isNull();
			assertThat(event.type()).isEqualTo("feedback");
		}

		@Test
		@DisplayName("thumbsDown creates binary negative feedback with reason")
		void thumbsDown() {
			FeedbackEvent event = FeedbackEvent.thumbsDown("item-2", "reviewer-1",
					"Hallucinated the date");

			assertThat(event.score().value()).isEqualTo(0.0);
			assertThat(event.comment()).isEqualTo("Hallucinated the date");
		}

		@Test
		@DisplayName("rated creates numerical feedback")
		void rated() {
			FeedbackEvent event = FeedbackEvent.rated("item-3", 4, 5,
					"reviewer-1", "Good but missed edge case");

			assertThat(event.score().kind()).isEqualTo(FeedbackScore.ScoreKind.NUMERICAL);
			assertThat(event.score().value()).isEqualTo(4.0);
			assertThat(event.score().max()).isEqualTo(5.0);
			assertThat(event.comment()).isEqualTo("Good but missed edge case");
		}

		@Test
		@DisplayName("labeled creates feedback with domain labels")
		void labeled() {
			FeedbackEvent event = FeedbackEvent.labeled("item-4", "reviewer-1",
					Map.of("category", "factual_error", "severity", "high"));

			assertThat(event.labels()).containsEntry("category", "factual_error");
			assertThat(event.labels()).containsEntry("severity", "high");
		}
	}

	@Nested
	@DisplayName("FeedbackTarget")
	class TargetTests {

		@Test
		@DisplayName("item-level target")
		void itemTarget() {
			FeedbackTarget target = FeedbackTarget.item("item-1");

			assertThat(target.itemId()).isEqualTo("item-1");
			assertThat(target.subjectId()).isNull();
			assertThat(target.subjectKind()).isNull();
		}

		@Test
		@DisplayName("subject-level target")
		void subjectTarget() {
			FeedbackTarget target = FeedbackTarget.subject("item-1", "tool-42", "TOOL_CALL");

			assertThat(target.itemId()).isEqualTo("item-1");
			assertThat(target.subjectId()).isEqualTo("tool-42");
			assertThat(target.subjectKind()).isEqualTo("TOOL_CALL");
		}

		@Test
		@DisplayName("run-level target")
		void runTarget() {
			FeedbackTarget target = FeedbackTarget.run();

			assertThat(target.itemId()).isNull();
			assertThat(target.subjectId()).isNull();
			assertThat(target.subjectKind()).isNull();
		}

		@Test
		@DisplayName("subjectKind is String, not enum")
		void subjectKindIsString() {
			FeedbackTarget target = FeedbackTarget.subject("item-1", "sub-1", "CUSTOM_KIND");
			assertThat(target.subjectKind()).isEqualTo("CUSTOM_KIND");
		}
	}

	@Nested
	@DisplayName("FeedbackScore")
	class ScoreTests {

		@Test
		@DisplayName("binary positive normalizes to 1.0")
		void binaryPositive() {
			FeedbackScore score = FeedbackScore.binary(true);

			assertThat(score.kind()).isEqualTo(FeedbackScore.ScoreKind.BINARY);
			assertThat(score.normalized()).hasValue(1.0);
		}

		@Test
		@DisplayName("binary negative normalizes to 0.0")
		void binaryNegative() {
			FeedbackScore score = FeedbackScore.binary(false);
			assertThat(score.normalized()).hasValue(0.0);
		}

		@Test
		@DisplayName("numerical normalizes to value/max")
		void numericalNormalization() {
			FeedbackScore score = FeedbackScore.numerical(4, 5);
			assertThat(score.normalized()).hasValue(0.8);
		}

		@Test
		@DisplayName("numerical with zero max normalizes to 0.0")
		void numericalZeroMax() {
			FeedbackScore score = FeedbackScore.numerical(3, 0);
			assertThat(score.normalized()).hasValue(0.0);
		}

		@Test
		@DisplayName("categorical returns empty normalized")
		void categoricalEmpty() {
			FeedbackScore score = FeedbackScore.categorical("factual_error");

			assertThat(score.kind()).isEqualTo(FeedbackScore.ScoreKind.CATEGORICAL);
			assertThat(score.category()).isEqualTo("factual_error");
			assertThat(score.normalized()).isEmpty();
		}

		@Test
		@DisplayName("toMap includes all fields")
		void toMap() {
			FeedbackScore score = FeedbackScore.numerical(3, 5);
			Map<String, Object> map = score.toMap();

			assertThat(map).containsEntry("kind", "NUMERICAL");
			assertThat(map).containsEntry("value", 3.0);
			assertThat(map).containsEntry("max", 5.0);
		}
	}

	@Nested
	@DisplayName("compact constructor defaults")
	class ConstructorDefaults {

		@Test
		@DisplayName("null timestamp defaults to now")
		void nullTimestamp() {
			Instant before = Instant.now();
			FeedbackEvent event = new FeedbackEvent(null, FeedbackTarget.item("i"),
					FeedbackScore.binary(true), null, "r", null);
			assertThat(event.timestamp()).isAfterOrEqualTo(before);
		}

		@Test
		@DisplayName("null target defaults to run-level")
		void nullTarget() {
			FeedbackEvent event = new FeedbackEvent(Instant.now(), null,
					FeedbackScore.binary(true), null, "r", null);
			assertThat(event.target()).isEqualTo(FeedbackTarget.run());
		}

		@Test
		@DisplayName("null labels defaults to empty map")
		void nullLabels() {
			FeedbackEvent event = new FeedbackEvent(Instant.now(), FeedbackTarget.item("i"),
					FeedbackScore.binary(true), null, "r", null);
			assertThat(event.labels()).isEmpty();
		}

		@Test
		@DisplayName("labels are defensively copied")
		void labelsDefensivelyCopied() {
			FeedbackEvent event = FeedbackEvent.labeled("i", "r",
					Map.of("k", "v"));
			assertThat(event.labels()).isUnmodifiable();
		}
	}

	@Nested
	@DisplayName("Jackson serialization")
	class Serialization {

		private final ObjectMapper mapper = new ObjectMapper()
				.registerModule(new JavaTimeModule());

		@Test
		@DisplayName("round-trips FeedbackEvent through JSON")
		void roundTrip() throws Exception {
			FeedbackEvent original = FeedbackEvent.thumbsDown("item-1", "reviewer-1",
					"Bad output");

			String json = mapper.writeValueAsString(original);
			JournalEvent deserialized = mapper.readValue(json, JournalEvent.class);

			assertThat(deserialized).isInstanceOf(FeedbackEvent.class);
			FeedbackEvent feedback = (FeedbackEvent) deserialized;
			assertThat(feedback.target().itemId()).isEqualTo("item-1");
			assertThat(feedback.score().kind()).isEqualTo(FeedbackScore.ScoreKind.BINARY);
			assertThat(feedback.score().value()).isEqualTo(0.0);
			assertThat(feedback.comment()).isEqualTo("Bad output");
			assertThat(feedback.reviewer()).isEqualTo("reviewer-1");
		}

		@Test
		@DisplayName("round-trips numerical score")
		void roundTripNumerical() throws Exception {
			FeedbackEvent original = FeedbackEvent.rated("item-2", 3, 5,
					"reviewer-1", "OK");

			String json = mapper.writeValueAsString(original);
			FeedbackEvent feedback = mapper.readValue(json, FeedbackEvent.class);

			assertThat(feedback.score().kind()).isEqualTo(FeedbackScore.ScoreKind.NUMERICAL);
			assertThat(feedback.score().value()).isEqualTo(3.0);
			assertThat(feedback.score().max()).isEqualTo(5.0);
		}

		@Test
		@DisplayName("round-trips subject-level target")
		void roundTripSubjectTarget() throws Exception {
			FeedbackEvent original = new FeedbackEvent(Instant.now(),
					FeedbackTarget.subject("item-1", "tool-42", "TOOL_CALL"),
					FeedbackScore.binary(false), "wrong params", "reviewer-1", Map.of());

			String json = mapper.writeValueAsString(original);
			FeedbackEvent feedback = mapper.readValue(json, FeedbackEvent.class);

			assertThat(feedback.target().subjectId()).isEqualTo("tool-42");
			assertThat(feedback.target().subjectKind()).isEqualTo("TOOL_CALL");
		}

		@Test
		@DisplayName("round-trips with labels")
		void roundTripLabels() throws Exception {
			FeedbackEvent original = FeedbackEvent.labeled("item-1", "reviewer-1",
					Map.of("category", "hallucination"));

			String json = mapper.writeValueAsString(original);
			FeedbackEvent feedback = mapper.readValue(json, FeedbackEvent.class);

			assertThat(feedback.labels()).containsEntry("category", "hallucination");
		}

		@Test
		@DisplayName("JSON contains @type discriminator")
		void typeDiscriminator() throws Exception {
			FeedbackEvent event = FeedbackEvent.thumbsUp("item-1", "reviewer-1");
			String json = mapper.writeValueAsString(event);
			assertThat(json).contains("\"@type\":\"feedback\"");
		}
	}

}
