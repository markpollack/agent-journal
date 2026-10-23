package io.github.markpollack.journal.ocsf.writer;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static io.github.markpollack.journal.ocsf.writer.MapperSupport.isIntegral;

/**
 * The canonical usage tranches of extension 0.6.0-prototype, from each vendor's native usage object: uncached input,
 * cache reads, cache writes with their tiers, and output with its reasoning part. Each component is copied from one
 * reported member, derived exactly by a declared rule whose arithmetic the pinned source establishes, or stated
 * unavailable; {@code components[]} says which, with RFC 6901 pointers into the observation's {@code data}, which keeps
 * the native object whole. Absent members stay absent, never zero.
 */
final class UsageTranches {

    static final Map<Integer, String> COMPONENT_BASIS = Map.of(0, "Unknown", 1, "Reported", 2, "Derived", 3, "Unavailable");
    static final Map<Integer, String> AGGREGATION = Map.of(0, "Unknown", 1, "Scope total", 2, "Cumulative snapshot");
    static final Map<Integer, String> REASONING_RELATION = Map.of(0, "Unknown", 1, "Part of output", 2, "Separate from output");
    static final Map<Integer, String> MEASUREMENT = Map.of(0, "Unknown", 1, "Measured", 2, "Possibly default", 3, "Conditional", 4, "Lower bound");

    /** Codex TokenUsage members the pinned emitter writes as 0 when the provider omits them. */
    static final Set<String> CODEX_DEFAULTED = Set.of("cached_input_tokens", "cache_write_input_tokens", "reasoning_output_tokens");
    /** The Codex TokenUsage members whose values decide whether an update arrived. */
    static final List<String> CODEX_MEMBERS = List.of("input_tokens", "cached_input_tokens", "cache_write_input_tokens", "output_tokens", "reasoning_output_tokens");
    static final String CODEX_MEASUREMENT = "codex.usage.component_measurement.v2: at rust-v0.160.1 the exec stream's usage is the thread running total "
            + "(ThreadTokenUsage.total), the element-wise sum of the usage of each response whose completion carried one (TokenUsageInfo::append_last_usage); "
            + "a completion without usage adds nothing (ResponseEvent::Completed.token_usage is an Option), and within a response an omitted cache_write_tokens, "
            + "input_tokens_details or output_tokens_details is written as cache-write, cached or reasoning 0 (#[serde(default)], unwrap_or_default, "
            + "unwrap_or(0)) while input_tokens and output_tokens are required. So no member establishes the thread's complete quantity, however large: with any "
            + "non-zero member a zero cached, cache-write or reasoning member is Possibly default and every other member is a Lower bound (the thread's actual quantity "
            + "is at least the member and may exceed it: a reported 100 is compatible with an actual 150); with every member zero "
            + "(Usage::default, no update arrived) each is Possibly default";
    static final String CODEX_UNCACHED = "codex.usage.uncached_input.v2: input_tokens - cached_input_tokens (input_tokens includes cached_input_tokens: "
            + "TokenUsage::non_cached_input) is the uncached input only if cache_write_input_tokens is a measured 0, which the pinned deserializer does not "
            + "establish because it also writes 0 when the provider omits cache_write_tokens, and only if input_tokens and cached_input_tokens are complete "
            + "measurements of the same responses; the result is this component's conditional value, never the canonical tranche";

    /** Anthropic: output_tokens is the inclusive billed total; output_tokens_details.thinking_tokens is always at most it. */
    static final String ANTHROPIC_REASONING = "anthropic.usage.thinking_in_output.v1: OutputTokensDetails.thinking_tokens is always <= output_tokens, which remains the inclusive billed total";
    /** Claude Code's ModelUsage.thinkingTokens is documented as already counted inside outputTokens. */
    static final String CLAUDE_MODEL_USAGE_REASONING = "claude.model_usage.thinking_in_output.v1: ModelUsage.thinkingTokens is already counted inside outputTokens";
    /** Codex: reasoning_output_tokens is the Responses API output_tokens_details.reasoning_tokens, a part of output_tokens. */
    static final String CODEX_REASONING = "codex.usage.reasoning_in_output.v1: reasoning_output_tokens is the Responses API output_tokens_details.reasoning_tokens; "
            + "TokenUsage::blended_total adds output_tokens alone and the vendor's usage test totals input plus output with reasoning inside output";

