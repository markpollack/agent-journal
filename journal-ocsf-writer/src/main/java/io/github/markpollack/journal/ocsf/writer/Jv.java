package io.github.markpollack.journal.ocsf.writer;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.core.StreamReadFeature;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The writer's JSON value: a lexically exact tree. Numbers keep the exact text the source spelled (so {@code 1.0},
 * {@code 1e5} and {@code 9007199254740993} are written back unchanged), object members keep their order, and strings
 * are stored as decoded text. Parsing is strict: one value per text, duplicate object keys are rejected, trailing
 * content is rejected. Jackson's tokenizer does the lexing; nothing else of Jackson is used, and no production DTO
 * or vendor type appears anywhere in the writer.
 */
public sealed interface Jv permits Jv.Obj, Jv.Arr, Jv.Str, Jv.Num, Jv.Bool, Jv.Null {

    final class Obj implements Jv {
        private final Map<String, Jv> members;

        public Obj() {
            this.members = new LinkedHashMap<>();
        }

        public Obj(Map<String, Jv> members) {
            this.members = new LinkedHashMap<>(members);
        }

        public Map<String, Jv> members() {
            return members;
        }

        /** Puts a member; a {@code null} value removes nothing and stores JSON null only when {@code value} is {@link Null}. */
        public Obj put(String key, Jv value) {
            if (value != null) {
                members.put(key, value);
            }
            return this;
        }

        public Obj put(String key, String value) {
            return value == null ? this : put(key, new Str(value));
        }

        public Obj put(String key, long value) {
            return put(key, Num.of(value));
        }

        public Obj put(String key, boolean value) {
            return put(key, new Bool(value));
        }

        public Jv get(String key) {
            return members.get(key);
        }

        public boolean has(String key) {
            return members.containsKey(key);
        }
    }

    final class Arr implements Jv {
        private final List<Jv> elements;

        public Arr() {
            this.elements = new ArrayList<>();
        }

        public Arr(List<? extends Jv> elements) {
            this.elements = new ArrayList<>(elements);
        }

        @Override
        public List<Jv> elements() {
            return elements;
        }

        public Arr add(Jv v) {
            elements.add(v);
            return this;
        }
    }

    record Str(String value) implements Jv {
    }

    /** @param text the number exactly as spelled; {@code value} is its exact decimal value */
    record Num(String text, BigDecimal value) implements Jv {
        public Num(String text) {
            this(text, new BigDecimal(text));
        }

        public static Num of(long v) {
            return new Num(Long.toString(v));
        }

        /** Whether the spelled value is an integer with no fraction (so it may be copied into an integer attribute). */
        public boolean integral() {
            return value.stripTrailingZeros().scale() <= 0;
        }
    }

    record Bool(boolean value) implements Jv {
    }

    record Null() implements Jv {
        public static final Null INSTANCE = new Null();
    }

    static Str str(String s) {
        return new Str(s);
    }

    static Arr arr(String... items) {
        Arr a = new Arr();
        for (String s : items) {
            a.add(new Str(s));
        }
        return a;
    }

    default Jv member(String key) {
        return this instanceof Obj o ? o.get(key) : null;
    }

    default Jv path(String... keys) {
        Jv current = this;
        for (String key : keys) {
            current = current == null ? null : current.member(key);
        }
        return current;
    }

    /** The string value, or {@code null} when this is not a string. */
    default String text() {
        return this instanceof Str s ? s.value() : null;
    }

    default boolean isNull() {
        return this instanceof Null;
    }

    default List<Jv> elements() {
        return this instanceof Arr a ? a.elements() : List.of();
    }

    default boolean isObject() {
        return this instanceof Obj;
    }

    // ------------------------------------------------------------------------------------------ parse / write

    JsonFactory FACTORY = JsonFactory.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build();

    /** @throws IllegalArgumentException if the text is not exactly one JSON value under strict parsing */
    static Jv parse(String text) {
        try (JsonParser parser = FACTORY.createParser(text)) {
            JsonToken first = parser.nextToken();
            if (first == null) {
                throw new IllegalArgumentException("empty JSON text");
            }
            Jv value = read(parser, first);
            if (parser.nextToken() != null) {
                throw new IllegalArgumentException("trailing content after the JSON value");
            }
            return value;
        }
        catch (IOException e) {
            throw new IllegalArgumentException("not JSON: " + e.getMessage(), e);
        }
    }

    private static Jv read(JsonParser parser, JsonToken token) throws IOException {
        switch (token) {
            case START_OBJECT -> {
                Obj o = new Obj();
                while (parser.nextToken() != JsonToken.END_OBJECT) {
                    String key = parser.currentName();
                    o.members().put(key, read(parser, parser.nextToken()));
                }
                return o;
            }
            case START_ARRAY -> {
                Arr a = new Arr();
                JsonToken next;
                while ((next = parser.nextToken()) != JsonToken.END_ARRAY) {
                    a.add(read(parser, next));
                }
                return a;
            }
            case VALUE_STRING -> {
                return new Str(parser.getText());
            }
            case VALUE_NUMBER_INT, VALUE_NUMBER_FLOAT -> {
                return new Num(parser.getText());
            }
            case VALUE_TRUE -> {
                return new Bool(true);
            }
            case VALUE_FALSE -> {
                return new Bool(false);
            }
            case VALUE_NULL -> {
                return Null.INSTANCE;
            }
            default -> throw new IllegalArgumentException("unexpected token " + token);
        }
    }

    /** Compact JSON text; numbers by their spelling, members in order, every non-ASCII character written as itself. */
    static String write(Jv value) {
        StringBuilder sb = new StringBuilder();
        write(value, sb);
        return sb.toString();
    }

    static void write(Jv json, StringBuilder out) {
        if (json instanceof Obj o) {
            out.append('{');
            boolean first = true;
            for (Map.Entry<String, Jv> m : o.members().entrySet()) {
                if (!first) {
                    out.append(',');
                }
                first = false;
                quote(m.getKey(), out);
                out.append(':');
                write(m.getValue(), out);
            }
            out.append('}');
        }
        else if (json instanceof Arr a) {
            out.append('[');
            for (int i = 0; i < a.elements().size(); i++) {
                if (i > 0) {
                    out.append(',');
                }
                write(a.elements().get(i), out);
            }
            out.append(']');
        }
        else if (json instanceof Str s) {
            quote(s.value(), out);
        }
        else if (json instanceof Num n) {
            out.append(n.text());
        }
        else if (json instanceof Bool b) {
            out.append(b.value());
        }
        else {
            out.append("null");
        }
    }

    private static void quote(String value, StringBuilder out) {
        out.append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    }
                    else {
                        out.append(c);
                    }
                }
            }
        }
        out.append('"');
    }

    /** A deep copy (re-parse of the compact text). */
    static Jv copy(Jv v) {
        return parse(write(v));
    }
}
