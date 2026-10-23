package io.github.markpollack.journal.ocsf.writer;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * The registry's {@code version_bootstrap}: per source profile, the one record that reports the CLI version (record
 * members that must equal the given strings) and the RFC 6901 pointer of the version in it. It is the only vendor
 * reading the writer does before a compatibility entry is selected (RULE-9): it reads the version and its location and
 * nothing else, so the selection can be declared before that same record is interpreted. A profile without a rule
 * never learns its version from its records.
 */
final class VersionBootstrap {

    /** One profile's rule: the identifying members, the version's pointer and the basis stated with the assertion. */
    record Rule(Map<String, String> record, String pointer, String basis) {
    }

    private final Map<String, Rule> rules;

    private VersionBootstrap(Map<String, Rule> rules) {
        this.rules = Map.copyOf(rules);
    }

    /** The rules of the registry's {@code version_bootstrap} member (its {@code meaning} text is not a rule). */
    static VersionBootstrap of(Jv member) {
        Map<String, Rule> rules = new TreeMap<>();
        if (member instanceof Jv.Obj vb) {
            for (Map.Entry<String, Jv> m : vb.members().entrySet()) {
                if (m.getValue() instanceof Jv.Obj rule) {
                    rules.put(m.getKey(), new Rule(discriminator(rule.member("record")), MapperSupport.text(rule, "pointer"), MapperSupport.text(rule, "basis")));
                }
            }
        }
        return new VersionBootstrap(rules);
    }

    private static Map<String, String> discriminator(Jv record) {
        Map<String, String> out = new TreeMap<>();
        if (record instanceof Jv.Obj rec) {
            rec.members().forEach((k, v) -> out.put(k, v.text()));
        }
        return Map.copyOf(out);
    }

    /** Why a rule is unusable: no identifying members, no RFC 6901 pointer or basis, or a profile that is not version-scoped. */
    List<String> problems(Set<String> notVersionScoped) {
        List<String> problems = new ArrayList<>();
        rules.forEach((profile, r) -> {
            if (r.record().isEmpty() || r.pointer() == null || !r.pointer().startsWith("/") || r.basis() == null) {
                problems.add("version_bootstrap " + profile + ": a rule needs the identifying record members, an RFC 6901 pointer and a basis");
            }
            if (notVersionScoped.contains(profile)) {
                problems.add("version_bootstrap " + profile + ": the profile is declared not version-scoped");
            }
        });
        return problems;
    }

    /**
     * The version asserted at the profile's declared location of a parsed record, or {@code null} when the profile has no
     * rule, the record is not its version-bearing record, or no version string is there.
     */
    Compatibility.Assertion read(String profile, String artifact, NativeRecord r) {
        Rule rule = rules.get(profile);
        if (rule == null || !r.parsed() || !identifies(rule, r.json())) {
            return null;
        }
        Jv v = at(r.json(), rule.pointer());
        return v instanceof Jv.Str s && !s.value().isBlank() ? new Compatibility.Assertion(s.value(), "source", rule.basis(), artifact, r.index(), rule.pointer()) : null;
    }

    private static boolean identifies(Rule rule, Jv json) {
        for (Map.Entry<String, String> m : rule.record().entrySet()) {
            if (!(json.member(m.getKey()) instanceof Jv.Str s) || !s.value().equals(m.getValue())) {
                return false;
            }
        }
        return true;
    }

    /** The value at an RFC 6901 pointer ({@code ~1} is {@code /}, {@code ~0} is {@code ~}), or {@code null}. */
    static Jv at(Jv root, String pointer) {
        Jv x = root;
        for (String raw : pointer.substring(1).split("/", -1)) {
            x = step(x, raw.replace("~1", "/").replace("~0", "~"));
            if (x == null) {
                return null;
            }
        }
        return x;
    }

    private static Jv step(Jv x, String step) {
        if (x instanceof Jv.Obj o) {
            return o.get(step);
        }
        if (x instanceof Jv.Arr a && step.matches("0|[1-9]\\d*") && Integer.parseInt(step) < a.elements().size()) {
            return a.elements().get(Integer.parseInt(step));
        }
        return null;
    }
}