    private UsageTranches() {
    }

    /**
     * An Anthropic Messages API usage object (Claude message and result usage): input_tokens are the tokens neither read
     * from nor used to create a cache, so they are the uncached tranche as reported; cache reads, cache writes and the
     * cache_creation tiers (parts of cache_creation_input_tokens) are copied, and thinking tokens are part of output.
     */
    static void anthropic(Jv.Obj into, Jv usage) {
        Jv.Arr components = new Jv.Arr();
        reported(into, components, usage, "", "input_tokens", "uncached_input_tokens");
        reported(into, components, usage, "", "cache_read_input_tokens", "cache_read_tokens");
        reported(into, components, usage, "", "cache_creation_input_tokens", "cache_write_tokens");
        tiers(into, usage, "cache_creation");
        reported(into, components, usage, "", "output_tokens", "output_tokens");
        Jv thinking = usage == null ? null : usage.path("output_tokens_details", "thinking_tokens");
        if (isIntegral(thinking)) {
            into.put("reasoning_tokens", thinking);
            components.add(component("reasoning_tokens", 1, List.of("/output_tokens_details/thinking_tokens"), null));
            relation(into, 1);
        }
        finish(into, components);
    }

    /** Claude Code's per-model summary (ModelUsage, camel case): the same tranches under their own member names; thinking is inside output. */
    static void claudeModelUsage(Jv.Obj into, Jv usage) {
        Jv.Arr components = new Jv.Arr();
        reported(into, components, usage, "", "inputTokens", "uncached_input_tokens");
        reported(into, components, usage, "", "cacheReadInputTokens", "cache_read_tokens");
        reported(into, components, usage, "", "cacheCreationInputTokens", "cache_write_tokens");
        reported(into, components, usage, "", "outputTokens", "output_tokens");
        if (reported(into, components, usage, "", "thinkingTokens", "reasoning_tokens")) {
            relation(into, 1);
        }
        finish(into, components);
    }

    /**
     * A Codex TokenUsage ({@code prefix} is its pointer within the observation's data): input_tokens includes
     * cached_input_tokens (TokenUsage::non_cached_input), but the uncached tranche is never established: with reported cache
     * writes of 0, which may be the deserializer's default, their difference is kept only as the component's conditional
     * value; with non-zero cache writes the source does not establish whether input_tokens includes them and nothing is
     * derived. Reasoning is part of output.
     */
    static void codex(Jv.Obj into, Jv usage, String prefix) {
        codex(into, usage, prefix, false);
    }

    /**
     * As {@link #codex(Jv.Obj, Jv, String)}; with {@code measure} each component also states what the source establishes
     * about its quantity (codex.usage.component_measurement.v2, the exec stream route; the experimental rollout reading
     * does not state it yet, apart from the conditional uncached value every Codex reading keeps).
     */
    static void codex(Jv.Obj into, Jv usage, String prefix, boolean measure) {
        CodexReading r = new CodexReading(into, new Jv.Arr(), usage, prefix, measure, measure && codexUpdated(usage));
        r.uncached();
        r.copy("cached_input_tokens", "cache_read_tokens");
        r.copy("cache_write_input_tokens", "cache_write_tokens");
        r.copy("output_tokens", "output_tokens");
        if (r.copy("reasoning_output_tokens", "reasoning_tokens")) {
            relation(into, 1);
        }
        reported(into, r.components(), usage, prefix, "total_tokens", "reported_total_tokens");
        if (measure && r.components().elements().stream().anyMatch(c -> ((Jv.Obj) c).has("measurement_status_id"))) {
            Jv basis = into.member("basis");
            into.put("basis", (basis == null ? "" : basis.text() + "; ") + CODEX_MEASUREMENT);
        }
        finish(into, r.components());
    }

