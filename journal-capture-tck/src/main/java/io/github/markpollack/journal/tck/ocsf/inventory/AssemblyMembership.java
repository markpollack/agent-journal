package io.github.markpollack.journal.tck.ocsf.inventory;

import io.github.markpollack.journal.tck.ocsf.inventory.AssemblyOracle.Expected;
import io.github.markpollack.journal.tck.ocsf.inventory.AssemblyOracle.Supplied;
import io.github.markpollack.journal.tck.verify.Json;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The assembly oracle's own membership verdicts, from the native bytes and the supplier's assertions alone (never the
 * writer's gate): which supplied sources belong to the execution being assembled.
 */
final class AssemblyMembership {

    private AssemblyMembership() {
    }

    /**
     * The oracle's verdicts from the native bytes and the supplier's assertions: the run's session is the single
     * identity the first source in the expectation's precedence reports (the anchor); a source reporting exactly it
     * belongs to the execution, any other identity or several do not. A source reporting none belongs only when it is
     * not required (or bound by acquisition) and its asserted acquisition is the anchor's; a companion-identified role
     * takes its companion's sessions only within one asserted acquisition. The same origin with the same bytes is the
     * same import, accepted once; equal bytes from another origin belong only as a declared copy of an accepted source.
     */
    static Map<String, Expected> expect(Json spec, List<Supplied> supplied) {
        Map<String, Json> roles = AssemblyOracle.roles(spec);
        List<Supplied> ordered = new ArrayList<>(supplied);
        List<String> precedence = AssemblyOracle.texts(spec.member("precedence"));
        ordered.sort((a, b) -> Integer.compare(precedence.indexOf(a.role()), precedence.indexOf(b.role())));
        Map<String, Set<String>> sessions = new LinkedHashMap<>();
        for (Supplied s : ordered) {
            sessions.put(s.name(), sessionsOf(s, roles.get(s.role()), ordered, roles));
        }
        Map<String, Expected> out = new LinkedHashMap<>();
        Supplied anchor = identified(ordered, sessions, out);
        // what is left, apart from declared copies, reports no session of its own
        for (Supplied s : ordered.stream().filter(x -> !out.containsKey(x.name()) && x.copyOf() == null).toList()) {
            out.put(s.name(), identityFree(s, roles.get(s.role()), anchor));
        }
        for (Supplied s : ordered) {
            if (s.copyOf() != null && !out.containsKey(s.name())) {
                out.put(s.name(), copy(s, out.get(s.copyOf())));
            }
        }
        Map<String, Expected> inOrder = new LinkedHashMap<>();
        ordered.forEach(s -> inOrder.put(s.name(), out.get(s.name())));
        return inOrder;
    }

    /**
     * Judges repeated imports, undeclared equal bytes and every source that reports a session into {@code out}; returns
     * the anchor, the first source that reports exactly one session. Declared copies and sources that report no session
     * are left for the later passes.
     */
    private static Supplied identified(List<Supplied> ordered, Map<String, Set<String>> sessions, Map<String, Expected> out) {
        Set<String> imports = new LinkedHashSet<>();
        Map<String, String> bytesSeen = new LinkedHashMap<>();
        Supplied anchor = null;
        for (Supplied s : ordered) {
            Set<String> own = sessions.get(s.name());
            Expected e = screened(s, own, imports, bytesSeen);
            if (e == null && s.copyOf() == null && !own.isEmpty()) {
                e = reported(s, own, anchor == null ? null : sessions.get(anchor.name()).iterator().next());
                anchor = anchor == null && e.accepted() ? s : anchor;
            }
            if (e != null) {
                out.put(s.name(), e);
            }
        }
        return anchor;
    }

    /** A repeated import, or equal bytes from another origin without a declared copy lineage; {@code null} otherwise. */
    private static Expected screened(Supplied s, Set<String> own, Set<String> imports, Map<String, String> bytesSeen) {
        if (!imports.add(s.origin() + "|" + s.bytes().sha256())) {
            return new Expected(s, own, false, "the same origin and bytes as a source already imported");
        }
        if (s.copyOf() != null) {
            return null;
        }
        String earlier = bytesSeen.putIfAbsent(s.bytes().sha256(), s.name());
        return earlier == null ? null : new Expected(s, own, false, "the bytes of " + earlier + " from another origin, not declared a copy of it");
    }

    /** A source reporting sessions: a member when it reports exactly the run's (or, before the run is known, exactly one). */
    private static Expected reported(Supplied s, Set<String> own, String run) {
        boolean same = own.size() == 1 && (run == null || own.contains(run));
        return new Expected(s, own, same, same ? "reports the run's session " + own.iterator().next() : "reports " + own + ", not the run's session " + run);
    }

    /** A source reporting no session: a member only when not required (or bound by acquisition) and asserted in the anchor's acquisition. */
    private static Expected identityFree(Supplied s, Json role, Supplied anchor) {
        boolean required = "required".equals(RawInventory.text(role, "requirement"));
        boolean acquisitionBound = "acquisition".equals(RawInventory.text(role, "identity")) && RawInventory.text(role, "companion") == null;
        boolean member = anchor != null && s.acquisition() != null && s.acquisition().equals(anchor.acquisition());
        boolean accepted = (!required || acquisitionBound) && member;
        return new Expected(s, Set.of(), accepted, accepted ? "no session; asserted acquisition " + s.acquisition() + " is the anchor's"
                : required && !acquisitionBound ? "a required source reports no session" : "no session, and the asserted acquisition " + s.acquisition() + " is not the anchor's");
    }

    /** A declared copy: its original's verdict when that is accepted with the same role and bytes. */
    private static Expected copy(Supplied s, Expected original) {
        Supplied o = original == null ? null : original.source();
        boolean accepted = o != null && original.accepted() && o.bytes().sha256().equals(s.bytes().sha256()) && o.role().equals(s.role());
        return new Expected(s, original == null ? Set.of() : original.sessions(), accepted, accepted ? "a declared copy of " + s.copyOf() + " with its bytes"
                : "declared a copy of " + s.copyOf() + ", which is not an accepted source of the same role and bytes");
    }

    private static Set<String> sessionsOf(Supplied s, Json role, List<Supplied> all, Map<String, Json> roles) {
        String companion = RawInventory.text(role, "companion");
        if (companion != null) {
            Set<String> out = new LinkedHashSet<>();
            for (Supplied c : all) {
                if (c.role().equals(companion) && s.acquisition() != null && s.acquisition().equals(c.acquisition())) {
                    out.addAll(sessionsOf(c, roles.get(companion), List.of(), roles));
                }
            }
            return out;
        }
        Json locators = role.member("session");
        return locators == null ? Set.of() : SourceLocator.values(AssemblyOracle.located(s), locators);
    }
}
