/*
 * Copyright (c) 2026 Mark Pollack
 *
 * Licensed under the Business Source License 1.1 (BSL).
 * See the LICENSE file in the repository root for the full license text.
 */

package io.github.markpollack.journal;

import io.github.markpollack.journal.metric.Tags;
import io.github.markpollack.journal.test.BaseTrackingTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests for core domain model classes: Experiment, Summary.
 * Config, Tags, and RunStatus are tested in TestInfrastructureTest.
 */
@DisplayName("Domain Model")
class DomainModelTest extends BaseTrackingTest {

    @Nested
    @DisplayName("Experiment")
    class ExperimentTests {

        @Test
        @DisplayName("creates experiment with minimal fields")
        void createsWithMinimalFields() {
            Experiment exp = Experiment.create("my-experiment").build();

            assertThat(exp.id()).isEqualTo("my-experiment");
            assertThat(exp.name()).isEqualTo("my-experiment"); // defaults to id
            assertThat(exp.createdAt()).isNotNull();
            assertThat(exp.description()).isNull();
            assertThat(exp.tags().isEmpty()).isTrue();
        }

        @Test
        @DisplayName("creates experiment with all fields")
        void createsWithAllFields() {
            Instant created = Instant.parse("2024-12-30T10:00:00Z");
            Tags tags = Tags.of("feature", "auth", "priority", "high");

            Experiment exp = Experiment.create("implement-oauth")
                    .name("Implement OAuth Feature")
                    .description("Add OAuth2 authentication support")
                    .createdAt(created)
                    .tags(tags)
                    .build();

            assertThat(exp.id()).isEqualTo("implement-oauth");
            assertThat(exp.name()).isEqualTo("Implement OAuth Feature");
            assertThat(exp.createdAt()).isEqualTo(created);
            assertThat(exp.description()).isEqualTo("Add OAuth2 authentication support");
            assertThat(exp.tags().get("feature")).isEqualTo("auth");
            assertThat(exp.tags().get("priority")).isEqualTo("high");
        }

        @Test
        @DisplayName("equality is based on ID only")
        void equalityBasedOnId() {
            Experiment exp1 = Experiment.create("same-id")
                    .name("First Name")
                    .build();
            Experiment exp2 = Experiment.create("same-id")
                    .name("Different Name")
                    .build();
            Experiment exp3 = Experiment.create("different-id").build();

            assertThat(exp1).isEqualTo(exp2);
            assertThat(exp1.hashCode()).isEqualTo(exp2.hashCode());
            assertThat(exp1).isNotEqualTo(exp3);
        }

        @Test
        @DisplayName("toString includes id and name")
        void toStringIncludesIdAndName() {
            Experiment exp = Experiment.create("my-exp")
                    .name("My Experiment")
                    .build();

            assertThat(exp.toString())
                    .contains("my-exp")
                    .contains("My Experiment");
        }

        @Test
        @DisplayName("requires non-null id")
        void requiresId() {
            assertThatThrownBy(() -> Experiment.create(null).build())
                    .isInstanceOf(NullPointerException.class);
        }
    }

    @Nested
    @DisplayName("Summary")
    class SummaryTests {

        @Test
        @DisplayName("creates empty summary")
        void createsEmpty() {
            Summary summary = Summary.empty();

            assertThat(summary.isEmpty()).isTrue();
            assertThat(summary.size()).isZero();
        }

        @Test
        @DisplayName("creates summary with factory methods")
        void createsWithFactoryMethods() {
            Summary s1 = Summary.of("success", true);
            Summary s2 = Summary.of("success", true, "filesChanged", 5);
            Summary s3 = Summary.of("success", true, "filesChanged", 5, "testsPass", true);

            assertThat(s1.size()).isEqualTo(1);
            assertThat(s2.size()).isEqualTo(2);
            assertThat(s3.size()).isEqualTo(3);
        }

        @Test
        @DisplayName("builder creates summary")
        void builderCreatesSummary() {
            Summary summary = Summary.builder()
                    .set("success", true)
                    .set("filesChanged", 5)
                    .set("totalCostUsd", 0.15)
                    .build();

            assertThat(summary.get("success", Boolean.class)).isTrue();
            assertThat(summary.get("filesChanged", Integer.class)).isEqualTo(5);
            assertThat(summary.get("totalCostUsd", Double.class)).isEqualTo(0.15);
        }

        @Test
        @DisplayName("with() creates new summary with additional value")
        void withCreatesNewSummary() {
            Summary original = Summary.of("success", true);
            Summary updated = original.with("filesChanged", 3);

            // Original unchanged
            assertThat(original.size()).isEqualTo(1);
            assertThat(original.values()).doesNotContainKey("filesChanged");

            // Updated has both
            assertThat(updated.size()).isEqualTo(2);
            assertThat(updated.get("success", Boolean.class)).isTrue();
            assertThat(updated.get("filesChanged", Integer.class)).isEqualTo(3);
        }

        @Test
        @DisplayName("with() overwrites existing key")
        void withOverwritesExistingKey() {
            Summary original = Summary.of("filesChanged", 3);
            Summary updated = original.with("filesChanged", 5);

            assertThat(original.get("filesChanged", Integer.class)).isEqualTo(3);
            assertThat(updated.get("filesChanged", Integer.class)).isEqualTo(5);
        }

        @Test
        @DisplayName("merge() combines maps")
        void mergeCombinesMaps() {
            Summary original = Summary.of("success", true);
            Summary merged = original.merge(Map.of(
                    "filesChanged", 5,
                    "testsPass", true
            ));

            assertThat(merged.size()).isEqualTo(3);
            assertThat(merged.get("success", Boolean.class)).isTrue();
            assertThat(merged.get("filesChanged", Integer.class)).isEqualTo(5);
        }

        @Test
        @DisplayName("getOrDefault returns default for missing keys")
        void getOrDefaultReturnsFallback() {
            Summary summary = Summary.of("success", true);

            assertThat(summary.getOrDefault("success", false)).isTrue();
            assertThat(summary.getOrDefault("missing", "default")).isEqualTo("default");
        }

        @Test
        @DisplayName("isSuccess checks success field")
        void isSuccessChecksField() {
            Summary success = Summary.of("success", true);
            Summary failure = Summary.of("success", false);
            Summary noField = Summary.empty();

            assertThat(success.isSuccess()).isTrue();
            assertThat(failure.isSuccess()).isFalse();
            assertThat(noField.isSuccess()).isFalse();
        }

        @Test
        @DisplayName("get throws ClassCastException for wrong type")
        void getThrowsOnWrongType() {
            Summary summary = Summary.of("count", 5);

            assertThatThrownBy(() -> summary.get("count", String.class))
                    .isInstanceOf(ClassCastException.class)
                    .hasMessageContaining("count");
        }

        @Test
        @DisplayName("summary is immutable")
        void summaryIsImmutable() {
            Summary summary = Summary.of("key", "value");

            assertThatThrownBy(() -> summary.values().put("newKey", "newValue"))
                    .isInstanceOf(UnsupportedOperationException.class);
        }
    }
}
