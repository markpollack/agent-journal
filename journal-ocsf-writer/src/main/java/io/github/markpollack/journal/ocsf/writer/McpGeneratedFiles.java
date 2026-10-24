package io.github.markpollack.journal.ocsf.writer;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Reading, formatting, comparing and writing the files {@link McpBindingGenerator} generates. */
final class McpGeneratedFiles {

    private McpGeneratedFiles() {
    }

    static String read(Path p) {
        try {
            return Files.readString(p, StandardCharsets.UTF_8);
        }
        catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Pretty JSON as the hand-written extension files are written (Python {@code json.dumps(indent=2, ensure_ascii=False)}). */
    static String pretty(Jv v) {
        StringBuilder out = new StringBuilder();
        pretty(v, out, 0);
        return out.append('\n').toString();
    }

    private static void pretty(Jv v, StringBuilder out, int depth) {
        List<Map.Entry<String, Jv>> entries = new ArrayList<>();
        if (v instanceof Jv.Obj o) {
            entries.addAll(o.members().entrySet());
        }
        else if (v instanceof Jv.Arr a) {
            a.elements().forEach(e -> entries.add(Map.entry("", e)));
        }
        if (entries.isEmpty()) {
            Jv.write(v, out);
            return;
        }
        boolean object = v instanceof Jv.Obj;
        out.append(object ? "{\n" : "[\n");
        String pad = "  ".repeat(depth + 1);
        for (int i = 0; i < entries.size(); i++) {
            out.append(pad);
            if (object) {
                Jv.write(new Jv.Str(entries.get(i).getKey()), out);
                out.append(": ");
            }
            pretty(entries.get(i).getValue(), out, depth + 1);
            out.append(i + 1 < entries.size() ? ",\n" : "\n");
        }
        out.append("  ".repeat(depth)).append(object ? '}' : ']');
    }

    static List<String> differences(Path root, Map<String, String> files, String objectDir) {
        List<String> out = new ArrayList<>();
        for (Map.Entry<String, String> f : files.entrySet()) {
            Path p = root.resolve(f.getKey());
            if (!Files.exists(p)) {
                out.add("missing " + f.getKey());
            }
            else if (!read(p).equals(f.getValue())) {
                out.add("differs " + f.getKey());
            }
        }
        stale(root, files, objectDir).forEach(s -> out.add("stale " + s));
        return out;
    }

    private static List<String> stale(Path root, Map<String, String> files, String objectDir) {
        Path dir = root.resolve(objectDir);
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        try (var s = Files.list(dir)) {
            return s.map(p -> objectDir + "/" + p.getFileName()).filter(p -> !files.containsKey(p)).sorted().toList();
        }
        catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static void write(Path root, Map<String, String> files, String objectDir) {
        try {
            for (String stale : stale(root, files, objectDir)) {
                Files.delete(root.resolve(stale));
            }
            for (Map.Entry<String, String> f : files.entrySet()) {
                Path p = root.resolve(f.getKey());
                Files.createDirectories(p.getParent());
                Files.writeString(p, f.getValue(), StandardCharsets.UTF_8);
            }
        }
        catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

}
