package io.github.markpollack.journal.tck.ocsf;

import java.util.Collections;
import java.util.Set;
import java.util.TreeSet;

/**
 * The declared native payload attributes of a package, read independently from the definition closure the package
 * declares (and the reader validates): every attribute typed {@code json_t}, except {@code data}, which on a Journal
 * object is its native residual. Inside a payload every member is a fact, whatever its key, so the reader's provenance
 * and residual exemptions apply only outside one.
 */
public final class PayloadBoundary {
    /** No declared payloads: every exemption applies as before (a package without a definition closure). */
    public static final PayloadBoundary NONE = new PayloadBoundary(Set.of());

    private final Set<String> names;

    private PayloadBoundary(Set<String> names) {
        this.names = Collections.unmodifiableSet(names);
    }

    public static PayloadBoundary of(SchemaCatalog catalog) {
        if (catalog == null) {
            return NONE;
        }
        Set<String> names = new TreeSet<>();
        for (var definitions : java.util.List.of(catalog.classes(), catalog.objects())) {
            definitions.values().forEach(d -> d.attributes().values().forEach(a -> {
                if ("json_t".equals(a.type())) { names.add(a.name()); }
            }));
        }
        names.remove("data");
        return new PayloadBoundary(names);
    }

    public Set<String> names() {
        return names;
    }

    /** Whether the JSON pointer (or comparison key) {@code pointer} lies inside a declared payload member. */
    public boolean contains(String pointer) {
        for (String segment : pointer.split("/")) {
            int brace = segment.indexOf('{');
            String name = (brace >= 0 ? segment.substring(0, brace) : segment).replace("~1", "/").replace("~0", "~");
            if (names.contains(name)) { return true; }
        }
        return false;
    }
}
