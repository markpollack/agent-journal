package io.github.markpollack.journal.ocsf.writer;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.HashSet;
/** Shared scalar checks for the terminal codec. */
final class TerminalValues {
    private TerminalValues() { }
    static void provider(Jv p) {
        fields(p, "kind nativeReason observedExitCode"); nullable(p, "nativeReason");
        if (!p.member("observedExitCode").isNull()) number(p, "observedExitCode");
        if (!Set.of("REPORTED_SUCCESS", "REPORTED_FAILURE", "STOPPED", "NOT_OBSERVED", "NOT_DISPATCHED").contains(text(p, "kind"))) throw new IllegalArgumentException("unsupported provider observation");
    }
    static void fields(Jv object, String names) {
        if (!(object instanceof Jv.Obj o) || !o.members().keySet().equals(Set.of(names.split(" ")))) throw new IllegalArgumentException("unexpected/missing object members: " + names);
    }
    static String string(Jv o, String key) {
        if (!(o.member(key) instanceof Jv.Str s)) throw new IllegalArgumentException("expected string: " + key);
        return s.value();
    }
    static String text(Jv o, String key) {
        String value = string(o, key);
        if (value.isEmpty() || value.length() > 4096) throw new IllegalArgumentException("empty/oversize identifier: " + key);
        return value;
    }
    static void nullable(Jv o, String key) { if (!o.member(key).isNull()) string(o, key); }
    static void equal(Jv o, String key, String expected) {
        if (!expected.equals(text(o, key))) throw new IllegalArgumentException("mismatched " + key);
    }
    static long number(Jv o, String key) {
        if (!(o.member(key) instanceof Jv.Num n)) throw new IllegalArgumentException("expected integer: " + key);
        return n.value().longValueExact();
    }
    static List<Jv> array(Jv o, String key) {
        if (!(o.member(key) instanceof Jv.Arr a)) throw new IllegalArgumentException("expected array: " + key);
        return a.elements();
    }
    static void instant(Jv o, String key) {
        String value = text(o, key);
        if (!value.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}\\.[0-9]{9}Z") || value.startsWith("0000")) throw new IllegalArgumentException("invalid instant");
        Instant.parse(value);
    }
    static Path relative(String text) {
        Path path = Path.of(text);
        if (text.isEmpty() || path.isAbsolute() || !path.normalize().equals(path) || text.contains("\\") || !path.toString().equals(text) || text.equals(".") || path.startsWith("..")) throw new IllegalArgumentException("unsafe relative path");
        return path;
    }
}
