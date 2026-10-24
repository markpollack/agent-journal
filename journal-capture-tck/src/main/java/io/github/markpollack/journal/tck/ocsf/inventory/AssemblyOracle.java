package io.github.markpollack.journal.tck.ocsf.inventory;

import io.github.markpollack.journal.tck.ocsf.Event;
import io.github.markpollack.journal.tck.ocsf.NativeArtifact;
import io.github.markpollack.journal.tck.ocsf.OcsfPackage;
import io.github.markpollack.journal.tck.ocsf.OracleReport;
import io.github.markpollack.journal.tck.ocsf.inventory.SourceLocator.Located;
import io.github.markpollack.journal.tck.verify.Check.Category;
import io.github.markpollack.journal.tck.verify.Check.Outcome;
import io.github.markpollack.journal.tck.verify.Json;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The independent oracle of a finally assembled file. It judges the file against the native bytes that were supplied
 * and an assembly expectation written from the vendor formats (agent-journal/assembly-expectation): per source role,
 * where the native records report their session; per entity, which native records report the same identity (RFC 6901
 * locators into the vendor records, never the writer's events); and per accounting total, which native values one
 * quantity sums. From these alone it derives which sources belong to the execution, which observations are one entity
 * and how many canonical entities there are, and compares the file with them: the declaration, the gate verdicts, the
 * required roles, the entity partition, the consolidated events' references and fields, and the accounting. It never
 * calls the writer's assembly and does not read the profile the writer applied for its expectations.
 */
public final class AssemblyOracle {

    public static final String SPECIFICATION_KIND = "agent-journal/assembly-expectation";
    static final String OWNER = "assembly";

    /**
     * One native source supplied to the assembly.
     *
     * @param name the artifact name the file declares
     * @param role the role it was supplied for
     * @param origin where it was acquired (its import identity together with its sha256)
     * @param bytes the native bytes, framed as the role declares
     */
    public record Supplied(String name, String role, String origin, NativeArtifact bytes) {
    }

    /** The oracle's own verdict for one supplied source. */
    record Expected(Supplied source, Set<String> sessions, boolean accepted, String reason) {
    }

    private AssemblyOracle() {
    }

    public static OracleReport verify(OcsfPackage pkg, Json spec, List<Supplied> supplied) {
        if (!SPECIFICATION_KIND.equals(RawInventory.text(spec, "kind"))) {
            throw new IllegalArgumentException("not an assembly expectation: kind " + RawInventory.text(spec, "kind"));
        }
        OracleReport report = new OracleReport();
        Json declared = pkg.declarations == null ? null : pkg.declarations.assembly();
        if (declared == null) {
            report.add(Category.COMPLETENESS, "assembly.declaration", OWNER, Outcome.DEFECTIVE, "the file declares no final assembly");
            return report;
        }
        declaration(report, declared, spec, supplied);
        Map<String, Expected> expected = expect(spec, supplied);
        gate(report, declared, expected, pkg);
        required(report, declared, spec, expected);
        List<Supplied> accepted = new ArrayList<>();
        expected.values().stream().filter(Expected::accepted).forEach(e -> accepted.add(e.source()));
        AssemblyPartition.check(report, pkg, spec, accepted);
        AssemblyConsolidation.check(report, pkg);
        AssemblyAccounting.check(report, pkg, spec, accepted);
        return report;
    }

    // ------------------------------------------------------------------------------------------ declaration

    private static void declaration(OracleReport report, Json declared, Json spec, List<Supplied> supplied) {
        List<String> problems = new ArrayList<>();
        String profile = RawInventory.text(declared, "profile", "profile_id");
        if (!RawInventory.text(spec, "profile_id").equals(profile)) {
            problems.add("profile " + profile + ", expected " + RawInventory.text(spec, "profile_id"));
        }
        Map<String, Json> sources = declaredSources(declared);
        for (Supplied s : supplied) {
            String mismatch = mismatch(s, sources.get(s.name()));
            if (mismatch != null) {
                problems.add(mismatch);
            }
        }
        if (sources.size() != supplied.size()) {
            problems.add(sources.size() + " declared sources, " + supplied.size() + " supplied");
        }
        report.add(Category.COMPLETENESS, "assembly.declaration", OWNER, problems.isEmpty() ? Outcome.VERIFIED : Outcome.DEFECTIVE,
                problems.isEmpty() ? "profile " + profile + "; every supplied source declared with its role, origin and sha256" : String.join("; ", problems));
    }

    private static String mismatch(Supplied s, Json d) {
        if (d == null) {
            return s.name() + " is not declared";
        }
        boolean same = s.role().equals(RawInventory.text(d, "role")) && s.origin().equals(RawInventory.text(d, "origin")) && s.bytes().sha256().equals(RawInventory.text(d, "sha256"));
        return same ? null : s.name() + " declared as role " + RawInventory.text(d, "role") + " from " + RawInventory.text(d, "origin") + " sha256 " + RawInventory.text(d, "sha256")
                + ", supplied as role " + s.role() + " from " + s.origin() + " sha256 " + s.bytes().sha256();
    }

    static Map<String, Json> declaredSources(Json declared) {
        Map<String, Json> out = new LinkedHashMap<>();
        for (Json s : declared.member("sources").elements()) {
            out.put(RawInventory.text(s, "artifact"), s);
        }
        return out;
    }

    // ------------------------------------------------------------------------------------------ gate

    /**
     * The oracle's verdicts from the native bytes: the run's session is the single identity the first source in the
     * expectation's precedence reports; a source reporting exactly it belongs to the execution, any other identity or
     * several do not; a source without one belongs only when its role is optional, bound by acquisition, or identified
     * by a companion that belongs; the same origin with the same bytes is the same import, accepted once.
     */
    static Map<String, Expected> expect(Json spec, List<Supplied> supplied) {
        Map<String, Json> roles = roles(spec);
        List<Supplied> ordered = new ArrayList<>(supplied);
        List<String> precedence = texts(spec.member("precedence"));
        ordered.sort((a, b) -> Integer.compare(precedence.indexOf(a.role()), precedence.indexOf(b.role())));
        Map<String, Set<String>> sessions = new LinkedHashMap<>();
        for (Supplied s : ordered) {
            sessions.put(s.name(), sessionsOf(s, roles.get(s.role()), ordered, roles));
        }
        String run = null;
        for (Supplied s : ordered) {
            if (run == null && sessions.get(s.name()).size() == 1) {
                run = sessions.get(s.name()).iterator().next();
            }
        }
        Map<String, Expected> out = new LinkedHashMap<>();
        Set<String> imports = new LinkedHashSet<>();
        for (Supplied s : ordered) {
            out.put(s.name(), verdict(s, roles.get(s.role()), sessions.get(s.name()), run, imports.add(s.origin() + "|" + s.bytes().sha256())));
        }
        return out;
    }

    private static Expected verdict(Supplied s, Json role, Set<String> own, String run, boolean firstImport) {
        boolean required = "required".equals(RawInventory.text(role, "requirement"));
        if (!firstImport) {
            return new Expected(s, own, false, "the same origin and bytes as a source already imported");
        }
        if ("acquisition".equals(RawInventory.text(role, "identity"))) {
            return new Expected(s, own, true, "bound by its acquisition");
        }
        if (own.isEmpty()) {
            return new Expected(s, own, !required, required ? "a required source reports no session" : "an optional source without a session");
        }
        boolean same = own.size() == 1 && own.contains(run);
        return new Expected(s, own, same, same ? "reports the run's session " + run : "reports " + own + ", not the run's session " + run);
    }

    private static Set<String> sessionsOf(Supplied s, Json role, List<Supplied> all, Map<String, Json> roles) {
        String companion = RawInventory.text(role, "companion");
        if (companion != null) {
            Set<String> out = new LinkedHashSet<>();
            for (Supplied c : all) {
                if (c.role().equals(companion)) {
                    out.addAll(sessionsOf(c, roles.get(companion), List.of(), roles));
                }
            }
            return out;
        }
        Json locators = role.member("session");
        return locators == null ? Set.of() : SourceLocator.values(located(s), locators);
    }

    private static void gate(OracleReport report, Json declared, Map<String, Expected> expected, OcsfPackage pkg) {
        List<String> problems = new ArrayList<>();
        Map<String, Json> sources = declaredSources(declared);
        for (Expected e : expected.values()) {
            problems.addAll(gateProblems(e, sources.get(e.source().name()), eventsOf(pkg, e.source().name())));
        }
        StringBuilder summary = new StringBuilder();
        expected.values().forEach(e -> summary.append(e.source().name()).append(e.accepted() ? " accepted (" : " refused (").append(e.reason()).append("); "));
        report.add(Category.COMPLETENESS, "assembly.gate", OWNER, problems.isEmpty() ? Outcome.VERIFIED : Outcome.DEFECTIVE,
                problems.isEmpty() ? summary.toString().trim() : String.join("; ", problems));
    }

    private static List<String> gateProblems(Expected e, Json declared, int written) {
        List<String> problems = new ArrayList<>();
        boolean writerAccepted = declared != null && declared.member("accepted") instanceof Json.JBool b && b.value();
        if (writerAccepted != e.accepted()) {
            problems.add(e.source().name() + " " + (writerAccepted ? "accepted" : "refused") + " by the file; the native bytes say " + (e.accepted() ? "accept" : "refuse") + " (" + e.reason() + ")");
        }
        if (!e.accepted() && written > 0) {
            problems.add(e.source().name() + " is refused yet " + written + " event(s) of it are written");
        }
        return problems;
    }

    private static int eventsOf(OcsfPackage pkg, String artifact) {
        int n = 0;
        for (Event e : pkg.events) {
            if (e.className != null && artifact.equals(e.artifact())) {
                n++;
            }
        }
        return n;
    }

    private static void required(OracleReport report, Json declared, Json spec, Map<String, Expected> expected) {
        List<String> missing = new ArrayList<>();
        for (Json r : spec.member("sources").elements()) {
            String role = RawInventory.text(r, "role");
            boolean present = expected.values().stream().anyMatch(e -> e.accepted() && e.source().role().equals(role));
            if ("required".equals(RawInventory.text(r, "requirement")) && !present) {
                missing.add(role);
            }
        }
        String status = RawInventory.text(declared, "result", "status");
        List<String> declaredMissing = texts(declared.path("result", "missing_required_roles"));
        boolean ok = missing.isEmpty() ? !"incomplete".equals(status) && declaredMissing.isEmpty() : "incomplete".equals(status) && declaredMissing.equals(missing);
        report.add(Category.COMPLETENESS, "assembly.required", OWNER, ok ? Outcome.VERIFIED : Outcome.DEFECTIVE,
                "required roles missing per the native bytes " + missing + "; the file says status " + status + ", missing " + declaredMissing);
    }

    // ------------------------------------------------------------------------------------------ shared

    static Map<String, Json> roles(Json spec) {
        Map<String, Json> out = new LinkedHashMap<>();
        for (Json r : spec.member("sources").elements()) {
            out.put(RawInventory.text(r, "role"), r);
        }
        return out;
    }

    static List<String> texts(Json array) {
        List<String> out = new ArrayList<>();
        for (Json v : array == null ? List.<Json>of() : array.elements()) {
            out.add(v.text());
        }
        return out;
    }

    static List<Integer> integers(Json array) {
        List<Integer> out = new ArrayList<>();
        for (Json v : array == null ? List.<Json>of() : array.elements()) {
            out.add(((Json.JNumber) v).value().intValueExact());
        }
        return out;
    }

    static String write(Json json) {
        String text = io.github.markpollack.journal.tck.verify.NeutralJson.write(json);
        return text.length() > 120 ? text.substring(0, 117) + "..." : text;
    }

    static List<Located> located(Supplied s) {
        List<Located> out = new ArrayList<>();
        for (NativeArtifact.Record r : s.bytes().records()) {
            if (r.parsed()) {
                out.add(new Located(s.name(), r.index(), r.json()));
            }
        }
        return out;
    }
}
