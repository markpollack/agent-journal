package io.github.markpollack.journal.tck.ocsf;

import io.github.markpollack.journal.tck.ocsf.inventory.SourceAvailability;
import io.github.markpollack.journal.tck.ocsf.inventory.SourceSpecification;
import io.github.markpollack.journal.tck.verify.Json;
import io.github.markpollack.journal.tck.verify.NeutralJson;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The onboarding gate on source availability ({@link SourceAvailability}), run on the committed specifications and the
 * native specimens they were observed on. The new routes hold; the audit of the earlier routes finds exactly the
 * recorded gaps; and each corrected rule rejects its counterexample: a wholly-absent usage claim for a source that
 * reports usage (the copilot-2 checkpoint review error, and Junie's usage under errorCode), a zero-match selector marked
 * exercised, nested vendor usage hidden under a data-keyed subtree, and an observed usage path missing from the
 * specification.
 */
class SourceAvailabilityTest {

    static final Path VENDOR = VendorSpecificationsTest.VENDOR;
    static final Path FIXTURES = VendorSpecificationsTest.FIXTURES;

    /** Each specification with an availability section, and the committed specimens of its route. */
    static final Map<String, List<String>> SPECIMENS = new LinkedHashMap<>();

    static {
        SPECIMENS.put("github-copilot-cli/stdout-json/1.0.100", List.of("copilot-live-1.0.100-otel/native/stdout.jsonl"));
        SPECIMENS.put("github-copilot-cli/session-events/1.0.100", List.of("copilot-live-1.0.100-otel/native/session-events.jsonl"));
        SPECIMENS.put("github-copilot-cli/otel-file/1.0.100", List.of("copilot-live-1.0.100-otel/native/otel.jsonl"));
        SPECIMENS.put("github-copilot-cli/usage-report/1.0.100", List.of("copilot-live-1.0.100-otel/native/usage-output.json"));
        SPECIMENS.put("junie/session-summary/3813.7", List.of("junie-live-3813.7-view/native/summary.json", "junie-live-3813.7-terminal/native/summary.json"));
        SPECIMENS.put("junie/session-events/3813.7", List.of("junie-live-3813.7-view/native/session-events.jsonl", "junie-live-3813.7-terminal/native/session-events.jsonl"));
        SPECIMENS.put("junie/json-stream/3813.7", List.of("junie-live-3813.7-view/native/stdout.jsonl", "junie-live-3813.7-terminal/native/stdout.jsonl"));
        SPECIMENS.put("claude-code/session-transcript/2.1.304", List.of("claude-live-2.1.304-mcp-pair/native/transcript.jsonl"));
        // the Claude stream routes, each against its own specification (D1): sdk 2.1.299, stdout 2.1.304
        SPECIMENS.put("claude-code/stream-json/2.1.303", List.of("claude-child-2.1.299/native/stream.jsonl", "claude-child-2.1.299-interrupted/native/stream.jsonl"));
        SPECIMENS.put("claude-code/stream-json/2.1.304", List.of("claude-live-2.1.304-features/native/stream.jsonl", "claude-live-2.1.304-denial/native/stream.jsonl",
                "claude-live-2.1.304-cancelled/native/stream.jsonl", "claude-live-2.1.304-mcp/native/stream.jsonl", "claude-live-2.1.304-mcp-pair/native/stream.jsonl"));
    }

    @Test
    void theNewRoutesHoldOnTheirSpecimens() {
        SPECIMENS.forEach((spec, natives) -> assertThat(SourceAvailability.check(read(spec), natives(natives))).as(spec).isEmpty());
        // the copilot-2 regression: the checkpoint's nested usage is inventoried on standard output, with the last call's full usage
        Json stdout = read("github-copilot-cli/stdout-json/1.0.100");
        Json checkpoint = stdout.path("inventory", "variants", "session.usage_checkpoint", "paths");
        for (String path : List.of("data.accountingSnapshot.tokenDetails.input.tokenCount", "data.accountingSnapshot.modelMetrics.{*}.usage.inputTokens",
                "data.accountingSnapshot.agentMetrics.{*}.modelMetrics.{*}.usage.outputTokens", "data.accountingSnapshot.lastCallInputTokens",
                "data.accountingSnapshot.lastCallOutputTokens", "data.promptCacheBreakState[].models.{*}.cache_read", "data.promptCacheBreakState[].models.{*}.system_segments")) {
            assertThat(checkpoint.member(path)).as(path).isNotNull();
        }
        assertThat(family(stdout, "token_usage").member("claim").text()).isEqualTo("present");
        // supplied system segments are hashes and counts, kept apart from system text: standard output carries no system text
        assertThat(family(stdout, "system_text").member("claim").text()).isEqualTo("absent");
        // Junie's usage under errorCode is usage
        Json junie = read("junie/json-stream/3813.7");
        assertThat(junie.path("inventory", "variants", "result", "paths", "errorCode[].inputTokens")).isNotNull();
        assertThat(family(junie, "token_usage").member("claim").text()).isEqualTo("present");
    }

    @Test
    void theAuditOfTheEarlierRoutesFindsExactlyTheRecordedGaps() {
        assertThat(SourceAvailability.check(read("codex-cli/exec-json-stream/0.160.1"), natives(fixtures("codex-live-", "syn-codex-exec"), "stream.jsonl"))).isEmpty();
        assertThat(SourceAvailability.check(read("antigravity-cli/stream-json/1.4.6"), natives(fixtures("antigravity-live-"), "stream.jsonl"))).isEmpty();
        // findings A-1 and A-3 (repaired by D1): the Agent result's child-run usage is normalized by its obligation
        // (claude.stream.usage.child-run.agent-result), so the specifications list tool_use_result as a reviewed data-keyed
        // exception, and each route names its own specification (sdk 2.1.303 0.2.0 with the 2.1.299 recordings, stdout
        // 2.1.304 0.1.0), whose inventory has the task usage paths; both hold on their recordings (theNewRoutesHoldOnTheirSpecimens).
        // Without the reviewed exception the detector still finds what it found at 24e8ecc: the negative control.
        Json noException = set(read("claude-code/stream-json/2.1.304"), "availability",
                set(read("claude-code/stream-json/2.1.304").member("availability"), "data_keyed_exceptions", new Json.JArray(List.of())));
        List<String> claude = SourceAvailability.check(noException, natives(SPECIMENS.get("claude-code/stream-json/2.1.304")));
        assertThat(claude).isNotEmpty().allSatisfy(p -> assertThat(p).startsWith("data-keyed subtree under tool_use_result holds the token-usage key").contains("(stream.jsonl:33)"));
        assertThat(String.join("\n", claude)).contains("totalTokens").contains("input_tokens");
        // finding A-2 (repaired): the 2.1.304 session file entry named the 2.1.303 specification, whose inventory lacks a usage path the
        // labelled synthetic pair transcript carries; the 2.1.304 entry now names its own specification (held above)
        assertThat(SourceAvailability.check(read("claude-code/session-transcript/2.1.303"), natives(List.of("syn-claude-pair-transcript"), "transcript.jsonl")))
                .singleElement().asString().contains("cost-state modelUsage.{*}.maxOutputTokens");
    }

    @Test
    void aWhollyAbsentUsageClaimIsRejected() {
        Json stdout = withFamily(read("github-copilot-cli/stdout-json/1.0.100"), "token_usage", f -> set(f, "claim", new Json.JString("absent")));
        assertThat(SourceAvailability.check(stdout, natives(SPECIMENS.get("github-copilot-cli/stdout-json/1.0.100"))))
                .anySatisfy(p -> assertThat(p).startsWith("token_usage is claimed absent but the inventory observes"));
        Json junie = withFamily(read("junie/json-stream/3813.7"), "token_usage", f -> set(f, "claim", new Json.JString("absent")));
        assertThat(SourceAvailability.check(junie, natives(SPECIMENS.get("junie/json-stream/3813.7"))))
                .anySatisfy(p -> assertThat(p).startsWith("token_usage is claimed absent but the inventory observes"));
    }

    @Test
    void aZeroMatchSelectorMarkedExercisedIsRejected() {
        Json stdout = withFamily(read("github-copilot-cli/stdout-json/1.0.100"), "token_usage", f -> {
            List<Json> selectors = new ArrayList<>(f.member("selectors").elements());
            Map<String, Json> s = new LinkedHashMap<>(((Json.JObject) selectors.get(0)).members());
            s.put("variant", new Json.JString("session.usage_record"));
            selectors.set(0, new Json.JObject(s));
            return set(f, "selectors", new Json.JArray(selectors));
        });
        assertThat(SourceAvailability.check(stdout, natives(SPECIMENS.get("github-copilot-cli/stdout-json/1.0.100"))))
                .anySatisfy(p -> assertThat(p).contains("matches nothing and is marked exercised"));
    }

    @Test
    void nestedUsageMayNotDisappear() {
        // declared as tool data: the checkpoint's accounting is hidden from the inventory
        Json hidden = read("github-copilot-cli/stdout-json/1.0.100");
        List<Json> dataKeyed = new ArrayList<>(hidden.path("inventory", "data_keyed").elements());
        dataKeyed.add(new Json.JString("data.accountingSnapshot"));
        hidden = set(hidden, "inventory", set(hidden.member("inventory"), "data_keyed", new Json.JArray(dataKeyed)));
        assertThat(SourceAvailability.check(hidden, natives(SPECIMENS.get("github-copilot-cli/stdout-json/1.0.100"))))
                .anySatisfy(p -> assertThat(p).startsWith("data-keyed subtree under data.accountingSnapshot holds the token-usage key"));
        // dropped from the specification: the inventory of the artifact still observes it
        Json dropped = read("github-copilot-cli/stdout-json/1.0.100");
        Json variant = dropped.path("inventory", "variants", "session.usage_checkpoint");
        Map<String, Json> paths = new LinkedHashMap<>(((Json.JObject) variant.member("paths")).members());
        paths.remove("data.accountingSnapshot.lastCallOutputTokens");
        Map<String, Json> variants = new LinkedHashMap<>(((Json.JObject) dropped.path("inventory", "variants")).members());
        variants.put("session.usage_checkpoint", set(variant, "paths", new Json.JObject(paths)));
        dropped = set(dropped, "inventory", set(dropped.member("inventory"), "variants", new Json.JObject(variants)));
        assertThat(SourceAvailability.check(dropped, natives(SPECIMENS.get("github-copilot-cli/stdout-json/1.0.100"))))
                .anySatisfy(p -> assertThat(p).contains("observes the usage path session.usage_checkpoint data.accountingSnapshot.lastCallOutputTokens"));
    }

    // ---- support -----------------------------------------------------------------------------------------------------

    static Json read(String spec) {
        return SourceSpecification.read(VENDOR.resolve(spec + ".json"));
    }

    static List<NativeArtifact> natives(List<String> relative) {
        return relative.stream().map(r -> r.endsWith(".json") ? NativeArtifact.document(FIXTURES.resolve(r)) : NativeArtifact.read(FIXTURES.resolve(r))).toList();
    }

    static List<NativeArtifact> natives(List<String> fixtures, String artifact) {
        return natives(fixtures.stream().map(f -> f + "/native/" + artifact).filter(r -> FIXTURES.resolve(r).toFile().isFile()).toList());
    }

    static List<String> fixtures(String... prefixes) {
        List<String> out = new ArrayList<>();
        for (java.io.File f : java.util.Objects.requireNonNull(FIXTURES.toFile().listFiles())) {
            for (String p : prefixes) {
                if (f.isDirectory() && f.getName().startsWith(p)) {
                    out.add(f.getName());
                }
            }
        }
        return out;
    }

    static Json family(Json spec, String name) {
        return spec.path("availability", "families").elements().stream().filter(f -> name.equals(f.member("family").text())).findFirst().orElseThrow();
    }

    static Json withFamily(Json spec, String name, UnaryOperator<Json> edit) {
        List<Json> families = new ArrayList<>();
        spec.path("availability", "families").elements().forEach(f -> families.add(name.equals(f.member("family").text()) ? edit.apply(f) : f));
        return set(spec, "availability", set(spec.member("availability"), "families", new Json.JArray(families)));
    }

    static Json set(Json object, String member, Json value) {
        Map<String, Json> m = new LinkedHashMap<>(((Json.JObject) object).members());
        m.put(member, value);
        return new Json.JObject(m);
    }

    static Json parse(String text) {
        return NeutralJson.parse(text);
    }
}
