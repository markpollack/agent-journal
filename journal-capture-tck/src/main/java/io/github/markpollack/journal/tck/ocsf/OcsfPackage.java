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

    public List<Event> events(String className) {
        List<Event> out = new ArrayList<>();
        for (Event e : events) {
            if (className.equals(e.className)) {
                out.add(e);
            }
        }
        return out;
    }

    public List<Event> events(String className, int activityId) {
        List<Event> out = new ArrayList<>();
        for (Event e : events) {
            if (e.is(className, activityId)) {
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