    /**
     * An Antigravity usage object (step and result usage of the headless stream): cache reads, output, thinking (as
     * reasoning) and the reported total are copied; the uncached tranche is unavailable because no published source states
     * whether input_tokens includes cache_read_tokens, and no reasoning relation is written because none states whether
     * thinking_tokens is part of output_tokens (agy.usage.tranches.v1).
     */
    static void antigravity(Jv.Obj into, Jv usage) {
        Jv.Arr components = new Jv.Arr();
        if (usage != null && (usage.member("input_tokens") != null || usage.member("cache_read_tokens") != null)) {
            components.add(component("uncached_input_tokens", 3, List.of("/input_tokens", "/cache_read_tokens"),
                    "agy.usage.tranches.v1: neither the headless documentation nor a published type states whether input_tokens includes cache_read_tokens; not copied as the uncached tranche"));
        }
        reported(into, components, usage, "", "cache_read_tokens", "cache_read_tokens");
        reported(into, components, usage, "", "output_tokens", "output_tokens");
        reported(into, components, usage, "", "thinking_tokens", "reasoning_tokens");
        reported(into, components, usage, "", "total_tokens", "reported_total_tokens");
        finish(into, components);
    }

    /** Whether any Codex member is non-zero: then a usage update arrived (codex.usage.component_measurement.v2). */
    private static boolean codexUpdated(Jv usage) {
        for (String m : CODEX_MEMBERS) {
            Jv v = usage == null ? null : usage.member(m);
            if (isIntegral(v) && ((Jv.Num) v).value().signum() != 0) {
                return true;
            }
        }
        return false;
    }

    /**
     * codex.usage.component_measurement.v2 for one member of the exec stream's running total: 2 Possibly default when no
     * update arrived or a member the deserializer defaults is 0; otherwise 4 Lower bound, since the total adds only the
     * responses whose usage arrived and a positive value does not establish that every contribution was reported.
     */
    static int codexMeasurement(String member, Jv.Num value, boolean updated) {
        if (!updated) {
            return 2;
        }
        return value.value().signum() == 0 && CODEX_DEFAULTED.contains(member) ? 2 : 4;
    }

    /** One Codex usage object being stated into an observation, with whether measurements are stated and whether an update arrived. */
    private record CodexReading(Jv.Obj into, Jv.Arr components, Jv usage, String prefix, boolean measure, boolean updated) {

        Jv member(String name) {
            return usage == null ? null : usage.member(name);
        }

        /**
         * The uncached tranche, which the pinned source never establishes exactly: its cache-write operand is either non-zero
         * (inclusion in input unknown) or a 0 the deserializer also writes for an omitted member. With cache writes 0 and
         * cached not above input the difference is kept as the component's conditional value (codex.usage.uncached_input.v2,
         * on every Codex reading); otherwise the component is unavailable with the reason.
         */
        void uncached() {
            Jv input = member("input_tokens");
            Jv cached = member("cached_input_tokens");
            Jv writes = member("cache_write_input_tokens");
            List<String> operands = List.of(prefix + "/input_tokens", prefix + "/cached_input_tokens", prefix + "/cache_write_input_tokens");
            if (!(isIntegral(input) && isIntegral(cached) && isIntegral(writes))) {
                if (input != null || cached != null) {
                    components.add(component("uncached_input_tokens", 3, operands, "codex.usage.uncached_input.v2: an operand is not reported as an integer"));
                }
                return;
            }
            BigDecimal in = ((Jv.Num) input).value();
            BigDecimal ca = ((Jv.Num) cached).value();
            String unavailable = unavailable(in, ca, ((Jv.Num) writes).value());
            if (unavailable != null) {
                components.add(component("uncached_input_tokens", 3, operands, unavailable));
                return;
            }
            Jv.Obj c = component("uncached_input_tokens", 3, operands, CODEX_UNCACHED);
            measured(c, 3, new Jv.Num(in.subtract(ca).toBigIntegerExact().toString()));
            components.add(c);
        }

