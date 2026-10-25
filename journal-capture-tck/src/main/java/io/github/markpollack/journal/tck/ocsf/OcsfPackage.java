package io.github.markpollack.journal.tck.ocsf;

import io.github.markpollack.journal.tck.verify.Json;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * A run package as the reader opened it: its directory, manifest, events (raw lines are kept even when the reader
 * refuses to interpret them), the definition catalog when the manifest carries one, the linked child packages and
 * every explicit {@link Diagnostic}.
 */
public final class OcsfPackage {

    public final Path dir;
    public final RunManifest manifest;
    public final List<Event> events;
    public final List<String> rawLines;
    public final SchemaCatalog catalog;
    public final List<OcsfPackage> children;
    public final List<Diagnostic> diagnostics;
    /** The declarations of a standalone event file; {@code null} for the historical two-file layouts. */
    public final DeclaredFile declarations;

    OcsfPackage(Path dir, RunManifest manifest, List<Event> events, List<String> rawLines, SchemaCatalog catalog,
            List<OcsfPackage> children, List<Diagnostic> diagnostics, DeclaredFile declarations) {
        this.dir = dir;
        this.manifest = manifest;
        this.events = List.copyOf(events);
        this.rawLines = List.copyOf(rawLines);
        this.catalog = catalog;
        this.children = List.copyOf(children);
        this.diagnostics = List.copyOf(diagnostics);
        this.declarations = declarations;
    }

    /** Whether this package was read from a standalone event file (its declarations), not from a sibling manifest. */
    public boolean standalone() {
        return declarations != null;
    }

    /** How a file's sources were combined: the writer's combination policy, as the file itself states it. */
    public enum Combination {
        /** One declared source: incremental recording, nothing to combine. */
        SINGLE_SOURCE,
        /** A final assembly declared in the file: the only supported combination of several sources. */
        ASSEMBLED,
        /** Several sources declared as an unvalidated evidence log: no identity relation between them was checked. */
        UNVALIDATED_EVIDENCE_LOG,
        /**
         * Several sources with neither an assembly nor an evidence-log declaration: written before the combination
         * policy by the plain writer, readable as before, and unvalidated in the same way.
         */
        UNVALIDATED_UNDECLARED
    }

    /**
     * How this file combined its sources. Only {@link Combination#ASSEMBLED} (and a single source) can be a final
     * execution; in either unvalidated form an entity two sources report appears once per source.
     */
    public Combination combination() {
        int sources = declarations != null ? declarations.sources().size() : manifest == null ? 0 : manifest.sources().size();
        if (declarations != null && declarations.assembly() != null) {
            return Combination.ASSEMBLED;
        }
        if (declarations != null && declarations.run() != null && declarations.run().member("combination") != null) {
            return Combination.UNVALIDATED_EVIDENCE_LOG;
        }
        return sources > 1 ? Combination.UNVALIDATED_UNDECLARED : Combination.SINGLE_SOURCE;
    }

    /** Whether the reader refused to interpret this package (its events are then raw only). */
    public boolean refused() {
        for (Diagnostic d : diagnostics) {
            if (d.severity() == Diagnostic.Severity.REFUSED) {
                return true;
            }
        }
        return false;
    }

    public List<Diagnostic> diagnostics(Diagnostic.Severity severity) {
        List<Diagnostic> out = new ArrayList<>();
        for (Diagnostic d : diagnostics) {
            if (d.severity() == severity) {
                out.add(d);
            }
        }
        return out;
    }

    public List<Diagnostic> diagnosticsWithCode(String code) {
        List<Diagnostic> out = new ArrayList<>();
        for (Diagnostic d : diagnostics) {
            if (d.code().equals(code)) {
                out.add(d);
            }
        }
        return out;
    }

    /**
     * The canonical events: all of them outside a finally assembled file; inside one, the canonical single observations
     * and the consolidated events, never the supporting observations a consolidated event was built from.
     */
    public List<Event> canonical() {
        return events.stream().filter(Event::canonical).toList();
    }

    /** Each source's own observations: all events outside an assembled file; inside one, everything but consolidated events. */
    public List<Event> observations() {
        return events.stream().filter(Event::observation).toList();
    }

    /** The canonical events of a class ({@link #canonical()}). */
    public List<Event> events(String className) {
        List<Event> out = new ArrayList<>();
        for (Event e : events) {
            if (className.equals(e.className) && e.canonical()) {
                out.add(e);
            }
        }
        return out;
    }

    /** The canonical events of a class and activity ({@link #canonical()}). */
    public List<Event> events(String className, int activityId) {
        List<Event> out = new ArrayList<>();
        for (Event e : events) {
            if (e.is(className, activityId) && e.canonical()) {
                out.add(e);
            }
        }
        return out;
    }

    /** This package and every linked child, depth first. */
    public List<OcsfPackage> all() {
        List<OcsfPackage> out = new ArrayList<>();
        out.add(this);
        for (OcsfPackage c : children) {
            out.addAll(c.all());
        }
        return out;
    }

    /** {@code main} for a root core, {@code child:<spawn tool-call id>} for a linked child core. */
    public String label() {
        if (manifest == null) {
            return "detached";
        }
        Json parent = manifest.parent();
        if (parent == null) {
            return "main";
        }
        String spawn = SchemaCatalog.text(parent, "spawn_tool_call_uid");
        return "child:" + (spawn == null ? manifest.runUid() : spawn);
    }
}
