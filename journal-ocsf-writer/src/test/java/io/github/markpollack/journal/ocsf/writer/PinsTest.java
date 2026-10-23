package io.github.markpollack.journal.ocsf.writer;

import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The writer's pins agree with the repository's lock file and the reader's resources: toolchain commits, the compiled
 * schema hash behind the bundled excerpt, the semantic contract bytes the reader implements, the bundled extension
 * source list, and the class table.
 */
class PinsTest {

    private static final Path PROJECT = Path.of("..").toAbsolutePath().normalize();

    @Test
    void toolchainPinsEqualTheLockFile() throws Exception {
        Jv lock = Jv.parse(Files.readString(PROJECT.resolve("prototype/ocsf-acp/toolchain.lock.json"), StandardCharsets.UTF_8));
        assertThat(lock.path("ocsf_schema", "commit").text()).isEqualTo(Pins.OCSF_SCHEMA_COMMIT);
        assertThat(lock.path("ocsf_schema_compiler", "commit").text()).isEqualTo(Pins.OCSF_COMPILER_COMMIT);
        assertThat(lock.path("ocsf_toolkit", "commit").text()).isEqualTo(Pins.TOOLKIT_COMMIT);
        assertThat(lock.path("ocsf_toolkit", "sha256").text()).isEqualTo(Pins.TOOLKIT_SHA256);
        SchemaPin pin = SchemaPin.bundled();
        assertThat(pin.extensionVersion()).isEqualTo(lock.path("extension", "version").text());
        assertThat(pin.compiledSha256()).isEqualTo(lock.path("extension", "compiled_sha256_uid_901").text());
        assertThat(pin.extensionUid()).isEqualTo(901);
        assertThat(pin.ocsfVersion()).isEqualTo("1.9.0");
        assertThat(pin.classUids()).containsKeys("model_response", "prompt_turn", "session_context", "source_record", "tool_call");
        assertThat(pin.classNamed("session_context").classUid()).isEqualTo(90111005);
        assertThat(pin.classNamed("prompt_turn").activityName(3)).isEqualTo("Turn started");
    }

    @Test
    void semanticContractBytesAreIdenticalInWriterAndReader() throws Exception {
        byte[] writer = resource(Pins.SEMANTIC_CONTRACT_RESOURCE);
        byte[] reader = Files.readAllBytes(PROJECT.resolve("journal-capture-tck/src/main/resources/ocsf/semantic-contract-0.5.0-prototype.json"));
        assertThat(writer).isEqualTo(reader);
        assertThat(io.github.markpollack.journal.tck.ocsf.SemanticOperation.VERSION).isEqualTo(Pins.SEMANTIC_OPERATIONS_VERSION);
        assertThat(io.github.markpollack.journal.tck.ocsf.PackageReader.READER_CONTRACT_VERSION).isEqualTo(Pins.READER_CONTRACT_VERSION);
        assertThat(io.github.markpollack.journal.tck.ocsf.PackageReader.SUPPORTED_PACKAGE_VERSIONS).contains(Pins.PACKAGE_VERSION);
    }

    @Test
    void bundledExtensionSourceListsEveryFileOfTheExtensionDirectory() throws Exception {
        Path ext = PROJECT.resolve("prototype/ocsf-acp/extension/agentic_cli");
        List<String> onDisk;
        try (Stream<Path> s = Files.walk(ext)) {
            onDisk = s.filter(Files::isRegularFile).map(p -> ext.relativize(p).toString().replace('\\', '/')).sorted().toList();
        }
        assertThat(new ArrayList<>(SchemaPin.EXTENSION_SOURCE_FILES).stream().sorted().toList()).isEqualTo(onDisk);
        Map<String, Jv> files = new TreeMap<>();
        String sha = SchemaPin.extensionSource(files);
        assertThat(files).hasSize(onDisk.size());
        assertThat(files.get("extension.json").member("version").text()).isEqualTo(SchemaPin.bundled().extensionVersion());
        assertThat(sha).hasSize(64);
    }

    @Test
    void mappingContractsDeclareEveryRuleTheMappersExecute() throws Exception {
        MappingContracts c = new MappingContracts();
        for (String name : List.of(ClaudeStreamMapper.CONTRACT, ClaudeTranscriptMapper.CONTRACT, CodexRolloutMapper.CONTRACT, CodexStreamMapper.CONTRACT, OpaqueTextMapper.CONTRACT)) {
            // the Codex exec-stream contract is 0.5.0 (component measurement status, Tier 1 wave), the Claude and rollout contracts 0.3.0 (SEM-CLOSE tranches); opaque text is unchanged
            assertThat(c.uid(name)).endsWith(CodexStreamMapper.CONTRACT.equals(name) ? "/0.6.0-prototype" : CodexRolloutMapper.CONTRACT.equals(name) ? "/0.4.0-prototype"
                    : OpaqueTextMapper.CONTRACT.equals(name) ? "/0.2.0-prototype" : "/0.3.0-prototype");
            c.rule(name, "common.opaque->source_record");
            c.rule(name, "common.mapper_exception->source_record");
        }
        // every rule id string literal used by a mapper class is declared in its contract
        for (Map.Entry<String, String> m : Map.of("ClaudeStreamMapper.java", ClaudeStreamMapper.CONTRACT, "ClaudeTranscriptMapper.java", ClaudeTranscriptMapper.CONTRACT,
                "CodexRolloutMapper.java", CodexRolloutMapper.CONTRACT, "CodexStreamMapper.java", CodexStreamMapper.CONTRACT, "CodexItems.java", CodexStreamMapper.CONTRACT).entrySet()) {
            String src = Files.readString(Path.of("src/main/java/io/github/markpollack/journal/ocsf/writer/" + m.getKey()), StandardCharsets.UTF_8);
            java.util.regex.Matcher mt = java.util.regex.Pattern.compile("\"([a-z]+\\.[a-z_.A-Z]+->[a-z_.]+)\"").matcher(src);
            int found = 0;
            while (mt.find()) {
                c.rule(m.getValue(), mt.group(1));
                found++;
            }
            assertThat(found).as(m.getKey() + " rule ids").isPositive();
        }
    }

    private static byte[] resource(String name) throws Exception {
        try (InputStream in = PinsTest.class.getResourceAsStream(name)) {
            assertThat(in).as(name).isNotNull();
            return in.readAllBytes();
        }
    }
}
