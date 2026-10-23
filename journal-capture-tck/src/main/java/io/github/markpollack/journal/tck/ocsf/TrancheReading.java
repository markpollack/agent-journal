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
 * whether input includes the writes is not established and the tranche is unavailable; reasoning is part of output.
 * Measurement per component (codex-api sse/responses.rs and protocol TokenUsage): the provider usage deserializer
 * writes 0 for an omitted {@code cache_write_tokens} ({@code #[serde(default)]}), for cached and cache writes when
 * {@code input_tokens_details} is omitted ({@code unwrap_or_default}) and for reasoning when
 * {@code output_tokens_details} is omitted ({@code unwrap_or(0)}), while {@code input_tokens} and
 * {@code output_tokens} are required. So where any member is non-zero (an update arrived), a non-zero member or a zero
 * required member is measured and a zero cached, cache-write or reasoning member possibly default; where all are zero
 * ({@code Usage::default}) each is possibly default. Version 2 of that reading (exec stream): the printed usage is the
 * thread running total ({@code ThreadTokenUsage.total}), which adds a response's usage only when its completion carried one
 * ({@code ResponseEvent::Completed.token_usage} is an {@code Option}, {@code TokenUsageInfo::append_last_usage}), so
 * with an update any member other than a zero defaultable one is only a lower bound, a positive one included. The
 * uncached difference is never the canonical tranche on any Codex reading: a cache-write 0 may be the default and non-zero
 * writes leave their inclusion unknown, so with writes 0 and cached not above input the difference is only the
 * component's conditional value.</li>
 * </ul>
 * Anthropic and Claude Code usage carry no measurement statuses: no pinned source establishes whether Claude Code
 * relays an omitted member as 0.
 */
final class TrancheReading {

    static final int REPORTED = 1;
    static final int DERIVED = 2;
    static final int UNAVAILABLE = 3;
    static final int PART_OF_OUTPUT = 1;
    static final int MEASURED = 1;
    static final int POSSIBLY_DEFAULT = 2;
    static final int CONDITIONAL = 3;
    static final int LOWER_BOUND = 4;
    /** Codex members the pinned deserializer fills with 0 when the provider omits them. */
    static final List<String> FILLED_WHEN_OMITTED = List.of("cached_input_tokens", "cache_write_input_tokens", "reasoning_output_tokens");

    private TrancheReading() {
    }

    /** Collects one observation's tranches. */
    private static final class Builder {
        final Map<String, Json> values = new LinkedHashMap<>();
        final Map<String, Integer> bases = new LinkedHashMap<>();
        final Map<String, List<String>> pointers = new LinkedHashMap<>();
        final List<ExpectedFacts.Tier> tiers = new ArrayList<>();
        final Map<String, Integer> statuses = new LinkedHashMap<>();
        final Map<String, Json> conditionals = new LinkedHashMap<>();
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
            return new ExpectedFacts.Tranches(values, bases, pointers, tiers, relation, statuses, conditionals);
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
        return codex(usage, prefix, false);
    }

    /** The exec stream's reading: with {@code measure} each component also carries its measurement status (the rollout reading does not yet). */
    static ExpectedFacts.Tranches codex(Json usage, String prefix, boolean measure) {
        Builder b = new Builder();
        boolean anyNonZero = false;
        for (String m : List.of("input_tokens", "cached_input_tokens", "cache_write_input_tokens", "output_tokens", "reasoning_output_tokens")) {
            Json v = usage == null ? null : usage.member(m);
            anyNonZero |= SourceProfile.isIntegral(v) && SourceProfile.decimal(v).signum() != 0;
        }
        uncached(b, usage, prefix);
        String[][] table = {{"cached_input_tokens", "cache_read_tokens"}, {"cache_write_input_tokens", "cache_write_tokens"}, {"output_tokens", "output_tokens"},
            {"reasoning_output_tokens", "reasoning_tokens"}};
        for (String[] row : table) {
            if (b.copy(usage, prefix, row[0], row[1])) {
                if (measure) {
                    b.statuses.put(row[1], status(row[0], usage.member(row[0]), anyNonZero));
                }
                if ("reasoning_tokens".equals(row[1])) {
                    b.relation = PART_OF_OUTPUT;
                }
            }
        }
        b.copy(usage, prefix, "total_tokens", "reported_total_tokens");
        return b.build();
    }

    /** What the exec stream's running total establishes about one member: possibly default, or at least the stated value. */
    private static int status(String member, Json value, boolean anyNonZero) {
        if (!anyNonZero) {
            return POSSIBLY_DEFAULT;
        }
        return SourceProfile.decimal(value).signum() == 0 && FILLED_WHEN_OMITTED.contains(member) ? POSSIBLY_DEFAULT : LOWER_BOUND;
    }

    /**
     * Codex's uncached tranche, never established: with reported cache writes 0 and cached not above input the difference
     * is the conditional value of an unavailable component (Conditional); otherwise the component is unavailable without one.
     */
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
            b.conditionals.put("uncached_input_tokens", new Json.JNumber(difference.toBigIntegerExact().toString()));
            b.statuses.put("uncached_input_tokens", CONDITIONAL);
        }
        b.bases.put("uncached_input_tokens", UNAVAILABLE);
        b.pointers.put("uncached_input_tokens", List.of(prefix + "/input_tokens", prefix + "/cached_input_tokens", prefix + "/cache_write_input_tokens"));
    }

    /**
     * An Antigravity usage object (headless documentation: input, output, thinking, cache-read and total tokens): cache
     * reads, output, thinking as reasoning and the total are reported. input_tokens cannot include the cache reads (the
     * documentation's own example reports input 278 with cache_read 30214), but the stream reports no cache writes, so it is
     * the uncached input only conditionally: its value is the unavailable component's conditional value. Whether thinking is
     * part of output is not stated, so no relation is stated.
     */
    static ExpectedFacts.Tranches antigravity(Json usage) {
        Builder b = new Builder();
        Json input = usage == null ? null : usage.member("input_tokens");
        if (input != null) {
            b.bases.put("uncached_input_tokens", UNAVAILABLE);
            b.pointers.put("uncached_input_tokens", List.of("/input_tokens"));
            if (SourceProfile.isIntegral(input)) {
                b.statuses.put("uncached_input_tokens", CONDITIONAL);
                b.conditionals.put("uncached_input_tokens", input);
            }
        }
        b.copy(usage, "", "cache_read_tokens", "cache_read_tokens");
        b.copy(usage, "", "output_tokens", "output_tokens");
        b.copy(usage, "", "thinking_tokens", "reasoning_tokens");
        b.copy(usage, "", "total_tokens", "reported_total_tokens");
        return b.build();
    }
}
