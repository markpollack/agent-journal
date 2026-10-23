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
    static final Map<Integer, String> MEASUREMENT = Map.of(0, "Unknown", 1, "Measured", 2, "Possibly default", 3, "Conditional");

    /** Codex TokenUsage members the pinned emitter writes as 0 when the provider omits them. */
    static final Set<String> CODEX_DEFAULTED = Set.of("cached_input_tokens", "cache_write_input_tokens", "reasoning_output_tokens");
    /** The Codex TokenUsage members whose values decide whether an update arrived. */
    static final List<String> CODEX_MEMBERS = List.of("input_tokens", "cached_input_tokens", "cache_write_input_tokens", "output_tokens", "reasoning_output_tokens");
    static final String CODEX_MEASUREMENT = "codex.usage.component_measurement.v1: at rust-v0.160.1 an omitted provider cache_write_tokens deserializes as 0 "
            + "(#[serde(default)]), an omitted input_tokens_details as cached and cache-write 0 (unwrap_or_default) and an omitted output_tokens_details as "
            + "reasoning 0 (unwrap_or(0)); input_tokens and output_tokens are required. With any non-zero member an update arrived: a non-zero member and a zero "
            + "required member are Measured, a zero cached, cache-write or reasoning member is Possibly default; with every member zero (Usage::default) each is "
            + "Possibly default; a component derived from a Possibly default operand is Conditional. A non-zero cumulative value assumes the provider reports the "
            + "member on every response or on none";

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
     * cached_input_tokens (TokenUsage::non_cached_input), so the uncached tranche is derived as their difference when the
     * reported cache writes are 0; when cache writes are reported non-zero the source does not establish whether
     * input_tokens includes them, and the tranche is unavailable rather than guessed. Reasoning is part of output.
     */
    static void codex(Jv.Obj into, Jv usage, String prefix) {
        codex(into, usage, prefix, false);
    }

    /**
     * As {@link #codex(Jv.Obj, Jv, String)}; with {@code measure} each component also states what the source establishes
     * about its quantity (codex.usage.component_measurement.v1, the exec stream route; the experimental rollout reading
     * does not state it yet).
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
        if (r.components().elements().stream().anyMatch(c -> ((Jv.Obj) c).has("measurement_status_id"))) {
            Jv basis = into.member("basis");
            into.put("basis", (basis == null ? "" : basis.text() + "; ") + CODEX_MEASUREMENT);
        }
        finish(into, r.components());
    }

    /** Whether any Codex member is non-zero: then a usage update arrived (codex.usage.component_measurement.v1). */
    private static boolean codexUpdated(Jv usage) {
        for (String m : CODEX_MEMBERS) {
            Jv v = usage == null ? null : usage.member(m);
            if (isIntegral(v) && ((Jv.Num) v).value().signum() != 0) {
                return true;
            }
        }
        return false;
    }

    /** codex.usage.component_measurement.v1 for one reported member: 1 Measured or 2 Possibly default. */
    static int codexMeasurement(String member, Jv.Num value, boolean updated) {
        if (!updated) {
            return 2;
        }
        return value.value().signum() == 0 && CODEX_DEFAULTED.contains(member) ? 2 : 1;
    }

    /** One Codex usage object being stated into an observation, with whether measurements are stated and whether an update arrived. */
    private record CodexReading(Jv.Obj into, Jv.Arr components, Jv usage, String prefix, boolean measure, boolean updated) {

        Jv member(String name) {
            return usage == null ? null : usage.member(name);
        }

        /** The uncached tranche: derived when cache writes are reported 0 and cached does not exceed input, else unavailable with the reason. */
        void uncached() {
            Jv input = member("input_tokens");
            Jv cached = member("cached_input_tokens");
            Jv writes = member("cache_write_input_tokens");
            List<String> operands = List.of(prefix + "/input_tokens", prefix + "/cached_input_tokens", prefix + "/cache_write_input_tokens");
            if (!(isIntegral(input) && isIntegral(cached) && isIntegral(writes))) {
                if (input != null || cached != null) {
                    components.add(component("uncached_input_tokens", 3, operands, "codex.usage.uncached_input.v1: an operand is not reported as an integer"));
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
            into.put("uncached_input_tokens", new Jv.Num(in.subtract(ca).toBigIntegerExact().toString()));
            List<String> defaulted = defaultedOperands();
            Jv.Obj c = component("uncached_input_tokens", 2, operands, "codex.usage.uncached_input.v1: input_tokens - cached_input_tokens, with cache_write_input_tokens reported 0 "
                    + "(input_tokens includes cached_input_tokens: TokenUsage::non_cached_input)" + conditionText(defaulted));
            if (measure) {
                measured(c, defaulted.isEmpty() ? 1 : 3);
            }
            components.add(c);
        }

        /** Why the uncached tranche cannot be derived from integral operands, or {@code null} when it can. */
        static String unavailable(BigDecimal input, BigDecimal cached, BigDecimal writes) {
            if (writes.signum() != 0) {
                return "codex.usage.uncached_input.v1: cache_write_input_tokens is non-zero and the pinned source does not establish "
                        + "whether input_tokens includes cache writes; the uncached tranche is not derived";
            }
            return input.compareTo(cached) < 0
                    ? "codex.usage.uncached_input.v1: cached_input_tokens exceeds input_tokens, which the inclusion the derivation relies on excludes; not clamped"
                    : null;
        }

        /** The operands of the uncached derivation that are Possibly default (none when measurements are not stated). */
        List<String> defaultedOperands() {
            List<String> out = new java.util.ArrayList<>();
            for (String m : measure ? List.of("input_tokens", "cached_input_tokens", "cache_write_input_tokens") : List.<String>of()) {
                if (codexMeasurement(m, (Jv.Num) member(m), updated) == 2) {
                    out.add(m);
                }
            }
            return out;
        }

        static String conditionText(List<String> defaulted) {
            if (defaulted.isEmpty()) {
                return "";
            }
            String names = String.join(" and ", defaulted);
            String verb = defaulted.size() > 1 ? " are" : " is";
            return "; " + names + verb + " Possibly default, so the value is the input not read from cache and is the uncached tranche only if " + names + verb + " measured";
        }

        /** A reported member copied as its canonical component, with its measurement status when stated. */
        boolean copy(String nativeMember, String canonical) {
            if (!reported(into, components, usage, prefix, nativeMember, canonical)) {
                return false;
            }
            if (measure) {
                measured((Jv.Obj) components.elements().get(components.elements().size() - 1), codexMeasurement(nativeMember, (Jv.Num) member(nativeMember), updated));
            }
            return true;
        }
    }

    /** States what the source establishes about a component's quantity, apart from how Journal obtained it (component_basis_id). */
    private static void measured(Jv.Obj component, int status) {
        Jv derivation = component.members().remove("derivation");
        Jv pointers = component.members().remove("native_pointers");
        component.put("measurement_status_id", status).put("measurement_status", MEASUREMENT.get(status)).put("native_pointers", pointers).put("derivation", derivation);
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
