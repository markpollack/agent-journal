package io.github.markpollack.journal.architecture.control;

import io.github.markpollack.journal.ocsf.writer.Jv;

/**
 * NEGATIVE CONTROL, never executed: an expected-value reading that takes a Codex MCP tool name from a native line with the
 * writer's own JSON parser. Otherwise a valid reading; {@code ArchitectureBoundaryTest} shows that the checker rule rejects
 * it, so the rule is not satisfied vacuously. It lives in test sources and is excluded from the analyzed scope.
 */
public final class ExpectedValueFromTheWritersParser {

    private ExpectedValueFromTheWritersParser() {
    }

    static String toolName(String nativeLine) {
        return Jv.parse(nativeLine).path("item", "tool").text();
    }
}