        /** Why the uncached tranche cannot be derived from integral operands, or {@code null} when it can. */
        static String unavailable(BigDecimal input, BigDecimal cached, BigDecimal writes) {
            if (writes.signum() != 0) {
                return "codex.usage.uncached_input.v2: cache_write_input_tokens is non-zero and the pinned source does not establish "
                        + "whether input_tokens includes cache writes; the uncached tranche is not derived";
            }
            return input.compareTo(cached) < 0
                    ? "codex.usage.uncached_input.v2: cached_input_tokens exceeds input_tokens, which the inclusion the derivation relies on excludes; not clamped"
                    : null;
        }

        /** A reported member copied as its canonical component, with its measurement status when stated. */
        boolean copy(String nativeMember, String canonical) {
            if (!reported(into, components, usage, prefix, nativeMember, canonical)) {
                return false;
            }
            if (measure) {
                measured((Jv.Obj) components.elements().get(components.elements().size() - 1), codexMeasurement(nativeMember, (Jv.Num) member(nativeMember), updated), null);
            }
            return true;
        }
    }

    /**
     * States what the source establishes about a component's quantity, apart from how Journal obtained it
     * (component_basis_id), with a Conditional formula result kept as {@code conditional_value}, never the canonical value.
     */
    private static void measured(Jv.Obj component, int status, Jv conditional) {
        Jv derivation = component.members().remove("derivation");
        Jv pointers = component.members().remove("native_pointers");
        component.put("measurement_status_id", status).put("measurement_status", MEASUREMENT.get(status)).put("conditional_value", conditional)
                .put("native_pointers", pointers).put("derivation", derivation);
    }

    /** States the aggregation facet with its declared rule appended to the observation's basis. */
    static Jv.Obj aggregation(Jv.Obj into, int aggregation, String rule) {
        into.put("aggregation_id", aggregation).put("aggregation", AGGREGATION.get(aggregation));
        Jv basis = into.member("basis");
        into.put("basis", (basis == null ? "" : basis.text() + "; ") + rule);
        return into;
    }

    private static boolean reported(Jv.Obj into, Jv.Arr components, Jv usage, String prefix, String nativeMember, String canonical) {
        Jv v = usage == null ? null : usage.member(nativeMember);
        if (!isIntegral(v)) {
            return false;
        }
        into.put(canonical, v);
        components.add(component(canonical, 1, List.of(prefix + "/" + escape(nativeMember)), null));
        return true;
    }

    private static void tiers(Jv.Obj into, Jv usage, String member) {
        if (usage == null || !(usage.member(member) instanceof Jv.Obj breakdown)) {
            return;
        }
        Jv.Arr tiers = new Jv.Arr();
        for (Map.Entry<String, Jv> t : breakdown.members().entrySet()) {
            if (isIntegral(t.getValue())) {
                tiers.add(new Jv.Obj().put("tier", t.getKey()).put("token_count", t.getValue())
                        .put("native_pointers", Jv.arr("/" + escape(member) + "/" + escape(t.getKey()))));
            }
        }
        if (!tiers.elements().isEmpty()) {
            into.put("cache_write_tiers", tiers);
        }
    }

    private static void relation(Jv.Obj into, int relation) {
        into.put("reasoning_relation_id", relation).put("reasoning_relation", REASONING_RELATION.get(relation));
    }

    private static Jv.Obj component(String name, int basis, List<String> pointers, String derivation) {
        Jv.Obj c = new Jv.Obj().put("name", name).put("component_basis_id", basis).put("component_basis", COMPONENT_BASIS.get(basis))
                .put("native_pointers", Jv.arr(pointers.toArray(String[]::new)));
        return c.put("derivation", derivation);
    }

    /** Puts components[] and keeps the native data last, after every normalized member. */
    private static void finish(Jv.Obj into, Jv.Arr components) {
        if (!components.elements().isEmpty()) {
            into.put("components", components);
        }
        dataLast(into);
    }

    /** Moves the observation's native {@code data} after every other member (members added later otherwise follow it). */
    static Jv.Obj dataLast(Jv.Obj into) {
        Jv data = into.members().remove("data");
        return data == null ? into : into.put("data", data);
    }

    private static String escape(String name) {
        return name.replace("~", "~0").replace("/", "~1");
    }
}
