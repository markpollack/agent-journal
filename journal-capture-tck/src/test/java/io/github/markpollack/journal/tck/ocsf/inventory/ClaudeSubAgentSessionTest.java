package io.github.markpollack.journal.tck.ocsf.inventory;

import io.github.markpollack.journal.tck.verify.Json;
import io.github.markpollack.journal.tck.verify.NeutralJson;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.function.UnaryOperator;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

/** The native binding of a Claude sub-agent's own session file, on the sealed {@code claude-sub-agent-session} case. */
class ClaudeSubAgentSessionTest {
    private static final Path CASE = Path.of("src/test/resources/ocsf/terminal-child/claude-sub-agent-session/store/prepared");
    private static final String SESSION = "native/native-1/subagents/agent-a1.jsonl";
    private static final String META = "native/native-1/subagents/agent-a1.meta.json";
    @TempDir Path temp;

    private static Path sources() throws IOException {
        try (Stream<Path> files = Files.walk(CASE)) {
            return files.filter(p -> p.endsWith("sources/acquisition.json") && !p.toString().contains("/children/")).findFirst().orElseThrow().getParent();
        }
    }

    /** A copy of the sealed sources with {@code file} rewritten by {@code change}; the bindings the copy establishes. */
    private Map<String, Json> bindingsWith(String file, UnaryOperator<String> change) throws IOException {
        Path copy = Files.createTempDirectory(temp, "copy").resolve("sources");
        try (Stream<Path> files = Files.walk(sources())) {
            for (Path p : files.toList()) {
                Path t = copy.resolve(sources().relativize(p).toString());
                if (Files.isDirectory(p)) Files.createDirectories(t); else Files.copy(p, t);
            }
        }
        if (file != null) Files.writeString(copy.resolve(file), change.apply(Files.readString(copy.resolve(file))));
        return ClaudeSubAgentSession.bindings(NeutralJson.readDocument(copy.resolve("acquisition.json")), copy);
    }

    @Test void theMetaFileTheStreamAndTheSideChainBindTheSessionFileToItsSpawningCall() throws IOException {
        Map<String, Json> bindings = bindingsWith(null, s -> s);
        assertThat(bindings).containsOnlyKeys(SESSION, META);
        assertThat(RawInventory.text(bindings.get(SESSION), "spawn_tool_call_uid")).isEqualTo("toolu_AGENT");
        assertThat(RawInventory.text(bindings.get(SESSION), "agent_id")).isEqualTo("a1");
        assertThat(RawInventory.text(bindings.get(SESSION), "meta_artifact")).isEqualTo(META);
    }

    @Test void aMetaFileNamingACallTheStreamNeverRequestsBindsNothing() throws IOException {
        assertThat(bindingsWith(META, s -> s.replace("toolu_AGENT", "toolu_OTHER"))).isEmpty();
    }

    @Test void aMetaFileWithoutASpawningCallBindsNothing() throws IOException {
        assertThat(bindingsWith(META, s -> s.replace("\"toolUseId\"", "\"toolUse\""))).isEmpty();
        assertThat(bindingsWith(META, s -> "not json")).isEmpty();
    }

    @Test void aSessionFileOfAnotherAgentOrSessionBindsNothing() throws IOException {
        assertThat(bindingsWith(SESSION, s -> s.replaceFirst("\"agentId\":\"a1\"", "\"agentId\":\"a2\""))).as("another agent's record").isEmpty();
        assertThat(bindingsWith(SESSION, s -> s.replaceFirst("\"sessionId\":\"native-1\"", "\"sessionId\":\"native-2\""))).as("another session's record").isEmpty();
        assertThat(bindingsWith(SESSION, s -> s.replaceFirst("\"isSidechain\":true", "\"isSidechain\":false"))).as("the main thread's record").isEmpty();
        assertThat(bindingsWith(SESSION, s -> "not json\n" + s)).as("an unreadable record").isEmpty();
    }

    @Test void aStreamWhoseSubAgentStatesAnotherAgentIdBindsNothing() throws IOException {
        assertThat(bindingsWith("stdout.bin", s -> s.replace("\"agent_id\":\"a1\"", "\"agent_id\":\"a9\""))).isEmpty();
        assertThat(bindingsWith("stdout.bin", s -> s.replace("\"agent_id\":\"a1\",", ""))).as("no agent id stated").isEmpty();
    }
}
