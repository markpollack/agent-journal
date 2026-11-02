package io.github.markpollack.journal.ocsf.writer;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;

/** Exact JSON encoding for the terminal wire contract, independent of the event-file JSON encoding. */
final class TerminalJson {
    static final int MAX_BYTES = 1024 * 1024;
    private TerminalJson() { }

    static Jv read(byte[] bytes) {
        if (bytes.length > MAX_BYTES) throw new IllegalArgumentException("terminal payload exceeds 1 MiB");
        try {
            String text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
            Jv value = Jv.parse(text);
            if (!text.equals(write(value))) throw new IllegalArgumentException("noncanonical terminal JSON");
            return value;
        }
        catch (CharacterCodingException e) { throw new IllegalArgumentException("invalid UTF-8", e); }
    }

    static String write(Jv value) { return encode(value, 0); }

    private static String encode(Jv value, int depth) {
        if (depth > 32) throw new IllegalArgumentException("terminal JSON nesting exceeds 32");
        if (value instanceof Jv.Obj object) {
            List<String> fields = new ArrayList<>();
            new TreeMap<>(object.members()).forEach((key, item) -> fields.add(quote(key) + ":" + encode(item, depth + 1)));
            return "{" + String.join(",", fields) + "}";
        }
        if (value instanceof Jv.Arr array) {
            List<String> items = new ArrayList<>();
            for (Jv item : array.elements()) items.add(encode(item, depth + 1));
            return "[" + String.join(",", items) + "]";
        }
        return scalar(value);
    }

    private static String scalar(Jv value) {
        if (value instanceof Jv.Str string) return quote(string.value());
        if (value instanceof Jv.Num number) {
            long n = number.value().longValueExact();
            if (!number.text().equals(Long.toString(n))) throw new IllegalArgumentException("noncanonical integer");
            return number.text();
        }
        if (value instanceof Jv.Null) return "null";
        throw new IllegalArgumentException("unsupported terminal JSON type");
    }

    private static String quote(String value) {
        StringBuilder out = new StringBuilder("\"");
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (Character.isHighSurrogate(c)) {
                if (++i == value.length() || !Character.isLowSurrogate(value.charAt(i)))
                    throw new IllegalArgumentException("unpaired surrogate");
                out.append(c).append(value.charAt(i));
            }
            else if (Character.isLowSurrogate(c)) throw new IllegalArgumentException("unpaired surrogate");
            else appendScalar(out, c);
        }
        return out.append('\"').toString();
    }
    private static void appendScalar(StringBuilder out, char c) {
        if (c == '"' || c == '\\') out.append('\\').append(c);
        else if (c < 32) out.append(String.format(java.util.Locale.ROOT, "\\u%04x", (int) c));
        else out.append(c);
    }

}
