package io.github.markpollack.journal.architecture.control;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;

/**
 * NEGATIVE CONTROL, never executed: an expected-value reading that binds a native line to a typed record with Jackson data
 * binding, the deserializer pattern that silently drops unknown members and collapses duplicates. {@code ArchitectureBoundaryTest}
 * shows that the oracle-core rule rejects it. It lives in test sources and is excluded from the analyzed scope.
 */
public final class ExpectedValueFromDataBinding {

    /** The typed shape a deserializer would impose on a Codex {@code mcp_tool_call} item. */
    record McpToolCallItem(String id, String server, String tool) {
    }

    private ExpectedValueFromDataBinding() {
    }

    static String toolName(String nativeItem) throws IOException {
        return new ObjectMapper().readValue(nativeItem, McpToolCallItem.class).tool();
    }
}
