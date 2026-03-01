package io.github.markpollack.journal.claude;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class PhaseCaptureTest {

    @Test
    void totalTokensSumsAllTokenTypes() {
        PhaseCapture capture = new PhaseCapture("explore", null,
                100, 50, 25, 1000, 800, 0.01, "sess-1", 2, false,
                "output", List.of(), List.of(), null);

        assertThat(capture.totalTokens()).isEqualTo(175);
    }

    @Test
    void hasThinkingReturnsTrueWhenThinkingBlocksPresent() {
        PhaseCapture capture = new PhaseCapture("explore", null,
                100, 50, 25, 1000, 800, 0.01, "sess-1", 2, false,
                "output", List.of("thinking..."), List.of(), null);

        assertThat(capture.hasThinking()).isTrue();
    }

    @Test
    void hasThinkingReturnsFalseWhenEmpty() {
        PhaseCapture capture = new PhaseCapture("explore", null,
                100, 50, 0, 1000, 800, 0.01, "sess-1", 2, false,
                "output", List.of(), List.of(), null);

        assertThat(capture.hasThinking()).isFalse();
    }

    @Test
    void hasThinkingReturnsFalseWhenNull() {
        PhaseCapture capture = new PhaseCapture("explore", null,
                100, 50, 0, 1000, 800, 0.01, "sess-1", 2, false,
                "output", null, List.of(), null);

        assertThat(capture.hasThinking()).isFalse();
    }

    @Test
    void hasToolUsesReturnsTrueWhenPresent() {
        PhaseCapture capture = new PhaseCapture("act", "do something",
                100, 50, 0, 1000, 800, 0.01, "sess-1", 2, false,
                "output", List.of(),
                List.of(new ToolUseRecord("t-1", "Read", Map.of("file_path", "/pom.xml"))),
                null);

        assertThat(capture.hasToolUses()).isTrue();
    }

    @Test
    void hasToolUsesReturnsFalseWhenEmpty() {
        PhaseCapture capture = new PhaseCapture("act", null,
                100, 50, 0, 1000, 800, 0.01, "sess-1", 2, false,
                "output", List.of(), List.of(), null);

        assertThat(capture.hasToolUses()).isFalse();
    }

    @Test
    void hasToolUsesReturnsFalseWhenNull() {
        PhaseCapture capture = new PhaseCapture("act", null,
                100, 50, 0, 1000, 800, 0.01, "sess-1", 2, false,
                "output", List.of(), null, null);

        assertThat(capture.hasToolUses()).isFalse();
    }

    @Test
    void capturesPromptTextAndRawResult() {
        PhaseCapture capture = new PhaseCapture("plan", "Generate a plan",
                100, 50, 0, 1000, 800, 0.01, "sess-1", 2, false,
                "output", List.of(), List.of(), "Plan complete.");

        assertThat(capture.promptText()).isEqualTo("Generate a plan");
        assertThat(capture.rawResult()).isEqualTo("Plan complete.");
    }
}
