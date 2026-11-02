package io.github.markpollack.journal.tck.ocsf;
import io.github.markpollack.journal.tck.verify.Json;
import io.github.markpollack.journal.tck.verify.NeutralJson;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Set;
import java.util.TreeMap;
/** Independent terminal byte/scalar interpretation. */
final class TerminalReading {
    private TerminalReading() { }
    static void provider(Json p) {
        fields(p, "kind nativeReason observedExitCode"); oneOf(p, "kind", "REPORTED_SUCCESS REPORTED_FAILURE STOPPED NOT_OBSERVED NOT_DISPATCHED");
        nullable(p, "nativeReason"); if (!p.member("observedExitCode").isNull()) number(p, "observedExitCode");
    }    static void fields(Json object, String names) {
        if (!(object instanceof Json.JObject o) || !o.members().keySet().equals(Set.of(names.split(" ")))) throw new IllegalArgumentException("terminal object shape: " + names);
    }
    static String rawString(Json o, String key) {
        if (!(o.member(key) instanceof Json.JString s)) throw new IllegalArgumentException("expected string: " + key); return s.value();
    }
    static String string(Json o, String key) {
        String text = rawString(o, key); if (text.isEmpty()) throw new IllegalArgumentException("empty identity: " + key); return text;
    }
    static void nullable(Json o, String key) { if (!o.member(key).isNull()) rawString(o, key); }
    static String oneOf(Json o, String key, String values) {
        String v = string(o, key); if (!List.of(values.split(" ")).contains(v)) throw new IllegalArgumentException("unsupported " + key); return v;
    }
    static void expect(Json o, String key, String value) { if (!value.equals(string(o, key))) throw new IllegalArgumentException("mismatched " + key); }
    static long number(Json o, String key) {
        if (!(o.member(key) instanceof Json.JNumber n)) throw new IllegalArgumentException("expected integer"); return n.value().longValueExact();
    }
    static List<Json> array(Json o, String key) {
        if (!(o.member(key) instanceof Json.JArray a)) throw new IllegalArgumentException("expected array"); return a.elements();
    }
    static void instant(String value) {
        if (!value.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}\\.[0-9]{9}Z") || value.startsWith("0000")) throw new IllegalArgumentException("bad instant"); java.time.Instant.parse(value);
    }
    static Json decode(String encoded) {
        byte[] bytes = Base64.getDecoder().decode(encoded);
        if (!Base64.getEncoder().encodeToString(bytes).equals(encoded)) throw new IllegalArgumentException("bad base64");
        try { return exact(StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()); }
        catch (java.nio.charset.CharacterCodingException e) { throw new IllegalArgumentException("bad UTF-8", e); }
    }
    static Json exact(String text) {
        Json value = NeutralJson.parse(text); if (!canonical(value).equals(text)) throw new IllegalArgumentException("noncanonical terminal bytes"); return value;
    }
    static String canonical(Json value) {
        if (value instanceof Json.JObject object) {
            List<String> fields = new ArrayList<>();
            new TreeMap<>(object.members()).forEach((key, child) -> fields.add(quote(key) + ":" + canonical(child)));
            return "{" + String.join(",", fields) + "}";
        }
        if (value instanceof Json.JArray a) return "[" + String.join(",", a.elements().stream().map(TerminalReading::canonical).toList()) + "]";
        if (value instanceof Json.JString s) return quote(s.value());
        if (value instanceof Json.JNull) return "null";
        if (value instanceof Json.JNumber n) return Long.toString(n.value().longValueExact());
        throw new IllegalArgumentException("unsupported terminal value");
    }
    static String quote(String text) {
        StringBuilder out = new StringBuilder("\"");
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isSurrogate(c)) {
                if (!Character.isHighSurrogate(c) || i + 1 == text.length() || !Character.isLowSurrogate(text.charAt(i + 1))) throw new IllegalArgumentException("invalid Unicode scalar");
                out.append(c).append(text.charAt(++i));
            }
            else appendScalar(out, c);
        }
        return out.append('"').toString();
    }
    private static void appendScalar(StringBuilder out, char c) {
        if (c == '"' || c == '\\') out.append('\\').append(c);
        else if (c < 32) out.append(String.format(java.util.Locale.ROOT, "\\u%04x", (int) c));
        else out.append(c);
    }

}
