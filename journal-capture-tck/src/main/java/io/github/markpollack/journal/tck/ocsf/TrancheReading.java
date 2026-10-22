package io.github.markpollack.journal.tck.ocsf;

import io.github.markpollack.journal.tck.verify.Json;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The oracle's own reading of a vendor's native usage object into the canonical tranches of extension
 * 0.6.0-prototype, from the pinned definitions and never from the writer:
 *
 * <ul>
 * <li>Anthropic usage (prompt-caching definitions, Anthropic SDK Usage): {@code input_tokens} are the tokens neither read
 * from nor used to create a cache, so they are the uncached tranche; {@code cache_creation} tiers are parts of
 * {@code cache_creation_input_tokens}; {@code output_tokens_details.thinking_tokens} is at most {@code output_tokens},
 * the inclusive billed total.</li>
 * <li>Claude Code ModelUsage (Agent SDK 0.3.304): the same tranches under camel-case names; {@code thinkingTokens} is
 * documented as already counted inside {@code outputTokens}.</li>
 * <li>Codex TokenUsage (codex-rs protocol at rust-v0.160.1): {@code input_tokens} includes {@code cached_input_tokens}
 * ({@code non_cached_input}); with reported cache writes of 0 the uncached tranche is their difference, otherwise
 * whether input includes the writes is not established and the tranche is unavailable; reasoning is part of output.</li>
 * </ul>
 */
final class TrancheReading {

    static final int REPORTED = 1;
    static final int DERIVED = 2;
    static final int UNAVAILABLE = 3;
    static final int PART_OF_OUTPUT = 1;

    private TrancheReading() {
    }

    /** Collects one observation's tranches. */
    private static final class Builder {
        final Map<String, Json> values = new LinkedHashMap<>();
        final Map<String, Integer> bases = new LinkedHashMap<>();
        final Map<String, List<String>> pointers = new LinkedHashMap<>();
        final List<ExpectedFacts.Tier> tiers = new ArrayList<>();
        Integer relation;

        boolean copy(Json usage, String prefix, String member, String canonical) {
            Json v = usage == null ? null : usage.member(member);
            if (!SourceProfile.isIntegral(v)) {
                return false;
            }
            values.put(canonical, v);
            bases.put(canonical, REPORTED);
            pointers.put(canonical, List.of(prefix + "/" + member));
            return true;
        }

        ExpectedFacts.Tranches build() {
            return new ExpectedFacts.Tranches(values, bases, pointers, tiers, relation);
        }
    }

    static ExpectedFacts.Tranches anthropic(Json usage) {
        Builder b = new Builder();
        // order follows the canonical tranches, not the native members
        String[][] table = {{"input_tokens", "uncached_input_tokens"}, {"cache_read_input_tokens", "cache_read_tokens"}, {"cache_creation_input_tokens", "cache_write_tokens"},
            {"output_tokens", "output_tokens"}};
        for (String[] row : table) {
            b.copy(usage, "", row[0], row[1]);
        }
        Json breakdown = usage == null ? null : usage.member("cache_creation");
        if (breakdown instanceof Json.JObject o) {
            for (Map.Entry<String, Json> t : o.members().entrySet()) {
                if (SourceProfile.isIntegral(t.getValue())) {
                    b.tiers.add(new ExpectedFacts.Tier(t.getKey(), t.getValue(), "/cache_creation/" + t.getKey()));
                }
            }
        }
        Json thinking = usage == null ? null : usage.path("output_tokens_details", "thinking_tokens");
        if (SourceProfile.isIntegral(thinking)) {
            b.values.put("reasoning_tokens", thinking);
            b.bases.put("reasoning_tokens", REPORTED);
            b.pointers.put("reasoning_tokens", List.of("/output_tokens_details/thinking_tokens"));
            b.relation = PART_OF_OUTPUT;
        }
        return b.build();
    }

    static ExpectedFacts.Tranches claudeModelUsage(Json usage) {
        Builder b = new Builder();
        String[][] table = {{"inputTokens", "uncached_input_tokens"}, {"cacheReadInputTokens", "cache_read_tokens"}, {"cacheCreationInputTokens", "cache_write_tokens"},
            {"outputTokens", "output_tokens"}};
        for (String[] row : table) {
            b.copy(usage, "", row[0], row[1]);
        }
        if (b.copy(usage, "", "thinkingTokens", "reasoning_tokens")) {
            b.relation = PART_OF_OUTPUT;
        }
        return b.build();
    }

    /** @param prefix the pointer of the usage object within the observation's data ({@code ""} when it is the data) */
    static ExpectedFacts.Tranches codex(Json usage, String prefix) {
        Builder b = new Builder();
        uncached(b, usage, prefix);
        b.copy(usage, prefix, "cached_input_tokens", "cache_read_tokens");
        b.copy(usage, prefix, "cache_write_input_tokens", "cache_write_tokens");
        b.copy(usage, prefix, "output_tokens", "output_tokens");
        if (b.copy(usage, prefix, "reasoning_output_tokens", "reasoning_tokens")) {
            b.relation = PART_OF_OUTPUT;
        }
        b.copy(usage, prefix, "total_tokens", "reported_total_tokens");
        return b.build();
    }

    /** Codex's uncached tranche: input minus cached when the reported cache writes are 0 and cached does not exceed input; otherwise unavailable. */
    private static void uncached(Builder b, Json usage, String prefix) {
        Json input = usage == null ? null : usage.member("input_tokens");
        Json cached = usage == null ? null : usage.member("cached_input_tokens");
        Json writes = usage == null ? null : usage.member("cache_write_input_tokens");
        if (input == null && cached == null) {
            return;
        }
        boolean all = SourceProfile.isIntegral(input) && SourceProfile.isIntegral(cached) && SourceProfile.isIntegral(writes);
        BigDecimal difference = all ? SourceProfile.decimal(input).subtract(SourceProfile.decimal(cached)) : null;
        boolean derivable = all && SourceProfile.decimal(writes).signum() == 0 && difference.signum() >= 0;
        if (derivable) {
            b.values.put("uncached_input_tokens", new Json.JNumber(difference.toBigIntegerExact().toString()));
        }
        b.bases.put("uncached_input_tokens", derivable ? DERIVED : UNAVAILABLE);
        b.pointers.put("uncached_input_tokens", List.of(prefix + "/input_tokens", prefix + "/cached_input_tokens", prefix + "/cache_write_input_tokens"));
    }
}
