package io.github.markpollack.journal.tck.ocsf;

import io.github.markpollack.journal.tck.verify.Check;
import io.github.markpollack.journal.tck.verify.Json;

import java.util.List;
import java.util.Map;

/**
 * Attributes the existing independent protocol extraction to the native projection and its exact
 * canonical slot.
 */
final class ProtocolComparisons {

    private ProtocolComparisons() {}

    static void register(OracleReport report, ExpectedFacts facts, String owner) {
        for (ExpectedFacts.Mcp m : facts.mcp) {
            for (ExpectedFacts.McpProjection p : m.projections()) {
                report.expect(
                        new OracleReport.JsonExpectation(
                                owner,
                                key(m, p),
                                "mcp.partial_projection/v1",
                                p.value(),
                                support(facts, m)));
            }
        }
    }

    static void check(
            OracleReport report,
            ExpectedFacts facts,
            List<Event> events,
            String owner,
            Map<String, McpProtocol.Binding> bindings) {
        Comparison comparison = new Comparison(report, facts, owner, bindings);
        facts.mcp.forEach(
                m ->
                        comparison.check(
                                m,
                                McpChecks.find(events, m.recordIndex(), m.id(), m.activityId())));
    }

    private record Comparison(
            OracleReport report,
            ExpectedFacts facts,
            String owner,
            Map<String, McpProtocol.Binding> bindings) {
        private void check(ExpectedFacts.Mcp m, Event event) {
            List<Json> objects =
                    event == null ? List.of() : event.elements("mcp", "protocol_objects");
            FactComparison.compare(
                    report,
                    "fact.mcp.population",
                    owner,
                    new Check.Evidence(
                            "mcp.partial_projection/v1",
                            m.recordIndex() + ":" + m.id() + ":projections",
                            support(facts, m),
                            null,
                            FactComparison.value(m.projections().size()),
                            FactComparison.value(objects.size())));
            m.projections().forEach(expected -> projection(m, event, objects, expected));
        }

        private void projection(
                ExpectedFacts.Mcp m,
                Event event,
                List<Json> objects,
                ExpectedFacts.McpProjection expected) {
            List<Json> matches =
                    objects.stream()
                            .filter(
                                    o ->
                                            expected.definition()
                                                    .equals(
                                                            SourceProfile.text(
                                                                    o, "protocol_definition")))
                            .toList();
            Json actual = matches.size() == 1 ? matches.get(0) : null;
            Validation validation = extract(actual, expected, bindings);
            Json extracted = validation.value();
            boolean valid = validation.valid();
            boolean same = valid && Json.sameValue(expected.value(), extracted);
            String address =
                    actual == null
                            ? null
                            : owner
                                    + ":line="
                                    + event.line
                                    + "#/mcp/protocol_objects/"
                                    + objects.indexOf(actual);
            report.add(
                    new Check(
                            Check.Category.NORMALIZATION,
                            "fact.mcp.value",
                            owner,
                            same ? Check.Outcome.VERIFIED : Check.Outcome.DEFECTIVE,
                            key(m, expected)
                                    + (same
                                            ? " equals the native partial projection through its"
                                                    + " pinned binding"
                                            : " missing, ambiguous or different projection"),
                            1,
                            new Check.Evidence(
                                    "mcp.partial_projection/v1",
                                    key(m, expected),
                                    support(facts, m),
                                    address,
                                    expected.value(),
                                    extracted,
                                    actual)));
        }
    }

    private record Validation(Json value, boolean valid) {}

    private static Validation extract(
            Json actual,
            ExpectedFacts.McpProjection expected,
            Map<String, McpProtocol.Binding> bindings) {
        Json extracted = null;
        boolean valid = false;
        if (actual != null) {
            try {
                McpProtocol.Extracted x = McpProtocol.extractProtocolObject(bindings, actual);
                extracted = x.value();
                valid =
                        x.projection() == 2
                                && "2025-11-25".equals(x.revision())
                                && x.missingRequired().equals(expected.missingRequired())
                                && McpProtocol.validate(
                                                bindings.get(x.revision()),
                                                x.definition(),
                                                x.value(),
                                                java.util.Set.copyOf(x.missingRequired()))
                                        .isEmpty();
            } catch (IllegalArgumentException | IllegalStateException ex) {
                valid = false;
            }
        }
        return new Validation(extracted, valid);
    }

    private static String key(ExpectedFacts.Mcp m, ExpectedFacts.McpProjection p) {
        return m.recordIndex() + ":" + m.id() + ":" + m.activityId() + ":" + p.definition();
    }

    private static List<String> support(ExpectedFacts facts, ExpectedFacts.Mcp m) {
        return List.of(facts.artifact + ":record=" + m.recordIndex() + "#");
    }
}
