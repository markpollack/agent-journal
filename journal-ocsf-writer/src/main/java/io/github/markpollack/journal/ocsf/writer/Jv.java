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

    /**
     * An exact location in one parsed JSON text: from the root, each step is an object member (its name and its
     * occurrence among the members of that object with the same name, 1 for the first) or an array element (its index).
     * Unlike a dotted path it cannot confuse the member {@code item.id} of the root with the member {@code id} of the
     * root's {@code item}, and unlike a JSON Pointer alone it tells repeated members of one object apart.
     */
    record Location(List<Step> steps) {
        static final Location ROOT = new Location(List.of());

        /** One step: {@code member} with its {@code occurrence} (1 = first), or array element {@code index} when {@code member} is {@code null}. */
        record Step(String member, int occurrence, int index) {
        }

        Location member(String name, int occurrence) {
            List<Step> s = new ArrayList<>(steps);
            s.add(new Step(name, occurrence, -1));
            return new Location(List.copyOf(s));
        }

        Location element(int index) {
            List<Step> s = new ArrayList<>(steps);
            s.add(new Step(null, 0, index));
            return new Location(List.copyOf(s));
        }

        /** The RFC 6901 JSON Pointer of this location ({@code ~} as {@code ~0}, {@code /} as {@code ~1}); it does not carry occurrences. */
        String pointer() {
            StringBuilder sb = new StringBuilder();
            for (Step st : steps) {
                sb.append('/').append(st.member() == null ? Integer.toString(st.index()) : st.member().replace("~", "~0").replace("/", "~1"));
            }
            return sb.toString();
        }
    }

    /** One object member that occurs more than once: the exact location of its containing object, its name and every value in source order. */
    record Repeated(Location container, String member, List<Jv> values) {
        /** The member's RFC 6901 JSON Pointer; equal for every occurrence, which is why {@link #values} keeps them all. */
        String pointer() {
            return container.member(member, 1).pointer();
        }
    }

    /** A value whose repeated object members were recorded, not merged: the tree holds each member's first occurrence. */
    record WithDuplicates(Jv value, List<Repeated> repeated) {
    }

    /**
     * Parses one JSON value while recording every repeated object member, at its exact location, instead of rejecting
     * the text. This is not the reader's mode: {@link #parse} stays strict, and a caller must accept only a duplicate
     * form it has declared (every value is kept in {@link Repeated#values}; nothing is silently overwritten).
     *
     * @throws IllegalArgumentException if the text is not exactly one JSON value apart from repeated members
     */
    static WithDuplicates parseRecordingDuplicates(String text) {
        JsonFactory lenient = JsonFactory.builder().disable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build();
        try (JsonParser parser = lenient.createParser(text)) {
            JsonToken first = parser.nextToken();
            if (first == null) {
                throw new IllegalArgumentException("empty JSON text");
            }
            List<Repeated> repeated = new ArrayList<>();
            Jv value = read(parser, first, Location.ROOT, repeated);
            if (parser.nextToken() != null) {
                throw new IllegalArgumentException("trailing content after the JSON value");
            }
            return new WithDuplicates(value, List.copyOf(repeated));
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
            default -> {
                return scalar(parser, token);
            }
        }
    }

    /** As {@link #read(JsonParser, JsonToken)}, keeping each member's first value and recording every repeated member with all its values. */
    private static Jv read(JsonParser parser, JsonToken token, Location at, List<Repeated> repeated) throws IOException {
        switch (token) {
            case START_OBJECT -> {
                Obj o = new Obj();
                Map<String, List<Jv>> values = new LinkedHashMap<>();
                while (parser.nextToken() != JsonToken.END_OBJECT) {
                    String key = parser.currentName();
                    List<Jv> seen = values.computeIfAbsent(key, k -> new ArrayList<>());
                    Jv v = read(parser, parser.nextToken(), at.member(key, seen.size() + 1), repeated);
                    seen.add(v);
                    if (seen.size() == 1) {
                        o.members().put(key, v);
                    }
                }
                values.forEach((key, vs) -> {
                    if (vs.size() > 1) {
                        repeated.add(new Repeated(at, key, List.copyOf(vs)));
                    }
                });
                return o;
            }
            case START_ARRAY -> {
                Arr a = new Arr();
                JsonToken next;
                int i = 0;
                while ((next = parser.nextToken()) != JsonToken.END_ARRAY) {
                    a.add(read(parser, next, at.element(i++), repeated));
                }
                return a;
            }
            default -> {
                return scalar(parser, token);
            }
        }
    }

    private static Jv scalar(JsonParser parser, JsonToken token) throws IOException {
        switch (token) {
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
