package io.github.markpollack.journal.ocsf.writer;

import java.util.List;

import static io.github.markpollack.journal.ocsf.writer.MapperSupport.isIntegral;
import static io.github.markpollack.journal.ocsf.writer.MapperSupport.text;

/**
 * Junie 3813.7 usage entries ({@code model, cost, inputTokens, cacheInputTokens, cacheCreateTokens, outputTokens}, with
 * {@code calls} on standard output's result): token tranches with their components, and the entry's {@code cost} as a
 * quantity whose unit the source does not name (quantity kind 99 Other), never money: the per-model costs of the
 * recorded runs sum to the task's named USD total, but a sum in one run does not name their unit
 * (junie.usage.unnamed_cost.v1). Only {@code taskCostUsd} is money.
 */
final class JunieUsage {

    static final String UNCACHED = "junie.usage.uncached_input.v2: inputTokens is uncached only if both reported cache zeros are actual measurements; the recorded empirical source does not establish that premise";
    static final String UNNAMED = "cost (unit not named by the source)";

    private JunieUsage() {
    }

    /** One usage entry as a usage observation at {@code scope}, with its model. */
    static Jv.Obj usage(Jv entry, int scope, int status, int aggregation, String basis, Jv sourceRef) {
        Jv.Obj ob = MapperSupport.usage(scope, status, basis, sourceRef, MapperSupport.modelRef(text(entry, "model"), 1, null), entry);
        UsageTranches.aggregation(ob, aggregation, aggregation == 2 ? "a cumulative total of the model's usage in the task" : "the call's own total");
        Jv.Arr components = new Jv.Arr();
        Jv in = entry.member("inputTokens");
        Jv reads = entry.member("cacheInputTokens");
        Jv writes = entry.member("cacheCreateTokens");
        if (isIntegral(in) && isIntegral(reads) && isIntegral(writes) && ((Jv.Num) reads).value().signum() == 0 && ((Jv.Num) writes).value().signum() == 0) {
            components.add(component("uncached_input_tokens", 3, Jv.arr("/inputTokens", "/cacheInputTokens", "/cacheCreateTokens"), UNCACHED)
                    .put("measurement_status_id", 3).put("conditional_value", in));
        }
        copy(ob, components, entry, "cacheInputTokens", "cache_read_tokens");
        copy(ob, components, entry, "cacheCreateTokens", "cache_write_tokens");
        copy(ob, components, entry, "outputTokens", "output_tokens");
        if (!components.elements().isEmpty()) {
            ob.put("components", components);
        }
        return UsageTranches.dataLast(ob);
    }

    private static void copy(Jv.Obj ob, Jv.Arr components, Jv entry, String member, String canonical) {
        if (isIntegral(entry.member(member))) {
            ob.put(canonical, entry.member(member));
            Jv.Obj c = component(canonical, 1, Jv.arr("/" + member), null);
            if (canonical.startsWith("cache_") && ((Jv.Num) entry.member(member)).value().signum() == 0) c.put("measurement_status_id", 2);
            components.add(c);
        }
    }

    private static Jv.Obj component(String name, int basis, Jv pointers, String derivation) {
        return new Jv.Obj().put("name", name).put("component_basis_id", basis).put("component_basis", UsageTranches.COMPONENT_BASIS.get(basis)).put("native_pointers", pointers)
                .put("derivation", derivation);
    }

    /** The entry's unnamed-unit cost (kind 99 Other, no currency) for its model at {@code scope}; {@code null} when it reports none. */
    static Jv.Obj cost(Jv entry, int scope, int aggregation, Jv sourceRef) {
        if (!(entry.member("cost") instanceof Jv.Num c)) {
            return null;
        }
        Jv.Obj co = CopilotAccounting.cost(new CopilotAccounting.Quantity(99, UNNAMED), c, scope, aggregation, "cost", sourceRef);
        return co.put("model_ref", MapperSupport.modelRef(text(entry, "model"), 1, null));
    }

    /** Per-model usage and costs of a usage entry list (summary.json modelUsage, standard output's result errorCode). */
    static void perModel(Jv entries, int status, String basis, Jv sourceRef, Jv.Arr usage, Jv.Arr costs) {
        for (Jv entry : entries == null ? List.<Jv>of() : entries.elements()) {
            usage.add(usage(entry, 6, status, 2, basis, sourceRef));
            Jv.Obj c = cost(entry, 6, 2, sourceRef);
            if (c != null) {
                costs.add(c);
            }
        }
    }
}
