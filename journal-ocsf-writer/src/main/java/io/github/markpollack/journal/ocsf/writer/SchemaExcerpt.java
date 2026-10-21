package io.github.markpollack.journal.ocsf.writer;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Produces the bundled schema excerpt from a whole {@code compiled.json}: the extension's entry, its classes, every
 * object reachable from them through attribute {@code object_type} references, the scalar type table, and the hash
 * and size of the whole compiled document it was cut from. The excerpt has the same layout as {@code compiled.json}
 * so {@link DefinitionClosure} reads either. It is a derived artifact: regenerate it when the extension changes,
 * never edit it.
 *
 * <p>Usage: {@code SchemaExcerpt <compiled.json> <out.json>}
 */
public final class SchemaExcerpt {

    private SchemaExcerpt() {
    }

    public static void main(String[] args) throws IOException {
        if (args.length != 2) {
            System.err.println("usage: <compiled.json> <out.json>");
            System.exit(64);
        }
        byte[] bytes = Files.readAllBytes(Path.of(args[0]));
        Jv out = excerpt(Jv.parse(new String(bytes, StandardCharsets.UTF_8)), SchemaPin.sha256(bytes), bytes.length);
        Files.writeString(Path.of(args[1]), Jv.write(out) + "\n", StandardCharsets.UTF_8);
        System.out.println("wrote " + args[1] + " (" + Jv.write(out).length() + " chars) from compiled.json sha256 " + SchemaPin.sha256(bytes));
    }

    static Jv excerpt(Jv compiled, String compiledSha256, int compiledBytes) {
        Jv.Obj classes = (Jv.Obj) compiled.member("classes");
        Jv.Obj objects = (Jv.Obj) compiled.member("objects");
        Map<String, Jv> outClasses = new TreeMap<>();
        Deque<String> todo = new ArrayDeque<>();
        for (Map.Entry<String, Jv> c : classes.members().entrySet()) {
            if (c.getKey().startsWith(SchemaPin.EXTENSION + "/")) {
                outClasses.put(c.getKey(), c.getValue());
                references(c.getValue(), todo);
            }
        }
        Map<String, Jv> outObjects = new TreeMap<>();
        Set<String> seen = new LinkedHashSet<>();
        while (!todo.isEmpty()) {
            String key = todo.pop();
            if (!seen.add(key)) {
                continue;
            }
            Jv def = objects.get(key);
            if (def == null) {
                continue;
            }
            outObjects.put(key, def);
            references(def, todo);
        }
        Jv.Obj out = new Jv.Obj();
        out.put("version", compiled.member("version"));
        out.put("excerpt", "classes of extension " + SchemaPin.EXTENSION + ", every object reachable from them, the scalar type table and the extension entry; "
                + "cut from the pinned compiler's whole output, whose hash is source_compiled_schema.sha256");
        out.put("source_compiled_schema", new Jv.Obj().put("sha256", compiledSha256).put("bytes", compiledBytes));
        out.put("extensions", new Jv.Obj().put(SchemaPin.EXTENSION, compiled.path("extensions", SchemaPin.EXTENSION)));
        out.put("classes", new Jv.Obj(outClasses));
        out.put("objects", new Jv.Obj(outObjects));
        out.put("dictionary", new Jv.Obj().put("types", new Jv.Obj().put("attributes", compiled.path("dictionary", "types", "attributes"))));
        return out;
    }

    private static void references(Jv def, Deque<String> todo) {
        Jv attrs = def.member("attributes");
        if (!(attrs instanceof Jv.Obj o)) {
            return;
        }
        for (Jv a : o.members().values()) {
            Jv ot = a.member("object_type");
            if (ot != null && ot.text() != null) {
                todo.push(ot.text());
            }
        }
    }
}
