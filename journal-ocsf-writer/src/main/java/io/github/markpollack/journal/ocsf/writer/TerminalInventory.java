package io.github.markpollack.journal.ocsf.writer;
import java.io.IOException;
/** Complete exact source/artifact closure for a sealed publication. */
final class TerminalInventory {
    private TerminalInventory() { }
    static void check(Jv ack, Jv association) throws IOException {
        java.util.Map<String, Jv> actual = inventory(ack);
        String events = canonicalPath(actual.keySet());
        String prefix = events.substring(0, events.length() - "events.jsonl".length()) + "sources/";
        java.util.Set<String> required = new java.util.HashSet<>(); required.add(events);
        for (Jv source : TerminalValues.array(association, "sources")) {
            String path = prefix + TerminalValues.text(source, "relativePath"); required.add(path);
            Jv item = actual.get(path);
            if (item == null || !TerminalValues.text(source, "sha256").equals(TerminalValues.text(item, "sha256"))
                    || TerminalValues.number(source, "size") != TerminalValues.number(item, "size")) throw new TerminalIntegrityException("association artifact inventory mismatch");
        }
        if (!actual.keySet().equals(required)) throw new TerminalIntegrityException("unexpected artifact inventory entry");
    }

    private static java.util.Map<String, Jv> inventory(Jv ack) throws IOException {
        java.util.Map<String, Jv> actual = new java.util.LinkedHashMap<>();
        for (Jv item : TerminalValues.array(ack, "artifacts")) {
            TerminalValues.fields(item, "relativePath sha256 size");
            if (actual.putIfAbsent(TerminalValues.text(item, "relativePath"), item) != null) throw new TerminalIntegrityException("duplicate artifact inventory path");
        }
        return actual;
    }
    private static String canonicalPath(java.util.Set<String> paths) throws IOException {
        String events = null;
        for (String path : paths) {
            if (path.matches("prepared/candidate-[^/]+/events[.]jsonl")) {
                if (events != null) throw new TerminalIntegrityException("multiple canonical artifacts");
                events = path;
            }
        }
        if (events == null) throw new TerminalIntegrityException("missing canonical artifact");
        return events;
    }

}
