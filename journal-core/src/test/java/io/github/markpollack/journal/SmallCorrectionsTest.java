package io.github.markpollack.journal;

import io.github.markpollack.journal.event.FeedbackEvent;
import io.github.markpollack.journal.event.GitPullRequestEvent;
import io.github.markpollack.journal.metric.InMemoryMetricRegistry;
import io.github.markpollack.journal.metric.Tags;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("Step 12.3: small corrections (AJ-13, AJ-35, AJ-46, AJ-53)")
class SmallCorrectionsTest {

    @Test
    @DisplayName("AJ-13: a labelled item carries no score, so it is not a pass")
    void labeledHasNoScore() {
        FeedbackEvent labeled = FeedbackEvent.labeled("item-1", "alice", Map.of("quality", "good"));

        assertThat(labeled.score()).isNull();
        assertThat(labeled.toMap()).doesNotContainKey("score");
        assertThat(labeled.labels()).containsEntry("quality", "good");
    }

    @Test
    @DisplayName("AJ-35: the pull request event's type is the type that file storage writes")
    void pullRequestTypeIsGitPr() {
        GitPullRequestEvent pr = GitPullRequestEvent.created("123", "https://example.invalid/pr/123", "t", "head",
                "base");

        assertThat(pr.type()).isEqualTo("git_pr");
    }

    @Test
    @DisplayName("AJ-46: the same tags in a different order are one metric")
    void metricKeyIgnoresTagOrder() {
        InMemoryMetricRegistry registry = new InMemoryMetricRegistry();

        var first = registry.counter("calls", Tags.of("a", "1", "b", "2"));
        var second = registry.counter("calls", Tags.of("b", "2", "a", "1"));

        assertThat(second).isSameAs(first);
    }

    @Test
    @DisplayName("AJ-53: a stored integer reads back as a Long, and a lossy conversion still fails")
    void typedGetConvertsStoredNumbers() {
        Summary summary = new Summary(Map.of("tokens", 42, "ratio", 0.5, "name", "x", "big", 1L << 40));

        assertThat(summary.get("tokens", Long.class)).isEqualTo(42L);
        assertThat(summary.get("tokens", Double.class)).isEqualTo(42.0);
        assertThat(summary.get("big", Long.class)).isEqualTo(1L << 40);
        assertThatThrownBy(() -> summary.get("big", Integer.class)).isInstanceOf(ClassCastException.class);
        assertThatThrownBy(() -> summary.get("ratio", Long.class)).isInstanceOf(ClassCastException.class);
        assertThatThrownBy(() -> summary.get("name", Long.class)).isInstanceOf(ClassCastException.class);
    }
}
