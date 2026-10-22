package io.github.markpollack.journal.tck.ocsf;

import io.github.markpollack.journal.tck.verify.Json;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads one kind of native artifact and states the facts it reports. Each implementation is the oracle's own
 * understanding of the vendor's records, grounded in the native fields alone; it shares nothing with the adapter
 * that produced the candidate and never calls a production parser.
 *
 * <p>The profile name is the one the package's source inventory declares ({@code claude_stream},
 * {@code claude_transcript}, {@code codex_rollout}, {@code codex_stream}, {@code agy_stream}, {@code opaque_text}).
 */
public interface SourceProfile {

    /**
     * @param artifact the native artifact
     * @param source the package's declaration of this artifact (admission inputs such as {@code admit_response_uid})
     * @param scope the core's owner-scoped source selector, or {@code null} when the core owns every record
     */
    ExpectedFacts expected(NativeArtifact artifact, Json source, Json scope);

    static SourceProfile forName(String profile) {
        return switch (profile == null ? "" : profile) {
            case "claude_stream" -> new ClaudeStreamProfile();
            case "claude_transcript" -> new ClaudeTranscriptProfile();
            case "codex_rollout" -> new CodexRolloutProfile();
            case "codex_stream" -> new CodexStreamProfile();
            case "agy_stream" -> new AntigravityStreamProfile();
            case "opaque_text" -> new OpaqueTextProfile();
            default -> null;
        };
    }

    // ------------------------------------------------------------------------------------------ shared helpers

    static String text(Json json, String... path) {
        Json v = json == null ? null : json.path(path);
        return v == null || v.isNull() ? null : v.text();
    }

    static boolean isIntegral(Json v) {
        return v instanceof Json.JNumber n && n.value().stripTrailingZeros().scale() <= 0;
    }

    /** Copies integral native components into normalized names; absent or non-integral components stay absent. */
    static Map<String, Json> components(Json usage, String... pairs) {
        Map<String, Json> out = new LinkedHashMap<>();
        if (usage == null) {
            return out;
        }
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            Json v = usage.member(pairs[i]);
            if (isIntegral(v)) {
                out.put(pairs[i + 1], v);
            }
        }
        return out;
    }

    /**
     * An Anthropic assistant message (Claude stream and transcript): its response with the exact native blocks, and its
     * own usage, the total of that one API message (provisional until a stop reason is reported). Returns the blocks.
     */
    static List<Json> anthropicMessage(ExpectedFacts f, int index, Json message) {
        String stop = text(message, "stop_reason");
        List<Json> blocks = message.member("content") == null ? null : message.member("content").elements();
        f.responses.add(new ExpectedFacts.Response(index, text(message, "id"), blocks, stop, stop != null, text(message, "model")));
        Json usage = message.member("usage");
        if (usage != null) {
            f.usages.add(new ExpectedFacts.Usage(index, index, 2, stop == null ? 1 : 2, TrancheReading.anthropic(usage), text(message, "model"), null, null, 1));
        }
        return blocks == null ? List.of() : blocks;
    }

    /** Whether a record belongs to this core under an owner-scoped selector such as {@code parent_tool_use_id}. */
    static boolean owned(Json record, Json scope) {
        if (scope == null) {
            return true;
        }
        String path = text(scope, "owner_path");
        Json want = scope.member("owner_value");
        Json have = record == null || path == null ? null : record.member(path);
        boolean haveNull = have == null || have.isNull();
        boolean wantNull = want == null || want.isNull();
        return haveNull ? wantNull : !wantNull && have.text() != null && have.text().equals(want.text());
    }

    static int decisionId(String reported) {
        if (reported == null) {
            return 99;
        }
        return switch (reported) {
            case "accept", "allow" -> 1;
            case "deny", "denied", "reject", "rejected" -> 2;
            default -> 99;
        };
    }

    static List<String> ids(List<String> ids) {
        return new ArrayList<>(ids);
    }

    static BigDecimal decimal(Json v) {
        return v instanceof Json.JNumber n ? n.value() : null;
    }
}
